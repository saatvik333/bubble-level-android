package app.level.bubble;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.Locale;

/** Owns animation state and input; delegates all drawing to {@link Renderer}. */
final class LevelView extends View {

    interface Host {
        void onThemeApplied(Palette target);
        void onSettingsChanged();
        void onCalibrationChanged();
        void setGyroActive(boolean on);
        void setBackIntercept(boolean on);
    }

    private static final float SENS_FLAT = 8f;   // degrees of tilt mapped to ~76% of travel (tanh)
    private static final float SENS_EDGE = 6f;
    private static final float SPRING_W = 15f;   // rad/s
    private static final float SPRING_Z = 0.72f; // slight overshoot, settles in ~0.4 s

    private final Ui ui = new Ui();
    private final Renderer renderer;
    private final LevelEngine engine;
    private final Settings settings;
    private final Palette[] themes;
    private final Host host;
    private final float slop;
    private final A11y a11y = new A11y();
    private final AccessibilityManager am;

    private int themeIdx;
    private Palette palFrom, palTo;
    private float palT = 1f;
    private long lastNs, lastDrawNs;
    private boolean running, loop;

    private int page;
    private boolean held;
    private float tx, ty;                  // bubble target (normalized)
    private float bx, by, vx, vy;          // bubble spring
    private float rot;
    private boolean level;

    private float shownVal = Float.NaN, shownX, shownY;
    private int shownUnits = -1;
    private boolean shownEdge, shownLevel, dirty = true;
    private String flash;
    private long flashUntil;
    private String description = "";

    private int lastCal = LevelEngine.CAL_IDLE;
    private long calEndNs;
    private boolean gyroOn;

    private int downId;
    private float downX, downY, lastY;
    private boolean swiping, scrolling, multi;
    private int hitSig;

    LevelView(Context ctx, Renderer renderer, LevelEngine engine, Settings settings, Palette[] themes, Host host) {
        super(ctx);
        this.renderer = renderer;
        this.engine = engine;
        this.settings = settings;
        this.themes = themes;
        this.host = host;
        am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        float density = getResources().getDisplayMetrics().density;
        ui.dp = density;
        ui.sp = Math.min(getResources().getDisplayMetrics().scaledDensity, density * 1.3f);
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();

        themeIdx = 0;
        for (int i = 0; i < themes.length; i++) if (themes[i].name.equals(settings.theme)) themeIdx = i;
        palFrom = palTo = themes[themeIdx];
        ui.pal = palTo;
        ui.themeName = palTo.name;

        ui.guide2 = tanh(2f / SENS_FLAT);
        ui.guide5 = tanh(5f / SENS_FLAT);
        ui.edgeTicks[0] = tanh(1f / SENS_EDGE);
        ui.edgeTicks[1] = tanh(2f / SENS_EDGE);
        ui.edgeTicks[2] = tanh(5f / SENS_EDGE);
        syncSettingsUi(true);
        setKeepScreenOn(settings.keepOn);
        setHapticFeedbackEnabled(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    Palette currentTarget() {
        return palTo;
    }

    void resume() {
        running = true;
        lastNs = 0;
        loop = true;
        postInvalidateOnAnimation();
    }

    void pause() {
        running = false;
        if (engine.cal != LevelEngine.CAL_IDLE) engine.cancelCal();
        updateGyro(false);
    }

    /** New sensor sample: draw only when something visible would change. */
    void onSensor() {
        if (!running) return;
        // A frame is already on its way unless the last one is long gone (e.g. a skipped draw).
        if (loop && System.nanoTime() - lastDrawNs < 250_000_000L) return;
        if (loop || needsFrame()) {
            loop = true;
            postInvalidateOnAnimation();
        }
    }

    boolean handleBack() {
        if (page == 1) {
            page = 0;
            invalidate();
            return true;
        }
        if (engine.cal != LevelEngine.CAL_IDLE) {
            engine.cancelCal();
            invalidate();
            return true;
        }
        return false;
    }

    // ---- layout ------------------------------------------------------------------------------

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        int l = in.getSystemWindowInsetLeft(), t = in.getSystemWindowInsetTop();
        int r = in.getSystemWindowInsetRight(), b = in.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 30) {
            // system bars + display cutout; the deprecated getters leave the cutout out
            try {
                Class<?> type = Class.forName("android.view.WindowInsets$Type");
                int mask = (Integer) type.getMethod("systemBars").invoke(null)
                        | (Integer) type.getMethod("displayCutout").invoke(null);
                Object ins = WindowInsets.class.getMethod("getInsets", int.class).invoke(in, mask);
                Class<?> ic = ins.getClass();
                l = ic.getField("left").getInt(ins);
                t = ic.getField("top").getInt(ins);
                r = ic.getField("right").getInt(ins);
                b = ic.getField("bottom").getInt(ins);
            } catch (Exception ignored) {
                // keep the legacy values
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            try {
                Object cut = WindowInsets.class.getMethod("getDisplayCutout").invoke(in);
                if (cut != null) {
                    Class<?> cc = cut.getClass();
                    l = Math.max(l, (Integer) cc.getMethod("getSafeInsetLeft").invoke(cut));
                    t = Math.max(t, (Integer) cc.getMethod("getSafeInsetTop").invoke(cut));
                    r = Math.max(r, (Integer) cc.getMethod("getSafeInsetRight").invoke(cut));
                    b = Math.max(b, (Integer) cc.getMethod("getSafeInsetBottom").invoke(cut));
                }
            } catch (Exception ignored) {
            }
        }
        ui.insetL = l;
        ui.insetT = t;
        ui.insetR = r;
        ui.insetB = b;
        invalidate();
        return in;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        ui.width = w;
        ui.height = h;
        invalidate();
    }

    // ---- frame -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNs) * 1e-9f);
        lastNs = now;
        lastDrawNs = now;
        boolean animating = step(dt, now);
        renderer.draw(c, ui);
        if (ui.settingsScroll > ui.settingsMaxScroll) ui.settingsScroll = ui.settingsMaxScroll;
        a11yAfterFrame();
        loop = running && animating;
        if (loop) postInvalidateOnAnimation();
        else lastNs = 0;                   // next frame after an idle gap starts with a nominal dt
    }

