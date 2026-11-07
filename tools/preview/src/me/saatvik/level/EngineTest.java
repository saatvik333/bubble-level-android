package me.saatvik.level;

import java.util.Random;

/**
 * JVM checks of the sensor math: angles, signs, mode hysteresis, edge quadrants, two-step
 * calibration, and regressions for the review findings (calibration flow, per-pose offsets,
 * edge lean, flat quarter, sample-rate independence).
 */
public final class EngineTest {
    static final float G = 9.80665f;
    static int fails;
    static long t = 1_000_000_000L;
    static Random rnd = new Random(Long.getLong("seed", 7L));
    static long stepNs = 20_000_000L;                      // 50 Hz unless a test changes it
    static boolean gyro;                                   // stream a resting gyro alongside gravity

    static LevelEngine eng() {
        gyro = false;
        return new LevelEngine();
    }

    public static void main(String[] a) {
        // 1. flat, right edge raised 3 deg: up vector tilts toward +x
        LevelEngine e = eng();
        feed(e, up(3f, 0f), 2f, 0f);
        check("flat tilt 3", e.tilt, 3f, 0.05f);
        check("flat ax +3 (right high)", e.ax, 3f, 0.05f);
        check("flat ay 0", e.ay, 0f, 0.05f);
        check("mode flat", e.mode, LevelEngine.MODE_FLAT, 0);

        // 2. compound tilt
        e = eng();
        feed(e, up(1.5f, -2f), 2f, 0f);
        check("compound ax", e.ax, 1.5f, 0.06f);
        check("compound ay", e.ay, -2f, 0.06f);

        // 3. upright portrait, rotated 1.2 deg clockwise -> left side high -> dev < 0
        e = eng();
        feed(e, inPlane(-1.2f), 2f, 0f);
        check("edge mode", e.mode, LevelEngine.MODE_EDGE, 0);
        check("edge quarter 0", e.quarter, 0, 0);
        check("edge dev -1.2", e.dev, -1.2f, 0.05f);

        // 4. landscape (device rotated 90 cw + 0.8)
        e = eng();
        feed(e, inPlane(-90.8f), 2f, 0f);
        check("landscape quarter -90", e.quarter, -90, 0);
        check("landscape dev -0.8", e.dev, -0.8f, 0.05f);

        // 5. hysteresis: 45 deg inclination keeps the current mode
        e = eng();
        feed(e, up(45f, 0f), 1f, 0f);
        check("45 deg stays flat", e.mode, LevelEngine.MODE_FLAT, 0);
        feed(e, up(60f, 0f), 1f, 0f);
        check("60 deg -> edge", e.mode, LevelEngine.MODE_EDGE, 0);
        feed(e, up(45f, 0f), 1f, 0f);
        check("45 deg stays edge", e.mode, LevelEngine.MODE_EDGE, 0);
        feed(e, up(30f, 0f), 1f, 0f);
        check("30 deg -> flat", e.mode, LevelEngine.MODE_FLAT, 0);

        // 6. two-step calibration on a sloped surface with a sensor offset (noisy accelerometer)
        float slopeX = 1.1f, slopeY = -0.7f;
        float[] bias = {0.07f, -0.05f, 0.02f};            // m/s^2, ~0.4 deg and ~0.3 deg
        e = eng();
        feed(e, plus(up(slopeX, slopeY), bias), 1.5f, 0.01f);
        float before = e.ax;
        e.startCal();
        feed(e, plus(up(slopeX, slopeY), bias), 2.5f, 0.01f);
        check("cal step1 captured", e.cal, LevelEngine.CAL_TURN, 0);
        turn(e, (float) Math.PI, 2, plus(up(slopeX, slopeY), bias));
        check("gyro detected turn", e.cal, LevelEngine.CAL_STEP2, 0);
        feed(e, plus(up(-slopeX, -slopeY), bias), 2.5f, 0.01f);   // surface slope flips in device frame
        check("cal done", e.cal, LevelEngine.CAL_DONE, 0);
        check("bias x recovered", e.surf[0], bias[0], 0.005f);
        check("bias y recovered", e.surf[1], bias[1], 0.005f);
        e.cancelCal();
        feed(e, plus(up(slopeX, slopeY), bias), 3f, 0.01f);
        System.out.printf("   before cal ax=%.3f, after cal ax=%.3f (true %.3f)%n", before, e.ax, slopeX);
        check("calibrated ax = true slope", e.ax, slopeX, 0.05f);
        check("calibrated ay = true slope", e.ay, slopeY, 0.05f);

        // 7. edge calibration: standing portrait, turned around about the vertical, no gyro
        e = eng();
        float[] b2 = {0.06f, 0.0f, -0.04f};
        feed(e, plus(inPlane(0.9f), b2), 1.5f, 0.01f);
        e.startCal();
        feed(e, plus(inPlane(0.9f), b2), 2.5f, 0.01f);
        e.manualReady();
        feed(e, plus(inPlane(-0.9f), b2), 2.5f, 0.01f);
        check("edge cal done", e.cal, LevelEngine.CAL_DONE, 0);
        check("edge bias x", e.edge[LevelEngine.EDGE_BOTTOM], b2[0], 0.004f);
        check("edge cal leaves surface offset", e.surf[0], 0f, 0f);
        e.cancelCal();
        feed(e, plus(inPlane(0.9f), b2), 2f, 0.01f);
        check("edge calibrated dev", e.dev, 0.9f, 0.06f);

        // 8. a moved setup is rejected (15 deg apart implies a 7.5 deg offset)
        e = eng();
        feed(e, up(0f, 0f), 1f, 0f);
        e.startCal();
        feed(e, up(0f, 0f), 2.5f, 0f);
        e.manualReady();
        feed(e, up(15f, 0f), 2.5f, 0f);
        check("large offset rejected", e.cal, LevelEngine.CAL_FAILED, 0);
        check("fail reason offset", e.calFail, LevelEngine.FAIL_OFFSET, 0);

        // 9. steadiness: noisy accelerometer at rest, reading jitter after settling (50 and 200 Hz)
        for (long step : new long[]{20_000_000L, 5_000_000L}) {
            stepNs = step;
            e = eng();
            float lo = 99, hi = -99;
            int n = (int) (8_000_000_000L / step);
            for (int i = 0; i < n; i++) {
                float[] g = jitter(up(0.5f, 0f), 0.02f);
                t += step;
                e.onGravity(g[0], g[1], g[2], t);
                if (i > n / 3) { lo = Math.min(lo, e.ax); hi = Math.max(hi, e.ax); }
            }
            System.out.printf("   jitter at rest, 0.02 m/s^2 noise, %d Hz: %.3f deg p-p%n", 1_000_000_000L / step, hi - lo);
            check("rest jitter < 0.12 deg @" + 1_000_000_000L / step + "Hz", hi - lo, 0f, 0.12f);
        }
        stepNs = 20_000_000L;

        // 10. display rotated 90 (landscape-natural tablet held portrait, or system-rotated window):
        //     device +x edge raised = screen top raised
        e = eng();
        e.rotation = 1;
        feed(e, up(2f, 0f), 2f, 0f);
        check("rot90 screen ax", e.ax, 0f, 0.05f);
        check("rot90 screen ay", e.ay, 2f, 0.05f);
        e.rotation = 3;
        e.refresh();
        check("rot270 screen ay", e.ay, -2f, 0.05f);
        e.rotation = 2;
        e.refresh();
        check("rot180 screen ax", e.ax, -2f, 0.05f);

        // ---- review regressions ----------------------------------------------------------------

        // R-1a. started at 47 deg in Auto (still surface mode), then laid flat: must capture
        e = eng();
        feed(e, up(47f, 0f), 1f, 0f);
        e.startCal();
        feed(e, up(47f, 0f), 0.3f, 0f);
        feed(e, up(0.5f, 0.2f), 3f, 0.01f);
        check("47deg start, flat capture", e.cal, LevelEngine.CAL_TURN, 0);
        check("47deg start -> surface cal", e.calEdge ? 1 : 0, 0, 0);

        // R-1b. Edge locked, lying flat: captures as a surface calibration
        e = eng();
        e.lock = LevelEngine.LOCK_EDGE;
        feed(e, up(0.3f, 0.1f), 1f, 0f);
        e.startCal();
        feed(e, up(0.3f, 0.1f), 2.5f, 0.01f);
        check("edge lock flat capture", e.cal, LevelEngine.CAL_TURN, 0);

        // R-1c. edge cal started upright, then stood on a long side: captures
        e = eng();
        feed(e, inPlane(0.5f), 1f, 0f);
        e.startCal();
        feed(e, inPlane(-89.6f), 2.5f, 0.01f);
        check("long-side capture", e.cal, LevelEngine.CAL_TURN, 0);

        // R-1d. step 2 on the wrong face waits with a hint, then completes on the right one
        e = eng();
        feed(e, inPlane(0.4f), 1f, 0f);
        e.startCal();
        feed(e, inPlane(0.4f), 2.5f, 0f);
        e.manualReady();
        feed(e, inPlane(179.6f), 3f, 0f);                   // upside down: top edge down
        check("wrong edge: no capture", e.cal, LevelEngine.CAL_STEP2, 0);
        check("wrong edge: hint", e.calHint, LevelEngine.HINT_WRONG_SIDE, 0);
        feed(e, inPlane(-0.4f), 2.5f, 0f);
        check("right edge: done", e.cal, LevelEngine.CAL_DONE, 0);

        // R-2a. Ready without turning, no gyro: never completes on a sloped surface
        e = eng();
        float[] b3 = {0.05f, -0.03f, 0f};
        feed(e, plus(up(1.5f, 0.8f), b3), 1f, 0.01f);
        e.startCal();
        feed(e, plus(up(1.5f, 0.8f), b3), 2.5f, 0.01f);
        e.manualReady();
        feed(e, plus(up(1.5f, 0.8f), b3), 10f, 0.01f);
        check("unmoved Ready: no result", e.cal, LevelEngine.CAL_STEP2, 0);
        check("unmoved Ready: hint", e.calHint, LevelEngine.HINT_TURN_FIRST, 0);
        check("unmoved Ready: offset untouched", e.surf[0], 0f, 0f);

        // R-2b. Ready without turning, gyro present: refused while still in the turn step
        e = eng();
        feed(e, up(1.5f, 0.8f), 1f, 0f);
        e.startCal();
        gyroIdle(e, 0.1f);
        feed(e, up(1.5f, 0.8f), 2.5f, 0f);
        gyroIdle(e, 0.1f);
        e.manualReady();
        check("gyro, unturned Ready refused", e.cal, LevelEngine.CAL_TURN, 0);
        check("gyro, unturned Ready hint", e.calHint, LevelEngine.HINT_TURN_FIRST, 0);

        // R-3a. 1 deg camera bump: surface cal must not leak into edge mode
        e = eng();
        feed(e, up(1f, 0f), 1f, 0f);
        e.startCal();
        feed(e, up(1f, 0f), 2.5f, 0f);
        turn(e, (float) Math.PI, 2, up(1f, 0f));
        feed(e, up(1f, 0f), 2.5f, 0.005f);                  // bump is fixed to the phone: same reading
        check("bump cal done", e.cal, LevelEngine.CAL_DONE, 0);
        e.cancelCal();
        feed(e, up(1f, 0f), 1f, 0f);
        check("bump cal: flat reads 0", e.tilt, 0f, 0.05f);
        feed(e, inPlane(-90f), 2f, 0f);                     // level shelf, landscape
        check("bump cal: edge reads 0", e.dev, 0f, 0.03f);

        // R-3b. 0.8 deg mount misalignment: edge cal must not leak into surface mode
        e = eng();
        feed(e, inPlane(0.8f), 1f, 0f);
        e.startCal();
        feed(e, inPlane(0.8f), 2.5f, 0f);
        e.manualReady();
        lift(e, inPlane(0.8f));                             // picked up and turned around     // handling between the two captures
        feed(e, inPlane(0.8f), 2.5f, 0f);
        check("misalign edge cal done", e.cal, LevelEngine.CAL_DONE, 0);
        e.cancelCal();
        feed(e, inPlane(0.8f), 1f, 0f);
        check("misalign cal: edge reads 0", e.dev, 0f, 0.03f);
        feed(e, up(0f, 0f), 2f, 0f);                        // level table
        check("misalign cal: flat reads 0", e.tilt, 0f, 0.03f);

        // R-4. edge deviation is exact at any lean of the screen (true slope 1 deg)
        float[][] leans = {{30f, LevelEngine.LOCK_AUTO}, {40f, LevelEngine.LOCK_EDGE}, {50f, LevelEngine.LOCK_EDGE}, {70f, LevelEngine.LOCK_EDGE}};
        for (float[] l : leans) {
            e = eng();
            e.lock = (int) l[1];
            feed(e, leaned(1f, l[0]), 2f, 0f);
            check("edge 1deg @ lean " + (int) l[0], e.dev, 1f, 0.01f);
        }
        e = eng();
        feed(e, leanedLandscape(-0.7f, 35f), 2f, 0f);
        check("landscape -0.7 @ lean 35, quarter", e.quarter, -90, 0);
        check("landscape -0.7 @ lean 35", e.dev, -0.7f, 0.01f);

        // R-5. Edge locked, lying flat: the quarter does not spin on noise
        e = eng();
        e.lock = LevelEngine.LOCK_EDGE;
        int changes = 0, q0 = Integer.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            float[] g = jitter(up(0.02f, -0.01f), 0.05f);
            t += stepNs;
            e.onGravity(g[0], g[1], g[2], t);
            if (q0 != Integer.MIN_VALUE && e.quarter != q0) changes++;
            q0 = e.quarter;
        }
        check("edge lock flat: quarter changes", changes, 0, 0);

