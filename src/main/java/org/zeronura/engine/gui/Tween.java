package org.zeronura.engine.gui;

/**
 * Animates one float from a start value to an end value over time. Create through {@link Tweens}.
 *
 * <pre>{@code
 * scene.tweens().to(0f, 1f, 0.3f, Ease.BACK_OUT, v -> card.transform().setScale(v))
 *      .delay(0.1f)
 *      .onComplete(() -> System.out.println("dealt"));
 * }</pre>
 */
public final class Tween {

    @FunctionalInterface
    public interface Setter {
        void set(float value);
    }

    private final float from;
    private final float to;
    private final float duration;
    private final Ease ease;
    private final Setter setter;

    private float elapsed;
    private float delay;
    private int repeats;
    private boolean yoyo;
    private boolean reversed;
    private boolean finished;
    private boolean cancelled;
    private Runnable onComplete;

    Tween(float from, float to, float duration, Ease ease, Setter setter) {
        this.from = from;
        this.to = to;
        this.duration = Math.max(0f, duration);
        this.ease = ease;
        this.setter = setter;
    }

    /** Waits before starting. The start value is not applied until the delay ends. */
    public Tween delay(float seconds) {
        this.delay = Math.max(0f, seconds);
        return this;
    }

    /** Plays this many extra times; -1 repeats forever. */
    public Tween repeat(int times) {
        this.repeats = times;
        return this;
    }

    /** With {@link #repeat}, alternates direction each pass instead of jumping back to the start. */
    public Tween yoyo(boolean enabled) {
        this.yoyo = enabled;
        return this;
    }

    public Tween onComplete(Runnable action) {
        this.onComplete = action;
        return this;
    }

    /** Stops without applying the end value or running onComplete. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isFinished() {
        return finished || cancelled;
    }

    /** Advances the tween. Returns true once it is done and can be dropped. */
    boolean update(float dt) {
        if (cancelled) {
            return true;
        }
        if (finished) {
            return true;
        }
        if (delay > 0f) {
            delay -= dt;
            if (delay > 0f) {
                return false;
            }
            dt = -delay;
            delay = 0f;
        }
        elapsed += dt;
        float t = duration <= 0f ? 1f : Math.min(1f, elapsed / duration);
        float progress = ease.apply(reversed ? 1f - t : t);
        setter.set(from + (to - from) * progress);
        if (t < 1f) {
            return false;
        }
        if (repeats != 0) {
            if (repeats > 0) {
                repeats--;
            }
            elapsed = duration <= 0f ? 0f : elapsed - duration;
            if (yoyo) {
                reversed = !reversed;
            }
            return false;
        }
        finished = true;
        if (onComplete != null) {
            onComplete.run();
        }
        return true;
    }
}
