# Level

Bubble level for Android in the Dustland "Clean One" visual language: flat colour, hairlines,
single accent, circle motif, centred column, named themes switched from the header.

## Build

Gradle-free. Debian/Ubuntu toolchain:

    sudo apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner python3-pil
    ./build.sh            # -> build/Level.apk (v1+v2+v3 signed, minSdk 23, targetSdk 36)

`keystore/level.jks` (password `levelkey`) signs every build; keep it to install updates over the top.

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
- Tap the instrument or the pause icon to hold the reading. Swipe sideways to change theme.

Rubik is bundled under the SIL Open Font License (assets/fonts/OFL.txt).