        // R-6. stillness is reached at realistic noise levels and rates
        float[][] cases = {{50, 0.05f}, {200, 0.05f}, {100, 0.03f}, {400, 0.02f}};
        for (float[] cs : cases) {
            stepNs = (long) (1e9 / cs[0]);
            e = eng();
            feed(e, up(0.4f, 0.2f), 0.5f, cs[1]);
            e.startCal();
            float secs = 0f;
            while (e.cal == LevelEngine.CAL_STEP1 && secs < 30f) {
                feed(e, up(0.4f, 0.2f), 0.05f, cs[1]);
                secs += 0.05f;
            }
            System.out.printf("   step 1 capture at %d Hz, %.2f m/s^2 noise: %.2f s%n", (int) cs[0], cs[1], secs);
            check("capture < 4 s @" + (int) cs[0] + "Hz/" + cs[1], secs, 2f, 2f);
        }
        stepNs = 20_000_000L;

        // R-7. hand-held is not still
        e = eng();
        feed(e, up(5f, 3f), 0.1f, 0f);
        e.startCal();
        for (int i = 0; i < 300; i++) {
            double ph = i * 0.02 * 2 * Math.PI;
            float wob = (float) (0.6 * Math.sin(ph * 1.3) + 0.4 * Math.sin(ph * 4.1));
            feed(e, up(5f + wob, 3f - wob * 0.5f), 0.02f, 0.15f);
        }
        check("hand-held: no capture", e.cal, LevelEngine.CAL_STEP1, 0);

