#!/bin/bash
# Start bombaclip robustly after reboot/relogin or compositor switch.
# Safe to run any time: kills stale holders of :8080, waits for the port,
# waits for a notification owner, resolves the CURRENT Wayland socket,
# then execs the server under a lock. Used by mango AND niri autostart.
set -u
LOG=/tmp/bombaclip.log
RUNTIME=/run/user/1000

# 1. Kill stale servers by pattern AND by port owner (covers every
#    invocation style: mango line, niri line, manual runs).
pkill -f 'bombaclip\.py' 2>/dev/null
for p in $(ss -tlnp 2>/dev/null | grep ':8080' | grep -oP 'pid=\K[0-9]+' | sort -u); do
  [ "$p" != "$$" ] && kill "$p" 2>/dev/null
done
# 2. Wait until :8080 is actually free (max ~10s) so the server never
#    starts into Errno 98.
for _ in $(seq 1 50); do
  ss -tln 2>/dev/null | grep -q ':8080' || break
  sleep 0.2
done
sleep 0.5
: > "$LOG"
# 3. Wait for a notification owner (quickshell), max ~30s.
#    Server still starts if absent; those notifies just fail in the log.
for _ in $(seq 1 30); do
  busctl --user list 2>/dev/null | grep -q 'org\.freedesktop\.Notifications' && break
  sleep 1
done
# 4. Resolve the CURRENT compositor socket (newest wayland-N wins;
#    stale sockets linger after a compositor switch, env may be stale).
export WAYLAND_DISPLAY=$(ls -t "$RUNTIME" 2>/dev/null | grep -m1 '^wayland-[0-9]*$')
export XDG_RUNTIME_DIR="$RUNTIME"
export BOMBACLIP_TOKEN=$(cat ~/.bombaclip_token)
cd /home/karasu/github/bombaclip
exec flock -n /tmp/bombaclip.lock python3 -u bombaclip.py >>"$LOG" 2>&1
