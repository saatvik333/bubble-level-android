package app.level.bubble;

/**
 * Sensor math, free of Android dependencies so it can be exercised on a plain JVM.
 *
 * Input: gravity vector in device coordinates (x right, y up the screen, z out of the screen),
 * pointing away from the earth, as reported by TYPE_GRAVITY / TYPE_ACCELEROMETER.
 * Optional input: gyroscope (rad/s, device coordinates), used only while calibrating.
 *
 * Filtering: adaptive one-pole low-pass. Time constant shrinks while the device moves (responsive)
 * and grows while it rests (steady last digit).
 *
 * Calibration: two-position reversal. A sensor offset b is fixed in the device frame, while the
 * surface slope t flips sign in the two horizontal device axes when the device is turned 180 degrees
 * about the vertical. Averaging both readings cancels t and leaves b:  b = (g1 + g2) / 2 on the two
 * non-vertical axes. Works on a surface that is not level.
 */
final class LevelEngine {
    static final int MODE_FLAT = 0, MODE_EDGE = 1;
    static final int LOCK_AUTO = 0, LOCK_FLAT = 1, LOCK_EDGE = 2;
    static final int CAL_IDLE = 0, CAL_STEP1 = 1, CAL_TURN = 2, CAL_STEP2 = 3, CAL_DONE = 4, CAL_FAILED = 5;
    static final int EV_CAPTURE = 1, EV_DONE = 2, EV_FAILED = 4;

    private static final float RAD = (float) (180.0 / Math.PI);
    private static final float STILL_RAD = 0.0035f;       // ~0.2 deg drift allowed while "still"
    private static final long STILL_NS = 450_000_000L;    // must stay inside that cone this long
    private static final long CAPTURE_NS = 1_200_000_000L;
    private static final float MAX_BIAS = 0.6f;           // m/s^2, ~3.5 deg; larger means the setup moved
    private static final float TURN_RAD = 2.6f;           // ~150 deg of yaw counts as "turned around"

    // configuration
    int lock = LOCK_AUTO;
    float bx, by, bz;              // sensor offset, device frame
    int rotation;                  // Surface.ROTATION_0..3 of the display the UI is drawn on

    // filter state
    private boolean has;
    private long lastNs;
    private float fx, fy, fz;          // filtered raw gravity (bias not removed)
    private float ux, uy, uz;          // previous raw unit vector
    private float speed;               // smoothed angular speed, rad/s

    // stillness detector: anchor cone
    private float ax0, ay0, az0;
    private float lx, ly, lz;
    private long anchorNs;
    private boolean still;
    private float gyroMag;
    private long lastGyroNs;
    boolean gyroSeen;

    // outputs
    boolean valid;
    int mode = MODE_FLAT;
    float tilt, ax, ay, dir, incl, roll, dev;
    int quarter;

    // calibration
    int cal = CAL_IDLE;
    float calProgress;
    boolean calEdge;
    private int calAxis, calSign;
    private float yaw;
    private double sx, sy, sz;
    private int sn;
    private long captureStartNs = -1;
    private float c1x, c1y, c1z;
    private int events;

    void onGravity(float rx, float ry, float rz, long ns) {
        float n = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (n < 1f) return;                       // free fall or garbage
        float qx = rx / n, qy = ry / n, qz = rz / n;
        if (!has) {
            has = true;
            fx = rx; fy = ry; fz = rz;
            ux = qx; uy = qy; uz = qz;
            ax0 = qx; ay0 = qy; az0 = qz;
            anchorNs = ns;
            lastNs = ns;
        } else {
            float dt = clamp((ns - lastNs) * 1e-9f, 1e-4f, 0.2f);
            lastNs = ns;
            float inst = angle(qx, qy, qz, ux, uy, uz) / dt;
            ux = qx; uy = qy; uz = qz;
            speed += (inst - speed) * (1f - (float) Math.exp(-dt / 0.15f));
            float k = clamp(speed / 0.5f, 0f, 1f);
            float tau = 0.40f + (0.05f - 0.40f) * k;
            float a = 1f - (float) Math.exp(-dt / tau);
            fx += (rx - fx) * a;
            fy += (ry - fy) * a;
            fz += (rz - fz) * a;
        }

        // stillness, judged on a lightly smoothed unit vector so sensor noise alone never breaks it
        if (anchorNs == ns) {
            lx = qx; ly = qy; lz = qz;
        } else {
            lx += (qx - lx) * 0.18f;
            ly += (qy - ly) * 0.18f;
            lz += (qz - lz) * 0.18f;
        }
        boolean gyroQuiet = !gyroSeen || ns - lastGyroNs > 500_000_000L || gyroMag < 0.06f;
        if (angle(lx, ly, lz, ax0, ay0, az0) > STILL_RAD || !gyroQuiet) {
            ax0 = lx; ay0 = ly; az0 = lz;
            anchorNs = ns;
        }
        still = ns - anchorNs > STILL_NS;

        compute();
        if (cal == CAL_STEP1 || cal == CAL_STEP2) capture(rx, ry, rz, ns);
    }

    void onGyro(float wx, float wy, float wz, long ns) {
        float dt = lastGyroNs == 0 ? 0f : clamp((ns - lastGyroNs) * 1e-9f, 0f, 0.1f);
        lastGyroNs = ns;
        gyroSeen = true;
        gyroMag = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (cal == CAL_TURN && has) {
            float n = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            yaw += (wx * fx + wy * fy + wz * fz) / n * dt;   // rotation rate about the vertical
            if (Math.abs(yaw) > TURN_RAD) enterStep2();
        }
    }