    private boolean step(float dt, long now) {
        boolean anim = false;

        if (palT < 1f) {
            palT = Math.min(1f, palT + dt / 0.45f);
            ui.pal = Palette.lerp(palFrom, palTo, ease(palT));
            anim = true;
        }

        ui.pageT = approach(ui.pageT, page, dt, 0.085f);
        anim |= ui.pageT != page;

        handleCalibration(now);
        host.setBackIntercept(page == 1 || engine.cal != LevelEngine.CAL_IDLE);   // host no-ops if unchanged

        boolean edge = engine.mode == LevelEngine.MODE_EDGE;
        ui.modeT = approach(ui.modeT, edge ? 1f : 0f, dt, 0.08f);
        anim |= ui.modeT != (edge ? 1f : 0f);
        if (edge) {
            float d = LevelEngine.wrap(engine.quarter - rot);
            if (Math.abs(d) > 0.05f) {
                rot += d * (1f - (float) Math.exp(-dt / 0.09f));
                anim = true;
            } else {
                rot = engine.quarter;
            }
        }
        ui.rotation = rot;

        if (!held && engine.valid) {
            if (edge) {
                tx = tanh(engine.dev / SENS_EDGE);
                ty = 0f;
            } else {
                float m = tanh(engine.tilt / SENS_FLAT);
                tx = m * (float) Math.cos(engine.dir);
                ty = -m * (float) Math.sin(engine.dir);
            }
        }
        anim |= spring(dt);
        ui.bubX = bx;
        ui.bubY = by;

        float val = edge ? Math.abs(engine.dev) : engine.tilt;
        if (engine.cal != LevelEngine.CAL_IDLE || !engine.valid) {
            level = false;
        } else if (!held) {
            boolean next = nextLevel(val);
            if (next && !level) {
                ui.pulseT = 0f;
                buzz(true);
            }
            level = next;
        }
        float lt = level ? 1f : 0f;
        ui.levelT = approach(ui.levelT, lt, dt, 0.07f);
        anim |= ui.levelT != lt;
        if (ui.pulseT >= 0f) {
            ui.pulseT += dt / 0.7f;
            if (ui.pulseT > 1f) ui.pulseT = -1f;
            anim = true;
        }

        updateReadout(edge, val, now);
        if (flash != null) anim = true;

        float h = settings.haptics ? 1f : 0f, k = settings.keepOn ? 1f : 0f;
        ui.hapticsT = approach(ui.hapticsT, h, dt, 0.06f);
        ui.keepOnT = approach(ui.keepOnT, k, dt, 0.06f);
        anim |= ui.hapticsT != h || ui.keepOnT != k;
        return anim || engine.cal != LevelEngine.CAL_IDLE;
    }

