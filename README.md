# Bubble Level – Spot On

A calm bubble level for Android: flat colour, hairlines, one accent, a circle motif, a centred
instrument, and named themes chosen in Settings. No ads, no tracking, no internet permission.

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="19%" alt="Surface mode, Aurum theme, tilted 6.9°">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="19%" alt="Edge mode, Ambra theme, tube 4.2° off level">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="19%" alt="Surface mode, Celeste theme, level at 0.0°">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="19%" alt="Calibration step 2 of 2, Salvia theme">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="19%" alt="Settings page, Rosa theme">
</p>

Android 7.0 (API 24) and later. Download the APK from
[Releases](https://github.com/saatvik333/bubble-level-android/releases);
[privacy policy](https://saatvik333.github.io/bubble-level-android/privacy).

## Build

Gradle-free: aapt2 → javac → R8 → bundletool. Needs the Android SDK (`build-tools;36.0.0`,
`platforms;android-36`, `cmdline-tools;latest`), JDK 17+, [bundletool](https://github.com/google/bundletool),
python3 with Pillow, and zip. The design system lives in `hairline/` (its own repository, added with
`git subtree`): the build compiles `hairline/src` and `hairline/android` with the app and takes the
fonts from `hairline/assets`.

    ./build.sh                     # build/Level.aab (Play), build/Level.apk (universal), build/mapping.txt
    NEW_KEYSTORE=1 ./build.sh      # first build on a machine without the upload key: creates one

The upload key lives outside the repository at `~/.local/share/bubble-level-android/upload.jks` (`KEYSTORE`
overrides it). Its password comes from `KEYSTORE_PASS` or the desktop keyring
(`secret-tool lookup service bubble-level-android key upload-keystore`). `VERSION_CODE` / `VERSION_NAME`
override the version; every Play upload needs a higher `VERSION_CODE`.

Publishing on Google Play, with every Play Console answer: [docs/play-console.md](docs/play-console.md).
Store text and graphics live in `fastlane/metadata/android/en-US/`; `tools/store_screenshots.sh`
recaptures the screenshots from an emulator, `tools/make_feature_graphic.py` redraws the feature graphic.

## Layout

    src/me/saatvik/level/
      LevelEngine.java   sensor math, adaptive filter, mode hysteresis, two-step reversal calibration (no Android deps)
      Renderer.java      the instrument and readout, and the settings rows; android.graphics only
      LevelView.java     animation (spring bubble, theme/page/mode crossfades, sliding option highlight), input, haptics
      Settings.java      preferences; calibration in a no-backup file
      Ui.java            per-frame state LevelView writes and Renderer reads
      MainActivity.java  sensors, window, predictive back
    hairline/            the design system: palette and themes, drawing primitives, the settings page,
                         accessibility and keyboard navigation, window chrome, fonts, preview stubs (hairline/README.md)
    tools/preview/       renders Renderer on the JVM with hairline's AWT-backed stubs; EngineTest

    javac -d /tmp/pv $(find tools/preview hairline/preview hairline/src -name '*.java') src/me/saatvik/level/{Ui,Renderer,LevelEngine}.java
    java -cp /tmp/pv me.saatvik.level.EngineTest
    java -Djava.awt.headless=true -cp /tmp/pv me.saatvik.level.Preview hairline/assets/fonts /tmp/shots

## Behaviour

- Surface mode (bullseye) when the screen is within 40° of horizontal, edge mode (tube) beyond 50°.
  Edge mode rotates the instrument to the nearest 90° so the tube stays world-horizontal.
- Calibration: place, capture; turn 180° about the vertical (gyro-detected, or tap Ready); capture.
  The two readings cancel the surface slope and leave the sensor offset, so any firm surface works.
  With a gyroscope the measured net turn between the two resting poses is used, so an imprecise turn
  still solves exactly, however the phone was carried (picked up, held tipped, spun on its own axis).
  Surface and edge offsets are calibrated and applied separately (a camera bump or an edge has its
  own geometry). Calibration is stored outside backups: it never moves to another phone.
- Tap the instrument or the pause icon to hold the reading.
- Settings is one list: units, level tolerance and mode as segmented choices (every option visible),
  haptic feedback and keep-screen-on switches, calibration status with Reset, and the theme swatches
  last. Reset asks first (Keep / Erase) and says so if the change could not be saved.
- Every control is exposed to TalkBack and Switch Access as a virtual node, and reachable with a
  keyboard or D-pad: arrows move a focus ring, Tab goes in reading order, Enter or Space activates,
  Escape goes back.

## Credits

Visual style inspired by Dustland Design's *The Clean One* games; not affiliated with or endorsed by
Dustland Design.

Rubik is bundled under the SIL Open Font License (hairline/assets/fonts/OFL.txt).
