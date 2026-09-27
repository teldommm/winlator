package com.winlator.cmod.math;

/**
 * Turns fractional cursor deltas into whole-pixel steps without losing the remainder.
 *
 * Every speed path used to round each event on its own (roundPoint = away from zero, or a
 * plain (int) cast). Rounding away from zero made any slow movement at least 1 px per event,
 * so speeds below 100% did nothing for fine motion; truncation did the opposite and turned
 * small stick deflections into a dead zone. Carrying the remainder to the next event makes the
 * output equal to the scaled input over time, so the number on the slider is the real speed.
 *
 * Not thread-safe: use one instance per input source, from one thread.
 */
public final class MotionAccumulator {
    private float remainderX;
    private float remainderY;
    private int stepX;
    private int stepY;

    /** Adds a fractional delta; read the whole-pixel result with {@link #x()} / {@link #y()}. */
    public void add(float dx, float dy) {
        remainderX += dx;
        remainderY += dy;
        // (int) truncates toward zero, so the remainder keeps its sign and stays within (-1, 1).
        stepX = (int) remainderX;
        stepY = (int) remainderY;
        remainderX -= stepX;
        remainderY -= stepY;
    }

    public int x() {
        return stepX;
    }

    public int y() {
        return stepY;
    }

    /** Drops the carried remainder; call when a gesture starts or the source goes idle. */
    public void reset() {
        remainderX = remainderY = 0;
        stepX = stepY = 0;
    }
}
