package me.saatvik.level;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import me.saatvik.hairline.Kit;
import me.saatvik.hairline.Motion;
import me.saatvik.hairline.Palette;
import me.saatvik.hairline.SettingsPage;

/**
 * Draws one frame from a {@link Ui}. Depends on android.graphics only.
 *
 * The visual grammar is the hairline design system's (hairline/DESIGN.md); this class draws the
 * instrument and its readout and declares the settings rows. The level page has no header.
 *
 * Layout adapts to the window: the surface instrument stacks vial over readout, moves the readout
 * beside the vial in short wide windows, and scales down as a last resort; the edge instrument
 * scales to fit its square; the settings page scrolls.
 */
final class Renderer {
    static final int H_SETTINGS = 3, H_CALIBRATE = 4, H_HOLD = 5, H_READOUT = 6, H_BACK = 7,
            H_HAPTIC = 16, H_KEEPON = 17, H_CAL_CANCEL = 20, H_CAL_READY = 21;
    /** Calibration row: Reset; while it asks, Keep takes Reset's id (keyboard focus stays on the safe answer) and Erase the next. */
    static final int H_RESET = 18, H_ERASE = H_RESET + 1;
    /** Option ids: base + index. */
    static final int H_UNITS = 30, H_TOL = 40, H_MODE = 50, H_THEME = 60;

    private static final String[] RESET = {"Reset"}, CONFIRM = {"Keep", "Erase"}, NO_BUTTONS = {};

    private final Kit k;
    private final Paint fill, stroke, text;
    private final RectF rect = new RectF();

    private Ui u;
    private float dp, sp;
    private float left, usable, cx0; // horizontal safe area: left edge, width, centre

    Renderer(Typeface light, Typeface regular) {
        k = new Kit(light, regular);
        fill = k.fill;
        stroke = k.stroke;
        text = k.text;
    }

    void draw(Canvas c, Ui ui) {
        u = ui;
        dp = ui.dp;
        sp = ui.sp;
        left = ui.insetL;
        usable = Math.max(1f, ui.width - ui.insetL - ui.insetR);
        cx0 = left + usable / 2f;
        Palette p = ui.pal;
        k.begin(ui);
        c.drawColor(p.bg);

        float pt = ui.pageT;
        boolean levelActive = pt < 0.5f;
        if (pt < 0.999f) {
            k.a = 1f - pt;
            k.hitDy = -16f * dp * pt;
            c.save();
            c.translate(0f, k.hitDy);
            drawLevelPage(c, p, levelActive);
            c.restore();
        }
        if (pt > 0.001f) {
            k.a = pt;
            k.hitDy = 24f * dp * (1f - pt);
            c.save();
            c.translate(0f, k.hitDy);
            drawSettingsPage(c, !levelActive);
            c.restore();
            k.hitDy = 0f;
            k.header(c, "Settings", left, cx0, ui.insetT + 32f * dp, H_BACK, pt >= 0.5f);
        }
        k.a = 1f;
        k.hitDy = 0f;
        k.feedback(c);
    }

    // ---- level page --------------------------------------------------------------------------

    private void drawLevelPage(Canvas c, Palette p, boolean active) {
        float top = u.insetT + 24f * dp;
        float bottom = u.height - u.insetB - 80f * dp;
        float pillRow = 64f * dp;
        float area = Math.max(1f, bottom - top - pillRow);
        float base = k.a;
        float mt = u.modeT;
        if (mt < 0.999f) {
            k.a = base * (1f - mt);
            drawSurface(c, p, top, area, active && mt < 0.5f, mt < 0.5f);
        }
        if (mt > 0.001f) {
            k.a = base * mt;
            drawEdge(c, p, top, area, active && mt >= 0.5f, mt >= 0.5f);
        }
        k.a = base;
        if (u.calActive) drawCalPills(c, cx0, top + area + pillRow / 2f, active);
        drawBottomBar(c, p, active);
    }

    // Small text keeps a legible size when an instrument is drawn scaled down: under canvas scale s
    // the caption stays >= 10sp and the secondary line >= 11sp on screen.
    private float capSize(float s) {
        return Math.max(12f * sp, 10f * sp / s);
    }

    private float secSize(float s) {
        return Math.max(13f * sp, 11f * sp / s);
    }

