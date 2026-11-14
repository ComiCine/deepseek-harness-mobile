# 原版 DeepSeek Harness 插件兼容性

## 兼容目标

Android 插件页接收原版 npm 包名/版本或原版 `.tgz`。安装的是上游发布包本身：保留其 ESM 文件、`package.json`、peer/dependency 声明与 Cordis plugin export，不改写 plugin source、manifest、接口或内部实现，也不以 Kotlin 重写插件能力。运行时由设备内 npm、Javet Node Android、原版 Cordis 和 `@deepseek-ai/cordis-plugin-loader` 承载。

不兼容的包会在安装依赖、Cordis 配置/启用或工具执行阶段显示真实错误。npm 包成功安装只证明包管理器取得代码，不代表它依赖的 DSH service、桌面命令或 native addon 已在 Android 提供。

## Android 运行桥接

1. 嵌入式 Node.js 运行在 Android app 进程中；Node runtime 和 NPM 归应用本地管理，无需外部 Node 安装或终端应用。
2. NPM 将原版包与依赖解析到 app 私有 `filesDir/plugins/`；registry 安装使用 npm registry，`.tgz` 则从 SAF 选取后在本机安装。
3. Cordis Host 创建原版 Cordis context，加载原版 Cordis Loader，并把 Loader 的内部模块 import seam 接到 Node module loader。插件代码以其原始 ESM package 入口加载。
4. 插件的 `apply(ctx, config)`、Cordis 配置校验、作用域/Fiber、`ctx.tools` schema 和工具执行都交给上游实现。启停/卸载使用 Loader 的原生 update/remove 生命周期。
5. Kotlin Harness 适配模型循环与 Android UI；插件 tool-call 仍通过 `ctx.tools.execute()` 进入原版工具管线。Cordis Session event 快照本机落盘，恢复 Node runtime 后从原始事件数据重新建立 Session。

当前装配的 DSH service 集合有限：Node 包目录安装了 `@deepseek-ai/dsh-agent`、`dsh-llm`、`dsh-scope`、`dsh-session`、`dsh-session-projection`、`dsh-system-prompt`、`dsh-tools`、`dsh-tool-executor-native`、`dsh-user-approval`、`dsh-typert-protocol`、`dsh-util-values`、`dsh-workspace` 等同版本依赖；Cordis context 当前显式加载 Agent、System Prompt、Tools、Native Tool Executor 和 Session Projection 插件。它不是上游 `web`/`desktop` profile 的完整 bundle tree。依赖 MCP、subagent/team、web、skills、jobs、sandbox、credential、filesystem 等未装配服务的插件可能无法启用或运行。

## 已在 Android 设备执行的原版插件

以下测试由 `:app:connectedDebugAndroidTest` 在 `dsh-api36-arm64(AVD)`、Android API 36、arm64-v8a 模拟器上执行；AndroidJUnitRunner 报告 1 项测试通过。测试位置：`app/src/androidTest/java/ai/comicine/dshmobile/plugins/PluginRuntimeInstrumentedTest.kt`。

| 原版上游包 | 安装方式 | 已验证的真实行为 |
|---|---|---|
| `@deepseek-ai/dsh-repeat-tool-reminder@0.2.2-rc.3` | npm registry 包名和固定版本 | 原始 `type: module` manifest/ESM 入口；依赖 `@deepseek-ai/schemastery` 解析；原版 Cordis 配置设置与无效阈值拒绝；实际侦听 `tools/post-execute`；重复工具调用产生原版 additional context；停用后监听器不再触发；卸载后 npm 目录和 Room 记录删除。 |
| `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3` | 从上游发布 `.tgz` 安装 | 原始包名/version/manifest；依赖 `@deepseek-ai/schemastery` 和 `zod` 在设备本地解析；原版 Cordis config；经原版 `ctx.tools` 调用 `todo_write`，写入真实 Session `todo/write` 事件；非法的多个 `in_progress` todo 得到原版工具错误；Node runtime 关闭再创建后旧 Session event 仍恢复；卸载后 npm 目录及 Room 记录删除。 |

测试还覆盖：两插件同时启用、按 Cordis 原生 config 校验拒绝配置、plugin deactivate fiber 清理、runtime 生命周期重建、Session 数据删除。测试使用实际上游 tarball 和 registry 依赖，不用 stub、假结果或 Kotlin 插件替身。

