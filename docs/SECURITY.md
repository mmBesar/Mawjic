# Security notes and audit checklist

## Source and parsing boundaries

- Playlist and guide reads are bounded to 64 MiB and use explicit connect/read timeouts for HTTP(S).
- XML DTD / entity declarations are rejected, and external entities are never fetched or expanded.
- Rust FFI parser entry points validate null pointers, UTF-8, and the input length; parser panics are caught at the ABI boundary. The API's caller must pass a valid, readable, NUL-terminated pointer; arbitrary invalid C pointers cannot be made safe by this function.
- Rust output strings must be released with `iptv_string_free` exactly once. ABI version mismatch fails closed.
- Art URLs are accepted only for HTTP or HTTPS, and the application does not scrape third-party sites.
- `mpv` launches through `ProcessBuilder` argument lists, not a shell. Its IPC socket is placed in a private temporary directory. The player uses a fixed Unix-domain socket filename inside that private directory.
- Android local document access is obtained via the system document picker; the URI grant is persisted when the provider supports it.

## Known limitations / risks to re-audit

1. The in-repository XML parser is intentionally a limited XMLTV subset; a security audit and fuzzing campaign are required before treating it as hardened for hostile feed providers. Structural nesting validation and all XML character rules are not comprehensive.
2. HTTP is allowed for compatibility. Playlist URLs, stream URLs, and guide responses may be observed or modified by the network when HTTP is used. Prefer HTTPS and do not share private subscription URLs.
3. Automatic handling for playlist authentication headers, expiring links, provider-specific refresh, and DRM is not implemented.
4. Android player networking/authentication and exact codec support need physical-device and representative stream testing.
5. Linux `mpv` playback is in a separate window; audio/subtitle controls use mpv's own on-screen UI, not a custom in-app track picker.
6. Linux XDG Portal colour discovery depends on `gdbus` and portal availability; polling is used rather than signal subscription.
7. Release builds are not tested in this authoring environment. Confirm APK signature, version metadata, ABI contents, dependencies, and an install/upgrade cycle before public release.
8. The Gradle bootstrap is checksum-pinned but is not the official Gradle wrapper JAR; regenerate the standard wrapper in a connected environment before relying on it for long-term releases.

## Release-secret handling

Never add signing keys, passwords, source URLs, test subscriptions, or `.env` files to Git. CI secrets are passed through environment variables and should not be echoed. Restrict repository Actions access, keep the keystore backup offline or in a managed secret store, and validate the generated release APK with Android `apksigner` before release. If the signing key is lost, Android users cannot install the new APK as an in-place update under the same app ID.
