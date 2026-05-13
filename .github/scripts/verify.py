#!/usr/bin/env python3
"""
CI self-verification of Hermes APK.

Runs inside a GitHub Actions Android emulator:
1. Install APK with a prefilled test server URL
2. Launch MainActivity with Intent extra "prefill_url"
3. Wait for WebView to load
4. Take screenshots + dump debug logs
5. Use visual + log heuristics to decide pass/fail
6. Exit 0 = pass, 1 = fail (Release step will be skipped)
"""
import os
import re
import subprocess
import sys
import time
from pathlib import Path


ARTIFACTS = Path(os.environ.get("ARTIFACTS_DIR", "verify-artifacts"))
ARTIFACTS.mkdir(parents=True, exist_ok=True)

APP_ID = "ai.hermes.app"
TEST_URL = os.environ.get("TEST_URL", "http://10.0.2.2:8648")  # 10.0.2.2 = emulator host


def sh(cmd: str, check: bool = True, **kw) -> str:
    print(f"$ {cmd}", flush=True)
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, **kw)
    if r.stdout:
        print(r.stdout, flush=True)
    if r.stderr:
        print(r.stderr, file=sys.stderr, flush=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"cmd failed ({r.returncode}): {cmd}")
    return r.stdout


def adb(cmd: str, check: bool = True) -> str:
    return sh(f"adb {cmd}", check=check)


def screenshot(name: str):
    out = ARTIFACTS / f"{name}.png"
    adb(f"exec-out screencap -p > {out}", check=False)
    print(f"Saved screenshot: {out}", flush=True)
    return out


def wait_for_device():
    print("Waiting for device…", flush=True)
    adb("wait-for-device")
    for _ in range(60):
        state = adb("shell getprop sys.boot_completed", check=False).strip()
        if state == "1":
            print("Device booted", flush=True)
            return
        time.sleep(2)
    raise RuntimeError("Device boot timeout")


def install_apk(apk_path: str):
    print(f"Installing {apk_path}…", flush=True)
    adb(f"install -r -g {apk_path}")


def launch_with_url(url: str):
    """Launch MainActivity with the prefill_url extra so we skip the first-launch dialog."""
    print(f"Launching {APP_ID} with prefill_url={url}", flush=True)
    adb("shell am force-stop " + APP_ID, check=False)
    # Clear any previous prefs
    adb(f"shell pm clear {APP_ID}", check=False)
    time.sleep(1)
    cmd = (
        f"shell am start -n {APP_ID}/.MainActivity "
        f"--es prefill_url '{url}'"
    )
    adb(cmd)


def dump_logcat(name: str):
    out = ARTIFACTS / f"{name}.log"
    log = adb("logcat -d -s Hermes:V HermesJS:V WebView:V chromium:V AndroidRuntime:E", check=False)
    out.write_text(log)
    print(f"Saved logcat: {out} ({len(log)} bytes)", flush=True)
    return log


def verify_display() -> tuple[bool, str]:
    """Check if the chat page actually rendered."""
    dump_path = ARTIFACTS / "uiautomator.xml"
    adb(f"exec-out uiautomator dump /dev/tty > {dump_path}", check=False)

    xml_raw = dump_path.read_text(errors="replace") if dump_path.exists() else ""

    # Heuristic 1: WebView element must exist in view hierarchy
    if "WebView" not in xml_raw and "webview" not in xml_raw:
        return False, "WebView not found in UI hierarchy"

    # Heuristic 2: check if Hermes UI text appears
    hermes_markers = ["Hermes", "Access token", "Type a message", "Sign Out", "CONVERSATION"]
    found = [m for m in hermes_markers if m in xml_raw]

    # Look at logcat for errors
    log = "\n".join([
        (ARTIFACTS / p).read_text(errors="replace") for p in ("pre-load.log", "post-load.log") if (ARTIFACTS / p).exists()
    ])

    # Critical JS errors
    critical_patterns = [
        r"HermesJS.*Uncaught",
        r"net::ERR_",
        r"Failed to fetch",
    ]
    critical_hits = []
    for pat in critical_patterns:
        if re.search(pat, log):
            critical_hits.append(pat)

    msg_parts = []
    msg_parts.append(f"UI markers found: {found}")
    msg_parts.append(f"Critical log patterns: {critical_hits}")

    # Pass if we see any Hermes marker AND no critical errors
    ok = (len(found) > 0) and (len(critical_hits) == 0)
    return ok, " | ".join(msg_parts)


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else "app-debug.apk"
    if not Path(apk).exists():
        print(f"APK not found: {apk}", file=sys.stderr)
        sys.exit(1)

    wait_for_device()
    adb("logcat -c")

    install_apk(apk)
    launch_with_url(TEST_URL)

    print("Waiting 3s for app launch…", flush=True)
    time.sleep(3)
    screenshot("01-launched")
    dump_logcat("pre-load")

    print("Waiting 20s for page load…", flush=True)
    time.sleep(20)
    screenshot("02-loaded")
    dump_logcat("post-load")

    ok, reason = verify_display()
    (ARTIFACTS / "verdict.txt").write_text(f"{'PASS' if ok else 'FAIL'}\n{reason}\n")
    print(f"\n=== VERDICT: {'PASS' if ok else 'FAIL'} ===")
    print(reason)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