这证明所测两个 Cordis 插件在该模拟器配置下未改源码即可安装、配置和执行；**不能推出所有上游插件、所有 Node 包、全部 ABI 或所有上游 Harness profiles 都兼容。**

## 插件管理能力

| 操作 | Android 行为 | 当前边界 |
|---|---|---|
| 发现/安装 | 输入 npm package specifier；也可从 SAF 安装 `.tgz`；依赖在设备内解析 | 未提供上游 Plugin Market 搜索、profile/bundle 安装、任意 registry 设置或官方源签名验证 |
| 激活 | 原版 Cordis Loader 调用包入口并挂载原始 plugin fiber | 缺少未装配的 profile services 时，兼容性仍会失败并显示 loader error |
| 配置 | 编辑 JSON object；配置更新使用 Loader update，让原插件 schema 校验并重载 | JSON 输入不支持原 profile 的 YAML 标记/JS 表达式编辑体验；复杂非 JSON 配置尚未测试 |
| 依赖 | npm 在设备本地解析生产依赖、peer dependency 和 lockfile integrity | `ignore-scripts=true`；不运行依赖安装脚本或 native build；Android 平台依赖可能不可用 |
| 执行 | 原版 `ctx.tools.schemas()` 和 `ctx.tools.execute()` | Kotlin Harness 目前承载 Agent 模型循环；不是原版 `dsh-agent-loop` 的完整重用 |
| 错误 | 配置异常和工具错误传回本地 UI/消息/日志 | 没有独立插件 stdout/stderr 浏览器或按插件分级诊断页面 |
| 停用/卸载 | Fiber/disposable 生命周期由原版 Loader 执行；卸载移除插件 Room 记录和 npm package | 会话事件保留到用户删除其会话时，再由本地会话删除流程销毁 Agent scope 并清理快照；npm 公共依赖按 npm tree 使用情况重算 |
| 更新 | 再安装明确版本可替换已安装包 | 无自动更新、变更审查、profile 回滚界面或版本兼容豁免界面 |

## 未验证或不兼容场景

- 上游所有 bundle/profile 组合尚未装配；只验证了上述两个实际插件包。
- Import seam 通过 npm 项目根目录中的 ESM helper 调用 Node 原生 `import()`，以保留 exports 条件和异步模块加载语义；两个已测上游包本身不含顶层 `await`，实际需要顶层 `await` 的上游包仍未验证。特殊 loader hook、host-process CLI loader 也未验证。
- 需要 `preinstall`/`install`/`postinstall` 脚本的 npm 依赖不可用，因为 npm 安装脚本被关闭。脚本若编译桌面二进制或下载平台资源也不能假设 Android 可运行。
- 原生 Node addons 必须为目标 Android ABI/NDK/Bionic 构建且与嵌入式 Node ABI 匹配。桌面 Linux、macOS、Windows addon 不能直接复用；项目没有宣称这类插件可用。
- 使用桌面 Bash、PTY、PowerShell、Windows/macOS GUI、全局系统路径、root、桌面 browser automation、MCP 服务、远程 shell、上游 sandbox/job manager 等的平台插件仍未验证或不兼容。
- 未提供 Cordis 插件级 OS 进程隔离、CPU/内存 quota、Node capability 权限拦截或 native crash containment。插件可能访问其 Node runtime 能访问的本地数据并发起网络请求；Android app UID、内存和 native crash 仍由所有插件共享。
- Node runtime 运行在 app 内，NPM 依赖在设备安装前会访问 registry；Harness 和插件执行本身不在网络端运行。
- physical phone、x86_64 Android emulator、Android 更低 API、长期后台执行和系统内存压力场景尚未测试。

Javet bridge 对一次 Node promise operation 默认设置 5 分钟 wall-time watchdog，并在 Kotlin coroutine 被取消时请求终止当前 V8 execution。Android 测试还在同一 runtime 中验证了待定 Promise 取消以及 1 秒 watchdog 打断同步死循环，随后 runtime 继续返回 `42`。这只验证了当前 arm64 模拟器/Javet 版本中的 CPU 执行终止；它不是 JavaScript capability 或内存隔离，native addon 崩溃仍可能终止整个进程。

由于可执行代码可能读写应用私有数据或联网，安装或启用前应核实包名、版本和发布来源。测试通过不是对包作者或代码安全性的证明。
