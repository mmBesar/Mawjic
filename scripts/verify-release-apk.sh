#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 RELEASE.apk VERSION_NAME VERSION_CODE" >&2
  exit 2
fi
readonly APK="$1"
readonly EXPECTED_NAME="$2"
readonly EXPECTED_CODE="$3"
readonly BUILD_TOOLS="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools/36.0.0"
readonly APKSIGNER="$BUILD_TOOLS/apksigner"
readonly AAPT="$BUILD_TOOLS/aapt"

[[ -s "$APK" ]] || { echo "ERROR: APK is missing or empty: $APK" >&2; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "ERROR: apksigner missing at $APKSIGNER" >&2; exit 1; }
[[ -x "$AAPT" ]] || { echo "ERROR: aapt missing at $AAPT" >&2; exit 1; }

"$APKSIGNER" verify --verbose --print-certs "$APK"
BADGING="$("$AAPT" dump badging "$APK")"
EXPECTED="package: name='app.mawjic.tv' versionCode='${EXPECTED_CODE}' versionName='${EXPECTED_NAME}'"
grep -Fq "$EXPECTED" <<<"$BADGING" || {
  echo "ERROR: APK package/version metadata does not match the release tag/run number." >&2
  grep '^package:' <<<"$BADGING" >&2 || true
  exit 1
}
command -v unzip >/dev/null 2>&1 || { echo "ERROR: unzip is required." >&2; exit 127; }
ZIP_CONTENTS="$(unzip -Z1 "$APK")"
grep -Fxq 'lib/arm64-v8a/libmawjic_core.so' <<<"$ZIP_CONTENTS" || {
  echo "ERROR: APK lacks libmawjic_core.so for arm64-v8a." >&2; exit 1;
}
if grep -Eq '^lib/(armeabi-v7a|x86|x86_64)/' <<<"$ZIP_CONTENTS"; then
  echo "ERROR: APK includes an unintended non-ARM64 ABI." >&2
  exit 1
fi
printf 'APK verification passed: package ID, version metadata, signature, and arm64 native core.\n'
