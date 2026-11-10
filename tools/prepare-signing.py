#!/usr/bin/env python3
"""Create one private, local update-signing key; never replace an existing key."""
import os
from pathlib import Path
import secrets
import subprocess

root = Path(__file__).resolve().parents[1]
directory = root / '.signing'
directory.mkdir(mode=0o700, exist_ok=True)
keystore = directory / 'telefonolite.p12'
properties = directory / 'signing.properties'
if keystore.exists() and properties.exists():
    raise SystemExit(0)
if keystore.exists() or properties.exists():
    raise SystemExit('Firma local incompleta: recupera los archivos de .signing; no se reemplazó ninguna clave.')
password = secrets.token_hex(24)
env = os.environ.copy()
env['TELEFONOLITE_KEY_PASSWORD'] = password
keytool = Path(os.environ['JAVA_HOME']) / 'bin' / 'keytool'
subprocess.run([
    str(keytool), '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12',
    '-storepass:env', 'TELEFONOLITE_KEY_PASSWORD', '-keypass:env', 'TELEFONOLITE_KEY_PASSWORD',
    '-alias', 'telefonolite', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
    '-dname', 'CN=Teléfono Lite, OU=Desarrollo local', '-noprompt',
], check=True, env=env)
keystore.chmod(0o600)
fd = os.open(properties, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write('storePassword=' + password + '\n')
print('Firma local creada. Conserva .signing/ para futuras actualizaciones; no la compartas.')
