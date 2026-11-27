package org.zeronura.nulzs.core;

/**
 * Frame timing as a game sees it.
 *
 * <p>{@link #delta()} is scaled by {@link #timeScale()} so slow motion and pausing are one setting;
 * {@link #unscaledDelta()} is what menus and UI animation should use, since a paused game still needs a
 * responsive pause menu.
 */
public final class Time {

    /** How quickly the displayed FPS follows the real one. Lower is steadier. */
    private static final float FPS_SMOOTHING = 0.1f;

    private final float fixedDelta;
    private float delta;
    private float unscaledDelta;
    private double total;
    private double unscaledTotal;
    private float timeScale = 1f;
    private float fps;
    private long frame;

    public Time(float fixedDelta) {
        this.fixedDelta = fixedDelta;
    }

    void beginFrame(float rawDelta) {
        unscaledDelta = rawDelta;
        delta = rawDelta * timeScale;
        total += delta;
        unscaledTotal += rawDelta;
        frame++;
        if (rawDelta > 0f) {
            float instantaneous = 1f / rawDelta;
            fps = fps == 0f ? instantaneous : fps + (instantaneous - fps) * FPS_SMOOTHING;
        }
    }

    /** Seconds since the last frame, scaled. */
    public float delta() {
        return delta;
    }

    /** Seconds since the last frame, ignoring {@link #timeScale()}. */
    public float unscaledDelta() {
        return unscaledDelta;
    }

    /** The constant step {@code fixedUpdate} runs at. */
    public float fixedDelta() {
        return fixedDelta;
    }

    /** Scaled seconds since the engine started. */
    public double total() {
        return total;
    }

    public double unscaledTotal() {
        return unscaledTotal;
    }

    public float timeScale() {
        return timeScale;
    }

    /** 1 is normal speed, 0 pauses simulation, 0.25 is slow motion. */
    public void setTimeScale(float scale) {
        this.timeScale = Math.max(0f, scale);
    }

    /** Smoothed frames per second. */
    public float fps() {
        return fps;
    }

    public long frame() {
        return frame;
    }
}
