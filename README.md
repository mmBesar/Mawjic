# Mawjic

**Streaming, your wave.**

Mawjic is a cross-platform IPTV client for Linux AMD64/ARM64 and Android ARM64, with a Rust parser core and Compose UI. The interface offers English and Arabic; choosing Arabic persists the setting and applies right-to-left layout. Channel and programme names/descriptions remain in the language supplied by the playlist or XMLTV guide.

> **Build status:** this repository has not yet been compiled in the authoring environment. It contains source, tests, build scripts, and GitHub Actions workflows, but no APK or Linux binary is claimed as built or tested. See [`BUILD_STATUS.md`](BUILD_STATUS.md) for exact environment limits and the commands needed to verify it.

## What is implemented in source

- Rust C ABI for M3U/M3U8 `#EXTINF` metadata and XMLTV channel/programme parsing, including common XML entities, numeric offsets, bounded input, and disabled DTD/external entity declarations.
- Kotlin multiplatform UI with Live TV, favourites, channel/group search, current/next programme details, a six-hour horizontally scrollable EPG timeline, programme artwork, and playback/channel switching UI.
- Channel logo resolution from M3U `tvg-logo`, then XMLTV channel icons; programme artwork from XMLTV `<icon src="…">`. Coil loads remote images with its image-loader caching. No third-party site scraping or invented artwork endpoints are used.
- Persistent settings for all four Catppuccin flavours and all fourteen named accents, system/light/dark/AMOLED appearance, Android Dynamic Color, and Linux XDG Desktop Portal accent/colour-scheme integration with fallbacks.
- Android playback via AndroidX Media3/ExoPlayer and HLS, DASH, RTSP modules. Audio and subtitle track selection are provided by Media3 controls. The Android release build is configured to contain only ARM64 native libraries.
- Linux playback via the locally installed `mpv` executable. The stream opens in an `mpv` fullscreen window; its on-screen controls provide audio/subtitle selection. Linux channel-switch buttons send commands over a private Unix-domain socket.
- Local file picker support on desktop and Android, URL sources, and bounded reads for playlists/guides.
- Rust parser unit/integration tests, common Kotlin integration tests, Linux package tasks, GitHub CI, and a tag-triggered signed release workflow.

## Deliberate scope limits

This is **not yet a verified release**. The build and device behaviour must be validated by CI/on real devices. The current XMLTV reader is a deliberately small, dependency-free parser for common XMLTV structures, not a complete validating XML implementation; malformed XML nesting, uncommon namespaces, or unusual XMLTV variants may not behave like a full XML parser. Timestamps with an explicit `±HHMM` offset are converted to UTC; timestamps without an offset are interpreted as UTC (not inferred from a local timezone or DST database).

Tunarr and ErsatzTV are supported by entering their generated standard M3U and XMLTV URLs from their own UI; no server-specific APIs have been implemented or independently tested. There is no TMDB/TVDB enrichment, catch-up/time-shift, Xtream Codes login, DRM, custom HTTP-header editor, or automatic periodic playlist/EPG refresh yet. Programme poster/thumb/backdrop art is shown when the XMLTV source includes an icon; the application does not fetch unrelated artwork from third-party catalogues.

The Linux player is an external `mpv` window, not an in-process video surface. `mpv` must be installed on the system and supported stream formats depend on that build. Android playback formats depend on Media3 and platform decoder support. Do not assume every provider, codec, authentication method, or stream URL works.

## Requirements

- JDK 21 (Gradle itself can run with JDK 17+, but use JDK 21 for the CI setup below).
- Rust stable with `x86_64-unknown-linux-gnu` or `aarch64-unknown-linux-gnu` for a Linux build.
- For Android: Android SDK Platform 36, Build Tools 36.0.0, NDK 27.2.12479018, Rust target `aarch64-linux-android`, and the Rust Android NDK linker.
- Linux runtime: `mpv`; a compatible glibc baseline matters for portable distributions. The `.deb` package is built on the matching architecture. XDG Desktop Portal support is optional; missing/unavailable settings fall back gracefully.
- Internet access is required on first build to download Gradle and Maven/Cargo dependencies.

