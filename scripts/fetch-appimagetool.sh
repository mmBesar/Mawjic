#!/usr/bin/env bash
# Fetch a version-pinned upstream AppImage tool and verify it against GitHub's
# release-asset SHA-256 digest before executing it.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <amd64|arm64> <output-directory>" >&2
  exit 2
fi

arch="$1"
out_dir="$2"
version="1.9.1"
case "$arch" in
  amd64) asset_arch="x86_64" ;;
  arm64) asset_arch="aarch64" ;;
  *) echo "Unsupported architecture: $arch" >&2; exit 2 ;;
esac

command -v gh >/dev/null || { echo "GitHub CLI (gh) is required on the CI runner" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }

asset="appimagetool-${asset_arch}.AppImage"
release="repos/AppImage/appimagetool/releases/tags/${version}"
digest="$(gh api "$release" --jq ".assets[] | select(.name == \"${asset}\") | .digest")"
if [[ ! "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "The pinned AppImage release did not provide a SHA-256 digest for ${asset}; refusing an unverified tool." >&2
  exit 1
fi

mkdir -p "$out_dir"
out_dir="$(cd "$out_dir" && pwd)"
tmp="${out_dir}/${asset}.download"
tool="${out_dir}/${asset}"
trap 'rm -f "$tmp"' EXIT
url="https://github.com/AppImage/appimagetool/releases/download/${version}/${asset}"
curl --fail --location --retry 3 --retry-all-errors --connect-timeout 20 "$url" --output "$tmp"
printf '%s  %s\n' "${digest#sha256:}" "$tmp" | sha256sum --check --status
mv "$tmp" "$tool"
chmod 0755 "$tool"
printf '%s\n' "$tool"
