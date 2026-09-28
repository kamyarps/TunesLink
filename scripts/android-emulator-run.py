#!/usr/bin/env python3
"""Run device checks with bounded emulator startup, ADB probes, and cleanup (Linux)."""

import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time


class BootFailure(RuntimeError):
    pass


def stop_process(process):
    """Stop only the process group we launched, including orphaned test children."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)


def bounded_command(command, timeout, log):
    """A hung ADB client must not prevent the boot deadline from being checked."""
    log.write(f"{datetime.now(timezone.utc).isoformat()} {command!r}\n")
    log.flush()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
        log.write(output)
        log.write(f"Exit code: {process.returncode}\n")
        return output.strip() if process.returncode == 0 else None
    except subprocess.TimeoutExpired as error:
        if error.output:
            log.write(error.output.decode(errors="replace") if isinstance(error.output, bytes) else error.output)
        log.write(f"Command timed out after {timeout:.1f}s\n")
        return None
    finally:
        stop_process(process)
        if process.stdout is not None:
            process.stdout.close()
        log.flush()


def framework_booted(output):
    properties = dict(re.findall(r"^\[([^\]]+)\]: \[([^\]]*)\]$", output or "", re.MULTILINE))
    # API 23 sets boot_completed in the temporary CryptKeeper framework too.
    # Wait for /data encryption and the restart into the full framework before
    # installing anything: that restart discards the temporary /data contents.
    return (properties.get("sys.boot_completed") == "1"
            and properties.get("vold.decrypt", "") in ("", "trigger_restart_framework")
            and properties.get("init.svc.encrypt", "") in ("", "stopped"))


def wait_for_boot(adb, emulator, timeout, log):
    deadline = time.monotonic() + timeout

    def probe(command):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BootFailure(f"Emulator did not become ready within {timeout}s")
        return bounded_command(adb + command, min(10, remaining), log)

    while True:
        if emulator.poll() is not None:
            raise BootFailure(f"Emulator exited during boot (code {emulator.returncode})")
        if framework_booted(probe(["shell", "getprop"])):
            # API 23's adb shell can report exit code 0 for a failed command.
            # Require a real package-manager result, then recheck boot state in
            # case Android restarted while the framework command was running.
            package = probe(["shell", "pm", "path", "android"])
            if (package and any(line.startswith("package:") for line in package.splitlines())
                    and framework_booted(probe(["shell", "getprop"]))):
                print("Emulator booted; encryption finished and package manager ready.", flush=True)
                return
        time.sleep(min(2, max(0, deadline - time.monotonic())))


def run_checks(command, timeout, serial):
    environment = dict(os.environ, ANDROID_SERIAL=serial)
    process = subprocess.Popen(command, env=environment, start_new_session=True)
    try:
        return process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"Device checks exceeded {timeout}s", file=sys.stderr, flush=True)
        return 124
    finally:
        stop_process(process)


def run(args):
    args.reports.mkdir(parents=True, exist_ok=True)
    serial = f"emulator-{args.port}"
    adb = [args.adb, "-s", serial]
    command = [args.emulator, "-avd", args.avd, "-port", str(args.port),
               "-memory", str(args.memory), "-no-window", "-gpu", "swiftshader",
               "-noaudio", "-no-boot-anim", "-camera-back", "none", "-camera-front", "none",
               "-no-snapshot", "-show-kernel"]
    if args.wipe_data:
        command.append("-wipe-data")
    # Retry startup only. A failing app test must remain a failure, not be retried away.
    for attempt in range(1, args.boot_attempts + 1):
        prefix = args.reports / f"startup-{attempt}"
        print(f"Starting {args.avd}: attempt {attempt}/{args.boot_attempts}", flush=True)
        with prefix.with_suffix(".emulator.log").open("w") as emulator_log, \
                prefix.with_suffix(".adb.log").open("w") as adb_log:
            if bounded_command([args.adb, "start-server"], 10, adb_log) is None:
                print("ADB server did not start", file=sys.stderr, flush=True)
                continue
            emulator = subprocess.Popen(command, stdout=emulator_log, stderr=subprocess.STDOUT,
                                        start_new_session=True)
            try:
                try:
                    wait_for_boot(adb, emulator, args.boot_timeout, adb_log)
                    if bounded_command(adb + ["shell", "input", "keyevent", "82"], 10, adb_log) is None:
                        raise BootFailure("Emulator did not respond after boot")
                except BootFailure as error:
                    print(str(error), file=sys.stderr, flush=True)
                    adb_log.write(f"{error}\n")
                    continue
                return run_checks(args.command, args.test_timeout, serial)
            finally:
                # These files exist even when startup fails before any app reports are created.
                try:
                    with prefix.with_suffix(".logcat.log").open("w") as device_log:
                        bounded_command(adb + ["logcat", "-d", "-v", "threadtime"], 10, device_log)
                finally:
                    stop_process(emulator)
    print("Emulator startup failed; app tests were not run. See device-emulator reports.",
          file=sys.stderr, flush=True)
    return 1


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--emulator", default="emulator")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--port", type=positive_int, default=5554)
    parser.add_argument("--memory", type=positive_int, default=4096)
    parser.add_argument("--wipe-data", action="store_true",
                        help="reset this disposable AVD before each startup attempt")
    parser.add_argument("--boot-timeout", type=positive_int, default=180)
    parser.add_argument("--boot-attempts", type=positive_int, default=2)
    parser.add_argument("--test-timeout", type=positive_int, default=1200)
    parser.add_argument("--reports", type=Path,
                        default=Path("android/app/build/reports/device-emulator"))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command[:1] == ["--"]:
        args.command = args.command[1:]
    if not args.command:
        parser.error("provide a test command after --")
    if sys.platform != "linux":
        parser.error("this launcher requires Linux process groups")
    if args.port % 2 or not 5554 <= args.port <= 5682:
        parser.error("emulator port must be even and between 5554 and 5682")
    def interrupted(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        return run(args)
    except KeyboardInterrupt:
        return 130
    except OSError as error:
        print(f"Emulator launcher failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