The bootstrap script `./gradlew` downloads Gradle 8.14.6 and verifies the official distribution SHA-256 before running it. **This clean source baseline uses a small checksum-pinned bootstrap script, not Gradle's standard `gradle-wrapper.jar`**: this environment had no Gradle installation and could not reach download hosts to generate the official wrapper. Once connected, regenerate the standard wrapper using the instructions in [Build and test](#build-and-test).

See [CI and dependency automation](docs/CI.md) for the workflow, artifact retention, and why Dependabot version-update PRs are disabled in the starter repository.

## Recommended workflow on NixOS (GitHub-first)

You do not need to install Rust, Gradle, the Android SDK, or the NDK on your laptop just to run project builds. Push source changes to your GitHub repository and open **Actions → CI**. Hosted runners compile and test Rust/Kotlin, package Linux AMD64/ARM64, and build an ARM64 debug APK. CI uploads the Linux packages and debug APK as temporary workflow artifacts (retained for seven days); the APK is explicitly debug-signed and is not a release/update replacement. Only a version tag triggers the release workflow, which requires the configured private signing key and uploads the verified signed APK plus Linux packages to GitHub Releases. The first CI run is the actual compilation gate; authoring-time static checks are not a substitute for it.

## Build and test

### Rust core

```bash
cargo fmt --manifest-path rust-core/Cargo.toml --all
cargo test --manifest-path rust-core/Cargo.toml --locked
cargo clippy --manifest-path rust-core/Cargo.toml --locked --all-targets -- -D warnings
```

### Linux packages

GitHub Actions is the recommended packaging machine. It builds three user-facing formats on native runners for both AMD64 and ARM64:

- **AppImage** — a single executable containing the Compose app, dependency JARs, native graphics library, and bundled Java runtime.
- **DEB** — for Debian, Ubuntu, and compatible distributions.
- **RPM** — for Fedora, RHEL, openSUSE, and compatible distributions.

The raw `packageTarGz` jpackage directory bundle is deliberately not published. It contained a launcher plus many JARs, native libraries, and a private Java runtime; it was not a standalone native executable.

For the DEB, select the package matching your CPU architecture and install it with:

```bash
sudo apt install ./mawjic-linux-amd64-v1.0.0.deb
sudo apt install mpv
```

For RPM-based distributions:

```bash
sudo dnf install ./mawjic-linux-amd64-v1.0.0.rpm
sudo dnf install mpv
```

For an AppImage:

```bash
chmod +x mawjic-linux-amd64-v1.0.0.AppImage
./mawjic-linux-amd64-v1.0.0.AppImage
```

On NixOS, `appimage-run` may be needed for FUSE/glibc integration. The app bundles its Java runtime but **does not bundle `mpv`**, which remains the external Linux playback process. Use the ARM64 asset on ARM64 devices.

Local native packaging commands (run only on the target Linux architecture) are:

```bash
./gradlew :composeApp:desktopTest :composeApp:createDistributable \
  :composeApp:packageDeb :composeApp:packageRpm -PversionName=0.1.0
```

The AppImage CI script additionally requires `appimagetool`; GitHub Actions fetches the version-pinned official release asset and validates its release digest before use. These packages are not yet claimed as successfully built until the fresh repository's GitHub Actions run passes.

### Android arm64-v8a debug build

Install Android SDK platform 36, Build Tools 36.0.0, NDK 27.2.12479018, then install the Rust target and expose the NDK:

```bash
rustup target add aarch64-linux-android
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
./gradlew :composeApp:assembleDebug
```

The debug APK, if the build succeeds, is at `composeApp/build/outputs/apk/debug/composeApp-debug.apk`. It is a development artifact and must **not** be uploaded or presented as a signed release.

### Regenerate an official Gradle wrapper

After Gradle has bootstrapped successfully, run the standard wrapper task through the downloaded distribution:

```bash
./gradlew wrapper --gradle-version 8.14.6 --distribution-type bin
./gradlew --version
```

