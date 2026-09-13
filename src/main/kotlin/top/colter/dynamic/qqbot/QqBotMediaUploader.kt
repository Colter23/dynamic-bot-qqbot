package top.colter.dynamic.qqbot

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

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

        var offset = 0L
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            ticket.parts.forEach { part ->
                require(part.blockSizeBytes in 1..MAX_PART_BYTES) {
                    "QQ 官方机器人分片大小超出支持范围：index=${part.index} size=${part.blockSizeBytes}"
                }
                val bytes = channel.readBytes(offset, part.blockSizeBytes)
                require(bytes.isNotEmpty()) { "QQ 官方机器人分片读取为空：index=${part.index}" }
                client.putUploadPart(part.presignedUrl, bytes)
                client.finishUploadPart(
                    scope = scope,
                    request = QqBotUploadPartFinishRequest(
                        uploadId = ticket.uploadId,
                        partIndex = part.index,
                        blockSizeBytes = bytes.size.toLong(),
                        md5 = QqBotFileHasher.md5(bytes),
                    ),
                )
                offset += bytes.size
            }
        }
        // 分片必须完整覆盖整个文件；服务端若下发空洞或跳片（如秒传），继续合并会上传不完整数据
        require(offset == hashes.sizeBytes) {
            "QQ 官方机器人分片未覆盖整个文件：expected=${hashes.sizeBytes} uploaded=$offset parts=${ticket.parts.size}"
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
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
