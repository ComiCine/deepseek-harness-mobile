# Android Architecture and On-Device Execution

## Execution architecture

```mermaid
flowchart LR
  UI[Compose UI] --> VM[HarnessViewModel]
  VM --> H[On-device Kotlin HarnessEngine]
  H -->|Configured model requests and SSE| API[Model provider API]
  H --> Tools[On-device tool executor]
  Tools --> FS[App-private workspace]
  Tools --> SH[/system/bin/sh under app UID]
  Tools --> PM[PluginManager]
  PM --> Node[Embedded Javet Node.js]
  Node --> Cordis[Original Cordis context and loader]
  Cordis --> P[Unmodified upstream ESM plugins]
  Cordis --> S[(Upstream session event snapshots)]
  VM --> DB[(Room / SQLite)]
  H --> DB
  PM --> PKG[(App-private npm package tree)]
  VM --> KS[Android Keystore]
```

Model inference, network calls explicitly initiated by plugins, and npm package downloads are external network access. Harness orchestration, Cordis loading, plugin execution, shell commands, workspace operations, application state, and persistence run on the Android device. The app does not use a Harness server, SSH host, desktop companion, network execution queue, or remote plugin runtime.

## Upstream plugin integration

The app uses the Javet Node Android runtime and an app-private npm project to install original npm packages. Package ESM entry points, `package.json` files, dependency declarations, and plugin implementations remain in their installed upstream form. They are not converted to an Android-specific manifest or CommonJS plugin ABI.

`app/src/main/assets/cordis-host.js` creates the original Cordis context, loads the upstream `@deepseek-ai/cordis-plugin-loader`, and assembles the DSH services selected by the Android profile. The loader's `ctx.loader.internal.import` seam connects to Node module loading. Plugins use the original loader's `create`, `update`, `remove`, and Fiber lifecycle behavior without modifying plugin source, interfaces, or manifests.

The Kotlin `HarnessEngine` currently hosts the model loop. It obtains callable tool definitions from the original `ctx.tools.schemas(agent)`, requests approval where required, and sends tool calls to upstream `ctx.tools.execute()`. Results, errors, and additional context are written to local session history and can feed the next model request. The Android Agent/session seam uses upstream Cordis APIs, but this app does not assemble the complete upstream `dsh-agent-loop`, profile/bundle tree, or every upstream service. Package installation alone does not guarantee that the package's required services are available; see [Plugin Compatibility](PLUGIN_COMPATIBILITY.md).

Cordis Session event snapshots are stored under the app-private `filesDir/plugins/sessions/` directory, using a SHA-256 hash of the session ID as the local filename. Room stores chat messages, task state, and execution logs. Instrumentation coverage verifies Cordis Session restoration after a Node runtime recreation. Deleting a session also destroys its Agent scope and removes its snapshot.

## Module boundaries

| Layer | Implementation | Responsibility |
|---|---|---|
| Android UI | `app/src/main/java/ai/comicine/dshmobile/ui/` | Compose chat, sessions, plugins, workspace, logs, settings, language selection, and tool approval |
| App state and lifecycle | `HarnessViewModel.kt`, `execution/ExecutionService.kt` | UI StateFlow, foreground notification, cancellation, and Android lifecycle integration |
| Harness loop | `engine/HarnessEngine.kt` | Session tasks, concurrency gate, streaming model requests, Agent steps, and Room state |
| Provider | `engine/DeepSeekMessagesClient.kt` | DeepSeek Messages HTTPS, SSE parsing, retries, and request cancellation |
| Tool execution | `engine/ToolExecutor.kt` | App-private workspace, path checks, shell, and Cordis tool integration |
| Upstream plugins | `plugins/PluginManager.kt`, `plugins/EmbeddedNodeRuntime.kt`, `assets/cordis-host.js` | Local npm installation, dependency resolution, Cordis lifecycle, and Node execution |
| Local persistence | `data/` and `filesDir/plugins/` | Room records, npm package tree, Node runtime, and Cordis Session data |
| Credentials | `security/CredentialStore.kt` | Keystore-derived AES-GCM key and local API-key encryption |
| Localization | `res/values/`, `res/values-zh-rCN/`, `res/xml/locales_config.xml` | English and Simplified Chinese resources and per-app language settings |

## Tool-call lifecycle

1. `HarnessEngine` saves the user message to Room and obtains the enabled plugins' original tool schemas from the local Cordis context.
2. The Kotlin provider adapter sends the conversation and tool schemas to the configured model API. SSE deltas appear in the UI and are periodically persisted.
3. When the model requests a tool, the user confirms actions that require approval.
4. The original `ctx.tools.execute()` in embedded Node handles upstream Cordis plugins. Actual tool results and errors are preserved; Kotlin reimplementations do not replace upstream plugin behavior.
5. Plugin context and tool results are saved locally and may be included in the next model request.
6. Completion, failure, or cancellation updates local task state and logs. The app does not resend a paid model request after process death; unfinished streams are marked interrupted.

Different sessions can run concurrently, while a second task cannot start in the same session. The concurrency limit is configurable and constrained by device memory and CPU safeguards. The embedded Node runtime uses a single-threaded dispatcher. Each bridge operation has a five-minute watchdog by default; coroutine cancellation requests Javet to terminate the active V8 execution.

## Data, permissions, and security

- Conversations, messages, tasks, logs, settings, npm packages, plugin configuration, and Cordis Session events are stored in app-private local storage.
- Android Keystore protects the AES-GCM key used for the API key. The Kotlin provider reads the key for model requests; the Cordis Node context does not receive the credential service directly.
- Plugins share the Android app UID and process. Node.js can use its available built-in modules and the app sandbox permissions. Tool approval is not a Node capability sandbox.
- The upstream Cordis loader supports JavaScript-valued configuration expressions, so Javet enables V8 evaluation and code generation. Plugins run as trusted in-process code; the watchdog is not a security sandbox.
- npm installation uses `ignore-scripts=true`. Dependencies that require install scripts or desktop native builds do not receive those build steps.
- Selecting an original `.tgz` through SAF reads the archive and installs it into the app-private package tree. Plugin execution is never delegated to another device or service.

## Android execution constraints

- Minimum API 26; compile and target API 36; packaged ABIs are `arm64-v8a` and `x86_64`.
- A `dataSync` foreground service displays an active-task notification and a cancellation action. Android background restrictions, battery policy, user force-stop, and service timeouts can interrupt long-running tasks.
- Room and Cordis Session files live in app-private persistent storage. Session restoration after Node runtime recreation is covered by instrumentation tests. Android app force-stop recovery and upgrade migrations require separate device validation.
- Per-app locale selection uses AndroidX AppCompat and the Android locale configuration. The in-app selector supports system default, English, and Simplified Chinese.

See [Feature Parity](FEATURE_PARITY.md), [Plugin Compatibility](PLUGIN_COMPATIBILITY.md), and [Platform Limitations](PLATFORM_LIMITATIONS.md) for the source audit and platform behavior inventory.
