package me.saatvik.hairline;

/**
 * The motion of the design system: exponential approach for anything that follows a state (it
 * cannot overshoot and needs no duration), an eased cross-fade for themes. Time constants are in
 * seconds; a value reaches its target in about four of them.
 */
public final class Motion {
    /** Switch thumb and segmented highlight. */
    public static final float CONTROL = 0.06f;
    /** Page cross-fade (settings over the main page). */
    public static final float PAGE = 0.085f;
    /** Theme cross-fade duration, eased with {@link #ease}. */
    public static final float THEME = 0.45f;

    private Motion() {
    }

    /** One frame of exponential smoothing toward {@code target}; snaps once within 0.002. */
    public static float approach(float v, float target, float dt, float tau) {
        float n = target + (v - target) * (float) Math.exp(-dt / tau);
        return Math.abs(n - target) < 0.002f ? target : n;
    }

    /** Cubic ease-in-out of t in [0, 1]. */
    public static float ease(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    /** Smoothstep of t, clamped to [0, 1]. */
    public static float smooth(float t) {
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
