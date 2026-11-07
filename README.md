# Level

Bubble level for Android in the Dustland "Clean One" visual language: flat colour, hairlines,
single accent, circle motif, centred column, named themes switched from the header.

## Build

Gradle-free. Debian/Ubuntu toolchain:

    sudo apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner python3-pil
    KEYSTORE_PASS=... ./build.sh                   # -> build/Level.apk (v1+v2+v3, minSdk 23, targetSdk 36)
    KEYSTORE_PASS=... NEW_KEYSTORE=1 ./build.sh    # first build only: creates keystore/level.jks

The keystore is not in the repository. Every build that should install over an earlier one must be
signed with the same key, so back it up. `VERSION_CODE` / `VERSION_NAME` override the version.

Google Play needs an Android App Bundle (.aab), which this script does not produce yet
(it needs `bundletool`; aapt2 here already supports `--proto-format`).

## Layout

    src/app/level/bubble/
      LevelEngine.java   sensor math, adaptive filter, mode hysteresis, two-step reversal calibration (no Android deps)
      Renderer.java      all drawing, android.graphics only
      LevelView.java     animation (spring bubble, theme/page/mode crossfades), input, haptics
      MainActivity.java  sensors, window/insets, Material You palette on 12+
      Palette.java       theme tokens: bg, fg, dim, line, accent, onAccent
    tools/preview/       AWT-backed android.graphics stubs: renders Renderer on the JVM; EngineTest

    javac -d /tmp/pv $(find tools/preview -name '*.java') src/app/level/bubble/{Palette,Ui,Renderer,LevelEngine}.java
    java -cp /tmp/pv app.level.bubble.EngineTest
    java -Djava.awt.headless=true -cp /tmp/pv app.level.bubble.Preview assets/fonts /tmp/shots

## Behaviour

- Surface mode (bullseye) when the screen is within 40° of horizontal, edge mode (tube) beyond 50°.
  Edge mode rotates the instrument to the nearest 90° so the tube stays world-horizontal.
- Calibration: place, capture; turn 180° about the vertical (gyro-detected, or tap Ready); capture.
  The two readings cancel the surface slope and leave the sensor offset, so any firm surface works.
  With a gyroscope the measured turn angle is used, so an imprecise turn still solves exactly.
  Surface and edge offsets are calibrated and applied separately (a camera bump or an edge has its
  own geometry). Calibration is stored outside backups: it never moves to another phone.
- Tap the instrument or the pause icon to hold the reading. Swipe sideways to change theme.
- Every control is exposed to TalkBack and Switch Access as a virtual node.

Rubik is bundled under the SIL Open Font License (assets/fonts/OFL.txt).