    /** Height of the caption + value + two-line secondary block drawn by {@link #drawReadout}. */
    private float readoutH(float s) {
        return capSize(s) * 1.17f + 12f * dp + 64f * sp + secSize(s) * 2.68f;
    }

    private void drawSurface(Canvas c, Palette p, float top, float area, boolean active, boolean primary) {
        float readH = readoutH(1f), gap = 24f * dp, minD = 150f * dp;
        float d = Math.min(usable - 64f * dp, area - readH - gap);

        // short, wide window: readout beside the vial
        float sideW = usable - 32f * dp;
        float d2 = Math.min(area - 16f * dp, sideW * 0.45f);
        float rw = Math.min(sideW - d2 - 20f * dp, 360f * dp);
        if (d < minD && d2 >= 96f * dp && rw >= 160f * dp && area >= readH) {
            float x0 = cx0 - (d2 + 20f * dp + rw) / 2f;
            float vx = x0 + d2 / 2f, vy = top + area / 2f;
            drawVial(c, p, vx, vy, d2 / 2f, 1f, primary);
            float rx = x0 + d2 + 20f * dp + rw / 2f;
            float ry = top + (area - readH) / 2f;
            drawReadout(c, p, rx, ry, rw, 1f);
            if (active) k.hit(H_READOUT, x0, top, x0 + d2 + 20f * dp + rw, top + area);
            return;
        }

        // stacked; scaled down when even the minimum vial does not fit
        float dd = Math.max(d, minD);
        float blockH = dd + gap + readH;
        float wFit = (usable - 32f * dp) / Math.max(dd, 1f);
        float s = Math.min(1f, Math.min(area / blockH, wFit));
        for (int i = 0; i < 2 && s < 1f; i++) {          // larger minimum text grows the block
            blockH = dd + gap + readoutH(s);
            s = Math.min(1f, Math.min(area / blockH, wFit));
        }
        float y0 = top + Math.max(0f, (area - blockH * s) / 2f);
        float cx = cx0;
        c.save();
        if (s < 1f) c.scale(s, s, cx, y0);
        drawVial(c, p, cx, y0 + dd / 2f, dd / 2f, s, primary);
        drawReadout(c, p, cx, y0 + dd + gap, (usable - 48f * dp) / s, s);
        c.restore();
        if (active) {
            float hw = Math.max(dd * s, usable - 48f * dp) / 2f;
            k.hit(H_READOUT, cx - hw, y0, cx + hw, y0 + blockH * s);
        }
    }

    /** Bullseye vial of radius R; {@code scale} is the canvas scale in force, for the travel report. */
    private void drawVial(Canvas c, Palette p, float cx, float cy, float R, float scale, boolean primary) {
        float b = R * 0.17f;
        float travel = R - b - 4f * dp;
        if (primary) u.travelPx = Math.max(1f, travel * scale);
        float tr = b * 1.22f;
        float lt = u.levelT;

        // vial and guide rings
        stroke.setStrokeWidth(1.25f * dp);
        stroke.setColor(k.col(p.line));
        c.drawCircle(cx, cy, R, stroke);
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(k.col(p.line, 0.8f));
        float g2 = u.guide2 * travel, g5 = u.guide5 * travel;
        c.drawCircle(cx, cy, g2, stroke);
        c.drawCircle(cx, cy, g5, stroke);
        if (u.guideLabels) {
            text.setTypeface(k.regular);
            text.setTextSize(10f * sp);
            text.setColor(k.col(p.dim, 0.9f));
            text.setTextAlign(Paint.Align.LEFT);
            float q = 0.7071f;
            c.drawText("2°", cx + g2 * q + 3f * dp, cy - g2 * q - 3f * dp, text);
            c.drawText("5°", cx + g5 * q + 3f * dp, cy - g5 * q - 3f * dp, text);
            text.setTextAlign(Paint.Align.CENTER);
        }

        // crosshair, broken around the target ring
        stroke.setColor(k.col(p.line));
        float inner = tr + 6f * dp, outer = R - 10f * dp;
        if (outer > inner) {
            c.drawLine(cx - outer, cy, cx - inner, cy, stroke);
            c.drawLine(cx + inner, cy, cx + outer, cy, stroke);
            c.drawLine(cx, cy - outer, cx, cy - inner, stroke);
            c.drawLine(cx, cy + inner, cx, cy + outer, stroke);
        }

        // calibration progress around the vial
        if (u.calActive && u.calProgress > 0f) {
            float rr = R + 8f * dp;
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr);
            stroke.setStrokeWidth(2f * dp);
            stroke.setColor(k.col(p.accent));
            c.drawArc(rect, -90f, 360f * u.calProgress, false, stroke);
        }

