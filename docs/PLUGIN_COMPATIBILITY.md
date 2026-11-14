# Original DeepSeek Harness Plugin Compatibility

## Compatibility target

The Android plugin screen accepts an original npm package name/version or original `.tgz` archive. The installed package retains its upstream ESM files, `package.json`, dependency declarations, and Cordis plugin export. The app does not rewrite plugin source, manifests, interfaces, or internal implementations, and does not substitute Kotlin plugin implementations. The runtime uses on-device npm, Javet Node Android i18n (Node.js v26.13.0 and V8 14.6 on the tested build), original Cordis, and `@deepseek-ai/cordis-plugin-loader`.

An incompatible package reports its actual dependency-installation, Cordis configuration/activation, or tool-execution error. A successful npm installation only means npm acquired the package; it does not provide DSH services, desktop commands, or native addons that the package may require.

## Android runtime bridge

1. Embedded Node.js runs inside the Android app process. Node and npm are managed by the app; users do not need to install a terminal or Node runtime.
2. npm resolves original packages and dependencies into the app-private `filesDir/plugins/` tree. Registry installs use the npm registry; `.tgz` archives are selected with SAF and installed locally.
3. The Cordis host creates the original Cordis context and loads the original Cordis Loader. The Loader's internal module import seam is connected to Node's module loader, and plugins load from their original ESM package entry points.
4. Original `apply(ctx, config)`, Cordis configuration validation, scopes/Fibers, `ctx.tools` schemas, and tool execution use upstream implementations. Enable, disable, and uninstall use Loader lifecycle operations.
5. Kotlin adapts the model loop and Android UI. Plugin tool calls still use `ctx.tools.execute()`. Cordis Session event snapshots are persisted locally and restore the original session event data after Node runtime recreation.

The assembled DSH service set is limited. The npm tree includes same-version dependencies such as `@deepseek-ai/dsh-agent`, `dsh-llm`, `dsh-scope`, `dsh-session`, `dsh-session-projection`, `dsh-system-prompt`, `dsh-tools`, `dsh-tool-executor-native`, `dsh-user-approval`, `dsh-typert-protocol`, `dsh-util-values`, and `dsh-workspace`. The Cordis context explicitly loads Agent, System Prompt, Tools, Native Tool Executor, and Session Projection services. It is not the complete upstream `web`/`desktop` bundle tree. Plugins requiring unassembled MCP, subagent/team, web, skills, jobs, sandbox, credential, or filesystem services can fail activation or execution.

## Original plugins exercised on Android

The instrumentation tests below were run individually on the `dsh-api36-arm64` Android API 36 `arm64-v8a` emulator. Each reported one test passed. The provider, filesystem, and Unicode-runtime tests have separate test source files in `app/src/androidTest/java/ai/comicine/dshmobile/plugins/`.

| Upstream package | Installation route | Exercised behavior |
|---|---|---|
| `@deepseek-ai/dsh-repeat-tool-reminder@0.2.2-rc.3` | npm registry package name and pinned version | Original `type: module` manifest and ESM entry; `@deepseek-ai/schemastery` dependency resolution; original Cordis configuration and invalid-threshold rejection; real `tools/post-execute` listener; repeated tool calls produce original additional context; disabling removes the listener; uninstall removes the npm directory and Room record. |
| `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3` | Original published `.tgz` archive | Original package name, version, manifest, and configuration; local resolution of `@deepseek-ai/schemastery` and `zod`; original `ctx.tools` call to `todo_write`; writes a real Session `todo/write` event; invalid multiple `in_progress` items produce the original tool error; recreating Node restores the existing Session event; uninstall removes the npm directory and Room record. |

The test also covers enabling both packages together, native Cordis configuration rejection, plugin Fiber disposal, runtime recreation, Session deletion, asynchronous cancellation, and a synchronous execution timeout. It uses upstream tarballs and registry dependencies rather than stubs, mock results, or Kotlin plugin substitutes.

`UpstreamFilesystemPluginsInstrumentedTest.kt` installs these three exact registry packages through the Android PluginManager and embedded npm runtime:

