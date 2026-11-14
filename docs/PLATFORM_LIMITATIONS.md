# Android Platform Constraints

Harness and plugin execution remain on the Android device. The compatibility gaps listed here are platform and implementation boundaries; they are not routed to a server or SSH host.

## Upstream Harness capabilities

- The original Cordis ESM plugin path is implemented and has been exercised with two real upstream packages on an Android 36 arm64 emulator. The complete upstream `web`/`desktop` base profile, all plugin packages, and all DSH services are not assembled. A plugin that requires an unavailable service can report its actual loader or execution error.
- Kotlin currently hosts the Agent model loop. The app does not fully reuse the upstream `dsh-agent-loop`, profile/bundle builder, all hooks, or provider-neutral model layer.
- The primary provider integration is a DeepSeek Messages adapter. Multi-provider profiles, OAuth/account plugins, model catalog, and all upstream provider behaviors are not available as a complete set.
- Subagents, Agent Teams, workflows/PTC, MCP transports/resources, web search/fetch, skills/personas, goals/plans, and persistent jobs/schedules are not implemented as full upstream feature sets.
- File/workspace tools use app-private storage and SAF single-file import/export. The full upstream filesystem adapter, JSONL session-format migration, attachments, images, Office, desktop GUI, and Computer Use integrations are absent.
- Session export uses the Android app's format and does not implement every upstream versioned JSONL format. Room schema migration and data-cleanup behavior require additional implementation.
- The Android UI is native Compose and does not include every upstream Web/Desktop screen, plugin market, profile editor, command palette, or configuration page.

See [Feature Parity](FEATURE_PARITY.md) for the complete source inventory.

## Original plugin runtime

- The device-tested packages are `dsh-repeat-tool-reminder@0.2.2-rc.3` and `dsh-tool-todo@0.2.2-rc.3` on an Android 36 arm64 emulator using real npm, Cordis, and Node execution.
- The Cordis host assembles a subset of DSH services. Packages that require unassembled MCP, subagent/team, web, skills, jobs, sandbox, credential, or filesystem services need those local services before they can run.
- npm lifecycle scripts are disabled. Dependencies that require `preinstall`, `install`, or `postinstall`, binary downloads, or build steps may fail to install or run.
- Native extensions must match Android `arm64-v8a` or `x86_64`, Bionic, the NDK, and the embedded Node ABI. Desktop native addons are not directly reusable; the app has no desktop ABI translation layer.
- Plugins share the app process and UID. There is no per-plugin OS process, Node capability filter, filesystem permission proxy, memory quota, or native crash containment. Javet's watchdog can terminate a timed-out V8 execution; it does not constrain memory growth or isolate a native crash.
- The plugin configuration editor accepts JSON objects. It does not reproduce the upstream profile's complete YAML, Loader JavaScript marker, and complex overlay editing experience.
- Physical phones, x86_64 Android emulators, lower Android API levels, long background runs, and system memory pressure need separate device validation.

## Shell, files, and system APIs

Shell commands use Android `/system/bin/sh` with the app's ordinary UID inside its sandbox. The app does not require root, SSH, or a companion computer. Available commands depend on the device image; desktop Bash/PowerShell, PTY, Windows/macOS programs, global `/usr` paths, and desktop GUI tools must not be assumed. The shell adapter supports working directory, standard input/output/error, exit status, timeout, and cancellation. Android can still stop background processes.

The default workspace is `filesDir/workspace`, private to the app. Canonical path checks reject traversal outside this directory. SAF supports user-authorized single-file import and export; it does not mount arbitrary filesystem roots or provide a persistent SAF tree workspace.

## Lifecycle and background execution

A `dataSync` foreground service shows active work and a cancellation action. Android version-specific start rules, service time limits, battery policy, user force-stop, and process pressure can interrupt a task. The app does not promise unrestricted background execution.

Room stores session and task state. After process death, unfinished streams are marked interrupted; old paid model requests are not automatically resent. Node plugins are terminated with the app process. At the next launch, enabled configuration is restored locally and the Cordis context is rebuilt when plugin schemas or tools are needed. Instrumentation tests cover Node runtime teardown/recreation, not every Android app force-stop or reboot scenario.

## Verification scope

Executed Android instrumentation results are listed in [Plugin Compatibility](PLUGIN_COMPATIBILITY.md). Those results do not cover a live DeepSeek account and network response, every plugin package, all ABIs, physical-device power/notification behavior, or native addons. A successful Gradle build establishes that build tasks completed; it does not establish runtime compatibility for paths that were not exercised.
