package me.saatvik.level;

import android.content.Context;
import android.graphics.Canvas;

import java.util.Locale;

import me.saatvik.hairline.Motion;
import me.saatvik.hairline.Palette;
import me.saatvik.hairline.Stage;

/**
 * The level's state, animation and actions on a hairline {@link Stage} (which owns the frame loop,
 * input, accessibility, themes and back); drawing is {@link Renderer}'s.
 */
final class LevelView extends Stage<Ui> {

    interface Host {
        void onSettingsChanged();
        /** Persists the calibration; false when it could not be saved. */
        boolean onCalibrationChanged();
        void setGyroActive(boolean on);
    }

    private static final float SENS_FLAT = 8f;   // degrees of tilt mapped to ~76% of travel (tanh)
    private static final float SENS_EDGE = 6f;
    private static final float SPRING_W = 15f;   // rad/s
    private static final float SPRING_Z = 0.72f; // slight overshoot, settles in ~0.4 s

    private final Renderer renderer;
    private final LevelEngine engine;
    private final Settings settings;
    private final Host host;

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
    private boolean calSaved = true;       // whether the last finished calibration reached storage
    private boolean gyroOn;

    LevelView(Context ctx, Renderer renderer, LevelEngine engine, Settings settings, Host host) {
        super(ctx, new Ui(), settings.theme);
        this.renderer = renderer;
        this.engine = engine;
        this.settings = settings;
        this.host = host;
        ui.unitOpts = Settings.UNITS;
        ui.modeOpts = Settings.LOCKS;
        ui.tolOpts = new String[Settings.TOLERANCE.length];
        for (int i = 0; i < ui.tolOpts.length; i++) ui.tolOpts[i] = String.format(Locale.US, "%.1f°", Settings.TOLERANCE[i]);

        ui.guide2 = tanh(2f / SENS_FLAT);
        ui.guide5 = tanh(5f / SENS_FLAT);
        ui.edgeTicks[0] = tanh(1f / SENS_EDGE);
        ui.edgeTicks[1] = tanh(2f / SENS_EDGE);
        ui.edgeTicks[2] = tanh(5f / SENS_EDGE);
        syncSettingsUi(true);
        setKeepScreenOn(settings.keepOn);
    }

    @Override
    public void pause() {
        super.pause();
        if (engine.cal != LevelEngine.CAL_IDLE) engine.cancelCal();
        updateGyro(false);
    }

    /** New sensor sample: draw only when something visible would change. */
    void onSensor() {
        if (running() && (looping() || needsFrame())) requestFrame();
    }

