#!/usr/bin/env python3
"""Exercise Teléfono Lite using synthetic calls on an Android emulator only."""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'pe.gustavo.telefonolite'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Emulator serial, e.g. emulator-5554')
    args = parser.parse_args()
    serial = args.serial
    if not re.fullmatch(r'emulator-\d+', serial):
        parser.error('Only emulator-NNNN serials are allowed')

    def adb(*args, check=True, timeout=20, binary=False):
        proc = subprocess.run(['adb', '-s', serial, *args], stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=timeout)
        if check and proc.returncode:
            raise RuntimeError('adb ' + ' '.join(args) + ': ' + proc.stderr.decode(errors='replace'))
        return proc.stdout if binary else proc.stdout.decode(errors='replace').strip()

    # Verify before any device mutation, including orientation or synthetic calls.
    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise SystemExit('Rejected: device does not report ro.kernel.qemu=1')
    if adb('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.DIALER') != PACKAGE:
        raise SystemExit('Configure Teléfono Lite as the default dialer in the emulator first')
    result_dir = ROOT / 'docs' / 'resultados'
    capture_dir = ROOT / 'docs' / 'capturas'
    result_dir.mkdir(parents=True, exist_ok=True)
    capture_dir.mkdir(parents=True, exist_ok=True)
    results = []

    def xml():
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty', timeout=30)
            start, end = raw.find('<?xml'), raw.rfind('</hierarchy>')
            if start >= 0 and end >= 0:
                return raw[start:end + len('</hierarchy>')]
            time.sleep(.4)
        raise RuntimeError('uiautomator returned no XML: ' + raw[:300])

    def nodes():
        return list(ET.fromstring(xml()).iter('node'))

    def find(label, description=False):
        for node in nodes():
            candidate = node.get('content-desc', '') if description else node.get('text', '')
            if (label in candidate if description else candidate == label):
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    if x2 > x1 and y2 > y1:
                        return ((x1 + x2) // 2, (y1 + y2) // 2)
        return None

    def wait_for(label, description=False, timeout=15):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            point = find(label, description)
            if point:
                return point
            time.sleep(.4)
        raise AssertionError('No aparece: ' + label)

    def tap(label, description=False, long=False):
        x, y = wait_for(label, description)
        if long:
            adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(y), '1000')
        else:
            adb('shell', 'input', 'tap', str(x), str(y))

    def screenshot(name, directory=capture_dir):
        (directory / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))

    def failure_artifacts(name):
        try:
            (result_dir / (name + '.xml')).write_text(xml(), encoding='utf-8')
        except Exception:
            pass
        try:
            screenshot(name, result_dir)
        except Exception:
            pass

    def run(name, action):
        try:
            print('Probando: ' + name, flush=True)
            action()
            results.append({'test': name, 'status': 'pass'})
        except Exception as exc:
            print('Fallo: ' + name + ': ' + str(exc), flush=True)
            failure_artifacts(name)
            results.append({'test': name, 'status': 'fail', 'error': str(exc)})

    def dial(number):
        adb('shell', 'am', 'start', '-a', 'android.intent.action.DIAL', '-d', 'tel:' + number, PACKAGE)
        wait_for(number)

    def call_ui():
        # Opening the shade also prevents heads-up cards from intercepting app taps.
        adb('shell', 'cmd', 'statusbar', 'expand-notifications')
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            for node in nodes():
                value = node.get('text', '')
                if value == 'Llamada entrante' or value in ('5550102', '5550104'):
                    bounds = re.findall(r'\d+', node.get('bounds', ''))
                    if len(bounds) == 4:
                        x1, y1, x2, y2 = map(int, bounds)
                        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                        return wait_for('Responder')
            time.sleep(.3)
        raise AssertionError('No aparece notificación entrante de Teléfono Lite')

    def dialer_test():
        dial('5550101')
        if find('Colgar') or find('Responder'):
            raise AssertionError('ACTION_DIAL abrió una llamada')
        tap('Borrar último carácter', description=True)
        wait_for('555010')
        tap('0', description=True, long=True)
        wait_for('555010+')
        tap('Borrar último carácter', description=True, long=True)
        tap('2', description=True)
        wait_for('2')
        screenshot('marcador')

    def rotation_test():
        dial('5550101')
        adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
        try:
            wait_for('5550101')
        finally:
            adb('shell', 'settings', 'put', 'system', 'user_rotation', '0', check=False)
            adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '1', check=False)

    def incoming_test():
        adb('emu', 'gsm', 'call', '5550102')
        call_ui()
        screenshot('llamada-entrante')
        tap('Responder')
        wait_for('Micrófono\nActivado')
        screenshot('llamada-activa')
        tap('Micrófono\nActivado')
        wait_for('Micrófono\nSilenciado')
        tap('Micrófono\nSilenciado')
        wait_for('Micrófono\nActivado')
        if find('Altavoz\nDesactivado'):
            tap('Altavoz\nDesactivado')
            wait_for('Altavoz\nActivado')
            tap('Altavoz\nActivado')
        tap('Teclado\nCerrado')
        wait_for('Teclado\nAbierto')
        wait_for('1', description=True)
        wait_for('Colgar')
        # Allow the footer to finish drawing after the keypad layout changes.
        time.sleep(2)
        screenshot('llamada-teclado')
        tap('1', description=True)
        wait_for('Colgar')
        tap('Teclado\nAbierto')
        tap('Colgar')
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            notifications = adb('shell', 'dumpsys', 'notification', '--noredact', check=False)
            if not re.search(r'NotificationRecord\([^\n]*' + re.escape(PACKAGE), notifications):
                return
            time.sleep(.5)
        raise AssertionError('Persisten notificaciones de la aplicación después de colgar')

    def reject_test():
        adb('emu', 'gsm', 'call', '5550104')
        call_ui()
        tap('Rechazar')
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if not find('Rechazar'):
                return
            time.sleep(.4)
        raise AssertionError('La llamada rechazada sigue visible')

    def outgoing_test():
        dial('5550103')
        tap('Llamar')
        wait_for('Colgar')
        adb('emu', 'gsm', 'accept', '5550103')
        wait_for('Micrófono\nActivado')
        tap('Colgar')

    try:
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        run('marcador', dialer_test)
        run('rotacion', rotation_test)
        run('entrante', incoming_test)
        adb('emu', 'gsm', 'cancel', '5550102', check=False)
        run('rechazar', reject_test)
        adb('emu', 'gsm', 'cancel', '5550104', check=False)
        run('saliente', outgoing_test)
    finally:
        for number in ('5550102', '5550103', '5550104'):
            adb('emu', 'gsm', 'cancel', number, check=False)
        # New emulator modems may retain outgoing calls after gsm cancel.
        for _ in range(3):
            adb('shell', 'input', 'keyevent', 'KEYCODE_ENDCALL', check=False)
    report = {'serial': serial, 'results': results}
    (result_dir / 'smoke-emulator.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if all(item['status'] == 'pass' for item in results) else 1


if __name__ == '__main__':
    sys.exit(main())
