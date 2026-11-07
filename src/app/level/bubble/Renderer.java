package app.level.bubble;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Draws one frame from a {@link Ui}. Depends on android.graphics only.
 *
 * Visual grammar: flat colour, hairline strokes, one accent, circles as the primary shape,
 * everything centred on a single column, outlined pills for actions, filled accent circles
 * for "on" states, theme name flanked by chevrons at the top.
 *
 * Layout adapts to the window: the surface instrument stacks vial over readout, moves the readout
 * beside the vial in short wide windows, and scales down as a last resort; the edge instrument
 * scales to fit its square; the settings page scrolls.
 */
final class Renderer {
    static final int H_THEME_PREV = 1, H_THEME_NEXT = 2, H_SETTINGS = 3, H_CALIBRATE = 4, H_HOLD = 5,
            H_READOUT = 6, H_BACK = 7,
            H_UNITS_PREV = 10, H_UNITS_NEXT = 11, H_TOL_PREV = 12, H_TOL_NEXT = 13,
            H_MODE_PREV = 14, H_MODE_NEXT = 15, H_HAPTIC = 16, H_KEEPON = 17, H_RESET = 18,
            H_CAL_CANCEL = 20, H_CAL_READY = 21;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Typeface light, regular;

    private Ui u;
    private float dp, sp;
    private float a = 1f;            // alpha multiplier for the layer being drawn
    private float left, usable, cx0; // horizontal safe area: left edge, width, centre
    private float hitDy;             // canvas translation of the layer being drawn, applied to hit rects
    private float clipT = Float.NEGATIVE_INFINITY, clipB = Float.POSITIVE_INFINITY;

    Renderer(Typeface light, Typeface regular) {
        this.light = light;
        this.regular = regular;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
    }

    void draw(Canvas c, Ui ui) {
        u = ui;
        dp = ui.dp;
        sp = ui.sp;
        left = ui.insetL;
        usable = Math.max(1f, ui.width - ui.insetL - ui.insetR);
        cx0 = left + usable / 2f;
        ui.resetHits();
        Palette p = ui.pal;
        c.drawColor(p.bg);

        float pt = ui.pageT;
        boolean levelActive = pt < 0.5f;
        if (pt < 0.999f) {
            a = 1f - pt;
            hitDy = -16f * dp * pt;
            c.save();
            c.translate(0f, hitDy);
            drawLevelPage(c, p, levelActive);
            c.restore();
        }
        if (pt > 0.001f) {
            a = pt;
            hitDy = 24f * dp * (1f - pt);
            c.save();
            c.translate(0f, hitDy);
            drawSettingsPage(c, p, !levelActive);
            c.restore();
        }
        a = 1f;
        hitDy = 0f;
        drawHeader(c, p);
        drawPressed(c, p);
    }

    // ---- header ------------------------------------------------------------------------------

    private void drawHeader(Canvas c, Palette p) {
        float cy = u.insetT + 32f * dp, cx = cx0;
        text.setTypeface(regular);
        text.setTextSize(14f * sp);
        text.setColor(col(p.fg));
        float tw = text.measureText(u.themeName);
        textCentered(c, u.themeName, cx, cy);
        float off = tw / 2f + 22f * dp;
        chevron(c, cx - off, cy, -1, 5f * dp, 1.5f * dp, col(p.dim));
        chevron(c, cx + off, cy, 1, 5f * dp, 1.5f * dp, col(p.dim));
        hit(H_THEME_PREV, cx - off - 30f * dp, cy - 24f * dp, cx - 1f, cy + 24f * dp, cx - off, cy, 20f * dp);
        hit(H_THEME_NEXT, cx + 1f, cy - 24f * dp, cx + off + 30f * dp, cy + 24f * dp, cx + off, cy, 20f * dp);

        if (u.pageT > 0.001f) {
            float bx = left + 30f * dp;
            a = u.pageT;
            chevron(c, bx, cy, -1, 6f * dp, 1.5f * dp, col(p.fg));
            a = 1f;
            if (u.pageT >= 0.5f) {
                hit(H_BACK, left, cy - 28f * dp, bx + 30f * dp, cy + 28f * dp, bx, cy, 22f * dp);
            }
        }
    }

