#!/usr/bin/env bash
# Create (and optionally launch) a Karoo-shaped Android emulator.
#
# There is no official Hammerhead system image, so this is a stock Android AVD sized
# like a Karoo 3 (3.2" 480x800). The Karoo system app is absent, so KarooSystemService
# never connects — use the in-app simulator or tools/karoo-sim.sh to drive triggers.
#
#   tools/karoo-avd.sh create     # create the AVD (idempotent)
#   tools/karoo-avd.sh start      # boot it
#   tools/karoo-avd.sh delete     # remove it
#
# Overridables: AVD_NAME, API_LEVEL, LCD_DENSITY, LCD_WIDTH, LCD_HEIGHT.
set -euo pipefail

AVD_NAME="${AVD_NAME:-karoo3}"
API_LEVEL="${API_LEVEL:-30}"
LCD_WIDTH="${LCD_WIDTH:-480}"
LCD_HEIGHT="${LCD_HEIGHT:-800}"
# 480x800 over 3.2" is ~292 real dpi; 320 (xhdpi) is the nearest bucket. Check the real
# value on a Karoo with `adb shell wm density` and override if it differs.
LCD_DENSITY="${LCD_DENSITY:-320}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$SDK/emulator/emulator"

case "$(uname -m)" in
  arm64|aarch64) ABI="arm64-v8a" ;;
  *) ABI="x86_64" ;;
esac
IMAGE="system-images;android-${API_LEVEL};google_apis;${ABI}"

require_sdk() {
  if [[ ! -x "$SDKMANAGER" ]]; then
    cat >&2 <<MSG
Android command-line tools not found at:
  $SDKMANAGER

Install them with Android Studio (SDK Manager -> SDK Tools -> "Android SDK
Command-line Tools"), or set ANDROID_HOME to your SDK location.
MSG
    exit 1
  fi
}

create() {
  require_sdk
  echo "Installing $IMAGE ..."
  yes | "$SDKMANAGER" --install "platform-tools" "emulator" "$IMAGE" >/dev/null

  if "$AVDMANAGER" list avd -c | grep -qx "$AVD_NAME"; then
    echo "AVD '$AVD_NAME' already exists."
  else
    echo "Creating AVD '$AVD_NAME' ..."
    echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE" --force >/dev/null
  fi

  local config="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
  [[ -f "$config" ]] || { echo "config.ini not found at $config" >&2; exit 1; }

  # Karoo-shaped screen; skin.name/skin.path are cleared so the raw size wins.
  python3 - "$config" "$LCD_WIDTH" "$LCD_HEIGHT" "$LCD_DENSITY" <<'PY'
import sys
config, width, height, density = sys.argv[1:5]
props = {
    "hw.lcd.width": width,
    "hw.lcd.height": height,
    "hw.lcd.density": density,
    "skin.name": f"{width}x{height}",
    "skin.path": "_no_skin",
    "hw.keyboard": "yes",
    "hw.mainKeys": "yes",
    "hw.gps": "yes",
    "hw.sensors.temperature": "yes",
    "hw.ramSize": "2048",
    "disk.dataPartition.size": "4G",
}
lines = [l for l in open(config).read().splitlines()
         if l.split("=", 1)[0].strip() not in props]
lines += [f"{k}={v}" for k, v in props.items()]
open(config, "w").write("\n".join(lines) + "\n")
PY
  echo "AVD '$AVD_NAME' ready (${LCD_WIDTH}x${LCD_HEIGHT} @ ${LCD_DENSITY}dpi, API $API_LEVEL, $ABI)."
  echo "Start it with: tools/karoo-avd.sh start"
}

start() {
  require_sdk
  exec "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load "$@"
}

delete() {
  require_sdk
  "$AVDMANAGER" delete avd -n "$AVD_NAME"
}

case "${1:-create}" in
  create) create ;;
  start) shift || true; start "$@" ;;
  delete) delete ;;
  *) echo "usage: $0 {create|start|delete}" >&2; exit 2 ;;
esac
