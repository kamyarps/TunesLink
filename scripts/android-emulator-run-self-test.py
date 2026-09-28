#!/usr/bin/env python3
"""Exercise boot hangs, deadlines, retries, and failure propagation without an SDK."""

import argparse
import importlib.util
import io
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location("launcher", Path(__file__).with_name("android-emulator-run.py"))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


BOOTED = "[sys.boot_completed]: [1]\n"
ENCRYPTING = BOOTED + "[vold.decrypt]: [trigger_restart_min_framework]\n[init.svc.encrypt]: [running]\n"
ENCRYPTED = BOOTED + "[vold.decrypt]: [trigger_restart_framework]\n[init.svc.encrypt]: [stopped]\n"
ANDROID_PACKAGE = "package:/system/framework/framework-res.apk"


class EmulatorRunnerTests(unittest.TestCase):
    def test_hung_adb_command_is_terminated(self):
        log = io.StringIO()
        started = time.monotonic()
        result = launcher.bounded_command([sys.executable, "-c", "import time; time.sleep(60)"], 0.1, log)
        self.assertIsNone(result)
        self.assertLess(time.monotonic() - started, 3)
        self.assertIn("timed out", log.getvalue())

    def test_boot_has_elapsed_deadline_even_when_probes_time_out(self):
        clock = [0.0]
        def probe(command, timeout, log):
            clock[0] += timeout
            return None
        def sleep(seconds):
            clock[0] += seconds
        with patch.object(launcher.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(launcher.time, "sleep", side_effect=sleep), \
                patch.object(launcher, "bounded_command", side_effect=probe) as calls:
            with self.assertRaises(launcher.BootFailure):
                launcher.wait_for_boot(["adb"], Mock(poll=lambda: None), 25, io.StringIO())
        self.assertEqual(clock[0], 25)
        self.assertEqual([call.args[1] for call in calls.call_args_list], [10, 10, 1])

    def test_boot_succeeds_after_offline_probe(self):
        with patch.object(launcher, "bounded_command", side_effect=[None, "", BOOTED, ANDROID_PACKAGE, BOOTED]), \
                patch.object(launcher.time, "sleep"):
            launcher.wait_for_boot(["adb"], Mock(poll=lambda: None), 30, io.StringIO())

    def test_encryption_framework_is_not_ready_even_with_boot_completed(self):
        for state in ("trigger_encryption", "trigger_restart_min_framework", "trigger_shutdown_framework",
                      "trigger_reset_main", "trigger_post_fs_data", "trigger_load_persist_props"):
            with self.subTest(state=state):
                self.assertFalse(launcher.framework_booted(BOOTED + f"[vold.decrypt]: [{state}]\n"))
        self.assertFalse(launcher.framework_booted(BOOTED + "[init.svc.encrypt]: [running]\n"))
        self.assertFalse(launcher.framework_booted(BOOTED + "[init.svc.encrypt]: [restarting]\n"))

    def test_waits_for_encryption_restart_before_probing_package_manager(self):
        outputs = [ENCRYPTING, "[sys.boot_completed]: [0]\n", ENCRYPTED, ANDROID_PACKAGE, ENCRYPTED]
        with patch.object(launcher, "bounded_command", side_effect=outputs) as calls, \
                patch.object(launcher.time, "sleep"):
            launcher.wait_for_boot(["adb"], Mock(poll=lambda: None), 180, io.StringIO())
        self.assertEqual([call.args[0] for call in calls.call_args_list], [
            ["adb", "shell", "getprop"], ["adb", "shell", "getprop"], ["adb", "shell", "getprop"],
            ["adb", "shell", "pm", "path", "android"], ["adb", "shell", "getprop"],
        ])

    def test_waits_for_package_manager_and_rechecks_boot_after_it_responds(self):
        outputs = [BOOTED, None, BOOTED, "Error: package manager unavailable", BOOTED, ANDROID_PACKAGE,
                   ENCRYPTING, ENCRYPTED, ANDROID_PACKAGE, ENCRYPTED]
        with patch.object(launcher, "bounded_command", side_effect=outputs) as calls, \
                patch.object(launcher.time, "sleep"):
            launcher.wait_for_boot(["adb"], Mock(poll=lambda: None), 180, io.StringIO())
        self.assertEqual(calls.call_count, len(outputs))

    def test_package_manager_probes_share_boot_deadline(self):
        clock = [0.0]
        def probe(command, timeout, log):
            clock[0] += timeout
            return BOOTED if command[-1] == "getprop" else None
        def sleep(seconds):
            clock[0] += seconds
        with patch.object(launcher.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(launcher.time, "sleep", side_effect=sleep), \
                patch.object(launcher, "bounded_command", side_effect=probe) as calls:
            with self.assertRaises(launcher.BootFailure):
                launcher.wait_for_boot(["adb"], Mock(poll=lambda: None), 25, io.StringIO())
        self.assertEqual(clock[0], 25)
        self.assertEqual([call.args[1] for call in calls.call_args_list], [10, 10, 3])

    def test_emulator_exit_fails_without_waiting_for_deadline(self):
        with self.assertRaisesRegex(launcher.BootFailure, "code 7"):
            launcher.wait_for_boot(["adb"], Mock(poll=lambda: 7, returncode=7), 180, io.StringIO())

    def session(self, failures, test_result):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(reports=Path(directory), port=5554, adb="adb", emulator="emulator",
                                      avd="test", memory=2048, wipe_data=True, boot_attempts=2, boot_timeout=180,
                                      test_timeout=1200, command=["tests"])
            with patch.object(launcher.subprocess, "Popen"), \
                    patch.object(launcher, "bounded_command", return_value=""), \
                    patch.object(launcher, "stop_process") as stop, \
                    patch.object(launcher, "wait_for_boot", side_effect=failures) as boot, \
                    patch.object(launcher, "run_checks", return_value=test_result) as checks:
                result = launcher.run(args)
                return result, boot.call_count, checks.call_count, stop.call_count, list(Path(directory).iterdir())

    def test_startup_retries_once_and_keeps_diagnostics(self):
        result, boots, checks, stops, files = self.session([launcher.BootFailure("hung"), None], 0)
        self.assertEqual((result, boots, checks, stops), (0, 2, 1, 2))
        self.assertEqual(len(files), 6)

    def test_failed_startup_never_runs_app_tests(self):
        result, boots, checks, stops, _ = self.session([launcher.BootFailure("hung")] * 2, 0)
        self.assertEqual((result, boots, checks, stops), (1, 2, 0, 2))

    def test_test_failure_is_preserved_without_retry(self):
        result, boots, checks, stops, _ = self.session([None], 42)
        self.assertEqual((result, boots, checks, stops), (42, 1, 1, 1))

    def test_test_process_has_a_separate_deadline(self):
        started = time.monotonic()
        result = launcher.run_checks([sys.executable, "-c", "import time; time.sleep(60)"], 0.1, "emulator-5554")
        self.assertEqual(result, 124)
        self.assertLess(time.monotonic() - started, 3)

    def test_cleanup_kills_children_even_after_parent_exits(self):
        child = "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); print('ready', flush=True); time.sleep(60)"
        parent = f"import subprocess,sys,time; subprocess.Popen([sys.executable, '-c', {child!r}]); time.sleep(60)"
        process = subprocess.Popen([sys.executable, "-c", parent], stdout=subprocess.PIPE,
                                   text=True, start_new_session=True)
        try:
            self.assertTrue(select.select([process.stdout], [], [], 2)[0], "child did not start")
            self.assertEqual(process.stdout.readline().strip(), "ready")
            launcher.stop_process(process)
            # The child inherits stdout; EOF proves it was stopped too.
            process.communicate(timeout=2)
        finally:
            launcher.stop_process(process)
            process.stdout.close()


if __name__ == "__main__":
    unittest.main()