    private boolean nextLevel(float val) {
        float tol = Settings.TOLERANCE[settings.tolIdx];
        return level ? val <= tol * 1.3f + 0.03f : val <= tol;
    }

    /** Whether a sample changes anything on screen by more than ~1/3 px or one readout digit. */
    private boolean needsFrame() {
        if (!engine.valid) return false;
        if (engine.cal != LevelEngine.CAL_IDLE) return true;
        if (page == 1 && ui.pageT == 1f) return false;     // level page is not visible
        boolean edge = engine.mode == LevelEngine.MODE_EDGE;
        if (ui.modeT != (edge ? 1f : 0f)) return true;
        if (edge && Math.abs(LevelEngine.wrap(engine.quarter - rot)) > 0.05f) return true;
        if (held) return false;
        float ntx, nty;
        if (edge) {
            ntx = tanh(engine.dev / SENS_EDGE);
            nty = 0f;
        } else {
            float m = tanh(engine.tilt / SENS_FLAT);
            ntx = m * (float) Math.cos(engine.dir);
            nty = -m * (float) Math.sin(engine.dir);
        }
        float eps = 0.35f / Math.max(1f, ui.travelPx);
        if (Math.abs(ntx - bx) > eps || Math.abs(nty - by) > eps) return true;
        float val = edge ? Math.abs(engine.dev) : engine.tilt;
        float x = edge ? engine.dev : engine.ax, y = edge ? 0f : engine.ay;
        if (Float.isNaN(shownVal) || edge != shownEdge || Math.abs(val - shownVal) >= 0.05f
                || Math.abs(x - shownX) >= 0.05f || Math.abs(y - shownY) >= 0.05f) return true;
        return nextLevel(val) != level;
    }

    /** Damped spring toward (tx, ty), sub-stepped for stability, bubble kept inside the vial. */
    private boolean spring(float dt) {
        float h = 0.004f;
        for (float t = 0f; t < dt; t += h) {
            float s = Math.min(h, dt - t);
            float axl = SPRING_W * SPRING_W * (tx - bx) - 2f * SPRING_Z * SPRING_W * vx;
            float ayl = SPRING_W * SPRING_W * (ty - by) - 2f * SPRING_Z * SPRING_W * vy;
            vx += axl * s;
            vy += ayl * s;
            bx += vx * s;
            by += vy * s;
            float m = (float) Math.sqrt(bx * bx + by * by);
            if (m > 1f) {
                float nx = bx / m, ny = by / m;
                bx = nx;
                by = ny;
                float vr = vx * nx + vy * ny;
                if (vr > 0f) {
                    vx -= vr * nx;
                    vy -= vr * ny;
                }
            }
        }
        float eps = 0.2f / Math.max(1f, ui.travelPx);
        boolean moving = Math.abs(tx - bx) > eps || Math.abs(ty - by) > eps
                || Math.abs(vx) > eps * 10f || Math.abs(vy) > eps * 10f;
        if (!moving) {
            bx = tx;
            by = ty;
            vx = vy = 0f;
        }
        return moving;
    }

