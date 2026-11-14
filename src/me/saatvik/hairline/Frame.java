package me.saatvik.hairline;

/**
 * The state every frame shares, written by {@code Stage} (window, metrics, preferences, theme)
 * and read by the renderer. An app's frame state extends it with its own fields. Pure Java, so
 * the JVM preview builds one by hand.
 */
public class Frame {
    /** Sizes, sp, at which {@link #textPx} samples the system's text scale. */
    static final float[] TEXT_SP = {8f, 10f, 12f, 14f, 16f, 18f, 20f, 24f, 30f, 40f, 100f};

    /** Pixels per dp; pixels per sp on pages that cap text at {@link Kit#MAX_FONT_SCALE}. */
    public float dp = 1f, sp = 1f;
    /**
     * The system's text size in px at each {@link #TEXT_SP} size, uncapped. Android 14 and later
     * scale large text less than small text (non-linear up to 2x), so pages that scroll take
     * their sizes from here through {@link #fullPx}.
     */
    public final float[] textPx = new float[TEXT_SP.length];
    public int width, height, insetL, insetT, insetR, insetB;

    public Palette pal = Palette.BUILT_IN[0];
    public Palette[] themes = Palette.BUILT_IN;
    public int themeIdx;

    /**
     * The system's animation scale: 1 normal, 0 when animations are off (reduce motion). Time
     * constants of interface motion are multiplied by it; decorative motion is skipped at 0.
     */
    public float motion = 1f;
    /** Progress of a predictive back gesture, 0 to 1; 0 when none is under way. */
    public float backT;

    public final Hits hits;
    /** The settings list; the renderer declares its rows, the view scrolls it. */
    public final SettingsPage page = new SettingsPage();

    public Frame(int targets) {
        hits = new Hits(targets);
        textScale(1f, 1f);
    }

    /** Linear text scale at {@code density} and {@code fontScale}: Android before 14, the preview, and tests. */
    public final void textScale(float density, float fontScale) {
        for (int i = 0; i < TEXT_SP.length; i++) textPx[i] = TEXT_SP[i] * density * fontScale;
        sp = density * Math.min(fontScale, Kit.MAX_FONT_SCALE);
        dp = density;
    }

    /** {@code size} sp in px at the system's full text scale, interpolated between the samples. */
    public final float fullPx(float size) {
        float[] s = TEXT_SP;
        int n = s.length;
        if (size <= s[0]) return textPx[0] * size / s[0];
        if (size >= s[n - 1]) return textPx[n - 1] * size / s[n - 1];
        int i = 1;
        while (s[i] < size) i++;
        float t = (size - s[i - 1]) / (s[i] - s[i - 1]);
        return textPx[i - 1] + (textPx[i] - textPx[i - 1]) * t;
    }
}
