#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash -n gradlew scripts/source-sanity.sh scripts/fetch-appimagetool.sh scripts/package-appimage.sh scripts/generate-signing-key.sh scripts/verify-release-apk.sh
python3 scripts/check-licenses.py
python3 scripts/check-localization.py
python3 - <<'PY'
from pathlib import Path
import subprocess
import tomllib

root = Path('.')
with (root / 'rust-core/Cargo.toml').open('rb') as f:
    manifest = tomllib.load(f)
assert manifest['package']['name'] == 'mawjic-core'
assert manifest['package']['rust-version'] == '1.75'
assert manifest['lib']['crate-type'] == ['cdylib', 'staticlib', 'rlib']
lock = (root / 'rust-core/Cargo.lock').read_text(encoding='utf-8')
assert 'mawjic-core' in lock and '[[package]]' in lock

# Inspect only files tracked by Git when in a checkout, so generated .so files
# and APKs from earlier build steps are not misclassified as committed files.
if subprocess.run(['git', 'rev-parse', '--is-inside-work-tree'], capture_output=True, text=True).returncode == 0:
    paths = [Path(p.decode('utf-8', errors='surrogateescape')) for p in subprocess.run(
        ['git', 'ls-files', '-z'], check=True, capture_output=True).stdout.split(b'\0') if p]
else:
    excluded = {'.git', '.gradle', '.kotlin', 'build', 'target'}
    paths = [p.relative_to(root) for p in root.rglob('*') if p.is_file() and not any(part in excluded for part in p.parts)]
for path in paths:
    assert path.suffix.lower() not in {'.apk', '.aab', '.so', '.dylib', '.dll', '.jks', '.keystore', '.p12'}, \
        f'Unexpected generated binary or signing key in source: {path}'

for required in ('README.md', 'BUILD_STATUS.md', 'LICENSE', '.gitignore', 'docs/ARCHITECTURE.md', 'docs/SECURITY.md', 'docs/CI.md'):
    assert (root / required).is_file(), f'missing {required}'
build = (root / 'composeApp/build.gradle.kts').read_text(encoding='utf-8')
assert 'namespace = "app.mawjic.tv"' in build
assert 'applicationId = "app.mawjic.tv"' in build
assert 'activity-compose:1.12.4' in build
assert 'coil-compose:3.5.0' in build and 'coil-network-ktor3:3.5.0' in build and 'coil-svg:3.5.0' in build
theme = (root / 'composeApp/src/commonMain/kotlin/app/mawjic/tv/Theme.kt').read_text(encoding='utf-8')
app = (root / 'composeApp/src/commonMain/kotlin/app/mawjic/tv/App.kt').read_text(encoding='utf-8')
android_platform = (root / 'composeApp/src/androidMain/kotlin/app/mawjic/tv/Platform.android.kt').read_text(encoding='utf-8')
android_player = (root / 'composeApp/src/androidMain/kotlin/app/mawjic/tv/Playback.android.kt').read_text(encoding='utf-8')
assert 'Color(value.toULong())' not in theme, 'ARGB values must not be passed to Compose packed-Color constructor'
assert 'hex.removePrefix("#").toLong(16)).toULong()' not in theme + app, 'ARGB hex values must use Color(Int) conversion'
assert 'colorScheme.background.toArgb().toLong()' in app
assert 'color.toArgb().toLong()' in android_platform
for deprecated_alias in ('implementation(compose.runtime)', 'implementation(compose.foundation)', 'implementation(compose.material3)', 'implementation(compose.ui)', 'implementation(compose.components.resources)'):
    assert deprecated_alias not in build, f'deprecated Compose alias remains: {deprecated_alias}'
