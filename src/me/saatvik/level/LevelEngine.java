package me.saatvik.level;

/**
 * Sensor math, free of Android dependencies so it can be exercised on a plain JVM.
 *
 * Input: gravity vector in device coordinates (x right, y up the screen, z out of the screen),
 * pointing away from the earth, as reported by TYPE_GRAVITY / TYPE_ACCELEROMETER.
 * Optional input: gyroscope (rad/s, device coordinates), used only while calibrating.
 *
 * Filtering: adaptive one-pole low-pass, defined in time rather than per sample so behaviour does
 * not depend on the delivery rate. The time constant shrinks while the device moves (responsive)
 * and grows while it rests (steady last digit). Motion is judged on a lightly pre-filtered vector,
 * so sensor noise alone does not open the filter.
 *
 * Calibration: two-position reversal. A sensor offset b is fixed in the device frame, while the
 * surface slope t turns with the device when it is rotated about the vertical. Two readings
 * r1 = b + t and r2 = b + R(phi) t give t = (I - R(phi))^-1 (r1 - r2) and b = r1 - t; with
 * phi = 180 degrees this is b = (r1 + r2) / 2. Works on a surface that is not level.
 *
 * The offset depends on the pose (a camera bump tilts the phone lying flat, each edge has its own
 * geometry, and an in-plane sensor misalignment changes sign between opposite edges), so every
 * pose has its own offset: face up, face down, and each of the four edges.
 */
final class LevelEngine {
    static final int MODE_FLAT = 0, MODE_EDGE = 1;
    static final int LOCK_AUTO = 0, LOCK_FLAT = 1, LOCK_EDGE = 2;
    static final int CAL_IDLE = 0, CAL_STEP1 = 1, CAL_TURN = 2, CAL_STEP2 = 3, CAL_DONE = 4, CAL_FAILED = 5;
    static final int EV_CAPTURE = 1, EV_DONE = 2, EV_FAILED = 4;
    static final int HINT_NONE = 0, HINT_TURN_FIRST = 1, HINT_WRONG_SIDE = 2;
    static final int FAIL_NONE = 0, FAIL_OFFSET = 1, FAIL_TURN = 2;

    private static final float RAD = (float) (180.0 / Math.PI);
    private static final float PRE_TAU = 0.05f;           // s, pre-filter for the motion estimate
    private static final float SPEED_TAU = 0.15f;
    private static final float SPEED_DEAD = 0.05f;        // rad/s of apparent motion that noise alone produces
    private static final float SPEED_FULL = 0.45f;        // above dead zone: filter fully open
    private static final float STILL_TAU = 0.15f;         // s, smoothing of the stillness vector
    private static final float NOISE_TAU = 0.2f;
    private static final float NOISE_CAP = 4f * 0.03f * 0.03f;   // a jolt counts as "not still", not as hours of noise
    private static final float SNAP_RAD = 0.06f;          // ~3.4 deg, far above sensor noise
    private static final float STILL_RAD = 0.0035f;       // ~0.2 deg: minimum drift cone while "still"
    private static final float STILL_NOISE_MAX = 0.008f;  // rad per axis; above this it is hand-held
    private static final long STILL_NS = 450_000_000L;    // must stay inside the cone this long
    private static final long CAPTURE_NS = 1_200_000_000L;
    private static final float MAX_BIAS = 1.0f;           // m/s^2, ~6 deg; larger means the setup moved
    private static final float TURN_RAD = 2.6f;           // ~150 deg of yaw: advance to step 2 on its own
    private static final float TURN_MIN = 2.1f, TURN_MAX = 4.2f;   // accepted measured turn, 120..240 deg
    private static final float NOT_TURNED = 0.8f;         // < ~45 deg measured: Ready is refused
    private static final float EDGE_TURN_TOL = 0.15f;     // rad (~9 deg): edges only solve for a 180 turn
    private static final float GYRO_DEAD = 0.06f;         // rad/s, dead zone while the gyro bias is unknown
    private static final float GYRO_DEAD_KNOWN = 0.015f;  // rad/s, once the bias was measured in step 1
    private static final float GAP_TAU = 1.0f;            // s, resting statistics of the step gate
    private static final float MOVED_RAD = 0.02f;         // ~1.1 deg away from the step 1 reading
    private static final float MOVED_GYRO = 0.3f;         // rad/s

