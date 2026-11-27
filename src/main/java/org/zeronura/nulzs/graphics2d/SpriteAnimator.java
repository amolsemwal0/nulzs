package org.zeronura.nulzs.graphics2d;

import org.zeronura.nulzs.scene.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Plays named sprite-sheet animations on the entity's {@link SpriteRenderer}.
 *
 * <pre>{@code
 * var animator = player.add(new SpriteAnimator());
 * animator.add("idle", Animation.of(0.2f, LOOP, frames[0], frames[1]));
 * animator.add("run", Animation.of(0.08f, LOOP, frames[2], frames[3], frames[4]));
 * animator.play("run");
 * }</pre>
 */
public class SpriteAnimator extends Component {

    private final Map<String, Animation<TextureRegion>> animations = new HashMap<>();
    private String current;
    private float time;
    /** Playback rate; 2 plays twice as fast. */
    public float speed = 1f;
    private boolean paused;

    public SpriteAnimator add(String name, Animation<TextureRegion> animation) {
        animations.put(name, animation);
        return this;
    }

    /** Switches to an animation. Calling it with the animation already playing does not restart it. */
    public void play(String name) {
        if (!animations.containsKey(name)) {
            throw new IllegalArgumentException("No animation named '" + name + "'");
        }
        if (!name.equals(current)) {
            current = name;
            time = 0f;
        }
        paused = false;
        applyFrame();
    }

    public void restart() {
        time = 0f;
        applyFrame();
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public String current() {
        return current;
    }

    /** Whether the current animation is a play-once that has ended. */
    public boolean isFinished() {
        return current != null && animations.get(current).isFinished(time);
    }

    @Override
    protected void update(float dt) {
        if (current == null || paused) {
            return;
        }
        time += dt * speed;
        applyFrame();
    }

    private void applyFrame() {
        SpriteRenderer sprite = entity() == null ? null : entity().get(SpriteRenderer.class);
        if (sprite != null && current != null) {
            sprite.region = animations.get(current).frame(time);
        }
    }
}
