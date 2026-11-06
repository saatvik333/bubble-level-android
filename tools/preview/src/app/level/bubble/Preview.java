package app.level.bubble;

import android.graphics.Canvas;
import android.graphics.Typeface;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Renders the production Renderer on the JVM (AWT-backed android.graphics stubs) for visual review. */
public final class Preview {
    static final float DENSITY = 2.625f;              // 1080 x 2400 phone, 412 x 914 dp
    static final int W = 1080, H = 2400;

    public static void main(String[] args) throws Exception {
        String fonts = args[0], out = args[1];
        new File(out).mkdirs();
        Renderer r = new Renderer(Typeface.fromFile(fonts + "/rubik_300.ttf"), Typeface.fromFile(fonts + "/rubik_400.ttf"));

        Ui u = base("Aurum");
        u.bubX = 0.30f; u.bubY = -0.18f;
        u.main = "2.7°"; u.secondary = "X  +2.2°     Y  +1.4°"; u.caption = "Surface";
        shot(r, u, out + "/1_aurum_surface.png");

        u = base("Aurum");
        u.levelT = 1f; u.pulseT = 0.35f; u.bubX = 0.01f; u.bubY = 0.0f;
        u.main = "0.1°"; u.secondary = "X  +0.1°     Y  0.0°"; u.caption = "Surface";
        shot(r, u, out + "/2_aurum_level.png");

        u = base("Celeste");
        u.modeT = 1f; u.bubX = -0.28f; u.main = "1.4°"; u.secondary = "Right side low"; u.caption = "Edge";
        shot(r, u, out + "/3_celeste_edge.png");

        u = base("Ambra");
        u.modeT = 1f; u.rotation = -90f; u.bubX = 0.12f; u.main = "0.5°"; u.secondary = "Left side low"; u.caption = "Edge";
        shot(r, u, out + "/4_ambra_edge_landscape.png");

        u = base("Rosa");
        u.pageT = 1f;
        u.unitsLabel = "Degrees"; u.tolLabel = "± 0.2°"; u.modeLabel = "Auto";
        u.hapticsT = 1f; u.keepOnT = 0f; u.calibrated = true;
        shot(r, u, out + "/5_rosa_settings.png");

        u = base("Salvia");
        u.calActive = true; u.calProgress = 0.62f; u.calTitle = "Calibrate"; u.caption = "Step 1 of 2";
        u.secondary = "Lay it face up on a firm surface"; u.bubX = 0.05f; u.bubY = 0.04f;
        shot(r, u, out + "/6_salvia_calibrate.png");

        u = base("Notte");
        u.held = true; u.caption = "Held"; u.bubX = -0.4f; u.bubY = 0.35f;
        u.main = "4.6"; u.mainUnit = "mm/m"; u.secondary = "X  −3.9     Y  −2.6"; u.guideLabels = false;
        shot(r, u, out + "/7_notte_held_mm.png");

        u = base("Celeste");
        u.calActive = true; u.calShowReady = true; u.calTitle = "Turn around"; u.caption = "Step 2 of 2";
        u.secondary = "Rotate it 180° on the same spot";
        shot(r, u, out + "/8_celeste_turn.png");
    }

    static Ui base(String theme) {
        Ui u = new Ui();
        u.dp = DENSITY;
        u.sp = DENSITY;
        u.width = W;
        u.height = H;
        u.insetT = Math.round(32 * DENSITY);
        u.insetB = Math.round(24 * DENSITY);
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
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        r.draw(new Canvas(g, W, H), u);
        g.dispose();
        ImageIO.write(img, "png", new File(path));
    }
}