    // configuration
    int lock = LOCK_AUTO;
    /** Surface offsets, device frame, m/s^2: {x, y} face up, then {x, y} face down. */
    final float[] surf = new float[4];
    /**
     * Edge offsets along the edge, m/s^2: [EDGE_BOTTOM] upright (x), [EDGE_TOP] upside down (x),
     * [EDGE_LEFT] on the left long side (y), [EDGE_RIGHT] on the right long side (y).
     */
    final float[] edge = new float[4];
    static final int EDGE_BOTTOM = 0, EDGE_TOP = 1, EDGE_LEFT = 2, EDGE_RIGHT = 3;
    /**
     * Which slots hold a measurement: bit 0 face up, bit 1 face down, bit 2 + i edge i. A pose
     * without one borrows its opposite (face up/down, bottom/top, left/right): same axis, so a
     * plain sensor bias carries over, which is the usual and larger part of an offset.
     */
    int calMask;
    int rotation;                  // Surface.ROTATION_0..3 of the display the UI is drawn on

    // filter state
    private boolean has;
    private long lastNs;
    private float fx, fy, fz;          // filtered raw gravity (offset not removed)
    private float px, py, pz;          // pre-filtered unit vector, motion estimate only
    private float speed;               // smoothed angular speed, rad/s

    // stillness detector: smoothed vector inside a cone around an anchor
    private float lx, ly, lz;
    private float mx0, my0, mz0;       // smoothed without snapping: a single jolt does not count as a move
    private float ax0, ay0, az0;
    private float ux, uy, uz;          // previous raw unit vector
    private float noise2;              // mean square of successive raw unit differences, rad^2 (= 4 sigma^2)
    private float gap2 = 1e-6f;        // resting mean square of the step gate's gap, rad^2
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
    boolean calEdge;                   // pose being calibrated: false = lying flat, true = on an edge
    int calHint, calFail;
    private int calAxis = -1, calSign;
    private float yaw;
    private boolean moved;
    private double gbx, gby, gbz;      // gyro samples while still in step 1, for its bias
    private int gbn;
    private float biasX, biasY, biasZ;
    private boolean biasKnown;
    private double sx, sy, sz;
    private int sn;
    private long captureStartNs = -1;
    private float c1x, c1y, c1z;
    private int events;

