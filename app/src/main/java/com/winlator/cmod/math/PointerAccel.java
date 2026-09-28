package com.winlator.cmod.math;

/**
 * Velocity-based pointer acceleration for finger-driven cursor movement (free touch area,
 * Trackpad elements, mouse-move buttons).
 *
 * The old acceleration multiplied each axis by 1.25 whenever that axis moved more than 6 X-server
 * pixels in one touch event. That was a step (6 px -> 6, 6.1 px -> 7.6), bent diagonals (only
 * one axis got boosted), and depended on the display refresh rate (events are batched per vsync,
 * so at 120 Hz each delta is half as big and the boost barely triggered), on the game resolution
 * (X pixels) and on Cursor Speed itself (the threshold was checked after scaling).
 *
 * Here the finger speed is measured on the physical screen in dp/s, smoothed, and mapped through
 * a smoothstep to a gain applied to the whole vector. The range 1.0 .. 1.25 is the same as before;
 * the ramp sits around the speed where the old 6 px/event threshold kicked in at 60 Hz for a
 * typical 720p-on-phone setup, so medium and fast swipes feel as they did.
 *
 * Not thread-safe: one instance per finger / source, used from one thread.
 */
public final class PointerAccel {
    public static final float MIN_GAIN = 1.0f;
    public static final float MAX_GAIN = 1.25f;
    // Finger speed (dp/s) where the gain starts rising and where it reaches MAX_GAIN.
    public static final float LOW_SPEED_DP = 150f;
    public static final float HIGH_SPEED_DP = 400f;
    // Event gaps outside this range are clamped: batched events can arrive nearly together, and
    // a long gap (finger resting) must not read as a very slow move.
    private static final float MIN_DT_S = 0.004f;
    private static final float MAX_DT_S = 0.05f;

    private final float density;
    private long lastTimeMs = -1;
    private float velocityDp;

    public PointerAccel(float density) {
        this.density = density > 0 ? density : 1f;
    }

    /** Starts a new gesture at the given time (event time or uptime, in ms). */
    public void reset(long timeMs) {
        lastTimeMs = timeMs;
        velocityDp = 0;
    }

    /**
     * Returns the gain for a move of (screenDx, screenDy) physical pixels reported at timeMs.
     * Apply it to the already speed-scaled delta vector.
     */
    public float gain(float screenDx, float screenDy, long timeMs) {
        float distanceDp = (float) Math.hypot(screenDx, screenDy) / density;
        float dt = lastTimeMs < 0 ? 1f / 60f : (timeMs - lastTimeMs) / 1000f;
        lastTimeMs = timeMs;
        dt = Math.max(MIN_DT_S, Math.min(MAX_DT_S, dt));
        float instant = distanceDp / dt;
        velocityDp = velocityDp == 0 ? instant : velocityDp * 0.5f + instant * 0.5f;

        float k = (velocityDp - LOW_SPEED_DP) / (HIGH_SPEED_DP - LOW_SPEED_DP);
        k = Math.max(0f, Math.min(1f, k));
        k = k * k * (3f - 2f * k);
        return MIN_GAIN + (MAX_GAIN - MIN_GAIN) * k;
    }
}
