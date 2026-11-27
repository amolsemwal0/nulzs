package org.zeronura.nulzs.gui;

import org.zeronura.nulzs.scene.Transform;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@link Tween}s. Every scene owns one, updated with the scene — {@code scene.tweens()}.
 *
 * <p>Tweens started from another tween's {@code onComplete} begin on the next update, so chains of
 * animations are safe to build from callbacks.
 */
public final class Tweens {

    private final List<Tween> active = new ArrayList<>();
    private final List<Tween> added = new ArrayList<>();
    private boolean updating;

    /** Animates a value, handing each new value to {@code setter}. */
    public Tween to(float from, float to, float duration, Ease ease, Tween.Setter setter) {
        Tween tween = new Tween(from, to, duration, ease, setter);
        (updating ? added : active).add(tween);
        return tween;
    }

    /** Moves a transform in x and y from where it is now. */
    public Tween move(Transform transform, float x, float y, float duration, Ease ease) {
        float startX = transform.position.x;
        float startY = transform.position.y;
        return to(0f, 1f, duration, ease, t -> transform.setPosition(startX + (x - startX) * t, startY + (y - startY) * t));
    }

    /** Scales a transform in x and y from its current scale. */
    public Tween scale(Transform transform, float scaleX, float scaleY, float duration, Ease ease) {
        float startX = transform.scale.x;
        float startY = transform.scale.y;
        return to(0f, 1f, duration, ease, t -> {
            transform.scale.x = startX + (scaleX - startX) * t;
            transform.scale.y = startY + (scaleY - startY) * t;
        });
    }

    /** Rotates a transform about z (2D) from its current angle, in radians. */
    public Tween rotate2D(Transform transform, float radians, float duration, Ease ease) {
        float start = transform.rotation2D();
        return to(start, radians, duration, ease, transform::setRotation2D);
    }

    public void update(float dt) {
        updating = true;
        try {
            active.removeIf(tween -> tween.update(dt));
        } finally {
            updating = false;
        }
        active.addAll(added);
        added.clear();
    }

    public void cancelAll() {
        active.forEach(Tween::cancel);
        added.forEach(Tween::cancel);
        active.clear();
        added.clear();
    }

    public int count() {
        return active.size() + added.size();
    }
}
