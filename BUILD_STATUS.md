# Mawjic build status

**Base revision `4a085312886534d79d0f18380e377b7aecf5558e`: GitHub Actions CI run #6 passed on 2026-10-27.** The attached source archive matches that revision. This player-OSD source update is not part of the passing run until applied and tested in a new workflow run. This authoring environment has Java installed but no Gradle, Cargo, or Rust compiler; Android UI/device behavior cannot be verified here. Keep base-CI evidence distinct from checks performed on this update.

## Confirmed failures from the previous repository's GitHub Actions logs

- RustSec's `audit-check` looked for `./Cargo.lock`, but Mawjic's lockfile is `rust-core/Cargo.lock`. The clean workflow installs pinned `cargo-audit` and runs `cargo audit --file rust-core/Cargo.lock` from repository root.
- Linux AMD64/ARM64 packaging failed before invoking AppImage because the expected jpackage app directory was absent. The clean workflow explicitly runs `:composeApp:createDistributable` before packaging and has the AppImage script locate/validate a jpackage app under `build/compose/binaries/main/app` with a diagnostic directory listing if missing.
- Android dependency metadata reported that `androidx.compose.runtime:runtime-saveable-android:1.12.0` required AGP 9.1.0 while the project uses AGP 8.11.1. The clean baseline pins Compose Multiplatform 1.10.3 (Jetpack Compose 1.10.5 / Lifecycle 2.10.0 per JetBrains release documentation), stable Compose Material 3 1.9.0, and AndroidX Activity Compose 1.12.4. Coil 3.6.3 was updated upstream to Compose 1.12.0 and Android compile SDK 37; its modules are now downgraded to 3.5.0, whose upstream release notes specify Compose 1.11.1 and compile SDK 36. Compose runtime/foundation/UI/resources use explicit coordinates instead of deprecated aliases. At the time this note was first written, the selected Kotlin 2.4.21 / AGP 8.11.1 / CMP 1.10.3 + Coil 3.5.0 combination still required hosted verification; base CI run #6 later passed the configured Android build job.
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

The authoring environment cannot run Cargo/Gradle, Android SDK/NDK, RPM or AppImage tooling. GitHub Actions run #6 later passed the configured Rust, Linux package, and Android debug build/common-test jobs for the base commit. That run does not include the separate Android player-OSD update below. The prior AppImage colour-conversion crash was corrected in shared code, but a fresh packaged-AppImage launch remains unverified.

## Follow-up: Linux runtime crash and Android dependency (2026-10-27)

- AppImage starts the desktop Compose application; the reported `Theme.kt` stack trace is not Android running inside the AppImage. It is a shared-code colour conversion bug: ARGB integers were passed to Compose's packed `Color(ULong)` constructor, which produced invalid/NaN colour components and crashed foreground-contrast calculation. Colour construction and Android system-bar transfer now use ARGB `Int`/`toArgb()` consistently. A common test checks finite, correct RGB values. This still needs a fresh packaged-app launch test.
- Android CI's earlier AAR metadata error named `androidx.compose.runtime:runtime-saveable-android:1.12.0`, selected by Coil 3.6.3. Coil modules were pinned to 3.5.0. The Android ARM64 debug-build job passed in base CI run #6; the workflow's dependency-insight diagnostic is failure-only and therefore was not run separately on that successful build.
- A fresh packaged-AppImage launch is still not recorded in this status file; the green CI run proves package creation, not desktop runtime behavior.

## Base revision CI evidence and next gate