    void onGravity(float rx, float ry, float rz, long ns) {
        float n = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (n < 1f) return;                       // free fall or garbage
        float qx = rx / n, qy = ry / n, qz = rz / n;
        boolean reset;
        if (!has) {
            has = true;
            fx = rx; fy = ry; fz = rz;
            px = lx = ax0 = mx0 = qx;
            py = ly = ay0 = my0 = qy;
            pz = lz = az0 = mz0 = qz;
            noise2 = 0f;
            ux = qx; uy = qy; uz = qz;
            anchorNs = ns;
            lastNs = ns;
            reset = false;
        } else {
            float dt = clamp((ns - lastNs) * 1e-9f, 1e-4f, 0.2f);
            lastNs = ns;

            // Sensor noise from successive differences: white noise shows up fully, real motion
            // (smooth at these rates) barely, so a move does not read as noise.
            float dx0 = qx - ux, dy0 = qy - uy, dz0 = qz - uz;
            ux = qx; uy = qy; uz = qz;
            noise2 += (Math.min(dx0 * dx0 + dy0 * dy0 + dz0 * dz0, NOISE_CAP) - noise2)
                    * (1f - (float) Math.exp(-dt / NOISE_TAU));
            float sigma = (float) Math.sqrt(noise2 * 0.25f);                  // per axis, raw

            float opx = px, opy = py, opz = pz;
            float ap = 1f - (float) Math.exp(-dt / PRE_TAU);
            px += (qx - px) * ap;
            py += (qy - py) * ap;
            pz += (qz - pz) * ap;
            float inst = angle(px, py, pz, opx, opy, opz) / dt;
            speed += (inst - speed) * (1f - (float) Math.exp(-dt / SPEED_TAU));
            float k = clamp((speed - SPEED_DEAD) / SPEED_FULL, 0f, 1f);
            // Step gate: when the pre-filtered direction sits well outside its usual distance from
            // the output (a bump, a shim under a leg), open the filter until it has caught up.
            // "Usual" is learned from the gap itself, so correlated noise (a vibrating surface)
            // raises the threshold instead of opening the gate. A step only grows it slowly.
            float gap = angle(px, py, pz, fx, fy, fz);
            float thr = 3f * (float) Math.sqrt(gap2) + 0.0003f;
            k = Math.max(k, clamp((gap - thr) / 0.002f, 0f, 1f));
            gap2 += (Math.min(gap * gap, 16f * gap2 + 1e-8f) - gap2) * (1f - (float) Math.exp(-dt / GAP_TAU));
            float tau = 0.50f + (0.05f - 0.50f) * k;
            float a = 1f - (float) Math.exp(-dt / tau);
            fx += (rx - fx) * a;
            fy += (ry - fy) * a;
            fz += (rz - fz) * a;

            // stillness: the smoothed vector must stay inside a cone sized to the measured noise
            if (angle(qx, qy, qz, lx, ly, lz) > SNAP_RAD) {
                lx = qx; ly = qy; lz = qz;             // a real move: no need to wait for the smoothing
            } else {
                float al = 1f - (float) Math.exp(-dt / STILL_TAU);
                lx += (qx - lx) * al;
                ly += (qy - ly) * al;
                lz += (qz - lz) * al;
            }
            float am = 1f - (float) Math.exp(-dt / 0.25f);
            mx0 += (qx - mx0) * am;
            my0 += (qy - my0) * am;
            mz0 += (qz - mz0) * am;
            float sigmaL = sigma * (float) Math.sqrt(dt / (2f * STILL_TAU));  // per axis, smoothed
            // Hysteresis: once still, 1.5x the cone and the noise are tolerated, so a capture
            // in progress is not restarted by a noise excursion.
            float hyst = still ? 1.5f : 1f;
            float cone = Math.max(STILL_RAD, 5f * sigmaL) * hyst;
            boolean gyroQuiet = !gyroSeen || ns - lastGyroNs > 500_000_000L || gyroMag < 0.06f;
            reset = angle(lx, ly, lz, ax0, ay0, az0) > cone || sigma > STILL_NOISE_MAX * hyst
                    || !gyroQuiet;
            if (reset) {
                ax0 = lx; ay0 = ly; az0 = lz;
                anchorNs = ns;
            }
            if ((cal == CAL_TURN || cal == CAL_STEP2) && !moved) {
                float n1 = (float) Math.sqrt(c1x * c1x + c1y * c1y + c1z * c1z);
                // Sustained change of direction only (smoothed, never snapped): a tap or a jolt
                // when pressing Ready is not a turn. Handling always tilts the phone this much.
                moved = angle(mx0, my0, mz0, c1x / n1, c1y / n1, c1z / n1) > MOVED_RAD;
            }
        }
        still = !reset && ns - anchorNs > STILL_NS;

        compute();
        if (cal == CAL_STEP1) calEdge = incl > 45f;       // instruction follows the live pose until captured
        if (cal == CAL_STEP1 || cal == CAL_STEP2) capture(rx, ry, rz, ns);
    }

    void onGyro(float wx, float wy, float wz, long ns) {
        float dt = lastGyroNs == 0 ? 0f : clamp((ns - lastGyroNs) * 1e-9f, 0f, 0.1f);
        lastGyroNs = ns;
        gyroSeen = true;
        gyroMag = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (cal == CAL_STEP1 && still) {
            gbx += wx; gby += wy; gbz += wz;
            gbn++;
        }
        if ((cal == CAL_TURN || cal == CAL_STEP2) && has) {
            if (gyroMag > MOVED_GYRO) moved = true;
            float n = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            // rotation rate about the vertical, bias removed when it was measured
            float w = ((wx - biasX) * fx + (wy - biasY) * fy + (wz - biasZ) * fz) / n;
            if (Math.abs(w) > (biasKnown ? GYRO_DEAD_KNOWN : GYRO_DEAD)) yaw += w * dt;
            if (Math.abs(yaw) > NOT_TURNED) moved = true;
            if (cal == CAL_TURN && Math.abs(yaw) > TURN_RAD) enterStep2();
        }
    }

