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


def wait_for_network():
    """Wait for emulator's WebView to be able to reach 10.0.2.2 (the host)."""
    print("Waiting for emulator network…", flush=True)
    for i in range(30):
        out = adb(
            "shell 'wget -q -O - http://10.0.2.2:8648/health 2>&1; echo EXIT=$?'",
            check=False,
        )
        if "ok" in out and "EXIT=0" in out:
            print(f"Network ready (probe {i+1})", flush=True)
            return True
        time.sleep(2)
    state = adb("shell ping -c 1 -W 2 10.0.2.2", check=False)
    if "1 received" in state or "1 packets received" in state:
        print("Network ping ok", flush=True)
        return True
    print("WARN: network may not be ready", flush=True)
    return False


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


def check_mock_server_evidence(mock_log_path: str) -> dict:
    """
    Parse mock server logs for proof that the WebView actually did things.
    This is more reliable than uiautomator (which can't see WebView DOM content).
    """
    try:
        log = Path(mock_log_path).read_text(errors="replace")
    except Exception:
        return {"log_accessible": False}

    return {
        "log_accessible": True,
        "page_served": bool(re.search(r"GET / ", log) or re.search(r"GET /\?", log)),
        "socketio_handshake": "/socket.io/" in log or "client connected" in log,
        "socket_connected": "client connected" in log,
        "user_message_received": "got user_message" in log,
        "stream_sent": "streamed reply complete" in log,
    }


def verify_display() -> tuple[bool, str]:
    """Parse mock server logs + UI hierarchy + logcat to decide pass/fail."""
    xml = dump_ui("final")

    ui_markers = check_markers(xml)

    mock_log_path = os.environ.get("MOCK_LOG", "mock-hermes.log")
    if not Path(mock_log_path).exists():
        # Try absolute path in GITHUB_WORKSPACE
        ws = os.environ.get("GITHUB_WORKSPACE", "")
        if ws:
            mock_log_path = str(Path(ws) / "mock-hermes.log")
    server_evidence = check_mock_server_evidence(mock_log_path)

    # Save mock log copy
    try:
        import shutil
        if Path(mock_log_path).exists():
            shutil.copy(mock_log_path, ARTIFACTS / "mock-hermes.log")
    except Exception as e:
        print(f"copy mock log failed: {e}")

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
    parts.append(f"UI markers: {ui_markers}")
    parts.append(f"Server evidence: {server_evidence}")
    parts.append(f"Critical log hits: {critical_hits}")

    # Pass criteria (server-side evidence is authoritative):
    # 1. Server saw the WebView load the page
    # 2. Server saw Socket.IO handshake complete
    # 3. Server received a user message (proves JS onClick / autotest works)
    # 4. Server streamed a reply back (proves client stayed connected)
    # 5. No critical native-side errors in logcat
    checks = {
        "page_served": server_evidence.get("page_served", False),
        "socket_connected": server_evidence.get("socket_connected", False),
        "user_message_received": server_evidence.get("user_message_received", False),
        "stream_sent": server_evidence.get("stream_sent", False),
        "no_critical_errors": len(critical_hits) == 0,
    }

    ok = all(checks.values())

    for name, passed in checks.items():
        if not passed:
            parts.append(f"FAIL: {name}")

    return ok, " | ".join(parts)


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else "app-debug.apk"
    if not Path(apk).exists():
        print(f"APK not found: {apk}", file=sys.stderr)
        sys.exit(1)

    wait_for_device()
    adb("logcat -c")
    wait_for_network()

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