    // ---- level page --------------------------------------------------------------------------

    private void drawLevelPage(Canvas c, Palette p, boolean active) {
        float top = u.insetT + 64f * dp;
        float bottom = u.height - u.insetB - 80f * dp;
        float pillRow = 64f * dp;
        float area = Math.max(1f, bottom - top - pillRow);
        float base = a;
        float mt = u.modeT;
        if (mt < 0.999f) {
            a = base * (1f - mt);
            drawSurface(c, p, top, area, active && mt < 0.5f, mt < 0.5f);
        }
        if (mt > 0.001f) {
            a = base * mt;
            drawEdge(c, p, top, area, active && mt >= 0.5f, mt >= 0.5f);
        }
        a = base;
        if (u.calActive) drawCalPills(c, p, cx0, top + area + pillRow / 2f, active);
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
            if (active) hit(H_READOUT, x0, top, x0 + d2 + 20f * dp + rw, top + area, 0, 0, -1f);
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
            float hw = Math.max(dd / 2f, (usable - 48f * dp) / 2f) * s;
            hit(H_READOUT, cx - hw, y0, cx + hw, y0 + blockH * s, 0, 0, -1f);
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
        stroke.setColor(col(p.line));
        c.drawCircle(cx, cy, R, stroke);
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(col(p.line, 0.8f));
        float g2 = u.guide2 * travel, g5 = u.guide5 * travel;
        c.drawCircle(cx, cy, g2, stroke);
        c.drawCircle(cx, cy, g5, stroke);
        if (u.guideLabels) {
            text.setTypeface(regular);
            text.setTextSize(10f * sp);
            text.setColor(col(p.dim, 0.9f));
            text.setTextAlign(Paint.Align.LEFT);
            float k = 0.7071f;
            c.drawText("2°", cx + g2 * k + 3f * dp, cy - g2 * k - 3f * dp, text);
            c.drawText("5°", cx + g5 * k + 3f * dp, cy - g5 * k - 3f * dp, text);
            text.setTextAlign(Paint.Align.CENTER);
        }

        // crosshair, broken around the target ring
        stroke.setColor(col(p.line));
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
            stroke.setColor(col(p.accent));
            c.drawArc(rect, -90f, 360f * u.calProgress, false, stroke);
        }

        // bubble
        float bx = cx + u.bubX * travel, by = cy + u.bubY * travel;
        fill.setColor(col(p.accent));
        c.drawCircle(bx, by, b, fill);
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(col(p.onAccent, 0.8f));
        float k = b * 0.3f;
        c.drawLine(bx - k, by, bx + k, by, stroke);
        c.drawLine(bx, by - k, bx, by + k, stroke);

