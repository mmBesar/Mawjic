# Third-party components

The versions below are declared by the build. This is a dependency inventory, not a substitute for the licence notices packaged with the exact transitive dependency graph. Before the first public binary release, generate and review a complete dependency licence report from the resolved Gradle graph and archive it with release records.

| Component | Pinned version / source | Purpose | Licence reference |
|---|---|---|---|
| Compose Multiplatform | 1.10.3 (Jetpack Compose 1.10.5; Lifecycle 2.10.0) | Cross-platform UI, runtime, foundation, resources and desktop application | [JetBrains Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| Compose Material 3 Multiplatform | 1.9.0 (Jetpack Material 3 1.4.0) | Stable Material components; deliberately pinned independently of CMP 1.10.3 | [JetBrains Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) |
| Kotlin Multiplatform / Serialization | 2.4.21 / JSON 1.11.0 | Kotlin build and JSON decoding | [Kotlin](https://github.com/JetBrains/kotlin) |
| Android Gradle Plugin | 8.11.1 | Android packaging | [Android tools](https://developer.android.com/studio) |
| AndroidX Activity Compose | 1.12.4 | Android activity result APIs and Compose host | [AndroidX Activity](https://developer.android.com/jetpack/androidx/releases/activity) |
| AndroidX Media3 | 1.11.1 | Android media playback, HLS, DASH, RTSP, UI | [AndroidX Media3](https://github.com/androidx/media) |
| Coil 3 | 3.5.0 | Lazy artwork loading and image cache; release notes target Compose 1.11.1 and Android compile SDK 36 | [Coil](https://github.com/coil-kt/coil) |
| Ktor client | 3.6.0 | Coil network engine integration | [Ktor](https://github.com/ktorio/ktor) |
| JNA | 5.19.1 | Android AAR provides Android native dispatch; desktop JVM JAR calls the Rust C ABI | [JNA](https://github.com/java-native-access/jna) |
| kotlinx.coroutines | 1.11.0 | Coroutine and IO dispatch | [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| Rust standard library | target Rust stable | Parser and C ABI; no third-party Cargo crates | [Rust](https://www.rust-lang.org/) |
| mpv | host distribution package | Linux playback process (not bundled) | [mpv](https://github.com/mpv-player/mpv) |

Mawjic's own source is MIT licensed. The app does not bundle mpv or redistribute media/EPG/artwork. Build-produced binaries will bundle the dependency versions resolved by Gradle; retain their copyright and licence obligations when redistributing them.
