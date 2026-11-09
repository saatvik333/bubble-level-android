#!/usr/bin/env bash
# Gradle-free build: aapt2 -> javac -> R8 -> bundletool.
#   build/Level.aab          Google Play upload (signed with the upload key, R8 mapping embedded)
#   build/Level.apk          universal APK built from that bundle, for sideloading
#   build/mapping.txt        R8 mapping for retracing stack traces from either
# Needs: Android SDK (build-tools, platform, cmdline-tools), JDK 17+, bundletool, python3-pillow, zip.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME to the Android SDK}}
BUILD_TOOLS=${BUILD_TOOLS:-36.0.0}
PLATFORM=${PLATFORM:-android-36}
MIN_SDK=24
TARGET_SDK=36
VERSION_CODE=${VERSION_CODE:-2}
VERSION_NAME=${VERSION_NAME:-1.0}
KS=${KEYSTORE:-${XDG_DATA_HOME:-$HOME/.local/share}/bubble-level-android/upload.jks}
KS_ALIAS=${KEYSTORE_ALIAS:-upload}
OUT=build

BT=$SDK/build-tools/$BUILD_TOOLS
JAR=$SDK/platforms/$PLATFORM/android.jar
R8_JAR=$SDK/cmdline-tools/latest/lib/r8.jar
BUNDLETOOL=${BUNDLETOOL:-bundletool}
for f in "$BT/aapt2" "$JAR" "$R8_JAR"; do
    [ -e "$f" ] || { echo "error: $f missing; install it with sdkmanager" >&2; exit 1; }
done
command -v "$BUNDLETOOL" >/dev/null || { echo "error: bundletool not on PATH (or set BUNDLETOOL)" >&2; exit 1; }

# Upload key: Play App Signing holds the app signing key; this key only authenticates uploads.
# The password comes from KEYSTORE_PASS or, failing that, the desktop keyring.
if [ -z "${KEYSTORE_PASS:-}" ] && command -v secret-tool >/dev/null; then
    KEYSTORE_PASS=$(secret-tool lookup service bubble-level-android key upload-keystore || true)
fi
: "${KEYSTORE_PASS:?set KEYSTORE_PASS (or store it: secret-tool store --label=bubble-level-android service bubble-level-android key upload-keystore)}"
export KEYSTORE_PASS
if [ ! -f "$KS" ]; then
    if [ "${NEW_KEYSTORE:-0}" != 1 ]; then
        echo "error: keystore $KS not found." >&2
        echo "Restore it, or run with NEW_KEYSTORE=1 to create a new upload key." >&2
        exit 1
    fi
    mkdir -p -m 700 "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$KS_ALIAS" -keyalg RSA -keysize 4096 \
        -validity 10000 -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS \
        -dname "CN=Saatvik Sharma, O=Level" 2>/dev/null
    chmod 600 "$KS"
    echo "created $KS - back it up"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/base/manifest" "$OUT/base/dex"

python3 tools/make_icons.py res

# Resources, compiled once in protobuf form: bundletool turns them into the APKs Play serves.
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link --proto-format -I "$JAR" \
    --manifest AndroidManifest.xml \
    -A assets \
    --java "$OUT/gen" \
    --proguard "$OUT/aapt-rules.pro" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o "$OUT/proto.zip" "$OUT/res.zip"

javac -nowarn -Xlint:-options -XDsuppressNotes -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$JAR" -classpath "$BT/core-lambda-stubs.jar" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')
(cd "$OUT/classes" && jar --create --file ../classes.jar .)

java -cp "$R8_JAR" com.android.tools.r8.R8 --release --min-api "$MIN_SDK" \
    --lib "$JAR" --pg-conf proguard-rules.pro --pg-conf "$OUT/aapt-rules.pro" \
    --pg-map-output "$OUT/mapping.txt" --output "$OUT/dex" "$OUT/classes.jar"

# Bundle module layout: manifest/, dex/, res/, assets/, resources.pb.
(cd "$OUT/base" && unzip -q ../proto.zip && mv AndroidManifest.xml manifest/)
cp "$OUT/dex/classes.dex" "$OUT/base/dex/"
(cd "$OUT/base" && zip -q -X -r ../base.zip .)

"$BUNDLETOOL" build-bundle --modules="$OUT/base.zip" --output="$OUT/Level.aab" \
    --metadata-file=com.android.tools.build.obfuscation/proguard.map:"$OUT/mapping.txt"
# Upload certificates are self-signed by design; jarsigner's warning about that is noise.
jarsigner -keystore "$KS" -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS \
    -sigalg SHA256withRSA -digestalg SHA-256 "$OUT/Level.aab" "$KS_ALIAS" >/dev/null
jarsigner -verify "$OUT/Level.aab" | grep '^jar verified' >/dev/null \
    || { echo "error: $OUT/Level.aab signature does not verify" >&2; exit 1; }
"$BUNDLETOOL" validate --bundle="$OUT/Level.aab" >/dev/null

"$BUNDLETOOL" build-apks --bundle="$OUT/Level.aab" --output="$OUT/Level.apks" --mode=universal \
    --ks="$KS" --ks-key-alias="$KS_ALIAS" \
    --ks-pass=file:<(printf %s "$KEYSTORE_PASS") --key-pass=file:<(printf %s "$KEYSTORE_PASS")
unzip -q -p "$OUT/Level.apks" universal.apk > "$OUT/Level.apk"
java --enable-native-access=ALL-UNNAMED -jar "$BT/lib/apksigner.jar" verify "$OUT/Level.apk"

echo "built $OUT/Level.aab ($(stat -c %s "$OUT/Level.aab") bytes), $OUT/Level.apk ($(stat -c %s "$OUT/Level.apk") bytes)"
