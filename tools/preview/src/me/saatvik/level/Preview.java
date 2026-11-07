package me.saatvik.level;

import android.graphics.Canvas;
import android.graphics.Typeface;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the production Renderer on the JVM (AWT-backed android.graphics stubs) for visual review:
 * a 412 x 914 dp phone, plus small windows (split screen, narrow phone at large font, landscape).
 */
public final class Preview {
    static final float DENSITY = 2.625f;

    public static void main(String[] args) throws Exception {
        String fonts = args[0], out = args[1];
        new File(out).mkdirs();
        Renderer r = new Renderer(Typeface.fromFile(fonts + "/rubik_300.ttf"), Typeface.fromFile(fonts + "/rubik_400.ttf"));

        Ui u = phone("Aurum");
        u.bubX = 0.30f; u.bubY = -0.18f;
        u.main = "2.7°"; u.secondary = "X  +2.2°     Y  +1.4°"; u.caption = "Surface";
        shot(r, u, out + "/1_aurum_surface.png");

        u = phone("Aurum");
        u.levelT = 1f; u.pulseT = 0.35f; u.bubX = 0.01f; u.bubY = 0.0f;
        u.main = "0.1°"; u.secondary = "X  +0.1°     Y  0.0°"; u.caption = "Surface";
        shot(r, u, out + "/2_aurum_level.png");

        u = phone("Celeste");
        u.modeT = 1f; u.bubX = -0.28f; u.main = "1.4°"; u.secondary = "Right side low"; u.caption = "Edge";
        shot(r, u, out + "/3_celeste_edge.png");

        u = phone("Ambra");
        u.modeT = 1f; u.rotation = -90f; u.bubX = 0.12f; u.main = "0.5°"; u.secondary = "Left side low"; u.caption = "Edge";
        shot(r, u, out + "/4_ambra_edge_landscape.png");

        u = phone("Rosa");
        settings(u);
        shot(r, u, out + "/5_rosa_settings.png");

        u = phone("Salvia");
        cal1(u);
        shot(r, u, out + "/6_salvia_calibrate.png");

        u = phone("Notte");
        u.held = true; u.caption = "Held"; u.bubX = -0.4f; u.bubY = 0.35f;
        u.main = "4.6"; u.mainUnit = "mm/m"; u.secondary = "X  −3.9     Y  −2.6"; u.guideLabels = false;
        shot(r, u, out + "/7_notte_held_mm.png");

        u = phone("Celeste");
        turnStep(u);
        shot(r, u, out + "/8_celeste_turn.png");

        // ---- small windows ----
        // half-screen split on a 360 dp phone: 360 x 370 dp
        u = sized("Aurum", 360, 370, 2.625f, 1f, 24, 0);
        u.bubX = 0.2f; u.bubY = -0.1f; u.main = "1.8°"; u.secondary = "X  +1.6°     Y  +0.9°"; u.caption = "Surface";
        shot(r, u, out + "/s1_split_surface.png");
        u = sized("Salvia", 360, 370, 2.625f, 1f, 24, 0);
        turnStep(u);
        shot(r, u, out + "/s2_split_turn.png");
        u = sized("Celeste", 360, 370, 2.625f, 1f, 24, 0);
        u.modeT = 1f; u.bubX = -0.2f; u.main = "0.9°"; u.secondary = "Right side low"; u.caption = "Edge";
        shot(r, u, out + "/s3_split_edge.png");
        u = sized("Ambra", 360, 370, 2.625f, 1f, 24, 0);
        u.modeT = 1f; u.rotation = -90f; u.main = "0.9°"; u.secondary = "Left side low"; u.caption = "Edge";
        shot(r, u, out + "/s4_split_edge_rot.png");
        u = sized("Rosa", 360, 370, 2.625f, 1f, 24, 0);
        settings(u);
        shot(r, u, out + "/s5_split_settings.png");
        u = sized("Rosa", 360, 370, 2.625f, 1f, 24, 0);
        settings(u);
        u.settingsScroll = 1e9f;                           // clamped by the renderer to the bottom
        shot(r, u, out + "/s6_split_settings_scrolled.png");

        // 320 dp phone at 1.3x font
        u = sized("Salvia", 320, 569, 2f, 1.3f, 24, 48);
        cal1(u);
        shot(r, u, out + "/n1_narrow_cal.png");
        u = sized("Celeste", 320, 569, 2f, 1.3f, 24, 48);
        turnStep(u);
        u.secondary = "Turn it to face the other way, same edge down";
        shot(r, u, out + "/n2_narrow_turn_edge.png");
        u = sized("Rosa", 320, 569, 2f, 1.3f, 24, 48);
        settings(u);
        shot(r, u, out + "/n3_narrow_settings.png");

        u = sized("Celeste", 360, 370, 2.625f, 1f, 24, 0);
        u.modeT = 1f; turnStep(u); u.secondary = "Turn it to face the other way, same edge down";
        shot(r, u, out + "/s7_split_edge_turn.png");
        u = sized("Ambra", 914, 412, 2.625f, 1f, 24, 24);
        u.modeT = 1f; u.main = "0.4°"; u.secondary = "Left side low"; u.caption = "Edge";
        shot(r, u, out + "/l2_landscape_edge.png");
        u = sized("Rosa", 412, 914, 2.625f, 1f, 32, 24);
        settings(u); u.calSummary = "face up, face down, 4 edges";
        shot(r, u, out + "/9_settings_summary.png");

        // landscape window (large screen ignoring the portrait request)
        u = sized("Aurum", 914, 412, 2.625f, 1f, 24, 24);
        u.bubX = -0.3f; u.bubY = 0.2f; u.main = "3.1°"; u.secondary = "X  −2.6°     Y  −1.7°"; u.caption = "Surface";
        shot(r, u, out + "/l1_landscape_surface.png");
    }

