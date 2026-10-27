# Release and Android signing

## Versioning and workflow

A Git tag matching `vMAJOR.MINOR.PATCH` triggers `.github/workflows/release.yml`. It builds Linux packages on Linux runners matching AMD64 and ARM64, and builds a signed Android `arm64-v8a` release APK. The tag controls `versionName`; `github.run_number` controls `versionCode`. Both are checked during the Android artifact verification step. The Android APK is checked with `apksigner verify`, its package ID/version are inspected with `aapt`, and the Rust shared library ABI is checked in the APK archive.

The app ID is permanently set to `app.mawjic.tv` for this project. Do not change it for a point update. Avoid reusing a released version tag; use a new monotonically increasing tag and ensure `versionCode` is greater than the installed version.

## Generate a release keystore locally

Run from the repository root with a JDK installed:

```bash
./scripts/generate-signing-key.sh "$HOME/secure/mawjic-release.jks"
```

`keytool` prompts interactively for the keystore password and identity fields. Store the resulting keystore in a secure offline backup location; keep another independently protected backup. Never add the file to Git or attach it to a public issue. Losing this key means Android will not accept later releases as an update of the same app ID.

## Configure GitHub Actions secrets

Create these **repository Actions secrets** (Settings → Secrets and variables → Actions):

- `ANDROID_KEYSTORE_BASE64`: the keystore file encoded as one base64 string.
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_ALIAS`: alias used when creating the key (the provided script uses `mawjic`).
- `ANDROID_KEY_PASSWORD`: the private key password.

With the GitHub CLI authenticated and the file already safely stored, the base64 secret can be set without printing its value:

```bash
gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -w0 "$HOME/secure/mawjic-release.jks")
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

The `gh secret set` commands for password/alias prompt for secret input. Confirm the keystore and alias locally before relying on CI. The release workflow checks all secrets before building; if any are missing or invalid, it fails and publishes no release. It never falls back to a debug signature.

## Make and verify a release

Once the repository has been pushed and CI passes, create and push a version tag:

```bash
git tag -a v1.0.0 -m "Mawjic 1.0.0"
git push origin v1.0.0
```

This is documentation only; no tag or release was created during implementation. Check the Release workflow and download all seven assets: `mawjic-linux-amd64-v1.0.0.AppImage`, `mawjic-linux-amd64-v1.0.0.deb`, `mawjic-linux-amd64-v1.0.0.rpm`, `mawjic-linux-arm64-v1.0.0.AppImage`, `mawjic-linux-arm64-v1.0.0.deb`, `mawjic-linux-arm64-v1.0.0.rpm`, and `mawjic-android-arm64-v1.0.0.apk`. The ARM64 APK filename ends in `.apk` so release-page clients can discover it.

For a downloaded APK, verify it again locally with Android SDK Build Tools 36.0.0:

```bash
scripts/verify-release-apk.sh mawjic-android-arm64-v1.0.0.apk 1.0.0 VERSION_CODE
```

Replace `VERSION_CODE` with the workflow run number shown in the APK metadata. Do not install an APK from an untrusted source.

## Obtainium updates

Install the first signed APK from the project's GitHub Releases, then add the project's GitHub Releases page as an app in [Obtainium](https://github.com/ImranR98/Obtainium). Select the `mawjic-android-arm64-*.apk` release asset. On subsequent tags, Obtainium can check for newer assets on that release page. Always publish upgrades under the same app ID and with the same private signing key; back up the signing key long term.
