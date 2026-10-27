# IPTV UX research and Mawjic follow-ups

Reviewed 2026-10-27. This is a feature/UX comparison, not an endorsement or code-copying exercise.

## References

- [IPTVMine-Pro](https://github.com/samyak2403/IPTVMine-Pro): playlist import, groups/categories, search, bookmarks/history, and a separate Media3 player; project README describes it as under construction. It is a useful Android interaction reference, but Mawjic must keep its own implementation and add EPG/theme capabilities.
- [IPTVnator](https://github.com/4gray/iptvnator): local and remote M3U, XMLTV guide, EPG ribbon/grid, group/search, and external MPV/VLC players. This validates making source management, guide browsing, and external-player fallback first-class functions.
- [Kodi PVR IPTV Simple](https://github.com/kodi-pvr/pvr.iptvsimple): local/remote XMLTV, caching, refresh policy, EPG time shift, logo-source preference (M3U versus XMLTV), and local/remote logo folders/base URLs. These are sensible advanced settings to add later.
- [Coil 3 network setup](https://coil-kt.github.io/coil/network/): remote image loading depends on including a network fetcher and platform-compatible HTTP engine. Mawjic declares Coil's Ktor network module plus Android/JVM Ktor engines; if Linux logos still fail after TLS is configured, inspect actual image URLs and Coil error states rather than adding more dependencies blindly.
- [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config): Android can trust specific custom CAs; accepting all invalid certificate chains is unsafe. Mawjic therefore keeps the insecure trust toggle off by default and makes the trade-off visible.

## Product choices from this study

- Playlist loading must not depend on successful EPG loading; guide errors are shown while loaded channels remain usable.
- Phone layouts should not reserve a desktop sidebar. Channel cards and settings must scroll vertically, and EPG cards must use the current compact/wide timeline scale consistently.
- Artwork failures need observable diagnostics without leaking subscription tokens embedded in image URLs. Mawjic logs only host and error type and shows a channel initial fallback.
- XMLTV feeds may be gzip-compressed; the reader supports gzip while bounding both raw and decompressed input. Persistent EPG cache, refresh, time shift and per-source HTTP headers remain future work.
- Android external-player handoff is a fallback, not a claim that Media3 supports every codec/container/provider protocol.

## Findings mapped to implementation

1. **EPG**: XMLTV feeds commonly contain an external `DOCTYPE` such as `SYSTEM "xmltv.dtd"`. Mawjic now skips such declarations without fetching them, while still rejecting internal subsets/entities. Playlist loading is committed before optional EPG parsing, so malformed guide data no longer hides working channels.
2. **Phones**: a fixed 176dp sidebar and fixed-width channel field leave too little room on narrow screens. Compact screens now hide the sidebar, show bottom navigation, omit the large hero card, use stacked channel rows, and narrow the guide's channel column.
3. **Playback**: Android includes an explicit “Open in external player” action so incompatible streams can be handed to installed players such as VLC. Media3 remains the in-app default.
4. **HTTPS**: normal HTTPS verification remains default. An advanced opt-in allows self-signed certificate chains for trusted sources/playback and is OFF by default. It does not disable hostname verification. Users should install a proper CA certificate or fix the server certificate whenever possible.
5. **Artwork**: channel logos resolve from playlist `tvg-logo`, then XMLTV channel icons, then explicit mappings. No URLs are fabricated and no metadata site is scraped. If Linux still shows missing logos, collect the actual `tvg-logo` values and loader error rather than assuming all missing artwork has one cause.

## Still useful follow-ups

- Surface Coil load errors in a non-intrusive diagnostics panel.
- Add user-configurable logo mappings/local logo directory.
- Add EPG cache, refresh controls, timezone/time-shift setting, and guide matching diagnostics.
- Add a per-playlist user-agent/referrer option for providers that require them.
- Test Media3 and mpv against real HLS streams, redirects, server certificate variants, and Android external player handoff.