    static void settings(Ui u) {
        u.pageT = 1f;
        u.unitsLabel = "Degrees"; u.tolLabel = "± 0.2°"; u.modeLabel = "Auto";
        u.hapticsT = 1f; u.keepOnT = 0f; u.calibrated = true;
    }

    static void cal1(Ui u) {
        u.calActive = true; u.calProgress = 0.62f; u.calTitle = "Calibrate"; u.caption = "Step 1 of 2";
        u.secondary = "Lay it flat on a firm surface"; u.bubX = 0.05f; u.bubY = 0.04f;
    }

    static void turnStep(Ui u) {
        u.calActive = true; u.calShowReady = true; u.calTitle = "Turn around"; u.caption = "Step 2 of 2";
        u.secondary = "Rotate it 180° on the same spot";
    }

    static Ui phone(String theme) {
        return sized(theme, 412, 914, DENSITY, 1f, 32, 24);
    }

    static Ui sized(String theme, int wDp, int hDp, float density, float font, int topDp, int bottomDp) {
        Ui u = new Ui();
        u.dp = density;
        u.sp = density * font;
        u.width = Math.round(wDp * density);
        u.height = Math.round(hDp * density);
        u.insetT = Math.round(topDp * density);
        u.insetB = Math.round(bottomDp * density);
        for (Palette p : Palette.BUILT_IN) if (p.name.equals(theme)) u.pal = p;
        u.themeName = theme;
        u.guide2 = (float) Math.tanh(2f / 8f);
        u.guide5 = (float) Math.tanh(5f / 8f);
        u.edgeTicks[0] = (float) Math.tanh(1f / 6f);
        u.edgeTicks[1] = (float) Math.tanh(2f / 6f);
        u.edgeTicks[2] = (float) Math.tanh(5f / 6f);
        u.guideLabels = true;
        return u;
    }

    static void shot(Renderer r, Ui u, String path) throws Exception {
        BufferedImage img = new BufferedImage(u.width, u.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.draw(new Canvas(g, u.width, u.height), u);
        if (u.settingsScroll > u.settingsMaxScroll) {          // what LevelView does after a frame
            u.settingsScroll = u.settingsMaxScroll;
            r.draw(new Canvas(g, u.width, u.height), u);
        }
        // -Dhits=true outlines the hit rectangles, for checking overlap and reach
        if (Boolean.getBoolean("hits")) g.setColor(new java.awt.Color(255, 0, 0, 90));
        for (int i = 0; Boolean.getBoolean("hits") && i < u.hitCount; i++) {
            android.graphics.RectF h = u.hits[i].rect;
            g.drawRect(Math.round(h.left), Math.round(h.top), Math.round(h.right - h.left), Math.round(h.bottom - h.top));
        }
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }
}
