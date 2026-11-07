#!/usr/bin/env bash
# Gradle-free build: aapt2 -> javac -> dx -> zipalign -> apksigner.
# Toolchain (Debian/Ubuntu): apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
BT=$SDK/build-tools/debian
JAR=$SDK/platforms/android-23/android.jar
VERSION_CODE=${VERSION_CODE:-2}
VERSION_NAME=${VERSION_NAME:-1.1}
KS=${KEYSTORE:-keystore/level.jks}
OUT=build

# Signing: the key must stay the same for every build that should install over a previous one.
# It is never generated silently; NEW_KEYSTORE=1 creates one on purpose.
: "${KEYSTORE_PASS:?set KEYSTORE_PASS to the keystore password}"
if [ ! -f "$KS" ]; then
    if [ "${NEW_KEYSTORE:-0}" != 1 ]; then
        echo "error: keystore $KS not found." >&2
        echo "Restore it, or run with NEW_KEYSTORE=1 to create a new key (a new key cannot update installs signed with the old one)." >&2
        exit 1
    fi
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias level -keyalg RSA -keysize 3072 \
        -validity 10000 -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS -dname "CN=Level" 2>/dev/null
    echo "created $KS - back it up; it signs every future update"
fi

rm -rf "$OUT"
mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/classes" "$OUT/dex"

python3 tools/make_icons.py res

"$BT/aapt2" compile --dir res -o "$OUT/flat/res.zip"
"$BT/aapt2" link -I "$JAR" \
    --manifest AndroidManifest.xml \
    -A assets \
    --java "$OUT/gen" \
    --min-sdk-version 23 --target-sdk-version 36 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o "$OUT/res.apk" "$OUT/flat/res.zip"

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

"$BT/dx" --dex --min-sdk-version=23 --output="$OUT/dex/classes.dex" "$OUT/classes"

cp "$OUT/res.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex)
zipalign -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

apksigner sign --ks "$KS" --ks-pass env:KEYSTORE_PASS --ks-key-alias level \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/Level.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/Level.apk"
echo "built $OUT/Level.apk ($(stat -c %s "$OUT/Level.apk") bytes)"
