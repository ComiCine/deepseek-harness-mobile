# Third-Party Notices

Original ComiCine materials in this project are licensed under the GNU Affero General Public License Version 3 or any later version (AGPL-3.0-or-later). See [LICENSE](LICENSE), [ADDITIONAL_TERMS.txt](ADDITIONAL_TERMS.txt), and [COPYRIGHT.md](COPYRIGHT.md). These project terms do not replace or relicense third-party materials.

## DeepSeek Harness

The upstream DeepSeek Harness MIT license is preserved verbatim in [LICENSE.upstream-deepseek-harness](LICENSE.upstream-deepseek-harness). Upstream-derived materials remain subject to their applicable license notices.

The Android instrumentation-test assets include unmodified release tarballs of `@deepseek-ai/dsh-repeat-tool-reminder@0.2.2-rc.3` and `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3`, both declared MIT in their upstream package manifests. They are test fixtures for on-device compatibility testing.

## Embedded npm Runtime

`app/src/main/assets/npm-runtime/npm-11.20.0.tgz` packages npm 11.20.0, declared `Artistic-2.0`, for local dependency installation. Its archive contains npm's license and bundled dependency notices. SHA-256: `d1a92f40e6c407b84c3a00c3cf978a10b24fd42f153c527e2016cef7bb34a483`.

## Runtime and Compression Dependencies

- `com.caoccao.javet:javet-node-android-i18n:6.0.3` — Apache License 2.0, as declared by its artifact POM. Project: [caoccao/Javet](https://github.com/caoccao/Javet).
- `org.apache.commons:commons-compress:1.28.0` — Apache License 2.0. Project: [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/).

## Build and Application Dependencies

The Gradle build resolves AndroidX/Jetpack Compose/Room, Kotlin Coroutines, OkHttp, JUnit, Android Gradle Plugin, Kotlin Gradle Plugin, Gradle Wrapper, and their transitive dependencies. This file is not a generated per-artifact inventory of every resolved Gradle license. Review licenses for the exact release variant before distributing an APK and include any notices required by those components.

No credentials or repository tokens belong in this file or a distributed build.
