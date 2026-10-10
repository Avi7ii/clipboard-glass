# Third-party notices

The following dependencies retain their upstream licenses. This document describes dependency licenses only.

| Component | Version | Use | License | Upstream metadata |
| --- | --- | --- | --- | --- |
| libxposed API | 101.0.1 | Compile-only; not bundled | Apache-2.0 | [Maven Central](https://central.sonatype.com/artifact/io.github.libxposed/api/101.0.1) |
| libxposed service | 101.0.0 | Bundled runtime classes | Apache-2.0 | [Maven Central](https://central.sonatype.com/artifact/io.github.libxposed/service/101.0.0) |
| libxposed interface | 101.0.0 | Bundled runtime classes | Apache-2.0 | [Maven Central](https://central.sonatype.com/artifact/io.github.libxposed/interface/101.0.0) |
| Kotlin standard library | 2.2.10 | Bundled runtime classes | Apache-2.0 | [Kotlin v2.2.10](https://github.com/JetBrains/kotlin/tree/v2.2.10) |

The complete Apache License 2.0 text is in [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt). The upstream Kotlin notice is preserved in [licenses/Kotlin-NOTICE.txt](licenses/Kotlin-NOTICE.txt).

The Android SDK and libxposed API are build inputs. The repository does not redistribute Android or ColorOS system APKs, framework libraries, LSPosed binaries, device configuration, or signing keys. Runtime dependency artifacts are downloaded by `build.py` with pinned SHA-256 checksums.