| Upstream package | Installation route | Exercised behavior |
|---|---|---|
| `@deepseek-ai/dsh-fs-local@0.2.2-rc.3` | npm registry package name and pinned version | Original Cordis filesystem service activates with an app-private temporary workspace as its configured cwd; path resolution, metadata lookup, and UTF-8 text reading are exercised. |
| `@deepseek-ai/dsh-working-directory@0.2.2-rc.3` | npm registry package name and pinned version | Original Cordis service activates with the filesystem provider and uses the configured app-private working directory for the Session. |
| `@deepseek-ai/dsh-tool-fs@0.2.2-rc.3` | npm registry package name and pinned version | Original `read` schema and implementation read a real relative workspace path; missing-path and invalid-offset errors, deactivation, tool deregistration, and uninstall are checked. The test config disables `read_image`, `write`, `edit`, and `apply_patch`. |

This filesystem test does not establish complete compatibility. Watchers, image attachments, mutations, patches, and other upstream filesystem operations were not exercised. The upstream `dsh-fs-local` backend is not a sandbox: its `cwd` controls relative path resolution but does not confine absolute or parent-relative paths. The test is read-only and uses only an app-private fixture.

## Original provider packages exercised on Android

`UpstreamProviderRuntimeInstrumentedTest.kt` installs the published `@deepseek-ai/dsh-llm-deepseek-api-key@0.2.2-rc.3` and `@deepseek-ai/dsh-credentials-local@0.2.2-rc.3` packages into an isolated app-private npm tree. It imports the original Cordis LLM, credentials, and provider packages without source changes; checks registration and model resolution for `deepseek-official` / `deepseek-flash`; then sends the upstream provider request to a test-only loopback HTTP/SSE endpoint. The test checks the POST path, authentication header, model and prompt payload, streamed text delta, and stop event. This verifies the on-device provider adapter path; it does not claim a live request to DeepSeek's service.

`UpstreamAgentLoopInstrumentedTest.kt` separately installs unmodified `@deepseek-ai/dsh-agent-loop@0.2.2-rc.3` together with the original DeepSeek API-key provider and `credentials-local`. It composes the original Cordis LLM, Session, Session Projection, System Prompt, Tools, Native Tool Executor, Agent Registry, AgentLoop, provider, and credentials services; creates an Agent through `ctx.agents.create()`, submits a user message through `followup()`, and waits with `whenIdle()`. On the API 36 arm64 emulator it completed a full turn through a test-only loopback SSE endpoint and asserted the original Session's user, assistant, step, and turn events. The credential is a mode-0600 temporary test fixture. This test does not wire the application Keystore to Cordis, does not mount JSONL persistence, and does not mean the product's Kotlin `HarnessEngine` has been replaced by the original AgentLoop.

## Original UI plugins exercised on Android

The Android app runs original web-client plugin bundles in an embedded WebView served from APK assets. Plugin bundles and dependencies are loaded from the app-private npm installation through the upstream client module loader, Cordis plugin loader, React renderer, and slot contracts. The WebView is an on-device UI runtime; it does not connect to an execution server. The two `.tgz` fixtures below were packed directly from `fork/deepseek-harness/apps/web/tests/fixtures/plugins/`; their `client.js` SHA-256 digests were checked against the upstream source files.

| Original upstream fixture | Installation and lifecycle exercised | UI behavior exercised |
|---|---|---|
| `@fixture/layout-bottom@0.0.1` | Install original tarball, activate, deactivate, uninstall, and remove its local package and Room record | Original React contribution renders in the Android plugin UI, its input is present, and its button changes and collapses the bottom slot. |
| `@fixture/input-extension@0.0.1` | Install original tarball, activate, open its UI from the native Plugins screen, deactivate, uninstall, and remove its local package and Room record | Original `plugins.bundle.config` UI opens from the plugin manager; its `conversation.input.activity` control mounts in the native Chat screen and inserts its captured text into the native Compose draft. |

