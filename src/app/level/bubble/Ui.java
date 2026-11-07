package app.level.bubble;

import android.graphics.RectF;

/** Everything the renderer needs for one frame. Written by LevelView, read by Renderer. */
final class Ui {
    float dp = 1f, sp = 1f;
    int width, height, insetL, insetT, insetR, insetB;

    Palette pal = Palette.BUILT_IN[0];
    String themeName = "";
    float pageT;                 // 0 = level, 1 = settings

    // instrument
    float modeT;                 // 0 = surface (bullseye), 1 = edge (tube)
    float rotation;              // degrees, edge mode keeps the tube world-horizontal
    float bubX, bubY;            // normalized travel, |v| <= 1
    float levelT;                // 0..1 level highlight
    float pulseT = -1f;          // ring pulse on reaching level, -1 = idle
    float guide2, guide5;        // normalized radii of the 2 and 5 degree rings
    final float[] edgeTicks = new float[3];
    boolean guideLabels;

    // readout
    String caption = "", main = "", mainUnit = "", secondary = "";
    boolean secondaryAccent, held;

    // calibration
    boolean calActive, calShowReady;
    String calTitle = "";
    float calProgress;

    // settings page
    String unitsLabel = "", tolLabel = "", modeLabel = "";
    boolean calibrated;
    String calSummary = "";      // calibrated poses, e.g. "face up, 1 edge"
    float hapticsT, keepOnT;
    float settingsScroll;        // px, written by LevelView
    float settingsMaxScroll;     // px, written by Renderer

    // written by Renderer for LevelView: on-screen bubble travel of the instrument shown, px
    float travelPx = 1f;

    // interaction
    int pressedId;

    static final class Hit {
        int id;
        final RectF rect = new RectF();
        float vx, vy, vr;        // visual press feedback: circle (vr > 0), pill (vr == 0), none (vr < 0)
    }

    final Hit[] hits = new Hit[32];
    int hitCount;

    Ui() {
        for (int i = 0; i < hits.length; i++) hits[i] = new Hit();
    }

    void resetHits() {
        hitCount = 0;
    }

    void addHit(int id, float l, float t, float r, float b, float vx, float vy, float vr) {
        if (hitCount >= hits.length) return;
        Hit h = hits[hitCount++];
        h.id = id;
        h.rect.set(l, t, r, b);
        h.vx = vx;
        h.vy = vy;
        h.vr = vr;
    }

    int hitAt(float x, float y) {
        for (int i = hitCount - 1; i >= 0; i--) {
            if (hits[i].rect.contains(x, y)) return hits[i].id;
        }
        return 0;
    }

    Hit find(int id) {
        for (int i = 0; i < hitCount; i++) if (hits[i].id == id) return hits[i];
        return null;
    }
}
