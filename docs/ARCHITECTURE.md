# Android 架构与本地执行边界

## 执行架构

```mermaid
flowchart LR
  UI[Compose UI] --> VM[HarnessViewModel]
  VM --> H[本机 Kotlin HarnessEngine]
  H -->|用户配置的模型请求 / SSE| API[DeepSeek Messages API]
  H --> Tools[本机工具执行器]
  Tools --> FS[应用私有 workspace]
  Tools --> SH[/system/bin/sh · 普通 app UID]
  Tools --> PM[PluginManager]
  PM --> Node[Javet 嵌入式 Node.js]
  Node --> Cordis[原版 Cordis Context + Loader]
  Cordis --> P[未修改的上游 ESM 插件]
  Cordis --> S[(上游 Session Event 快照)]
  VM --> DB[(Room / SQLite)]
  H --> DB
  PM --> PKG[(应用私有 npm 包目录)]
  VM --> KS[Android Keystore]
```

模型推理请求、用户安装的插件自主发起的网络请求以及 npm 下载属于明确的外部网络访问。Harness 会话编排、Cordis 加载、插件执行、shell、workspace、应用状态和持久化均在 Android 设备本地完成。没有 Harness 服务端、SSH 主机、桌面伴侣、网络执行队列或远端插件 runtime。

## 上游插件适配

Android 使用 Javet Node Android 运行时和应用私有 npm 项目目录安装原版 npm 包。原包的 ESM 入口、`package.json`、依赖声明和内部实现以 npm tarball 安装内容保留，不转换为 Android 专用 manifest 或 CommonJS ABI。

`app/src/main/assets/cordis-host.js` 建立原版 Cordis `Context`，加载上游 `@deepseek-ai/cordis-plugin-loader`，并装配已选定的上游 DSH services。Loader 的 `ctx.loader.internal.import` 由 Node 模块加载适配器连接到内嵌 Node；插件仍由原版 Loader 的 `create`、`update`、`remove` 和 Fiber 生命周期处理。插件本身的 source code、接口或 manifest 不做改写。

Android Harness 的模型循环由 Kotlin `HarnessEngine` 承载。它从原版 `ctx.tools.schemas(agent)` 获取模型可调用工具定义，经审批后把 tool-call 交给上游 `ctx.tools.execute()`；工具返回值、错误和附加上下文再进入本地会话记录及下一次模型请求。Agent/session seam 与原版 Cordis API 相接，但 Android 尚未装配完整上游 `dsh-agent-loop`、profile/bundle 树或所有上游 services。因此能安装任意包不代表它依赖的 service 已提供；完整插件兼容边界见[插件兼容性](PLUGIN_COMPATIBILITY.md)。

本机 Cordis Session event 快照保存到 app 私有 `filesDir/plugins/sessions/`，以 session ID 的 SHA-256 作为本地文件名；Room 单独保存聊天消息、任务状态和执行日志。instrumented test 验证了关闭再创建 Node runtime 后从快照恢复原版 Session，再启用 Room 中仍标记为启用的插件；Android 整个 app 强停/重启恢复尚未执行仪器验证。显式删除会话时会同时销毁 Agent scope 并删除 Session 快照。

## 模块分层

| 层 | 实现 | 职责 |
|---|---|---|
| Android UI | `app/src/main/java/ai/comicine/dshmobile/ui/` | Compose 聊天、会话、插件、工作区、日志、设置及工具审批 |
| 应用状态/生命周期 | `HarnessViewModel.kt`, `execution/ExecutionService.kt` | UI StateFlow、前台通知、取消及 Android 生命周期衔接 |
| Harness 循环 | `engine/HarnessEngine.kt` | 会话任务、并发闸门、流式模型请求、Agent 步骤和 Room 状态 |
| Provider | `engine/DeepSeekMessagesClient.kt` | DeepSeek Messages HTTPS、SSE 解析、重试和请求取消 |
| 工具执行 | `engine/ToolExecutor.kt` | app 私有 workspace、安全路径校验、shell 与 Cordis 工具连接 |
| 上游插件 | `plugins/PluginManager.kt`, `plugins/EmbeddedNodeRuntime.kt`, `assets/cordis-host.js` | 本地 npm 安装、依赖解析、Cordis Loader 生命周期、Node 执行 |
| 本地持久层 | `data/` 和 `filesDir/plugins/` | Room 结构化数据、npm package tree、Node runtime 与 Cordis Session 数据 |
| 凭据 | `security/CredentialStore.kt` | Keystore 派生 AES-GCM 密钥及本地 API Key 加密 |

## 工具调用过程

1. HarnessEngine 将用户消息保存到 Room，并从本机 Cordis context 取得启用插件的原版工具 schema。
2. Kotlin provider adapter 将历史消息和 tool schemas 发送到用户配置的模型 API；SSE 增量显示在 UI 并周期性保存。
3. 模型返回工具调用时，用户对需要批准的操作进行确认。
4. 内嵌 Node 中的原版 `ctx.tools.execute()` 处理原版 Cordis plugin；工具结果和错误保留为真实执行结果，不用 Kotlin 重写实现代替。
5. 原版插件附加上下文与工具结果写入 Room 后，进入下一轮模型请求。此过程仍在设备本地运行。
6. 任务结束、失败或取消时更新本地状态与日志。应用进程被系统杀死后，不会自动重发付费模型请求；未完成流会恢复为中断状态。

不同会话可并发执行，同一会话拒绝并发开局；并发上限有设备内存和 CPU 防护。嵌入式 Node runtime 由单线程调度；一次 bridge operation 默认受 5 分钟 watchdog 约束，coroutine 取消会请求 Javet 终止当前 V8 execution。arm64 模拟器测试验证了异步取消和同步死循环 watchdog。插件仍没有独立 OS 进程、内存 quota 或 native crash 隔离。

## 数据、权限和边界

- 会话、消息、任务、日志、设置、npm 包、插件配置和 Session event 均保存在本机 app 私有存储，不自动上传或同步。
- API Key 由 Android Keystore 派生的 AES-GCM key 加密存放。模型请求时由 Kotlin Provider 读取；Cordis Node context 没有直接暴露 credential service。
- 插件代码与 Harness 共享 Android app UID/进程，Node 可使用其内置模块和 Android 应用沙箱权限。启用提示和工具逐次批准不是 Node capability sandbox。
- 为保留原版 Cordis Loader 支持的 JS 配置表达式，Javet 启用了 V8 eval/code generation。插件仍作为可信任的进程内代码运行；watchdog 不是安全沙箱。
- 插件依赖通过 npm 解析，但安装时设置 `ignore-scripts=true`。依赖需要执行 install/postinstall 脚本或桌面 native build 的包不能视为兼容。
- 从 SAF 选取原版 `.tgz` 时，只读文件内容并安装进私有目录；不把插件包执行交给外部设备或服务。

## Android 运行约束

- 最低 API 26；compile/target API 36；打包 `arm64-v8a` 和 `x86_64`。
- `dataSync` foreground service 在 Android 允许的窗口内显示任务通知并提供取消入口。Android 后台限制、电量策略、用户强停和服务超时仍可能中断长任务，不承诺无限后台运行。
- Room 结构化状态及 Cordis Session 文件位于应用私有持久目录；Node runtime 重建恢复已验证，Android app 强停/重启和升级迁移仍未完整验证。Room schema migration、会话外部格式互通及系统备份策略尚未完整实现。
- 真实设备验证范围及未测试 ABI 见[插件兼容性](PLUGIN_COMPATIBILITY.md)和[平台限制](PLATFORM_LIMITATIONS.md)。