assert 'TargetFormat.Rpm' in build
assert 'gzip XMLTV' in (root / 'README.md').read_text(encoding='utf-8')
assert 'Mawjic artwork failed' in app and 'AsyncImagePainter.State.Error' in app
assert 'setSingletonImageLoaderFactory' in app and 'KtorNetworkFetcherFactory' in app and 'HttpClient()' in app
assert 'triedHlsFallback' in android_player and 'MimeTypes.APPLICATION_M3U8' in android_player
manifest = (root / 'composeApp/src/androidMain/AndroidManifest.xml').read_text(encoding='utf-8')
assert 'org.videolan.vlc' in manifest and 'is.xyz.mpv' in manifest
common_tests = (root / 'composeApp/src/commonTest/kotlin/app/mawjic/tv/CoreIntegrationTest.kt').read_text(encoding='utf-8')
assert 'assertEquals(2, guide.programmes.size)' in common_tests
assert 'xmltvSubtitleAndOnscreenEpisodeNumberSurviveNativeParsing' in common_tests
assert 'sourceReloadVersion' in app and 'hourWidth.value.toDouble()' in app
models = (root / 'composeApp/src/commonMain/kotlin/app/mawjic/tv/Models.kt').read_text(encoding='utf-8')
rust = (root / 'rust-core/src/lib.rs').read_text(encoding='utf-8')
android_player = (root / 'composeApp/src/androidMain/kotlin/app/mawjic/tv/Playback.android.kt').read_text(encoding='utf-8')
assert 'val subtitle: String = ""' in models and 'val episodeNumber: String = ""' in models
assert '"sub-title"' in rust and '"episode-num"' in rust and '"image" => if let Some(p)' in rust
assert 'LaunchedEffect(url, selectedExternalPlayer, useInAppFallback)' in android_player
assert '"org.videolan.vlc"' in android_player and '"is.xyz.mpv"' in android_player
listener_at = android_player.index('player.addListener(listener)')
initial_item_at = android_player.index('player.setMediaItem(mediaItem())')
initial_prepare_at = android_player.index('player.prepare()', initial_item_at)
assert listener_at < initial_item_at < initial_prepare_at, 'Media3 listener must be attached before initial prepare'
assert 'GZIPInputStream' in (root / 'composeApp/src/androidMain/kotlin/app/mawjic/tv/Platform.android.kt').read_text(encoding='utf-8')
assert 'GZIPInputStream' in (root / 'composeApp/src/desktopMain/kotlin/app/mawjic/tv/Platform.desktop.kt').read_text(encoding='utf-8')
root_build = (root / 'build.gradle.kts').read_text(encoding='utf-8')
assert 'id("org.jetbrains.compose") version "1.10.3" apply false' in root_build
assert 'packageTarGz' not in build
assert (root / 'assets/mawjic.svg').is_file() and (root / 'assets/mawjic.png').is_file()
assert (root / 'composeApp/src/androidMain/res/drawable-nodpi/mawjic_icon.png').is_file()
strings = (root / 'composeApp/src/commonMain/kotlin/app/mawjic/tv/Strings.kt').read_text(encoding='utf-8')
assert 'supportedLanguages = listOf("English", "Arabic")' in strings
assert 'Streaming, your wave.' in strings and 'البث على موجتك.' in strings
app = (root / 'composeApp/src/commonMain/kotlin/app/mawjic/tv/App.kt').read_text(encoding='utf-8')
assert 'LocalLayoutDirection provides' in app and 'LayoutDirection.Rtl' in app
ci = (root / '.github/workflows/ci.yml').read_text(encoding='utf-8')
release = (root / '.github/workflows/release.yml').read_text(encoding='utf-8')
assert 'working-directory: rust-core' not in ci # audit uses explicit rust-core/Cargo.lock path instead
assert 'cargo audit --file rust-core/Cargo.lock' in ci
assert ':composeApp:createDistributable' in ci and ':composeApp:createDistributable' in release
assert ':composeApp:packageRpm' in ci and ':composeApp:packageRpm' in release
assert 'packageTarGz' not in ci + release
assert 'AppImage' in ci and 'AppImage' in release
assert not (root / '.github/dependabot.yml').exists()
print('Source sanity passed: Rust manifest/lockfile, package ID, dependency inventory, localization, release signing, Linux formats, audit path, and repository hygiene.')
PY
