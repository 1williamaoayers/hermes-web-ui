#!/usr/bin/env python3
"""
CI self-verification of Hermes APK.

Runs inside a GitHub Actions Android emulator:
1. Install APK with a prefilled test server URL
2. Launch MainActivity with Intent extra "prefill_url" + "?autotest=1" appended
3. Wait for WebView to load and auto-send a test message
4. Take screenshots + dump debug logs + uiautomator hierarchy
5. Verify: initial greeting rendered AND streamed reply marker present
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
# 10.0.2.2 = emulator's view of the host machine
TEST_URL = os.environ.get("TEST_URL", "http://10.0.2.2:8648/?autotest=1")


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


def dump_ui(name: str) -> str:
    tmp = ARTIFACTS / f"{name}-uiautomator.xml"
    adb("shell uiautomator dump /sdcard/window_dump.xml", check=False)
    adb(f"pull /sdcard/window_dump.xml {tmp}", check=False)
    return tmp.read_text(errors="replace") if tmp.exists() else ""


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
    print(f"Launching {APP_ID} with prefill_url={url}", flush=True)
    adb("shell am force-stop " + APP_ID, check=False)
    adb(f"shell pm clear {APP_ID}", check=False)
    time.sleep(1)
    # Quote single-quote-safe using shell escaping
    cmd = f"shell am start -n {APP_ID}/.MainActivity --es prefill_url \"{url}\""
    adb(cmd)


def dump_logcat(name: str) -> str:
    out = ARTIFACTS / f"{name}.log"
    log = adb(
        "logcat -d -s Hermes:V HermesJS:V WebView:V chromium:V AndroidRuntime:E",
        check=False,
    )
    out.write_text(log)
    print(f"Saved logcat: {out} ({len(log)} bytes)", flush=True)
    return log


def check_markers(xml: str) -> dict:
    """Verify that the UI hierarchy contains text nodes indicating a rendered chat."""
    markers = {
        "hermes_title": bool(re.search(r'text="Hermes"', xml)),
        "sign_out": "Sign Out" in xml,
        "conversation": "CONVERSATION" in xml,
        "greeting": "你好" in xml and "Hermes Agent" in xml,
        "input_placeholder": "Type a message" in xml,
        "chat_ready": "CHAT_READY" in xml,
        "reply_ready": bool(re.search(r'收到你的消息', xml)),
    }
    return markers


def verify_display() -> tuple[bool, str]:
    """Parse UI hierarchy + logs to decide pass/fail."""
    # Final UI dump (after waiting for stream to complete)
    xml = dump_ui("final")

    markers = check_markers(xml)
    log = "\n".join(
        [
            (ARTIFACTS / p).read_text(errors="replace")
            for p in ("pre-load.log", "post-load.log", "final.log")
            if (ARTIFACTS / p).exists()
        ]
    )

    critical_patterns = [
        r"HermesJS.*Uncaught",
        r"HermesJS.*E/.*connect_error",
        r"net::ERR_",
    ]
    critical_hits = [p for p in critical_patterns if re.search(p, log)]

    parts = []
    parts.append(f"Markers: {markers}")
    parts.append(f"Critical log hits: {critical_hits}")

    # Pass criteria:
    #   1. Greeting message rendered (initial DOM)
    #   2. Socket.IO streamed reply rendered (proves real chat flow)
    #   3. No critical JS/net errors
    must_have_greeting = markers["greeting"]
    must_have_reply = markers["reply_ready"]
    must_have_chat_ready = markers["chat_ready"]
    no_critical = len(critical_hits) == 0

    ok = must_have_greeting and must_have_reply and must_have_chat_ready and no_critical

    # Detailed reason
    if not must_have_greeting:
        parts.append("FAIL: initial greeting not rendered in WebView")
    if not must_have_chat_ready:
        parts.append("FAIL: Socket.IO never connected (CHAT_READY marker missing)")
    if not must_have_reply:
        parts.append(
            "FAIL: streamed reply not rendered (Socket.IO delta events not processed)"
        )
    if not no_critical:
        parts.append("FAIL: critical errors in logcat")

    return ok, " | ".join(parts)


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

    print("Waiting 15s for page load + socket connect…", flush=True)
    time.sleep(15)
    screenshot("02-loaded")
    dump_logcat("post-load")

    print("Waiting 10s for stream reply to complete…", flush=True)
    time.sleep(10)
    screenshot("03-replied")
    dump_logcat("final")

    ok, reason = verify_display()
    (ARTIFACTS / "verdict.txt").write_text(
        f"{'PASS' if ok else 'FAIL'}\n{reason}\n"
    )
    print(f"\n=== VERDICT: {'PASS' if ok else 'FAIL'} ===")
    print(reason)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
