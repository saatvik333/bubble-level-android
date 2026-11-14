package me.saatvik.hairline;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * JVM checks of the pure parts: palette contrast guarantees (every built-in theme, and the
 * derivations over random palettes), settings layout invariants across window sizes and font
 * scales, the hit table, motion, and text wrapping. Run from the library root:
 *   java -cp DIR me.saatvik.hairline.Check assets/fonts
 */
public final class Check {
    static int fails;

    public static void main(String[] args) {
        Typeface light = null, regular = null;
        if (args.length > 0) {
            light = Typeface.fromFile(args[0] + "/rubik_300.ttf");
            regular = Typeface.fromFile(args[0] + "/rubik_400.ttf");
        }
        builtInContrast();
        derivations();
        highContrast();
        lerp();
        hits();
        motion();
        textScale();
        wrap(new Kit(light, regular));
        paragraphs(new Kit(light, regular));
        settingsLayout(new Kit(light, regular));
        largeText(new Kit(light, regular));
        components(new Kit(light, regular));
        stepperRow(new Kit(light, regular));
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }

    // ---- palette -----------------------------------------------------------------------------

    static void builtInContrast() {
        for (Palette p : Palette.BUILT_IN) {
            int tint = Palette.mix(p.bg, p.accent, Palette.TINT);
            check(p.name + " fg on bg >= 4.5", Palette.contrast(p.fg, p.bg) >= 4.5);
            check(p.name + " dim on bg >= 4.5", Palette.contrast(p.dim, p.bg) >= 4.5);
            check(p.name + " accentText on bg >= 4.5", Palette.contrast(p.accentText, p.bg) >= 4.5);
            check(p.name + " accentText on tint >= 4.5", Palette.contrast(p.accentText, tint) >= 4.5);
            check(p.name + " onAccentText on accent >= 4.5", Palette.contrast(p.onAccentText, p.accent) >= 4.5);
            check(p.name + " accentStroke at STROKE_ALPHA >= 3", strokeContrast(p) >= 3.0);
        }
        // the reference for onAccentText ink: pure black reads on Rosa's accent where #1A1A1A did not
        Palette rosa = Palette.BUILT_IN[2];
        check("Rosa onAccentText is ink", rosa.onAccentText == 0xFF000000);
        check("Aurum keeps its own onAccent", Palette.BUILT_IN[0].onAccentText == Palette.BUILT_IN[0].onAccent);
    }

    static double strokeContrast(Palette p) {
        return Palette.contrast(Palette.mix(p.bg, p.accentStroke, Palette.STROKE_ALPHA), p.bg);
    }

    /**
     * Derived tokens reach their bar whenever fg itself does, for any palette (Wallpaper palettes
     * come from the system and cannot be listed); onAccentText always, since black or white
     * reaches 4.58:1 on any colour.
     */
    static void derivations() {
        Random rnd = new Random(7);
        int text = 0, stroke = 0, onAccent = 0, textTested = 0, strokeTested = 0;
        for (int i = 0; i < 5000; i++) {
            int bg = 0xFF000000 | rnd.nextInt(0x1000000);
            int far = Palette.contrast(0xFF000000, bg) > Palette.contrast(0xFFFFFFFF, bg) ? 0xFF000000 : 0xFFFFFFFF;
            int fg = Palette.mix(bg, far, 0.6f + 0.4f * rnd.nextFloat());
            int accent = 0xFF000000 | rnd.nextInt(0x1000000), on = 0xFF000000 | rnd.nextInt(0x1000000);
            Palette p = new Palette("r", false, bg, fg, fg, fg, accent, on);
            int tint = Palette.mix(bg, accent, Palette.TINT);
            if (Palette.contrast(p.onAccentText, accent) < 4.5) onAccent++;
            if (Palette.contrast(fg, bg) >= 4.5 && Palette.contrast(fg, tint) >= 4.5) {
                textTested++;
                if (Palette.contrast(p.accentText, bg) < 4.5 || Palette.contrast(p.accentText, tint) < 4.5) text++;
            }
            if (Palette.contrast(Palette.mix(bg, fg, Palette.STROKE_ALPHA), bg) >= 3.0) {
                strokeTested++;
                if (strokeContrast(p) < 3.0) stroke++;
            }
        }
        check("random palettes tested", textTested > 1000 && strokeTested > 1000);
        check("accentText >= 4.5 on bg and tint, all", text == 0);
        check("accentStroke >= 3 at STROKE_ALPHA, all", stroke == 0);
        check("onAccentText >= 4.5 on accent, all", onAccent == 0);
    }

