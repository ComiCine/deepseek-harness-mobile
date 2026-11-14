# DeepSeek Harness Android

这是一个原生 Kotlin/Jetpack Compose Android 应用。Harness 编排、Cordis 插件加载、工具执行、会话与插件状态均在设备本机运行；只有用户配置的模型推理请求和插件自行发起的外部 API 请求会访问网络。应用不需要 Harness 服务端、桌面伴侣或远程插件执行器。

Android 插件页直接安装原版 npm 包或原版 `.tgz`，通过嵌入式 Node.js、上游 Cordis Loader 和上游 `ctx.tools` 接口加载与执行原始 ESM 插件。插件源文件、`package.json` 和 Cordis 配置/生命周期没有转换成 Android 自有 ABI。当前已在 arm64 Android 36 模拟器上端到端执行两个未修改的上游插件；这不代表整个上游插件市场或所有默认 profile 插件均已兼容，具体边界见[插件兼容性](docs/PLUGIN_COMPATIBILITY.md)和[功能对照表](docs/FEATURE_PARITY.md)。

## 已实现的 Android 功能

- Material 3 原生聊天、会话、设置、插件、日志和本地工作区界面。
- DeepSeek Messages SSE 文本、思考内容、工具调用、多步循环、重试与取消。
- Room 本地消息、会话、插件记录、任务状态和执行日志；API Key 使用 Android Keystore 派生的 AES-GCM 密钥加密。
- 上游 Cordis Loader、`dsh-tools` 工具 schema/执行管线，以及在设备内运行原版 npm ESM 插件所需的 Node.js runtime。
- 原版插件 npm registry 安装和本地 `.tgz` 安装、依赖解析、配置校验、启停、更新替换、卸载及本地包完整性记录。
- 应用私有工作区文件工具、SAF 单文件导入/导出、普通应用 UID 下的 Android `/system/bin/sh` 命令工具。
- 前台任务通知、用户取消、多会话并发保护、会话/工具日志和本地插件配置恢复。

本项目尚未达到上游 `web`/`desktop` 默认 profile 的完整功能 parity。Agent 主循环及界面由 Android 代码承载，当前装配的 Cordis services 是有限子集。MCP、subagents/teams、workflows/PTC、web tools、skills、jobs 等功能和依赖它们的插件不能据此视为已兼容。未运行的功能不会作为已完成能力宣传。

## 构建与安装

要求 Android SDK API 36、JDK 17+。Gradle Wrapper 使用项目固定版本。

```sh
./gradlew :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

debug APK 按 ABI 输出在 `app/build/outputs/apk/debug/`。当前支持 `arm64-v8a` 与 `x86_64`。也可用 Android Studio 打开本目录并在设备上运行 `app`。

首次启动后，在“设置”中配置 DeepSeek Messages API endpoint、模型 ID 和 API Key。API 请求发送到用户填写的 endpoint；Harness 工具和插件逻辑不会迁移到该 endpoint。

## 安装原版插件

在“插件”页输入原版 npm 包名和版本，例如 `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3`，或从文件选择器安装上游 `.tgz`。依赖由设备内 npm 解析到应用私有目录，代码默认保持停用；启用前会提示插件运行在应用进程内。可在插件详情中编辑 JSON 配置、停用或卸载。

安装依赖时默认不执行 npm lifecycle scripts。依赖或插件若要求下载平台二进制、编译 native addon 或运行安装脚本，不能仅凭成功安装声明兼容。Android ABI、系统调用、Node API 及上游服务依赖都需要实际设备验证。插件有与应用相同的 Android UID 权限，当前没有独立 OS 进程隔离或 Node capability sandbox；只安装信任的代码。

## 验证

仪器测试 `PluginRuntimeInstrumentedTest` 在 Android 36 arm64 模拟器上验证了原版 `@deepseek-ai/dsh-repeat-tool-reminder@0.2.2-rc.3` 与 `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3`：registry 与原始 tarball 安装、原始 ESM manifest、npm 依赖解析、有效/无效配置、Cordis 工具执行和错误结果、重复工具提醒、Node runtime 关闭再创建后恢复原始 Session 事件、停用清理、卸载和会话数据删除。Android 整个 app 的强停/重启恢复尚未做仪器验证。执行记录和其他未验证项见[兼容性文档](docs/PLUGIN_COMPATIBILITY.md)。这项验证不覆盖真实 DeepSeek API 账户、物理设备、x86_64 native runtime 或全部插件包。

## 文档

- [架构与本地执行边界](docs/ARCHITECTURE.md)
- [上游功能对照表](docs/FEATURE_PARITY.md)
- [原版插件兼容性与设备验证](docs/PLUGIN_COMPATIBILITY.md)
- [Android 平台限制](docs/PLATFORM_LIMITATIONS.md)
- [原版插件安装与开发说明](docs/PLUGIN_DEVELOPMENT.md)
- [第三方依赖和许可](THIRD_PARTY_NOTICES.md)

## 上游与许可

功能基线来自 `fork/deepseek-harness` commit `39163dd30f`。保留该项目的 MIT License 和必要归属声明。`fork/deepseek-harness-mobile` 仅作为只读参考，不提供本应用实现源代码。
