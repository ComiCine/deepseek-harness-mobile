# 原版插件的安装与开发

Android 不定义自己的插件 ABI。请按上游 DeepSeek Harness/Cordis 文档开发和发布插件；兼容目标是原版 npm package、ESM 入口、`package.json` 依赖、Cordis `Plugin`/`apply(ctx, config)`、配置 schema 和 Loader 生命周期。

上游开发文档位于主源码仓库：

- `fork/deepseek-harness/docs/cordis-primer.md`
- `fork/deepseek-harness/docs/cordis-tutorial/01-first-plugin.md`
- `fork/deepseek-harness/docs/architecture.md`
- `fork/deepseek-harness/docs/tool-catalog.md`

Android 兼容桥接说明和已通过设备执行的包列表见[原版插件兼容性](PLUGIN_COMPATIBILITY.md)。不要为 Android 增加 `dsh-mobile.json`、重写插件实现或修改上游 package source。

## 用户安装方式

1. 在插件页输入原版 npm package specifier，例如 `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3`，或从系统文件选择器选择上游 `.tgz`。
2. npm 在应用沙箱内安装包及依赖。安装完成后插件默认停用。
3. 打开插件 JSON 配置编辑器，按该原版包 schema 输入 object。启用时应用显示代码信任提示，Cordis Loader 执行上游配置验证和 activation。
4. 聊天中，插件公开的原版工具 schema 进入模型请求。需要审批的工具调用由 Harness UI 请求确认，再交给原版 `ctx.tools.execute()`。
5. 停用走 Cordis Loader update/fiber dispose；卸载走 deactivate/remove 并由 npm 删除包。数据均留在 Android 应用私有目录。

## 依赖和平台要求

NPM registry 安装需要可访问 `https://registry.npmjs.org/`；本地 `.tgz` 可在安装包本身已经具备依赖或 npm 可取得依赖时使用。网络只用于获取包和依赖，不会执行 Harness 或插件代码。

为避免包安装阶段自动执行第三方脚本，npm 使用 `ignore-scripts=true`。要求生命周期脚本的依赖无法按桌面 Node 语义完整安装。native addon 还必须由插件作者提供与 Android ABI、NDK/Bionic 及内嵌 Node ABI 相匹配的构建，不能把 Linux/macOS/Windows 预编译文件直接放入包中并假定可执行。

运行插件意味着在 app UID 下执行第三方 JavaScript。当前不提供插件级 OS 进程、capability 权限、CPU/内存额度或 native crash 隔离；启用前请审查包名、版本和来源。测试通过只证明已测包在特定 emulator/runtime 上能执行，不保证未测插件安全或兼容。
