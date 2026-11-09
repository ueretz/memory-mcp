#!/usr/bin/env bash
# Builds "Memory MCP.app" that opens the dashboard and pins it to the Dock.
# Usage: scripts/macos/install-dock-app.sh [--url http://localhost:8080] [--dest ~/Applications] [--no-dock]
set -euo pipefail

URL="${MEMORY_MCP_URL:-http://localhost:8080}"
DEST="$HOME/Applications"
ADD_TO_DOCK=1

while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --dest) DEST="$2"; shift 2 ;;
    --no-dock) ADD_TO_DOCK=0; shift ;;
    -h|--help) sed -n '2,3p' "$0" | sed 's/^# //'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ "$(uname -s)" = "Darwin" ] || { echo "This script only works on macOS" >&2; exit 1; }
# The URL is embedded into AppleScript and a shell command, so only plain URL characters are accepted.
[[ "$URL" =~ ^https?://[A-Za-z0-9.:_/-]+$ ]] || { echo "Unsupported dashboard URL: $URL" >&2; exit 2; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP="$DEST/Memory MCP.app"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$DEST"
rm -rf "$APP"
# Chrome's --app mode gives the dashboard its own window without tabs; without Chrome the default browser is used.
osacompile -o "$APP" -e "do shell script \"open -na 'Google Chrome' --args --app='$URL' 2>/dev/null || open '$URL'\""

if xcrun --find swift >/dev/null 2>&1; then
  xcrun swift "$SCRIPT_DIR/dock-icon.swift" "$WORK/icon.png"
  mkdir "$WORK/icon.iconset"
  for s in 16 32 128 256 512; do
    sips -z "$s" "$s" "$WORK/icon.png" --out "$WORK/icon.iconset/icon_${s}x${s}.png" >/dev/null
    sips -z $((s * 2)) $((s * 2)) "$WORK/icon.png" --out "$WORK/icon.iconset/icon_${s}x${s}@2x.png" >/dev/null
  done
  iconutil -c icns "$WORK/icon.iconset" -o "$APP/Contents/Resources/applet.icns"
  # osacompile also ships the default icon in Assets.car, which wins over applet.icns while CFBundleIconName is set.
  rm -f "$APP/Contents/Resources/Assets.car"
  plutil -remove CFBundleIconName "$APP/Contents/Info.plist" 2>/dev/null || true
else
  echo "Swift toolchain not found (xcode-select --install): keeping the default applet icon" >&2
fi

plutil -replace CFBundleName -string "Memory MCP" "$APP/Contents/Info.plist"
plutil -replace CFBundleIdentifier -string "local.memory-mcp.dashboard" "$APP/Contents/Info.plist"
# Editing Info.plist invalidates the signature osacompile made; an unsigned applet is refused by Gatekeeper.
codesign --force --deep --sign - "$APP" 2>/dev/null
touch "$APP"

if [ "$ADD_TO_DOCK" = 1 ]; then
  APP_URL="file://$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$APP")/"
  if defaults read com.apple.dock persistent-apps 2>/dev/null | grep -qF "$APP_URL"; then
    killall Dock
  else
    defaults write com.apple.dock persistent-apps -array-add \
      "<dict><key>tile-data</key><dict><key>file-data</key><dict><key>_CFURLString</key><string>$APP_URL</string><key>_CFURLStringType</key><integer>15</integer></dict></dict></dict>"
    killall Dock
  fi
fi

echo "Installed: $APP -> $URL"
[ "$ADD_TO_DOCK" = 1 ] && echo "Pinned to the Dock. To remove: drag it out of the Dock and delete the app." || true