The Android frame currently mounts these owners: `shell.bottom`, `shell.overlay`, `plugins.bundle.config`, `plugins.detail.actions`, `plugins.detail.badge`, and `plugins.detail.section`; the native Chat screen mounts the session-scoped `conversation.input.activity` owner. The tests above explicitly exercise the bottom slot, plugin configuration UI, and chat input insertion lifecycle. They verify real plugin code, not a Kotlin substitute or a mock UI. They do not cover every slot in the upstream client slot catalog. UI contributions whose owning surface is not mounted by the Android app are not currently visible, even when their package installs and its Cordis plugin activates. Chat input actions currently support text insertion, quote insertion, draft replacement, and submit; upstream attachment operations are not implemented.

## Plugin management behavior

| Operation | Android behavior | Current boundary |
|---|---|---|
| Discover and install | Enter an npm package specifier or choose a `.tgz` through SAF; resolve dependencies on-device | No upstream Plugin Market search, profile/bundle installation, arbitrary registry setting, or official-source signature verification. |
| Activate | Original Cordis Loader invokes the package entry point and attaches the original plugin Fiber | A plugin requiring an unassembled profile service can fail with its real loader error. |
| Configure | Edit a JSON object; Loader update lets the upstream schema validate and reload an enabled plugin | The editor does not provide the upstream profile's YAML, JavaScript marker, or complex overlay editing interface. |
| Resolve dependencies | npm resolves production and peer dependencies and checks lockfile integrity on-device | `ignore-scripts=true`; dependency lifecycle scripts and native builds are not run, and Android-specific dependencies may be unavailable. |
| Execute tools | Original `ctx.tools.schemas()` and `ctx.tools.execute()` | Kotlin hosts the current Agent loop; this is not full reuse of the upstream `dsh-agent-loop`. |
| Execute web-client UI | Original client bundle, Cordis Loader, React slot renderer, and Android-hosted UI tested with two unmodified upstream fixtures | Only the native host slots listed above are mounted; this is not the complete upstream Web/Desktop slot-owner tree. |
| Report errors | Configuration and tool errors flow to local UI, messages, and logs | There is no separate per-plugin stdout/stderr browser or detailed plugin diagnostics page. |
| Disable and uninstall | Original Loader performs Fiber/disposable cleanup; uninstall removes the plugin Room record and npm package | Cordis Session events remain until the user deletes the session, at which point the local deletion path destroys the Agent scope and snapshot. npm's package tree handles shared dependency removal. |
| Update | Reinstalling a specified version can replace an installed package | No automatic update, change-review, profile rollback, or compatibility-override interface. |

## Platform and runtime boundaries

- The complete upstream bundle/profile combinations are not assembled; device tests cover only the packages and operations listed above.
- The import seam uses an ESM helper at the npm project root and Node's native `import()` to preserve `exports` conditions and asynchronous module semantics. The tested packages do not use top-level `await`; packages that require it, custom loader hooks, and host-process CLI loaders need separate validation.
- npm lifecycle scripts are disabled. Dependencies that require `preinstall`, `install`, or `postinstall` cannot rely on those steps. Scripts that compile desktop binaries or download platform resources may not work on Android.
- Native Node addons must be built for the Android ABI, NDK/Bionic, and embedded Node ABI. Linux, macOS, and Windows addons are not directly reusable.
- Plugins share the app process and UID. There is no per-plugin OS process isolation, CPU/memory quota, Node capability enforcement, or native crash containment. Plugins can access data available to the Node runtime and can make network requests.
- The Node runtime is in the app process; npm dependency downloads happen during installation. Harness and plugin execution do not run on a network host.
- Physical phones, x86_64 emulators, lower Android API levels, long background execution, and system memory pressure require additional device testing.

The Javet bridge has a five-minute wall-time watchdog per Node Promise operation by default. Coroutine cancellation requests termination of active V8 execution. Instrumentation also tested cancelling a pending Promise and interrupting a synchronous loop with a one-second watchdog, after which the same runtime returned `42`. This exercises CPU execution termination on the tested arm64 emulator/runtime; it does not isolate JavaScript capabilities or memory, and a native addon crash can still terminate the app process.

An API 36 arm64 device test confirms that the selected Node i18n runtime parses Unicode property escapes used by original DeepSeek provider packages. This test does not by itself establish provider compatibility; package loading, Cordis registration, and real request/stream behavior are verified separately.

Plugin code can read app-private data available to Node.js and make network requests. Confirm the package name, version, and source before enabling a package.
