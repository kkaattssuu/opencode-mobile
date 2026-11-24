set -eu
pid="${1:-}"
case "$pid" in
  ''|*[!0-9]*|0*|1) echo "Invalid server process." >&2; exit 1 ;;
esac
: "${OPENCODE_PASSWORD:?Missing app server credential}"
if [ ! -d "/proc/$pid" ]; then
  echo "OpenCode is already stopped."
  exit 0
fi
# Never signal a reused PID or a server belonging to another Termux session.
cmdline="$(tr '\000' '\n' < "/proc/$pid/cmdline")"
executable="${cmdline%%$'\n'*}"
case "$executable" in
  opencode|*/opencode|*/opencode.exe) ;;
  *) echo "Process does not belong to OpenCode; nothing was stopped." >&2; exit 1 ;;
esac
printf '%s\n' "$cmdline" | grep -Fxq 'serve'
printf '%s\n' "$cmdline" | grep -Fxq '4096'
tr '\000' '\n' < "/proc/$pid/environ" | grep -Fxq "OPENCODE_PASSWORD=$OPENCODE_PASSWORD" \
  || { echo "Server ownership could not be verified; nothing was stopped." >&2; exit 1; }
kill -TERM "$pid"
for ((attempt = 0; attempt < 100; attempt++)); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "OpenCode stopped."
    exit 0
  fi
  sleep 0.1
done
echo "OpenCode has not finished shutting down. No forced kill was used." >&2
exit 1
