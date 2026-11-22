#!/usr/bin/env bash
# Flash one demo phone, ready to hand to a judge: the latest app, a clean start, every permission granted (so no
# dialogs), the screen kept on while plugged in, and the passenger for that phone.
#
#   scripts/flash-demo-phone.sh <adb-serial> [persona] [extra am-start args…]
#
#   persona: supersaver (phone 1, the default), ticket (phone 2), ga (phone 3, waits at Lausanne)
#   e.g.     scripts/flash-demo-phone.sh 67281JEA307289 ticket --es car 3
#
# The app defaults to the live demo server and has automatic check-in on; phone 3 follows IR 90 from Lausanne instead.
# Build first if needed: ./gradlew :composeApp:assembleDebug   (run from sbb-prototype/native)
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL="${1:?usage: $0 <adb-serial> [persona] [am-start extras…]}"
PERSONA="${2:-supersaver}"
shift $(( $# >= 2 ? 2 : 1 ))
ADB="${ADB:-adb}"; command -v "$ADB" >/dev/null || ADB="$HOME/Android/Sdk/platform-tools/adb"
APK=composeApp/build/outputs/apk/debug/composeApp-debug.apk
PKG=railstatus.mobile
a() { "$ADB" -s "$SERIAL" "$@"; }

[ -f "$APK" ] || { echo "No APK at $APK: run ./gradlew :composeApp:assembleDebug first"; exit 1; }
echo "• installing $(git log --oneline -1 | cut -c1-60)"
# A build signed on another laptop can't be updated in place: uninstall it first (the data is cleared anyway).
if ! out=$(a install -r "$APK" 2>&1); then
  if echo "$out" | grep -q "UPDATE_INCOMPATIBLE\|signatures do not match"; then
    echo "• signed by another laptop: reinstalling"
    a uninstall "$PKG" >/dev/null
    a install "$APK" >/dev/null
  else
    echo "$out"; exit 1
  fi
fi
echo "• clean start"
a shell pm clear "$PKG" >/dev/null
for p in POST_NOTIFICATIONS BLUETOOTH_SCAN BLUETOOTH_CONNECT ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
  a shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
done
a shell svc power stayon usb
a shell input keyevent KEYCODE_WAKEUP

case "$PERSONA" in
  ga) EXTRA=(--es persona ga --es passive off --es follow IR90-1734 --es at 8501120 --es dest 8501506) ;;
  *)  EXTRA=(--es persona "$PERSONA") ;;
esac
a shell am start -n "$PKG/.MainActivity" "${EXTRA[@]}" "$@" >/dev/null
echo "• started as $PERSONA"

if [ "$(a shell settings get global zen_mode | tr -d '\r')" != "0" ]; then
  echo "! Do Not Disturb is ON on this phone: turn it off, or the alerts arrive silently."
fi
echo "done: $SERIAL"