        // R-8. physical simulation: imperfect 155 deg turn on a 3 deg slope, face up and face down
        simTurn("up", 155f, 3f, -1.2f, new float[]{0.08f, -0.06f, 0.03f}, true, 0.012f);
        simTurn("up", -170f, 2f, 1.5f, new float[]{-0.05f, 0.04f, 0f}, true, 0.012f);
        simTurn("down", 160f, 2.5f, 1f, new float[]{0.06f, 0.05f, -0.02f}, true, 0.012f);
        // edges: only a half turn solves; a 160 deg turn on a slope is refused, not mis-solved
        simTurn("bottom", 176f, 2f, 0.7f, new float[]{0.05f, 0f, 0f}, true, 0.02f);
        simTurn("top", -183f, 2f, 2.1f, new float[]{-0.04f, 0f, 0f}, true, 0.02f);
        simTurn("left", 178f, 2f, -0.4f, new float[]{0f, 0.06f, 0f}, true, 0.02f);
        simTurn("right", 181f, 1.5f, 2.9f, new float[]{0f, -0.03f, 0f}, true, 0.02f);
        simTurn("bottom", 160f, 2f, 0.7f, new float[]{0.05f, 0f, 0f}, false, 0f);
        simTurn("left", 125f, 2f, 0.7f, new float[]{0f, 0.05f, 0f}, false, 0f);

