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
assert 'modules("java.net.http")' in build, \
    'The packaged desktop runtime must include java.net.http for Ktor Java engine'
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
assert 'AsyncImagePainter.State.Error' in app
assert 'Mawjic artwork request:' not in app and 'Mawjic artwork response:' not in app and 'Mawjic artwork failed:' not in app, \
    'Temporary artwork network diagnostics must not remain enabled in normal builds'
assert 'createClientPlugin' not in app and 'MawjicArtworkNetworkDiagnostics' not in app
assert 'setSingletonImageLoaderFactory' in app and 'KtorNetworkFetcherFactory' in app and 'HttpClient()' in app
assert 'implementation("org.slf4j:slf4j-nop:2.0.19")' in build, \
    'Desktop Ktor runtime must have the intentional SLF4J NOP provider'
programme_dialog = app[app.index('private fun ProgrammeDialog'):]
assert 'programme.image' in programme_dialog and 'contentScale = ContentScale.Fit' in programme_dialog and 'ContentScale.Crop' not in programme_dialog, \
    'Programme artwork must preserve its full image instead of center-cropping'
assert 'import coil3.request.crossfade' in app, 'Coil crossfade extension import missing'
assert 'triedHlsFallback' in android_player and 'MimeTypes.APPLICATION_M3U8' in android_player
manifest = (root / 'composeApp/src/androidMain/AndroidManifest.xml').read_text(encoding='utf-8')
assert 'org.videolan.vlc' in manifest and 'is.xyz.mpv' in manifest
common_tests = (root / 'composeApp/src/commonTest/kotlin/app/mawjic/tv/CoreIntegrationTest.kt').read_text(encoding='utf-8')
assert 'assertEquals(2, guide.programmes.size)' in common_tests
assert 'xmltvSubtitleAndOnscreenEpisodeNumberSurviveNativeParsing' in common_tests
assert 'guideTimelineWindowCovers12HoursAndFloorsToHalfHour' in common_tests, \
    'The 12-hour EPG time window must have a regression test'
assert 'sourceReloadVersion' in app and 'hourWidth.value.toDouble()' in app
assert 'guideTimelineRange(nowEpoch)' in app, 'EPG timeline must use the tested 12-hour range helper'
assert 'programmesInRange(channel, visibleStartEpoch, visibleEndEpoch)' in app, 'Guide cards must use the visible-window interval index'
assert 'drawWithContent' in app and 'drawCircle(' in app, 'The guide must use one continuous current-time indicator overlay'
assert 'visibleTimelineProgrammes' not in app, 'Avoid filtering all computed timeline geometry a second time'
assert 'GuideVerticalScrollbar(' in app, 'The guide must expose the Desktop vertical scrollbar hook'
assert (root / 'composeApp/src/commonMain/kotlin/app/mawjic/tv/GuideScrollbar.kt').is_file(), 'Common scrollbar expect declaration is missing'
desktop_platform = (root / 'composeApp/src/desktopMain/kotlin/app/mawjic/tv/Platform.desktop.kt').read_text(encoding='utf-8')
assert 'rememberScrollbarAdapter(scrollState = state)' in desktop_platform, 'Desktop scrollbar must use the guide LazyListState'
assert 'hourWidth * GUIDE_TIMELINE_HOURS' in app, 'EPG timeline width must cover the entire horizon'
assert 'guideTimelineTicks(rangeStart)' in app and '0 until (GUIDE_TIMELINE_HOURS * 2)' in app, \
    'EPG header must show 30-minute labels and draw 5-minute ticks across the horizon'
assert 'index % 6 == 0' in app and 'index % 3 == 0' in app, 'EPG minor tick marks need a 30/15/5 minute visual hierarchy'
assert 'var zoomStep by remember' in app and 'zoomLevels = listOf(0.75f, 1f, 1.5f, 2f, 3f, 4f)' in app, \
    'EPG must default to the enlarged timeline and provide user-controlled zoom'
assert 'height(98.dp)' in app and 'height(86.dp)' in app, 'EPG rows/cards need room for subtitles and episode metadata'
assert 'current?.subtitle?.takeIf(String::isNotBlank)' in app, 'Channel rows must show current programme subtitles'
assert 'programme.subtitle).filter(String::isNotBlank)' in app, 'Channel rows must show next programme subtitles'
assert 'containerColor = MaterialTheme.colorScheme.surfaceContainer' in app, 'Bottom navigation must use the app surface colour'
models = (root / 'composeApp/src/commonMain/kotlin/app/mawjic/tv/Models.kt').read_text(encoding='utf-8')
assert 'internal const val GUIDE_TIMELINE_HOURS = 12' in models
assert 'internal const val GUIDE_TIMELINE_TICK_SECONDS = 300L' in models
assert 'internal fun guideTimelineTicks(rangeStartEpochSeconds: Long)' in models
assert 'internal fun guideTimelineRange(nowEpochSeconds: Long)' in models
common_tests = (root / 'composeApp/src/commonTest/kotlin/app/mawjic/tv/CoreIntegrationTest.kt').read_text(encoding='utf-8')
assert 'guideTimelineTicksUseFiveMinuteStepsWithHalfHourBoundaries' in common_tests and 'assertEquals(145, ticks.size)' in common_tests
assert 'surfaceContainerHigh = containerHigh' in theme and 'surfaceContainer = container' in theme, \
    'Material 3 menus and dialogs need app-derived surface container colours'
