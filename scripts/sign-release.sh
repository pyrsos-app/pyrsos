#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Signs a release APK on this computer with the project key, then prints the certificate
# fingerprint people check it against. The key never leaves this machine; apksigner asks for its
# password. Signing is v2 and v3 only: Pyrsos needs Android 10, which reads both.
#
#   ./gradlew :app:assembleRelease && scripts/sign-release.sh
#   scripts/sign-release.sh some-unsigned.apk out.apk     (an APK built elsewhere, e.g. by CI)
#
# PYRSOS_KEYSTORE overrides where the key is (default ~/pyrsos-release.jks).
set -eu

KEYSTORE="${PYRSOS_KEYSTORE:-$HOME/pyrsos-release.jks}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
IN="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
OUT="${2:-build/pyrsos-$VERSION.apk}"

[ -f "$KEYSTORE" ] || { echo "No key at $KEYSTORE" >&2; exit 1; }
[ -f "$IN" ] || { echo "No APK at $IN: build it first" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"

"$TOOLS/zipalign" -f -p 4 "$IN" "$OUT.aligned"
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias pyrsos \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT" "$OUT.aligned"
rm -f "$OUT.aligned" "$OUT.idsig"

echo
"$TOOLS/apksigner" verify --print-certs "$OUT" | grep "SHA-256"
echo "Signed: $OUT"