        // R-9. a full 360 is not a reversal
        e = eng();
        feed(e, up(1f, 0f), 1f, 0f);
        e.startCal();
        feed(e, up(1f, 0f), 2.5f, 0f);
        turn(e, (float) (2 * Math.PI), 3, up(1f, 0f));
        feed(e, up(1f, 0f), 2.5f, 0f);
        check("360 turn rejected", e.cal, LevelEngine.CAL_FAILED, 0);
        check("360 turn reason", e.calFail, LevelEngine.FAIL_TURN, 0);

        // R-10. a real 66 mg offset with a perfect reversal is accepted
        e = eng();
        float[] big = {0.65f, -0.2f, 0f};
        feed(e, plus(up(0.6f, 0.3f), big), 1f, 0f);
        e.startCal();
        feed(e, plus(up(0.6f, 0.3f), big), 2.5f, 0f);
        e.manualReady();
        feed(e, plus(up(-0.6f, -0.3f), big), 2.5f, 0f);
        check("66 mg offset accepted", e.cal, LevelEngine.CAL_DONE, 0);
        check("66 mg offset x", e.surf[0], big[0], 0.003f);

        // R-11. edge cal with a different lean in the two positions: lean is ignored
        e = eng();
        feed(e, leaned(0.6f, 10f), 1f, 0f);
        e.startCal();
        feed(e, leaned(0.6f, 10f), 2.5f, 0f);
        e.manualReady();
        feed(e, leaned(-0.6f, 18f), 2.5f, 0f);
        check("edge cal, lean 10 vs 18 accepted", e.cal, LevelEngine.CAL_DONE, 0);
        check("edge cal, lean 10 vs 18 offset", e.edge[LevelEngine.EDGE_BOTTOM], 0f, 0.003f);

