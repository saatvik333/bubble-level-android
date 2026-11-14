package me.saatvik.level;

import me.saatvik.hairline.Frame;

/**
 * Everything the renderer needs for one frame: the shared {@link Frame} (metrics, insets, theme,
 * targets, settings list) and the level's own state. Written by LevelView, read by Renderer.
 */
final class Ui extends Frame {
    Ui() {
        super(32);
    }

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

    // settings page: option labels, the selected index, and its animated position for the slider
    String[] unitOpts = {}, tolOpts = {}, modeOpts = {};
    int units, tolIdx, mode;
    float unitsPos, tolPos, modePos;
    boolean haptics, keepOn;
    float hapticsT, keepOnT;
    boolean calibrated, resetConfirm;
    String calSummary = "";      // calibrated poses, e.g. "Face up, 1 edge", or "Not calibrated"
    String calNote;              // storage failure to show on the calibration row, null when none

    // written by Renderer for LevelView: on-screen bubble travel of the instrument shown, px
    float travelPx = 1f;
}
