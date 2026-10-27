#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <jpackage-app-dir-or-app-root> <amd64|arm64> <version|vMAJOR.MINOR.PATCH> <appimagetool> <output-dir>" >&2
}
if [[ $# -ne 5 ]]; then usage; exit 2; fi

source_input="$1"
arch="$2"
version="$3"
tool_input="$4"
output_dir="$5"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "$arch" in
  amd64) appimage_arch="x86_64"; expected_machine='X86-64' ;;
  arm64) appimage_arch="aarch64"; expected_machine='AArch64' ;;
  *) echo "Unsupported Mawjic Linux architecture: $arch" >&2; usage; exit 2 ;;
esac
[[ "$version" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must be X.Y.Z or vX.Y.Z: $version" >&2; exit 2; }
[[ -f "$tool_input" && -x "$tool_input" ]] || { echo "Verified appimagetool not executable: $tool_input" >&2; exit 1; }
command -v readelf >/dev/null || { echo "readelf is required (install binutils)" >&2; exit 1; }

is_app_dir() {
  [[ -d "$1/lib/app" && -d "$1/lib/runtime" && -x "$1/bin/mawjic" ]]
}
if is_app_dir "$source_input"; then
  source_dir="$source_input"
else
  if [[ ! -d "$source_input" ]]; then
    echo "jpackage output root does not exist: $source_input" >&2
    exit 1
  fi
  candidates=()
  while IFS= read -r -d '' candidate; do
    if is_app_dir "$candidate"; then candidates+=("$candidate"); fi
  done < <(find "$source_input" -mindepth 1 -maxdepth 3 -type d -print0)
  if [[ ${#candidates[@]} -ne 1 ]]; then
    echo "Expected exactly one jpackage application directory below: $source_input (found ${#candidates[@]})." >&2
    find "$source_input" -maxdepth 4 -print >&2 || true
    exit 1
  fi
  source_dir="${candidates[0]}"
fi
source_dir="$(cd "$source_dir" && pwd)"
tool="$(cd "$(dirname "$tool_input")" && pwd)/$(basename "$tool_input")"
[[ -s "$root/assets/mawjic.png" ]] || { echo "Missing Mawjic PNG icon" >&2; exit 1; }

# Verify native libraries packaged by jpackage match the selected runner.
shopt -s nullglob
native_libraries=("$source_dir"/lib/app/*.so)
for library in "${native_libraries[@]}"; do
  if ! readelf -h "$library" | grep -F "$expected_machine" >/dev/null; then
    echo "Wrong-architecture or unreadable native library in app bundle: $library" >&2
    readelf -h "$library" >&2 || true
    exit 1
  fi
done

mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"
output="$output_dir/mawjic-linux-${arch}-${version}.AppImage"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
appdir="$work/Mawjic.AppDir"
mkdir -p "$appdir"
cp -a "$source_dir/." "$appdir/"
install -m 0644 "$root/assets/mawjic.png" "$appdir/mawjic.png"
ln -s mawjic.png "$appdir/.DirIcon"

cat > "$appdir/mawjic.desktop" <<'EOF_DESKTOP'
[Desktop Entry]
Type=Application
Name=Mawjic
Comment=Streaming, your wave.
Exec=AppRun %U
Icon=mawjic
Terminal=false
Categories=AudioVideo;Video;Player;
StartupWMClass=mawjic
EOF_DESKTOP

cat > "$appdir/AppRun" <<'EOF_APPRUN'
#!/bin/sh
set -eu
APPDIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export APPDIR
exec "$APPDIR/bin/mawjic" "$@"
EOF_APPRUN
chmod 0755 "$appdir/AppRun"

# appimagetool ships its own runtime. Use extract-and-run to avoid requiring FUSE
# to launch the build tool itself on a hosted runner.
VERSION="${version#v}" ARCH="$appimage_arch" "$tool" --appimage-extract-and-run --no-appstream "$appdir" "$output"
chmod 0755 "$output"
readelf -h "$output" | grep -F "$expected_machine" >/dev/null || {
  echo "AppImage ELF header does not match requested architecture: $arch" >&2; exit 1;
}
probe="$work/probe"
mkdir -p "$probe"
(cd "$probe" && "$output" --appimage-extract >/dev/null)
[[ -x "$probe/squashfs-root/AppRun" && -x "$probe/squashfs-root/bin/mawjic" ]] || {
  echo "AppImage payload failed its extraction smoke test" >&2; exit 1;
}
printf 'Created and inspected %s\n' "$output"
