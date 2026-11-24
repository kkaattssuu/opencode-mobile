set -eu
command -v opencode >/dev/null || { echo "Tap Install OpenCode first." >&2; exit 1; }
command -v flock >/dev/null || { echo "Tap Install OpenCode to install the required tools." >&2; exit 1; }
mkdir -p "$HOME/.local/state/opencode-android" "$HOME/projects"
cd "$HOME/projects"
# The lock prevents duplicate starts, including after the Android app restarts.
# Exit 75 means another launch already owns the lock; the app waits for that server.
exec flock -n -E 75 "$HOME/.local/state/opencode-android/server.lock" \
  opencode serve --hostname 127.0.0.1 --port 4096
