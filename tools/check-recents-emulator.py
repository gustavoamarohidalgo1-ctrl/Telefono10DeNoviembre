#!/usr/bin/env python3
"""Exercise recent calls on a configured Android 36 emulator, never a phone.

Usage: python3 tools/check-recents-emulator.py --serial emulator-5554
Requires Teléfono Lite installed and holding ROLE_DIALER. Inserts synthetic call
log rows and identifies inserted IDs by querying before and after each insert, and deletes only those IDs. Writes report.json
to docs/resultados/recientes. No calls are placed.
"""
import argparse
import json
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "pe.gustavo.telefonolite"
URI = "content://call_log/calls"
RESULTS = Path(__file__).resolve().parent.parent / "docs/resultados/recientes"
PERMISSION = "android.permission.READ_CALL_LOG"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("--serial must match emulator-N")
    base = ["adb", "-s", args.serial]

    def adb(*parts, check=True):
        proc = subprocess.run(base + list(parts), capture_output=True, text=True, timeout=30)
        if check and proc.returncode:
            raise RuntimeError("adb failed: " + proc.stderr.strip())
        return proc.stdout

    def shell(*parts, check=True):
        return adb("shell", shlex.join(parts), check=check)

    if shell("getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Refusing device without ro.kernel.qemu=1")
    if shell("getprop", "ro.build.version.sdk").strip() != "36":
        raise RuntimeError("Requires Android API 36 emulator")
    role = shell("cmd", "role", "get-role-holders", "--user", "0", "android.app.role.DIALER").strip()
    if role != PACKAGE:
        raise RuntimeError("Teléfono Lite must already hold ROLE_DIALER: " + role)

    RESULTS.mkdir(parents=True, exist_ok=True)
    results = {"serial": args.serial, "checks": {}}
    checks = results["checks"]
    ids = []
    package_info = shell("dumpsys", "package", PACKAGE)
    initial_grant = bool(re.search(r"android\.permission\.READ_CALL_LOG: granted=true", package_info))
    stamp = int(time.time() * 1000)
    number = "5550188123"

    def matching_ids(number_value, date):
        where = ("date=" + str(date) if number_value == "5550188125"
                 else "number='" + number_value + "' AND date=" + str(date))
        output = shell("content", "query", "--uri", URI, "--projection", "_id", "--where", where)
        return set(re.findall(r"\b_id=(\d+)", output))

    def insert(number_value, kind, date, presentation):
        before = matching_ids(number_value, date)
        try:
            shell("content", "insert", "--uri", URI,
                  "--bind", "number:s:" + number_value,
                  "--bind", "type:i:" + str(kind),
                  "--bind", "date:l:" + str(date),
                  "--bind", "duration:l:45",
                  "--bind", "presentation:i:" + str(presentation))
        finally:
            added = matching_ids(number_value, date) - before
            ids.extend(sorted(added))
        if len(added) != 1:
            raise RuntimeError("Expected one inserted row, found " + repr(added))

    def nodes():
        for _ in range(3):
            shell("uiautomator", "dump", "/sdcard/telefono-recents-check.xml", check=False)
            xml = shell("cat", "/sdcard/telefono-recents-check.xml", check=False)
            try:
                return list(ET.fromstring(xml).iter("node"))
            except ET.ParseError:
                time.sleep(0.3)
        return []

    def wait_for(predicate, timeout=15):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            current = nodes()
            if predicate(current):
                return current
            time.sleep(0.4)
        return nodes()

    def find(current, value):
        return next ( (n for n in current if value in (n.get("text", ""), n.get("content-desc", ""))), None )

    def tap(node):
        if node is None:
            raise RuntimeError("Expected UI element missing")
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not bounds:
            raise RuntimeError("Invalid UI bounds")
        x1, y1, x2, y2 = map(int, bounds.groups())
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def restart():
        shell("am", "force-stop", PACKAGE)
        shell("am", "start", "-n", PACKAGE + "/.MainActivity")
        current = wait_for(lambda ns: find(ns, "Recientes") is not None)
        tap(find(current, "Recientes"))

    def call_state():
        output = shell("dumpsys", "telephony.registry", check=False)
        return re.findall(r"mCallState\s*=\s*(\d+)", output)

    try:
        insert(number, 2, stamp, 1)
        insert("5550188124", 1, stamp - 1000, 1)
        insert("5550188125", 3, stamp + 1000, 2)
        shell("pm", "revoke", PACKAGE, PERMISSION)
        restart()
        current = wait_for(lambda ns: find(ns, "Permitir historial") is not None)
        text = " ".join(n.get("text", "") + " " + n.get("content-desc", "") for n in current)
        checks["permission_denied"] = {"pass": find(current, "Permitir historial") is not None and number not in text}
        shell("pm", "grant", PACKAGE, PERMISSION)
        restart()
        current = wait_for(lambda ns: any(number in n.get("content-desc", "") for n in ns))
        private = next((n for n in current if n.get("content-desc", "").startswith("Número privado,")), None)
        row = next((n for n in current if n.get("content-desc", "").startswith(number + ",")), None)
        checks["entries"] = {"pass": row is not None and private is not None}
        before = call_state()
        if not before or any(state != "0" for state in before):
            raise RuntimeError("Cannot establish idle call state: " + repr(before))
        tap(private)
        current = nodes()
        still_private = any(n.get("content-desc", "").startswith("Número privado,") for n in current)
        checks["private_not_clickable"] = {"pass": still_private and call_state() == before}
        tap(next((n for n in current if n.get("content-desc", "").startswith(number + ",")), None))
        current = wait_for(lambda ns: any(n.get("text", "") == number for n in ns))
        after = call_state()
        checks["opens_dialer_without_call"] = {"pass": any(n.get("text", "") == number for n in current) and after == before,
                                               "call_state_before": before, "call_state_after": after}
    except Exception as exc:
        results["error"] = str(exc)
    finally:
        errors = []
        for row_id in ids:
            try:
                shell("content", "delete", "--uri", URI, "--where", "_id=" + row_id)
            except Exception as exc:
                errors.append(str(exc))
        try:
            shell("pm", "grant" if initial_grant else "revoke", PACKAGE, PERMISSION)
        except Exception as exc:
            errors.append(str(exc))
        checks["cleanup"] = {"pass": not errors, "inserted_ids": ids, "errors": errors}
        (RESULTS / "report.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0 if "error" not in results and all(c["pass"] for c in checks.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
