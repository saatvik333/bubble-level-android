# Hairline

The design system shared by the Clean-One-style Android apps (Level, Tuner): colour tokens and
themes, drawing primitives, the settings page, the view every app draws on (frame loop, input,
accessibility, keyboard, back, the system's preference signals), window chrome, the Rubik fonts,
and the AWT stubs that render it all on the JVM. The rules behind it are in [DESIGN.md](DESIGN.md).

Plain Java 8, no dependencies, no reflection; compiled against API 36 with newer calls guarded by
`Build.VERSION.SDK_INT`, so it runs from API 24 (the apps' `minSdk`). Each app draws one custom
`View` with android.graphics.

## Layout

    src/me/saatvik/hairline/        android.graphics only, compiles on the JVM with the stubs
      Frame.java                    shared frame state an app's Ui extends: metrics, text scale, insets,
                                    theme, animation scale, back progress, targets, settings list
      Palette.java                  tokens, derived contrast tokens, high-contrast variant, built-in themes,
                                    mix / contrast / alpha
      Motion.java                   approach, ease, smooth, clamp; the system's time constants
      Hits.java                     the frame's targets: id, rect, press shape; pressed and focused ids; roles
      Kit.java                      paints and primitives: header, pill, segmented, stepper, switch, radio,
                                    swatch, rule, row text, scroll thumb, chevron, bar icons, text fitting
                                    and wrapping, focus ring and pressed overlay; type and size scale
      SettingsPage.java             the settings list: rows, layout at any text size, scrolling,
                                    accessibility answers (roles, groups, errors)
    android/me/saatvik/hairline/    Android framework
      Stage.java                    the base View: frame loop, touch, keys, accessibility wiring, themes
                                    and cross-fade, haptics, predictive back with progress, system text
                                    scale, animation scale and contrast
      Access.java                   AccessibilityNodeProvider over Hits: roles, groups with collection
                                    info, errors, pane titles, live regions; keyboard and D-pad focus
      Chrome.java                   edge to edge, bar colours, safe insets, Rubik loader
      Themes.java                   built-ins + Material You "Wallpaper", lookup by name
    preview/stubs/android/graphics/ AWT-backed Canvas, Paint (kerning and ligatures on), Path, RectF, Typeface
    test/me/saatvik/hairline/       Check.java: JVM checks of the pure parts
    assets/fonts/                   rubik_300.ttf, rubik_400.ttf, OFL.txt (SIL Open Font License)

`src/` holds everything the renderer and the JVM preview need; `android/` holds what only runs on
a device. An app compiles both; a preview compiles `src/` with the stubs.

## Use in an app

The app carries the library as a subtree at `hairline/` and refers to it only by that path:

    git subtree add  --prefix=hairline https://github.com/saatvik333/hairline main --squash
    git subtree pull --prefix=hairline https://github.com/saatvik333/hairline main --squash

`build.sh`:

    "$AAPT2" link ... -A hairline/assets ...                                  # Rubik
    javac ... $(find src hairline/src hairline/android "$OUT/gen" -name '*.java')

The manifest sets `android:enableOnBackInvokedCallback="true"` on the application: `Stage`
registers its back callback from Android 13. No ProGuard rules are needed.

Preview and tests (from the app's root):

    javac -d /tmp/pv $(find tools/preview hairline/preview hairline/src -name '*.java') src/<package>/{Ui,Renderer,...}.java
    java -Djava.awt.headless=true -cp /tmp/pv <package>.Preview hairline/assets/fonts /tmp/shots

The library's own checks (from `hairline/`; they print one line per check and exit 1 on a failure):

    javac -d /tmp/hl $(find preview src test -name '*.java')
    java -Djava.awt.headless=true -cp /tmp/hl me.saatvik.hairline.Check assets/fonts

## Wiring

    // Ui: the frame's state
    final class Ui extends Frame {
        Ui() { super(48); }                     // target capacity
        float pageT;                            // the app's own fields
    }

    // Renderer.draw (android.graphics only)
    k.begin(ui);
    c.drawColor(ui.pal.bg);
    ... main page: k.a = 1 - pageT; k.hitDy = -16 * dp * pageT; k.pill(...), k.hitCircle(...) ...
    if (pageT > 0.001f) {
        k.a = pageT;
        k.hitDy = 24f * dp * (1f - pageT);
        c.save(); c.translate(0f, k.hitDy);
        SettingsPage s = ui.page;
        s.clear();
        s.segmented(H_NAMES, "Note names", null, NAMES, ui.flats ? 1 : 0, ui.namesPos);
        s.stepper(H_A4, "Reference pitch", null, ui.a4Label);        // H_A4 lowers, H_A4 + 1 raises
        s.toggle(H_HAPTIC, "Haptic feedback", null, ui.haptics, ui.hapticsT);
        s.themes(H_THEME, "Theme", ui.themes, ui.themeIdx);
        s.draw(c, k, left, usable, ui.insetT + 64f * dp, ui.height - ui.insetB, pageT >= 0.5f);
        c.restore();
        k.hitDy = 0f;
        k.header(c, "Settings", left, cx, ui.insetT + 32f * dp, H_BACK, pageT >= 0.5f);
    }
    k.a = 1f;
    k.feedback(c);                                                    // focus ring, pressed overlay

    // View
    final class MyView extends Stage<Ui> {
        MyView(Context c, Renderer r, Settings s) { super(c, new Ui(), s.theme); ... }
        boolean step(float dt, long now) {   // true while something moves
            float pt = onSettings ? 1f - BACK_PREVIEW * ui.backT : 0f;    // back gesture previews closing
            ui.pageT = approach(ui.pageT, pt, dt, Motion.PAGE);           // follows the animation scale
            access.list = onSettings ? ui.page : null;
            ...
        }
        void render(Canvas c)      { renderer.draw(c, ui); }
        void act(int id)           { ... }          // Stage has already given the tap haptic
        boolean canGoBack()        { return onSettings || somethingElseOpen; }
        public boolean back()      { ... }          // closes what is open; false at the top level
        public String label(int id) { switch (id) { case H_SETTINGS: return "Settings"; ... default: return super.label(id); } }
        void themeChanged(Palette p) { settings.theme = p.name; save(); }
        int liveTarget()           { return onMain ? H_READING : 0; }      // a changing reading, said politely
    }
    opening settings: page = 1; access.pageChanged(0); paneTitle("Settings");
    closing:          page = 0; access.pageChanged(H_SETTINGS); paneTitle(null);
    a theme swatch:   setTheme(id - H_THEME);
    success, failure: haptic(Stage.CONFIRM), haptic(Stage.REJECT); setHaptics(settings.haptics)
    a sensor sample:  if (looping() || needsFrame()) requestFrame();

    // Activity
    Typeface light = Chrome.font(getAssets(), 300), regular = Chrome.font(getAssets(), 400);
    view = new MyView(this, new Renderer(light, regular), settings);
    setContentView(view);
    Chrome.edgeToEdge(getWindow());
    onResume() { view.resume(); }  onPause() { view.pause(); }
    onBackPressed()  { if (!view.back()) super.onBackPressed(); }   // Android 12 and below

## Ids

Target ids are the app's: non-zero, unique within a frame, and below `0x40000000` (group nodes use
that bit). A settings row takes its id and, for several targets, the ids after it: segmented and
themes `id + i` per option, stepper `id` (lower) and `id + 1` (raise), actions `id + i` per button
(a row without buttons is a text node with its id); leave gaps between row ids. The options of a
group take consecutive ids from the first, which is the group's key. The library reserves no ids;
the header's back id is passed to `Kit.header`. For an in-place confirmation, give the question's
buttons the trigger's id as their first (safe) one, so keyboard focus stays on it.

## License

Copyright © 2026 Saatvik Sharma. All rights reserved. Published for viewing only; no permission is
granted to use, copy, modify or distribute it (see [LICENSE](LICENSE)). The Rubik fonts keep the SIL
Open Font License (`assets/fonts/OFL.txt`).
