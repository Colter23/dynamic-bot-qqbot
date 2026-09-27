package top.colter.dynamic.qqbot

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import top.colter.dynamic.core.tools.loggerFor
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val mediaLogger = loggerFor<QqBotMediaUploader>()

internal data class QqBotFileHashes(
    val sizeBytes: Long,
    val md5: String,
    val sha1: String,
    val md5Prefix: String,
)

/**
 * 计算分片上传需要的校验值：整文件 MD5、整文件 SHA1、前 [PREFIX_BYTES] 字节的 MD5。
 *
 * 一次遍历同时算出三个值，避免对大文件多次读盘。
 */
internal object QqBotFileHasher {
    /** 官方 `md5_10m` 定义：文件前 10002432 字节（约 9.54 MB）的 MD5。 */
    internal const val PREFIX_BYTES: Long = 10_002_432L

    private const val BUFFER_BYTES: Int = 64 * 1024

    fun hash(path: Path): QqBotFileHashes {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val md5Prefix = MessageDigest.getInstance("MD5")
        var total = 0L
        var prefixRemaining = PREFIX_BYTES

        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md5.update(buffer, 0, read)
                sha1.update(buffer, 0, read)
                if (prefixRemaining > 0) {
                    val take = minOf(prefixRemaining, read.toLong()).toInt()
                    md5Prefix.update(buffer, 0, take)
                    prefixRemaining -= take
                }
                total += read
            }
        }

        return QqBotFileHashes(
            sizeBytes = total,
            md5 = md5.digest().toHex(),
            sha1 = sha1.digest().toHex(),
            md5Prefix = md5Prefix.digest().toHex(),
        )
    }

    fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).toHex()
}

/**
 * 官方推荐的分片上传流程编排：
 *
 * 1. `upload_prepare` 预上传，拿到 `upload_id` 与各分片预签名 URL；
 * 2. 按分片大小逐片 HTTP PUT 到预签名 URL；
 * 3. 每片成功后调用 `upload_part_finish` 确认；
 * 4. 全部分片完成后携带 `upload_id` 调上传接口合并，返回 `file_info`。
 *
 * 适用于本地文件，无需公网可访问地址。
 */
internal class QqBotMediaUploader(
    private val client: QqBotOpenApiClient,
) {
    fun upload(
        scope: QqBotOpenApiScope,
        fileType: QqBotMediaFileType,
        path: Path,
        fileName: String,
    ): QqBotMediaResource {
        val hashes = QqBotFileHasher.hash(path)
        val ticket = client.prepareUpload(
            scope = scope,
            request = QqBotUploadPrepareRequest(
                fileType = fileType,
                fileSizeBytes = hashes.sizeBytes,
                fileName = fileName,
                md5 = hashes.md5,
                sha1 = hashes.sha1,
                md5Prefix = hashes.md5Prefix,
            ),
        )
        val chunks = buildChunks(ticket, hashes.sizeBytes)
        val concurrency = ticket.concurrency.coerceIn(1, chunks.size.coerceAtLeast(1))
        val executor = Executors.newFixedThreadPool(concurrency) { runnable ->
            Thread(runnable, "qqbot-media-upload").apply { isDaemon = true }
        }
        try {
            val futures = chunks.map { chunk ->
                executor.submit {
                    uploadChunkWithRetry(scope, ticket, path, chunk)
                }
            }
            futures.forEach { future ->
                try {
                    future.get()
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }
        } finally {
            executor.shutdownNow()
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        return client.prepareMedia(
            scope = scope,
            request = QqBotMediaPrepareRequest(
                fileType = fileType,
                uploadId = ticket.uploadId,
                fileName = fileName,
            ),
        )
    }

    private fun buildChunks(ticket: QqBotUploadTicket, fileSizeBytes: Long): List<QqBotUploadChunk> {
        var offset = 0L
        val chunks = ticket.parts.mapNotNull { part ->
            require(part.blockSizeBytes in 1..MAX_PART_BYTES) {
                "QQ 官方机器人分片大小超出支持范围：index=${part.index} size=${part.blockSizeBytes}"
            }
            if (offset >= fileSizeBytes) return@mapNotNull null
            val sizeBytes = minOf(part.blockSizeBytes, fileSizeBytes - offset)
            QqBotUploadChunk(part = part, offset = offset, sizeBytes = sizeBytes).also {
                offset += sizeBytes
            }
        }
        require(offset == fileSizeBytes) {
            "QQ 官方机器人分片未覆盖整个文件：expected=$fileSizeBytes planned=$offset parts=${ticket.parts.size}"
        }
        return chunks
    }

    private fun uploadChunkWithRetry(
        scope: QqBotOpenApiScope,
        ticket: QqBotUploadTicket,
        path: Path,
        chunk: QqBotUploadChunk,
    ) {
        val deadline = System.nanoTime() + ticket.retryTimeoutSeconds.coerceAtLeast(0L) * NANOS_PER_SECOND
        var attempt = 0
        while (true) {
            try {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val bytes = channel.readBytes(chunk.offset, chunk.sizeBytes)
                    require(bytes.size.toLong() == chunk.sizeBytes) {
                        "QQ 官方机器人分片读取大小不符：index=${chunk.part.index} expected=${chunk.sizeBytes} actual=${bytes.size}"
                    }
                    client.putUploadPart(chunk.part.presignedUrl, bytes)
                    client.finishUploadPart(
                        scope = scope,
                        request = QqBotUploadPartFinishRequest(
                            uploadId = ticket.uploadId,
                            partIndex = chunk.part.index,
                            blockSizeBytes = bytes.size.toLong(),
                            md5 = QqBotFileHasher.md5(bytes),
                        ),
                    )
                }
                return
            } catch (error: Throwable) {
                if (error is InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L || !isRetryableUploadFailure(error)) throw error
                attempt += 1
                val delayMillis = ticket.retryDelaySeconds.coerceAtLeast(1L) * MILLIS_PER_SECOND
                val sleepMillis = minOf(delayMillis, (remaining / NANOS_PER_MILLISECOND).coerceAtLeast(1L))
                mediaLogger.warn(error) {
                    "QQ 官方机器人分片上传失败，将重试：index=${chunk.part.index} attempt=$attempt delayMs=$sleepMillis"
                }
                try {
                    Thread.sleep(sleepMillis)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        }
    }

    private fun isRetryableUploadFailure(error: Throwable): Boolean {
        val api = generateSequence(error) { it.cause }
            .filterIsInstance<QqBotOpenApiException>()
            .firstOrNull()
        if (api != null) {
            return api.retryable && (
                api.transientUploadFailure ||
                    api.statusCode == 429 ||
                    api.statusCode >= 500
                )
        }
        return generateSequence(error) { it.cause }.any {
            it is IOException || it is HttpTimeoutException
        }
    }
    private fun FileChannel.readBytes(offset: Long, sizeBytes: Long): ByteArray {
        val length = sizeBytes.coerceAtMost(MAX_PART_BYTES).toInt()
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = read(buffer, position)
            if (read < 0) break
            position += read
        }
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }

    private companion object {
        private const val MAX_PART_BYTES: Long = 32L * 1024 * 1024
        private const val MILLIS_PER_SECOND: Long = 1_000L
        private const val NANOS_PER_SECOND: Long = 1_000_000_000L
        private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    }

    private data class QqBotUploadChunk(
        val part: QqBotUploadPart,
        val offset: Long,
        val sizeBytes: Long,
    )
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
