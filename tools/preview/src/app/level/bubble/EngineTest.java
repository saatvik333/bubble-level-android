package app.level.bubble;

import java.util.Random;

/** JVM checks of the sensor math: angles, signs, mode hysteresis, edge quadrants, two-step calibration. */
public final class EngineTest {
    static final float G = 9.80665f;
    static int fails;
    static long t = 1_000_000_000L;
    static final Random rnd = new Random(7);

    public static void main(String[] a) {
        // 1. flat, right edge raised 3 deg: up vector tilts toward +x
        LevelEngine e = new LevelEngine();
        feed(e, up(3f, 0f), 2f, 0f);
        check("flat tilt 3", e.tilt, 3f, 0.05f);
        check("flat ax +3 (right high)", e.ax, 3f, 0.05f);
        check("flat ay 0", e.ay, 0f, 0.05f);
        check("mode flat", e.mode, LevelEngine.MODE_FLAT, 0);

        // 2. compound tilt
        e = new LevelEngine();
        feed(e, up(1.5f, -2f), 2f, 0f);
        check("compound ax", e.ax, 1.5f, 0.06f);
        check("compound ay", e.ay, -2f, 0.06f);

        // 3. upright portrait, rotated 1.2 deg clockwise -> left side high -> dev < 0
        e = new LevelEngine();
        feed(e, inPlane(-1.2f), 2f, 0f);
        check("edge mode", e.mode, LevelEngine.MODE_EDGE, 0);
        check("edge quarter 0", e.quarter, 0, 0);
        check("edge dev -1.2", e.dev, -1.2f, 0.05f);

        // 4. landscape (device rotated 90 cw + 0.8)
        e = new LevelEngine();
        feed(e, inPlane(-90.8f), 2f, 0f);
        check("landscape quarter -90", e.quarter, -90, 0);
        check("landscape dev -0.8", e.dev, -0.8f, 0.05f);

        // 5. hysteresis: 45 deg inclination keeps the current mode
        e = new LevelEngine();
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
        e = new LevelEngine();
        feed(e, plus(up(slopeX, slopeY), bias), 1.5f, 0.01f);
        float before = e.ax;
        e.startCal();
        feed(e, plus(up(slopeX, slopeY), bias), 2.5f, 0.01f);
        check("cal step1 captured", e.cal, LevelEngine.CAL_TURN, 0);
        turn(e, (float) Math.PI, 2, plus(up(slopeX, slopeY), bias));
        check("gyro detected turn", e.cal, LevelEngine.CAL_STEP2, 0);
        feed(e, plus(up(-slopeX, -slopeY), bias), 2.5f, 0.01f);   // surface slope flips in device frame
        check("cal done", e.cal, LevelEngine.CAL_DONE, 0);
        check("bias x recovered", e.bx, bias[0], 0.004f);
        check("bias y recovered", e.by, bias[1], 0.004f);
        e.cancelCal();
        feed(e, plus(up(slopeX, slopeY), bias), 2f, 0.01f);
        System.out.printf("   before cal ax=%.3f, after cal ax=%.3f (true %.3f)%n", before, e.ax, slopeX);
        check("calibrated ax = true slope", e.ax, slopeX, 0.04f);
        check("calibrated ay = true slope", e.ay, slopeY, 0.04f);

        // 7. edge calibration: standing portrait, turned end for end about device y
        e = new LevelEngine();
        float[] b2 = {0.06f, 0.0f, -0.04f};
        feed(e, plus(inPlane(0.9f), b2), 1.5f, 0.01f);
        e.startCal();
        feed(e, plus(inPlane(0.9f), b2), 2.5f, 0.01f);
        e.manualReady();
        feed(e, plus(inPlane(-0.9f), b2), 2.5f, 0.01f);
        check("edge cal done", e.cal, LevelEngine.CAL_DONE, 0);
        check("edge bias x", e.bx, b2[0], 0.004f);
        e.cancelCal();
        feed(e, plus(inPlane(0.9f), b2), 2f, 0.01f);
        check("edge calibrated dev", e.dev, 0.9f, 0.04f);

        // 8. a moved setup is rejected
        e = new LevelEngine();
        feed(e, up(0f, 0f), 1f, 0f);
        e.startCal();
        feed(e, up(0f, 0f), 2.5f, 0f);
        e.manualReady();
        feed(e, up(9f, 0f), 2.5f, 0f);
        check("large offset rejected", e.cal, LevelEngine.CAL_FAILED, 0);

        // 9. steadiness: noisy accelerometer at rest, reading jitter after settling
        e = new LevelEngine();
        float lo = 99, hi = -99;
        for (int i = 0; i < 400; i++) {
            float[] g = jitter(up(0.5f, 0f), 0.02f);
            t += 20_000_000L;
            e.onGravity(g[0], g[1], g[2], t);
            if (i > 150) { lo = Math.min(lo, e.ax); hi = Math.max(hi, e.ax); }
        }
        System.out.printf("   jitter at rest with 0.02 m/s^2 noise: %.3f deg p-p%n", hi - lo);
        check("rest jitter < 0.1 deg", hi - lo, 0f, 0.1f);

        // 10. display rotated 90 (landscape-natural tablet held portrait, or system-rotated window):
        //     device +x edge raised = screen top raised
        e = new LevelEngine();
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

    static float[] plus(float[] v, float[] b) {
        return new float[]{v[0] + b[0], v[1] + b[1], v[2] + b[2]};
    }

    static float[] jitter(float[] v, float s) {
        return new float[]{v[0] + (float) rnd.nextGaussian() * s, v[1] + (float) rnd.nextGaussian() * s, v[2] + (float) rnd.nextGaussian() * s};
    }

    static void feed(LevelEngine e, float[] g, float seconds, float noise) {
        int n = Math.round(seconds * 50);
        for (int i = 0; i < n; i++) {
            float[] s = noise > 0 ? jitter(g, noise) : g;
            t += 20_000_000L;
            e.onGravity(s[0], s[1], s[2], t);
        }
    }

    /** Simulates turning about the vertical with the gyroscope, then setting down. */
    static void turn(LevelEngine e, float rad, float seconds, float[] g) {
        int n = Math.round(seconds * 100);
        float n3 = (float) Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        float rate = rad / seconds;
        for (int i = 0; i < n; i++) {
            t += 10_000_000L;
            e.onGyro(rate * g[0] / n3, rate * g[1] / n3, rate * g[2] / n3, t);
            if (i % 2 == 0) e.onGravity(g[0] + 0.3f, g[1], g[2], t);   // handling motion
        }
    }

    static void check(String name, float got, float want, float tol) {
        boolean ok = Math.abs(got - want) <= tol;
        if (!ok) fails++;
        System.out.printf("%s %-32s got %.4f want %.4f%n", ok ? "ok  " : "FAIL", name, got, want);
    }
}
