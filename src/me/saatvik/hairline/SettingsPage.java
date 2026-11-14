package me.saatvik.hairline;

import android.graphics.Canvas;

/**
 * The settings list: rows split by hairlines in a centred column at most {@link Kit#COLUMN} wide,
 * scrolling under the header. Each row is a label, an optional second line and its control. The
 * app declares the rows (each frame, or whenever they change) with the row methods after
 * {@link #clear}; {@link #draw} lays them out, draws them and registers their targets; the
 * accessibility queries answer for the ids the rows own. android.graphics only.
 *
 * Ids: a row takes its id and, for several targets, the ids after it: segmented and themes
 * {@code id + i} per option, stepper {@code id} (decrease) and {@code id + 1} (increase),
 * actions {@code id + i} per button. Leave room between row ids.
 */
public final class SettingsPage {
    private static final int SEGMENTED = 0, TOGGLE = 1, STEPPER = 2, ACTIONS = 3, THEMES = 4;

    private static final class Row {
        int kind, id;
        String label, sub, value;
        boolean subAccent;
        String[] items;
        Palette[] themes;
        int selected;   // segmented and themes: chosen index; toggle: 1 = on; actions: filled button, -1 none
        float t;        // segmented: highlight position; toggle: animated state
    }

    private Row[] rows = new Row[12];
    private int count;

    /** Scroll offset, px. Written by touch and accessibility code; clamped to the content by {@link #draw}. */
    public float scroll;
    /** Largest scroll offset for the content and window of the last draw, px. */
    public float maxScroll;
    private float viewH, dp = 1f;

    /** Starts a new list of rows. */
    public void clear() {
        count = 0;
    }

    private Row add(int kind, int id, String label, String sub) {
        if (count == rows.length) {
            Row[] n = new Row[count * 2];
            System.arraycopy(rows, 0, n, 0, count);
            rows = n;
        }
        Row r = rows[count];
        if (r == null) r = rows[count] = new Row();
        count++;
        r.kind = kind;
        r.id = id;
        r.label = label;
        r.sub = sub;
        r.subAccent = false;
        r.value = null;
        r.items = null;
        r.themes = null;
        r.selected = -1;
        r.t = 0f;
        return r;
    }

    /** One choice from a few short options, all visible; {@code pos} is {@code selected}, animated. */
    public void segmented(int id, String label, String sub, String[] options, int selected, float pos) {
        Row r = add(SEGMENTED, id, label, sub);
        r.items = options;
        r.selected = selected;
        r.t = pos;
    }

    /** On/off; {@code t} is {@code on}, animated. The whole row is the target. */
    public void toggle(int id, String label, String sub, boolean on, float t) {
        Row r = add(TOGGLE, id, label, sub);
        r.selected = on ? 1 : 0;
        r.t = t;
    }

    /** A value from a long range, stepped with chevrons either side of it: {@code id} lowers, {@code id + 1} raises. */
    public void stepper(int id, String label, String sub, String value) {
        Row r = add(STEPPER, id, label, sub);
        r.value = value;
    }

    /**
     * Pill buttons for the row: outlined beside the text, or, when one of them is {@code filled}
     * (an in-place confirmation: the question as an accent second line, the safe answer first
     * and the destructive one last and filled), under the text. An empty array leaves the text alone.
     * {@code subAccent} marks the second line as a question or an error.
     */
    public void actions(int id, String label, String sub, boolean subAccent, String[] buttons, int filled) {
        Row r = add(ACTIONS, id, label, sub);
        r.subAccent = subAccent;
        r.items = buttons;
        r.selected = filled;
    }

    /** The themes as swatches; the second line names the selected one. Last row by convention. */
    public void themes(int id, String label, Palette[] themes, int selected) {
        Row r = add(THEMES, id, label, themes.length > 0 ? themes[selected].name : null);
        r.themes = themes;
        r.selected = selected;
    }

    // ---- layout ------------------------------------------------------------------------------

    private static float pad(Kit k) {
        return Kit.PAD * k.dp;
    }

    private static float swatchD(Kit k, float w, int n) {
        return n == 0 ? 0f : Math.min(40f * k.dp, (w - (n - 1) * 8f * k.dp) / n);
    }

    private static boolean confirm(Row r) {
        return r.kind == ACTIONS && r.selected >= 0;
    }

    /** Width of each button in an actions row; all buttons of a row share it. */
    private static float buttonW(Kit k, Row r) {
        k.text.setTypeface(k.regular);
        k.text.setTextSize(Kit.CONTROL * k.sp);
        float tw = 0f;
        for (String b : r.items) tw = Math.max(tw, k.text.measureText(b));
        return confirm(r) ? Math.max(88f * k.dp, tw + 48f * k.dp) : Math.max(72f * k.dp, tw + 40f * k.dp);
    }

