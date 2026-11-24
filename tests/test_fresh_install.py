"""Exercise real setup scripts with an empty, isolated, mocked Termux prefix."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"


class FreshInstallChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.bin = self.home / "bin"
        self.fixtures = self.home / "fixtures"
        self.bin.mkdir()
        self.fixtures.mkdir()
        self.bash = shutil.which("bash")
        # No host PATH fallback: OpenCode and dependencies really start absent.
        self.env = {**os.environ, "HOME": str(self.home), "PREFIX": str(self.home),
                    "PATH": str(self.bin)}
        for tool in ("bash", "mkdir", "cp"):
            (self.bin / tool).symlink_to(shutil.which(tool))
        self.script(self.bin / "uname", "echo aarch64")
        self.script(self.bin / "dpkg", "echo aarch64")
        self.script(self.fixtures / "opencode", "echo 'opencode v2.3.2'")
        for tool in ("git", "rg", "flock"):
            self.script(self.fixtures / tool, "exit 0")
        self.script(self.fixtures / "repo-setup",
                    'cp "$HOME/fixtures/opencode" "$HOME/bin/opencode"')
        self.script(self.fixtures / "curl", """
while [ "$#" -gt 0 ]; do
  if [ "$1" = -o ]; then shift; output="$1"; fi
  shift
done
[ "${FAIL_DOWNLOAD:-0}" != 1 ] || exit 22
cp "$HOME/fixtures/repo-setup" "$output"
""")
        self.script(self.bin / "pkg", """
printf '%s\\n' "$*" >> "$HOME/pkg.log"
case "$1" in
  install)
    for tool in curl git rg flock; do
      cp "$HOME/fixtures/$tool" "$HOME/bin/$tool"
    done ;;
esac
""")

    def script(self, path, body):
        path.write_text("#!/bin/sh\nset -eu\n" + body + "\n")
        path.chmod(0o755)

    def run_script(self, name):
        script = (ASSETS / name).read_text()
        if name == "install.sh":
            script += "\n" + (ASSETS / "probe.sh").read_text()
        return subprocess.run([self.bash, "-c", script], env=self.env,
                              capture_output=True, text=True, timeout=10)

    def test_empty_install_installs_dependencies_then_detects_v2(self):
        self.assertEqual(self.run_script("probe.sh").returncode, 20)
        self.assertFalse((self.bin / "curl").exists())
        result = self.run_script("install.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("OpenCode 2.3.2 is ready.", result.stdout)
        self.assertIn("install -y curl git ripgrep util-linux", (self.home / "pkg.log").read_text())
        self.assertEqual(self.run_script("probe.sh").returncode, 0)

    def test_retry_keeps_existing_projects_and_files(self):
        projects = self.home / "projects"
        projects.mkdir()
        marker = projects / "keep.txt"
        marker.write_text("User work must remain.")
        for _ in range(2):
            result = self.run_script("install.sh")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(marker.read_text(), "User work must remain.")

    def test_offline_failure_does_not_execute_stale_installer(self):
        state = self.home / ".local/state/opencode-android"
        state.mkdir(parents=True)
        self.script(state / "repo-setup.sh", 'printf stale > "$HOME/should-not-run"')
        self.env["FAIL_DOWNLOAD"] = "1"
        self.assertNotEqual(self.run_script("install.sh").returncode, 0)
        self.assertFalse((self.home / "should-not-run").exists())
        self.assertFalse((self.bin / "opencode").exists())
        self.env["FAIL_DOWNLOAD"] = "0"
        self.assertEqual(self.run_script("install.sh").returncode, 0)


if __name__ == "__main__":
    unittest.main()