    /** Re-evaluates outputs, e.g. after the display rotation changed without a new sample. */
    void refresh() {
        if (has) compute();
    }

    private void compute() {
        float n0 = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (n0 < 1e-3f) return;
        // Mode from the raw vector: offsets are a fraction of a degree, the hysteresis band is ten.
        incl = (float) Math.acos(Math.min(1f, Math.abs(fz) / n0)) * RAD;
        if (lock == LOCK_FLAT) mode = MODE_FLAT;
        else if (lock == LOCK_EDGE) mode = MODE_EDGE;
        else if (mode == MODE_FLAT && incl > 50f) mode = MODE_EDGE;
        else if (mode == MODE_EDGE && incl < 40f) mode = MODE_FLAT;

        // Offsets belong to the pose they were measured in (device frame).
        boolean flatPose = lock == LOCK_AUTO ? mode == MODE_FLAT : incl < 45f;
        float dx = fx, dy = fy;
        if (flatPose) {
            int f = slot(fz >= 0f ? 0 : 1, 0);
            dx -= surf[2 * f];
            dy -= surf[2 * f + 1];
        } else if (Math.abs(fy) >= Math.abs(fx)) {
            dx -= edge[slot(fy >= 0f ? EDGE_BOTTOM : EDGE_TOP, 2)];     // x runs along the edge
        } else {
            dy -= edge[slot(fx >= 0f ? EDGE_LEFT : EDGE_RIGHT, 2)];     // y runs along the edge
        }
        float gz = fz;

        // Outputs in display coordinates, so the bubble tracks the screen on landscape-natural
        // tablets and when the system rotates the window.
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
        tilt = (float) Math.atan2(h, gz) * RAD;
        ax = (float) Math.atan2(gx, gz) * RAD;
        ay = (float) Math.atan2(gy, gz) * RAD;
        dir = (float) Math.atan2(gy, gx);
        roll = (float) Math.atan2(gx, gy) * RAD;

        // Lying flat, roll is noise: keep the last quarter instead of spinning the tube.
        if (h > 0.5f * n && Math.abs(wrap(roll - quarter)) > 52f) {
            quarter = Math.round(roll / 90f) * 90;
            if (quarter == -180) quarter = 180;
        }
        // Slope of the tube axis (cos q, -sin q) against the horizontal plane. Exact at any lean
        // of the screen; atan2(gx, gy) alone would overstate it by 1 / cos(lean).
        double q = Math.toRadians(quarter);
        float along = (float) (gx * Math.cos(q) - gy * Math.sin(q)) / n;
        dev = (float) Math.asin(clamp(along, -1f, 1f)) * RAD;
    }

    // ---- calibration -------------------------------------------------------------------------

    void startCal() {
        if (!has) return;
        cal = CAL_STEP1;
        calAxis = -1;
        calEdge = incl > 45f;
        calHint = HINT_NONE;
        calFail = FAIL_NONE;
        moved = false;
        yaw = 0f;
        gyroSeen = false;                 // re-established by this calibration's own gyro events
        gbx = gby = gbz = 0;
        gbn = 0;
        biasX = biasY = biasZ = 0f;
        biasKnown = false;
        resetCapture();
    }

    void cancelCal() {
        cal = CAL_IDLE;
        calHint = HINT_NONE;
        resetCapture();
    }

    /** Manual fallback for "I have turned it around" (no gyroscope, or a turn the gyro missed). */
    void manualReady() {
        if (cal != CAL_TURN) return;
        if (gyroSeen && Math.abs(yaw) < NOT_TURNED) {
            calHint = HINT_TURN_FIRST;
            return;
        }
        enterStep2();
    }

    int takeEvents() {
        int e = events;
        events = 0;
        return e;
    }

    private void enterStep2() {
        cal = CAL_STEP2;
        calHint = HINT_NONE;
        resetCapture();
    }

    private void resetCapture() {
        sx = sy = sz = 0;
        sn = 0;
        captureStartNs = -1;
        calProgress = 0f;
    }

