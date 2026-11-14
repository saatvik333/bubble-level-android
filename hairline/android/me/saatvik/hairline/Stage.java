package me.saatvik.hairline;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeProvider;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * The one full-screen view an app draws on. It owns what every app needs and nothing an app
 * decides: the {@link Frame} metrics (dp, text scale, insets, size), the system's preference
 * signals (animation scale, contrast), the frame loop, touch on the hit table (press, scroll,
 * held repeat, a second finger cancels), keyboard and accessibility through {@link Access},
 * themes and their cross-fade, haptics, and back (predictive from Android 13, with gesture
 * progress from 14).
 *
 * An app extends it with {@link #step} (advance its state; true while something moves),
 * {@link #render} (draw the frame), {@link #act} (a target was activated), {@link #canGoBack}
 * and {@link Access.Nodes#back}, and answers labels for its own targets, deferring to the
 * settings page for the rest ({@code super.label(id)}). The activity calls {@link #resume} and
 * {@link #pause}, and forwards onBackPressed to {@link Access.Nodes#back} below Android 13.
 */
public abstract class Stage<F extends Frame> extends View implements Access.Nodes {
    /** Haptic kinds: a tap, a success (level reached, a step done), a refusal or failure. */
    public static final int TAP = 0, CONFIRM = 1, REJECT = 2;
    /** A held stepper's repeated step. */
    private static final int REPEAT = -1;
    /** Held repeat of a stepper chevron: first after this, then every {@link #REPEAT_MS}. */
    private static final long REPEAT_DELAY_MS = 450, REPEAT_MS = 70;
    /** A sample is drawn at once unless the frame loop drew this recently. */
    private static final long LOOP_GAP_NS = 250_000_000L;
    /** How far a back gesture previews closing: the fraction of the page transition it covers. */
    public static final float BACK_PREVIEW = 0.3f;

    protected final F ui;
    protected final Access access;
    private final float slop;

    private Palette palFrom, palTo;
    private float palT = 1f;
    private String themeName;
    private float contrast = Float.NaN;
    private boolean haptics = true;

    private long lastNs, lastDrawNs;
    private boolean running, looping;

    private int downId, repeatId;
    private boolean repeated, scrolling, multi;
    private float downX, downY, lastY;

    private OnBackInvokedCallback backCallback;
    private boolean backRegistered;

    private final Rect insets = new Rect();

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (repeatId == 0 || ui.hits.pressed != repeatId) return;
            repeated = true;
            haptic(REPEAT);
            act(repeatId);
            invalidate();
            postDelayed(this, REPEAT_MS);
        }
    };

    /** {@code theme}: the saved theme's name; a name no longer offered falls back to the first theme. */
    protected Stage(Context ctx, F ui, String theme) {
        super(ctx);
        this.ui = ui;
        access = new Access(this, ui.hits, this);
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        ui.dp = dm.density;
        // text as the system scales it (non-linear from Android 14), fixed pages capped
        for (int i = 0; i < Frame.TEXT_SP.length; i++) {
            ui.textPx[i] = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Frame.TEXT_SP[i], dm);
        }
        ui.sp = Math.min(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, dm), dm.density * Kit.MAX_FONT_SCALE);
        themeName = theme;
        readPreferences();
        setHapticFeedbackEnabled(true);
        setFocusable(true);
        // the focus ring is drawn per target; Android 8+ would otherwise wash the whole view
        if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    // ---- what the app supplies ---------------------------------------------------------------

    /** Advances the app's state by {@code dt} seconds; true while anything still moves. */
    protected abstract boolean step(float dt, long nowNs);

    /** Draws the frame from {@link #ui}. */
    protected abstract void render(Canvas c);

    /** Target {@code id} was activated (tap, key, accessibility service); haptics are done. */
    protected abstract void act(int id);

    /** Whether something is open that back closes (a page, a calibration); back leaves the app otherwise. */
    protected abstract boolean canGoBack();

    /** The theme changed to {@code p}, by the user: save its name. */
    protected void themeChanged(Palette p) {
    }

    /** The target whose text is a polite live region this frame (a changing reading), 0 for none. */
    protected int liveTarget() {
        return 0;
    }

    /** Text of the live region {@link #liveTarget} names. */
    protected String liveText(int id) {
        return label(id);
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    /** From Activity.onResume: rereads the system's animation scale and contrast, and starts drawing. */
    public void resume() {
        readPreferences();
        running = true;
        lastNs = 0;
        looping = true;
        postInvalidateOnAnimation();
    }

    /** From Activity.onPause: stops the frame loop and any held repeat. */
    public void pause() {
        running = false;
        removeCallbacks(repeater);
        repeatId = 0;
    }

    /**
     * Something outside the frame loop changed (a sensor sample, an audio frame). Draws soon,
     * unless the frame loop is running and drew recently.
     */
    public void requestFrame() {
        if (!running) return;
        if (looping && System.nanoTime() - lastDrawNs < LOOP_GAP_NS) return;
        looping = true;
        postInvalidateOnAnimation();
    }

    /** Whether the frame loop runs: a frame is on its way. */
    protected boolean looping() {
        return looping;
    }

    /** Whether the activity is resumed. */
    protected boolean running() {
        return running;
    }

    // ---- preferences and themes --------------------------------------------------------------

    /**
     * The system's animation scale ("Remove animations" sets 0) and, from Android 14, its contrast
     * level. Both change only in system settings, so they are read when the activity resumes.
     */
    private void readPreferences() {
        Context c = getContext();
        ui.motion = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        float level = 0f;
        if (Build.VERSION.SDK_INT >= 34) {
            UiModeManager um = c.getSystemService(UiModeManager.class);
            if (um != null) level = um.getContrast();
        }
        if (level == contrast) return;
        contrast = level;
        Palette[] themes = Themes.all(c);
        if (level > 0f) {
            for (int i = 0; i < themes.length; i++) themes[i] = themes[i].contrasted();
        }
        ui.themes = themes;
        ui.themeIdx = Themes.indexOf(themes, themeName);
        palFrom = palTo = ui.pal = themes[ui.themeIdx];
        palT = 1f;
        if (isAttachedToWindow()) applyWindowTheme();   // else onAttachedToWindow does it: no decor before setContentView
        invalidate();
    }

    /** Switches to theme {@code idx} of {@link Frame#themes}, cross-fading every token. */
    protected final void setTheme(int idx) {
        if (idx == ui.themeIdx || idx < 0 || idx >= ui.themes.length) return;
        ui.themeIdx = idx;
        palFrom = ui.pal;
        palTo = ui.themes[idx];
        palT = ui.motion > 0f ? 0f : 1f;
        if (palT >= 1f) ui.pal = palTo;
        themeName = palTo.name;
        applyWindowTheme();
        themeChanged(palTo);
        invalidate();
    }

    /** The theme being shown or faded to. */
    public final Palette theme() {
        return palTo;
    }

    /** Names the page now showing for accessibility services; null names the main page by the activity's title. */
    protected final void paneTitle(CharSequence title) {
        if (title == null && getContext() instanceof Activity) title = ((Activity) getContext()).getTitle();
        access.paneTitle(title);
    }

    private void applyWindowTheme() {
        Window w = getContext() instanceof Activity ? ((Activity) getContext()).getWindow() : null;
        if (w != null) Chrome.theme(w, palTo);
    }

    /**
     * {@link Motion#approach} for interface motion (pages, controls): its time constant follows
     * the system's animation scale, and with animations off the value jumps to its target.
     * Motion that carries information (an instrument's needle) uses Motion.approach directly.
     */
    protected final float approach(float v, float target, float dt, float tau) {
        return ui.motion > 0f ? Motion.approach(v, target, dt, tau * ui.motion) : target;
    }

    /** Turns tap and success haptics on or off (the app's setting). */
    protected final void setHaptics(boolean on) {
        haptics = on;
    }

    /**
     * A haptic of kind {@link #TAP}, {@link #CONFIRM} or {@link #REJECT}, mapped to the platform's
     * richest constant; the system's own touch-feedback setting still applies.
     */
    public final void haptic(int kind) {
        if (!haptics) return;
        int c;
        switch (kind) {
            case CONFIRM:
                c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
                break;
            case REJECT:
                c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS;
                break;
            case REPEAT:
                c = Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK;
                break;
            default:
                c = HapticFeedbackConstants.KEYBOARD_TAP;
        }
        performHapticFeedback(c);
    }

    /** The tap haptic for activating {@code id}: a switch says which way it turned (Android 14+). */
    private void tapHaptic(int id) {
        if (!haptics) return;
        if (Build.VERSION.SDK_INT >= 34 && role(id) == Hits.SWITCH) {
            // checked() still answers from the frame drawn before the tap
            performHapticFeedback(checked(id) ? HapticFeedbackConstants.TOGGLE_OFF : HapticFeedbackConstants.TOGGLE_ON);
        } else {
            haptic(TAP);
        }
    }

    // ---- accessibility nodes: the settings page answers for its rows ---------------------------

    @Override
    public String label(int id) {
        return ui.page.label(id);
    }

    @Override
    public int role(int id) {
        int r = ui.page.role(id);
        return r >= 0 ? r : Hits.BUTTON;
    }

    @Override
    public boolean checked(int id) {
        return ui.page.checked(id);
    }

    @Override
    public boolean repeats(int id) {
        return ui.page.repeats(id);
    }

    @Override
    public int group(int id) {
        return ui.page.group(id);
    }

    @Override
    public CharSequence groupLabel(int group) {
        return ui.page.groupLabel(group);
    }

    @Override
    public int groupSize(int group) {
        return ui.page.groupSize(group);
    }

    @Override
    public CharSequence error(int id) {
        return ui.page.error(id);
    }

    /** Every activation (touch, key, service): its haptic, then the app's action. */
    @Override
    public final void activate(int id) {
        if (!acts(id)) return;                         // text only reads: no action, no buzz
        tapHaptic(id);
        act(id);
        invalidate();
    }

    // ---- frame -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNs) * 1e-9f);
        lastNs = now;
        lastDrawNs = now;
        boolean moving = false;
        if (palT < 1f) {
            palT = Math.min(1f, palT + dt / (Motion.THEME * ui.motion));
            ui.pal = Palette.lerp(palFrom, palTo, Motion.ease(palT));
            moving = true;
        }
        moving |= step(dt, now);
        render(c);
        access.afterFrame();
        int live = liveTarget();
        if (live != 0 && ui.hits.find(live) == null) live = 0;
        boolean unsaid = access.live(live, live != 0 ? liveText(live) : "", now);
        if (unsaid && running) postInvalidateDelayed(300);   // come back to say the settled text
        interceptBack(canGoBack());
        looping = running && moving;
        if (looping) postInvalidateOnAnimation();
        else lastNs = 0;                   // the next frame after an idle gap starts with a nominal dt
    }

    // ---- window ------------------------------------------------------------------------------

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        Chrome.insets(in, insets);
        ui.insetL = insets.left;
        ui.insetT = insets.top;
        ui.insetR = insets.right;
        ui.insetB = insets.bottom;
        invalidate();
        return in;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
        applyWindowTheme();                // Android 11 can drop bar appearance set before attach
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        ui.width = w;
        ui.height = h;
        invalidate();
    }

    // ---- touch -------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastY = y;
                scrolling = multi = repeated = false;
                ui.hits.focused = 0;                   // touch hides the keyboard focus ring
                downId = ui.hits.at(x, y);
                ui.hits.pressed = downId;
                if (downId != 0 && repeats(downId)) {  // a finger held on a stepper chevron steps on
                    repeatId = downId;
                    postDelayed(repeater, REPEAT_DELAY_MS);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;                          // a second finger cancels the gesture
                cancelPress();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (multi) return true;
                SettingsPage list = access.list;
                float dx = x - downX, dy = y - downY;
                if (!scrolling && !repeated && list != null && list.maxScroll > 0f
                        && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    scrolling = true;
                }
                if (scrolling && list != null && list.scrollBy(lastY - y)) invalidate();
                lastY = y;
                if (ui.hits.pressed != 0 && (scrolling || ui.hits.at(x, y) != downId)) {
                    ui.hits.pressed = 0;
                    repeatId = 0;
                    removeCallbacks(repeater);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(repeater);
                ui.hits.pressed = 0;
                // a tap acts on release, inside the target it started on; a held repeat already acted
                if (!multi && !scrolling && !repeated && downId != 0 && ui.hits.at(x, y) == downId) activate(downId);
                scrolling = multi = repeated = false;
                downId = repeatId = 0;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelPress();
                multi = false;
                return true;
            default:
                return true;
        }
    }

    private void cancelPress() {
        removeCallbacks(repeater);
        ui.hits.pressed = 0;
        downId = repeatId = 0;
        scrolling = false;
        invalidate();
    }

    // ---- keyboard and accessibility ----------------------------------------------------------

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return access;
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent e) {
        return access.onHover(e) || super.dispatchHoverEvent(e);
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        access.onFocusChanged(gained);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        return access.onKeyDown(code, e) || super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        return access.onKeyUp(code, e) || super.onKeyUp(code, e);
    }

    // ---- back --------------------------------------------------------------------------------

    /**
     * From Android 13 (with android:enableOnBackInvokedCallback="true" in the manifest)
     * Activity.onBackPressed is no longer called; a callback is registered only while something
     * can close, so at the top level the system keeps its back-to-home animation. From 14 the
     * gesture's progress is {@link Frame#backT}, for the page to preview its closing.
     */
    private void interceptBack(boolean on) {
        if (Build.VERSION.SDK_INT < 33 || on == backRegistered || !(getContext() instanceof Activity)) return;
        OnBackInvokedDispatcher dispatcher = ((Activity) getContext()).getOnBackInvokedDispatcher();
        if (backCallback == null) backCallback = Build.VERSION.SDK_INT >= 34 ? new Animated() : new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                backInvoked();
            }
        };
        if (on) dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        else dispatcher.unregisterOnBackInvokedCallback(backCallback);
        backRegistered = on;
    }

    private void backInvoked() {
        ui.backT = 0f;
        if (!back()) interceptBack(false);
        invalidate();
    }

    /** Back with the gesture's progress (Android 14+). */
    private final class Animated implements OnBackAnimationCallback {
        @Override
        public void onBackStarted(BackEvent e) {
            ui.backT = 0f;
        }

        @Override
        public void onBackProgressed(BackEvent e) {
            ui.backT = e.getProgress();
            invalidate();
        }

        @Override
        public void onBackCancelled() {
            ui.backT = 0f;
            invalidate();
        }

        @Override
        public void onBackInvoked() {
            backInvoked();
        }
    }
}
