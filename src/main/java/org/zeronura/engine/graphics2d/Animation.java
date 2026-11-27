package org.zeronura.engine.graphics2d;

import java.util.List;

/**
 * A sequence of frames shown at a fixed rate — sprite-sheet animation, or any timed sequence of values.
 *
 * <p>Stateless: it answers "which frame at time t", and whoever plays it owns the clock. That is what lets
 * a hundred enemies share one walk cycle while each is at a different point in it.
 */
public final class Animation<T> {

    public enum PlayMode {
        /** 0 1 2 3 0 1 2 3 … */
        LOOP,
        /** 0 1 2 3 3 3 … */
        ONCE,
        /** 0 1 2 3 2 1 0 1 … */
        PING_PONG
    }

    private final List<T> frames;
    private final float frameDuration;
    private final PlayMode mode;

    public Animation(float frameDuration, PlayMode mode, List<T> frames) {
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("an animation needs at least one frame");
        }
        if (frameDuration <= 0f) {
            throw new IllegalArgumentException("frameDuration must be positive: " + frameDuration);
        }
        this.frames = List.copyOf(frames);
        this.frameDuration = frameDuration;
        this.mode = mode;
    }

    @SafeVarargs
    public static <T> Animation<T> of(float frameDuration, PlayMode mode, T... frames) {
        return new Animation<>(frameDuration, mode, List.of(frames));
    }

    public int frameIndex(float time) {
        int count = frames.size();
        if (count == 1) {
            return 0;
        }
        int step = Math.max(0, (int) Math.floor(time / frameDuration));
        return switch (mode) {
            case LOOP -> step % count;
            case ONCE -> Math.min(step, count - 1);
            case PING_PONG -> {
                int period = 2 * count - 2;
                int phase = step % period;
                yield phase < count ? phase : period - phase;
            }
        };
    }

    public T frame(float time) {
        return frames.get(frameIndex(time));
    }

    /** Whether a {@link PlayMode#ONCE} animation has reached its last frame's end. Loops never finish. */
    public boolean isFinished(float time) {
        return mode == PlayMode.ONCE && time >= duration();
    }

    /** Length of one pass through the frames, in seconds. */
    public float duration() {
        return frames.size() * frameDuration;
    }

    public int frameCount() {
        return frames.size();
    }

    public PlayMode mode() {
        return mode;
    }
}