        // R-12. gyro bias alone does not end the turn step
        e = eng();
        feed(e, up(0f, 0f), 1f, 0f);
        e.startCal();
        feed(e, up(0f, 0f), 2.5f, 0f);
        for (int i = 0; i < 100 * 300; i++) {                // 5 minutes of 0.015 rad/s bias
            t += 10_000_000L;
            e.onGyro(0.001f, 0.002f, 0.015f, t);
            if (i % 2 == 0) e.onGravity(0f, 0f, G, t);
        }
        check("gyro bias: still waiting", e.cal, LevelEngine.CAL_TURN, 0);

        // ---- re-review regressions ---------------------------------------------------------------

        // RR-2. per-pose slots. A plain bias carries to the opposite edge until that edge is
        //       calibrated itself; then an in-plane misalignment (sign flips) is exact on both.
        e = eng();
        float[] by = {0f, 0.1f, 0f};                        // 0.1 m/s^2 bias on y, level shelf
        calEdgeNoGyro(e, plus(inPlane(-90f), by));          // left long side down (up = -x? see slots)
        check("bias: calibrated side reads 0", e.dev, 0f, 0.02f);
        feed(e, plus(inPlane(90f), by), 2f, 0f);            // the other long side
        check("bias: opposite side borrows the offset", e.dev, 0f, 0.02f);
        e = eng();
        float mis = 0.5f;                                   // sensor turned 0.5 deg in the screen plane
        calEdgeNoGyro(e, inPlane(-90f + mis));
        check("misalign: first side reads 0", e.dev, 0f, 0.02f);
        calEdgeNoGyro(e, inPlane(90f + mis));
        check("misalign: second side reads 0", e.dev, 0f, 0.02f);
        feed(e, inPlane(-90f + mis), 2f, 0f);
        check("misalign: first side still 0", e.dev, 0f, 0.02f);
        check("two edge slots set", Integer.bitCount(e.calMask >> 2), 2, 0);

        // RR-3. slow turn on a near-level surface with a gyro: completes, no stall in step 2
        e = eng();
        gyro = true;
        float[] lvl = up(0.2f, 0.1f);
        feed(e, lvl, 1f, 0.003f);
        e.startCal();
        feed(e, lvl, 2.5f, 0.003f);
        for (int i = 0; i < 1200; i++) {                    // 180 deg over 12 s, 0.26 rad/s, at 100 Hz
            t += 10_000_000L;
            double a2 = Math.PI * i / 1200.0;
            float rr = (float) (Math.PI / 12.0);
            e.onGyro(0f, 0f, rr, t);
            if (i % 2 == 0) {
                float[] g = up((float) (0.2 * Math.cos(a2) + 0.1 * Math.sin(a2)), (float) (-0.2 * Math.sin(a2) + 0.1 * Math.cos(a2)));
                float[] j = jitter(g, 0.003f);
                e.onGravity(j[0], j[1], j[2], t);
            }
        }
        feed(e, up(-0.2f, -0.1f), 3f, 0.003f);
        check("slow turn completes", e.cal, LevelEngine.CAL_DONE, 0);

        // RR-risk. a single jolt when tapping Ready (no gyro) is not a turn
        e = eng();
        feed(e, up(1.5f, 0f), 1f, 0.01f);
        e.startCal();
        feed(e, up(1.5f, 0f), 2.5f, 0.01f);
        e.manualReady();
        t += stepNs;
        e.onGravity(up(1.5f, 0f)[0] + 0.6f, up(1.5f, 0f)[1], up(1.5f, 0f)[2], t);
        feed(e, up(1.5f, 0f), 6f, 0.01f);
        check("jolt is not a turn", e.cal, LevelEngine.CAL_STEP2, 0);

        // RR-risk. a calibration without gyro events after one with: Ready works, turn assumed 180
        e = eng();
        feed(e, up(0.8f, 0.4f), 1f, 0f);
        e.startCal();
        gyroIdle(e, 0.2f);
        e.cancelCal();
        gyro = false;
        e.startCal();
        feed(e, up(0.8f, 0.4f), 2.5f, 0f);
        e.manualReady();
        check("stale gyro: Ready accepted", e.cal, LevelEngine.CAL_STEP2, 0);
        feed(e, up(-0.8f, -0.4f), 2.5f, 0f);
        check("stale gyro: done", e.cal, LevelEngine.CAL_DONE, 0);

        // RR-risk. a 3-sample (60 ms) sideways jolt when tapping Ready is not a turn (no gyro)
        e = eng();
        feed(e, up(1.5f, 0f), 1f, 0.01f);
        e.startCal();
        feed(e, up(1.5f, 0f), 2.5f, 0.01f);
        e.manualReady();
        for (int i = 0; i < 3; i++) {
            t += stepNs;
            e.onGravity(up(1.5f, 0f)[0] + 0.7f, up(1.5f, 0f)[1], up(1.5f, 0f)[2], t);
        }
        feed(e, up(1.5f, 0f), 6f, 0.01f);
        check("3-sample jolt is not a turn", e.cal, LevelEngine.CAL_STEP2, 0);

