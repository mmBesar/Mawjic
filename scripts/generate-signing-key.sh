#!/usr/bin/env bash
set -euo pipefail

# Generate a release keystore interactively. Passwords are entered by keytool,
# not placed on this script's command line or printed by it.
readonly DESTINATION="${1:-mawjic-release.jks}"
if [[ -e "$DESTINATION" ]]; then
  printf 'ERROR: Refusing to overwrite existing key material: %s\n' "$DESTINATION" >&2
  exit 1
fi
if ! command -v keytool >/dev/null 2>&1; then
  printf 'ERROR: keytool is unavailable; install a JDK before generating a key.\n' >&2
  exit 127
fi
umask 077
keytool -genkeypair -v \
  -keystore "$DESTINATION" \
  -alias mawjic \
  -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$DESTINATION"
printf '\nCreated keystore: %s\n' "$DESTINATION"
printf 'Back it up securely and never add it to Git. All future app updates need this same key.\n'
