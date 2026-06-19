# dynamic-bot-qqbot

QQ 官方机器人插件骨架，用于后续通过 QQ 官方 API v2 接入 dynamic-bot。

当前已接入 WebSocket 网关骨架：可获取访问令牌与网关地址、连接官方 WebSocket、处理 Hello 后 Identify、发送心跳并分发 Dispatch 事件；已支持将单聊/群聊消息事件映射为 dynamic-bot 入站消息，并可通过 OpenAPI 发送单聊/群聊文本消息。WebSocket 断开后支持基础自动重连，并会在 READY 后记录 `session_id`，重连时优先尝试 Resume；富媒体和撤回仍在后续补齐。

## 当前包含

- 独立 Gradle 插件项目。
- `plugin.yml` 插件描述。
- 插件生命周期与配置表单。
- QQ 官方机器人目标类型占位：单聊用户、群聊。
- WebSocket opcode、Identify、Heartbeat、Hello、Dispatch 协议模型。
- WebSocket READY 会话记录与基础 Resume 恢复。
- 访问令牌获取、缓存刷新、`/gateway/bot` 接入点获取组件。
- 基于 Java 标准库 `java.net.http.WebSocket` 的正向 WebSocket 网关骨架。
- 单聊/群聊文本消息发送与命令结果发送。
- 撤回、目标列表的接口骨架。
- 插件边界测试，避免直接依赖主程序运行时实现。

## 预计接入方向

1. 使用 `AppID` 和 `AppSecret` 获取访问令牌。
2. 通过 `/gateway` 或 `/gateway/bot` 获取 WebSocket 地址。
3. 连接 QQ 官方 WebSocket，处理 Hello、Identify、Heartbeat、Dispatch。
4. 将 `C2C_MESSAGE_CREATE` 和 `GROUP_AT_MESSAGE_CREATE` 映射为 dynamic-bot 入站消息。
5. 用 OpenAPI 回复单聊和群聊消息。
6. 后续再补富媒体、按钮、撤回和更细致的错误重试。

## 构建与测试

```powershell
.\gradlew.bat test
.\gradlew.bat fatJar --offline
```

生成的 `*-all.jar` 放入主程序 `plugins/` 后，再由后台启用插件。

## 配置项

插件默认未启用。后续实现接入后，至少需要：

```yaml
enabled: true
appId: "你的 AppID"
appSecret: "你的 AppSecret"
intents:
  - GROUP_AND_C2C_EVENT
reconnect: true
autoPublishIncoming: true
```

频道相关能力暂不作为本插件初始目标。