GitHub Actions [run #6](https://github.com/mmBesar/Mawjic/actions/runs/40512040286) passed for base commit `4a085312886534d79d0f18380e377b7aecf5558e` on 2026-10-27. Its configured Rust format/tests/Clippy/audit, Linux AMD64/ARM64 packaging and Android ARM64 debug build/common-test jobs succeeded. The run is evidence for the base commit only; it does not include the player-OSD update in this ZIP.

Before considering the OSD update complete, run the full workflow after applying it. CI needs to compile the revised `PlaybackPane` expect/actual contract and pass the existing common tests plus the new static source-contract checks. Then validate Android fullscreen/system bars, OSD auto-hide and touch-to-show, keep-screen-on release, logos and programme metadata on an actual device. Do not create a release tag before update CI passes and Android release signing secrets are configured.


## Real-device feedback follow-up (2026-10-27)

Reported by maintainer after Linux and Android artifacts launched:

- XMLTV sources still failed. The parser rejected all `DOCTYPE` declarations, including ordinary external `xmltv.dtd` references. It now skips external declarations without fetching them; internal DTD subsets/entities remain rejected. Playlist channels are now committed before optional guide loading so an EPG failure cannot blank the channel list. Added Rust ABI and common-parser regression fixtures.
- Android layout hid much of the content behind a fixed sidebar and wide channel rows. Compact screens now use bottom navigation, omit the hero card, and stack channel rows; the guide's channel column is narrower on compact layouts.
- Added an optional Android external-player chooser for streams Media3 cannot play.
- Added a persistent, explicitly unsafe self-signed TLS preference (off by default). It relaxes certificate-chain checks for source reads and HTTP-based playback, but retains hostname verification; prefer installing a proper CA/server certificate. Linux mpv receives `--tls-verify=no` only when the user opts in. Image loading still requires real-world verification on Linux.
- Researched IPTVMine-Pro, IPTVnator, Kodi PVR IPTV Simple, Coil network setup, and Android Network Security Configuration. See `docs/UX_RESEARCH.md`.
- Reported Gradle 8.14.6 “out-of-date” notices are annotations, not build failures. Keep the currently passing wrapper; Kotlin 2.4.21's fully supported Gradle range ends at 9.7.0, but AGP compatibility must also be checked before moving to Gradle 9.

At the time this historical entry was written, the follow-ups had received static review only. Base CI run #6 subsequently passed the configured build/test/package jobs. Device-level playback, TLS, real-feed XMLTV, Linux remote logos, and layout interactions remain runtime checks and must not be treated as verified from CI alone.


## UX/runtime follow-up from maintainer testing (2026-10-27)

- The maintainer reports a successful baseline CI for Rust/audit, Linux AMD64/ARM64 packaging and Android ARM64 compilation. The Gradle 8.14.6 annotation is informational, not a build failure: keep the passing 8.14 line while Kotlin 2.4.21 and AGP 8.11.1 are pinned; do not jump to Gradle 9 until the Kotlin/AGP compatibility matrix is deliberately upgraded together.
- XMLTV: skip a standard external `DOCTYPE` without fetching it; continue rejecting internal subsets/entities. Commit playlist channels before optional guide parsing. Add gzip XMLTV (`.xml.gz`) support with 64 MiB raw and decompressed limits.
- Phone UI: hide the fixed desktop sidebar on compact screens, use bottom navigation and stacked channel rows, scroll the settings page, and calculate EPG positions using its actual compact/wide hour width. These changes need Android-device validation.
- Playback: Android provides an external-player chooser for supported stream URLs and for schemes Media3 does not support in-app. Real provider/codec playback remains unverified.
- Artwork: Linux failures render the channel initial and log only host + exception type, never the URL path/query. This is a diagnostic step, not a claim that every logo loads. The next AppImage run should be launched from a terminal to capture `Mawjic artwork failed` messages.
- TLS: self-signed/untrusted certificate-chain acceptance is off by default and intentionally unsafe; hostname verification remains enabled. Reloading source settings now retries source loads after a TLS setting change. Correct server certificates remain the preferred solution.
- Research references and product choices are in `docs/UX_RESEARCH.md`. No third-party source code was copied.
- Not verified here: Rust compilation/tests, Kotlin compilation, AppImage launch, real `.xml.gz` feed, Linux remote logos, self-signed playback, or phone layout. Run GitHub Actions and manual runtime checks before declaring complete.

## Usability, playback and XMLTV subtitle follow-up (2026-10-27)

- Linux artwork: the shared Compose root explicitly configures Coil 3 with its Ktor network fetcher and the platform-specific Ktor engine. Linux remote images still need runtime verification against the maintainer's real image hosts.
- XMLTV metadata: preserve `<sub-title>` and `<episode-num>` through the Rust JSON ABI and Kotlin model; prefer the `onscreen` episode number, keep description separate, and read text-valued `<image>` poster elements when no `<icon src>` was supplied. The common fixture count now matches its two programme records.
- Android playback: attach Media3 error listeners before initial preparation, configure HTTP timeouts/user-agent/redirects and inferred MIME, and make one bounded HLS retry for extensionless HTTP(S) IPTV endpoints after a first playback error. Display a redacted cause message for diagnosis; real streams must still be tested.
- Android external playback: persist In-app/VLC/MPV selection, auto-launch the selected installed package, declare VLC/mpv-android package visibility, and retain an in-app fallback if launching fails. Android player/intent handoff needs device validation.
- Compact UI: keep the 15-second current-time EPG marker, subtitle/episode line on programme cards and details, collapsible compact search and appearance controls, local-time greeting, and platform-filtered colour source settings.
- Code-audit note: static scripts and source sanity can be checked here, but this environment has no usable Gradle or Cargo toolchain. Hosted CI must compile and run Rust/Kotlin tests; manual device tests must verify Linux logos, Android stream compatibility, MPV/VLC handoff, current-time marker visuals, and self-signed TLS behavior.

## CI follow-up: Coil import and XMLTV sanity assertion (2026-10-27)

- The shared Compose source calls `ImageLoader.Builder.crossfade(true)`. Coil 3.5.0 exposes `crossfade` as an extension in `coil3.request`; the missing import caused the Kotlin compile failure. `App.kt` now imports `coil3.request.crossfade`.
- The source-sanity check previously required the Rust XMLTV parser to contain the exact text `"image" => if let Some(p)`, making it sensitive to harmless formatting/refactoring. It now verifies the `<sub-title>` and `<episode-num>` handling, while existing common tests cover XMLTV programme artwork and `<image>` fallback.
- These corrections are present in base revision `4a085312886534d79d0f18380e377b7aecf5558e`, for which GitHub Actions CI run #6 passed. The OSD changes documented below are a separate update and still require a fresh workflow run.


## Follow-up: XMLTV offset test correction

- CI failure `nativeXmltvParserNormalizesExplicitOffsetsAndArtwork` was caused by the test calling `guide.programmes.single()` after explicitly asserting that the parser returned two programmes. Kotlin correctly throws `IllegalArgumentException` when `single()` sees two elements; this was a test bug, not evidence of an XMLTV parser defect.
- The test now verifies both UTC start timestamps independently: 01:00 +0200 -> 2023-12-31 23:00 UTC (1704063600), and 02:00 +0200 -> 2024-01-01 00:00 UTC (1704067200). It also retains the channel-logo and both programme-artwork assertions.
- The correction was included in base revision `4a085312886534d79d0f18380e377b7aecf5558e`; GitHub Actions CI run #6 later passed on that revision on 2026-10-27. See the current run linked in the project handoff. This confirms the run's configured tests/build steps, not XMLTV behavior against every real-world feed.

## Android player OSD follow-up (2026-10-27; update ZIP, not yet in CI)

- Added source wiring for channel logo with initial fallback, current programme title/subtitle, auto-hiding Mawjic OSD, fullscreen/system-bar toggle, back-to-windowed behavior, compact wrapping controls, and status-bar insets.
- Tied `PlayerView.keepScreenOn` to Media3's `onIsPlayingChanged`; clear it when playback stops, the player view is released, or the playback effect is disposed.
- Retained the existing Media3 player, error listener ordering, bounded HLS retry, track-selection dialogs, external-player fallback, and channel switching path.
- Added source-sanity regression guards for the player contract. They are static source checks, not Kotlin compilation or runtime tests.
- **Not yet verified:** Kotlin/Gradle compilation of this ZIP; CI tests; fullscreen/system-bar behavior across Android API levels; OSD hit-testing/autohide in portrait and landscape; screen-awake pause/disposal behavior on a physical device; remote logo fetch/fallback; programme metadata display after channel switching.
- Required next evidence: run the full GitHub Actions workflow after applying the ZIP. Then test on a phone: play/pause, tap to reveal, 5-second hide during playback, paused/error controls remain visible, fullscreen toggle/back/close restoration, screen sleep after pause/close, portrait/landscape controls, logo failure fallback, and programme title/subtitle changes when switching channels.


## Linux artwork diagnostic gate (not fixed by this update)

- The current source already registers Coil 3's Ktor network fetcher in `MawjicApp()` and includes the JVM Ktor engine under `desktopMain`. Do not add a second fetcher or change TLS behavior without runtime evidence.
- Linux remote logo loading remains unverified. Launch the AppImage from a terminal and capture sanitized `Mawjic artwork failed: host=… error=…` lines for the actual failing host(s). The diagnostic deliberately excludes URL paths/query strings, which may contain credentials. Check provider redirects, status codes, content types and certificate failures from those logs before choosing a fix.


## Android Compose callback compile fix (2026-10-27; awaiting CI)

- The first CI build of the Android player OSD update failed during `:composeApp:compileDebugKotlinAndroid`. The reported `@Composable invocations can only happen from the context of a @Composable function` error points to the Audio and Subtitles button handlers in `Playback.android.kt`.
- Root cause in the source: `tr()` is `@Composable` because it reads `LocalAppLanguage.current`, but it was called from ordinary `TextButton.onClick` callbacks when constructing `TrackSelectionDialogBuilder`. The dialog builder itself is not the problem.
- Fix: resolve `audioTrackLabel` and `subtitlesTrackLabel` during `PlaybackPane` composition and capture those plain strings in the click callbacks. Add source-sanity regression guards against calling `tr()` inside these track-dialog builder expressions.
- Static checks and guarded-updater tests are run for this follow-up bundle. **Not yet verified:** Kotlin/Gradle compilation, hosted CI after this fix, or Android runtime behavior. The next CI run is the authority for compilation.
