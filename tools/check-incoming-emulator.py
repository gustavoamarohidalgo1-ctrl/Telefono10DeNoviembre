#!/usr/bin/env python3
"""Verify incoming-call affordances using synthetic calls on an emulator only."""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = 'pe.gustavo.telefonolite'
CHANNEL = 'calls_silent_v1'
OUT = Path(__file__).resolve().parents[1] / 'docs/resultados/0.2.1/incoming'
NUMBERS = ('5550120', '5550121', '5550122', '5550123')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    serial = parser.parse_args().serial
    if not re.fullmatch(r'emulator-\d+', serial):
        parser.error('Only emulator-N serials are allowed')

    def adb(*args, check=True, binary=False):
        proc = subprocess.run(['adb', '-s', serial, *args], capture_output=True, timeout=30)
        if check and proc.returncode:
            raise RuntimeError(f'adb {args!r}: {proc.stderr.decode(errors="replace")}')
        return proc.stdout if binary else proc.stdout.decode(errors='replace').strip()

    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise SystemExit('Rejected: device does not report ro.kernel.qemu=1')
    if adb('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.DIALER') != PACKAGE:
        raise SystemExit('Configure Teléfono Lite as the default dialer first')
    original_zen = adb('shell', 'settings', 'get', 'global', 'zen_mode')
    modes = {'0': 'off', '1': 'priority', '2': 'none', '3': 'alarms'}
    if original_zen not in modes:
        raise SystemExit('Cannot restore the original DND mode: ' + original_zen)
    OUT.mkdir(parents=True, exist_ok=True)
    report = {'serial': serial, 'checks': {}, 'note': 'Synthetic emulator calls; no acoustic verification.'}

    def shell(*args, check=True):
        return adb('shell', *args, check=check)

    def ui():
        raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty', check=False)
        start, end = raw.find('<?xml'), raw.rfind('</hierarchy>')
        return raw[start:end + len('</hierarchy>')] if start >= 0 and end >= 0 else ''

    def wait(predicate, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = predicate()
            if result:
                return result
            time.sleep(.3)
        raise AssertionError('Timed out waiting for expected state')

    def has(xml, label, package=None):
        if not xml:
            return False
        return any((n.get('text') == label or n.get('content-desc') == label)
                   and (package is None or n.get('package') == package)
                   for n in ET.fromstring(xml).iter('node'))

    def tap(label):
        def position():
            data = ui()
            if not data:
                return None
            for node in ET.fromstring(data).iter('node'):
                if node.get('text') == label and node.get('package') == PACKAGE:
                    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
                    if match:
                        x1, y1, x2, y2 = map(int, match.groups())
                        if x2 > x1 and y2 > y1:
                            return (str((x1 + x2) // 2), str((y1 + y2) // 2))
            return None
        shell('input', 'tap', *wait(position))

    def capture(name):
        time.sleep(2)
        (OUT / (name + '.xml')).write_text(ui(), encoding='utf-8')
        (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

    def call(number):
        result = adb('emu', 'gsm', 'call', number)
        if 'OK' not in result:
            raise RuntimeError('Synthetic call failed: ' + result)

    def cancel(number):
        adb('emu', 'gsm', 'cancel', number, check=False)
        time.sleep(1)

    def launch_main():
        shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        shell('wm', 'dismiss-keyguard', check=False)
        shell('am', 'start', '-n', PACKAGE + '/.MainActivity')
        wait(lambda: PACKAGE in ui())

    def check(name, action):
        print('Probando: ' + name, flush=True)
        try:
            action()
            report['checks'][name] = {'pass': True}
        except Exception as exc:
            report['checks'][name] = {'pass': False, 'error': str(exc)}
            try:
                capture(name + '-failure')
            except Exception:
                pass

    def card_answer():
        shell('cmd', 'notification', 'set_dnd', 'none')
        wait(lambda: shell('settings', 'get', 'global', 'zen_mode') == '2')
        launch_main()
        call(NUMBERS[0])
        wait(lambda: all(has(ui(), label, PACKAGE) for label in ('Llamada entrante', 'Responder', 'Rechazar', 'Recientes', 'Teclado')))
        assert re.search(r'mResumedActivity:.*MainActivity|topResumedActivity=.*MainActivity', shell('dumpsys', 'activity', 'activities')), 'MainActivity is not resumed'
        capture('main-ringing-dnd')
        tap('Responder')
        wait(lambda: has(ui(), 'Colgar', PACKAGE))
        wait(lambda: 'InCallActivity' in shell('dumpsys', 'activity', 'activities'))
        wait(lambda: bool(re.search(r'\bACTIVE\b', shell('dumpsys', 'telecom', check=False))))
        capture('answered')
        launch_main()
        wait(lambda: has(ui(), 'Volver a la llamada', PACKAGE))
        capture('main-active')
        cancel(NUMBERS[0])

    def card_reject():
        launch_main()
        call(NUMBERS[1])
        wait(lambda: all(has(ui(), label, PACKAGE) for label in ('Llamada entrante', 'Responder', 'Rechazar', 'Recientes', 'Teclado')))
        tap('Rechazar')
        wait(lambda: not has(ui(), 'Rechazar', PACKAGE))
        assert not has(ui(), 'Volver a la llamada', PACKAGE), 'Rejected call remains on card'
        capture('rejected')
        cancel(NUMBERS[1])

    def heads_up():
        shell('cmd', 'notification', 'set_dnd', 'off')
        wait(lambda: shell('settings', 'get', 'global', 'zen_mode') == '0')
        shell('input', 'keyevent', 'KEYCODE_HOME')
        call(NUMBERS[2])
        wait(lambda: shell('settings', 'get', 'global', 'zen_mode') == '0')
        # UI Automator on this emulator omits the SystemUI heads-up window. Capture
        # the real screen and assert SystemUI's pinned entry; inspect image visually.
        time.sleep(2)
        (OUT / 'home-heads-up.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        state = shell('dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService')
        (OUT / 'home-heads-up-systemui.txt').write_text(state, encoding='utf-8')
        assert re.search(r'pe\.gustavo\.telefonolite.*section=.*HeadsUp', state), 'No app entry in HeadsUp section'
        assert 'mHeadsUpPinnedMode=true' in state, 'Heads-up is not pinned'
        cancel(NUMBERS[2])

    def locked():
        shell('input', 'keyevent', 'KEYCODE_HOME')
        shell('input', 'keyevent', 'KEYCODE_SLEEP')
        call(NUMBERS[3])
        wait(lambda: has(ui(), 'Responder', PACKAGE) and has(ui(), 'Rechazar', PACKAGE))
        wait(lambda: 'InCallActivity' in shell('dumpsys', 'activity', 'activities'))
        capture('locked-incoming')
        cancel(NUMBERS[3])

    def channel():
        dump = shell('dumpsys', 'notification', '--noredact')
        lines = [line for line in dump.splitlines() if CHANNEL in line]
        (OUT / 'channel.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')
        text = '\n'.join(lines)
        assert re.search(r'(?:mSound|sound)\s*[=:]\s*null\b', text, re.I), 'Channel sound is not null'
        assert re.search(r'(?:mVibrationEnabled|vibrationEnabled|vibrate)\s*[=:]\s*false\b', text, re.I), 'Channel vibration is not disabled'
        assert re.search(r'(?:mBypassDnd|bypassDnd)\s*[=:]\s*false\b', text, re.I), 'Channel bypasses DND'
        assert re.search(r'(?:mImportance|importance)\s*[=:]\s*4\b', text, re.I), 'Channel importance is not HIGH'

    try:
        for name, action in (('main_answer_dnd_and_active', card_answer), ('main_reject_dnd', card_reject),
                             ('home_heads_up', heads_up), ('locked_incoming', locked), ('silent_channel', channel)):
            check(name, action)
            for number in NUMBERS:
                adb('emu', 'gsm', 'cancel', number, check=False)
    finally:
        shell('cmd', 'notification', 'set_dnd', modes[original_zen], check=False)
        (OUT / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if all(item['pass'] for item in report['checks'].values()) else 1


if __name__ == '__main__':
    sys.exit(main())
