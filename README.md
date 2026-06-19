# dynamic-bot-qqbot

QQ 官方机器人插件，用于通过 QQ 官方 API v2 接入 dynamic-bot。

当前已接入 WebSocket 网关能力：可获取访问令牌与网关地址、连接官方 WebSocket、处理 Hello 后 Identify、发送心跳并分发 Dispatch 事件；已支持将单聊/群聊消息事件映射为 dynamic-bot 标准入站消息，并可通过 OpenAPI 发送、撤回单聊/群聊文本消息。WebSocket 断开后支持基础自动重连，并会在 READY 后记录 `session_id`，重连时优先尝试 Resume；图片、视频、语音已支持使用 HTTP(S) URL 或 `base64://` 数据先登记富媒体资源再发送。

## 当前包含

- 独立 Gradle 插件项目。
- `plugin.yml` 插件描述。
- 插件生命周期与配置表单。
- QQ 官方机器人目标类型占位：单聊用户、群聊。
- WebSocket opcode、Identify、Heartbeat、Hello、Dispatch 协议模型。
- WebSocket READY 会话记录与基础 Resume 恢复。
- 访问令牌获取、缓存刷新、`/gateway/bot` 接入点获取组件。
- 基于 Java 标准库 `java.net.http.WebSocket` 的正向 WebSocket 网关实现。
- 单聊/群聊文本消息发送、URL/base64 富媒体发送、命令结果发送与消息撤回。
- 入站消息提交到主项目统一管线，由主项目识别命令、链接和普通消息。
- 富媒体 `/files` 请求模型已支持 `file_data` base64 字段；媒体投递建议会禁用本地文件直传，引导主程序自动落到 base64 兜底。
- 目标列表的接口骨架。
- 插件边界测试，避免直接依赖主程序运行时实现。

## 当前接入流程

1. 使用 `AppID` 和 `AppSecret` 获取访问令牌。
2. 通过 `/gateway` 或 `/gateway/bot` 获取 WebSocket 地址。
3. 连接 QQ 官方 WebSocket，处理 Hello、Identify、Heartbeat、Dispatch。
4. 将 `C2C_MESSAGE_CREATE`、`GROUP_MESSAGE_CREATE` 和 `GROUP_AT_MESSAGE_CREATE` 映射为 dynamic-bot 标准入站消息。
5. 文本消息直接调用 OpenAPI 回复单聊和群聊。
6. 图片、视频、语音先调用 `/files` 登记 HTTP(S) URL 或 `base64://` 数据，拿到 `file_info` 后用 `msg_type = 7` 发送。
7. 官网当前富媒体文档已列出 `file_data` base64 字段，但 `url` 仍标记必填；仅传 `file_data` 的正式环境兼容性仍需实测确认。
8. 后续再补文件类型、按钮和更细致的错误重试。

## 构建与测试

```powershell
.\gradlew.bat test
.\gradlew.bat fatJar --offline
```

生成的 `*-all.jar` 放入主程序 `plugins/` 后，再由后台启用插件。

## 配置项

插件默认未启用。启用前至少需要：

```yaml
enabled: true
appId: "你的 AppID"
appSecret: "你的 AppSecret"
intents:
  - GROUP_AND_C2C_EVENT
reconnect: true
autoPublishIncoming: true
```

`autoPublishIncoming` 开启后，插件只发布标准入站消息；命令前缀匹配、群聊开头 at 当前 Bot 的规范化、链接识别和普通消息插件过滤都由主项目管线完成。平台原始消息 ID 会作为回复目标传入命令结果发送链路，便于 QQ 官方接口按原消息回复。

## 连通性调试

如果机器人已启动但没有回复，可以先临时打开原始载荷日志和调试回显：

```yaml
debugLogPayloads: true
debugEchoText: "ping"
debugEchoReply: "QQ 官方机器人 WebSocket 连通性正常"
debugImageEchoText: "发图"
debugImageEchoSource: "base64://这里填写图片二进制的base64"
debugImageEchoCaption: "QQ 官方机器人图片发送测试"
```

然后在单聊发送 `ping`，或在群聊中按实际收到的文本配置触发内容。群聊消息如果以 `<@机器人> ` 开头，调试回显匹配时会自动忽略这个开头 at。日志中应至少能看到 WebSocket 打开、Hello、Identify、READY、Dispatch、入站消息解析；如果能收到入站消息但回显失败，继续看 OpenAPI 发送失败日志。排查完成后建议关闭 `debugLogPayloads`，避免日志中长期记录原始消息载荷。

图片发送可以临时配置 `debugImageEchoText` 和 `debugImageEchoSource`。`debugImageEchoSource` 支持 HTTP(S) URL 或 `base64://` 数据；部署机器没有公网 IP 时，优先用 `base64://` 验证官方 `file_data` 路径。触发后日志中应看到“调试图片回显已触发”和“调试图片发送成功”；如果失败，错误日志会包含 `/files` 登记或消息发送接口返回的业务错误。

频道相关能力暂不作为本插件初始目标。