    private static float height(Kit k, Row r, float w) {
        float lab = k.rowTextH(r.sub != null);
        switch (r.kind) {
            case SEGMENTED: return 2f * pad(k) + lab + 12f * k.dp + k.segmentH();
            case TOGGLE: return 2f * pad(k) + Math.max(lab, Kit.SWITCH_H * k.dp);
            case STEPPER:
                return 2f * pad(k) + (beside(k, r, w) ? Math.max(lab, k.segmentH()) : lab + 12f * k.dp + k.segmentH());
            case ACTIONS:
                return 2f * pad(k) + (confirm(r) ? lab + 12f * k.dp + Kit.CONTROL_H * k.dp
                        : Math.max(lab, Kit.CONTROL_H * k.dp));
            default: return 2f * pad(k) + k.rowTextH(true) + 14f * k.dp + swatchD(k, w, r.themes.length);
        }
    }

    // ---- drawing -----------------------------------------------------------------------------

    /**
     * Draws the rows between {@code viewTop} and {@code viewBottom} (the list scrolls under the
     * header) in the horizontal safe area [left, left + usable), with targets while {@code active}.
     * The caller sets the layer's alpha and translation on {@code k}.
     */
    public void draw(Canvas c, Kit k, float left, float usable, float viewTop, float viewBottom, boolean active) {
        float dp = this.dp = k.dp;
        float w = Math.min(usable - 48f * dp, Kit.COLUMN * dp);
        float x0 = left + (usable - w) / 2f;
        float y = viewTop + 8f * dp;
        float content = 0f;
        for (int i = 0; i < count; i++) content += height(k, rows[i], w);
        float contentBottom = y + content + 24f * dp;
        viewH = viewBottom - viewTop;
        maxScroll = Math.max(0f, contentBottom - viewBottom);
        scroll = Motion.clamp(scroll, 0f, maxScroll);

        c.save();
        c.clipRect(left, viewTop, left + usable, viewBottom);
        c.translate(0f, -scroll);
        float saveDy = k.hitDy;
        k.hitDy -= scroll;
        k.clipHits(viewTop + saveDy, viewBottom + saveDy);
        for (int i = 0; i < count; i++) {
            if (i > 0) k.rule(c, x0, w, y);
            Row r = rows[i];
            float h = height(k, r, w);
            switch (r.kind) {
                case SEGMENTED: segmented(c, k, r, x0, w, y, active); break;
                case TOGGLE: toggle(c, k, r, x0, w, y, h, active); break;
                case STEPPER: stepper(c, k, r, x0, w, y, h, active); break;
                case ACTIONS: actions(c, k, r, x0, w, y, h, active); break;
                default: themes(c, k, r, x0, w, y, active); break;
            }
            y += h;
        }
        c.restore();
        k.hitDy = saveDy;
        k.unclipHits();
        k.scrollThumb(c, left + usable - 6f * dp, viewTop, viewBottom, contentBottom, scroll, maxScroll);
    }

    private static void segmented(Canvas c, Kit k, Row r, float x0, float w, float y, boolean active) {
        float top = y + pad(k);
        k.rowText(c, r.label, r.sub, false, x0, top, w);
        k.segmented(c, r.items, r.t, r.id, x0, w, top + k.rowTextH(r.sub != null) + 12f * k.dp, active);
    }

    private static void toggle(Canvas c, Kit k, Row r, float x0, float w, float y, float rowH, boolean active) {
        float dp = k.dp, tw = Kit.SWITCH_W * dp;
        float h = rowH - 2f * pad(k), top = y + pad(k), cy = top + h / 2f;
        k.rowText(c, r.label, r.sub, false, x0, top + (h - k.rowTextH(r.sub != null)) / 2f, w - tw - 16f * dp);
        k.toggle(c, r.t, x0 + w, cy);
        if (active) k.hitCircle(r.id, x0, y, x0 + w, y + rowH, x0 + w - tw / 2f, cy, 30f * dp);
    }

    /**
     * Whether a stepper's label and second line fit beside it at full size. A stepper is wide and
     * its label long ("Reference pitch"), so in narrow windows or large text the control goes under
     * the text instead of shrinking it.
     */
    private static boolean beside(Kit k, Row r, float w) {
        float room = w - k.stepperW(r.value) - 16f * k.dp;
        k.text.setTypeface(k.regular);
        k.text.setTextSize(Kit.LABEL * k.sp);
        if (k.text.measureText(r.label) > room) return false;
        k.text.setTextSize(Kit.SUB * k.sp);
        return r.sub == null || k.text.measureText(r.sub) <= room;
    }

    private static void stepper(Canvas c, Kit k, Row r, float x0, float w, float y, float rowH, boolean active) {
        float h = rowH - 2f * pad(k), top = y + pad(k), sw = k.stepperW(r.value);
        if (!beside(k, r, w)) {
            k.rowText(c, r.label, r.sub, false, x0, top, w);
            k.stepper(c, r.value, x0 + sw, top + k.rowTextH(r.sub != null) + 12f * k.dp + k.segmentH() / 2f, r.id, active);
            return;
        }
        k.rowText(c, r.label, r.sub, false, x0, top + (h - k.rowTextH(r.sub != null)) / 2f, w - sw - 16f * k.dp);
        k.stepper(c, r.value, x0 + w, top + h / 2f, r.id, active);
    }

