package top.colter.dynamic.qqbot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.TargetKind

class QqBotIncomingMessageMapperTest {
    @Test
    fun `单聊消息映射为用户入站消息`() {
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
    fun `群 at 消息带附件映射为群入站消息`() {
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
    fun `群普通消息映射为群入站消息且不标记提及`() {
        val incoming = QqBotIncomingMessageMapper.toIncomingMessage(
            QqBotIncomingEvent(
                type = QQBOT_GROUP_MESSAGE_CREATE,
                botAccountId = "bot-app-id",
                rawPayload = """
                    {
                      "op":0,
                      "s":2,
                      "t":"GROUP_MESSAGE_CREATE",
                      "id":"GROUP_MESSAGE_CREATE:event-id",
                      "d":{
                        "id":"group-msg-2",
                        "group_id":"2200000000000000F189092",
                        "group_openid":"2200000000000000F189092",
                        "author":{
                          "member_openid":"member-openid",
                          "union_openid":"member-openid",
                          "username":"Colter"
                        },
                        "content":"测试",
                        "message_type":0,
                        "timestamp":"2026-06-19T17:35:57+08:00"
                      }
                    }
                """.trimIndent(),
            ),
        )

        requireNotNull(incoming)
        assertEquals(TargetKind.GROUP, incoming.target.kind)
        assertEquals("2200000000000000F189092", incoming.target.externalId)
        assertEquals("member-openid", incoming.senderId)
        assertEquals("group-msg-2", incoming.messageId)
        assertEquals("测试", incoming.text)
        assertEquals(emptySet(), incoming.mentions)
    }

    @Test
    fun `群普通消息包含 at 时解析提及账号`() {
        val incoming = QqBotIncomingMessageMapper.toIncomingMessage(
            QqBotIncomingEvent(
                type = QQBOT_GROUP_MESSAGE_CREATE,
                botAccountId = "bot-app-id",
                rawPayload = """
                    {
                      "op":0,
                      "s":3,
                      "t":"GROUP_MESSAGE_CREATE",
                      "id":"GROUP_MESSAGE_CREATE:event-id",
                      "d":{
                        "id":"group-msg-3",
                        "group_openid":"group-openid",
                        "author":{"member_openid":"member-openid"},
                        "content":"<@bot-member-openid> 测试",
                        "mentions":[
                          {
                            "bot":true,
                            "id":"bot-member-openid",
                            "is_you":true,
                            "member_openid":"bot-member-openid",
                            "username":"PuniBot-测试中"
                          }
                        ],
                        "message_type":0
                      }
                    }
                """.trimIndent(),
            ),
        )

        requireNotNull(incoming)
        assertEquals("<@bot-member-openid> 测试", incoming.text)
        assertEquals(setOf("bot-app-id"), incoming.mentions)
        assertEquals(2, incoming.segments.size)
        val mention = assertIs<IncomingMessageSegment.Mention>(incoming.segments[0])
        assertEquals("bot-app-id", mention.targetId)
        assertEquals(" 测试", assertIs<IncomingMessageSegment.Text>(incoming.segments[1]).text)
    }

    @Test
    fun `群普通消息带图片附件时映射为图片消息段`() {
        val incoming = QqBotIncomingMessageMapper.toIncomingMessage(
            QqBotIncomingEvent(
                type = QQBOT_GROUP_MESSAGE_CREATE,
                botAccountId = "bot-app-id",
                rawPayload = """
                    {
                      "op":0,
                      "s":5,
                      "t":"GROUP_MESSAGE_CREATE",
                      "id":"GROUP_MESSAGE_CREATE:event-id",
                      "d":{
                        "id":"group-msg-with-image",
                        "group_openid":"group-openid",
                        "author":{"member_openid":"member-openid","username":"Colter"},
                        "content":"测试",
                        "attachments":[
                          {
                            "content":"",
                            "content_type":"image/jpeg",
                            "filename":"EBA1A8BA5ACE6A73B5406D86A04E85FF.jpg",
                            "height":1000,
                            "size":47836,
                            "url":"https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=image-file-id&rkey=image-rkey&spec=0",
                            "width":1000
                          }
                        ],
                        "message_type":0,
                        "timestamp":"2026-06-19T18:27:07+08:00"
                      }
                    }
                """.trimIndent(),
            ),
        )

        requireNotNull(incoming)
        assertEquals(TargetKind.GROUP, incoming.target.kind)
        assertEquals("group-openid", incoming.target.externalId)
        assertEquals("测试", incoming.text)
        assertEquals(2, incoming.segments.size)
        assertEquals("测试", assertIs<IncomingMessageSegment.Text>(incoming.segments[0]).text)
        val image = assertIs<IncomingMessageSegment.Image>(incoming.segments[1])
        assertEquals("EBA1A8BA5ACE6A73B5406D86A04E85FF.jpg", image.file)
        assertEquals("https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=image-file-id&rkey=image-rkey&spec=0", image.url)
    }

    @Test
    fun `不支持的事件返回 null`() {
        val incoming = QqBotIncomingMessageMapper.toIncomingMessage(
            QqBotIncomingEvent(
                type = "GROUP_ADD_ROBOT",
                rawPayload = """{"op":0,"s":3,"t":"GROUP_ADD_ROBOT","d":{"group_openid":"g"}}""",
            ),
        )

        assertNull(incoming)
    }
}
