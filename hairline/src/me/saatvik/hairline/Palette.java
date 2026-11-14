package me.saatvik.hairline;

/**
 * Colour tokens. Every surface is drawn from six chosen values: bg (canvas), fg (primary text),
 * dim (secondary text, icons), line (hairlines), accent (the one saturated colour: active and
 * selected states), onAccent (glyphs on accent). Three more are derived so that text and strokes
 * keep WCAG contrast whatever the six are; see DESIGN.md.
 */
public final class Palette {
    /** Opacity of an accent wash behind a highlighted row; accentText is derived to read on it. */
    public static final float TINT = 0.10f;
    /** Opacity of a softened accent stroke (marching chevrons); accentStroke is derived to survive it. */
    public static final float STROKE_ALPHA = 0.85f;

    private static final int INK = 0xFF000000, PAPER = 0xFFFFFFFF;

    public final String name;
    public final boolean dark;
    public final int bg, fg, dim, line, accent, onAccent;
    /**
     * Derived, WCAG AA: accentText is the accent as text, 4.5:1 on bg and on the TINT wash;
     * onAccentText is text or a glyph on an accent fill, 4.5:1; accentStroke is the accent as a
     * ring, chevron or focus outline, 3:1 on bg (also at STROKE_ALPHA).
     */
    public final int accentText, onAccentText, accentStroke;

    public Palette(String name, boolean dark, int bg, int fg, int dim, int line, int accent, int onAccent) {
        this(name, dark, bg, fg, dim, line, accent, onAccent,
                accentText(bg, fg, accent), onAccentText(onAccent, accent), accentStroke(bg, fg, accent));
    }

    private Palette(String name, boolean dark, int bg, int fg, int dim, int line, int accent, int onAccent,
                    int accentText, int onAccentText, int accentStroke) {
        this.name = name;
        this.dark = dark;
        this.bg = bg;
        this.fg = fg;
        this.dim = dim;
        this.line = line;
        this.accent = accent;
        this.onAccent = onAccent;
        this.accentText = accentText;
        this.onAccentText = onAccentText;
        this.accentStroke = accentStroke;
    }

    /** The accent moved toward fg in 2% steps until it reads as text on bg and on the wash. */
    private static int accentText(int bg, int fg, int accent) {
        int tint = mix(bg, accent, TINT);
        int at = accent;
        for (int i = 1; i <= 50 && (contrast(at, bg) < 4.5 || contrast(at, tint) < 4.5); i++) {
            at = mix(accent, fg, i / 50f);
        }
        return at;
    }

    private static int accentStroke(int bg, int fg, int accent) {
        int at = accent;
        for (int i = 1; i <= 50 && contrast(mix(bg, at, STROKE_ALPHA), bg) < 3.0; i++) {
            at = mix(accent, fg, i / 50f);
        }
        return at;
    }

    private static int onAccentText(int onAccent, int accent) {
        if (contrast(onAccent, accent) >= 4.5) return onAccent;
        return contrast(INK, accent) >= contrast(PAPER, accent) ? INK : PAPER;
    }

    /** The built-in themes, in the order the theme row shows them. Never modified. */
    public static final Palette[] BUILT_IN = {
        new Palette("Aurum",   true,  0xFF2B2A27, 0xFFE9E5D9, 0xFF9C978A, 0xFF4A4741, 0xFFBCAD7E, 0xFF2B2A27),
        new Palette("Ambra",   true,  0xFF353432, 0xFFEDEBE8, 0xFFA19D98, 0xFF4E4C48, 0xFFF08A2E, 0xFFFFF8F0),
        new Palette("Rosa",    false, 0xFFFAF9F9, 0xFF4A4646, 0xFF757070, 0xFFE4DEDE, 0xFFC4575E, 0xFFFFFFFF),
        new Palette("Celeste", false, 0xFFF5F7F8, 0xFF4B5055, 0xFF6A7076, 0xFFDCE2E6, 0xFF6A9EC4, 0xFFFFFFFF),
        new Palette("Salvia",  false, 0xFFEFF1EB, 0xFF3F453D, 0xFF676E64, 0xFFD6DCD1, 0xFF7D9A78, 0xFFFFFFFF),
        new Palette("Notte",   true,  0xFF000000, 0xFFE6E6E6, 0xFF8A8A8A, 0xFF262626, 0xFFE6E6E6, 0xFF000000),
    };

    /** In-between palette for the theme cross-fade: mixes every token, derived ones included. */
    public static Palette lerp(Palette a, Palette b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        return new Palette(b.name, t < 0.5f ? a.dark : b.dark,
                mix(a.bg, b.bg, t), mix(a.fg, b.fg, t), mix(a.dim, b.dim, t),
                mix(a.line, b.line, t), mix(a.accent, b.accent, t), mix(a.onAccent, b.onAccent, t),
                mix(a.accentText, b.accentText, t), mix(a.onAccentText, b.onAccentText, t),
                mix(a.accentStroke, b.accentStroke, t));
    }

    /** Per-channel mix of two ARGB colours, alpha included. */
    public static int mix(int c1, int c2, float t) {
        if (t <= 0f) return c1;
        if (t >= 1f) return c2;
        int a = ch(c1 >>> 24, c2 >>> 24, t);
        int r = ch((c1 >> 16) & 0xFF, (c2 >> 16) & 0xFF, t);
        int g = ch((c1 >> 8) & 0xFF, (c2 >> 8) & 0xFF, t);
        int b = ch(c1 & 0xFF, c2 & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int ch(int x, int y, float t) {
        return Math.round(x + (y - x) * t);
    }

    /** WCAG 2 contrast ratio of two opaque colours. */
    public static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int c) {
        return 0.2126 * lin((c >> 16) & 0xFF) + 0.7152 * lin((c >> 8) & 0xFF) + 0.0722 * lin(c & 0xFF);
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Multiplies the colour's existing alpha by {@code a}. */
    public static int alpha(int c, float a) {
        if (a >= 1f) return c;
        if (a <= 0f) return c & 0x00FFFFFF;
        int al = Math.round((c >>> 24) * a);
        return (c & 0x00FFFFFF) | (al << 24);
    }
}
