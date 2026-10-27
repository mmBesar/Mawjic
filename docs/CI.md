# CI and dependency automation

Mawjic's normal development path is GitHub Actions. CI runs Rust formatting/tests/Clippy/security audit, Linux AMD64 and ARM64 desktop tests plus AppImage/DEB/RPM packaging, and an Android ARM64 debug APK build. A tagged release builds the same three Linux formats for each architecture and a signed Android ARM64 APK.

## Why Dependabot version PRs are not enabled by default

This clean repository intentionally contains no `.github/dependabot.yml`. Creating the repository from this source will therefore not schedule weekly Gradle/Cargo/GitHub Actions version-update PRs. Routine version upgrades should be introduced deliberately, tested together, and reviewed against the Kotlin/Compose/AGP compatibility matrix.

GitHub can still create Dependabot **security** PRs if repository security settings enable security updates. If you want no automated PRs, after creating the repository open **Settings → Advanced Security** (or **Code security and analysis**, depending on the GitHub UI) and disable **Dependabot security updates**. You can still keep Dependabot alerts enabled for notifications without allowing it to open PRs.

## CI artifact retention

Push/PR workflows upload temporary artifacts retained for seven days. The debug APK is explicitly a development build and is never used as a release substitute. Only a `vMAJOR.MINOR.PATCH` tag triggers a release; release signing secrets are mandatory.

## GitHub-hosted runner requirements

Linux packaging uses native `ubuntu-24.04` (AMD64) and `ubuntu-24.04-arm` (ARM64) runners so the JVM/Skiko and Rust native components are built for the matching architecture. Do not replace these with a cross-architecture runner without validating the native libraries inside each package.