    /**
     * The high-contrast variant: built-ins reach every raised bar; any palette's fg reaches 7:1
     * or is as far as it can go (pure black or white), and dim, line and the derived tokens follow.
     */
    static void highContrast() {
        for (Palette b : Palette.BUILT_IN) {
            Palette p = b.contrasted();
            int tint = Palette.mix(p.bg, p.accent, Palette.TINT);
            String n = p.name + " high contrast: ";
            check(n + "same name, bg, accent", p.name.equals(b.name) && p.bg == b.bg && p.accent == b.accent);
            check(n + "fg, dim >= 7", Palette.contrast(p.fg, p.bg) >= 7.0 && Palette.contrast(p.dim, p.bg) >= 7.0);
            check(n + "line >= 3", Palette.contrast(p.line, p.bg) >= 3.0);
            check(n + "accentText >= 7 on bg and tint",
                    Palette.contrast(p.accentText, p.bg) >= 7.0 && Palette.contrast(p.accentText, tint) >= 7.0);
            check(n + "accentStroke at STROKE_ALPHA >= 4.5", strokeContrast(p) >= 4.5);
            check(n + "onAccentText no worse", Palette.contrast(p.onAccentText, p.accent) >= Palette.contrast(b.onAccentText, b.accent));
        }
        Random rnd = new Random(11);
        int bad = 0;
        for (int i = 0; i < 5000; i++) {
            int bg = 0xFF000000 | rnd.nextInt(0x1000000);
            Palette p = new Palette("r", false, bg, 0xFF000000 | rnd.nextInt(0x1000000), 0xFF000000 | rnd.nextInt(0x1000000),
                    0xFF000000 | rnd.nextInt(0x1000000), 0xFF000000 | rnd.nextInt(0x1000000), 0xFF000000 | rnd.nextInt(0x1000000)).contrasted();
            int pole = Palette.contrast(0xFF000000, bg) >= Palette.contrast(0xFFFFFFFF, bg) ? 0xFF000000 : 0xFFFFFFFF;
            double best = Math.min(7.0, Palette.contrast(pole, bg));
            if (Palette.contrast(p.fg, bg) < best - 0.01 && p.fg != pole) bad++;
        }
        check("high contrast fg reaches 7 or the pole, any palette", bad == 0);
    }

    static void lerp() {
        Palette a = Palette.BUILT_IN[0], b = Palette.BUILT_IN[3];
        check("lerp 0 is from", Palette.lerp(a, b, 0f) == a);
        check("lerp 1 is to", Palette.lerp(a, b, 1f) == b);
        Palette m = Palette.lerp(a, b, 0.5f);
        check("lerp mixes derived tokens, not re-derives", m.accentText == Palette.mix(a.accentText, b.accentText, 0.5f)
                && m.accentStroke == Palette.mix(a.accentStroke, b.accentStroke, 0.5f)
                && m.onAccentText == Palette.mix(a.onAccentText, b.onAccentText, 0.5f));
        check("lerp names the target", m.name.equals(b.name));
        check("alpha multiplies", Palette.alpha(0x80FFFFFF, 0.5f) >>> 24 == 0x40);
    }

    // ---- hits, motion, text ------------------------------------------------------------------

    static void hits() {
        Hits h = new Hits(2);
        h.add(1, 0, 0, 100, 100, Hits.AREA, 0, 0, 0, 0);
        h.add(2, 50, 50, 150, 150, Hits.CIRCLE, 100, 100, 20, 20);
        check("topmost target wins", h.at(60, 60) == 2);
        check("lower target outside overlap", h.at(10, 10) == 1);
        check("miss is 0", h.at(200, 200) == 0);
        check("full table refuses", !h.add(3, 0, 0, 1, 1, Hits.AREA, 0, 0, 0, 0));
        check("find", h.find(2) != null && h.find(3) == null);
        h.reset();
        check("reset empties", h.count == 0 && h.at(60, 60) == 0);
    }

    static void motion() {
        float v = 0f;
        int frames = 0;
        while (v != 1f && frames < 1000) {
            v = Motion.approach(v, 1f, 1f / 60f, Motion.CONTROL);
            frames++;
        }
        check("approach snaps to target", v == 1f);
        check("control settles in under 0.5 s", frames < 30);
        check("ease ends", Motion.ease(0f) == 0f && Motion.ease(1f) == 1f);
        check("ease midpoint", Math.abs(Motion.ease(0.5f) - 0.5f) < 1e-6f);
        check("smooth clamps", Motion.smooth(-1f) == 0f && Motion.smooth(2f) == 1f);
    }

