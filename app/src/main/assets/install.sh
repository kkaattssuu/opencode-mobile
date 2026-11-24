set -eu
[ "$(uname -m)" = aarch64 ] || { echo "This build needs a 64-bit ARM phone." >&2; exit 1; }
[ "$(dpkg --print-architecture)" = aarch64 ] || { echo "Install the 64-bit ARM (aarch64) version of Termux." >&2; exit 1; }
pkg update -y
pkg install -y curl git ripgrep util-linux
mkdir -p "$HOME/.local/state/opencode-android"
installer="$HOME/.local/state/opencode-android/repo-setup.sh"
# Pin the reviewed installer, not a mutable branch. Packages still update through the signed repository.
curl --fail --location --connect-timeout 15 --max-time 120 \
  https://raw.githubusercontent.com/bd-loser/opencode-bionic/9f0ff5fccd6a1f8ff41213ffdf58d96d4cf2cfa5/repo-setup.sh \
  -o "$installer"
bash "$installer"