    /** Re-evaluates outputs, e.g. after the display rotation changed without a new sample. */
    void refresh() {
        if (has) compute();
    }

    private void compute() {
        // Filtering, stillness and calibration stay in the device frame (sensor offsets are fixed
        // to the hardware). Only the outputs are expressed in display coordinates, so the bubble
        // tracks the screen on landscape-natural tablets and when the system rotates the window.
        float dx = fx - bx, dy = fy - by, gz = fz - bz;
        float gx, gy;
        switch (rotation & 3) {
            case 1:  gx = -dy; gy = dx;  break;   // ROTATION_90
            case 2:  gx = -dx; gy = -dy; break;   // ROTATION_180
            case 3:  gx = dy;  gy = -dx; break;   // ROTATION_270
            default: gx = dx;  gy = dy;  break;
        }
        float n = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (n < 1e-3f) return;
        valid = true;
        float h = (float) Math.sqrt(gx * gx + gy * gy);
        incl = (float) Math.acos(Math.min(1f, Math.abs(gz) / n)) * RAD;
        tilt = (float) Math.atan2(h, gz) * RAD;
        ax = (float) Math.atan2(gx, gz) * RAD;
        ay = (float) Math.atan2(gy, gz) * RAD;
        dir = (float) Math.atan2(gy, gx);
        roll = (float) Math.atan2(gx, gy) * RAD;

        if (lock == LOCK_FLAT) mode = MODE_FLAT;
        else if (lock == LOCK_EDGE) mode = MODE_EDGE;
        else if (mode == MODE_FLAT && incl > 50f) mode = MODE_EDGE;
        else if (mode == MODE_EDGE && incl < 40f) mode = MODE_FLAT;

        if (Math.abs(wrap(roll - quarter)) > 52f) {
            quarter = Math.round(roll / 90f) * 90;
            if (quarter == -180) quarter = 180;
        }
        dev = wrap(roll - quarter);
    }

    // ---- calibration -------------------------------------------------------------------------

    void startCal() {
        if (!has) return;
        cal = CAL_STEP1;
        calEdge = mode == MODE_EDGE;
        calAxis = dominantAxis(fx, fy, fz);
        calSign = component(fx, fy, fz, calAxis) >= 0 ? 1 : -1;
        resetCapture();
    }

    void cancelCal() {
        cal = CAL_IDLE;
        resetCapture();
    }

    /** Manual fallback for "I have turned it around" (no gyroscope, or user preference). */
    void manualReady() {
        if (cal == CAL_TURN) enterStep2();
    }

    int takeEvents() {
        int e = events;
        events = 0;
        return e;
    }

    private void enterStep2() {
        cal = CAL_STEP2;
        resetCapture();
    }

    private void resetCapture() {
        sx = sy = sz = 0;
        sn = 0;
        captureStartNs = -1;
        calProgress = 0f;
        yaw = 0f;
    }

    private void capture(float rx, float ry, float rz, long ns) {
        boolean sameFace = dominantAxis(rx, ry, rz) == calAxis
                && (component(rx, ry, rz, calAxis) >= 0 ? 1 : -1) == calSign;
        if (!still || !sameFace) {
            sx = sy = sz = 0;
            sn = 0;
            captureStartNs = -1;
            calProgress = 0f;
            return;
        }
        if (captureStartNs < 0) captureStartNs = ns;
        sx += rx; sy += ry; sz += rz;
        sn++;
        calProgress = Math.min(1f, (ns - captureStartNs) / (float) CAPTURE_NS);
        if (calProgress < 1f || sn < 10) return;

        float mx = (float) (sx / sn), my = (float) (sy / sn), mz = (float) (sz / sn);
        if (cal == CAL_STEP1) {
            c1x = mx; c1y = my; c1z = mz;
            cal = CAL_TURN;
            resetCapture();
            events |= EV_CAPTURE;
            return;
        }
        // step 2: resolve the two horizontal components of the offset
        float nbx = calAxis == 0 ? bx : (c1x + mx) / 2f;
        float nby = calAxis == 1 ? by : (c1y + my) / 2f;
        float nbz = calAxis == 2 ? bz : (c1z + mz) / 2f;
        float ox = calAxis == 0 ? 0f : nbx, oy = calAxis == 1 ? 0f : nby, oz = calAxis == 2 ? 0f : nbz;
        if (Math.abs(ox) > MAX_BIAS || Math.abs(oy) > MAX_BIAS || Math.abs(oz) > MAX_BIAS) {
            cal = CAL_FAILED;
            events |= EV_FAILED;
        } else {
            bx = nbx; by = nby; bz = nbz;
            cal = CAL_DONE;
            events |= EV_DONE;
            compute();
        }
        sx = sy = sz = 0;
        sn = 0;
        captureStartNs = -1;
        calProgress = 1f;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static int dominantAxis(float x, float y, float z) {
        float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        if (az >= ax && az >= ay) return 2;
        return ax >= ay ? 0 : 1;
    }

    private static float component(float x, float y, float z, int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static float angle(float x1, float y1, float z1, float x2, float y2, float z2) {
        float d = x1 * x2 + y1 * y2 + z1 * z2;
        float cx = y1 * z2 - z1 * y2, cy = z1 * x2 - x1 * z2, cz = x1 * y2 - y1 * x2;
        return (float) Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), d);
    }

    static float wrap(float deg) {
        while (deg > 180f) deg -= 360f;
        while (deg <= -180f) deg += 360f;
        return deg;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
