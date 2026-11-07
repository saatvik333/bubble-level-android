package me.saatvik.level;

/**
 * Colour tokens. Every surface in the app is drawn from these six values:
 * bg (canvas), fg (primary text), dim (secondary text, icons), line (hairlines),
 * accent (the one saturated colour: bubble, active states), onAccent (glyphs on accent).
 */
final class Palette {
    final String name;
    final boolean dark;
    final int bg, fg, dim, line, accent, onAccent;
    /** Derived for text, WCAG AA (4.5:1): accent as text on bg, and text on an accent fill. */
    final int accentText, onAccentText;

    Palette(String name, boolean dark, int bg, int fg, int dim, int line, int accent, int onAccent) {
        this.name = name;
        this.dark = dark;
        this.bg = bg;
        this.fg = fg;
        this.dim = dim;
        this.line = line;
        this.accent = accent;
        this.onAccent = onAccent;
        int at = accent;
        for (int i = 1; i <= 50 && contrast(at, bg) < 4.5; i++) at = mix(accent, fg, i / 50f);
        accentText = at;
        if (contrast(onAccent, accent) >= 4.5) {
            onAccentText = onAccent;
        } else {
            int ink = 0xFF1A1A1A, paper = 0xFFFFFFFF;
            onAccentText = contrast(ink, accent) >= contrast(paper, accent) ? ink : paper;
        }
    }

    static final Palette[] BUILT_IN = {
        new Palette("Aurum",   true,  0xFF2B2A27, 0xFFE9E5D9, 0xFF9C978A, 0xFF4A4741, 0xFFBCAD7E, 0xFF2B2A27),
        new Palette("Ambra",   true,  0xFF353432, 0xFFEDEBE8, 0xFFA19D98, 0xFF4E4C48, 0xFFF08A2E, 0xFFFFF8F0),
        new Palette("Rosa",    false, 0xFFFAF9F9, 0xFF4A4646, 0xFF757070, 0xFFE4DEDE, 0xFFC4575E, 0xFFFFFFFF),
        new Palette("Celeste", false, 0xFFF5F7F8, 0xFF4B5055, 0xFF6A7076, 0xFFDCE2E6, 0xFF6A9EC4, 0xFFFFFFFF),
        new Palette("Salvia",  false, 0xFFEFF1EB, 0xFF3F453D, 0xFF676E64, 0xFFD6DCD1, 0xFF7D9A78, 0xFFFFFFFF),
        new Palette("Notte",   true,  0xFF000000, 0xFFE6E6E6, 0xFF8A8A8A, 0xFF262626, 0xFFE6E6E6, 0xFF000000),
    };

    static Palette lerp(Palette a, Palette b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        return new Palette(b.name, t < 0.5f ? a.dark : b.dark,
                mix(a.bg, b.bg, t), mix(a.fg, b.fg, t), mix(a.dim, b.dim, t),
                mix(a.line, b.line, t), mix(a.accent, b.accent, t), mix(a.onAccent, b.onAccent, t));
    }

    static int mix(int c1, int c2, float t) {
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
    static double contrast(int a, int b) {
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
    static int alpha(int c, float a) {
        if (a >= 1f) return c;
        if (a <= 0f) return c & 0x00FFFFFF;
        int al = Math.round((c >>> 24) * a);
        return (c & 0x00FFFFFF) | (al << 24);
    }
}
