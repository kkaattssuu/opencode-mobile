set -eu
command -v opencode >/dev/null || { echo "OpenCode is not installed. Tap Install OpenCode." >&2; exit 20; }
version="$(opencode --version)"
version="${version#opencode }"
version="${version#v}"
case "$version" in
  2.*) ;;
  *) echo "OpenCode 2 is required; found: $version" >&2; exit 21 ;;
esac
for tool in curl git rg flock; do
  command -v "$tool" >/dev/null || { echo "Missing $tool. Tap Install OpenCode to complete setup." >&2; exit 22; }
done
printf 'OpenCode %s is ready.\n' "$version"
