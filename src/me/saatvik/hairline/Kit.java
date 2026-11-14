package me.saatvik.hairline;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Drawing primitives of the design system, android.graphics only so the JVM preview compiles it.
 * One per renderer. {@link #begin} starts a frame (palette, scale, an emptied hit table); the
 * primitives draw with the public paints and register their targets through {@link #hit} and
 * friends, which apply the layer translation and clip. Nothing here allocates while drawing.
 *
 * Paint state convention: {@link #text} is centre-aligned between calls; primitives that change
 * the alignment put it back.
 */
public final class Kit {
    /** Type scale, sp. */
    public static final float TITLE = 16f, LABEL = 16f, SUB = 13f, CONTROL = 14f;
    /** Sizes, dp: row padding, control height (pill, segment, stepper), touch target, list column, switch. */
    public static final float PAD = 16f, CONTROL_H = 40f, TOUCH = 48f, COLUMN = 480f, SWITCH_W = 48f, SWITCH_H = 28f;
    /** Focus ring stroke and its gap outside the press shape, dp. */
    public static final float FOCUS_W = 2f, FOCUS_GAP = 3f;
    /**
     * Text on a fixed page (an instrument and its controls) follows the system font scale up to
     * this factor; layouts are checked at it. Scrolling pages ({@link #fullText}) follow it fully.
     */
    public static final float MAX_FONT_SCALE = 1.3f;
    /** Most lines {@link #rowText} wraps a label or second line onto; the last takes the rest, shrunk. */
    static final int MAX_LINES = 6;

    public final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final Typeface light, regular;

    // frame state, set by begin()
    public float dp = 1f, sp = 1f;
    public Palette pal = Palette.BUILT_IN[0];
    public Hits hits;
    public Frame frame;
    /** Alpha multiplier of the layer being drawn; {@link #col} applies it. */
    public float a = 1f;
    /** Canvas translation of the layer being drawn; added to hit rectangles. */
    public float hitDy;
    /**
     * Whether text in the layer being drawn follows the system font scale fully, as on a page that
     * scrolls; otherwise it stops at {@link #MAX_FONT_SCALE}. {@link #px} applies it.
     */
    public boolean fullText;

    private float clipT = Float.NEGATIVE_INFINITY, clipB = Float.POSITIVE_INFINITY;
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final Path path = new Path();
    private final RectF r = new RectF();

    /** Where a wrapped string breaks; kept for as long as its string, width and font stay the same. */
    private static final class Wrap {
        String src, first, second;
        float maxW, size;
        Typeface tf;
        boolean one;
    }

    private final Wrap[] wraps = new Wrap[16];
    private int wrapNext;

    /** Where {@link #rowText} breaks a string into lines; kept like {@link Wrap}. */
    private static final class Para {
        String src;
        float maxW, size;
        Typeface tf;
        int n;
        final String[] lines = new String[MAX_LINES];
    }

    private final Para[] paras = new Para[24];
    private int paraNext;

    public Kit(Typeface light, Typeface regular) {
        this.light = light;
        this.regular = regular;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Starts a frame: palette, scale, an emptied hit table; resets layer alpha, translation, text scale and clip. */
    public void begin(Frame f) {
        frame = f;
        hits = f.hits;
        pal = f.pal;
        dp = f.dp;
        sp = f.sp;
        hits.reset();
        a = 1f;
        hitDy = 0f;
        fullText = false;
        unclipHits();
    }

    /** Text size {@code size} sp in px for the layer being drawn (see {@link #fullText}). */
    public float px(float size) {
        return fullText ? frame.fullPx(size) : size * sp;
    }

    /** Targets outside [top, bottom) (screen px) are cut to it or dropped: the visible part of a scrolled list. */
    public void clipHits(float top, float bottom) {
        clipT = top;
        clipB = bottom;
    }

    public void unclipHits() {
        clipT = Float.NEGATIVE_INFINITY;
        clipB = Float.POSITIVE_INFINITY;
    }

    public int col(int c) {
        return Palette.alpha(c, a);
    }

    public int col(int c, float extra) {
        return Palette.alpha(c, a * extra);
    }

    // ---- targets -----------------------------------------------------------------------------

    /** A target without press feedback; focus draws a rounded box inside it. */
    public boolean hit(int id, float l, float t, float r, float b) {
        return add(id, l, t, r, b, Hits.AREA, 0f, 0f, 0f, 0f);
    }

    public boolean hitCircle(int id, float l, float t, float r, float b, float cx, float cy, float radius) {
        return add(id, l, t, r, b, Hits.CIRCLE, cx, cy, radius, radius);
    }

    public boolean hitPill(int id, float l, float t, float r, float b, float cx, float cy, float halfW, float halfH) {
        return add(id, l, t, r, b, Hits.PILL, cx, cy, halfW, halfH);
    }

    private boolean add(int id, float l, float t, float r, float b, int shape, float cx, float cy, float rx, float ry) {
        t += hitDy;
        b += hitDy;
        cy += hitDy;
        if (t < clipT) t = clipT;
        if (b > clipB) b = clipB;
        if (b - t < 8f * dp) return false;           // scrolled out of view
        return hits.add(id, l, t, r, b, shape, cx, cy, rx, ry);
    }

    /** Focus ring and pressed overlay, drawn last, over everything, at full opacity. */
    public void feedback(Canvas c) {
        Hits.Hit f = hits.focused != 0 ? hits.find(hits.focused) : null;
        if (f != null) {
            float g = FOCUS_GAP * dp;
            stroke.setStrokeWidth(FOCUS_W * dp);
            stroke.setColor(pal.accentStroke);
            if (f.shape == Hits.CIRCLE) {
                c.drawCircle(f.cx, f.cy, f.rx + g, stroke);
            } else if (f.shape == Hits.PILL) {
                r.set(f.cx - f.rx - g, f.cy - f.ry - g, f.cx + f.rx + g, f.cy + f.ry + g);
                c.drawRoundRect(r, f.ry + g, f.ry + g, stroke);
            } else {
                r.set(f.rect.left + g, f.rect.top + g, f.rect.right - g, f.rect.bottom - g);
                c.drawRoundRect(r, 10f * dp, 10f * dp, stroke);
            }
        }
        Hits.Hit h = hits.pressed != 0 ? hits.find(hits.pressed) : null;
        if (h == null || h.shape == Hits.AREA) return;
        fill.setColor(Palette.alpha(pal.fg, 0.08f));
        if (h.shape == Hits.CIRCLE) {
            c.drawCircle(h.cx, h.cy, h.rx, fill);
        } else {
            r.set(h.cx - h.rx, h.cy - h.ry, h.cx + h.rx, h.cy + h.ry);
            c.drawRoundRect(r, h.ry, h.ry, fill);
        }
    }

    // ---- components --------------------------------------------------------------------------

    /**
     * Centred title with a back chevron at the left; the back target is registered while
     * {@code active}. The title heads a page that scrolls, so it follows the full text scale,
     * shrunk only to keep clear of the chevron.
     */
    public void header(Canvas c, String title, float left, float cx, float cy, int backId, boolean active) {
        float bx = left + 30f * dp;
        if (title != null) {
            text.setTypeface(regular);
            text.setTextSize(frame.fullPx(TITLE));
            text.setColor(col(pal.fg));
            fitCentered(c, title, cx, cy, 2f * (cx - bx - 30f * dp));
        }
        chevron(c, bx, cy, -1, 6f * dp, 1.5f * dp, col(pal.fg));
        if (active) hitCircle(backId, left, cy - 28f * dp, bx + 30f * dp, cy + 28f * dp, bx, cy, 22f * dp);
    }

    /**
     * Pill button of height {@code h}, at least 112 dp wide unless {@code maxW} is smaller (never
     * under 72 dp). Filled: accent with onAccentText, the one primary or destructive answer.
     * Outlined: everything else. The target is at least {@link #TOUCH} tall.
     */
    public void pill(Canvas c, String label, float cx, float cy, int id, boolean active, boolean filled,
                     float maxW, float h) {
        text.setTypeface(regular);
        // full-height pills follow the font scale; compact ones keep their text inside
        float size = h >= CONTROL_H * dp ? px(CONTROL) : Math.min(px(CONTROL), h * 0.4f);
        text.setTextSize(size);
        float tw = text.measureText(label);
        float w = Math.min(Math.max(112f * dp, tw + 48f * dp), Math.max(maxW, 72f * dp));
        r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        if (filled) {
            fill.setColor(col(pal.accent));
            c.drawRoundRect(r, h / 2f, h / 2f, fill);
            text.setColor(col(pal.onAccentText));
        } else {
            stroke.setStrokeWidth(1f * dp);
            stroke.setColor(col(pal.dim, 0.7f));
            c.drawRoundRect(r, h / 2f, h / 2f, stroke);
            text.setColor(col(pal.fg));
        }
        if (tw > w - 24f * dp && tw > 0f) text.setTextSize(size * (w - 24f * dp) / tw);
        textCentered(c, label, cx, cy);
        text.setTextSize(size);
        float ext = Math.max(6f * dp, (TOUCH * dp - h) / 2f);
        if (active) hitPill(id, r.left - 4f * dp, r.top - ext, r.right + 4f * dp, r.bottom + ext, cx, cy, w / 2f, h / 2f);
    }

    /** Height of a segmented control, stepper or pill in a list: {@link #CONTROL_H}, more when large text needs it. */
    public float controlH() {
        return Math.max(CONTROL_H * dp, px(CONTROL) + 24f * dp);
    }

    /**
     * Segmented control: every option visible in a hairline capsule, the accent highlight at
     * {@code pos} (the selected index, animated). Option i is target {@code baseId + i}.
     */
    public void segmented(Canvas c, String[] opts, float pos, int baseId, float x0, float w, float top, boolean active) {
        int n = opts.length;
        if (n == 0) return;
        float h = controlH(), sw = w / n, rr = h / 2f, in = 3f * dp;
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(col(pal.line));
        r.set(x0, top, x0 + w, top + h);
        c.drawRoundRect(r, rr, rr, stroke);
        float sx = x0 + Motion.clamp(pos, 0f, n - 1) * sw;
        r.set(sx + in, top + in, sx + sw - in, top + h - in);
        fill.setColor(col(pal.accent));
        c.drawRoundRect(r, rr - in, rr - in, fill);
        text.setTypeface(regular);
        text.setTextSize(px(CONTROL));
        for (int i = 0; i < n; i++) {
            float on = Motion.clamp(1f - Math.abs(pos - i), 0f, 1f);
            float cx = x0 + sw * (i + 0.5f);
            text.setColor(col(Palette.mix(pal.fg, pal.onAccentText, on)));
            fitCentered(c, opts[i], cx, top + h / 2f, sw - 12f * dp);
            if (active) {
                hitPill(baseId + i, x0 + sw * i, top - 4f * dp, x0 + sw * (i + 1), top + h + 4f * dp,
                        cx, top + h / 2f, sw / 2f - in, rr - in);
            }
        }
    }

    /** Width of a {@link #stepper} showing {@code value}: steady across similar values, wider for long ones. */
    public float stepperW(String value) {
        text.setTypeface(regular);
        text.setTextSize(px(CONTROL));
        return Math.max(152f * dp, text.measureText(value) + 2f * stepperEnd());
    }

    /** Room at each end of a stepper that the value keeps clear of: chevron, press circle, focus ring, 4 dp. */
    private float stepperEnd() {
        return 22f * dp + controlH() / 2f - 3f * dp + (FOCUS_GAP + FOCUS_W / 2f + 4f) * dp;
    }

    /**
     * Stepper: the value in a hairline capsule {@link #controlH} tall, chevrons at its ends, right
     * edge at {@code right}. Its left half is target {@code id} (lower), its right half {@code id + 1}.
     */
    public void stepper(Canvas c, String value, float right, float cy, int id, boolean active) {
        float sw = stepperW(value), h = controlH(), rr = h / 2f;
        float x0 = right - sw, mid = x0 + sw / 2f, lx = x0 + 22f * dp, rx = right - 22f * dp;
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(col(pal.line));
        r.set(x0, cy - rr, right, cy + rr);
        c.drawRoundRect(r, rr, rr, stroke);
        text.setColor(col(pal.fg));
        fitCentered(c, value, mid, cy, sw - 2f * stepperEnd());
        chevron(c, lx, cy, -1, 5f * dp, 1.5f * dp, col(pal.fg));
        chevron(c, rx, cy, 1, 5f * dp, 1.5f * dp, col(pal.fg));
        if (active) {
            float ext = Math.max(4f * dp, (TOUCH * dp - h) / 2f), pr = rr - 3f * dp;     // press circle
            hitCircle(id, x0, cy - rr - ext, mid, cy + rr + ext, lx, cy, pr);
            hitCircle(id + 1, mid, cy - rr - ext, right, cy + rr + ext, rx, cy, pr);
        }
    }

    /** On/off switch, {@link #SWITCH_W} x {@link #SWITCH_H}, right edge at {@code right}; t = 1 is on. */
    public void toggle(Canvas c, float t, float right, float cy) {
        float tw = SWITCH_W * dp, th = SWITCH_H * dp, rr = th / 2f, x0 = right - tw;
        r.set(x0, cy - rr, right, cy + rr);
        if (t > 0.001f) {
            fill.setColor(col(pal.accent, t));
            c.drawRoundRect(r, rr, rr, fill);
        }
        if (t < 0.999f) {
            stroke.setStrokeWidth(1.25f * dp);
            stroke.setColor(col(pal.dim, 1f - t));
            c.drawRoundRect(r, rr, rr, stroke);
        }
        fill.setColor(col(Palette.mix(pal.dim, pal.onAccent, t)));
        c.drawCircle(x0 + rr + t * (tw - th), cy, rr - 5f * dp, fill);
    }

    /**
     * One-of-many indicator of a stacked choice, centred on (cx, cy): a ring in dim, turning to an
     * accentStroke ring around an accentStroke dot as {@code t} goes to 1 (chosen).
     */
    public void radio(Canvas c, float t, float cx, float cy) {
        float rr = 10f * dp;
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(col(Palette.mix(pal.dim, pal.accentStroke, t)));
        c.drawCircle(cx, cy, rr, stroke);
        if (t > 0.001f) {
            fill.setColor(col(pal.accentStroke, t));
            c.drawCircle(cx, cy, rr * 0.5f * (0.6f + 0.4f * t), fill);
        }
    }

    /** A theme as a swatch: its bg disc, hairline edge, accent centre; a ring when selected. */
    public void swatch(Canvas c, Palette t, float cx, float cy, float radius, boolean selected) {
        fill.setColor(col(t.bg));
        c.drawCircle(cx, cy, radius, fill);
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(col(pal.dim, 0.5f));
        c.drawCircle(cx, cy, radius, stroke);
        fill.setColor(col(t.accent));
        c.drawCircle(cx, cy, radius * 0.42f, fill);
        if (selected) {
            stroke.setStrokeWidth(2f * dp);
            stroke.setColor(col(pal.accentStroke));
            c.drawCircle(cx, cy, radius + 3.5f * dp, stroke);
        }
    }

    /** Hairline across [x0, x0 + w] at y. */
    public void rule(Canvas c, float x0, float w, float y) {
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(col(pal.line));
        c.drawLine(x0, y, x0 + w, y, stroke);
    }

    /** Height of {@link #rowText} for these strings in {@code maxW}: the label's lines, then the second line's. */
    public float rowTextH(String label, String sub, float maxW) {
        text.setTypeface(regular);
        text.setTextSize(px(LABEL));
        float h = lineCount(label, maxW) * px(LABEL) * 1.3f;
        if (sub == null) return h;
        text.setTextSize(px(SUB));
        return h + 2f * dp + lineCount(sub, maxW) * px(SUB) * 1.4f;
    }

    /**
     * Row label and optional second line (dim, or accent for a question or an error), left-aligned,
     * top at y, each wrapped onto as many lines as it needs within {@code maxW}: large text grows
     * the row rather than shrinking the words.
     */
    public void rowText(Canvas c, String label, String sub, boolean subAccent, float x, float y, float maxW) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(regular);
        float ls = px(LABEL), ss = px(SUB);
        text.setTextSize(ls);
        text.setColor(col(pal.fg));
        int n = drawLines(c, label, x, y + ls, maxW, ls * 1.3f);
        if (sub != null) {
            text.setTextSize(ss);
            text.setColor(col(subAccent ? pal.accentText : pal.dim));
            drawLines(c, sub, x, y + n * ls * 1.3f + 2f * dp + ss * 1.05f, maxW, ss * 1.4f);
        }
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Scroll position hint at x for a list showing [viewTop, viewBottom) of content ending at contentBottom. */
    public void scrollThumb(Canvas c, float x, float viewTop, float viewBottom, float contentBottom,
                            float scroll, float maxScroll) {
        if (maxScroll <= 0f) return;
        float trackH = viewBottom - viewTop - 32f * dp;
        float thumbH = Math.max(24f * dp, trackH * (viewBottom - viewTop) / (contentBottom - viewTop));
        float ty = viewTop + 16f * dp + (trackH - thumbH) * (scroll / maxScroll);
        stroke.setStrokeWidth(2f * dp);
        stroke.setColor(col(pal.line));
        c.drawLine(x, ty, x, ty + thumbH, stroke);
    }

    /** Open chevron pointing left (dir -1) or right (+1), centred on (x, y), half height {@code size}. */
    public void chevron(Canvas c, float x, float y, int dir, float size, float w, int color) {
        stroke.setStrokeWidth(w);
        stroke.setColor(color);
        float tip = x + dir * size * 0.5f, back = x - dir * size * 0.5f;
        path.reset();
        path.moveTo(back, y - size);
        path.lineTo(tip, y);
        path.lineTo(back, y + size);
        c.drawPath(path, stroke);
    }

    /**
     * Bottom-bar icon state: while {@code on}, a 16 dp accent disc behind the glyph. Returns the
     * glyph colour, onAccentText on the disc, dim otherwise, with the layer alpha applied.
     */
    public int iconDisc(Canvas c, float x, float y, boolean on) {
        if (!on) return col(pal.dim);
        fill.setColor(col(pal.accent));
        c.drawCircle(x, y, 16f * dp, fill);
        return col(pal.onAccentText);
    }

    /** The settings glyph (two sliders) that opens the settings page from the bottom bar. */
    public void settingsIcon(Canvas c, float x, float y) {
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(col(pal.dim));
        c.drawLine(x - 10f * dp, y - 4.5f * dp, x + 10f * dp, y - 4.5f * dp, stroke);
        c.drawLine(x - 10f * dp, y + 4.5f * dp, x + 10f * dp, y + 4.5f * dp, stroke);
        fill.setColor(col(pal.bg));
        c.drawCircle(x - 3.5f * dp, y - 4.5f * dp, 3f * dp, fill);
        c.drawCircle(x + 4f * dp, y + 4.5f * dp, 3f * dp, fill);
        c.drawCircle(x - 3.5f * dp, y - 4.5f * dp, 3f * dp, stroke);
        c.drawCircle(x + 4f * dp, y + 4.5f * dp, 3f * dp, stroke);
    }

    // ---- text --------------------------------------------------------------------------------

    /** One line centred vertically on cy (horizontally per the paint's alignment). */
    public void textCentered(Canvas c, String s, float x, float cy) {
        text.getFontMetrics(fm);
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    /** {@link #textCentered}, shrunk to fit {@code maxW}. */
    public void fitCentered(Canvas c, String s, float x, float cy, float maxW) {
        float size = text.getTextSize(), w = text.measureText(s);
        if (w > maxW && w > 0f) text.setTextSize(size * maxW / w);
        textCentered(c, s, x, cy);
        text.setTextSize(size);
    }

    /** One line on baseline {@code base}, shrunk to fit {@code maxW}. */
    public void fitLine(Canvas c, String s, float x, float base, float maxW) {
        if (s == null || s.length() == 0) return;
        float size = text.getTextSize(), w = text.measureText(s);
        if (w > maxW && w > 0f) text.setTextSize(size * maxW / w);
        c.drawText(s, x, base, text);
        text.setTextSize(size);
    }

    /** Lines {@link #drawLines} puts {@code s} on within {@code maxW} at the paint's font and size; 0 for none. */
    public int lineCount(String s, float maxW) {
        return s == null || s.length() == 0 ? 0 : para(s, maxW).n;
    }

    /**
     * {@code s} broken at spaces onto as many lines as it needs (at most {@link #MAX_LINES}), the
     * first on baseline {@code base}, each next {@code lineH} below; a word wider than the line
     * gets a line of its own, shrunk to fit. Returns the number of lines.
     */
    public int drawLines(Canvas c, String s, float x, float base, float maxW, float lineH) {
        if (s == null || s.length() == 0) return 0;
        Para p = para(s, maxW);
        for (int i = 0; i < p.n; i++) fitLine(c, p.lines[i], x, base + i * lineH, maxW);
        return p.n;
    }

    private Para para(String s, float maxW) {
        Typeface tf = text.getTypeface();
        float size = text.getTextSize();
        for (Para p : paras) {
            if (p != null && p.maxW == maxW && p.size == size && p.tf == tf && (p.src == s || p.src.equals(s))) return p;
        }
        Para p = paras[paraNext];
        if (p == null) p = paras[paraNext] = new Para();
        paraNext = (paraNext + 1) % paras.length;
        p.src = s;
        p.maxW = maxW;
        p.size = size;
        p.tf = tf;
        p.n = 0;
        int start = 0, len = s.length();
        while (start < len) {
            if (p.n == MAX_LINES - 1 || text.measureText(s, start, len) <= maxW) {
                p.lines[p.n++] = s.substring(start);
                break;
            }
            int cut = -1;
            for (int i = s.indexOf(' ', start + 1); i > 0; i = s.indexOf(' ', i + 1)) {
                if (text.measureText(s, start, i) <= maxW) cut = i;
                else break;
            }
            if (cut < 0) cut = s.indexOf(' ', start + 1);        // a word wider than the line: alone, shrunk
            if (cut < 0) {
                p.lines[p.n++] = s.substring(start);
                break;
            }
            p.lines[p.n++] = s.substring(start, cut);
            start = cut + 1;
        }
        return p;
    }

    /** Lines {@link #wrapped} draws {@code s} on: 2 when it breaks, else 1 (text with no break that fits is shrunk onto one). */
    public int lines(String s, float maxW) {
        return wrap(s, maxW).first != null ? 2 : 1;
    }

    /**
     * Text wrapped onto at most two lines, the first on baseline {@code base}, the second
     * {@code lineH} below and shrunk to fit. Breaks at a sentence when both halves fit, else at
     * the last word that fits.
     */
    public void wrapped(Canvas c, String s, float x, float base, float maxW, float lineH) {
        if (s == null || s.length() == 0) return;
        Wrap k = wrap(s, maxW);
        if (k.one) {
            c.drawText(s, x, base, text);
        } else if (k.first == null) {
            fitLine(c, s, x, base, maxW);
        } else {
            c.drawText(k.first, x, base, text);
            fitLine(c, k.second, x, base + lineH, maxW);
        }
    }

    /** {@link #wrapped}, its one or two lines centred vertically on {@code midY}. */
    public void wrappedCentered(Canvas c, String s, float x, float midY, float maxW, float lineH) {
        if (s == null || s.length() == 0) return;
        text.getFontMetrics(fm);
        float half = -(fm.ascent + fm.descent) / 2f;
        wrapped(c, s, x, midY + half - (lines(s, maxW) - 1) * lineH / 2f, maxW, lineH);
    }

    /** Where {@code s} breaks at the paint's current font and size; remembered, since text rarely changes between frames. */
    private Wrap wrap(String s, float maxW) {
        Typeface tf = text.getTypeface();
        float size = text.getTextSize();
        for (Wrap k : wraps) {
            if (k != null && k.maxW == maxW && k.size == size && k.tf == tf && (k.src == s || k.src.equals(s))) return k;
        }
        Wrap k = wraps[wrapNext];
        if (k == null) k = wraps[wrapNext] = new Wrap();
        wrapNext = (wrapNext + 1) % wraps.length;
        k.src = s;
        k.maxW = maxW;
        k.size = size;
        k.tf = tf;
        k.first = k.second = null;
        k.one = text.measureText(s) <= maxW;
        if (k.one) return k;
        int cut = -1;
        for (int i = s.indexOf(' '); i > 0; i = s.indexOf(' ', i + 1)) {
            if (text.measureText(s, 0, i) <= maxW) cut = i;
            else break;
        }
        // prefer a sentence boundary when both halves fit: no stranded words
        for (int i = s.indexOf(". "); i > 0; i = s.indexOf(". ", i + 1)) {
            if (text.measureText(s, 0, i + 1) <= maxW && text.measureText(s, i + 2, s.length()) <= maxW) {
                cut = i + 1;
                break;
            }
        }
        if (cut >= 0) {
            k.first = s.substring(0, cut);
            k.second = s.substring(cut + 1);
        }
        return k;
    }
}
