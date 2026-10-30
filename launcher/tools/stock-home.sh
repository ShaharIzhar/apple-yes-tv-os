#!/usr/bin/env bash
# Optional: stop the stock Google TV home and its recommendations service, which keep
# running behind this launcher (~100-200 MB on a 2 GB box). The yes firmware refuses
# `pm disable`, so this suspends / restricts them instead. Fully reversible.
#
# Usage: tools/stock-home.sh <tv-ip> off   # stop them now and keep them from running
#        tools/stock-home.sh <tv-ip> stop  # just stop them again (after a reboot)
#        tools/stock-home.sh <tv-ip> on    # undo everything
set -euo pipefail
TV="${1:?usage: tools/stock-home.sh <tv-ip> off|stop|on}"
[[ "$TV" == *:* ]] || TV="$TV:5555"
MODE="${2:?usage: tools/stock-home.sh <tv-ip> off|stop|on}"
HOME_PKG=com.google.android.tvlauncher
REC_PKG=com.google.android.tvrecommendations
REC_LISTENER=$REC_PKG/$REC_PKG.NotificationsService
tv() { adb -s "$TV" shell "$@"; }
adb connect "$TV" >/dev/null

case "$MODE" in
  off)
    # Recommendations: can't be opened, can't run in the background, and no longer a
    # notification listener (Android keeps every enabled listener running).
    tv pm suspend --user 0 "$REC_PKG"
    tv cmd appops set "$REC_PKG" RUN_ANY_IN_BACKGROUND ignore
    tv cmd notification disallow_listener "$REC_LISTENER"
    # The stock home stays the system Home (suspending it could hang the boot on an
    # error screen), so it is only kept from running in the background.
    tv cmd appops set "$HOME_PKG" RUN_ANY_IN_BACKGROUND ignore
    tv am force-stop "$REC_PKG"
    tv am force-stop "$HOME_PKG"
    ;;
  stop)
    tv am force-stop "$REC_PKG"
    tv am force-stop "$HOME_PKG"
    ;;
  on)
    tv pm unsuspend --user 0 "$REC_PKG"
    tv cmd appops reset --user 0 "$REC_PKG"
    tv cmd appops reset --user 0 "$HOME_PKG"
    tv cmd notification allow_listener "$REC_LISTENER"
    echo "Restored. Reboot the TV once."
    exit 0
    ;;
  *)
    echo "usage: tools/stock-home.sh <tv-ip> off|stop|on" >&2
    exit 2
    ;;
esac
tv cat /proc/meminfo | grep MemAvailable
