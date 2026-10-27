# Mawjic code audit — usability pass

## Findings and actions

| Finding | Change | Verification state |
|---|---|---|
| Coil 3 network fetcher was not explicitly configured for the Linux Compose singleton. | Register `KtorNetworkFetcherFactory` directly at the root composable and provide an explicit Ktor `HttpClient`; align Coil modules/inventory to 3.5.0 and use the existing platform Ktor engines. | Source/configuration change; Linux image requests still need runtime verification. |
| XMLTV `<sub-title>` and `<episode-num>` were discarded by the Rust parser and Kotlin model. | Carry `subtitle` and `episodeNumber` through Rust JSON, Kotlin parsing, EPG cards, search and the details dialog; prefer `onscreen` episode numbers. EPG cards combine subtitle plus episode number on one compact metadata line, while the details card keeps the full description. | Rust and common Kotlin regression tests added for subtitle, onscreen episode number and poster text; hosted CI required. |
| EPG marker did not update during a long-running session. | Refresh local clock every 15 seconds and render a high-contrast line/dot over the time ruler and each row. | Source inspection only; visual verification on phone and desktop required. |
| Fixed mobile header/search and expanded palette consumed scarce screen height. | Toggle search on compact layouts, remove guide header duplication, and collapse colour options by default. | Source inspection only; screenshots/device verification required. |
| Platform-specific color source options were both visible on every platform. | Choose Android Dynamic or Linux Native System Colours based on platform preference. | Source inspection only. |
| Android external-player chooser did not follow a persistent preference. | Persist In-app/VLC/MPV and automatically target the selected installed package; allow VLC/MPV fallback on in-app errors. | Android build and real installed-player handoff not tested in this environment. |
| Android stream type and network defaults were underspecified. | Attach the Media3 error listener before preparing, set user-agent/timeouts/redirect handling, infer explicit MIME for HLS/DASH/TS, and make one bounded HLS fallback for extensionless HTTP IPTV endpoints. Clear retry callbacks on disposal. External player launch uses an allowlisted URI scheme, persistent selection and manifest visibility declarations for `is.xyz.mpv` / `org.videolan.vlc`. | Source inspection only; provider playback and installed-player handoff must be tested with real URLs/devices. |
| Greeting was fixed to evening. | Derive greeting from platform-local time and refresh the clock every 15 seconds. | Source inspection only. |

## Security and known limitations

- External player apps must already be installed; package names are `org.videolan.vlc` and `is.xyz.mpv`. The app does not install or bundle these players.
- The self-signed HTTPS setting weakens certificate-chain validation when enabled. Keep it off except for trusted servers. Startup restores the platform TLS policy before Coil creates its Ktor client; toggling the setting during an already-running app still needs a live self-signed-artwork test because Coil retains its singleton client.
- Linux `mpv` remains an external runtime dependency.
- Image failures log only the host and exception class, not full URLs that could include access tokens. Coil network fetching is explicitly registered, but live Linux artwork requests must still be verified on the user’s network.
- This environment has no usable Gradle/Rust toolchain and cannot reach download hosts. No end-to-end compilation, tests, APK inspection, live stream, or GUI test is claimed here.
