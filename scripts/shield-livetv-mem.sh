#!/usr/bin/env bash
# Spec 67 G1c Shield measurement (D2 gate): Live TV memory and guide parse/decode times.
# Read-only on the device: reads /proc, dumpsys meminfo and logcat; never installs, launches,
# stops or clears anything. Run only on Arshad's explicit go (AGENTS.md Shield access rule);
# the procedure (which build, when to run each label) is Spec 67 "G1c Shield measurement".
#
# Usage: scripts/shield-livetv-mem.sh <label> [serial] [seconds]
#   label    baseline | g1c-refresh | g1c-cold (names the log file)
#   seconds  sampling length, default 60 (one sample every 5 s)
# Output: builds/logs/<date>-s67-<label>.log (also printed).
set -euo pipefail

LABEL="${1:?usage: $0 <label> [serial] [seconds]}"
SERIAL="${2:-192.0.2.119:5555}"
SECS="${3:-60}"
PKG=com.nuvio.tv.ke
ADB=./tools/platform-tools/adb
OUT="builds/logs/$(date +%F-%H%M)-s67-$LABEL.log"

"$ADB" connect "$SERIAL" >/dev/null
# Every command targets the Shield with -s: never another connected device (an emulator).
if [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null || true)" != device ]; then
  echo "Shield $SERIAL is not connected; nothing measured." >&2
  exit 1
fi
dsh() { "$ADB" -s "$SERIAL" shell "$@" | tr -d '\r'; }

PID="$(dsh pidof "$PKG" || true)"
if [ -z "$PID" ]; then
  echo "$PKG is not running: open Live TV on the Shield first." >&2
  exit 1
fi
mkdir -p builds/logs

{
  echo "# Spec 67 $LABEL $(date -Is) serial $SERIAL pid $PID"
  dsh dumpsys package "$PKG" | grep -m2 -E 'versionCode=|versionName=' || true
  echo
  echo "## Samples every 5 s: seconds, VmRSS kB, VmHWM kB (peak), TOTAL PSS kB, MemAvailable kB"
  for ((t = 0; t < SECS; t += 5)); do
    status="$(dsh cat "/proc/$PID/status" 2>/dev/null || true)"
    rss="$(awk '/^VmRSS/ {print $2}' <<<"$status")"
    hwm="$(awk '/^VmHWM/ {print $2}' <<<"$status")"
    pss="$(dsh dumpsys meminfo "$PKG" | awk '/TOTAL PSS:/ {print $3; exit}')"
    avail="$(dsh cat /proc/meminfo | awk '/^MemAvailable/ {print $2}')"
    echo "$t ${rss:--} ${hwm:--} ${pss:--} ${avail:--}"
    sleep 5
  done
  echo
  echo "## dumpsys meminfo $PKG (end of sampling)"
  dsh dumpsys meminfo "$PKG"
  echo
  echo "## logcat: guide fetch + parse (LiveSourceManager), cache decode (LiveTvJsonFile)"
  "$ADB" -s "$SERIAL" logcat -d -v time -s LiveSourceManager:I LiveTvJsonFile:I | tr -d '\r'
} | tee "$OUT"

echo "Saved $OUT" >&2