    private static void actions(Canvas c, Kit k, Row r, float x0, float w, float y, float rowH, boolean active) {
        float dp = k.dp, top = y + pad(k), ch = Kit.CONTROL_H * dp, gap = 8f * dp;
        int n = r.items.length;
        float bw = n > 0 ? buttonW(k, r) : 0f, all = n * bw + Math.max(0, n - 1) * gap;
        if (confirm(r)) {
            // the question replaces the second line; the answers sit under it, right-aligned
            k.rowText(c, r.label, r.sub, r.subAccent, x0, top, w);
            float cy = top + k.rowTextH(r.sub != null) + 12f * dp + ch / 2f;
            for (int i = 0; i < n; i++) {
                k.pill(c, r.items[i], x0 + w - all + i * (bw + gap) + bw / 2f, cy, r.id + i, active, i == r.selected, bw, ch);
            }
            return;
        }
        float h = rowH - 2f * pad(k), cy = top + h / 2f;
        k.rowText(c, r.label, r.sub, r.subAccent, x0, top + (h - k.rowTextH(r.sub != null)) / 2f,
                w - (n > 0 ? all + 16f * dp : 0f));
        for (int i = 0; i < n; i++) {
            k.pill(c, r.items[i], x0 + w - all + i * (bw + gap) + bw / 2f, cy, r.id + i, active, false, bw, ch);
        }
    }

    private static void themes(Canvas c, Kit k, Row r, float x0, float w, float y, boolean active) {
        float dp = k.dp, top = y + pad(k);
        int n = r.themes.length;
        k.rowText(c, r.label, r.sub, false, x0, top, w);
        float d = swatchD(k, w, n), rad = d / 2f;
        float gap = n > 1 ? Math.min(16f * dp, (w - n * d) / (n - 1)) : 0f;
        float cy = top + k.rowTextH(true) + 14f * dp + rad;
        for (int i = 0; i < n; i++) {
            float cx = x0 + rad + i * (d + gap);
            k.swatch(c, r.themes[i], cx, cy, rad, i == r.selected);
            float hw = (d + gap) / 2f, hh = Math.max(24f * dp, rad + 6f * dp);
            if (active) k.hitCircle(r.id + i, cx - hw, cy - hh, cx + hw, cy + hh, cx, cy, rad + 6f * dp);
        }
    }

    // ---- scrolling ---------------------------------------------------------------------------

    /** Scrolls by {@code dy} px within the content; false when already at that end. */
    public boolean scrollBy(float dy) {
        float to = Motion.clamp(scroll + dy, 0f, maxScroll);
        if (to == scroll) return false;
        scroll = to;
        return true;
    }

    /** Scrolls most of a screen forward (dir 1) or back (-1), as accessibility services ask. */
    public boolean page(int dir) {
        return scrollBy(dir * Math.max(48f * dp, viewH * 0.8f));
    }

    // ---- accessibility -----------------------------------------------------------------------

    private Row owner(int id) {
        for (int i = 0; i < count; i++) {
            Row r = rows[i];
            int n = r.kind == TOGGLE ? 1 : r.kind == STEPPER ? 2 : r.kind == THEMES ? r.themes.length : r.items.length;
            if (id >= r.id && id < r.id + n) return r;
        }
        return null;
    }

    /** What a screen reader says for target {@code id}; null when no row owns it. */
    public String label(int id) {
        Row r = owner(id);
        if (r == null) return null;
        int i = id - r.id;
        switch (r.kind) {
            case SEGMENTED: return r.label + ", " + r.items[i];
            case TOGGLE: return r.label;
            case STEPPER: return r.label + ", " + r.value + (i == 0 ? ". Decrease" : ". Increase");
            case ACTIONS: return r.items[i] + ", " + r.label + (r.sub != null ? ". " + r.sub : "");
            default: return r.label + " " + r.themes[i].name;
        }
    }

    /** {@link Hits} role of target {@code id}, -1 when no row owns it. */
    public int role(int id) {
        Row r = owner(id);
        if (r == null) return -1;
        switch (r.kind) {
            case SEGMENTED:
            case THEMES: return Hits.RADIO;
            case TOGGLE: return Hits.SWITCH;
            default: return Hits.BUTTON;
        }
    }

    /** Whether a switch is on or an option is the chosen one. */
    public boolean checked(int id) {
        Row r = owner(id);
        if (r == null) return false;
        switch (r.kind) {
            case SEGMENTED:
            case THEMES: return id - r.id == r.selected;
            case TOGGLE: return r.selected == 1;
            default: return false;
        }
    }

    /** Stepper chevrons repeat while held (a key held down, a finger left on them). */
    public boolean repeats(int id) {
        Row r = owner(id);
        return r != null && r.kind == STEPPER;
    }
}
