package app.level.bubble;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;

import java.util.Locale;

/** Owns animation state and input; delegates all drawing to {@link Renderer}. */
final class LevelView extends View {

    interface Host {
        void onThemeApplied(Palette target);
        void onSettingsChanged();
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

    private int themeIdx;
    private Palette palFrom, palTo;
    private float palT = 1f;
    private long lastNs;
    private boolean running;

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

    private int lastCal = LevelEngine.CAL_IDLE;
    private long calEndNs;
    private boolean gyroOn;

    private int downId;
    private float downX, downY;
    private boolean swiping;

    LevelView(Context ctx, Renderer renderer, LevelEngine engine, Settings settings, Palette[] themes, Host host) {
        super(ctx);
        this.renderer = renderer;
        this.engine = engine;
        this.settings = settings;
        this.themes = themes;
        this.host = host;
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
    }

    Palette currentTarget() {
        return palTo;
    }

    void resume() {
        running = true;
        lastNs = 0;
        postInvalidateOnAnimation();
    }

    void pause() {
        running = false;
        if (engine.cal != LevelEngine.CAL_IDLE) engine.cancelCal();
        updateGyro(false);
    }

    void onSensor() {
        if (running) postInvalidateOnAnimation();
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
        ui.insetL = in.getSystemWindowInsetLeft();
        ui.insetT = in.getSystemWindowInsetTop();
        ui.insetR = in.getSystemWindowInsetRight();
        ui.insetB = in.getSystemWindowInsetBottom();
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
    }

    // ---- frame -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNs) * 1e-9f);
        lastNs = now;
        boolean animating = step(dt, now);
        renderer.draw(c, ui);
        if (running && animating) postInvalidateOnAnimation();
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
        float tol = Settings.TOLERANCE[settings.tolIdx];
        if (engine.cal != LevelEngine.CAL_IDLE || !engine.valid) {
            level = false;
        } else if (!held) {
            boolean next = level ? val <= tol * 1.3f + 0.03f : val <= tol;
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
        float err = Math.abs(tx - bx) + Math.abs(ty - by) + Math.abs(vx) + Math.abs(vy);
        return err > 0.0005f;
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
        setContentDescription((shownEdge ? "Edge " : "Surface ") + ui.main + " " + ui.mainUnit + (held ? ", held" : ""));
        if (ui.calActive) return;              // calibration owns caption and secondary line
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
        if ((ev & LevelEngine.EV_CAPTURE) != 0) buzz(true);
        if ((ev & LevelEngine.EV_DONE) != 0) {
            buzz(true);
            settings.bx = engine.bx;
            settings.by = engine.by;
            settings.bz = engine.bz;
            settings.calibrated = true;
            ui.calibrated = true;
            host.onSettingsChanged();
            calEndNs = now;
        }
        if ((ev & LevelEngine.EV_FAILED) != 0) {
            buzz(true);
            calEndNs = now;
        }
        int cal = engine.cal;
        if ((cal == LevelEngine.CAL_DONE || cal == LevelEngine.CAL_FAILED) && now - calEndNs > 1_800_000_000L) {
            engine.cancelCal();
            cal = LevelEngine.CAL_IDLE;
            if (lastCal == LevelEngine.CAL_DONE) showFlash("Calibrated", now);
        }
        updateGyro(cal == LevelEngine.CAL_STEP1 || cal == LevelEngine.CAL_TURN || cal == LevelEngine.CAL_STEP2);

        ui.calActive = cal != LevelEngine.CAL_IDLE;
        ui.calProgress = engine.calProgress;
        ui.calShowReady = cal == LevelEngine.CAL_TURN;
        boolean edgeCal = engine.calEdge;
        switch (cal) {
            case LevelEngine.CAL_STEP1:
                ui.caption = "Step 1 of 2";
                ui.calTitle = "Calibrate";
                ui.secondary = edgeCal ? "Stand it on its edge on a firm surface" : "Lay it face up on a firm surface";
                break;
            case LevelEngine.CAL_TURN:
                ui.caption = "Step 2 of 2";
                ui.calTitle = "Turn around";
                ui.secondary = edgeCal ? "Flip it end for end, same edge down" : "Rotate it 180° on the same spot";
                break;
            case LevelEngine.CAL_STEP2:
                ui.caption = "Step 2 of 2";
                ui.calTitle = "Calibrate";
                ui.secondary = "Keep it still";
                break;
            case LevelEngine.CAL_DONE:
                ui.caption = "";
                ui.calTitle = "Calibrated";
                ui.secondary = "Offset saved";
                break;
            case LevelEngine.CAL_FAILED:
                ui.caption = "";
                ui.calTitle = "Try again";
                ui.secondary = "It moved too far between steps";
                break;
            default:
                break;
        }
        if (cal != lastCal) {
            ui.secondaryAccent = false;
            if (cal == LevelEngine.CAL_IDLE) dirty = true;   // restore normal readout text
        }
        lastCal = cal;
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

    // ---- input -------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                swiping = false;
                downId = ui.hitAt(x, y);
                ui.pressedId = downId;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                if (!swiping && page == 0 && Math.abs(dx) > slop * 2f && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    swiping = true;
                }
                if (swiping || (ui.pressedId != 0 && ui.hitAt(x, y) != downId)) {
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
                if (swiping) {
                    if (Math.abs(dx) > 64f * ui.dp) setTheme(themeIdx + (dx < 0f ? 1 : -1));
                } else if (downId != 0 && ui.hitAt(x, y) == downId) {
                    action(downId);
                }
                swiping = false;
                downId = 0;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                ui.pressedId = 0;
                swiping = false;
                downId = 0;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void action(int id) {
        boolean changed = false;
        switch (id) {
            case Renderer.H_THEME_PREV: setTheme(themeIdx - 1); break;
            case Renderer.H_THEME_NEXT: setTheme(themeIdx + 1); break;
            case Renderer.H_SETTINGS: page = 1; break;
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
                settings.calibrated = false;
                settings.bx = settings.by = settings.bz = 0f;
                engine.bx = engine.by = engine.bz = 0f;
                changed = true;
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
        invalidate();
    }

    private void syncSettingsUi(boolean snap) {
        ui.unitsLabel = Settings.UNITS[settings.units];
        float tol = Settings.TOLERANCE[settings.tolIdx];
        ui.tolLabel = String.format(Locale.US, "± %.1f°", tol);
        ui.modeLabel = Settings.LOCKS[settings.lock];
        ui.calibrated = settings.calibrated;
        ui.guideLabels = settings.units == 0;
        if (snap) {
            ui.hapticsT = settings.haptics ? 1f : 0f;
            ui.keepOnT = settings.keepOn ? 1f : 0f;
        }
        dirty = true;
    }

    private void buzz(boolean strong) {
        if (!settings.haptics) return;
        int c;
        if (strong) c = Build.VERSION.SDK_INT >= 30 ? 16 /* CONFIRM */ : HapticFeedbackConstants.VIRTUAL_KEY;
        else c = HapticFeedbackConstants.KEYBOARD_TAP;
        performHapticFeedback(c);
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

    private static int wrapIdx(int i, int n) {
        return ((i % n) + n) % n;
    }
}