Then commit the generated `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and updated wrapper properties. Verify the wrapper JAR checksum against the official [Gradle checksum reference](https://gradle.org/release-checksums/).

## Sources and artwork

Open **Add source** and enter an M3U/M3U8 URL or use Browse to select a local file. The XMLTV URL is optional. On Android the file picker grants read access to the selected document and stores its URI. Tunarr/ErsatzTV users should paste the M3U and XMLTV endpoints shown in the respective server interface. URL fetching has a 64 MiB safety limit and bounded connect/read timeouts; redirects are followed. URLs containing credentials are not printed in UI error messages, but a provider's URL can still contain secrets and HTTP transport is unencrypted.

HTTP is enabled because many IPTV setups expose HTTP endpoints. Prefer HTTPS whenever it is available. Do not enter subscription URLs into screenshots, public bug reports, shell history, or public repositories.

## Language behaviour

Open **Settings → Language** and choose **English** or **العربية**. The selection is stored alongside other preferences and takes effect immediately. Arabic mode switches the Compose layout direction to RTL and translates interface labels, settings, source dialogs, empty states, and programme controls. Proper names and source-provided channel/programme metadata are intentionally not machine-translated. Arabic glyphs use the platform font fallback; Linux systems may need an Arabic-capable font installed. The language preference is covered by the common settings round-trip test, but the Kotlin test has not run in this environment.

## Theme behaviour

- Catppuccin flavour + accent are stored locally. The full list is `Latte`, `Frappé`, `Macchiato`, `Mocha`, with accents `Rosewater`, `Flamingo`, `Pink`, `Mauve`, `Red`, `Maroon`, `Peach`, `Yellow`, `Green`, `Teal`, `Sky`, `Sapphire`, `Blue`, `Lavender`.
- Appearance options: system, forced light, forced dark, and AMOLED Black. AMOLED sets the main background and surface to pure `#000000`; a near-black surface variant remains available for visual grouping.
- Android Dynamic uses platform Material You palettes on Android 12+ (API 31+); otherwise the selected Catppuccin scheme is the fallback.
- Linux Native System Colours reads `org.freedesktop.appearance.color-scheme` and `accent-color` through `gdbus` and the XDG Desktop Portal Settings interface. If `gdbus`, the portal, or an individual setting is absent, the application falls back. It polls for changes while the app is open rather than subscribing to portal change signals.
- Accent foreground is selected using relative luminance to keep primary controls readable; this needs instrumented accessibility testing across supported devices and desktops.

## Release signing and Obtainium

See [docs/RELEASING.md](docs/RELEASING.md). The release workflow triggers on tags such as `v1.0.0`, requires a release keystore and passwords as repository Actions secrets, builds the ARM64 APK, runs `apksigner verify`, checks package/version information, and publishes the APK as `mawjic-android-arm64-v1.0.0.apk` alongside AMD64/ARM64 AppImage, DEB, and RPM assets. If signing secrets are missing, the release fails; it never substitutes a debug-signed APK.

Install a published APK from the GitHub Releases page once, then add the Mawjic GitHub Releases page in Obtainium. Select the APK asset ending `.apk` whose name includes `android-arm64`. Preserve and securely back up the signing key: all future upgrades must be signed with the same key or Android will reject the update.

## Licences

Mawjic source is MIT licensed. Third-party dependencies retain their own licences; see [THIRD_PARTY.md](THIRD_PARTY.md) and the upstream licence files for the exact notices associated with versions included by the build.

## Start your own Git repository

The folder has intentionally not been initialized with Git and no remote has been created. From the project folder:

```bash
cd Mawjic
git init -b main
git add .
git diff --cached --check
git commit -m "Initial Mawjic implementation"
git remote add origin https://github.com/USER/REPOSITORY.git
git push -u origin main
```

Replace `USER/REPOSITORY` with your chosen GitHub repository. The GitHub workflow files run only after you create the remote and push; nothing in this task creates a remote, publishes a release, or uses account credentials. Prefer GitHub Actions for Rust/Kotlin tests and all platform packaging so your NixOS laptop does not need the complete native and Android build toolchains. Local build commands are optional maintainer/debug paths.


## Build compatibility baseline

The initial CI baseline pins Compose Multiplatform 1.10.3, which maps to Jetpack Compose 1.10.5 and Lifecycle 2.10.0 in JetBrains release documentation. Compose Material 3 is pinned separately to stable version 1.9.0, and runtime/foundation/UI/resources use direct coordinates rather than deprecated plugin aliases. It avoids the 1.12 Compose runtime line that required AGP 9.1.0 in earlier CI logs. This version combination must still pass the fresh GitHub Actions build before it is considered verified.
