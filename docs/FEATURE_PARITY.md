# 上游功能对照表

本表来自对上游 `fork/deepseek-harness` 源码和 bundle 配置的审计，基准 commit `39163dd30f`。重点检查了 `packages/bundle/base/cordis.patch.yml`、`packages/bundle/base/package.json`、`packages/bundle/sdk-minimal/package.json`、`apps/cli/package.json`、`apps/desktop/package.json` 及相关实现包。上游不仅是聊天客户端：默认 base profile 包含 Cordis Agent、会话/JSONL 持久化、模型 provider、审批、沙箱、本地工具、插件管理、计划/目标、jobs、skills、MCP、subagents/teams、web 与工作区集成等。

**状态含义：** “部分实现”表示 Android 有限实现，不等同于完整上游行为/API 兼容；已实际运行的部分单独注明。“未实现”不隐藏在 UI 或假结果中。插件设备验证覆盖范围见[兼容性表](PLUGIN_COMPATIBILITY.md)，不等同于模型 API 或全部上游包验证。

| 上游能力 | Android 状态 | 实际范围 / 差距 |
|---|---|---|
| DeepSeek Messages provider | 部分实现 | 单一 Messages SSE adapter；header、路径、文本/思考/tool-call 与上游请求行为相近，设备/API 端到端尚未验证 |
| provider / model 管理 | 部分实现 | 手动填写 Messages-compatible URL 和 model ID；单 API Key；无 provider profile、OAuth/account adapter、模型 catalog、pi-ai 多 provider 配置 |
| 凭据管理 | 部分实现 | 单 DeepSeek key Keystore 加密；没有上游凭据引用/多账户/认证扩展 |
| 流式输出与 reasoning | 部分实现 | SSE 文本与 thinking 显示、checkpoint 保存；缺少上游事件、签名、usage 和错误边界的完整覆盖 |
| Agent/tool 循环 | 部分实现 | Kotlin HarnessEngine 承载模型步骤循环；插件适配真实上游 Cordis Agent/Tools service，但尚未重用完整上游 `dsh-agent-loop`、hook 和 provider-neutral model layer |
| 工具 schema / 执行 | 部分实现 | 插件 schema 与调用通过上游 `ctx.tools.schemas/execute`；Android 文件、shell 工具是本地适配器；无完整上游 native/PTC executor 与 sandbox 策略 |
| 多会话 | 部分实现 | Room 会话分隔、不同会话并行、同会话拒绝重复执行；插件使用上游 Session Event 快照本地持久化；无上游 session lineage、fork 或 query API |
| 并发任务 | 部分实现 | 可配置上限并按设备规格 clamp；不是上游 Agent Team/task DAG，不共享/协调子 Agent 工作 |
| 取消、恢复、重试 | 部分实现 | HTTP call 取消、shell kill、指数退避、进程死亡标记 interrupted；没有持久化任务队列/恢复续跑 |
| 上下文处理与 compaction | 部分实现 | 在用户轮次边界裁剪字符数；无 token meter、上游 compaction 插件、图像 offload 或工具结果 pruner |
| 本地 workspace | 部分实现 | 应用私有目录内文件操作，可 SAF 单文件导入/导出；无任意 SAF tree、原生工作区 provider、观察/变更事件 |
| 文件工具 / 搜索 / 替换 | 部分实现 | 列目录、读、写、唯一字符串编辑；无全文搜索、上游 patch semantics、change approval/diff、文档/office 工具 |
| shell / terminal | 部分实现 | 普通 app UID `/system/bin/sh -c`、cwd/stdin/out/err/exit/timeout；无 PTY、持久 shell、Bash profile、PowerShell 或桌面命令环境 |
| 原版 Cordis 插件发现与加载 | 部分实现；代表包已设备验证 | npm 包名/版本或原版 `.tgz` 安装；原版 Cordis Loader/ESM plugin contract、config 和 Fiber lifecycle；两个上游插件在 API 36 arm64 模拟器实测；不是完整 base profile 或市场覆盖 |
| 原版插件管理与依赖 | 部分实现 | 设备内 npm 依赖解析、安装、启停、JSON 配置校验、替换版本、卸载和 Session event 持久化；无上游 marketplace/profile/bundle UI、自选 registry、自动更新、publisher 签名与完整 `engines.dsh` 管理 |
| 原版插件 runtime | 部分实现；arm64 模拟器实测 | Javet Node Android + Node ESM、npm 和 Cordis Loader；实际执行 `dsh-repeat-tool-reminder` 与 `dsh-tool-todo`；其他插件、x86_64 ABI、native addon 和全部平台依赖仍未验证 |
| 插件权限 / 安全 | 未实现 | 插件与 app 共用进程/UID，没有 Node builtin 限权、插件 OS 进程隔离、CPU/内存配额、签名信任根或运行时 capability 控制 |
| 插件异步执行与生命周期 | 部分实现 | 内嵌 Node bridge 等待 Promise；Cordis Loader update/remove 与原版 Fiber disposal 已验证；没有插件单独 stdout/stderr 面板或崩溃隔离 |
| 上游 subagent / agent team | 未实现 | 没有子 Agent 创建/恢复、委派、共享任务树或 team tool |
| 上游 workflow / PTC | 未实现 | 没有 workflow、Node PTC runtime 或可重用多工具流程系统 |
| MCP client/resources/manager | 未实现 | 没有 stdio/HTTP MCP transport、server lifecycle、resource 或 tool discovery |
| Web search / fetch / browser use | 未实现 | 没有内建搜索、抓取或浏览器自动化工具 |
| Skills / persona / preset | 未实现 | 没有 skill 文件发现、执行、Agent preset、persona 管理 |
| Goal / plan / TODO | 未实现 | 没有上游目标持久化、plan mode、round driver 或 TODO 工具 |
| Jobs / schedule / timer | 未实现 | 只有当前前台任务通知；无持久 job manager、定时器或 schedule UI/API |
| 会话日志和调试 | 部分实现 | Room 本地按 session 存 task/tool 日志；原版插件产生的 Session Event 快照独立落本机文件且已验证 runtime 重建恢复；无完整上游 event log/telemetry/OTel 兼容 |
| 会话导入/导出 | 部分实现 | 本地自定义 `deepseek-harness-mobile-session-v1` JSON；不兼容上游 JSONL/versioned session formats |
| 本地存储 | 部分实现 | Room 存 app session/message/log/plugin rows；没有上游 storage domain/KV backend、跨版本 schema migration |
| UI 与设置 | 部分实现 | 原生 Compose 响应式主要页面；无上游桌面完整 client/plugin/settings/catalog UI、命令面板、session search |
| 附件、图像、文件引用 | 未实现 | 当前聊天输入为纯文本；没有上游附件仓库/上传、图像消息或引用解析 |
| Computer use / Office | 未实现 | 不提供桌面 GUI 自动化、终端/窗口控制、Office 工具 |
| 远端访问 / API gateway / ACP | 未实现 | Android 不启动远端执行服务或 SDK JSON-RPC server，避免将本地 Harness 暴露成服务 |
| Telemetry / analytics | 未实现 | 应用没有自动上传运行数据或产品遥测 |
| 自动更新 / 插件市场 | 未实现 | 可输入 npm 包 specifier 或选取本地 `.tgz`；无上游 plugin-market 搜索、bundle 安装或应用/插件自动更新器 |

