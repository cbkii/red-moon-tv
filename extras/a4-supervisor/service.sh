#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Magisk late_start only. No staged executable, panel setter or arbitrary command surface.
if [ "${ASH_STANDALONE:-}" != 1 ] && [ -x /data/adb/magisk/busybox ]; then
  ASH_STANDALONE=1 exec /data/adb/magisk/busybox sh "$0"
fi
MODDIR=${0%/*}
# Magisk BusyBox provides flock; the kernel releases this lock after death/reboot.
exec 9> "$MODDIR/supervisor.lock"
flock -n 9 || exit 0
while [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
  IFS= read -r selected < "$MODDIR/selected-package" 2>/dev/null || exit 0
  case "$selected" in
    com.cbkii.redmoontv|com.cbkii.redmoontv.testing|com.cbkii.redmoontv.debug) ;;
    *) exit 0 ;;
  esac
  state=$(timeout 8 /system/bin/dumpsys package "$selected" 2>/dev/null)
  user=$(printf '%s\n' "$state" | sed -n '/User 0:/p' | head -n 1)
  case "$user" in
    *stopped=true*|*enabled=2*|*enabled=3*|*enabled=4*)
      /system/bin/log -t RedMoonTV 'Supervisor respects stopped/disabled package; exiting'
      exit 0 ;;
    '') sleep 30; continue ;;
  esac
  result=$(timeout 12 /system/bin/content call --uri "content://$selected.diagnostics" --method supervise --arg '{}' 2>&1)
  case "$result" in
    *'"enabled":false'*)
      /system/bin/log -t RedMoonTV 'Supervisor: all features disabled; exiting'
      exit 0 ;;
    *'"enabled":true'*) ;;
    *) /system/bin/log -t RedMoonTV 'Supervisor could not query app; retry in five minutes' ;;
  esac
  sleep 300
 done
