#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .local.env ]]; then source .local.env; fi
if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  export JAVA_HOME
fi
if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo 'Configura JAVA_HOME con un JDK 17 antes de compilar.' >&2
  exit 1
fi
python3 tools/prepare-signing.py
./gradlew assembleRelease lint "$@"
mkdir -p dist
release_version=$(python3 -c "import json; print(json.load(open('app/build/outputs/apk/release/output-metadata.json'))['elements'][0]['versionName'])")
release_apk="dist/TelefonoLite-${release_version}.apk"
cp app/build/outputs/apk/release/app-release.apk "$release_apk"
shasum -a 256 "$release_apk" > dist/SHA256SUMS.txt
wc -c "$release_apk"
