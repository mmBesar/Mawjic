# Mawjic build status

**Status: clean source baseline prepared; full builds still require GitHub Actions verification.** This environment does not have Cargo/Rust, Gradle, Android SDK/NDK, RPM, or AppImage tooling installed, and shell DNS cannot reach download hosts. Static checks are run locally; no APK or Linux package is claimed as built here.

## Confirmed failures from the previous repository's GitHub Actions logs

- RustSec's `audit-check` looked for `./Cargo.lock`, but Mawjic's lockfile is `rust-core/Cargo.lock`. The clean workflow installs pinned `cargo-audit` and runs `cargo audit --file rust-core/Cargo.lock` from repository root.
- Linux AMD64/ARM64 packaging failed before invoking AppImage because the expected jpackage app directory was absent. The clean workflow explicitly runs `:composeApp:createDistributable` before packaging and has the AppImage script locate/validate a jpackage app under `build/compose/binaries/main/app` with a diagnostic directory listing if missing.
- Android dependency metadata reported that `androidx.compose.runtime:runtime-saveable-android:1.12.0` required AGP 9.1.0 while the project uses AGP 8.11.1. The clean baseline pins Compose Multiplatform 1.10.3 (Jetpack Compose 1.10.5 / Lifecycle 2.10.0 per JetBrains release documentation), stable Compose Material 3 1.9.0, and AndroidX Activity Compose 1.12.4. Compose runtime/foundation/UI/resources now use explicit coordinates instead of deprecated `compose.*` aliases. This avoids the 1.12 Compose runtime line flagged as requiring AGP 9.1.0. The selected Kotlin 2.4.21 / AGP 8.11.1 / CMP 1.10.3 combination still requires hosted Gradle verification; no compatibility is claimed until CI builds it.
- `source-sanity.sh` previously scanned the entire workspace after builds and mistook generated `.so` outputs for committed binaries. The clean script checks Git-tracked files in a checkout and has an excluded-build-directory fallback when run from an extracted source archive.
- Gradle 8.14.3 warned that it is deprecated for future Kotlin Gradle Plugin releases. The clean baseline uses Gradle 8.14.6, with the official binary distribution checksum updated in both bootstrap files.
- Dependabot PRs were too noisy for this early project. The clean repository has no `dependabot.yml`; see `docs/CI.md` for how to disable security-update PRs in the repository settings if desired.

## Implementation in the clean baseline

- Rust `or_else` and `trim().split_whitespace()` Clippy findings corrected; the M3U ABI test now checks `tvg-name` precedence and title fallback.
- Linux outputs are AppImage, DEB and RPM for AMD64 and ARM64. Runtime/foundation/UI/resources use explicit CMP coordinates; Material 3 is explicitly pinned to stable 1.9.0 to avoid a moving alpha dependency. The raw jpackage `.tar.gz` is not published.
- AppImage tooling is version-pinned and checked against the GitHub release asset SHA-256 digest before execution. Native libraries and output ELF architecture are checked; the AppImage payload is extracted for a smoke test.
- GitHub Actions use Node 24-compatible major versions: checkout v7, setup-java v6, Gradle setup v6, upload-artifact v7, download-artifact v8, Android SDK setup v4, and softprops release v3.
- Release APKs require the configured keystore and secrets; debug signing is never substituted.

## Verification performed for this archive

Static checks run successfully in this workspace:

- `scripts/source-sanity.sh` (including dependency-inventory and localization checks).
- `bash -n` on the bootstrap, audit, AppImage fetch/package, signing and APK verification scripts.
- YAML syntax parsing for both GitHub Actions workflows.
- Gradle version/checksum consistency check and static workflow invariant checks.
- Archive integrity check after ZIP creation.

Not run here: Cargo tests/Clippy/audit, Kotlin compilation, Compose desktop tests, Android APK build, native jpackage tasks, AppImage generation, package installation, APK signature verification, or device playback. The environment has no Rust/Cargo, Gradle distribution, Android SDK/NDK or RPM/AppImage tools, and network downloads are unavailable. A green GitHub Actions run is the first full compile/test verification.

## Required first GitHub verification

1. Rust format, tests, Clippy, and Cargo audit must pass.
2. Linux AMD64 and ARM64 must build desktop tests, `createDistributable`, DEB, RPM, and AppImage; verify architectures and AppImage extraction.
3. Android ARM64 must build a debug APK with only `lib/arm64-v8a/libmawjic_core.so` and pass common Kotlin tests.
4. Only after those are green should a signed tag release be considered. Do not create a release tag before Android release signing secrets are configured and CI passes.
