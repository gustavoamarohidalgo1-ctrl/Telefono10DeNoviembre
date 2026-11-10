#!/usr/bin/env python3
"""Read-only assertions around synthetic calls; changes settings only on an emulator.

Usage: python3 tools/check-policy-emulator.py --serial emulator-5554
Requires an installed, configured default dialer and adb on PATH. Saves a screenshot
and JSON report under docs/resultados/policy/. Sound is not acoustically tested.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "pe.gustavo.telefonolite"
CHANNEL = "calls_silent_v1"
OUT = Path(__file__).resolve().parents[1] / "docs" / "resultados" / "policy"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("--serial must be an emulator-N serial")
    base = ["adb", "-s", args.serial]

    def adb(*cmd, check=True, binary=False):
        result = subprocess.run(base + list(cmd), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, check=False, timeout=30)
        if check and result.returncode:
            raise RuntimeError(f"adb {cmd!r}: {result.stderr.decode(errors='replace').strip()}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Selected device does not report ro.kernel.qemu=1")
    OUT.mkdir(parents=True, exist_ok=True)
    report = {"serial": args.serial, "package": PACKAGE, "checks": {},
              "note": "No acoustic sound or vibration assertion was made."}
    checks = report["checks"]

    def shell(*cmd, check=True):
        return adb("shell", *cmd, check=check)

    def end_call():
        adb("emu", "gsm", "cancel", "5550110", check=False)
        adb("emu", "gsm", "cancel", "5550111", check=False)
        adb("emu", "gsm", "cancel", "5550112", check=False)

    def call(number):
        result = adb("emu", "gsm", "call", number)
        if "OK" not in result:
            raise RuntimeError("Emulator rejected synthetic call: " + result.strip())

    def await_value(getter, predicate, seconds=15):
        deadline = time.monotonic() + seconds
        last = ""
        while time.monotonic() < deadline:
            last = getter()
            if predicate(last):
                return last
            time.sleep(0.5)
        return last

    def ui():
        shell("uiautomator", "dump", "/sdcard/window.xml", check=False)
        return shell("cat", "/sdcard/window.xml", check=False)

    def notifications():
        return shell("dumpsys", "notification", "--noredact", check=False)

    def call_alive():
        return shell("dumpsys", "telecom", check=False).split("mCallAudioManager:", 1)[0]

    try:
        shell("cmd", "notification", "set_dnd", "off")
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard", check=False)
        shell("input", "keyevent", "KEYCODE_HOME")
        shell("input", "keyevent", "KEYCODE_SLEEP")
        call("5550110")
        xml = await_value(ui, lambda x: PACKAGE in x and "Responder" in x)
        activity = shell("dumpsys", "activity", "activities", check=False)
        shot = adb("exec-out", "screencap", "-p", binary=True)
        (OUT / "locked-incoming.png").write_bytes(shot)
        checks["locked_incoming"] = {"pass": PACKAGE in xml and "Responder" in xml and "InCallActivity" in activity,
                                     "package_in_ui": PACKAGE in xml, "answer_visible": "Responder" in xml,
                                     "activity_present": "InCallActivity" in activity,
                                     "screenshot": str(OUT / "locked-incoming.png")}
        end_call()
        time.sleep(2)
        shell("cmd", "notification", "set_dnd", "none")
        zen = await_value(lambda: shell("settings", "get", "global", "zen_mode"), lambda x: x.strip() == "2")
        call("5550111")
        live_keys = await_value(lambda: shell("cmd", "notification", "list"), lambda x: PACKAGE in x)
        dump = notifications()
        telecom = call_alive()
        # Channel settings and notification records are separate sections of dumpsys.
        # Capture only package-specific lines plus their local context for inspection.
        lines = dump.splitlines()
        indexes = [i for i, line in enumerate(lines) if PACKAGE in line or CHANNEL in line]
        relevant = "\n".join(lines[i] for i in sorted({j for i in indexes for j in range(max(0, i - 2), min(len(lines), i + 8))}))
        channel_lines = [line for line in lines if CHANNEL in line]
        channel = "\n".join(channel_lines)
        sound = bool(re.search(r"(?:mSound|sound)\s*[=:]\s*null\b", channel, re.I))
        vibration = bool(re.search(r"(?:mVibrationEnabled|vibrationEnabled|vibrate)\s*[=:]\s*false\b", channel, re.I))
        bypass = bool(re.search(r"(?:mBypassDnd|bypassDnd)\s*[=:]\s*false\b", channel, re.I))
        alive = bool(re.search(r"5550111|state=RINGING|mState=RINGING", telecom, re.I))
        checks["dnd"] = {"pass": zen.strip() == "2" and PACKAGE in live_keys and CHANNEL in dump and sound and vibration and bypass and alive,
                         "zen_mode": zen.strip(), "package_notification": PACKAGE in relevant,
                         "silent_sound": sound, "vibration_disabled": vibration, "bypass_disabled": bypass,
                         "call_alive": alive, "notification_excerpt": relevant[:16000]}
        end_call()
        time.sleep(2)
        shell("cmd", "notification", "set_dnd", "off")
        shell("appops", "set", PACKAGE, "USE_FULL_SCREEN_INTENT", "deny")
        call("5550112")
        dump = await_value(notifications, lambda x: PACKAGE in x and ("Responder" in x or "Answer" in x))
        telecom = call_alive()
        pid = shell("pidof", PACKAGE, check=False).strip()
        # The Android notification dump format varies; keep the evidence for review.
        lines = dump.splitlines()
        indexes = [i for i, line in enumerate(lines) if PACKAGE in line or CHANNEL in line]
        excerpt = "\n".join(lines[i] for i in sorted({j for i in indexes for j in range(max(0, i - 2), min(len(lines), i + 20))}))
        has_actions = bool(re.search(r"Responder|Answer", excerpt, re.I)) and bool(re.search(r"Rechazar|Decline", excerpt, re.I))
        alive = bool(re.search(r"5550112|state=RINGING|mState=RINGING", telecom, re.I))
        checks["fsi_denied"] = {"pass": bool(pid) and PACKAGE in excerpt and has_actions and alive,
                                "process_running": bool(pid), "incoming_actions": has_actions,
                                "call_alive": alive, "notification_excerpt": excerpt[:16000]}
    except Exception as exc:
        report["error"] = str(exc)
    finally:
        end_call()
        shell("appops", "set", PACKAGE, "USE_FULL_SCREEN_INTENT", "allow", check=False)
        shell("cmd", "notification", "set_dnd", "off", check=False)
        (OUT / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if len(checks) == 3 and all(c["pass"] for c in checks.values()) and "error" not in report else 1


if __name__ == "__main__":
    sys.exit(main())
