#!/usr/bin/env python3
"""Integration check using synthetic calls on an already configured emulator only.

Usage: python3 tools/check-blocker-emulator.py --serial emulator-5554
Requires the real blocker APK, Teléfono Lite as default dialer, a synthetic contact
Prueba Bloqueador / 5550191, and blocker configured for blocking all calls.
Writes JSON and screenshots to docs/resultados/bloqueador. Never use on a phone.
"""
import argparse
import json
import shlex
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

DIALER = "pe.gustavo.telefonolite"
BLOCKER = "com.gustavo.silenciollamadas"
RESULTS = Path(__file__).resolve().parent.parent / "docs/resultados/bloqueador"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("--serial must match emulator-N")
    base = ["adb", "-s", args.serial]

    def adb(*args, check=True, raw=False):
        p = subprocess.run(base + list(args), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if check and p.returncode:
            raise RuntimeError("adb failed: " + p.stderr.decode(errors="replace").strip())
        return p.stdout if raw else p.stdout.decode(errors="replace")

    def shell(*args, check=True):
        return adb("shell", shlex.join(args), check=check)

    if shell("getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Refusing device without ro.kernel.qemu=1")
    RESULTS.mkdir(parents=True, exist_ok=True)
    results = {"serial": args.serial, "synthetic_contact": "5550191", "checks": {}}
    checks = results["checks"]

    def role(name):
        return shell("cmd", "role", "get-role-holders", "--user", "0", "android.app.role." + name).strip()

    def roles():
        return {"DIALER": role("DIALER"), "CALL_SCREENING": role("CALL_SCREENING")}

    def require_roles(label):
        value = roles()
        ok = value == {"DIALER": DIALER, "CALL_SCREENING": BLOCKER}
        checks[label] = {"pass": ok, "holders": value}
        if not ok:
            raise RuntimeError("Unexpected role holders: " + repr(value))

    def wait_for(func, predicate, timeout=15):
        until = time.monotonic() + timeout
        last = None
        while time.monotonic() < until:
            last = func()
            if predicate(last):
                return last
            time.sleep(0.5)
        return last

    def hierarchy():
        shell("uiautomator", "dump", "/sdcard/telefono-blocker-check.xml", check=False)
        xml = shell("cat", "/sdcard/telefono-blocker-check.xml", check=False)
        try:
            root = ET.fromstring(xml)
            return list(root.iter("node"))
        except ET.ParseError:
            return []

    def node(resource_id):
        return next((n for n in hierarchy() if n.get("resource-id") == resource_id), None)

    def tap_node(n):
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            raise RuntimeError("UI node has no tap bounds")
        x1, y1, x2, y2 = map(int, m.groups())
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def blocker_ui():
        shell("am", "start", "-n", BLOCKER + "/.MainActivity")
        for _ in range(6):
            n = node(BLOCKER + ":id/masterSwitch")
            if n is not None and n.get("bounds") != "[0,0][0,0]":
                return
            shell("input", "swipe", "500", "2000", "500", "700", "400")
        raise RuntimeError("Blocker master switch not visible")

    def set_master(value):
        blocker_ui()
        n = node(BLOCKER + ":id/masterSwitch")
        if n is None:
            raise RuntimeError("Blocker master switch missing")
        if (n.get("checked") == "true") != value:
            tap_node(n)
        n = wait_for(lambda: node(BLOCKER + ":id/masterSwitch"),
                     lambda x: x is not None and (x.get("checked") == "true") == value)
        if n is None or (n.get("checked") == "true") != value:
            raise RuntimeError("Could not set blocker master switch")

    def require_mode_all():
        blocker_ui()
        for _ in range(4):
            n = node(BLOCKER + ":id/modeAll")
            if n is not None:
                if n.get("checked") != "true":
                    raise RuntimeError("Block-all mode must already be selected")
                return
            shell("input", "swipe", "500", "1800", "500", "900", "400")
        raise RuntimeError("Block-all mode not visible")

    def screenshot(filename):
        (RESULTS / filename).write_bytes(adb("exec-out", "screencap", "-p", raw=True))

    def cancel(number):
        adb("emu", "gsm", "cancel", number, check=False)

    def call(number):
        output = adb("emu", "gsm", "call", number)
        if "OK" not in output:
            raise RuntimeError("Synthetic call failed: " + output)

    def records(number):
        return shell("content", "query", "--uri", "content://call_log/calls", "--projection", "number:type:date",
                     "--where", "number=" + number, "--sort", "date DESC", check=False)

    def current_activity():
        return shell("dumpsys", "activity", "activities", check=False)

    def package_notifications():
        output = shell("dumpsys", "notification", "--noredact", check=False)
        # Restrict match to active notification record headers, excluding channel definitions.
        return [line for line in output.splitlines() if "NotificationRecord(" in line and "|" + DIALER + "|" in line]

    def is_call_screen_resumed():
        output = current_activity()
        return any("InCallActivity" in line and ("mResumedActivity" in line or "topResumedActivity" in line or "ResumedActivity" in line) for line in output.splitlines())

    def ui_text():
        return " ".join((n.get("text", "") + " " + n.get("content-desc", "")) for n in hierarchy())

    def check_blocked(number):
        previous = records(number)
        shell("input", "keyevent", "KEYCODE_HOME")
        call(number)
        latest = wait_for(lambda: records(number), lambda x: x != previous and bool(re.search(r"\btype=6\b", x)), 20)
        # Check most recent row, not an older blocked call.
        first = next((line for line in latest.splitlines() if line.startswith("Row:")), "")
        blocked = bool(re.search(r"\btype=6\b", first)) and latest != previous
        time.sleep(1)
        foreground = is_call_screen_resumed()
        notifications = package_notifications()
        key = "blocked_contact" if number == "5550191" else "blocked_unknown"
        checks[key] = {"pass": blocked and not foreground and not notifications,
                       "new_blocked_log_entry": blocked, "incall_resumed": foreground,
                       "dialer_notification_count": len(notifications)}
        cancel(number)
        time.sleep(1)

    try:
        require_roles("roles_before")
        require_mode_all()
        n = node(BLOCKER + ":id/masterSwitch")
        if n is None or n.get("checked") != "true":
            raise RuntimeError("Blocker must already be enabled")
        check_blocked("5550191")
        check_blocked("5550192")
        shell("am", "start", "-n", DIALER + "/.MainActivity")
        text = wait_for(ui_text, lambda x: "Bloqueada" in x, 10)
        # Some versions show the Recientes tab rather than opening it by default.
        if "Bloqueada" not in text:
            n = next((n for n in hierarchy() if n.get("text") == "Recientes" or n.get("content-desc") == "Recientes"), None)
            if n is not None:
                tap_node(n)
                text = wait_for(ui_text, lambda x: "Bloqueada" in x, 10)
        screenshot("recientes-bloqueadas.png")
        checks["recents_blocked"] = {"pass": "Bloqueada" in text, "screenshot": "recientes-bloqueadas.png"}
        set_master(False)
        shell("input", "keyevent", "KEYCODE_HOME")
        call("5550191")
        notes = wait_for(package_notifications, bool, 15)
        shell("cmd", "statusbar", "expand-notifications")
        ns = wait_for(hierarchy, lambda nodes: any("Prueba Bloqueador" in n.get("text", "") for n in nodes))
        candidate = next((n for n in ns if "Prueba Bloqueador" in n.get("text", "")), None)
        if candidate is not None:
            tap_node(candidate)
        text = wait_for(ui_text, lambda x: "Responder" in x and "Prueba Bloqueador" in x, 10)
        screenshot("permitida-contacto.png")
        checks["allowed_contact"] = {"pass": bool(notes) and "Responder" in text and "Prueba Bloqueador" in text,
                                     "notification": bool(notes), "answer_visible": "Responder" in text,
                                     "contact_name_visible": "Prueba Bloqueador" in text,
                                     "screenshot": "permitida-contacto.png"}
        reject = next((n for n in hierarchy() if n.get("text") == "Rechazar"), None)
        if reject is not None:
            tap_node(reject)
        else:
            cancel("5550191")
        require_roles("roles_after")
    except Exception as exc:
        results["error"] = str(exc)
    finally:
        for number in ("5550191", "5550192"):
            try:
                cancel(number)
            except Exception:
                pass
        try:
            set_master(True)
            require_mode_all()
            checks["restored"] = {"pass": True}
            require_roles("roles_restored")
        except Exception as exc:
            checks["restored"] = {"pass": False, "error": str(exc)}
        (RESULTS / "report.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0 if "error" not in results and all(v["pass"] for v in checks.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
