# Installing and Developing Original Plugins

Android does not define a separate plugin ABI. Develop and publish plugins for the upstream DeepSeek Harness/Cordis contracts. The compatibility target is an original npm package with an ESM entry point, `package.json` dependencies, Cordis `Plugin`/`apply(ctx, config)` behavior, configuration schema, and Loader lifecycle.

The authoritative source repository includes these upstream references:

- `fork/deepseek-harness/docs/cordis-primer.md`
- `fork/deepseek-harness/docs/cordis-tutorial/01-first-plugin.md`
- `fork/deepseek-harness/docs/architecture.md`
- `fork/deepseek-harness/docs/tool-catalog.md`

See [Plugin Compatibility](PLUGIN_COMPATIBILITY.md) for the Android bridge and the upstream packages exercised on a device. The Android app does not add a `dsh-mobile.json` format, rewrite plugin implementations, or modify upstream package source.

## Install a plugin

1. In **Plugins**, enter an original npm package specifier, such as `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3`, or choose an upstream `.tgz` archive with the system file picker.
2. npm installs the package and dependencies into the app sandbox. The plugin remains disabled after installation.
3. Open the plugin's JSON configuration editor and provide an object accepted by the package schema. When enabling it, review the code-trust prompt; the original Cordis Loader performs configuration validation and activation.
4. The plugin's original tool schema can be included in model requests. The Harness UI requests approval when a tool requires it, then invokes the original `ctx.tools.execute()` pipeline.
5. Disabling uses Cordis Loader update/Fiber disposal. Uninstall deactivates and removes the plugin through the original lifecycle and removes its npm package. Plugin data remains in app-private storage until removed by the relevant data action.

## Dependencies and Android requirements

Registry installation requires network access to `https://registry.npmjs.org/`. A local `.tgz` can be installed when its dependencies are included or available from the registry. Network use downloads packages and dependencies; it does not execute Harness or plugin code remotely.

npm uses `ignore-scripts=true` to avoid automatically executing third-party package installation scripts. Dependencies that require lifecycle scripts cannot rely on desktop Node installation behavior. A native addon must be built for the target Android ABI, NDK/Bionic, and embedded Node ABI; desktop Linux, macOS, or Windows binaries are not interchangeable.

Plugin JavaScript runs in the Android app process under the app UID. The app does not provide a per-plugin OS process, capability permission system, CPU/memory quota, or native crash isolation. Review package identity, version, and source before enabling a plugin.
