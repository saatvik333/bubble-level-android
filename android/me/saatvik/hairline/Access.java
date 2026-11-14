package me.saatvik.hairline;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

/**
 * Accessibility and keyboard for a single custom-drawn view. Every target in {@link Hits} is a
 * virtual node (TalkBack, Switch Access, Voice Access reach each control), and the keyboard or
 * D-pad moves a focus ring over the same targets: arrows by position, Tab in reading order,
 * Enter / Space / centre to activate, Escape for back. The app answers per target through {@link Nodes}.
 *
 * The view wires it up: return it from getAccessibilityNodeProvider; forward dispatchHoverEvent,
 * onFocusChanged, onKeyDown and onKeyUp; call {@link #afterFrame} after each draw; clear
 * {@code hits.focused} on touch down. The view must be focusable.
 */
public final class Access extends AccessibilityNodeProvider {
    /** What the app says and does for its targets. Settings targets can answer from {@link SettingsPage}. */
    public interface Nodes {
        /** Spoken text of target {@code id}. */
        String label(int id);

        /** {@link Hits#BUTTON}, {@link Hits#SWITCH}, {@link Hits#RADIO} or {@link Hits#TEXT}. */
        int role(int id);

        /** On, for a switch; chosen, for an option. */
        boolean checked(int id);

        /** Click, Enter, Space or D-pad centre on target {@code id}. */
        void activate(int id);

        /** Escape or system back: close what is open; false when nothing is. */
        boolean back();

        /** Whether activating {@code id} does something now; text only reads. */
        default boolean acts(int id) {
            return role(id) != Hits.TEXT;
        }

        /** What activating {@code id} does, when its label does not say it; null for none. */
        default CharSequence clickLabel(int id) {
            return null;
        }

        /** Whether a held Enter key repeats {@code id}, like a held stepper chevron. */
        default boolean repeats(int id) {
            return false;
        }
    }

    /** A live region says its changed text at most this often; the settled text follows. */
    public static final long LIVE_GAP_NS = 1_500_000_000L;
    private static final int NONE = Integer.MIN_VALUE;
    private static final String[] CLASS = {
        "android.widget.Button", "android.widget.Switch", "android.widget.RadioButton", "android.widget.TextView",
    };

    private final View view;
    private final Hits hits;
    private final Nodes nodes;
    private final AccessibilityManager am;
    private final float dp;
    /** The settings list on screen, scrolled by services and by arrow keys past its last visible row; null when none. */
    public SettingsPage list;

    private int a11yFocused = NONE, hovered = NONE, hitSig;
    private int liveId;
    private String liveSaid = "";
    private long liveNs;
    private float focusX, focusY;
    private int focusAfterPage = -1;      // see pageChanged; -1 when none is pending
    private int clicked;                  // see click; 0 when none is pending
    private final Rect r = new Rect();
    private final int[] loc = new int[2];

    public Access(View view, Hits hits, Nodes nodes) {
        this.view = view;
        this.hits = hits;
        this.nodes = nodes;
        Context c = view.getContext();
        am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        dp = view.getResources().getDisplayMetrics().density;
    }

    private boolean enabled() {
        return am != null && am.isEnabled();
    }

    /**
     * Says {@code s} once, for moments (a mode switched, a step done, a refusal), not for text left
     * on screen: that belongs in a label or a {@link #live} region.
     */
    public void announce(CharSequence s) {
        if (enabled()) view.announceForAccessibility(s);
    }

