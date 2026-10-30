#!/usr/bin/env bash
# Build the launcher, install it on the TV and turn on its Home-button helper.
# Usage: tools/install.sh <tv-ip>[:port]   (port 5555 when omitted)
set -euo pipefail
cd "$(dirname "$0")/.."
TV="${1:?usage: tools/install.sh <tv-ip>   (Settings > Network on the TV shows it)}"
[[ "$TV" == *:* ]] || TV="$TV:5555"
SERVICE=tv.yes.launcher/tv.yes.launcher.HomeKeyService
tv() { adb -s "$TV" shell "$@"; }

for k in TMDB_API_KEY FANART_API_KEY; do
  [ -n "${!k:-}" ] || echo "Warning: $k is not set; the Top Shelf will skip that service's art." >&2
done
./gradlew assembleDebug -q
adb connect "$TV" >/dev/null || true
if ! adb -s "$TV" get-state 2>/dev/null | grep -q device; then
  echo "On the TV: accept \"Allow USB debugging?\" (tick Always allow). Waiting..." >&2
  adb -s "$TV" wait-for-device
fi
adb -s "$TV" push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/yeslauncher.apk >/dev/null
tv pm install -r /data/local/tmp/yeslauncher.apk
tv rm /data/local/tmp/yeslauncher.apk

# On this Google TV build the stock launcher's HOME filter has priority 2, which
# Android picks before any default-Home setting, so no third-party launcher can
# own Home. HomeKeyService takes the Home button instead. Keep any other
# accessibility services that are already on.
current="$(tv settings get secure enabled_accessibility_services | tr -d '\r')"
case "$current" in
  *"$SERVICE"*|*tv.yes.launcher/.HomeKeyService*) ;;
  ""|null) tv settings put secure enabled_accessibility_services "$SERVICE" ;;
  *) tv settings put secure enabled_accessibility_services "$current:$SERVICE" ;;
esac
tv settings put secure accessibility_enabled 1
# After an update the service rebinds but Android does not re-arm its key filter
# until the setting changes, so toggle it once.
enabled="$(tv settings get secure enabled_accessibility_services | tr -d '\r')"
tv settings put secure enabled_accessibility_services '""'
sleep 1
tv settings put secure enabled_accessibility_services "$enabled"
# Continue watching fallback: lets the launcher read other apps' media sessions.
tv cmd notification allow_listener tv.yes.launcher/tv.yes.launcher.MediaListener
# Calendar card: the box's own synced Google Calendar (a runtime permission; a launcher can't ask on screen).
tv pm grant tv.yes.launcher android.permission.READ_CALENDAR || echo "Note: calendar permission not granted; the calendar card stays hidden." >&2

tv am start -n tv.yes.launcher/.LauncherActivity >/dev/null
sleep 3
if tv dumpsys accessibility | grep -q "tv.yes.launcher"; then
  echo "Installed; Home-button helper is bound."
else
  echo "Installed, but the Home-button helper is not bound (check Settings > Accessibility)." >&2
  exit 1
fi