    /** Full text scale: linear samples reproduce sp; a non-linear table is followed between samples. */
    static void textScale() {
        Frame f = new Frame(1);
        f.textScale(2f, 1.5f);
        check("linear: 13 sp at 1.5x", Math.abs(f.fullPx(13f) - 39f) < 0.01f);
        check("capped scale for fixed pages", f.sp == 2f * Kit.MAX_FONT_SCALE);
        // Android 14's table at 2x (sp -> dp), times density 1: large text grows less than small
        float[] at2 = {16f, 20f, 24f, 26f, 30f, 34f, 36f, 38f, 42f, 50f, 100f};
        System.arraycopy(at2, 0, f.textPx, 0, at2.length);
        check("non-linear: 16 sp", Math.abs(f.fullPx(16f) - 30f) < 0.01f);
        check("non-linear: 13 sp between 12 and 14", Math.abs(f.fullPx(13f) - 25f) < 0.01f);
        check("non-linear: past the last sample keeps its ratio", Math.abs(f.fullPx(200f) - 200f) < 0.01f);
        boolean rising = true;
        for (float s = 1f; s < 120f; s += 0.5f) rising &= f.fullPx(s + 0.5f) > f.fullPx(s);
        check("non-linear: rises with size", rising);
    }

    /** A canvas that records the strings drawn. */
    static final class Recorder extends Canvas {
        final List<String> drawn = new ArrayList<>();

        Recorder(int w, int h) {
            super(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).createGraphics(), w, h);
        }

