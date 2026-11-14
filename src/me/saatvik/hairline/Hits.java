package me.saatvik.hairline;

import android.graphics.RectF;

/**
 * The frame's interactive targets, rebuilt by the renderer on every draw and read by touch,
 * keyboard and accessibility code. Each target has an id (0 means none), a touch rectangle and
 * the shape its press and focus feedback take. Ids are the app's; they must be unique per frame.
 */
public final class Hits {
    /** Feedback shapes: none (a rounded box inside the rect for focus only), circle, pill. */
    public static final int AREA = 0, CIRCLE = 1, PILL = 2;
    /** Roles, for accessibility: plain button, on/off switch, one-of-many option, read-only text. */
    public static final int BUTTON = 0, SWITCH = 1, RADIO = 2, TEXT = 3;

    public static final class Hit {
        public int id;
        public final RectF rect = new RectF();
        public int shape;
        /** Shape centre; circle radius in rx; pill half width and half height in rx, ry. */
        public float cx, cy, rx, ry;
    }

    public final Hit[] hits;
    public int count;
    /** Target under a finger or a held Enter key, 0 for none; drawn as a pressed overlay. */
    public int pressed;
    /** Keyboard focus, 0 for none; drawn as a focus ring. Cleared when the screen is touched. */
    public int focused;

    public Hits(int capacity) {
        hits = new Hit[capacity];
        for (int i = 0; i < capacity; i++) hits[i] = new Hit();
    }

    public void reset() {
        count = 0;
    }

    /** False when the table is full. */
    public boolean add(int id, float l, float t, float r, float b, int shape, float cx, float cy, float rx, float ry) {
        if (count >= hits.length) return false;
        Hit h = hits[count++];
        h.id = id;
        h.rect.set(l, t, r, b);
        h.shape = shape;
        h.cx = cx;
        h.cy = cy;
        h.rx = rx;
        h.ry = ry;
        return true;
    }

    /** Topmost target at (x, y): the one added last wins. 0 when none. */
    public int at(float x, float y) {
        for (int i = count - 1; i >= 0; i--) {
            if (hits[i].rect.contains(x, y)) return hits[i].id;
        }
        return 0;
    }

    public Hit find(int id) {
        for (int i = 0; i < count; i++) if (hits[i].id == id) return hits[i];
        return null;
    }
}