        // RR-risk. gyro bias measured in step 1: a slow final nudge still counts toward the turn
        e = eng();
        float[] gb = {0.004f, -0.03f, 0.05f};               // rad/s, a poor gyro
        float[] up0 = inPlane(0.3f);
        for (int i = 0; i < 50; i++) { t += stepNs; e.onGyro(gb[0], gb[1], gb[2], t); e.onGravity(up0[0], up0[1], up0[2], t); }
        e.startCal();
        int i1 = 0;
        while (e.cal == LevelEngine.CAL_STEP1 && i1++ < 500) {
            t += stepNs;
            e.onGyro(gb[0] + (float) rnd.nextGaussian() * 0.002f, gb[1], gb[2], t);
            e.onGravity(up0[0], up0[1], up0[2], t);
        }
        // 168 deg quickly, then the last 12 deg at 2.5 deg/s, about the vertical (device y)
        float done = 0f;
        float[] g2 = inPlane(0.3f);
        while (done < Math.PI - 0.0001) {
            float rate = done < Math.toRadians(168) ? 2f : (float) Math.toRadians(2.5);
            float step = Math.min(rate * 0.01f, (float) Math.PI - done);
            done += step;
            t += 10_000_000L;
            e.onGyro(gb[0], gb[1] + step / 0.01f, gb[2], t);
            if ((t / 10_000_000L) % 2 == 0) {
                float[] gr = done < Math.PI / 2 ? jitter(g2, 0.3f) : jitter(inPlane(-0.3f), 0.05f);
                e.onGravity(gr[0], gr[1], gr[2], t);
            }
        }
        if (e.cal == LevelEngine.CAL_TURN) e.manualReady();
        float[] dn = inPlane(-0.3f);
        for (int i = 0; i < 200; i++) { t += stepNs; e.onGyro(gb[0], gb[1], gb[2], t); e.onGravity(dn[0], dn[1], dn[2], t); }
        check("slow final nudge, bias known: done", e.cal, LevelEngine.CAL_DONE, 0);

        // RR-risk. 0.05 rad/s gyro bias, measured in step 1, for 5 minutes: not a turn
        e = eng();
        feed(e, up(0f, 0f), 1f, 0f);
        e.startCal();
        int i2 = 0;
        while (e.cal == LevelEngine.CAL_STEP1 && i2++ < 500) { t += stepNs; e.onGyro(0.004f, -0.003f, 0.05f, t); e.onGravity(0f, 0f, G, t); }
        for (int i = 0; i < 100 * 300; i++) {
            t += 10_000_000L;
            e.onGyro(0.004f, -0.003f, 0.05f + (float) rnd.nextGaussian() * 0.002f, t);
            if (i % 2 == 0) e.onGravity(0f, 0f, G, t);
        }
        check("known 0.05 rad/s bias: still waiting", e.cal, LevelEngine.CAL_TURN, 0);

