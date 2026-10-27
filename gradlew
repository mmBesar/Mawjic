#!/usr/bin/env bash
# Mawjic's offline-authored, checksum-verifying Gradle bootstrap wrapper.
# In a connected environment, this downloads the pinned distribution directly.
set -euo pipefail

readonly GRADLE_VERSION="8.14.6"
readonly DIST_SHA256="7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24"
readonly DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
readonly CACHE_ROOT="${GRADLE_USER_HOME:-${HOME}/.gradle}/wrapper/dists/mawjic-gradle-${GRADLE_VERSION}"
readonly DIST_DIR="${CACHE_ROOT}/gradle-${GRADLE_VERSION}"

if [[ -x "${DIST_DIR}/bin/gradle" ]]; then
  exec "${DIST_DIR}/bin/gradle" "$@"
fi
if ! command -v curl >/dev/null 2>&1 || ! command -v sha256sum >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  printf '\033[31mERROR\033[0m: curl, sha256sum, and unzip are required to bootstrap Gradle %s.\n' "$GRADLE_VERSION" >&2
  exit 127
fi
mkdir -p "$CACHE_ROOT"
readonly ZIP_TMP="${CACHE_ROOT}/gradle-${GRADLE_VERSION}-bin.zip.tmp"
readonly ZIP_FILE="${CACHE_ROOT}/gradle-${GRADLE_VERSION}-bin.zip"
rm -f "$ZIP_TMP"
printf 'Bootstrapping Gradle %s (SHA-256 is pinned)…\n' "$GRADLE_VERSION"
curl --fail --location --retry 3 --connect-timeout 15 "$DIST_URL" --output "$ZIP_TMP"
printf '%s  %s\n' "$DIST_SHA256" "$ZIP_TMP" | sha256sum --check --status || {
  rm -f "$ZIP_TMP"
  printf '\033[31mERROR\033[0m: Gradle distribution checksum mismatch; refusing to execute it.\n' >&2
  exit 1
}
mv "$ZIP_TMP" "$ZIP_FILE"
TMP_DIR="${CACHE_ROOT}/unpack.$$"
mkdir -p "$TMP_DIR"
unzip -q "$ZIP_FILE" -d "$TMP_DIR"
[[ -x "${TMP_DIR}/gradle-${GRADLE_VERSION}/bin/gradle" ]] || { rm -rf "$TMP_DIR"; echo "Invalid Gradle distribution" >&2; exit 1; }
mv "$TMP_DIR/gradle-${GRADLE_VERSION}" "$DIST_DIR"
rm -rf "$TMP_DIR"
exec "${DIST_DIR}/bin/gradle" "$@"