        @Override
        public void drawText(String s, float x, float y, Paint p) {
            drawn.add(s);
            super.drawText(s, x, y, p);
        }
    }

    static void wrap(Kit k) {
        k.text.setTypeface(k.regular);
        k.text.setTextSize(13f);
        // a word-by-word break would leave "while" alone on the second line
        String s = "Ok. Keep it on the same spot for a little while", one = "Ok.";
        String two = "Keep it on the same spot for a little while";
        float w = Math.max(k.text.measureText(one), k.text.measureText(two)) + 1f;
        check("wrap needs two lines", k.text.measureText(s) > w && k.lines(s, w) == 2);
        check("a word break would differ", k.text.measureText("Ok. Keep it on the same spot for a little") <= w);
        Recorder c = new Recorder(600, 100);
        k.wrapped(c, s, 300f, 40f, w, 18f);
        check("wrap breaks at the sentence", c.drawn.size() == 2 && c.drawn.get(0).equals(one)
                && c.drawn.get(1).equals(two));
        check("short text is one line", k.lines("Level", w) == 1);
        // no word fits: wrapped shrinks it onto one line, and centring must count one
        check("text without a fitting break is one line", k.lines("Calibration", 10f) == 1);
    }

    /** Row text wraps at spaces onto as many lines as it needs; only a word wider than the line is shrunk. */
    static void paragraphs(Kit k) {
        k.text.setTypeface(k.regular);
        k.text.setTextSize(26f);
        String s = "Counts as level within plus or minus this angle, in degrees";
        float w = 220f;
        int n = k.lineCount(s, w);
        check("long text takes several lines", n >= 3);
        Recorder c = new Recorder(600, 600);
        k.drawLines(c, s, 0f, 40f, w, 30f);
        boolean fit = c.drawn.size() == n;
        StringBuilder joined = new StringBuilder();
        for (String line : c.drawn) {
            fit &= k.text.measureText(line) <= w || line.indexOf(' ') < 0;
            joined.append(joined.length() > 0 ? " " : "").append(line);
        }
        check("every line fits, a lone long word excepted", fit);
        check("no word lost or split", joined.toString().equals(s));
        check("short text is one line", k.lineCount("Theme", w) == 1 && k.lineCount(null, w) == 0);
        check("lines are capped", k.lineCount("a b c d e f g h i j k l m n o p", 1f) == Kit.MAX_LINES);
    }

    /**
     * At 2x text the list keeps every word at full size: a choice whose options cannot fit
     * becomes a stacked list, and rows grow instead of shrinking their text.
     */
    static void largeText(Kit k) {
        float d = 2f, wPx = 320f * d, hPx = 569f * d;
        Frame f = frame(64, Palette.BUILT_IN[0], d, 2f);
        k.begin(f);
        SettingsPage p = page(Palette.BUILT_IN, false);
        Recorder c = new Recorder((int) wPx, (int) hPx);
        p.draw(c, k, 0f, wPx, 96f * d, hPx, true);
        Hits.Hit a = f.hits.find(30), b = f.hits.find(31);
        check("2x: units stack one option per line", a != null && b != null && b.rect.top >= a.rect.bottom - 0.5f
                && a.rect.height() >= Kit.TOUCH * d - 0.5f);
        check("2x: stacked options keep their roles", p.role(31) == Hits.RADIO && p.checked(30));
        p.scroll = 1e9f;
        f.hits.reset();
        p.draw(c, k, 0f, wPx, 96f * d, hPx, true);
        check("2x: themes reachable at the end", f.hits.find(60) != null);
        k.fullText = true;
        check("2x: list label at full size", Math.abs(k.px(Kit.LABEL) - 32f * d) < 0.01f);
        k.fullText = false;
        check("2x: fixed pages capped", Math.abs(k.px(Kit.LABEL) - 16f * d * Kit.MAX_FONT_SCALE) < 0.01f);

        SettingsPage q = new SettingsPage();
        q.segmented(30, "Units", null, UNITS, 1, 1f);
        q.actions(18, "Calibration", "Couldn't save. It applies until the app closes", true, new String[0], -1);
        check("groups: options name their row", q.group(31) == 30 && "Units".equals(q.groupLabel(30)) && q.groupSize(30) == 4);
        check("groups: not a group", q.group(18) == 0 && q.groupLabel(31) == null);
        check("text row: readable, not actionable", q.role(18) == Hits.TEXT
                && "Calibration. Couldn't save. It applies until the app closes".equals(q.label(18)));
        check("text row: reports its error", "Couldn't save. It applies until the app closes".equals(q.error(18)));
        q.clear();
        q.actions(18, "Calibration", "Erase the saved offsets for every pose?", true, CONFIRM, 1);
        check("a question is not an error", q.error(18) == null && q.error(19) == null);
    }

    // ---- settings layout ---------------------------------------------------------------------

    static final String[] UNITS = {"Degrees", "Percent", "mm/m", "in/ft"};
    static final String[] TOL = {"0.1°", "0.2°", "0.3°", "0.5°", "1.0°"};
    static final String[] RESET = {"Reset"}, CONFIRM = {"Keep", "Erase"};

    static void settingsLayout(Kit k) {
        Palette[] themes = new Palette[Palette.BUILT_IN.length + 1];   // with a Wallpaper theme: 7 swatches
        System.arraycopy(Palette.BUILT_IN, 0, themes, 0, Palette.BUILT_IN.length);
        themes[themes.length - 1] = new Palette("Wallpaper", false, 0xFFFDF8FD, 0xFF1D1B20, 0xFF605D66,
                0xFFE7E0EB, 0xFF6750A4, 0xFFFFFFFF);
        // w, h dp; density; font scale
        float[][] windows = {{412, 914, 2.625f, 1f}, {360, 370, 2.625f, 1f}, {320, 569, 2f, 1.3f}, {914, 412, 2.625f, 1f},
                {320, 569, 2f, 2f}, {412, 914, 2.625f, 2f}};
        for (float[] win : windows) {
            for (int confirm = 0; confirm < 2; confirm++) {
                for (int scrolled = 0; scrolled < 2; scrolled++) {
                    layout(k, themes, win, confirm == 1, scrolled == 1);
                }
            }
        }
        SettingsPage p = page(themes, false);
        check("label: segmented", "Units, Percent".equals(p.label(31)));
        check("label: toggle", "Haptic feedback".equals(p.label(16)));
        check("label: stepper", "Reference pitch, 440 Hz. Increase".equals(p.label(71)));
        check("label: action", "Reset, Calibration. Face up".equals(p.label(18)));
        check("label: theme", "Theme Rosa".equals(p.label(62)));
        check("label: not a row", p.label(99) == null && p.role(99) == -1);
        check("roles", p.role(30) == Hits.RADIO && p.role(16) == Hits.SWITCH && p.role(70) == Hits.BUTTON
                && p.role(18) == Hits.BUTTON && p.role(60) == Hits.RADIO);
        check("checked: chosen option only", p.checked(30) && !p.checked(31));
        check("checked: switch", p.checked(16) && !p.checked(17));
        check("checked: theme", p.checked(60) && !p.checked(61));
        check("stepper repeats, options do not", p.repeats(70) && p.repeats(71) && !p.repeats(30));
    }

    static SettingsPage page(Palette[] themes, boolean confirm) {
        SettingsPage p = new SettingsPage();
        p.segmented(30, "Units", null, UNITS, 0, 0f);
        p.segmented(40, "Level tolerance", "Counts as level within ± this angle", TOL, 1, 1f);
        p.toggle(16, "Haptic feedback", "Buzz on level and on taps", true, 1f);
        p.toggle(17, "Keep screen on", null, false, 0f);
        p.stepper(70, "Reference pitch", "A4", "440 Hz");
        if (confirm) p.actions(18, "Calibration", "Erase the saved offsets for every pose?", true, CONFIRM, 1);
        else p.actions(18, "Calibration", "Face up", false, RESET, -1);
        p.themes(60, "Theme", themes, 0);
        return p;
    }

    static void layout(Kit k, Palette[] themes, float[] win, boolean confirm, boolean scrolled) {
        float d = win[2], wPx = win[0] * d, hPx = win[1] * d;
        String at = (int) win[0] + "x" + (int) win[1] + "@" + win[3] + (confirm ? " confirm" : "") + (scrolled ? " scrolled" : "");
        Frame f = frame(64, Palette.BUILT_IN[2], d, win[3]);
        Hits hits = f.hits;
        k.begin(f);
        SettingsPage p = page(themes, confirm);
        float viewTop = 32f * d + 64f * d, viewBottom = hPx - 24f * d;
        if (scrolled) p.scroll = 1e9f;
        Canvas c = new Canvas(new BufferedImage((int) wPx, (int) hPx, BufferedImage.TYPE_INT_ARGB).createGraphics(),
                (int) wPx, (int) hPx);
        p.draw(c, k, 0f, wPx, viewTop, viewBottom, true);
        check(at + ": scroll clamped", p.scroll >= 0f && p.scroll <= p.maxScroll && (!scrolled || p.scroll == p.maxScroll));
        if (win[1] >= 900f && win[3] <= Kit.MAX_FONT_SCALE) check(at + ": tall window does not scroll", p.maxScroll == 0f);
        if (win[1] <= 400f) check(at + ": short window scrolls", p.maxScroll > 0f);

        float colW = Math.min(wPx - 48f * d, Kit.COLUMN * d), x0 = (wPx - colW) / 2f;
        Set<Integer> ids = new HashSet<>();
        boolean unique = true, inside = true, tall = true, wide = true, apart = true, named = true;
        for (int i = 0; i < hits.count; i++) {
            Hits.Hit h = hits.hits[i];
            RectF r = h.rect;
            unique &= ids.add(h.id);
            // swatch targets reach half a gap (8 dp at most) into the 24 dp margin, pills 4 dp
            inside &= r.left >= x0 - 8f * d - 0.5f && r.right <= x0 + colW + 8f * d + 0.5f
                    && r.top >= viewTop - 0.5f && r.bottom <= viewBottom + 0.5f;
            boolean clipped = r.top <= viewTop + 0.5f || r.bottom >= viewBottom - 0.5f;
            if (!clipped) tall &= r.height() >= Kit.TOUCH * d - 0.5f;
            wide &= r.width() >= 40f * d - 0.5f;
            String label = p.label(h.id);
            named &= label != null && label.length() > 0 && p.role(h.id) >= 0;
            for (int j = 0; j < i; j++) {
                RectF o = hits.hits[j].rect;
                float ox = Math.min(r.right, o.right) - Math.max(r.left, o.left);
                float oy = Math.min(r.bottom, o.bottom) - Math.max(r.top, o.top);
                apart &= ox <= 0.5f || oy <= 0.5f;
            }
        }
        check(at + ": targets present", hits.count > 0);
        check(at + ": ids unique", unique);
        check(at + ": targets within 8 dp of the column, inside the view", inside);
        check(at + ": visible targets >= 48 dp tall", tall);
        check(at + ": targets >= 40 dp wide", wide);
        check(at + ": targets do not overlap", apart);
        check(at + ": every target named with a role", named);
        if (confirm && hits.find(19) != null && hits.find(18) != null) {
            check(at + ": destructive answer last", hits.find(19).rect.left > hits.find(18).rect.left);
        }
    }

    /** Targets outside the list: header back, compact pills, the stepper, all reach the touch minimum. */
    static void components(Kit k) {
        float d = 2f;
        Frame f = frame(8, Palette.BUILT_IN[0], d, 1f);
        Hits hits = f.hits;
        k.begin(f);
        Canvas c = new Canvas(new BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB).createGraphics(), 800, 800);
        k.header(c, "Settings", 0f, 400f, 64f, 7, true);
        k.pill(c, "Skip", 400f, 300f, 8, true, false, 200f, 30f * d);
        k.stepper(c, "440 Hz", 700f, 500f, 9, true);
        k.pill(c, "Hidden", 400f, 700f, 11, false, true, 200f, 40f * d);
        check("back target >= 48 dp", hits.find(7) != null && hits.find(7).rect.height() >= Kit.TOUCH * d);
        check("compact pill target >= 48 dp", hits.find(8) != null && hits.find(8).rect.height() >= Kit.TOUCH * d);
        check("stepper halves >= 48 dp", hits.find(9) != null && hits.find(10) != null
                && hits.find(9).rect.height() >= Kit.TOUCH * d && hits.find(10).rect.left >= hits.find(9).rect.right);
        check("inactive draws without a target", hits.find(11) == null);
        check("pill press shape is the pill", hits.find(8).shape == Hits.PILL && hits.find(8).ry == 15f * d);
        // a long value keeps clear of the focus ring around either chevron's press circle, at any text size
        for (float scale : new float[]{1f, 1.3f}) {
            Frame g = frame(8, Palette.BUILT_IN[0], d, scale);
            hits = g.hits;
            k.begin(g);
            String v = "A4 = 440 Hz";
            k.stepper(c, v, 700f, 500f, 9, true);
            Hits.Hit raise = hits.find(10);
            float valueRight = 700f - k.stepperW(v) / 2f + k.text.measureText(v) / 2f;
            float ringLeft = raise.cx - raise.rx - (Kit.FOCUS_GAP + Kit.FOCUS_W / 2f) * d;
            check("stepper value clear of the focus ring at " + scale + "x", valueRight + 2f * d <= ringLeft);
        }
    }

    /** A stepper shares its row while its text fits beside it at full size; otherwise it goes under the text, never shrinking it. */
    static void stepperRow(Kit k) {
        // w dp, density, font scale, whether "Reference pitch" / "Clears the tuned marks" fit beside "A4 = 440 Hz"
        float[][] windows = {{412, 2.625f, 1f, 1}, {320, 2f, 1.3f, 0}};
        for (float[] win : windows) {
            float d = win[1], wPx = win[0] * d, viewTop = 96f * d;
            String at = (int) win[0] + "@" + win[2];
            Frame f = frame(4, Palette.BUILT_IN[0], d, win[2]);
            Hits hits = f.hits;
            k.begin(f);
            SettingsPage p = new SettingsPage();
            p.stepper(70, "Reference pitch", "Clears the tuned marks", "A4 = 440 Hz");
            Canvas c = new Canvas(new BufferedImage((int) wPx, 1200, BufferedImage.TYPE_INT_ARGB).createGraphics(), (int) wPx, 1200);
            p.draw(c, k, 0f, wPx, viewTop, 1200f, true);
            Hits.Hit lower = hits.find(70), raise = hits.find(71);
            float colW = Math.min(wPx - 48f * d, Kit.COLUMN * d), x0 = (wPx - colW) / 2f;
            k.fullText = true;                               // the list's text scale
            float textBottom = viewTop + 8f * d + Kit.PAD * d + k.rowTextH("Reference pitch", "Clears the tuned marks", colW);
            k.fullText = false;
            check(at + ": stepper targets", lower != null && raise != null);
            if (lower == null || raise == null) continue;
            if (win[3] == 1) {
                check(at + ": stepper beside its text", lower.cy < textBottom && Math.abs(raise.rect.right - (x0 + colW)) < 0.5f);
            } else {
                check(at + ": stepper under its text, at the column start", lower.rect.top >= textBottom
                        && Math.abs(lower.rect.left - x0) < 0.5f);
            }
        }
    }

    static Frame frame(int targets, Palette pal, float density, float fontScale) {
        Frame f = new Frame(targets);
        f.textScale(density, fontScale);
        f.pal = pal;
        return f;
    }

    static void check(String name, boolean ok) {
        if (!ok) fails++;
        System.out.printf("%s %s%n", ok ? "ok  " : "FAIL", name);
    }
}
