#!/usr/bin/env python3
"""Exercise the real setup scripts without downloading dependencies or changing the host."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
WINDOWS = os.name == "nt"


class SetupTests(unittest.TestCase):
    def run_setup(self, stale_home):
        java = shutil.which("java")
        self.assertIsNotNone(java, "run these checks with JDK 27 on PATH")
        jdk = Path(java).resolve().parent.parent
        version = subprocess.check_output([str(jdk / "bin" / ("java.exe" if WINDOWS else "java")), "-version"],
                                          stderr=subprocess.STDOUT, text=True)
        self.assertRegex(version, r'version "27(?:[.+"]|$)')
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            scripts = directory / "scripts"
            scripts.mkdir()
            name = "setup.ps1" if WINDOWS else "setup.sh"
            shutil.copy2(ROOT / "scripts" / name, scripts / name)
            wrapper = directory / ("gradlew.bat" if WINDOWS else "gradlew")
            if WINDOWS:
                wrapper.write_text('@echo off\n> selected-jdk.txt echo %JAVA_HOME%\njava -version\n', encoding="utf-8")
                powershell = shutil.which("powershell") or shutil.which("pwsh")
                self.assertIsNotNone(powershell, "PowerShell is required for the Windows setup check")
                command = [powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(scripts / name)]
            else:
                wrapper.write_text('#!/usr/bin/env bash\nset -e\nprintf "%s\\n" "$JAVA_HOME" > selected-jdk.txt\njava -version\n',
                                   encoding="utf-8")
                wrapper.chmod(0o755)
                command = ["bash", str(scripts / name)]
            env = os.environ.copy()
            if stale_home:
                old = directory / "old-jdk"
                (old / "bin").mkdir(parents=True)
                if not WINDOWS:
                    for executable in ("java", "javac"):
                        candidate = old / "bin" / executable
                        candidate.write_text('#!/bin/sh\necho \'openjdk version "11.0.0"\' >&2\n', encoding="utf-8")
                        candidate.chmod(0o755)
                env["JAVA_HOME"] = str(old)
            else:
                env["JAVA_HOME"] = str(jdk)
            result = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn("JDK 27 already installed", result.stdout)
            selected = Path((directory / "selected-jdk.txt").read_text().strip()).resolve()
            self.assertEqual(selected, jdk, result.stdout)

    def test_supported_java_home_is_retained(self):
        self.run_setup(stale_home=False)

    def test_stale_java_home_cannot_override_jdk_27_on_path(self):
        self.run_setup(stale_home=True)


if __name__ == "__main__":
    unittest.main()
