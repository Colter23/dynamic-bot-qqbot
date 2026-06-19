package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.TargetKind

class QqBotIncomingMessageMapperTest {
    @Test
    fun `c2c message maps to user incoming message`() {
        val incoming = QqBotIncomingMessageMapper.toIncomingMessage(
            QqBotIncomingEvent(
                type = QQBOT_C2C_MESSAGE_CREATE,
                botAccountId = "bot-app-id",
                rawPayload = """
                    {
                      "op":0,
                      "s":1,
                      "t":"C2C_MESSAGE_CREATE",
                      "d":{
                        "id":"msg-1",
                        "author":{"user_openid":"user-openid"},
                        "content":"hello",
                        "timestamp":"2026-06-19T04:00:00Z"
                      }
                    }
                """.trimIndent(),
            ),
        )

        requireNotNull(incoming)
        assertEquals(QQBOT_PLATFORM_ID, incoming.platformId)
        assertEquals(TargetKind.USER, incoming.target.kind)
        assertEquals("user-openid", incoming.target.externalId)
        assertEquals("bot-app-id", incoming.target.accountId)
        assertEquals("user-openid", incoming.senderId)
        assertEquals("bot-app-id", incoming.botAccountId)
        assertEquals("msg-1", incoming.messageId)
        assertEquals(1_781_841_600L, incoming.timestamp)
        assertEquals("hello", incoming.text)
        assertEquals(QQBOT_RAW_FORMAT, incoming.rawFormat)
        assertEquals("hello", assertIs<IncomingMessageSegment.Text>(incoming.segments.single()).text)
    }

    @Test
    fun `group at message maps to group incoming message with attachments`() {
        val incoming = QqBotIncomingMessageMapper.toIncomingMessage(
            QqBotIncomingEvent(
                type = QQBOT_GROUP_AT_MESSAGE_CREATE,
                botAccountId = "bot-app-id",
                rawPayload = """
                    {
                      "op":0,
                      "s":2,
                      "t":"GROUP_AT_MESSAGE_CREATE",
                      "d":{
                        "id":"group-msg-1",
                        "group_openid":"group-openid",
                        "author":{"member_openid":"member-openid"},
                        "content":"@bot 看图",
                        "timestamp":"2026-06-19T04:00:30Z",
                        "attachments":[
                          {"content_type":"image/png","filename":"a.png","url":"https://example.com/a.png"},
                          {"content_type":"video/mp4","filename":"b.mp4","url":"https://example.com/b.mp4"},
                          {"content_type":"application/pdf","filename":"c.pdf","url":"https://example.com/c.pdf"}
                        ]
                      }
                    }
                """.trimIndent(),
            ),
        )

        requireNotNull(incoming)
        assertEquals(TargetKind.GROUP, incoming.target.kind)
        assertEquals("group-openid", incoming.target.externalId)
        assertEquals("member-openid", incoming.senderId)
        assertEquals("group-msg-1", incoming.messageId)
        assertEquals(1_781_841_630L, incoming.timestamp)
        assertEquals("@bot 看图", incoming.text)
        assertEquals(setOf("bot-app-id"), incoming.mentions)
        assertEquals(4, incoming.segments.size)
        assertEquals("@bot 看图", assertIs<IncomingMessageSegment.Text>(incoming.segments[0]).text)
        assertEquals("https://example.com/a.png", assertIs<IncomingMessageSegment.Image>(incoming.segments[1]).url)
        assertEquals("https://example.com/b.mp4", assertIs<IncomingMessageSegment.Video>(incoming.segments[2]).url)
        assertEquals("application/pdf", assertIs<IncomingMessageSegment.Unknown>(incoming.segments[3]).segmentType)
    }

    @Test
    fun `unsupported event returns null`() {
        val incoming = QqBotIncomingMessageMapper.toIncomingMessage(
            QqBotIncomingEvent(
                type = "GROUP_ADD_ROBOT",
                rawPayload = """{"op":0,"s":3,"t":"GROUP_ADD_ROBOT","d":{"group_openid":"g"}}""",
            ),
        )

        assertNull(incoming)
    }
}