        // RR-risk. 0.05 rad/s gyro bias for 5 minutes does not count as a turn
        e = eng();
        feed(e, up(0f, 0f), 1f, 0f);
        e.startCal();
        feed(e, up(0f, 0f), 2.5f, 0f);
        for (int i = 0; i < 100 * 300; i++) {
            t += 10_000_000L;
            e.onGyro(0.004f, -0.003f, 0.05f, t);
            if (i % 2 == 0) e.onGravity(0f, 0f, G, t);
        }
        check("0.05 rad/s bias: still waiting", e.cal, LevelEngine.CAL_TURN, 0);

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }

    /** Up vector for a device tilted so that the right edge rises by ax deg and the top edge by ay deg. */
    static float[] up(float axDeg, float ayDeg) {
        double tx = Math.tan(Math.toRadians(axDeg)), ty = Math.tan(Math.toRadians(ayDeg));
        double n = Math.sqrt(tx * tx + ty * ty + 1);
        return new float[]{(float) (G * tx / n), (float) (G * ty / n), (float) (G / n)};
    }

    /** Up vector for a device standing in a vertical plane, rotated by rollDeg (atan2(gx, gy)). */
    static float[] inPlane(float rollDeg) {
        double r = Math.toRadians(rollDeg);
        return new float[]{(float) (G * Math.sin(r)), (float) (G * Math.cos(r)), 0f};
    }

    /** Standing upright on an edge sloped by slopeDeg along device x, screen leaned back by leanDeg. */
    static float[] leaned(float slopeDeg, float leanDeg) {
        double s = Math.toRadians(slopeDeg), l = Math.toRadians(leanDeg);
        return new float[]{(float) (G * Math.sin(s)), (float) (G * Math.cos(s) * Math.cos(l)), (float) (G * Math.cos(s) * Math.sin(l))};
    }

    /** Standing on the left long side (device +x up), edge slope along -y, leaned back. */
    static float[] leanedLandscape(float devDeg, float leanDeg) {
        double s = Math.toRadians(devDeg), l = Math.toRadians(leanDeg);
        // quarter -90: tube axis in display coords is (0, 1); dev = asin(gy / n)
        return new float[]{(float) (-G * Math.cos(s) * Math.cos(l)), (float) (G * Math.sin(s)), (float) (G * Math.cos(s) * Math.sin(l))};
    }

    static float[] plus(float[] v, float[] b) {
        return new float[]{v[0] + b[0], v[1] + b[1], v[2] + b[2]};
    }

    static float[] jitter(float[] v, float s) {
        return new float[]{v[0] + (float) rnd.nextGaussian() * s, v[1] + (float) rnd.nextGaussian() * s, v[2] + (float) rnd.nextGaussian() * s};
    }

    static void feed(LevelEngine e, float[] g, float seconds, float noise) {
        int n = Math.max(1, Math.round(seconds * 1e9f / stepNs));
        for (int i = 0; i < n; i++) {
            float[] s = noise > 0 ? jitter(g, noise) : g;
            t += stepNs;
            if (gyro) e.onGyro((float) rnd.nextGaussian() * 0.003f, (float) rnd.nextGaussian() * 0.003f, (float) rnd.nextGaussian() * 0.003f, t);
            e.onGravity(s[0], s[1], s[2], t);
        }
    }

    /** Edge calibration without a gyroscope: capture, pick up and turn, capture, back to idle. */
    static void calEdgeNoGyro(LevelEngine e, float[] g) {
        feed(e, g, 1f, 0f);
        e.startCal();
        feed(e, g, 2.5f, 0f);
        e.manualReady();
        lift(e, g);
        feed(e, g, 3.5f, 0f);                               // level shelf: same reading after the turn
        check("edge cal done", e.cal, LevelEngine.CAL_DONE, 0);
        e.cancelCal();
        feed(e, g, 1f, 0f);
    }

    /** Picked up and put back: tilted ~8 deg (about an axis across gravity) for half a second, hand shake. */
    static void lift(LevelEngine e, float[] g) {
        double[] gd = {g[0], g[1], g[2]};
        double[] k = unit(cross(gd, new double[]{0.3, 0.5, 0.8}));
        double[] r = rotate(gd, k, Math.toRadians(8));
        feed(e, new float[]{(float) r[0], (float) r[1], (float) r[2]}, 0.5f, 0.2f);
    }

    static void gyroIdle(LevelEngine e, float seconds) {
        gyro = true;
        for (int i = 0; i < Math.round(seconds * 100); i++) {
            t += 10_000_000L;
            e.onGyro(0.002f, -0.001f, 0.001f, t);
        }
    }

    /** Simulates turning about the vertical with the gyroscope, then setting down. */
    static void turn(LevelEngine e, float rad, float seconds, float[] g) {
        gyro = true;
        int n = Math.round(seconds * 100);
        float n3 = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        float rate = rad / seconds;
        for (int i = 0; i < n; i++) {
            t += 10_000_000L;
            e.onGyro(rate * g[0] / n3, rate * g[1] / n3, rate * g[2] / n3, t);
            if (i % 2 == 0) e.onGravity(g[0] + 0.3f, g[1], g[2], t);   // handling motion
        }
    }

    // ---- rigid-body simulation of a phone turned on a sloped surface ---------------------------

    /**
     * Phone resting on a sloped surface in a pose ("up", "down", "bottom", "top", "left", "right":
     * which way it lies or which edge is down), turned by turnDeg about the surface normal with
     * the gyroscope running. Checks the offset lands in that pose's slot.
     */
    static void simTurn(String pose, float turnDeg, float slopeDeg, float slopeDir, float[] bias,
                        boolean expectDone, float tol) {
        double sx = Math.tan(Math.toRadians(slopeDeg)) * Math.cos(slopeDir), sy = Math.tan(Math.toRadians(slopeDeg)) * Math.sin(slopeDir);
        double[] nrm = unit(new double[]{-sx, -sy, 1});             // surface normal, world
        double[] X, Y, Z;
        switch (pose) {
            case "up": case "down":
                Z = scale(nrm, pose.equals("up") ? 1 : -1);
                X = unit(sub(new double[]{1, 0.2, 0}, scale(nrm, dot(new double[]{1, 0.2, 0}, nrm))));
                Y = cross(Z, X);
                break;
            case "bottom": case "top":
                Y = scale(nrm, pose.equals("bottom") ? 1 : -1);
                Z = unit(sub(new double[]{0.2, 1, 0}, scale(nrm, dot(new double[]{0.2, 1, 0}, nrm))));
                X = cross(Y, Z);
                break;
            default:                                                  // left / right long side
                X = scale(nrm, pose.equals("left") ? 1 : -1);
                Y = unit(sub(new double[]{1, 0.3, 0}, scale(nrm, dot(new double[]{1, 0.3, 0}, nrm))));
                Z = cross(X, Y);
                break;
        }
        LevelEngine e = eng();
        gyro = true;
        float[] r = body(X, Y, Z, bias);
        feed(e, r, 1f, 0.01f);
        e.startCal();
        float secs1 = 0f;
        while (e.cal == LevelEngine.CAL_STEP1 && secs1 < 10f) {
            feed(e, r, 0.05f, 0.01f);
            secs1 += 0.05f;
        }
        String name = String.format("sim %s turn %.0f slope %.1f", pose, turnDeg, slopeDeg);
        if (secs1 > 2.5f) System.out.printf("   %s: step 1 took %.2f s%n", name, secs1);
        if (e.cal != LevelEngine.CAL_TURN) { check(name + " step1", e.cal, LevelEngine.CAL_TURN, 0); return; }
        double total = Math.toRadians(turnDeg), secs = 1.5;
        int steps = (int) (secs * 200);
        double rate = total / secs;
        for (int i = 0; i < steps; i++) {
            X = rotate(X, nrm, total / steps);
            Y = rotate(Y, nrm, total / steps);
            Z = rotate(Z, nrm, total / steps);
            t += 5_000_000L;
            double[] w = scale(nrm, rate);                           // world angular velocity
            e.onGyro((float) dot(X, w), (float) dot(Y, w), (float) dot(Z, w), t);
            if (i % 4 == 0) {
                float[] sm = jitter(body(X, Y, Z, bias), 0.25f);     // handling
                e.onGravity(sm[0], sm[1], sm[2], t);
            }
        }
        if (e.cal == LevelEngine.CAL_TURN) e.manualReady();
        feed(e, body(X, Y, Z, bias), 3f, 0.01f);
        if (!expectDone) {
            check(name + " rejected", e.cal, LevelEngine.CAL_FAILED, 0);
            check(name + " reason turn", e.calFail, LevelEngine.FAIL_TURN, 0);
            return;
        }
        check(name + " done", e.cal, LevelEngine.CAL_DONE, 0);
        switch (pose) {
            case "up":     check(name + " x", e.surf[0], bias[0], tol); check(name + " y", e.surf[1], bias[1], tol); break;
            case "down":   check(name + " x", e.surf[2], bias[0], tol); check(name + " y", e.surf[3], bias[1], tol);
                           check(name + " face-up slot untouched", Math.abs(e.surf[0]) + Math.abs(e.surf[1]), 0f, 0f); break;
            case "bottom": check(name + " x", e.edge[LevelEngine.EDGE_BOTTOM], bias[0], tol); break;
            case "top":    check(name + " x", e.edge[LevelEngine.EDGE_TOP], bias[0], tol); break;
            case "left":   check(name + " y", e.edge[LevelEngine.EDGE_LEFT], bias[1], tol); break;
            default:       check(name + " y", e.edge[LevelEngine.EDGE_RIGHT], bias[1], tol); break;
        }
    }

    static float[] body(double[] X, double[] Y, double[] Z, float[] b) {
        return new float[]{(float) (X[2] * G) + b[0], (float) (Y[2] * G) + b[1], (float) (Z[2] * G) + b[2]};
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] scale(double[] a, double s) { return new double[]{a[0] * s, a[1] * s, a[2] * s}; }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] unit(double[] a) { return scale(a, 1 / Math.sqrt(dot(a, a))); }
    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    /** Rodrigues rotation of v about unit axis k by ang (right-handed). */
    static double[] rotate(double[] v, double[] k, double ang) {
        double c = Math.cos(ang), s = Math.sin(ang);
        double[] kxv = cross(k, v);
        double kv = dot(k, v);
        return new double[]{v[0] * c + kxv[0] * s + k[0] * kv * (1 - c),
                v[1] * c + kxv[1] * s + k[1] * kv * (1 - c),
                v[2] * c + kxv[2] * s + k[2] * kv * (1 - c)};
    }

    static void check(String name, float got, float want, float tol) {
        boolean ok = Math.abs(got - want) <= tol;
        if (!ok) fails++;
        System.out.printf("%s %-40s got %.4f want %.4f%n", ok ? "ok  " : "FAIL", name, got, want);
    }
}