    private void capture(float rx, float ry, float rz, long ns) {
        boolean ok = still;
        if (cal == CAL_STEP2) {
            boolean sameFace = dominantAxis(rx, ry, rz) == calAxis
                    && (component(rx, ry, rz, calAxis) >= 0 ? 1 : -1) == calSign;
            calHint = !sameFace ? HINT_WRONG_SIDE : !moved ? HINT_TURN_FIRST : HINT_NONE;
            ok &= sameFace && moved;
        }
        if (!ok) {
            resetCapture();
            return;
        }
        if (captureStartNs < 0) captureStartNs = ns;
        sx += rx; sy += ry; sz += rz;
        sn++;
        calProgress = Math.min(1f, (ns - captureStartNs) / (float) CAPTURE_NS);
        if (calProgress < 1f || sn < 10) return;

        float mx = (float) (sx / sn), my = (float) (sy / sn), mz = (float) (sz / sn);
        if (cal == CAL_STEP1) {
            // The pose actually captured decides what is calibrated, whatever the mode said.
            c1x = mx; c1y = my; c1z = mz;
            calAxis = dominantAxis(mx, my, mz);
            calSign = component(mx, my, mz, calAxis) >= 0 ? 1 : -1;
            calEdge = calAxis != 2;
            if (gbn >= 20) {
                biasX = (float) (gbx / gbn);
                biasY = (float) (gby / gbn);
                biasZ = (float) (gbz / gbn);
                biasKnown = true;
            }
            cal = CAL_TURN;
            moved = false;
            yaw = 0f;
            resetCapture();
            events |= EV_CAPTURE;
            return;
        }

        // step 2
        // A gyro seen in this calibration decides the turn, even if its events stopped since:
        // the yaw it integrated is then incomplete, and refusing is the safe outcome.
        boolean gyro = gyroSeen;
        float turn = gyro ? Math.abs(yaw) : (float) Math.PI;
        boolean ok2;
        if (gyro && (turn < TURN_MIN || turn > TURN_MAX
                || (calAxis != 2 && Math.abs(turn - (float) Math.PI) > EDGE_TURN_TOL))) {
            calFail = FAIL_TURN;
            ok2 = false;
        } else if (calAxis == 2) {
            // Device frame sees the world slope turn by -yaw about "up"; up is -z when face down.
            float phi = gyro ? -calSign * yaw : (float) Math.PI;
            float c = (float) Math.cos(phi), s = (float) Math.sin(phi);
            float det = 2f * (1f - c);
            float ddx = c1x - mx, ddy = c1y - my;
            float tx = ((1f - c) * ddx - s * ddy) / det;
            float ty = (s * ddx + (1f - c) * ddy) / det;
            float nbx = c1x - tx, nby = c1y - ty;
            ok2 = Math.abs(nbx) <= MAX_BIAS && Math.abs(nby) <= MAX_BIAS;
            if (ok2) {
                int f = calSign > 0 ? 0 : 1;
                surf[2 * f] = nbx;
                surf[2 * f + 1] = nby;
                calMask |= 1 << f;
            }
        } else {
            // On an edge only the axis along the edge is solvable (the lean is not world-fixed),
            // and only for a half turn.
            float nb = calAxis == 1 ? (c1x + mx) / 2f : (c1y + my) / 2f;
            ok2 = Math.abs(nb) <= MAX_BIAS;
            if (ok2) {
                int slot = calAxis == 1 ? (calSign > 0 ? EDGE_BOTTOM : EDGE_TOP)
                        : (calSign > 0 ? EDGE_LEFT : EDGE_RIGHT);
                edge[slot] = nb;
                calMask |= 1 << (2 + slot);
            }
        }
        if (ok2) {
            cal = CAL_DONE;
            events |= EV_DONE;
            compute();
        } else {
            if (calFail == FAIL_NONE) calFail = FAIL_OFFSET;
            cal = CAL_FAILED;
            events |= EV_FAILED;
        }
        calHint = HINT_NONE;
        sx = sy = sz = 0;
        sn = 0;
        captureStartNs = -1;
        calProgress = 1f;
    }

    /** The slot to apply for pose {@code i} (bit {@code base + i}): its own, else its opposite's. */
    private int slot(int i, int base) {
        if ((calMask & (1 << (base + i))) != 0) return i;
        if ((calMask & (1 << (base + (i ^ 1)))) != 0) return i ^ 1;
        return i;
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