    /**
     * Makes target {@code id} a polite live region whose text is {@code text}; call every frame (0
     * for none). A change is said at once if the last was {@link #LIVE_GAP_NS} ago; otherwise this
     * returns true, and the caller draws again in a moment so the settled text is said.
     */
    public boolean live(int id, String text, long nowNs) {
        liveId = id;
        if (id == 0 || !enabled() || text.equals(liveSaid)) return false;
        if (nowNs - liveNs <= LIVE_GAP_NS) return true;
        liveSaid = text;
        liveNs = nowNs;
        send(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        return false;
    }

    /**
     * The app switched pages. Keyboard focus, if shown, moves to target {@code id} once it is on
     * screen, or with 0 to the new page's first target once the old one's are gone: the first
     * target when a page opens, the control that opened it when it closes.
     */
    public void pageChanged(int id) {
        focusAfterPage = id;
    }

    /** After each draw: keeps keyboard focus on a target that still exists and tells services when targets change. */
    public void afterFrame() {
        int click = clicked;
        clicked = 0;
        if (focusAfterPage >= 0) {
            if (hits.focused == 0) {
                focusAfterPage = -1;
            } else if (focusAfterPage == 0 ? hits.find(hits.focused) == null : hits.find(focusAfterPage) != null) {
                hits.focused = focusAfterPage == 0 ? first() : focusAfterPage;
                focusAfterPage = -1;
                focusMoved();
            }
        }
        if (hits.focused != 0) {
            Hits.Hit f = hits.find(hits.focused);
            if (f == null) {
                hits.focused = nearest(focusX, focusY);     // scrolled away, or a control came and went
                if (hits.focused != 0) view.invalidate();
            } else {
                focusX = f.rect.centerX();
                focusY = f.rect.centerY();
            }
        }
        if (!enabled()) return;
        if (a11yFocused != NONE && hits.find(a11yFocused) == null) a11yFocused = NONE;
        if (hovered != NONE && hits.find(hovered) == null) hovered = NONE;
        int sig = 17;
        for (int i = 0; i < hits.count; i++) sig = sig * 31 + hits.hits[i].id;
        if (list != null) sig = sig * 31 + Math.round(list.scroll / dp);   // bounds moved
        ViewParent parent = view.getParent();
        if (sig != hitSig && parent != null) {
            hitSig = sig;
            AccessibilityEvent ev = event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            view.onInitializeAccessibilityEvent(ev);
            ev.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
            parent.requestSendAccessibilityEvent(view, ev);
        }
        if (click != 0) send(click, AccessibilityEvent.TYPE_VIEW_CLICKED);
    }

    /**
     * Activates target {@code id} and reports the click once the next frame is drawn: settings
     * rows answer label and checked state from what was last drawn, so a report made now would
     * carry the state from before the click.
     */
    private void click(int id) {
        nodes.activate(id);
        clicked = id;
        view.invalidate();
    }

    // ---- nodes -------------------------------------------------------------------------------

    private boolean scrollable() {
        return list != null && list.maxScroll > 0f;
    }

    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
        if (id == HOST_VIEW_ID) {
            AccessibilityNodeInfo info = Build.VERSION.SDK_INT >= 30 ? new AccessibilityNodeInfo(view)
                    : AccessibilityNodeInfo.obtain(view);     // pooled before 30, deprecated from 33
            view.onInitializeAccessibilityNodeInfo(info);
            for (int i = 0; i < hits.count; i++) info.addChild(view, hits.hits[i].id);
            if (scrollable()) {
                // rows below the fold are reached by scrolling, like a ScrollView
                info.setClassName("android.widget.ScrollView");
                info.setScrollable(true);
                if (list.scroll > 0f) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                if (list.scroll < list.maxScroll) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
            return info;
        }
        Hits.Hit h = hits.find(id);
        if (h == null) return null;
        AccessibilityNodeInfo info = Build.VERSION.SDK_INT >= 30 ? new AccessibilityNodeInfo(view, id)
                : AccessibilityNodeInfo.obtain(view, id);
        info.setPackageName(view.getContext().getPackageName());
        int role = nodes.role(id);
        info.setClassName(CLASS[role]);
        info.setContentDescription(nodes.label(id));
        if (role == Hits.SWITCH || role == Hits.RADIO) {
            info.setCheckable(true);
            boolean on = nodes.checked(id);
            if (Build.VERSION.SDK_INT >= 36) {
                info.setChecked(on ? AccessibilityNodeInfo.CHECKED_STATE_TRUE : AccessibilityNodeInfo.CHECKED_STATE_FALSE);
            } else {
                info.setChecked(on);
            }
        }
        info.setParent(view);
        r.set(Math.round(h.rect.left), Math.round(h.rect.top), Math.round(h.rect.right), Math.round(h.rect.bottom));
        if (Build.VERSION.SDK_INT < 29) info.setBoundsInParent(r);   // services read only screen bounds from 29
        view.getLocationOnScreen(loc);
        r.offset(loc[0], loc[1]);
        info.setBoundsInScreen(r);
        info.setVisibleToUser(true);
        info.setEnabled(true);
        info.setFocusable(true);
        if (id == liveId) info.setLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (nodes.acts(id)) {
            info.setClickable(true);
            CharSequence what = nodes.clickLabel(id);
            info.addAction(what == null ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK
                    : new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, what));
        }
        if (a11yFocused == id) {
            info.setAccessibilityFocused(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
        } else {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        }
        return info;
    }

    @Override
    public boolean performAction(int id, int action, Bundle args) {
        if (id == HOST_VIEW_ID) {
            if (scrollable() && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                if (!list.page(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1)) return false;
                view.invalidate();
                view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                return true;
            }
            return view.performAccessibilityAction(action, args);
        }
        if (hits.find(id) == null) return false;
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (a11yFocused != id) {
                    int prev = a11yFocused;
                    a11yFocused = id;
                    if (prev != NONE) send(prev, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                }
                return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (a11yFocused == id) {
                    a11yFocused = NONE;
                    send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                }
                return true;
            case AccessibilityNodeInfo.ACTION_CLICK:
                if (!nodes.acts(id)) return false;
                click(id);
                return true;
            default:
                return false;
        }
    }

    /** From View.dispatchHoverEvent: touch exploration over the targets. */
    public boolean onHover(MotionEvent e) {
        if (!enabled() || !am.isTouchExplorationEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: {
                int id = hits.at(e.getX(), e.getY());
                setHovered(id == 0 ? NONE : id);
                return id != 0;
            }
            case MotionEvent.ACTION_HOVER_EXIT:
                if (hovered != NONE) {
                    setHovered(NONE);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void setHovered(int id) {
        if (id == hovered) return;
        int prev = hovered;
        hovered = id;
        if (id != NONE) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        if (prev != NONE) send(prev, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
    }

    private void send(int id, int type) {
        if (!enabled()) return;
        ViewParent parent = view.getParent();
        if (parent == null) return;
        AccessibilityEvent ev = event(type);
        ev.setPackageName(view.getContext().getPackageName());
        int role = nodes.role(id);
        ev.setClassName(CLASS[role]);
        ev.setSource(view, id);
        ev.setEnabled(true);
        ev.setContentDescription(nodes.label(id));
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            ev.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION);
        }
        if (role == Hits.SWITCH || role == Hits.RADIO) ev.setChecked(nodes.checked(id));
        parent.requestSendAccessibilityEvent(view, ev);
    }

    /** Events are no longer pooled from 33, where obtain() is deprecated; the constructor exists from 30. */
    private static AccessibilityEvent event(int type) {
        return Build.VERSION.SDK_INT >= 30 ? new AccessibilityEvent(type) : AccessibilityEvent.obtain(type);
    }

    // ---- keyboard and D-pad ------------------------------------------------------------------

    /** From View.onFocusChanged: the ring appears on the first target when the view gains focus from a key. */
    public void onFocusChanged(boolean gained) {
        if (gained && !view.isInTouchMode() && hits.focused == 0) {
            hits.focused = first();
            view.invalidate();
        } else if (!gained && hits.focused != 0) {
            hits.focused = 0;
            view.invalidate();
        }
    }

    /** From View.onKeyDown; false for keys it leaves to the system (Tab past the last target, others). */
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return move(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return move(1, 0);
            case KeyEvent.KEYCODE_DPAD_UP: return move(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return move(0, 1);
            case KeyEvent.KEYCODE_TAB: return step(e.isShiftPressed() ? -1 : 1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE: {
                if (hits.focused == 0 || hits.find(hits.focused) == null) return step(1);
                int id = hits.focused;
                if (e.getRepeatCount() == 0 || nodes.repeats(id)) {
                    hits.pressed = id;
                    click(id);
                }
                return true;
            }
            case KeyEvent.KEYCODE_ESCAPE:
                return nodes.back();
            default:
                return false;
        }
    }

    /** From View.onKeyUp: releases the pressed look of an activated target. */
    public boolean onKeyUp(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (hits.pressed != 0) {
                    hits.pressed = 0;
                    view.invalidate();
                }
                return true;
            default:
                return false;
        }
    }

    /** Reading order: rows top to bottom (24 dp bands), then left to right. */
    private float order(Hits.Hit h) {
        float row = Math.round(h.rect.centerY() / (24f * dp));
        return row * 100000f + h.rect.centerX();
    }

    private int first() {
        int best = 0;
        float bo = Float.MAX_VALUE;
        for (int i = 0; i < hits.count; i++) {
            Hits.Hit h = hits.hits[i];
            if (!nodes.acts(h.id)) continue;
            float o = order(h);
            if (o < bo) {
                bo = o;
                best = h.id;
            }
        }
        return best;
    }

    private int nearest(float x, float y) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < hits.count; i++) {
            Hits.Hit h = hits.hits[i];
            if (!nodes.acts(h.id)) continue;
            float dx = h.rect.centerX() - x, dy = h.rect.centerY() - y;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = h.id;
            }
        }
        return best;
    }