assert '@Serializable\ndata class SourceProfile' in theme, 'Named IPTV source profiles must be serializable'
assert 'restoreSourceProfiles(rawSources, legacyPlaylist, legacyGuide, legacyTls)' in theme, \
    'Existing single-source preferences must migrate into a profile'
assert 'Platform.setPreference("sourcesJson"' in theme and 'Platform.setPreference("activeSourceId"' in theme, \
    'Named source profiles and active selection must persist'
assert 'SourceDialog(' in app and 'sources = settings.sources' in app and 'onActivate = ' in app, \
    'The UI must expose saved source activation and management'
assert 'legacySingleSourceMigratesOnceAndAnExplicitEmptyProfileListStaysEmpty' in common_tests, \
    'Source settings migration needs regression coverage'
rust = (root / 'rust-core/src/lib.rs').read_text(encoding='utf-8')
android_player = (root / 'composeApp/src/androidMain/kotlin/app/mawjic/tv/Playback.android.kt').read_text(encoding='utf-8')
assert 'val subtitle: String = ""' in models and 'val episodeNumber: String = ""' in models
assert '"sub-title"' in rust and '"episode-num"' in rust, \
    "XMLTV subtitle and episode-number parsing must remain present"
assert 'LaunchedEffect(url, selectedExternalPlayer, useInAppFallback)' in android_player
assert '"org.videolan.vlc"' in android_player and '"is.xyz.mpv"' in android_player
# Regression guards for Android player wiring. These are static contract checks;
# hosted compilation and manual device playback remain required.
assert 'channelLogo: String' in android_player and 'currentProgrammeSubtitle: String' in android_player
assert 'setControllerShowTimeoutMs(5_000)' in android_player
assert 'WindowInsetsControllerCompat' in android_player and 'WindowInsetsCompat.Type.systemBars()' in android_player
assert 'keepScreenOn = player.isPlaying' in android_player and 'playerViewRef.getAndSet(null)?.keepScreenOn = false' in android_player
assert 'override fun onIsPlayingChanged(isPlaying: Boolean)' in android_player
assert 'if (!showOsd)' in android_player and 'delay(5_000)' in android_player
assert 'playerViewRef.get()?.showController()' in android_player, 'Revealing a hidden OSD must also show Media3 controls'
# tr() reads Compose state and is @Composable, so resolve track labels before click callbacks.
assert 'val audioTrackLabel = tr("Audio")' in android_player
assert 'val subtitlesTrackLabel = tr("Subtitles")' in android_player
assert 'TrackSelectionDialog(player, C.TRACK_TYPE_AUDIO, audioTrackLabel)' in android_player
assert 'TrackSelectionDialog(player, C.TRACK_TYPE_TEXT, subtitlesTrackLabel)' in android_player
assert 'TrackSelectionDialogBuilder' not in android_player, 'Android track menus must use the app Material theme'
assert 'TrackSelectionOverride(option.group.mediaTrackGroup, listOf(option.trackIndex))' in android_player
assert 'AlertDialogDefaults.colors(' not in android_player, 'Use supported individual AlertDialog color parameters, not a colors object'
assert 'containerColor = scheme.surfaceContainerHigh' in android_player
assert 'titleContentColor = scheme.onSurface' in android_player
assert 'textContentColor = scheme.onSurface' in android_player
assert 'setTrackTypeDisabled(trackType, true)' in android_player, 'Subtitle dialog must offer a real off option'
assert 'Text(tr("Auto")' in android_player and 'clearOverridesOfType(trackType)' in android_player, \
    'The themed track dialog must keep an automatic/default track selection option'
# Fullscreen orientation is temporary: landscape while fullscreen, restore prior request on exit/disposal.
assert 'private fun Context.findActivity(): Activity?' in android_player
assert 'ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE' in android_player
assert 'originalRequestedOrientation' in android_player
assert 'DisposableEffect(activity)' in android_player
assert 'onDispose { activity?.requestedOrientation = originalRequestedOrientation }' in android_player
assert 'currentProgrammeTitle = currentProgramme?.title.orEmpty()' in app
assert 'currentProgrammeSubtitle = currentProgramme?.subtitle.orEmpty()' in app
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
