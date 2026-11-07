#!/usr/bin/env bash
# Captures the Play Store screenshots from a running emulator with the app installed
# (bundletool install-apks, or adb install build/Level.apk). Overwrites
# fastlane/metadata/android/en-US/images/{phone,sevenInch,tenInch}Screenshots/.
# The emulator's display is resized per form factor and restored afterwards; app data is cleared.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=me.saatvik.level
IMAGES=fastlane/metadata/android/en-US/images
THEMES=(Aurum Ambra Rosa Celeste Salvia Notte)

adb get-state >/dev/null

restore() {
    adb shell wm size reset
    adb shell wm density reset
    adb shell settings delete secure icon_blacklist >/dev/null
    adb shell cmd connectivity airplane-mode disable
    adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
}
trap restore EXIT

# Clean, constant status bar: fixed clock and battery, no network or system icons, no notifications.
# Airplane mode removes the satellite indicator, which ignores both demo mode and the icon list.
adb shell settings put global sysui_demo_allowed 1
adb shell settings put secure icon_blacklist wifi,mobile,airplane,ethernet,vpn,location,cast,hotspot
adb shell cmd connectivity airplane-mode enable
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo notifications -e visible false
keys=$(adb shell dumpsys notification --noredact | grep 'NotificationRecord(' | grep -o 'key=[^:]*' | cut -d= -f2- || true)
for key in $keys; do adb shell cmd notification snooze --for 3600000 "'$key'" >/dev/null; done

# Taps the centre of the first accessibility node whose description starts with $1.
tap() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 < <(adb shell cat /sdcard/ui.xml | grep -o "content-desc=\"$1[^\"]*\" [^>]*bounds=\"[^\"]*\"" \
        | head -1 | grep -o 'bounds="[^"]*"' | tr -c '0-9\n' ' ') || true
    [ -n "${y2:-}" ] || { echo "error: no control '$1' on screen" >&2; exit 1; }
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

# scene OUT THEME GRAVITY [CONTROL]: fresh start, theme, sensor reading, optional control, capture.
scene() {
    local out=$1 theme=$2 gravity=$3 control=${4:-}
    adb shell pm clear "$PKG" >/dev/null
    # Reading first, so the app's filter starts settled instead of easing in from the last pose.
    adb emu sensor set acceleration "$gravity" >/dev/null
    adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
    sleep 2
    local i
    for i in "${!THEMES[@]}"; do
        [ "${THEMES[$i]}" = "$theme" ] && break
        tap "Next theme"
        sleep 0.6
    done
    [ -z "$control" ] || tap "$control"
    sleep 2
    adb exec-out screencap -p > "$out"
    echo "$out"
}

capture() {
    local dir=$IMAGES/$1 size=$2 density=$3
    shift 3
    adb shell wm size "$size"
    adb shell wm density "$density"
    sleep 2
    rm -rf "$dir"
    mkdir -p "$dir"
    local n=1 spec
    for spec in "$@"; do
        # shellcheck disable=SC2086
        scene "$dir/$n.png" $spec
        n=$((n + 1))
    done
}

SURFACE_TILTED="0.70:0.95:9.739"   # 6.9° (X +4.1°, Y +5.6°)
SURFACE_LEVEL="0:0:9.81"
EDGE_TILTED="0.72:9.79:0.0"        # standing on its edge, 4.2° off

capture phoneScreenshots 1080x2160 420 \
    "Aurum $SURFACE_TILTED" \
    "Ambra $EDGE_TILTED" \
    "Celeste $SURFACE_LEVEL" \
    "Salvia $SURFACE_TILTED Calibrate" \
    "Rosa $SURFACE_TILTED Settings" \
    "Notte $SURFACE_TILTED"

capture sevenInchScreenshots 1200x1920 240 \
    "Aurum $SURFACE_TILTED" \
    "Ambra $EDGE_TILTED" \
    "Celeste $SURFACE_TILTED Settings"

capture tenInchScreenshots 2560x1600 320 \
    "Aurum $SURFACE_TILTED" \
    "Salvia $SURFACE_LEVEL" \
    "Rosa $SURFACE_TILTED Calibrate"
