"""Host-only checks: never downloads or installs anything."""
import os
import fcntl
from pathlib import Path
import subprocess
import tempfile
import unittest
import shutil
import threading

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"


class ScriptChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = {**os.environ, "HOME": str(self.root), "PATH": f"{self.bin}:{os.environ['PATH']}"}

    def fake(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\n" + body + "\n")
        path.chmod(0o755)

    def run_script(self, name):
        script = (ASSETS / name).read_text()
        if name == "install.sh":
            script += "\n" + (ASSETS / "probe.sh").read_text()
        return subprocess.run(["bash", "-c", script], env=self.env,
                              capture_output=True, text=True, timeout=10)

    def installer_tools(self):
        self.fake("uname", "echo aarch64")
        self.fake("dpkg", "echo aarch64")
        self.fake("pkg", "exit 0")
        self.fake("opencode", "echo 2.4.0")
        self.fake("rg", "exit 0")
        self.fake("git", "exit 0")

    def test_rejects_other_architectures_before_packages(self):
        self.fake("uname", "echo x86_64")
        self.fake("pkg", 'touch "$HOME/packages-touched"')
        result = self.run_script("install.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "packages-touched").exists())

    def test_download_failure_never_executes_installer(self):
        self.installer_tools()
        self.fake("curl", "exit 22")
        self.assertNotEqual(self.run_script("install.sh").returncode, 0)

    def test_rejects_32bit_termux_before_package_changes(self):
        self.installer_tools()
        self.fake("dpkg", "echo arm")
        self.fake("pkg", 'touch "$HOME/packages-touched"')
        result = self.run_script("install.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("64-bit ARM", result.stderr)
        self.assertFalse((self.root / "packages-touched").exists())

    def test_package_failure_stops_before_download(self):
        self.installer_tools()
        self.fake("pkg", "exit 1")
        self.fake("curl", 'touch "$HOME/download-touched"')
        self.assertNotEqual(self.run_script("install.sh").returncode, 0)
        self.assertFalse((self.root / "download-touched").exists())

    def test_success_and_v1_rejection(self):
        self.installer_tools()
        self.fake("curl", 'while [ "$#" -gt 0 ]; do\n'
                  '  if [ "$1" = -o ]; then shift; printf "exit 0\\n" > "$1"; exit 0; fi\n'
                  '  shift\n'
                  'done\nexit 1')
        self.assertEqual(self.run_script("install.sh").returncode, 0)
        self.fake("opencode", "echo 1.19.0")
        self.assertNotEqual(self.run_script("install.sh").returncode, 0)

    def test_probe_accepts_real_phone_version_format(self):
        self.installer_tools()
        for version in ("2.4.0", "v2.3.2", "opencode v2.3.2", "opencode 2.3.2"):
            with self.subTest(version=version):
                self.fake("opencode", f"echo '{version}'")
                result = self.run_script("probe.sh")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("is ready.", result.stdout)
        self.fake("opencode", "echo opencode v1.19.0")
        self.assertEqual(self.run_script("probe.sh").returncode, 21)

    def test_server_has_local_host_port_and_private_project_directory(self):
        self.fake("opencode", 'printf "%s\\n" "$PWD" "$@"')
        result = self.run_script("start.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(),
                         [str(self.root / "projects"), "serve", "--hostname",
                          "127.0.0.1", "--port", "4096"])

    def test_duplicate_server_is_not_started(self):
        self.fake("opencode", 'touch "$HOME/duplicate-started"')
        state = self.root / ".local/state/opencode-android"
        state.mkdir(parents=True)
        with (state / "server.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertEqual(self.run_script("start.sh").returncode, 75)
        self.assertFalse((self.root / "duplicate-started").exists())

    def stop_script(self, pid, password="test-secret"):
        script = 'set -- "' + str(pid) + '"\n' + (ASSETS / "stop.sh").read_text()
        return subprocess.run(["bash", "-c", script],
                              env={**self.env, "OPENCODE_PASSWORD": password},
                              capture_output=True, text=True, timeout=15)

    def fake_server(self):
        path = self.bin / "opencode"
        shutil.copyfile("/bin/bash", path)
        path.chmod(0o755)
        proc = subprocess.Popen([str(path), "-c", "while :; do read -t 1 -r _ || :; done",
                                 "serve", "4096"], stdin=subprocess.PIPE,
                                env={**self.env, "OPENCODE_PASSWORD": "test-secret"})
        self.addCleanup(lambda: proc.poll() is None and proc.terminate())
        self.addCleanup(proc.stdin.close)
        # Termux reaps exited children; emulate that without leaving a zombie.
        threading.Thread(target=proc.wait, daemon=True).start()
        return proc

    def test_stop_rejects_invalid_pids(self):
        for pid in ("0", "1", "-1", "000", "0123", "2;echo unsafe"):
            self.assertNotEqual(self.stop_script(pid).returncode, 0, pid)

    def test_stop_leaves_wrong_owner_running(self):
        proc = self.fake_server()
        result = self.stop_script(proc.pid, "wrong-password")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(proc.poll())

    def test_stop_gracefully_terminates_only_owned_server(self):
        proc = self.fake_server()
        result = self.stop_script(proc.pid)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(proc.poll())


if __name__ == "__main__":
    unittest.main()
