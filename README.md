# Hairline

The design system shared by the Clean-One-style Android apps (Level, Tuner): colour tokens and
themes, drawing primitives, the settings page, accessibility and keyboard navigation over a hit
table, window chrome, the Rubik fonts, and the AWT stubs that render it all on the JVM. The rules
behind it are in [DESIGN.md](DESIGN.md).

Plain Java 8, no dependencies, no reflection; compiled against API 36 with newer calls guarded by
`Build.VERSION.SDK_INT`, so it runs from API 23. Each app draws one custom `View` with
android.graphics.

## Layout

    src/me/saatvik/hairline/        android.graphics only, compiles on the JVM with the stubs
      Palette.java                  tokens, derived contrast tokens, built-in themes, mix / contrast / alpha
      Motion.java                   approach, ease, smooth, clamp; the system's time constants
      Hits.java                     the frame's targets: id, rect, press shape; pressed and focused ids; roles
      Kit.java                      paints and primitives: header, pill, segmented, stepper, switch, swatch,
                                    rule, row text, scroll thumb, chevron, bar icons, text fitting and
                                    wrapping, focus ring and pressed overlay; type and size scale
      SettingsPage.java             the settings list: rows, layout, scrolling, accessibility answers
    android/me/saatvik/hairline/    Android framework
      Access.java                   AccessibilityNodeProvider over Hits, live region, announcements,
                                    keyboard and D-pad focus
      Chrome.java                   edge to edge, bar colours, safe insets, Rubik loader, predictive back
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

The manifest sets `android:enableOnBackInvokedCallback="true"` on the application: `Chrome.Back`
registers its callback from Android 13. No ProGuard rules are needed.

Preview and tests (from the app's root):

    javac -d /tmp/pv $(find tools/preview hairline/preview hairline/src -name '*.java') src/<package>/{Ui,Renderer,...}.java
    java -Djava.awt.headless=true -cp /tmp/pv <package>.Preview hairline/assets/fonts /tmp/shots

The library's own checks (from `hairline/`; they print one line per check and exit 1 on a failure):

    javac -d /tmp/hl $(find preview src test -name '*.java')
    java -Djava.awt.headless=true -cp /tmp/hl me.saatvik.hairline.Check assets/fonts

## Wiring

    // Ui (shared frame state)
    final Hits hits = new Hits(48);
    final SettingsPage page = new SettingsPage();

    // Renderer.draw (android.graphics only)
    k.begin(ui.hits, ui.pal, ui.dp, ui.sp);
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

    // View implements Access.Nodes
    private final Access access = new Access(this, ui.hits, this);
    onDraw:          step() (sets access.list = onSettings ? ui.page : null); renderer.draw(c, ui); access.afterFrame();
    page switches:   access.pageChanged(0) when opening, access.pageChanged(H_SETTINGS) when closing
    touch down:      ui.hits.focused = 0; ui.hits.pressed = ui.hits.at(x, y);
    drag:            if (ui.page.scrollBy(lastY - y)) invalidate();
    getAccessibilityNodeProvider() { return access; }
    dispatchHoverEvent(e)          { return access.onHover(e) || super.dispatchHoverEvent(e); }
    onFocusChanged(g, d, r)        { super.onFocusChanged(g, d, r); access.onFocusChanged(g); }
    onKeyDown(code, e)             { return access.onKeyDown(code, e) || super.onKeyDown(code, e); }
    onKeyUp(code, e)               { return access.onKeyUp(code, e) || super.onKeyUp(code, e); }
    label(id)   { switch (id) { case H_SETTINGS: return "Settings"; ... default: return ui.page.label(id); } }
    role(id)    { int r = ui.page.role(id); return r >= 0 ? r : Hits.BUTTON; }
    checked(id) { return ui.page.checked(id); }
    repeats(id) { return ui.page.repeats(id); }
    activate(id), back()   the app's actions; back() closes what is open, false at the top level

    // Activity
    Typeface light = Chrome.font(getAssets(), 300), regular = Chrome.font(getAssets(), 400);
    view = new MyView(this, new Renderer(light, regular), Themes.all(this), ...);
    back = new Chrome.Back(this, view);
    setContentView(view);
    Chrome.edgeToEdge(getWindow());
    Chrome.theme(getWindow(), palette);       // on every theme change and in onAttachedToWindow
    onBackPressed()  { if (!view.back()) super.onBackPressed(); }   // Android 12 and below
    the view calls back.intercept(somethingToClose) every frame

## Ids

Target ids are the app's: non-zero and unique within a frame. A settings row takes its id and, for
several targets, the ids after it: segmented and themes `id + i` per option, stepper `id` (lower)
and `id + 1` (raise), actions `id + i` per button; leave gaps between row ids. The library reserves
no ids; the header's back id is passed to `Kit.header`. For an in-place confirmation, give the
question's buttons the trigger's id as their first (safe) one, so keyboard focus stays on it.

## License

Copyright © 2026 Saatvik Sharma. All rights reserved. Published for viewing only; no permission is
granted to use, copy, modify or distribute it (see [LICENSE](LICENSE)). The Rubik fonts keep the SIL
Open Font License (`assets/fonts/OFL.txt`).