    private void updateReadout(boolean edge, float val, long now) {
        if (!held && engine.valid) {
            float x = edge ? engine.dev : engine.ax, y = edge ? 0f : engine.ay;
            if (Float.isNaN(shownVal) || edge != shownEdge || Math.abs(val - shownVal) >= 0.05f
                    || Math.abs(x - shownX) >= 0.05f || Math.abs(y - shownY) >= 0.05f) {
                shownVal = val;
                shownX = x;
                shownY = y;
                shownEdge = edge;
                dirty = true;
            }
            if (level != shownLevel) {
                shownLevel = level;
                dirty = true;
            }
        }
        if (settings.units != shownUnits) {
            shownUnits = settings.units;
            dirty = true;
        }
        boolean flashing = flash != null && now < flashUntil;
        if (flash != null && !flashing) {
            flash = null;
            dirty = true;
        }
        if (!dirty) return;
        dirty = false;

        String[] v = format(shownVal, false);
        ui.main = v[0];
        ui.mainUnit = v[1];
        ui.held = held;
        ui.guideLabels = settings.units == 0;
        if (ui.calActive) {                     // calibration owns caption and secondary line
            describe(ui.calTitle + ". " + ui.secondary);
            return;
        }
        ui.caption = held ? "Held" : shownEdge ? "Edge" : "Surface";
        if (flashing) {
            ui.secondary = flash;
            ui.secondaryAccent = true;
        } else if (shownEdge) {
            ui.secondary = shownLevel ? "Level" : shownX < 0f ? "Right side low" : "Left side low";
            ui.secondaryAccent = false;
        } else {
            String[] sx = format(shownX, true), sy = format(shownY, true);
            ui.secondary = "X  " + sx[0] + sx[1] + "     Y  " + sy[0] + sy[1];
            ui.secondaryAccent = false;
        }
        describe(readingText());
    }

    private String readingText() {
        String unit = ui.mainUnit.length() > 0 ? " " + ui.mainUnit : "";
        String s = (shownEdge ? "Edge, " : "Surface, ") + ui.main + unit;
        if (shownEdge) s += ", " + ui.secondary;
        else if (shownLevel) s += ", level";
        return held ? s + ", held" : s;
    }

    private void describe(String s) {
        if (!s.equals(description)) {
            description = s;
            setContentDescription(s);
        }
    }

    /** Returns {number, unit-suffix}. */
    private String[] format(float deg, boolean signed) {
        if (Float.isNaN(deg)) return new String[]{"–", ""};
        int u = settings.units;
        float v;
        String num, unit = "";
        if (u == 0) {
            num = fixed(deg, 1, signed) + "°";
        } else if (Math.abs(deg) > 80f) {
            num = "–";
        } else {
            v = (float) Math.tan(Math.toRadians(deg));
            if (u == 1) {
                num = fixed(v * 100f, 1, signed) + "%";
            } else if (u == 2) {
                float mm = v * 1000f;
                num = fixed(mm, Math.abs(mm) < 10f ? 1 : 0, signed);
                unit = signed ? "" : "mm/m";
            } else {
                num = fixed(v * 12f, 2, signed);
                unit = signed ? "" : "in/ft";
            }
        }
        return new String[]{num, unit};
    }

