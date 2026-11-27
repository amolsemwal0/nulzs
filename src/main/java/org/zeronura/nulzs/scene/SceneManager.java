package org.zeronura.nulzs.scene;

import org.zeronura.nulzs.core.Engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * The stack of active scenes.
 *
 * <p>{@link #set} replaces everything (go to the next level), {@link #push} covers the current scene
 * (open a pause menu), {@link #pop} uncovers it. Only the top scene updates; every scene from the topmost
 * non-overlay one upward is drawn, so a translucent menu shows the frozen game behind it.
 *
 * <p>Changes are applied at the end of the frame, never mid-update, so a scene can safely replace itself
 * from inside its own update.
 */
public final class SceneManager {

    private final Engine engine;
    private final List<Scene> stack = new ArrayList<>();
    private final List<Runnable> pending = new ArrayList<>();

    public SceneManager(Engine engine) {
        this.engine = engine;
    }

    /** Replaces every scene with {@code scene}. */
    public void set(Scene scene) {
        pending.add(() -> {
            exitAll();
            enter(scene);
        });
    }

    /** Puts {@code scene} on top of the current one. */
    public void push(Scene scene) {
        pending.add(() -> enter(scene));
    }

    /** Removes the top scene. */
    public void pop() {
        pending.add(() -> {
            if (!stack.isEmpty()) {
                stack.removeLast().detach();
            }
        });
    }

    /** The top scene, or null. */
    public Scene current() {
        return stack.isEmpty() ? null : stack.getLast();
    }

    public List<Scene> stack() {
        return Collections.unmodifiableList(stack);
    }

    public void applyPending() {
        while (!pending.isEmpty()) {
            List<Runnable> operations = List.copyOf(pending);
            pending.clear();
            operations.forEach(Runnable::run);
        }
    }

    public void fixedUpdate(float dt) {
        Scene top = current();
        if (top != null) {
            top.fixedUpdate(dt);
        }
    }

    public void update(float dt) {
        Scene top = current();
        if (top != null) {
            top.update(dt);
        }
    }

    public void render(float alpha) {
        if (stack.isEmpty()) {
            glClearColor(0f, 0f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            return;
        }
        render(stack.size(), alpha);
    }

    /**
     * Draws the frame as it would look without {@code scene} and every scene over it: what a menu is covering, for a
     * picture of it. The frame proper is still to be drawn over this one.
     *
     * @return false, having drawn nothing, if {@code scene} is not on the stack or nothing is under it
     */
    public boolean renderBelow(Scene scene) {
        int index = stack.indexOf(scene);
        if (index <= 0) {
            return false;
        }
        render(index, 1f);
        return true;
    }

    /** Draws the lowest {@code count} scenes of the stack as though they were all of it. */
    private void render(int count, float alpha) {
        int base = count - 1;
        while (base > 0 && stack.get(base).isOverlay()) {
            base--;
        }
        int top = count - 1;
        boolean guiHidden = stack.get(top).hidesGuiBelow();
        for (int i = base; i < count; i++) {
            // A covered scene's simulation is paused, so it is drawn as its last step left it. Blending between its
            // last two steps by this frame's leftover time would swing anything that was moving back and forth
            // between them, every frame, for as long as it stays covered.
            stack.get(i).render(i == top ? alpha : 1f, i == base, i == top || !guiHidden);
        }
    }

    public void resize(int width, int height) {
        for (Scene scene : stack) {
            scene.resize(width, height);
        }
    }

    /** The scenes on the stack, bottom to top, a line each, with what each says it is showing: for a report. */
    public String describe() {
        StringBuilder lines = new StringBuilder();
        for (Scene scene : stack) {
            lines.append(scene.getClass().getSimpleName());
            try {
                String line = scene.reportLine();
                if (line != null) {
                    lines.append(": ").append(line);
                }
            } catch (RuntimeException e) {
                lines.append(" (could not say what it was showing: ").append(e).append(')');
            }
            lines.append('\n');
        }
        return lines.isEmpty() ? "none" : lines.toString().stripTrailing();
    }

    /** Exits every scene immediately and drops pending changes. */
    public void clear() {
        pending.clear();
        exitAll();
    }

    private void enter(Scene scene) {
        stack.add(scene);
        scene.attach(engine);
        scene.start();
    }

    private void exitAll() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            stack.get(i).detach();
        }
        stack.clear();
    }
}
