package org.zeronura.engine.render;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * A world's {@link PointLight}s — every street lamp on an island — and how brightly they burn, of which the scene
 * shaders are given the {@value #MAX} nearest a focus each frame.
 *
 * <h2>The nearest, and fading as they leave</h2>
 * A fragment's light is a loop over every light it is given, so a frame is given a handful rather than the
 * hundreds a map can hold: the nearest to the focus, which is where the player is when the game says so and the
 * camera otherwise. As the focus moves, a light drops out of that handful and another takes its place; were it lit
 * at full strength until the frame it dropped, its pool would vanish from the ground at once. So each light is faded
 * by how much nearer it is than the nearest one left out: one {@value #FADE} world units nearer or more is at full
 * strength, and one only just nearer is all but out already, so whichever light changes places is dark as it does.
 *
 * <h2>Brightness</h2>
 * {@link #setBrightness} scales every light together, 0 to 1: lamps coming on at dusk and going out at dawn. At 0
 * none is given to the shaders at all, so a world in daylight pays nothing for its lamps.
 */
public final class PointLights {

    /** The most a frame is given. Must match {@code MAX_POINT_LIGHTS} in the scene shaders. */
    public static final int MAX = 16;
    /** How much nearer than the nearest left out a light has to be to shine at full strength, in world units. */
    public static final float FADE = 6f;

    private final List<PointLight> lights = new ArrayList<>();
    private final Vector3f focus = new Vector3f();
    private boolean hasFocus;
    private float brightness = 1f;

    // Scratch for picking the nearest: indices into `lights` and their distances, nearest first.
    private final int[] picked = new int[MAX + 1];
    private final float[] pickedDistance = new float[MAX + 1];

    /** Replaces every light with these. */
    public void set(Collection<PointLight> all) {
        lights.clear();
        lights.addAll(all);
    }

    public void clear() {
        lights.clear();
    }

    public List<PointLight> all() {
        return List.copyOf(lights);
    }

    /** Scales every light, from 0 for all out to 1 for all at their own strength. */
    public void setBrightness(float brightness) {
        this.brightness = Math.clamp(brightness, 0f, 1f);
    }

    public float brightness() {
        return brightness;
    }

    /** Where the lights given to the shaders are the nearest to: the player. Until it is set, the camera. */
    public void setFocus(Vector3f at) {
        focus.set(at);
        hasFocus = true;
    }

    /** The focus set, or null when there is none and the camera is used. */
    public Vector3f focus() {
        return hasFocus ? focus : null;
    }

    /**
     * Fills {@code into} with this frame's lights: up to {@value #MAX} nearest {@code focus}, the further ones faded
     * as the class says, all scaled by the brightness. None when the brightness is 0 or there are no lights.
     *
     * @return {@code into}
     */
    public Frame nearest(Vector3f focus, Frame into) {
        into.count = 0;
        if (brightness <= 0f || lights.isEmpty()) {
            return into;
        }
        // Insertion into a list kept nearest first, one longer than a frame holds: its last is the nearest left out.
        int kept = 0;
        for (int i = 0; i < lights.size(); i++) {
            float distance = lights.get(i).position().distance(focus);
            if (kept == MAX + 1 && distance >= pickedDistance[MAX]) {
                continue;
            }
            int at = Math.min(kept, MAX);
            while (at > 0 && pickedDistance[at - 1] > distance) {
                picked[at] = picked[at - 1];
                pickedDistance[at] = pickedDistance[at - 1];
                at--;
            }
            picked[at] = i;
            pickedDistance[at] = distance;
            kept = Math.min(kept + 1, MAX + 1);
        }
        float leftOut = kept > MAX ? pickedDistance[MAX] : Float.POSITIVE_INFINITY;
        for (int k = 0; k < Math.min(kept, MAX); k++) {
            float strength = brightness * Math.clamp((leftOut - pickedDistance[k]) / FADE, 0f, 1f);
            if (strength <= 0f) {
                continue;
            }
            PointLight light = lights.get(picked[k]);
            into.put(light.position(), light.color().x * strength, light.color().y * strength,
                    light.color().z * strength, light.radius());
        }
        return into;
    }

    /** The lights a frame is lit by, as the shaders take them: flat arrays, the first {@link #count} in use. */
    public static final class Frame {

        /** No lights at all. Never filled. */
        public static final Frame NONE = new Frame();

        int count;
        final float[] position = new float[MAX * 3];
        final float[] color = new float[MAX * 3];
        final float[] radius = new float[MAX];

        public int count() {
            return count;
        }

        void put(Vector3f at, float red, float green, float blue, float reach) {
            int i = count++;
            position[i * 3] = at.x;
            position[i * 3 + 1] = at.y;
            position[i * 3 + 2] = at.z;
            color[i * 3] = red;
            color[i * 3 + 1] = green;
            color[i * 3 + 2] = blue;
            radius[i] = reach;
        }

        /** The {@code i}th light's colour channel, for tests: 0 red, 1 green, 2 blue. */
        public float color(int i, int channel) {
            return color[i * 3 + channel];
        }

        public float radius(int i) {
            return radius[i];
        }

        public Vector3f position(int i, Vector3f into) {
            return into.set(position[i * 3], position[i * 3 + 1], position[i * 3 + 2]);
        }

        /** Makes this the same as {@code other}; whether that changed anything. */
        boolean copy(Frame other) {
            boolean same = count == other.count
                    && Arrays.equals(position, 0, count * 3, other.position, 0, count * 3)
                    && Arrays.equals(color, 0, count * 3, other.color, 0, count * 3)
                    && Arrays.equals(radius, 0, count, other.radius, 0, count);
            if (same) {
                return false;
            }
            count = other.count;
            System.arraycopy(other.position, 0, position, 0, count * 3);
            System.arraycopy(other.color, 0, color, 0, count * 3);
            System.arraycopy(other.radius, 0, radius, 0, count);
            return true;
        }
    }
}