    private boolean step(int dir) {
        Hits.Hit cur = hits.focused != 0 ? hits.find(hits.focused) : null;
        if (cur == null) {
            hits.focused = first();
        } else {
            float co = order(cur);
            int best = 0;
            float bo = dir > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE;
            for (int i = 0; i < hits.count; i++) {
                Hits.Hit h = hits.hits[i];
                if (h == cur || !nodes.acts(h.id)) continue;
                float o = order(h);
                if (dir > 0 ? (o > co && o < bo) : (o < co && o > bo)) {
                    bo = o;
                    best = h.id;
                }
            }
            if (best == 0) return false;                   // let the system move focus on
            hits.focused = best;
        }
        focusMoved();
        return true;
    }

    /**
     * Spatial move: the nearest target ahead whose extent overlaps the current one across the move
     * (in its beam, as Android's FocusFinder prefers), so Down from a full-width row reaches a
     * button at the end of the next; centre offset only breaks ties. Scrolls the list at its ends.
     */
    private boolean move(int dx, int dy) {
        Hits.Hit cur = hits.focused != 0 ? hits.find(hits.focused) : null;
        if (cur == null) {
            hits.focused = first();
            focusMoved();
            return true;
        }
        float x = cur.rect.centerX(), y = cur.rect.centerY();
        int best = 0;
        float bs = Float.MAX_VALUE;
        for (int i = 0; i < hits.count; i++) {
            Hits.Hit h = hits.hits[i];
            if (h == cur || !nodes.acts(h.id)) continue;
            RectF a = cur.rect, b = h.rect;
            float hx = b.centerX() - x, hy = b.centerY() - y;
            float along = hx * dx + hy * dy;
            if (along < 4f * dp) continue;
            float gap = dx != 0 ? gap(a.top, a.bottom, b.top, b.bottom) : gap(a.left, a.right, b.left, b.right);
            float score = along + 3f * gap + 0.1f * Math.abs(hx * dy - hy * dx);
            if (score < bs) {
                bs = score;
                best = h.id;
            }
        }
        if (best == 0 && dy != 0 && scrollable()) {
            // the next row is scrolled out of view: bring it in
            if (list.scrollBy(dy * 96f * dp)) view.invalidate();
            return true;
        }
        if (best != 0) {
            hits.focused = best;
            focusMoved();
        }
        return true;
    }

    /** Distance between intervals [a0, a1] and [b0, b1]; 0 when they overlap. */
    private static float gap(float a0, float a1, float b0, float b1) {
        return Math.max(0f, Math.max(a0, b0) - Math.min(a1, b1));
    }

    private void focusMoved() {
        Hits.Hit h = hits.find(hits.focused);
        if (h != null) {
            focusX = h.rect.centerX();
            focusY = h.rect.centerY();
            send(hits.focused, AccessibilityEvent.TYPE_VIEW_FOCUSED);
        }
        view.invalidate();
    }
}