        // bubble
        float bx = cx + u.bubX * travel, by = cy + u.bubY * travel;
        fill.setColor(k.col(p.accent));
        c.drawCircle(bx, by, b, fill);
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(k.col(p.onAccent, 0.8f));
        float q = b * 0.3f;
        c.drawLine(bx - q, by, bx + q, by, stroke);
        c.drawLine(bx, by - q, bx, by + q, stroke);

        // target ring sits on top: it is a marking on the glass
        stroke.setStrokeWidth((1f + 1.25f * lt) * dp);
        stroke.setColor(k.col(Palette.mix(p.dim, p.accent, lt)));
        c.drawCircle(cx, cy, tr, stroke);
        if (u.pulseT >= 0f) {
            float pq = u.pulseT;
            stroke.setStrokeWidth(1.5f * dp);
            stroke.setColor(k.col(p.accent, (1f - pq) * 0.8f));
            c.drawCircle(cx, cy, tr + pq * b * 1.7f, stroke);
        }
    }

    private void drawEdge(Canvas c, Palette p, float top, float area, boolean active, boolean primary) {
        // The instrument turns with the device: tube on top, readout below, in a box whose
        // length follows the screen room along the tube and whose depth is scaled to the room
        // across it. Both follow the (animated) rotation. While not calibrating the empty pill
        // row is free space too.
        float availH = area + (u.calActive ? 0f : 48f * dp);
        float cx = cx0, cy = top + availH / 2f;
        float availW = Math.max(1f, usable - 32f * dp);
        float sn = (float) Math.sin(Math.toRadians(u.rotation));
        float cs = (float) Math.cos(Math.toRadians(u.rotation));
        float along = availW + (availH - availW) * sn * sn;
        float across = availH + (availW - availH) * sn * sn;
        float T = Math.max(1f, Math.min(along, 520f * dp));          // on-screen box length
        float h = Motion.clamp(T * 0.88f * 0.15f, 44f * dp, 68f * dp);
        float s = 1f, H = 0f;
        for (int i = 0; i < 3; i++) {                     // larger minimum text grows the box
            H = 34f * dp + h + 30f * dp + readoutH(s);
            s = Math.min(1f, across / H);
        }
        float W = T / s;                                  // virtual length: the tube keeps its screen length
        float L = W * 0.88f;

        c.save();
        c.rotate(u.rotation, cx, cy);
        if (s < 1f) c.scale(s, s, cx, cy);

        float ty = cy - H / 2f + 34f * dp + h / 2f;
        float inset = 5f * dp;
        float bl = L * 0.2f;
        float travel = L / 2f - bl / 2f - inset;
        if (primary) u.travelPx = Math.max(1f, travel * s);
        float lt = u.levelT;
        float tubeTop = ty - h / 2f, tubeBot = ty + h / 2f;

        // scale ticks above the tube
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(k.col(p.line));
        for (float t : u.edgeTicks) {
            float x = t * travel;
            c.drawLine(cx - x, tubeTop - 14f * dp, cx - x, tubeTop - 7f * dp, stroke);
            c.drawLine(cx + x, tubeTop - 14f * dp, cx + x, tubeTop - 7f * dp, stroke);
        }
        if (u.guideLabels) {
            text.setTypeface(k.regular);
            text.setTextSize(10f * sp);
            text.setColor(k.col(p.dim, 0.9f));
            float x = u.edgeTicks[2] * travel;
            c.drawText("5°", cx - x, tubeTop - 20f * dp, text);
            c.drawText("5°", cx + x, tubeTop - 20f * dp, text);
        }

        // tube
        stroke.setStrokeWidth(1.25f * dp);
        stroke.setColor(k.col(p.line));
        rect.set(cx - L / 2f, tubeTop, cx + L / 2f, tubeBot);
        c.drawRoundRect(rect, h / 2f, h / 2f, stroke);

        // calibration progress under the tube
        if (u.calActive && u.calProgress > 0f) {
            stroke.setStrokeWidth(2f * dp);
            stroke.setColor(k.col(p.accent));
            float y = tubeBot + 12f * dp;
            c.drawLine(cx - L / 2f + h / 2f, y, cx - L / 2f + h / 2f + (L - h) * u.calProgress, y, stroke);
        }

        // bubble
        float bxc = cx + u.bubX * travel;
        float bh = h - 2f * inset;
        rect.set(bxc - bl / 2f, tubeTop + inset, bxc + bl / 2f, tubeBot - inset);
        fill.setColor(k.col(p.accent));
        c.drawRoundRect(rect, bh / 2f, bh / 2f, fill);
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(k.col(p.onAccent, 0.8f));
        c.drawLine(bxc, ty - bh * 0.22f, bxc, ty + bh * 0.22f, stroke);

        // level marks, on the glass
        float mx = bl / 2f + 4f * dp;
        stroke.setStrokeWidth((1f + 1.25f * lt) * dp);
        stroke.setColor(k.col(Palette.mix(p.dim, p.accent, lt)));
        c.drawLine(cx - mx, tubeTop - 4f * dp, cx - mx, tubeBot + 4f * dp, stroke);
        c.drawLine(cx + mx, tubeTop - 4f * dp, cx + mx, tubeBot + 4f * dp, stroke);
        if (u.pulseT >= 0f) {
            float q = u.pulseT, grow = q * 18f * dp;
            stroke.setStrokeWidth(1.5f * dp);
            stroke.setColor(k.col(p.accent, (1f - q) * 0.8f));
            rect.set(cx - mx - grow, tubeTop - 4f * dp - grow, cx + mx + grow, tubeBot + 4f * dp + grow);
            float r = (h + 8f * dp) / 2f + grow;
            c.drawRoundRect(rect, r, r, stroke);
        }

        drawReadout(c, p, cx, tubeBot + 30f * dp, W - 24f * dp, s);
        c.restore();
        if (active) {
            float hw = Math.max(T * Math.abs(cs), H * s * Math.abs(sn)) / 2f;
            float hh = Math.max(H * s * Math.abs(cs), T * Math.abs(sn)) / 2f;
            k.hit(H_READOUT, cx - hw, cy - hh, cx + hw, cy + hh);
        }
    }

    /**
     * Caption, big value, secondary line(s). {@code top} is the top of a {@link #readoutH} block;
     * {@code scale} is the canvas scale in force.
     */
    private void drawReadout(Canvas c, Palette p, float cx, float top, float maxW, float scale) {
        float capSz = capSize(scale), secSz = secSize(scale);
        float capBase = top + capSz * 1.17f;
        float mainBase = capBase + 12f * dp + 47f * sp;
        float secBase = mainBase + 17f * sp + secSz;

        text.setTypeface(k.regular);
        text.setTextSize(capSz);
        boolean capAccent = u.held && !u.calActive;
        text.setColor(k.col(capAccent ? p.accentText : p.dim));
        c.drawText(u.calActive ? (u.calProgress > 0f && u.calProgress < 1f ? "Hold still" : u.caption) : u.caption,
                cx, capBase, text);

        String main = u.calActive ? u.calTitle : u.main;
        String unit = u.calActive ? "" : u.mainUnit;
        float size = (u.calActive ? 34f : 64f) * sp;
        float base = u.calActive ? mainBase - 11f * sp : mainBase;
        text.setTypeface(k.light);
        text.setTextSize(size);
        float mw = text.measureText(main);
        float uw = 0f, us = size * 0.3f;
        if (unit.length() > 0) {
            text.setTextSize(us);
            uw = text.measureText(unit) + 6f * dp;
            text.setTextSize(size);
        }
        float total = mw + uw;
        if (total > maxW && total > 0f) {
            float s = maxW / total;
            size *= s;
            us *= s;
            mw *= s;
            uw *= s;
            total = maxW;
            text.setTextSize(size);
        }
        int mainCol = u.calActive ? p.fg : Palette.mix(p.fg, p.accentText, u.levelT);
        text.setColor(k.col(mainCol));
        text.setTextAlign(Paint.Align.LEFT);
        float x0 = cx - total / 2f;
        c.drawText(main, x0, base, text);
        if (uw > 0f) {
            text.setTypeface(k.regular);
            text.setTextSize(us);
            text.setColor(k.col(p.dim));
            c.drawText(unit, x0 + mw + 6f * dp, base, text);
        }
        text.setTextAlign(Paint.Align.CENTER);

        text.setTypeface(k.regular);
        text.setTextSize(secSz);
        text.setColor(k.col(u.secondaryAccent ? p.accentText : p.dim));
        k.wrapped(c, u.secondary, cx, secBase, maxW, secSz * 1.38f);
    }

    private void drawCalPills(Canvas c, float cx, float cy, boolean active) {
        float h = Kit.CONTROL_H * dp;
        if (u.calShowReady) {
            float off = Math.min(66f * dp, (usable - 24f * dp) / 4f);
            k.pill(c, "Cancel", cx - off, cy, H_CAL_CANCEL, active, false, 2f * off - 12f * dp, h);
            k.pill(c, "Ready", cx + off, cy, H_CAL_READY, active, true, 2f * off - 12f * dp, h);
        } else {
            k.pill(c, "Cancel", cx, cy, H_CAL_CANCEL, active, false, usable - 24f * dp, h);
        }
    }

    private void drawBottomBar(Canvas c, Palette p, boolean active) {
        float y = u.height - u.insetB - 40f * dp;
        float x1 = left + usable * 0.25f, x2 = left + usable * 0.5f, x3 = left + usable * 0.75f;
        k.settingsIcon(c, x1, y);
        iconTarget(c, x2, y, k.col(u.calActive ? p.accent : p.dim));
        iconHold(c, x3, y, u.held);
        if (active) {
            float r = Math.min(30f * dp, usable / 8f);
            k.hitCircle(H_SETTINGS, x1 - r, y - 30f * dp, x1 + r, y + 30f * dp, x1, y, 22f * dp);
            k.hitCircle(H_CALIBRATE, x2 - r, y - 30f * dp, x2 + r, y + 30f * dp, x2, y, 22f * dp);
            k.hitCircle(H_HOLD, x3 - r, y - 30f * dp, x3 + r, y + 30f * dp, x3, y, 22f * dp);
        }
    }

    // ---- settings page -----------------------------------------------------------------------

    private void drawSettingsPage(Canvas c, boolean active) {
        SettingsPage s = u.page;
        s.clear();
        s.segmented(H_UNITS, "Units", null, u.unitOpts, u.units, u.unitsPos);
        s.segmented(H_TOL, "Level tolerance", "Counts as level within ± this angle", u.tolOpts, u.tolIdx, u.tolPos);
        s.segmented(H_MODE, "Mode", "Auto switches between surface and edge", u.modeOpts, u.mode, u.modePos);
        s.toggle(H_HAPTIC, "Haptic feedback", "Buzz on level and on taps", u.haptics, u.hapticsT);
        s.toggle(H_KEEPON, "Keep screen on", "While the level is open", u.keepOn, u.keepOnT);
        if (u.resetConfirm) {
            s.actions(H_RESET, "Calibration", "Erase the saved offsets for every pose?", true, CONFIRM, 1);
        } else {
            s.actions(H_RESET, "Calibration", u.calNote != null ? u.calNote : u.calSummary, u.calNote != null,
                    u.calibrated ? RESET : NO_BUTTONS, -1);
        }
        s.themes(H_THEME, "Theme", u.themes, u.themeIdx);
        s.draw(c, k, left, usable, u.insetT + 64f * dp, u.height - u.insetB, active);
    }

    // ---- icons -------------------------------------------------------------------------------

    private void iconTarget(Canvas c, float x, float y, int color) {
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(color);
        c.drawCircle(x, y, 7f * dp, stroke);
        c.drawLine(x - 11f * dp, y, x - 4f * dp, y, stroke);
        c.drawLine(x + 4f * dp, y, x + 11f * dp, y, stroke);
        c.drawLine(x, y - 11f * dp, x, y - 4f * dp, stroke);
        c.drawLine(x, y + 4f * dp, x, y + 11f * dp, stroke);
        fill.setColor(color);
        c.drawCircle(x, y, 1.3f * dp, fill);
    }

    /** Hold: two bars; on the accent disc while the reading is held. */
    private void iconHold(Canvas c, float x, float y, boolean held) {
        stroke.setColor(k.iconDisc(c, x, y, held));
        stroke.setStrokeWidth(1.75f * dp);
        c.drawLine(x - 3.5f * dp, y - 6.5f * dp, x - 3.5f * dp, y + 6.5f * dp, stroke);
        c.drawLine(x + 3.5f * dp, y - 6.5f * dp, x + 3.5f * dp, y + 6.5f * dp, stroke);
    }
}