    private static String fixed(float v, int decimals, boolean signed) {
        String s = String.format(Locale.US, "%." + decimals + "f", Math.abs(v));
        boolean zero = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '1' && ch <= '9') {
                zero = false;
                break;
            }
        }
        if (!signed || zero) return s;
        return (v < 0f ? "−" : "+") + s;
    }

    // ---- calibration -------------------------------------------------------------------------

    private void handleCalibration(long now) {
        int ev = engine.takeEvents();
        if ((ev & LevelEngine.EV_CAPTURE) != 0) {
            buzz(true);
            announce("First reading taken. Turn it around.");
        }
        if ((ev & LevelEngine.EV_DONE) != 0) {
            buzz(true);
            System.arraycopy(engine.surf, 0, settings.surf, 0, 4);
            System.arraycopy(engine.edge, 0, settings.edge, 0, 4);
            settings.calMask = engine.calMask;
            settings.calibrated = engine.calMask != 0;
            syncSettingsUi(false);
            host.onCalibrationChanged();
            calEndNs = now;
            announce("Calibrated");
        }
        if ((ev & LevelEngine.EV_FAILED) != 0) {
            buzz(true);
            calEndNs = now;
            announce("Calibration failed. " + failText());
        }
        int cal = engine.cal;
        if ((cal == LevelEngine.CAL_DONE || cal == LevelEngine.CAL_FAILED) && now - calEndNs > 2_200_000_000L) {
            engine.cancelCal();
            cal = LevelEngine.CAL_IDLE;
            if (lastCal == LevelEngine.CAL_DONE) showFlash("Calibrated", now);
        }
        updateGyro(cal == LevelEngine.CAL_STEP1 || cal == LevelEngine.CAL_TURN || cal == LevelEngine.CAL_STEP2);

        ui.calActive = cal != LevelEngine.CAL_IDLE;
        ui.calProgress = engine.calProgress;
        ui.calShowReady = cal == LevelEngine.CAL_TURN;
        boolean edgeCal = engine.calEdge;
        int hint = engine.calHint;
        boolean hinted = false;
        switch (cal) {
            case LevelEngine.CAL_STEP1:
                ui.caption = "Step 1 of 2";
                ui.calTitle = "Calibrate";
                ui.secondary = edgeCal ? "Stand it on an edge on a firm surface" : "Lay it flat on a firm surface";
                break;
            case LevelEngine.CAL_TURN:
                ui.caption = "Step 2 of 2";
                ui.calTitle = "Turn around";
                if (hint == LevelEngine.HINT_TURN_FIRST) {
                    ui.secondary = "Turn it around first, then tap Ready";
                    hinted = true;
                } else {
                    ui.secondary = edgeCal ? "Turn it to face the other way, same edge down"
                            : "Rotate it 180° on the same spot";
                }
                break;
            case LevelEngine.CAL_STEP2:
                ui.caption = "Step 2 of 2";
                ui.calTitle = "Calibrate";
                if (hint == LevelEngine.HINT_TURN_FIRST) {
                    ui.secondary = edgeCal ? "Turn it to face the other way, same edge down"
                            : "Rotate it 180° on the same spot";
                    hinted = true;
                } else if (hint == LevelEngine.HINT_WRONG_SIDE) {
                    ui.secondary = edgeCal ? "Same edge down as before" : "Same side up as before";
                    hinted = true;
                } else {
                    ui.secondary = "Keep it still";
                }
                break;
            case LevelEngine.CAL_DONE:
                ui.caption = "";
                ui.calTitle = "Calibrated";
                ui.secondary = edgeCal ? "Edge offset saved" : "Surface offset saved";
                break;
            case LevelEngine.CAL_FAILED:
                ui.caption = "";
                ui.calTitle = "Try again";
                ui.secondary = failText();
                hinted = true;
                break;
            default:
                break;
        }
        if (ui.calActive) {
            ui.secondaryAccent = hinted;
            describe(ui.calTitle + ". " + ui.secondary);
        }
        if (cal != lastCal) {
            if (cal == LevelEngine.CAL_IDLE) {
                ui.secondaryAccent = false;
                dirty = true;   // restore normal readout text
            }
        }
        lastCal = cal;
    }

    private String failText() {
        if (engine.calFail == LevelEngine.FAIL_TURN) {
            return engine.calEdge ? "Turn it to face exactly the other way" : "Turn it about 180° between the two steps";
        }
        return "Readings too far apart. Keep it on the same spot";
    }

    private void updateGyro(boolean on) {
        if (on != gyroOn) {
            gyroOn = on;
            host.setGyroActive(on);
        }
    }

    private void showFlash(String s, long now) {
        flash = s;
        flashUntil = now + 1_600_000_000L;
        dirty = true;
    }

    private void announce(String s) {
        if (am != null && am.isEnabled()) announceForAccessibility(s);
    }

    // ---- input -------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                lastY = y;
                swiping = scrolling = multi = false;
                downId = ui.hitAt(x, y);
                ui.pressedId = downId;
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;                  // a second finger cancels the gesture
                ui.pressedId = 0;
                downId = 0;
                swiping = scrolling = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (multi) return true;
                float dx = x - downX, dy = y - downY;
                if (!swiping && !scrolling && page == 0 && Math.abs(dx) > slop * 2f && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiping = true;
                }
                if (!swiping && !scrolling && page == 1 && ui.settingsMaxScroll > 0f
                        && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                    scrolling = true;
                }
                if (scrolling) {
                    ui.settingsScroll = clamp(ui.settingsScroll - (y - lastY), 0f, ui.settingsMaxScroll);
                    invalidate();
                }
                lastY = y;
                if (swiping || scrolling || (ui.pressedId != 0 && ui.hitAt(x, y) != downId)) {
                    if (ui.pressedId != 0) {
                        ui.pressedId = 0;
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = x - downX;
                ui.pressedId = 0;
                if (!multi) {
                    if (swiping) {
                        if (Math.abs(dx) > 64f * ui.dp) setTheme(themeIdx + (dx < 0f ? 1 : -1));
                    } else if (!scrolling && downId != 0 && ui.hitAt(x, y) == downId) {
                        action(downId);
                    }
                }
                swiping = scrolling = multi = false;
                downId = 0;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                ui.pressedId = 0;
                swiping = scrolling = multi = false;
                downId = 0;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void action(int id) {
        boolean changed = false, calChanged = false;
        switch (id) {
            case Renderer.H_THEME_PREV: setTheme(themeIdx - 1); break;
            case Renderer.H_THEME_NEXT: setTheme(themeIdx + 1); break;
            case Renderer.H_SETTINGS:
                page = 1;
                ui.settingsScroll = 0f;
                break;
            case Renderer.H_BACK: page = 0; break;
            case Renderer.H_CALIBRATE:
                if (engine.cal != LevelEngine.CAL_IDLE) {
                    engine.cancelCal();
                } else {
                    held = false;
                    engine.startCal();
                }
                break;
            case Renderer.H_HOLD:
            case Renderer.H_READOUT:
                if (engine.cal == LevelEngine.CAL_IDLE) {
                    held = !held;
                    dirty = true;
                }
                break;
            case Renderer.H_UNITS_PREV: settings.units = wrapIdx(settings.units - 1, Settings.UNITS.length); changed = true; break;
            case Renderer.H_UNITS_NEXT: settings.units = wrapIdx(settings.units + 1, Settings.UNITS.length); changed = true; break;
            case Renderer.H_TOL_PREV: settings.tolIdx = wrapIdx(settings.tolIdx - 1, Settings.TOLERANCE.length); changed = true; break;
            case Renderer.H_TOL_NEXT: settings.tolIdx = wrapIdx(settings.tolIdx + 1, Settings.TOLERANCE.length); changed = true; break;
            case Renderer.H_MODE_PREV: settings.lock = wrapIdx(settings.lock - 1, Settings.LOCKS.length); changed = true; break;
            case Renderer.H_MODE_NEXT: settings.lock = wrapIdx(settings.lock + 1, Settings.LOCKS.length); changed = true; break;
            case Renderer.H_HAPTIC: settings.haptics = !settings.haptics; changed = true; break;
            case Renderer.H_KEEPON:
                settings.keepOn = !settings.keepOn;
                setKeepScreenOn(settings.keepOn);
                changed = true;
                break;
            case Renderer.H_RESET:
                settings.clearCalibration();
                for (int i = 0; i < 4; i++) engine.surf[i] = engine.edge[i] = 0f;
                engine.calMask = 0;
                engine.refresh();
                changed = true;
                calChanged = true;
                break;
            case Renderer.H_CAL_CANCEL: engine.cancelCal(); break;
            case Renderer.H_CAL_READY: engine.manualReady(); break;
            default: return;
        }
        buzz(false);
        if (changed) {
            engine.lock = settings.lock;
            syncSettingsUi(false);
            host.onSettingsChanged();
        }
        if (calChanged) host.onCalibrationChanged();
        invalidate();
    }

    private void setTheme(int idx) {
        themeIdx = wrapIdx(idx, themes.length);
        palFrom = ui.pal;
        palTo = themes[themeIdx];
        palT = 0f;
        ui.themeName = palTo.name;
        settings.theme = palTo.name;
        host.onThemeApplied(palTo);
        host.onSettingsChanged();
        announce("Theme " + palTo.name);
        invalidate();
    }

    private void syncSettingsUi(boolean snap) {
        ui.unitsLabel = Settings.UNITS[settings.units];
        float tol = Settings.TOLERANCE[settings.tolIdx];
        ui.tolLabel = String.format(Locale.US, "± %.1f°", tol);
        ui.modeLabel = Settings.LOCKS[settings.lock];
        ui.calibrated = settings.calibrated;
        ui.calSummary = calSummary(settings.calMask);
        ui.guideLabels = settings.units == 0;
        if (snap) {
            ui.hapticsT = settings.haptics ? 1f : 0f;
            ui.keepOnT = settings.keepOn ? 1f : 0f;
        }
        dirty = true;
    }

    /** "face up, 2 edges" style list of the calibrated poses, empty when none. */
    static String calSummary(int mask) {
        StringBuilder b = new StringBuilder();
        if ((mask & 1) != 0) b.append("face up");
        if ((mask & 2) != 0) b.append(b.length() > 0 ? ", " : "").append("face down");
        int edges = Integer.bitCount((mask >> 2) & 0xF);
        if (edges > 0) b.append(b.length() > 0 ? ", " : "").append(edges).append(edges == 1 ? " edge" : " edges");
        return b.toString();
    }

    private void buzz(boolean strong) {
        if (!settings.haptics) return;
        int c;
        if (strong) c = Build.VERSION.SDK_INT >= 30 ? 16 /* CONFIRM */ : HapticFeedbackConstants.VIRTUAL_KEY;
        else c = HapticFeedbackConstants.KEYBOARD_TAP;
        performHapticFeedback(c);
    }

    // ---- accessibility -----------------------------------------------------------------------
    // The whole UI is one custom view; every hit target is exposed as a virtual node so TalkBack,
    // Switch Access and keyboard users can reach the controls.

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return a11y;
    }

    @Override
    protected boolean dispatchHoverEvent(MotionEvent e) {
        return a11y.onHover(e) || super.dispatchHoverEvent(e);
    }

    private void a11yAfterFrame() {
        if (am == null || !am.isEnabled()) return;
        a11y.prune();
        int sig = 17;
        for (int i = 0; i < ui.hitCount; i++) sig = sig * 31 + ui.hits[i].id;
        if (page == 1) sig = sig * 31 + Math.round(ui.settingsScroll / ui.dp);   // bounds moved
        if (sig != hitSig) {
            hitSig = sig;
            ViewParent parent = getParent();
            if (parent != null) {
                AccessibilityEvent ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                onInitializeAccessibilityEvent(ev);
                ev.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
                parent.requestSendAccessibilityEvent(this, ev);
            }
        }
    }

    private String label(int id) {
        switch (id) {
            case Renderer.H_THEME_PREV: return "Previous theme. Current theme " + ui.themeName;
            case Renderer.H_THEME_NEXT: return "Next theme. Current theme " + ui.themeName;
            case Renderer.H_SETTINGS: return "Settings";
            case Renderer.H_CALIBRATE: return engine.cal != LevelEngine.CAL_IDLE ? "Stop calibration" : "Calibrate";
            case Renderer.H_HOLD: return held ? "Resume live reading" : "Hold reading";
            case Renderer.H_READOUT: return ui.calActive ? ui.calTitle + ". " + ui.secondary : readingText();
            case Renderer.H_BACK: return "Back";
            case Renderer.H_UNITS_PREV: return "Units " + ui.unitsLabel + ". Previous";
            case Renderer.H_UNITS_NEXT: return "Units " + ui.unitsLabel + ". Next";
            case Renderer.H_TOL_PREV: return "Level tolerance " + ui.tolLabel + ". Smaller";
            case Renderer.H_TOL_NEXT: return "Level tolerance " + ui.tolLabel + ". Larger";
            case Renderer.H_MODE_PREV: return "Mode " + ui.modeLabel + ". Previous";
            case Renderer.H_MODE_NEXT: return "Mode " + ui.modeLabel + ". Next";
            case Renderer.H_HAPTIC: return "Haptic feedback";
            case Renderer.H_KEEPON: return "Keep screen on";
            case Renderer.H_RESET: return "Reset calibration. Calibrated: " + ui.calSummary;
            case Renderer.H_CAL_CANCEL: return "Cancel calibration";
            case Renderer.H_CAL_READY: return "Ready, it is turned around";
            default: return "";
        }
    }

    private final class A11y extends AccessibilityNodeProvider {
        private static final int NONE = Integer.MIN_VALUE;
        private int focused = NONE, hovered = NONE;
        private final Rect r = new Rect();
        private final int[] loc = new int[2];

        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
            if (id == HOST_VIEW_ID) {
                AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(LevelView.this);
                onInitializeAccessibilityNodeInfo(info);
                if (page == 1) info.setContentDescription("Settings");
                for (int i = 0; i < ui.hitCount; i++) info.addChild(LevelView.this, ui.hits[i].id);
                if (scrollable()) {
                    // settings rows below the fold are reached by scrolling, like a ScrollView
                    info.setClassName("android.widget.ScrollView");
                    info.setScrollable(true);
                    if (ui.settingsScroll > 0f) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                    }
                    if (ui.settingsScroll < ui.settingsMaxScroll) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                    }
                }
                return info;
            }
            Ui.Hit h = ui.find(id);
            if (h == null) return null;
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(LevelView.this, id);
            info.setPackageName(getContext().getPackageName());
            boolean toggle = id == Renderer.H_HAPTIC || id == Renderer.H_KEEPON;
            info.setClassName(toggle ? "android.widget.Switch" : "android.widget.Button");
            info.setContentDescription(label(id));
            if (toggle) {
                info.setCheckable(true);
                info.setChecked(id == Renderer.H_HAPTIC ? settings.haptics : settings.keepOn);
            }
            info.setParent(LevelView.this);
            r.set(Math.round(h.rect.left), Math.round(h.rect.top), Math.round(h.rect.right), Math.round(h.rect.bottom));
            info.setBoundsInParent(r);
            getLocationOnScreen(loc);
            r.offset(loc[0], loc[1]);
            info.setBoundsInScreen(r);
            info.setVisibleToUser(true);
            info.setEnabled(true);
            info.setFocusable(true);
            info.setClickable(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            if (focused == id) {
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
                    float pageH = Math.max(48f * ui.dp, (ui.height - ui.insetB - ui.insetT - 56f * ui.dp) * 0.8f);
                    float to = clamp(ui.settingsScroll
                            + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? pageH : -pageH),
                            0f, ui.settingsMaxScroll);
                    if (to == ui.settingsScroll) return false;
                    ui.settingsScroll = to;
                    invalidate();
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                    return true;
                }
                return performAccessibilityAction(action, args);
            }
            if (ui.find(id) == null) return false;
            switch (action) {
                case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                    if (focused != id) {
                        int prev = focused;
                        focused = id;
                        if (prev != NONE) send(prev, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                        send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                    }
                    return true;
                case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                    if (focused == id) {
                        focused = NONE;
                        send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                    }
                    return true;
                case AccessibilityNodeInfo.ACTION_CLICK:
                    action(id);
                    send(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
                    return true;
                default:
                    return false;
            }
        }

        private boolean scrollable() {
            return page == 1 && ui.settingsMaxScroll > 0f;
        }

        /** Forgets focus/hover on targets that are no longer on screen. */
        void prune() {
            if (focused != NONE && ui.find(focused) == null) focused = NONE;
            if (hovered != NONE && ui.find(hovered) == null) hovered = NONE;
        }

        boolean onHover(MotionEvent e) {
            if (am == null || !am.isEnabled() || !am.isTouchExplorationEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: {
                    int id = ui.hitAt(e.getX(), e.getY());
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
            if (am == null || !am.isEnabled()) return;
            ViewParent parent = getParent();
            if (parent == null) return;
            AccessibilityEvent ev = AccessibilityEvent.obtain(type);
            ev.setPackageName(getContext().getPackageName());
            boolean toggle = id == Renderer.H_HAPTIC || id == Renderer.H_KEEPON;
            ev.setClassName(toggle ? "android.widget.Switch" : "android.widget.Button");
            ev.setSource(LevelView.this, id);
            ev.setEnabled(true);
            ev.setContentDescription(label(id));
            if (toggle) {
                ev.setChecked(id == Renderer.H_HAPTIC ? settings.haptics : settings.keepOn);
            }
            parent.requestSendAccessibilityEvent(LevelView.this, ev);
        }
    }

    // ---- math --------------------------------------------------------------------------------

    private static float approach(float v, float target, float dt, float tau) {
        float n = target + (v - target) * (float) Math.exp(-dt / tau);
        return Math.abs(n - target) < 0.002f ? target : n;
    }

    private static float ease(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    private static float tanh(float x) {
        return (float) Math.tanh(x);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static int wrapIdx(int i, int n) {
        return ((i % n) + n) % n;
    }
}
