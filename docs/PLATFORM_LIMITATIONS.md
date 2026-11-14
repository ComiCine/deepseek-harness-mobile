# Android 平台限制与功能缺口

本项目把 Harness 与插件执行留在设备本地；下列缺口不会通过服务器或 SSH 执行绕开。

## 原版 Harness 功能缺口

- 原版 Cordis ESM 插件运行路径已实现，并由两个真实上游包在 Android 36 arm64 模拟器验证；**完整上游 `web`/`desktop` base profile、全部插件包和全部 DSH services 尚未移植或验证**。需依赖未装配 service 的插件会报真实加载/执行错误。
- Kotlin 承载当前模型 Agent 循环；没有完整复用上游 `dsh-agent-loop`、profile/bundle 构建器、全部 hooks 和 provider-neutral model layer。
- 目前主要使用 DeepSeek Messages API adapter；多 provider profile、OAuth/account 插件、模型目录和全部上游 provider 行为没有完整移植验证。
- subagents、Agent Teams、workflow/PTC、MCP transports/resources、web search/fetch、skills/persona、goals/plans、持久 jobs/schedule 尚未完整实现。
- 文件/工作区工具限于 app 私有目录与 SAF 单文件导入/导出；缺少上游广泛文件系统 adapter、完整 JSONL session 格式迁移、附件、图像、Office、desktop GUI 和 Computer Use 能力。
- 会话导出格式是 Android 应用格式，不兼容上游所有 versioned JSONL Session data；尚无 Room schema migration 和数据清理策略。
- 原生 UI 是 Android Compose 实现，不包含完整 Web/Desktop UI、市场、profile editor、命令面板和所有上游配置页面。

逐项状态见[上游功能对照表](FEATURE_PARITY.md)。

## 原版插件 runtime 限制

- 已验证范围仅为 `dsh-repeat-tool-reminder@0.2.2-rc.3` 和 `dsh-tool-todo@0.2.2-rc.3`，在 Android 36 arm64-v8a 模拟器通过真实 npm/Cordis/Node 路径运行。未据此宣称全部插件兼容。
- 当前 Cordis Host 装配的是有限 DSH service 子集。依赖 MCP、subagent/team、web、skills、jobs、sandbox、credential 或未加载 service 的包需要先完成对应本地 service 移植和实际测试。
- NPM lifecycle scripts 被禁用。需要 `preinstall`/`install`/`postinstall`、下载二进制或构建步骤的依赖可能安装失败或运行不完整。
- 原生扩展必须匹配 Android 的 arm64-v8a/x86_64、Bionic、NDK 和嵌入式 Node ABI。桌面 native addon 不会自动兼容；目前没有提供桌面 ABI 转译层。
- 插件运行在 Android 应用同一个进程和 UID 中。没有独立插件进程、Node builtin capability 过滤、文件路径权限代理、内存 quota 或 native crash containment。Javet watchdog 会在 bridge operation 超时后终止 V8 execution；它不限制内存膨胀，native crash 仍可结束整个应用。
- 插件配置编辑目前面向 JSON object，未重现原 profile 对 YAML、Loader JS 标记及复杂 overlay 的完整编辑能力。
- 已运行模拟器为 Android 36 arm64。真机低内存/后台行为、x86_64 原生 runtime、其他 Android 版本、长时间任务与系统杀进程压力仍待测。

## Shell、文件和系统 API

Shell 使用 Android `/system/bin/sh`，以普通 app UID 在应用 sandbox 运行。无 root、不会使用 SSH 或伴侣电脑。系统镜像命令集因设备而异；桌面 Bash/Pwsh、PTY、Windows/macOS 专用程序、全局 `/usr` 和 GUI 不可假定存在。子进程可设置 cwd/stdin/stdout/stderr、exit code 与 timeout；Android 仍可能停止后台进程。

工作区默认在应用私有 `filesDir/workspace`。路径解析检查 canonical path，拒绝逃逸。SAF 支持用户授权的单文件输入/输出，未提供任意文件系统 root 挂载或持久 SAF tree provider。

## 生命周期与后台限制

应用用 `dataSync` Foreground Service 展示活动通知并提供取消入口；Android 版本、前台服务启动条件、时限、省电策略、用户强停和进程压力均可能打断任务。应用不承诺无限后台执行。

Room 保存任务和会话状态；进程死亡恢复会将未完成流记录为 interrupted，不会自动重发旧模型请求。Node 插件被应用进程一起终止；下次启动会从本地 Room 恢复启用配置，首次需要插件 schema/tool 时重建 Cordis context。正在执行的插件同步代码不保证可抢占。

## 验证边界

已执行的 Android 仪器测试记录在 [PLUGIN_COMPATIBILITY.md](PLUGIN_COMPATIBILITY.md)。这不验证真实 DeepSeek API 账户及网络响应，也不覆盖所有插件包、所有 ABI、真机电源/通知限制或 native addon。Gradle 成功只表示构建任务通过；未执行的功能保持未验证或不兼容状态。