    /** Closes the settings page or stops calibration; false at the top level. Escape and system back. */
    @Override
    public boolean back() {
        if (page == 1) {
            closeSettings();
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

    @Override
    protected boolean canGoBack() {
        return page == 1 || engine.cal != LevelEngine.CAL_IDLE;
    }

    // ---- frame -------------------------------------------------------------------------------

    @Override
    protected void render(Canvas c) {
        renderer.draw(c, ui);
    }

    /** The level page's readout is a polite live region: the reading, or the calibration step. */
    @Override
    protected int liveTarget() {
        return page == 0 ? Renderer.H_READOUT : 0;
    }

    @Override
    protected boolean step(float dt, long now) {
        boolean anim = false;

        // settings over the level; a back gesture previews closing it
        float pt = page == 1 ? 1f - BACK_PREVIEW * ui.backT : 0f;
        ui.pageT = approach(ui.pageT, pt, dt, Motion.PAGE);
        anim |= ui.pageT != pt;
        access.list = page == 1 ? ui.page : null;

        handleCalibration(now);

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
                if (ui.motion > 0f) ui.pulseT = 0f;     // the arrival ring is decoration: none without animations
                haptic(CONFIRM);
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
        ui.hapticsT = approach(ui.hapticsT, h, dt, Motion.CONTROL);
        ui.keepOnT = approach(ui.keepOnT, k, dt, Motion.CONTROL);
        anim |= ui.hapticsT != h || ui.keepOnT != k;
        ui.unitsPos = approach(ui.unitsPos, ui.units, dt, Motion.CONTROL);
        ui.tolPos = approach(ui.tolPos, ui.tolIdx, dt, Motion.CONTROL);
        ui.modePos = approach(ui.modePos, ui.mode, dt, Motion.CONTROL);
        anim |= ui.unitsPos != ui.units || ui.tolPos != ui.tolIdx || ui.modePos != ui.mode;
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
        if (ui.calActive) return;               // calibration owns caption and secondary line
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

    private String readingText() {
        String unit = ui.mainUnit.length() > 0 ? " " + ui.mainUnit : "";
        String s = (shownEdge ? "Edge, " : "Surface, ") + ui.main + unit;
        if (shownEdge) s += ", " + ui.secondary;
        else if (shownLevel) s += ", level";
        return held ? s + ", held" : s;
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
        // each moment shows in the readout, a live region: it is said from there
        if ((ev & LevelEngine.EV_CAPTURE) != 0) haptic(CONFIRM);
        if ((ev & LevelEngine.EV_DONE) != 0) {
            haptic(CONFIRM);
            System.arraycopy(engine.surf, 0, settings.surf, 0, 4);
            System.arraycopy(engine.edge, 0, settings.edge, 0, 4);
            settings.calMask = engine.calMask;
            settings.calibrated = engine.calMask != 0;
            syncSettingsUi(false);
            calSaved = host.onCalibrationChanged();
            ui.calNote = calSaved ? null : "Couldn't save. It applies until the app closes";
            calEndNs = now;
        }
        if ((ev & LevelEngine.EV_FAILED) != 0) {
            haptic(REJECT);
            calEndNs = now;
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
                ui.secondary = !calSaved ? "Couldn't save. It applies until the app closes"
                        : edgeCal ? "Edge offset saved" : "Surface offset saved";
                hinted = !calSaved;
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
        if (ui.calActive) ui.secondaryAccent = hinted;
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

    // ---- actions -----------------------------------------------------------------------------

    @Override
    protected void act(int id) {
        boolean changed = false, calChanged = false;
        switch (id) {
            case Renderer.H_SETTINGS:
                page = 1;
                ui.page.scroll = 0f;
                access.pageChanged(0);
                paneTitle("Settings");
                break;
            case Renderer.H_BACK: closeSettings(); break;
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
            case Renderer.H_HAPTIC: settings.haptics = !settings.haptics; changed = true; break;
            case Renderer.H_KEEPON:
                settings.keepOn = !settings.keepOn;
                setKeepScreenOn(settings.keepOn);
                changed = true;
                break;
            case Renderer.H_RESET:
                // Reset asks; while it asks, the same id is Keep, which drops the question. Focus
                // stays on that id, so a screen reader reads the question from Keep's new label.
                ui.resetConfirm = !ui.resetConfirm;
                break;
            case Renderer.H_ERASE:
                ui.resetConfirm = false;
                settings.clearCalibration();
                for (int i = 0; i < 4; i++) engine.surf[i] = engine.edge[i] = 0f;
                engine.calMask = 0;
                engine.refresh();
                changed = true;
                calChanged = true;
                break;
            case Renderer.H_CAL_CANCEL: engine.cancelCal(); break;
            case Renderer.H_CAL_READY: engine.manualReady(); break;
            default:
                if (id >= Renderer.H_UNITS && id < Renderer.H_UNITS + Settings.UNITS.length) {
                    settings.units = id - Renderer.H_UNITS;
                } else if (id >= Renderer.H_TOL && id < Renderer.H_TOL + Settings.TOLERANCE.length) {
                    settings.tolIdx = id - Renderer.H_TOL;
                } else if (id >= Renderer.H_MODE && id < Renderer.H_MODE + Settings.LOCKS.length) {
                    settings.lock = id - Renderer.H_MODE;
                } else if (id >= Renderer.H_THEME && id < Renderer.H_THEME + ui.themes.length) {
                    setTheme(id - Renderer.H_THEME);
                    break;
                } else {
                    return;
                }
                changed = true;
                break;
        }
        if (changed) {
            engine.lock = settings.lock;
            syncSettingsUi(false);
            host.onSettingsChanged();
        }
        if (calChanged) {
            // the row now reads "Not calibrated", or shows the error, from the same id
            boolean saved = host.onCalibrationChanged();
            ui.calNote = saved ? null : "Erased for now; couldn't save, so it returns on restart";
        }
    }

    /** Back to the level, keyboard focus to the settings button; an unanswered reset question is dropped, never answered for the user. */
    private void closeSettings() {
        page = 0;
        ui.resetConfirm = false;
        access.pageChanged(Renderer.H_SETTINGS);
        paneTitle(null);
    }

    @Override
    protected void themeChanged(Palette p) {
        settings.theme = p.name;
        host.onSettingsChanged();
    }

    private void syncSettingsUi(boolean snap) {
        ui.units = settings.units;
        ui.tolIdx = settings.tolIdx;
        ui.mode = settings.lock;
        ui.haptics = settings.haptics;
        ui.keepOn = settings.keepOn;
        ui.calibrated = settings.calibrated;
        ui.calSummary = calSummary(settings.calMask);
        ui.guideLabels = settings.units == 0;
        if (snap) {
            ui.hapticsT = settings.haptics ? 1f : 0f;
            ui.keepOnT = settings.keepOn ? 1f : 0f;
            ui.unitsPos = ui.units;
            ui.tolPos = ui.tolIdx;
            ui.modePos = ui.mode;
        }
        setHaptics(settings.haptics);
        dirty = true;
    }

    /** "Face up, 2 edges" style list of the calibrated poses, "Not calibrated" when none. */
    static String calSummary(int mask) {
        StringBuilder b = new StringBuilder();
        if ((mask & 1) != 0) b.append("face up");
        if ((mask & 2) != 0) b.append(b.length() > 0 ? ", " : "").append("face down");
        int edges = Integer.bitCount((mask >> 2) & 0xF);
        if (edges > 0) b.append(b.length() > 0 ? ", " : "").append(edges).append(edges == 1 ? " edge" : " edges");
        if (b.length() == 0) return "Not calibrated";
        b.setCharAt(0, Character.toUpperCase(b.charAt(0)));
        return b.toString();
    }

    @Override
    public String label(int id) {
        switch (id) {
            case Renderer.H_SETTINGS: return "Settings";
            case Renderer.H_CALIBRATE: return engine.cal != LevelEngine.CAL_IDLE ? "Stop calibration" : "Calibrate";
            case Renderer.H_HOLD: return held ? "Resume live reading" : "Hold reading";
            case Renderer.H_READOUT: return ui.calActive ? ui.calTitle + ". " + ui.secondary : readingText();
            case Renderer.H_BACK: return "Back";
            case Renderer.H_CAL_CANCEL: return "Cancel calibration";
            case Renderer.H_CAL_READY: return "Ready, it is turned around";
            default: return super.label(id);
        }
    }

    // ---- math --------------------------------------------------------------------------------

    private static float tanh(float x) {
        return (float) Math.tanh(x);
    }
}
