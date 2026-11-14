# Third-party notices

## DeepSeek Harness

This Android project is based on behavior and interfaces from `https://github.com/deepseek-ai/deepseek-harness`. The upstream MIT notice is preserved in [`LICENSE`](LICENSE). Android UI and compatibility bridge code are new implementation code. The original Cordis runtime packages are installed by users on the device; they are not copied from the read-only mobile reference repository.

The Android instrumentation-test assets include unmodified release tarballs of `@deepseek-ai/dsh-repeat-tool-reminder@0.2.2-rc.3` and `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3`, both declared MIT in their upstream package manifests. They are test fixtures for real on-device compatibility tests.

## Embedded npm runtime

`app/src/main/assets/npm-runtime/npm-11.20.0.tgz` packages npm 11.20.0, declared `Artistic-2.0`, for local dependency installation. Its bundled archive contains npm's own license and bundled dependency material. SHA-256: `d1a92f40e6c407b84c3a00c3cf978a10b24fd42f153c527e2016cef7bb34a483`.

## Runtime and compression dependencies

- `com.caoccao.javet:javet-node-android:6.0.3` — Apache License 2.0, as declared by its artifact POM. Project: [caoccao/Javet](https://github.com/caoccao/Javet).
- `org.apache.commons:commons-compress:1.28.0` — Apache License 2.0. Project: [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/).

## Other build and application dependencies

The Gradle build resolves AndroidX/Jetpack Compose/Room, Kotlin Coroutines, OkHttp, JUnit, Android Gradle Plugin, Kotlin Gradle Plugin, Gradle Wrapper and their transitive dependencies. This file is not a generated per-artifact inventory of every resolved Gradle license. Before distributing a release APK, generate and review a license report for the exact release variant and include any required notices.

No credentials or repository tokens belong in this file or a distributed build.
