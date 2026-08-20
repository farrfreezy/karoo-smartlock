#!/usr/bin/env bash
# Drive the SmartLock extension on an emulator (or any debug install) over adb.
#
#   tools/karoo-sim.sh start                 # start the extension service
#   tools/karoo-sim.sh ride recording        # idle | recording | pause | autopause
#   tools/karoo-sim.sh sensor hr 165         # hr | cadence | power | temp | distance
#   tools/karoo-sim.sh sensor hr off         # drop the reading (stream unavailable)
#   tools/karoo-sim.sh rain rain             # rain | dry | unknown
#   tools/karoo-sim.sh toggle                # bonus-action lock toggle
#   tools/karoo-sim.sh unlock                # padlock long-press equivalent
#   tools/karoo-sim.sh preview               # 10 s lock preview
#   tools/karoo-sim.sh hold hr 165           # resend every 3 s until Ctrl-C
#   tools/karoo-sim.sh log                   # tail SmartLock logcat
#
# Sensor readings older than 15 s are ignored by the reducer, so a one-shot
# `sensor` only holds a condition briefly — use `hold` to keep it live.
set -euo pipefail

PKG="io.github.farrfreezy.karoosmartlock"
SVC="$PKG/.KarooSmartLockExtension"
SIM_ACTION="$PKG.SIM"
PREVIEW_ACTION="$PKG.PREVIEW_LOCK"
ADB="${ADB:-adb}"
HOLD_INTERVAL="${HOLD_INTERVAL:-3}"

sim() { # sim <kind> [arg] [value]
  local args=(--es kind "$1")
  [[ $# -ge 2 ]] && args+=(--es arg "$2")
  [[ $# -ge 3 ]] && args+=(--es value "$3")
  "$ADB" shell am startservice -n "$SVC" -a "$SIM_ACTION" "${args[@]}" >/dev/null
}

case "${1:-}" in
  start)   "$ADB" shell am startservice -n "$SVC" ;;
  ride)    sim ride "${2:?usage: ride <idle|recording|pause|autopause>}" ;;
  sensor)  sim sensor "${2:?usage: sensor <name> <value|off>}" "${3:?usage: sensor <name> <value|off>}" ;;
  rain)    sim rain "${2:?usage: rain <rain|dry|unknown>}" ;;
  toggle)  sim toggle ;;
  unlock)  sim unlock ;;
  preview) "$ADB" shell am startservice -n "$SVC" -a "$PREVIEW_ACTION" >/dev/null ;;
  hold)
    name="${2:?usage: hold <sensor> <value>}"
    value="${3:?usage: hold <sensor> <value>}"
    echo "Holding $name=$value every ${HOLD_INTERVAL}s (Ctrl-C to stop)"
    while true; do sim sensor "$name" "$value"; sleep "$HOLD_INTERVAL"; done
    ;;
  log)     "$ADB" logcat -s SmartLock:V SmartLockSim:V AndroidRuntime:E ;;
  *)       sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
