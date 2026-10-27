# Architecture and decision record

## Component map

```text
Compose Multiplatform UI (Android + Linux desktop)
  ├─ common Kotlin models, settings, EPG selection, timeline and artwork URL policy
  ├─ Platform expect/actual: local preferences, source I/O, local file picker, theme/system colours
  ├─ Rust C ABI parser -> JSON -> Kotlin serialization
  └─ Player actuals
       ├─ Android: Media3 / ExoPlayer + HLS, DASH and RTSP modules
       └─ Linux: external mpv process + private Unix-domain IPC socket
```

## ADR-001 — Shared UI: Kotlin + Compose Multiplatform

Compose Multiplatform documents Linux x86-64/AArch64 desktop and Android support. UI code is shared in `commonMain`; platform I/O, theme integrations, file selection, and playback live in `androidMain` and `desktopMain`. Keep platform-specific APIs out of common sources. Compatibility references: [platform support](https://kotlinlang.org/docs/multiplatform/supported-platforms.html), [version compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html).

## ADR-002 — Rust interop: minimal versioned C ABI

The Rust crate has no third-party Cargo dependencies. It exports `iptv_parse_m3u`, `iptv_parse_xmltv`, `iptv_string_free`, and `iptv_core_abi_version`; strings cross the boundary as UTF-8 C strings and JSON. JNA maps these exports on Android and Linux. Returned buffers are freed through the Rust allocator wrapper, not the platform allocator. The C ABI is versioned and checked at load time. A generated UniFFI layer was not selected to keep the parser dependency graph and generated code small. Native packaging and JNA behaviour still require CI/device verification.

## ADR-003 — Input safety boundaries

Source reads are capped at 64 MiB with network timeouts. XML DTD and external entity declarations are rejected; only predefined and numeric XML entities are decoded. M3U is capped at 100,000 channels; XMLTV is capped at 1,000,000 programmes and 2,000,000 XML events. Artwork is accepted only over `http` or `https` and uses playlist/XMLTV-provided URLs or explicit in-memory mappings. The XML implementation is a common-shape subset rather than a full XML conformance parser; if the format grows, replace it with a maintained streaming XML parser after evaluating dependency size and Android/Linux compatibility.

## ADR-004 — Platform playback

Android uses Media3/ExoPlayer with its specific HLS, DASH and RTSP modules, track-selection controls, and bounded re-prepare attempts after errors. Linux delegates codec/container details to the host's `mpv` package and uses an argument-vector process invocation (no shell interpolation). Linux playback is a separate fullscreen window, so its visibility and features depend on the desktop and installed mpv build.

## ADR-005 — Themes and system appearance

The user-selected language (`English` or `Arabic`) persists across sessions; Arabic provides a right-to-left Compose layout direction while source-provided channel/programme text remains unchanged. The user-selected Catppuccin flavour/accent persists across sessions. Forced appearance overrides system preference; AMOLED Black forces true-black principal surfaces. Android Dynamic is applied only on Android. Linux Native System Colours uses the XDG Desktop Portal Settings interface through `gdbus`; absent portal facilities fall back to the selected Catppuccin scheme. System settings are polled while the application is open instead of holding a D-Bus signal subscription.

## ADR-006 — Sources from Tunarr and ErsatzTV

Treat each service as an M3U/XMLTV source producer: users paste the playlist and guide endpoints shown by that server. Do not depend on undocumented server APIs or hardcode endpoint patterns. Upstream client instructions: [ErsatzTV clients](https://ersatztv.org/docs/clients/). These integrations have not been verified against a live server in this environment.

## ADR-007 — Release signing and update identity

Android application ID is `app.mawjic.tv`. Release artefacts are signed only when all configured key material exists; no release task may silently fall back to the debug key. A version tag provides `versionName`; CI run number provides a monotonically increasing `versionCode` within the GitHub Actions repository. The same private signing key must be retained for all updates.