        // target ring sits on top: it is a marking on the glass
        stroke.setStrokeWidth((1f + 1.25f * lt) * dp);
        stroke.setColor(col(Palette.mix(p.dim, p.accent, lt)));
        c.drawCircle(cx, cy, tr, stroke);
        if (u.pulseT >= 0f) {
            float q = u.pulseT;
            stroke.setStrokeWidth(1.5f * dp);
            stroke.setColor(col(p.accent, (1f - q) * 0.8f));
            c.drawCircle(cx, cy, tr + q * b * 1.7f, stroke);
        }
    }

    private void drawEdge(Canvas c, Palette p, float top, float area, boolean active, boolean primary) {
        // The instrument turns with the device. Its content box (tube on top, readout below) keeps
        // one size in every orientation and is scaled down when the turned box would not fit.
        // While not calibrating the empty pill row is free space too.
        float availH = area + (u.calActive ? 0f : 48f * dp);
        float cx = cx0, cy = top + availH / 2f;
        float availW = Math.max(1f, usable - 32f * dp);
        // Tube length follows the shorter side, so turning the tube upright never squeezes it much.
        float W = Math.min(availW, Math.max(availH, 160f * dp));
        float L = W * 0.88f;
        float h = clamp(L * 0.15f, 44f * dp, 68f * dp);
        float sn = (float) Math.sin(Math.toRadians(u.rotation));
        float s = 1f, H = 0f;
        for (int i = 0; i < 3; i++) {                     // larger minimum text grows the box
            H = 34f * dp + h + 30f * dp + readoutH(s);
            float s0 = Math.min(1f, Math.min(availW / W, availH / H));    // tube horizontal on screen
            float s90 = Math.min(1f, Math.min(availW / H, availH / W));   // tube vertical on screen
            s = s0 + (s90 - s0) * sn * sn;
        }

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
        stroke.setColor(col(p.line));
        for (float t : u.edgeTicks) {
            float x = t * travel;
            c.drawLine(cx - x, tubeTop - 14f * dp, cx - x, tubeTop - 7f * dp, stroke);
            c.drawLine(cx + x, tubeTop - 14f * dp, cx + x, tubeTop - 7f * dp, stroke);
        }
        if (u.guideLabels) {
            text.setTypeface(regular);
            text.setTextSize(10f * sp);
            text.setColor(col(p.dim, 0.9f));
            float x = u.edgeTicks[2] * travel;
            c.drawText("5°", cx - x, tubeTop - 20f * dp, text);
            c.drawText("5°", cx + x, tubeTop - 20f * dp, text);
        }

        // tube
        stroke.setStrokeWidth(1.25f * dp);
        stroke.setColor(col(p.line));
        rect.set(cx - L / 2f, tubeTop, cx + L / 2f, tubeBot);
        c.drawRoundRect(rect, h / 2f, h / 2f, stroke);

        // calibration progress under the tube
        if (u.calActive && u.calProgress > 0f) {
            stroke.setStrokeWidth(2f * dp);
            stroke.setColor(col(p.accent));
            float y = tubeBot + 12f * dp;
            c.drawLine(cx - L / 2f + h / 2f, y, cx - L / 2f + h / 2f + (L - h) * u.calProgress, y, stroke);
        }

        // bubble
        float bxc = cx + u.bubX * travel;
        float bh = h - 2f * inset;
        rect.set(bxc - bl / 2f, tubeTop + inset, bxc + bl / 2f, tubeBot - inset);
        fill.setColor(col(p.accent));
        c.drawRoundRect(rect, bh / 2f, bh / 2f, fill);
        stroke.setStrokeWidth(1f * dp);
        stroke.setColor(col(p.onAccent, 0.8f));
        c.drawLine(bxc, ty - bh * 0.22f, bxc, ty + bh * 0.22f, stroke);

        // level marks, on the glass
        float mx = bl / 2f + 4f * dp;
        stroke.setStrokeWidth((1f + 1.25f * lt) * dp);
        stroke.setColor(col(Palette.mix(p.dim, p.accent, lt)));
        c.drawLine(cx - mx, tubeTop - 4f * dp, cx - mx, tubeBot + 4f * dp, stroke);
        c.drawLine(cx + mx, tubeTop - 4f * dp, cx + mx, tubeBot + 4f * dp, stroke);
        if (u.pulseT >= 0f) {
            float q = u.pulseT, grow = q * 18f * dp;
            stroke.setStrokeWidth(1.5f * dp);
            stroke.setColor(col(p.accent, (1f - q) * 0.8f));
            rect.set(cx - mx - grow, tubeTop - 4f * dp - grow, cx + mx + grow, tubeBot + 4f * dp + grow);
            float r = (h + 8f * dp) / 2f + grow;
            c.drawRoundRect(rect, r, r, stroke);
        }

        drawReadout(c, p, cx, tubeBot + 30f * dp, W - 24f * dp, s);
        c.restore();
        if (active) {
            float hw = Math.max(W * Math.abs((float) Math.cos(Math.toRadians(u.rotation))), H * Math.abs(sn)) * s / 2f;
            float hh = Math.max(H * Math.abs((float) Math.cos(Math.toRadians(u.rotation))), W * Math.abs(sn)) * s / 2f;
            hit(H_READOUT, cx - hw, cy - hh, cx + hw, cy + hh, 0, 0, -1f);
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

        text.setTypeface(regular);
        text.setTextSize(capSz);
        boolean capAccent = u.held && !u.calActive;
        text.setColor(col(capAccent ? p.accentText : p.dim));
        c.drawText(u.calActive ? (u.calProgress > 0f && u.calProgress < 1f ? "Hold still" : u.caption) : u.caption,
                cx, capBase, text);

        String main = u.calActive ? u.calTitle : u.main;
        String unit = u.calActive ? "" : u.mainUnit;
        float size = (u.calActive ? 34f : 64f) * sp;
        float base = u.calActive ? mainBase - 11f * sp : mainBase;
        text.setTypeface(light);
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
        text.setColor(col(mainCol));
        text.setTextAlign(Paint.Align.LEFT);
        float x0 = cx - total / 2f;
        c.drawText(main, x0, base, text);
        if (uw > 0f) {
            text.setTypeface(regular);
            text.setTextSize(us);
            text.setColor(col(p.dim));
            c.drawText(unit, x0 + mw + 6f * dp, base, text);
        }
        text.setTextAlign(Paint.Align.CENTER);

        text.setTypeface(regular);
        text.setTextSize(secSz);
        text.setColor(col(u.secondaryAccent ? p.accentText : p.dim));
        wrapped(c, u.secondary, cx, secBase, maxW, secSz * 1.38f);
    }

    private void drawCalPills(Canvas c, Palette p, float cx, float cy, boolean active) {
        if (u.calShowReady) {
            float off = Math.min(66f * dp, (usable - 24f * dp) / 4f);
            pill(c, p, "Cancel", cx - off, cy, H_CAL_CANCEL, active, false, 2f * off - 12f * dp);
            pill(c, p, "Ready", cx + off, cy, H_CAL_READY, active, true, 2f * off - 12f * dp);
        } else {
            pill(c, p, "Cancel", cx, cy, H_CAL_CANCEL, active, false, usable - 24f * dp);
        }
    }

    private void drawBottomBar(Canvas c, Palette p, boolean active) {
        float y = u.height - u.insetB - 40f * dp;
        float x1 = left + usable * 0.25f, x2 = left + usable * 0.5f, x3 = left + usable * 0.75f;
        iconSliders(c, x1, y, col(p.dim), col(p.bg));
        iconTarget(c, x2, y, col(u.calActive ? p.accent : p.dim));
        iconHold(c, p, x3, y, u.held);
        if (active) {
            float r = Math.min(30f * dp, usable / 8f);
            hit(H_SETTINGS, x1 - r, y - 30f * dp, x1 + r, y + 30f * dp, x1, y, 22f * dp);
            hit(H_CALIBRATE, x2 - r, y - 30f * dp, x2 + r, y + 30f * dp, x2, y, 22f * dp);
            hit(H_HOLD, x3 - r, y - 30f * dp, x3 + r, y + 30f * dp, x3, y, 22f * dp);
        }
    }

    // ---- settings page -----------------------------------------------------------------------

    private void drawSettingsPage(Canvas c, Palette p, boolean active) {
        float cx = cx0;
        float viewTop = u.insetT + 56f * dp, viewBottom = u.height - u.insetB;
        float top = u.insetT + 64f * dp;
        float rowsTop = top + 60f * dp;
        float minRow = 18f * sp + 60f * dp;
        float rh = Math.max(minRow, Math.min(96f * dp, (viewBottom - 16f * dp - rowsTop) / 6f));
        float contentBottom = rowsTop + 6f * rh + 16f * dp;
        u.settingsMaxScroll = Math.max(0f, contentBottom - viewBottom);
        float scroll = clamp(u.settingsScroll, 0f, u.settingsMaxScroll);

        c.save();
        c.clipRect(0f, viewTop, u.width, viewBottom);
        c.translate(0f, -scroll);
        float saveDy = hitDy;
        hitDy -= scroll;
        clipT = viewTop;
        clipB = viewBottom;

        text.setTypeface(light);
        text.setTextSize(26f * sp);
        text.setColor(col(p.fg));
        c.drawText("Settings", cx, top + 26f * sp, text);

        float y = rowsTop;
        selectorRow(c, p, "Units", u.unitsLabel, y, rh, H_UNITS_PREV, H_UNITS_NEXT, active);
        y += rh;
        selectorRow(c, p, "Level tolerance", u.tolLabel, y, rh, H_TOL_PREV, H_TOL_NEXT, active);
        y += rh;
        selectorRow(c, p, "Mode", u.modeLabel, y, rh, H_MODE_PREV, H_MODE_NEXT, active);
        y += rh;
        toggleRow(c, p, "Haptic feedback", u.hapticsT, y, rh, H_HAPTIC, active);
        y += rh;
        toggleRow(c, p, "Keep screen on", u.keepOnT, y, rh, H_KEEPON, active);
        y += rh;
        rowLabel(c, p, "Calibration", y);
        float cy = controlY(y, rh);
        if (u.calibrated) {
            pill(c, p, "Reset", cx, cy, H_RESET, active, false, usable - 24f * dp);
        } else {
            text.setTypeface(light);
            text.setTextSize(16f * sp);
            text.setColor(col(p.dim));
            textCentered(c, "Factory", cx, cy);
        }

        c.restore();
        hitDy = saveDy;
        clipT = Float.NEGATIVE_INFINITY;
        clipB = Float.POSITIVE_INFINITY;

        // scroll position hint
        if (u.settingsMaxScroll > 0f) {
            float trackH = viewBottom - viewTop - 32f * dp;
            float thumbH = Math.max(24f * dp, trackH * (viewBottom - viewTop) / (contentBottom - viewTop));
            float ty = viewTop + 16f * dp + (trackH - thumbH) * (scroll / u.settingsMaxScroll);
            float tx = left + usable - 6f * dp;
            stroke.setStrokeWidth(2f * dp);
            stroke.setColor(col(p.line));
            c.drawLine(tx, ty, tx, ty + thumbH, stroke);
        }
    }

    private float controlY(float y, float rh) {
        float lab = 18f * sp;
        return y + lab + (rh - lab) / 2f;
    }

    private void rowLabel(Canvas c, Palette p, String label, float y) {
        text.setTypeface(regular);
        text.setTextSize(13f * sp);
        text.setColor(col(p.fg, 0.9f));
        c.drawText(label, cx0, y + 14f * sp, text);
    }

    private void selectorRow(Canvas c, Palette p, String label, String value, float y, float rh,
                             int prevId, int nextId, boolean active) {
        rowLabel(c, p, label, y);
        float cx = cx0, cy = controlY(y, rh);
        text.setTypeface(light);
        text.setTextSize(17f * sp);
        text.setColor(col(p.fg));
        float tw = text.measureText(value);
        textCentered(c, value, cx, cy);
        float off = Math.min(Math.max(tw, 96f * dp) / 2f + 18f * dp, usable / 2f - 24f * dp);
        chevron(c, cx - off, cy, -1, 5f * dp, 1.5f * dp, col(p.dim));
        chevron(c, cx + off, cy, 1, 5f * dp, 1.5f * dp, col(p.dim));
        if (active) {
            float hh = Math.min(26f * dp, rh / 2f);
            hit(prevId, cx - off - 32f * dp, cy - hh, cx - 1f, cy + hh, cx - off, cy, 20f * dp);
            hit(nextId, cx + 1f, cy - hh, cx + off + 32f * dp, cy + hh, cx + off, cy, 20f * dp);
        }
    }

    private void toggleRow(Canvas c, Palette p, String label, float t, float y, float rh, int id, boolean active) {
        rowLabel(c, p, label, y);
        float cx = cx0, cy = controlY(y, rh);
        float r = clamp((rh - 18f * sp) / 2f - 6f * dp, 14f * dp, 22f * dp);
        if (t > 0.001f) {
            fill.setColor(col(p.accent, t));
            c.drawCircle(cx, cy, r, fill);
        }
        if (t < 0.999f) {
            stroke.setStrokeWidth(1f * dp);
            stroke.setColor(col(p.dim, 1f - t));
            c.drawCircle(cx, cy, r, stroke);
        }
        text.setTypeface(regular);
        text.setTextSize(Math.min(11f * sp, r * 0.62f));
        if (t > 0.001f) {
            text.setColor(col(p.onAccentText, t));
            textCentered(c, "ON", cx, cy);
        }
        if (t < 0.999f) {
            text.setColor(col(p.dim, 1f - t));
            textCentered(c, "OFF", cx, cy);
        }
        float hr = Math.max(r + 6f * dp, 24f * dp);
        if (active) hit(id, cx - hr - 12f * dp, cy - hr, cx + hr + 12f * dp, cy + hr, cx, cy, r + 6f * dp);
    }

    // ---- primitives --------------------------------------------------------------------------

    private void pill(Canvas c, Palette p, String label, float cx, float cy, int id, boolean active,
                      boolean filled, float maxW) {
        text.setTypeface(regular);
        text.setTextSize(14f * sp);
        float w = Math.min(Math.max(112f * dp, text.measureText(label) + 48f * dp), Math.max(maxW, 72f * dp));
        float h = 40f * dp;
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        if (filled) {
            fill.setColor(col(p.accent));
            c.drawRoundRect(rect, h / 2f, h / 2f, fill);
            text.setColor(col(p.onAccentText));
        } else {
            stroke.setStrokeWidth(1f * dp);
            stroke.setColor(col(p.dim, 0.7f));
            c.drawRoundRect(rect, h / 2f, h / 2f, stroke);
            text.setColor(col(p.fg));
        }
        textCentered(c, label, cx, cy);
        if (active) hit(id, rect.left - 4f * dp, rect.top - 6f * dp, rect.right + 4f * dp, rect.bottom + 6f * dp, 0, 0, 0f);
    }

    private void drawPressed(Canvas c, Palette p) {
        if (u.pressedId == 0) return;
        Ui.Hit h = u.find(u.pressedId);
        if (h == null || h.vr < 0f) return;
        fill.setColor(Palette.alpha(p.fg, 0.08f));
        if (h.vr > 0f) {
            c.drawCircle(h.vx, h.vy, h.vr, fill);
        } else {
            rect.set(h.rect.left + 4f * dp, h.rect.top + 6f * dp, h.rect.right - 4f * dp, h.rect.bottom - 6f * dp);
            float r = (rect.bottom - rect.top) / 2f;
            c.drawRoundRect(rect, r, r, fill);
        }
    }

    private void chevron(Canvas c, float x, float y, int dir, float size, float w, int color) {
        stroke.setStrokeWidth(w);
        stroke.setColor(color);
        float tip = x + dir * size * 0.5f, back = x - dir * size * 0.5f;
        path.reset();
        path.moveTo(back, y - size);
        path.lineTo(tip, y);
        path.lineTo(back, y + size);
        c.drawPath(path, stroke);
    }

    private void iconSliders(Canvas c, float x, float y, int color, int bg) {
        float s = dp;
        stroke.setStrokeWidth(1.5f * s);
        stroke.setColor(color);
        c.drawLine(x - 10f * s, y - 4.5f * s, x + 10f * s, y - 4.5f * s, stroke);
        c.drawLine(x - 10f * s, y + 4.5f * s, x + 10f * s, y + 4.5f * s, stroke);
        fill.setColor(bg);
        c.drawCircle(x - 3.5f * s, y - 4.5f * s, 3f * s, fill);
        c.drawCircle(x + 4f * s, y + 4.5f * s, 3f * s, fill);
        c.drawCircle(x - 3.5f * s, y - 4.5f * s, 3f * s, stroke);
        c.drawCircle(x + 4f * s, y + 4.5f * s, 3f * s, stroke);
    }

    private void iconTarget(Canvas c, float x, float y, int color) {
        float s = dp;
        stroke.setStrokeWidth(1.5f * s);
        stroke.setColor(color);
        c.drawCircle(x, y, 7f * s, stroke);
        c.drawLine(x - 11f * s, y, x - 4f * s, y, stroke);
        c.drawLine(x + 4f * s, y, x + 11f * s, y, stroke);
        c.drawLine(x, y - 11f * s, x, y - 4f * s, stroke);
        c.drawLine(x, y + 4f * s, x, y + 11f * s, stroke);
        fill.setColor(color);
        c.drawCircle(x, y, 1.3f * s, fill);
    }

    private void iconHold(Canvas c, Palette p, float x, float y, boolean held) {
        float s = dp;
        if (held) {
            fill.setColor(col(p.accent));
            c.drawCircle(x, y, 16f * s, fill);
            stroke.setColor(col(p.onAccent));
        } else {
            stroke.setColor(col(p.dim));
        }
        stroke.setStrokeWidth(1.75f * s);
        c.drawLine(x - 3.5f * s, y - 6.5f * s, x - 3.5f * s, y + 6.5f * s, stroke);
        c.drawLine(x + 3.5f * s, y - 6.5f * s, x + 3.5f * s, y + 6.5f * s, stroke);
    }

    private void textCentered(Canvas c, String s, float cx, float cy) {
        text.getFontMetrics(fm);
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    /** Centred text, wrapped onto at most two lines at word boundaries, the second shrunk to fit. */
    private void wrapped(Canvas c, String s, float cx, float base, float maxW, float lineH) {
        if (s == null || s.length() == 0) return;
        if (text.measureText(s) <= maxW) {
            c.drawText(s, cx, base, text);
            return;
        }
        int cut = -1;
        for (int i = s.indexOf(' '); i > 0; i = s.indexOf(' ', i + 1)) {
            if (text.measureText(s, 0, i) <= maxW) cut = i;
            else break;
        }
        if (cut < 0) {
            fitLine(c, s, cx, base, maxW);
            return;
        }
        c.drawText(s.substring(0, cut), cx, base, text);
        fitLine(c, s.substring(cut + 1), cx, base + lineH, maxW);
    }

    private void fitLine(Canvas c, String s, float cx, float base, float maxW) {
        float size = text.getTextSize(), w = text.measureText(s);
        if (w > maxW && w > 0f) text.setTextSize(size * maxW / w);
        c.drawText(s, cx, base, text);
        text.setTextSize(size);
    }

    private void hit(int id, float l, float t, float r, float b, float vx, float vy, float vr) {
        t += hitDy;
        b += hitDy;
        vy += hitDy;
        if (t < clipT) t = clipT;
        if (b > clipB) b = clipB;
        if (b - t < 8f * dp) return;                 // scrolled out of view
        u.addHit(id, l, t, r, b, vx, vy, vr);
    }

    private int col(int c) {
        return Palette.alpha(c, a);
    }

    private int col(int c, float extra) {
        return Palette.alpha(c, a * extra);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
