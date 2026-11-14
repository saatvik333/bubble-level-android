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
 * Nothing is spoken that is not on screen. Android 16 deprecates announcements; instead a page
 * names itself ({@link #paneTitle}), a changing reading is a {@link #live} region, an error is
 * the node's error, and a control whose words change after it is used is re-read while it holds
 * accessibility focus. One-of-many options sit in a group node with collection info, so services
 * say "2 of 4".
 *
 * {@code Stage} wires it up; a view without it returns this from getAccessibilityNodeProvider,
 * forwards dispatchHoverEvent, onFocusChanged, onKeyDown and onKeyUp, calls {@link #afterFrame}
 * after each draw and clears {@code hits.focused} on touch down. The view must be focusable.
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

        /** The one-of-many group {@code id} is an option of: its first option's id (options take consecutive ids), 0 for none. */
        default int group(int id) {
            return 0;
        }

        /** Name of group {@code group}, said when a service enters it; null for none. */
        default CharSequence groupLabel(int group) {
            return null;
        }

        /** Number of options in group {@code group}, including any scrolled out of view. */
        default int groupSize(int group) {
            return 0;
        }

        /** What is wrong with target {@code id}, shown beside it on screen; null when nothing is. */
        default CharSequence error(int id) {
            return null;
        }
    }

    /** A live region says its changed text at most this often; the settled text follows. */
    public static final long LIVE_GAP_NS = 1_500_000_000L;
    private static final int NONE = Integer.MIN_VALUE;
    /** Virtual ids of group nodes: the group key with this bit set. App ids stay below it. */
    private static final int GROUP = 0x40000000;
    private static final String[] CLASS = {
        "android.widget.Button", "android.widget.Switch", "android.widget.RadioButton", "android.widget.TextView",
    };
    private static final String GROUP_CLASS = "android.widget.RadioGroup";

    private final View view;
    private final Hits hits;
    private final Nodes nodes;
    private final AccessibilityManager am;
    private final float dp;
    /** The settings list on screen, scrolled by services and by arrow keys past its last visible row; null when none. */
    public SettingsPage list;

    private int a11yFocused = NONE, hovered = NONE, hitSig, errorSig;
    private String a11yLabel;              // label of the accessibility-focused node when last said
    private int liveId;
    private String liveSaid = "";
    private long liveNs;
    private float focusX, focusY;
    private int focusAfterPage = -1;      // see pageChanged; -1 when none is pending
    private int clicked;                  // see click; 0 when none is pending
    private final Rect r = new Rect();
    private final RectF u = new RectF();
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
     * Names the page now on screen (the main page by the app's name, then "Settings", a tab by
     * its name); services say it when it changes. Replaces announcing a page switch.
     */
    public void paneTitle(CharSequence title) {
        if (Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityPaneTitle(title);
        } else if (enabled()) {
            view.announceForAccessibility(title);    // deprecated from 36; pane titles exist from 28
        }
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
        send(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, changed(id));
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

    /** After each draw: keeps keyboard focus on a target that still exists and tells services what changed. */
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
        if (a11yFocused != NONE && !exists(a11yFocused)) a11yFocused = NONE;
        if (hovered != NONE && hits.find(hovered) == null) hovered = NONE;
        int sig = 17, errs = 17;
        for (int i = 0; i < hits.count; i++) {
            int id = hits.hits[i].id;
            sig = sig * 31 + id;
            CharSequence e = nodes.error(id);
            errs = errs * 31 + (e == null ? 0 : id * 31 + e.toString().hashCode());
        }
        if (list != null) sig = sig * 31 + Math.round(list.scroll / dp);   // bounds moved
        ViewParent parent = view.getParent();
        if (sig != hitSig && parent != null) {
            hitSig = sig;
            AccessibilityEvent ev = event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            view.onInitializeAccessibilityEvent(ev);
            ev.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
            parent.requestSendAccessibilityEvent(view, ev);
        }
        if (errs != errorSig) {
            errorSig = errs;
            for (int i = 0; i < hits.count; i++) {
                int id = hits.hits[i].id;
                if (nodes.error(id) != null) {
                    send(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_ERROR | AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_INVALID);
                }
            }
        }
        if (click != 0) send(click, AccessibilityEvent.TYPE_VIEW_CLICKED, 0);
        // the words of the control under accessibility focus changed (Hold became Resume, Reset
        // became the question's safe answer): say them again
        if (a11yFocused != NONE) {
            String now = String.valueOf(label(a11yFocused));
            if (a11yLabel != null && !now.equals(a11yLabel)) {
                send(a11yFocused, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, changed(a11yFocused));
            }
            a11yLabel = now;
        }
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

    private static boolean isGroup(int id) {
        return id > 0 && (id & GROUP) != 0;
    }

    private boolean exists(int id) {
        if (!isGroup(id)) return hits.find(id) != null;
        for (int i = 0; i < hits.count; i++) if (nodes.group(hits.hits[i].id) == (id & ~GROUP)) return true;
        return false;
    }

    private CharSequence label(int id) {
        return isGroup(id) ? nodes.groupLabel(id & ~GROUP) : nodes.label(id);
    }

    /** Content change type of a node whose words changed: text for read-only text, else its description. */
    private int changed(int id) {
        return !isGroup(id) && nodes.role(id) == Hits.TEXT ? AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
                : AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION;
    }

    private AccessibilityNodeInfo node(int id) {
        return Build.VERSION.SDK_INT >= 30 ? new AccessibilityNodeInfo(view, id)
                : AccessibilityNodeInfo.obtain(view, id);    // pooled before 30, deprecated from 33
    }

    /** Screen bounds of a virtual node from its view-relative rectangle. */
    private void bounds(AccessibilityNodeInfo info, RectF rect) {
        r.set(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom));
        if (Build.VERSION.SDK_INT < 29) info.setBoundsInParent(r);   // services read only screen bounds from 29
        view.getLocationOnScreen(loc);
        r.offset(loc[0], loc[1]);
        info.setBoundsInScreen(r);
    }

    private void focusActions(AccessibilityNodeInfo info, int id) {
        if (a11yFocused == id) {
            info.setAccessibilityFocused(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
        } else {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        }
    }

    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
        if (id == HOST_VIEW_ID) {
            AccessibilityNodeInfo info = Build.VERSION.SDK_INT >= 30 ? new AccessibilityNodeInfo(view)
                    : AccessibilityNodeInfo.obtain(view);
            view.onInitializeAccessibilityNodeInfo(info);
            int last = 0;
            for (int i = 0; i < hits.count; i++) {
                int h = hits.hits[i].id, g = nodes.group(h);
                if (g == 0) info.addChild(view, h);
                else if (g != last) info.addChild(view, GROUP | g);   // a group's options are drawn together
                last = g;
            }
            if (scrollable()) {
                // rows below the fold are reached by scrolling, like a ScrollView
                info.setClassName("android.widget.ScrollView");
                info.setScrollable(true);
                if (list.scroll > 0f) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                if (list.scroll < list.maxScroll) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
            return info;
        }
        return isGroup(id) ? groupNode(id) : targetNode(id);
    }

    /** A one-of-many group: its options as children, with collection info (CollectionInfo.obtain before 33). */
    private AccessibilityNodeInfo groupNode(int id) {
        int g = id & ~GROUP, n = 0;
        boolean row = true;
        float cy = Float.NaN;
        u.setEmpty();
        AccessibilityNodeInfo info = node(id);
        for (int i = 0; i < hits.count; i++) {
            Hits.Hit h = hits.hits[i];
            if (nodes.group(h.id) != g) continue;
            info.addChild(view, h.id);
            if (n++ == 0) {
                u.set(h.rect);
                cy = h.rect.centerY();
            } else {
                u.union(h.rect);
                row &= Math.abs(h.rect.centerY() - cy) < 1f;
            }
        }
        if (n == 0) return null;
        int size = Math.max(n, nodes.groupSize(g));
        int rows = row ? 1 : size, cols = row ? size : 1;
        int mode = AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE;
        info.setCollectionInfo(Build.VERSION.SDK_INT >= 33 ? new AccessibilityNodeInfo.CollectionInfo(rows, cols, false, mode)
                : AccessibilityNodeInfo.CollectionInfo.obtain(rows, cols, false, mode));
        info.setPackageName(view.getContext().getPackageName());
        info.setClassName(GROUP_CLASS);
        info.setContentDescription(nodes.groupLabel(g));
        info.setParent(view);
        bounds(info, u);
        info.setVisibleToUser(true);
        info.setEnabled(true);
        focusActions(info, id);
        return info;
    }

    /** A target: role, words, state, place in its group, error. */
    private AccessibilityNodeInfo targetNode(int id) {
        Hits.Hit h = hits.find(id);
        if (h == null) return null;
        AccessibilityNodeInfo info = node(id);
        info.setPackageName(view.getContext().getPackageName());
        int role = nodes.role(id);
        info.setClassName(CLASS[role]);
        if (role == Hits.TEXT) info.setText(nodes.label(id));
        else info.setContentDescription(nodes.label(id));
        if (role == Hits.SWITCH || role == Hits.RADIO) {
            info.setCheckable(true);
            boolean on = nodes.checked(id);
            if (Build.VERSION.SDK_INT >= 36) {
                info.setChecked(on ? AccessibilityNodeInfo.CHECKED_STATE_TRUE : AccessibilityNodeInfo.CHECKED_STATE_FALSE);
            } else {
                info.setChecked(on);
            }
        }
        int g = nodes.group(id);
        if (g != 0) {
            // one line of options, or one per line; options take consecutive ids from the group's first
            boolean row = true;
            for (int i = 0; i < hits.count; i++) {
                Hits.Hit o = hits.hits[i];
                if (o != h && nodes.group(o.id) == g) row &= Math.abs(o.rect.centerY() - h.rect.centerY()) < 1f;
            }
            int index = id - g;
            int ri = row ? 0 : index, ci = row ? index : 0;
            boolean sel = nodes.checked(id);
            info.setCollectionItemInfo(Build.VERSION.SDK_INT >= 33
                    ? new AccessibilityNodeInfo.CollectionItemInfo(ri, 1, ci, 1, false, sel)
                    : AccessibilityNodeInfo.CollectionItemInfo.obtain(ri, 1, ci, 1, false, sel));
            info.setParent(view, GROUP | g);
        } else {
            info.setParent(view);
        }
        CharSequence err = nodes.error(id);
        if (err != null) {
            info.setContentInvalid(true);
            info.setError(err);
        }
        bounds(info, h.rect);
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
        focusActions(info, id);
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
        if (!exists(id)) return false;
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (a11yFocused != id) {
                    int prev = a11yFocused;
                    a11yFocused = id;
                    a11yLabel = String.valueOf(label(id));
                    if (prev != NONE) send(prev, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, 0);
                    send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, 0);
                }
                return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (a11yFocused == id) {
                    a11yFocused = NONE;
                    send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, 0);
                }
                return true;
            case AccessibilityNodeInfo.ACTION_CLICK:
                if (isGroup(id) || !nodes.acts(id)) return false;
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
        if (id != NONE) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, 0);
        if (prev != NONE) send(prev, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT, 0);
    }

    /** Sends a {@code type} event from node {@code id}; {@code changes} are its content change types, if any. */
    private void send(int id, int type, int changes) {
        if (!enabled()) return;
        ViewParent parent = view.getParent();
        if (parent == null) return;
        AccessibilityEvent ev = event(type);
        ev.setPackageName(view.getContext().getPackageName());
        ev.setSource(view, id);
        ev.setEnabled(true);
        if (isGroup(id)) {
            ev.setClassName(GROUP_CLASS);
            ev.setContentDescription(nodes.groupLabel(id & ~GROUP));
        } else {
            int role = nodes.role(id);
            ev.setClassName(CLASS[role]);
            if (role == Hits.TEXT) ev.getText().add(nodes.label(id));
            else ev.setContentDescription(nodes.label(id));
            if (role == Hits.SWITCH || role == Hits.RADIO) ev.setChecked(nodes.checked(id));
        }
        if (changes != 0) ev.setContentChangeTypes(changes);
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
            send(hits.focused, AccessibilityEvent.TYPE_VIEW_FOCUSED, 0);
        }
        view.invalidate();
    }
}