## 上游 `dsh-base` 范围审计

上游 `packages/bundle/base/package.json` 在基线 commit 中声明了 **99 个直接运行依赖**。`cordis.patch.yml` 将它们组合成 profile，而不是 99 个互相独立、可随意启用的工具。按源码职责覆盖的功能域如下：

| 上游 bundle / service 职责 | 源码功能范围 | Android 对照 |
|---|---|---|
| Profile、bundle 与 patch | Cordis 插件树组装、profile 层叠、配置 overlay、HMR、live plugin/config manager | 未实现完整 profile/bundle composer；Android 有限地建立 Cordis context，并通过原版 Loader 安装具体 npm plugin |
| Agent 与指令 | Agent registry、default model、instructions、agent loop、turn/step/request hooks、问题收集 | Kotlin 实现有限多步循环；Cordis Agent/Tools seam 可用；未复用上游 agent loop/instructions/user questions 整套组合 |
| 并行 Agent 与任务 | Subagent、in-process spawn/fork、Agent Team、subagent control、goal、plan mode、workflow/PTC | 未实现完整上游子 Agent、team tree、目标与 workflow runtime |
| Model/LLM | 通用 LLM stream、DeepSeek account/API extensions/API key、pi-ai providers、retry、model selection | 单 Messages adapter、单 API key、重试；未有上游多 provider/profile/account adapter |
| Session、附件与上下文 | Append-only Session event、JSONL persistence、projection/cache/query、SQLite search、title、attachments、checkpoint、token meter、compaction/spill | Room app message + Cordis Session JSON snapshot；没有完整 JSONL/query/attachment/token/compaction 组合 |
| 工具注册与本地命令 | Tools service、native/PTC executor、bash/pwsh、working directory、shell env、timeout policy、subprocess/jobs、TODO、presentation | 已用上游 Cordis tools；Android 私有 FS 与 `/system/bin/sh` 由本机适配器提供；没有桌面 Bash/Pwsh/PTC/jobs 整套服务 |
| 文件系统与沙箱 | local FS、filesystem observation、sandbox、permission presets、approval、policy | App workspace 路径边界和单次审批已实现；不兼容上游沙箱策略/全权限隔离 |
| 网络/外部集成 | Web fetch/search、MCP resources、API Gateway/remote access、OpenTelemetry | 只允许模型 API 与插件明确发起的网络调用；上述完整客户端/服务器/telemetry 生态未实现，Android 不提供远程 Harness 执行服务 |
| 账户、设置和持久存储 | Authorization、credentials、settings、storage hub/JSON/domain backend、插件 package inventory/market | 单 provider Keystore 凭据、Room 与 app 私有文件；缺少上游账户体系、profile settings/storage domain 与插件市场 |

此分组依据上游 `dsh-base` 的 99 个 direct dependencies 和实际 patch rows；每行是功能域摘要，不表示表内列举的全部包都能在 Android 启用。单包状态以[插件兼容性矩阵](PLUGIN_COMPATIBILITY.md)和实际设备测试为准。

## 完成判断

这份对照表表明项目尚未达到“完整功能 parity”定义。原版 Cordis/ESM 插件路径已经在 arm64 Android 模拟器上执行两个真实上游包，但完整 `base` profile、全部原版插件依赖服务、多个 provider、subagent/team/workflow、MCP/web/skills/jobs/schedule、附件和隔离 sandbox 仍有缺口。不能把这些差距通过远程执行服务补齐；各项保持未实现或部分实现，直到 Android 本地实现并完成对应验证。
