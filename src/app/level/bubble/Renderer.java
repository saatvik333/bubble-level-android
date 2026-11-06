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
        ui.resetHits();
        Palette p = ui.pal;
        c.drawColor(p.bg);

        float pt = ui.pageT;
        boolean levelActive = pt < 0.5f;
        if (pt < 0.999f) {
            a = 1f - pt;
            c.save();
            c.translate(0f, -16f * dp * pt);
            drawLevelPage(c, p, levelActive);
            c.restore();
        }
        if (pt > 0.001f) {
            a = pt;
            c.save();
            c.translate(0f, 24f * dp * (1f - pt));
            drawSettingsPage(c, p, !levelActive);
            c.restore();
        }
        a = 1f;
        drawHeader(c, p);
        drawPressed(c, p);
    }

    // ---- header ------------------------------------------------------------------------------

    private void drawHeader(Canvas c, Palette p) {
        float cy = u.insetT + 32f * dp, cx = u.width / 2f;
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
            float bx = u.insetL + 30f * dp;
            a = u.pageT;
            chevron(c, bx, cy, -1, 6f * dp, 1.5f * dp, col(p.fg));
            a = 1f;
            if (u.pageT >= 0.5f) {
                hit(H_BACK, u.insetL, cy - 28f * dp, bx + 30f * dp, cy + 28f * dp, bx, cy, 22f * dp);
            }
        }
    }

    // ---- level page --------------------------------------------------------------------------

    private void drawLevelPage(Canvas c, Palette p, boolean active) {
        float top = u.insetT + 64f * dp;
        float bottom = u.height - u.insetB - 80f * dp;
        float pillRow = 64f * dp;
        float area = bottom - top - pillRow;
        float base = a;
        float mt = u.modeT;
        if (mt < 0.999f) {
            a = base * (1f - mt);
            drawSurface(c, p, top, area, active && mt < 0.5f);
        }
        if (mt > 0.001f) {
            a = base * mt;
            drawEdge(c, p, top, area, active && mt >= 0.5f);
        }
        a = base;
        if (u.calActive) drawCalPills(c, p, u.width / 2f, top + area + pillRow / 2f, active);
        drawBottomBar(c, p, active);
    }

    private void drawSurface(Canvas c, Palette p, float top, float area, boolean active) {
        float readH = 132f * dp, gap = 28f * dp;
        float d = Math.min(u.width - 64f * dp, area - readH - gap);
        d = Math.max(d, 140f * dp);
        float blockH = d + gap + readH;
        float y0 = top + Math.max(0f, (area - blockH) / 2f);
        float cx = u.width / 2f, cy = y0 + d / 2f, R = d / 2f;
        float b = R * 0.17f;
        float travel = R - b - 4f * dp;
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
        c.drawLine(cx - outer, cy, cx - inner, cy, stroke);
        c.drawLine(cx + inner, cy, cx + outer, cy, stroke);
        c.drawLine(cx, cy - outer, cx, cy - inner, stroke);
        c.drawLine(cx, cy + inner, cx, cy + outer, stroke);

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

        if (active) hit(H_READOUT, cx - R, cy - R, cx + R, y0 + blockH, 0, 0, -1f);
        drawReadout(c, p, cx, y0 + d + gap, u.width - 48f * dp);
    }

    private void drawEdge(Canvas c, Palette p, float top, float area, boolean active) {
        float cx = u.width / 2f, cy = top + area / 2f;
        float S = Math.min(u.width - 32f * dp, area);
        c.save();
        c.rotate(u.rotation, cx, cy);

        float L = S * 0.88f;
        float h = clamp(L * 0.15f, 44f * dp, 68f * dp);
        float ty = cy - S * 0.14f;
        float inset = 5f * dp;
        float bl = L * 0.2f;
        float travel = L / 2f - bl / 2f - inset;
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

        drawReadout(c, p, cx, tubeBot + 30f * dp, S - 24f * dp);
        c.restore();
        if (active) hit(H_READOUT, cx - S / 2f, cy - S / 2f, cx + S / 2f, cy + S / 2f, 0, 0, -1f);
    }

    /** Caption, big value, secondary line. {@code top} is the top of a ~132dp block. */
    private void drawReadout(Canvas c, Palette p, float cx, float top, float maxW) {
        Palette pp = p;
        float capBase = top + 14f * sp;
        float mainBase = capBase + 12f * dp + 47f * sp;
        float secBase = mainBase + 30f * sp;

        text.setTypeface(regular);
        text.setTextSize(12f * sp);
        boolean capAccent = u.held && !u.calActive;
        text.setColor(col(capAccent ? pp.accent : pp.dim));
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
        int mainCol = u.calActive ? pp.fg : Palette.mix(pp.fg, pp.accent, u.levelT);
        text.setColor(col(mainCol));
        text.setTextAlign(Paint.Align.LEFT);
        float x0 = cx - total / 2f;
        c.drawText(main, x0, base, text);
        if (uw > 0f) {
            text.setTypeface(regular);
            text.setTextSize(us);
            text.setColor(col(pp.dim));
            c.drawText(unit, x0 + mw + 6f * dp, base, text);
        }
        text.setTextAlign(Paint.Align.CENTER);

        text.setTypeface(regular);
        text.setTextSize(13f * sp);
        text.setColor(col(u.secondaryAccent ? pp.accent : pp.dim));
        wrapped(c, u.secondary, cx, secBase, maxW, 18f * sp);
    }

    private void drawCalPills(Canvas c, Palette p, float cx, float cy, boolean active) {
        if (u.calShowReady) {
            pill(c, p, "Cancel", cx - 66f * dp, cy, H_CAL_CANCEL, active, false);
            pill(c, p, "Ready", cx + 66f * dp, cy, H_CAL_READY, active, true);
        } else {
            pill(c, p, "Cancel", cx, cy, H_CAL_CANCEL, active, false);
        }
    }

    private void drawBottomBar(Canvas c, Palette p, boolean active) {
        float y = u.height - u.insetB - 40f * dp;
        float w = u.width;
        float x1 = w * 0.25f, x2 = w * 0.5f, x3 = w * 0.75f;
        iconSliders(c, x1, y, col(p.dim), col(p.bg));
        iconTarget(c, x2, y, col(u.calActive ? p.accent : p.dim));
        iconHold(c, p, x3, y, u.held);
        if (active) {
            float r = 30f * dp;
            hit(H_SETTINGS, x1 - r, y - r, x1 + r, y + r, x1, y, 22f * dp);
            hit(H_CALIBRATE, x2 - r, y - r, x2 + r, y + r, x2, y, 22f * dp);
            hit(H_HOLD, x3 - r, y - r, x3 + r, y + r, x3, y, 22f * dp);
        }
    }

    // ---- settings page -----------------------------------------------------------------------

    private void drawSettingsPage(Canvas c, Palette p, boolean active) {
        float cx = u.width / 2f;
        float top = u.insetT + 64f * dp;
        text.setTypeface(light);
        text.setTextSize(26f * sp);
        text.setColor(col(p.fg));
        c.drawText("Settings", cx, top + 26f * sp, text);

        float rowsTop = top + 60f * dp;
        float rowsBottom = u.height - u.insetB - 16f * dp;
        float rh = Math.min(96f * dp, (rowsBottom - rowsTop) / 6f);
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
            pill(c, p, "Reset", cx, cy, H_RESET, active, false);
        } else {
            text.setTypeface(light);
            text.setTextSize(16f * sp);
            text.setColor(col(p.dim));
            textCentered(c, "Factory", cx, cy);
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
        c.drawText(label, u.width / 2f, y + 14f * sp, text);
    }

    private void selectorRow(Canvas c, Palette p, String label, String value, float y, float rh,
                             int prevId, int nextId, boolean active) {
        rowLabel(c, p, label, y);
        float cx = u.width / 2f, cy = controlY(y, rh);
        text.setTypeface(light);
        text.setTextSize(17f * sp);
        text.setColor(col(p.fg));
        float tw = text.measureText(value);
        textCentered(c, value, cx, cy);
        float off = Math.max(tw, 96f * dp) / 2f + 18f * dp;
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
        float cx = u.width / 2f, cy = controlY(y, rh);
        float r = Math.min(22f * dp, (rh - 18f * sp) / 2f - 4f * dp);
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
        text.setTextSize(11f * sp);
        if (t > 0.001f) {
            text.setColor(col(p.onAccent, t));
            textCentered(c, "ON", cx, cy);
        }
        if (t < 0.999f) {
            text.setColor(col(p.dim, 1f - t));
            textCentered(c, "OFF", cx, cy);
        }
        if (active) hit(id, cx - r - 12f * dp, cy - r - 6f * dp, cx + r + 12f * dp, cy + r + 6f * dp, cx, cy, r + 6f * dp);
    }

    // ---- primitives --------------------------------------------------------------------------

    private void pill(Canvas c, Palette p, String label, float cx, float cy, int id, boolean active, boolean filled) {
        text.setTypeface(regular);
        text.setTextSize(14f * sp);
        float w = Math.max(112f * dp, text.measureText(label) + 48f * dp);
        float h = 40f * dp;
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        if (filled) {
            fill.setColor(col(p.accent));
            c.drawRoundRect(rect, h / 2f, h / 2f, fill);
            text.setColor(col(p.onAccent));
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

    /** Centred text, wrapped onto at most two lines at word boundaries. */
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
            c.drawText(s, cx, base, text);
            return;
        }
        c.drawText(s.substring(0, cut), cx, base, text);
        c.drawText(s.substring(cut + 1), cx, base + lineH, text);
    }

    private void hit(int id, float l, float t, float r, float b, float vx, float vy, float vr) {
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
