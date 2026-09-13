# dynamic-bot-qqbot

QQ 官方机器人插件，用于通过 QQ 官方 API v2 接入 dynamic-bot。

当前已接入 WebSocket 网关能力：可获取访问令牌与网关地址、连接官方 WebSocket、处理 Hello 后 Identify、发送心跳并分发 Dispatch 事件；已支持将单聊/群聊消息事件映射为 dynamic-bot 标准入站消息，并可通过 OpenAPI 发送、撤回单聊/群聊文本消息。WebSocket 断开后支持基础自动重连，并会在 READY 后记录 `session_id`，重连时优先尝试 Resume；图片、视频、语音已支持通过 HTTP(S) URL 或本地文件发送，其中本地文件走官方推荐的分片上传（无需公网地址）。

发送结果已按官方语义分级：回复窗口过期、参数无效、内容违规、用户拒收等业务码直接终态失败不再重试；超时与官方"发送异常"码上报为状态未知，避免重复推送。被动回复重试会自动续编 `msg_seq`，绕开官方 `msg_id + msg_seq` 去重。

> 官方自 2026-08-10 起统一接口域名为 `api.bot.qq.com`；本插件已对齐。

## 当前包含

- 独立 Gradle 插件项目。
- `plugin.yml` 插件描述。
- 插件生命周期与配置表单。
- QQ 官方机器人目标类型：单聊用户、群聊。
- WebSocket opcode、Identify、Heartbeat、Hello、Dispatch 协议模型。
- WebSocket READY 会话记录与基础 Resume 恢复。
- 访问令牌获取、缓存刷新、`/gateway/bot` 接入点获取组件。
- 基于 Java 标准库 `java.net.http.WebSocket` 的正向 WebSocket 网关实现。
- 单聊/群聊文本消息发送、URL 或本地文件富媒体发送、命令结果发送与消息撤回。
- 发送结果按官方错误码分级：终态失败不重试、超时与"发送异常"上报状态未知、被动回复重试自动续编 `msg_seq`。
- 入站消息提交到主项目统一管线，由主项目识别命令、链接和普通消息。
- `ark_data` 结构化卡片（B 站视频分享、小程序等）提取跳转链接为链接段，配合主项目"解析平台卡片链接"开关使用。
- `MessageContent.Mention` 映射为官方 at 协议 `<qqbot-at-user id="" />`；`MentionAll` 官方群聊不支持，降级为"@全体成员"纯文本。
- 本地文件富媒体走官方分片上传（预上传 → 分片 PUT → 分片完成 → 合并），无需公网可访问地址；媒体投递建议优先使用本地文件。
- 消息目标账本：从入站消息与群/好友生命周期事件积累 `group_openid` / `user_openid`，持久化到插件数据目录；后台"可选目标"列表由账本提供，无需手填 OpenID。被移出群、删除好友后自动从列表隐藏。
- 插件边界测试，避免直接依赖主程序运行时实现。

## 当前接入流程

1. 使用 `AppID` 和 `AppSecret` 获取访问令牌。
2. 通过 `/gateway` 或 `/gateway/bot` 获取 WebSocket 地址。
3. 连接 QQ 官方 WebSocket，处理 Hello、Identify、Heartbeat、Dispatch。
4. 将 `C2C_MESSAGE_CREATE`、`GROUP_MESSAGE_CREATE` 和 `GROUP_AT_MESSAGE_CREATE` 映射为 dynamic-bot 标准入站消息；`ark_data` 卡片提取跳转链接；其余事件进入目标账本。
5. 文本消息直接调用 OpenAPI 回复单聊和群聊。
6. 富媒体消息：
   - HTTP(S) URL：调用 `/files` 传 `url`，平台下载转存后拿到 `file_info`；
   - 本地文件：走分片上传（`upload_prepare` → 分片 PUT → `upload_part_finish` → 携带 `upload_id` 合并），拿到 `file_info`。
7. 用 `msg_type = 7` 携带 `media.file_info` 发送消息。
8. 后续再补文件类型、按钮和更细致的错误重试。

### 当前限制

- 每次发送都重新登记富媒体资源：同一张图片推送到多个群、或投递重试时，会按目标重新执行哈希计算和分片上传。官方 `file_info` 在 `ttl` 有效期内理论可复用，后续计划加短期缓存（注意官方单聊/群聊上传的文件相互隔离，跨场景复用需实测确认）。
- 分片上传按服务端返回的顺序串行执行，暂未按 `upload_config` 的 `concurrency` 并发，也暂无分片级重试；大视频上传会偏慢。
- 目标账本只积累真实见过的事件：机器人新部署时账本为空，需要至少一条群消息/单聊消息/生命周期事件后目标才会出现在后台列表。官方未提供"列出所在群/好友"的接口。
- 与 OneBot 插件同时启用时两者都声明 `qq` 平台，请在主配置的消息路由中为 `qq` 平台设置主账号，否则投递会在两个出口间按路由策略选择。
- 官方主动消息有频控（未认证群聊 30/qpm、每日 1000 条/群），大规模 fan-out 可能触发频控错误码；被动回复窗口群聊仅 5 分钟、单聊 60 分钟，超窗回复会终态失败。
- 官方撤回窗口为 2 分钟，超时撤回会失败；主项目"解析完成后撤回提示"等短期场景不受影响。
- `MessageContent.Forward`（合并转发）官方无对应能力，主项目会自动降级为文本。

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
debugImageEchoSource: "D:/path/to/image.png"
debugImageEchoCaption: "QQ 官方机器人图片发送测试"
```

然后在单聊发送 `ping`，或在群聊中按实际收到的文本配置触发内容。群聊消息如果以 `<@机器人> ` 开头，调试回显匹配时会自动忽略这个开头 at。日志中应至少能看到 WebSocket 打开、Hello、Identify、READY、Dispatch、入站消息解析；如果能收到入站消息但回显失败，继续看 OpenAPI 发送失败日志。排查完成后建议关闭 `debugLogPayloads`，避免日志中长期记录原始消息载荷。

图片发送可以临时配置 `debugImageEchoText` 和 `debugImageEchoSource`。`debugImageEchoSource` 支持 HTTP(S) URL 或本地文件路径；本地文件会走分片上传，因此部署机器没有公网 IP 时也能直接验证。触发后日志中应看到“调试图片回显已触发”和“调试图片发送成功”；如果失败，错误日志会包含分片上传或消息发送接口返回的业务错误。

频道相关能力暂不作为本插件初始目标。
