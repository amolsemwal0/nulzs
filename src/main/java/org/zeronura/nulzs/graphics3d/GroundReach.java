package org.zeronura.nulzs.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.nulzs.render.Camera;

/**
 * How far across the ground from a camera looking down the farthest ground it shows lies: where the rays through the
 * top edge of the screen first meet the ground. A top-down camera sees nothing past that — on level ground what stands
 * farther off is above the top of the screen, and on rising ground it is behind the rise — so a world drawn with it
 * can leave out every object farther than this ({@link World3D#setDrawDistance}).
 *
 * <p>Measured on the ground itself, not on a level plane, so a camera on a cliff top looking down into a valley reaches
 * the valley floor. A camera that sees past the ground anywhere along the top edge — the sky, or the far plane — has no
 * such limit, and is told infinity.
 */
public final class GroundReach {

    /** How the ground stands: its height at a point. */
    @FunctionalInterface
    public interface Ground {
        float heightAt(float x, float z);
    }

    /** Points across the top edge of the screen the rays go through, in normalised device x. */
    private static final float[] ACROSS = {-1f, -0.5f, 0f, 0.5f, 1f};
    /** How far a ray goes between looks at the ground, in metres. */
    private static final float STEP = 0.5f;

    private GroundReach() {
    }

    /**
     * The farthest distance across the ground, from the camera, at which the top edge of its view meets the ground;
     * infinity if any of it misses the ground within the camera's far plane.
     */
    public static float of(Camera camera, Ground ground) {
        Matrix4f inverse = camera.getProjectionMatrix().mul(camera.getViewMatrix()).invert();
        Vector3f eye = camera.getPosition();
        Vector4f far = new Vector4f();
        float reach = 0f;
        for (float x : ACROSS) {
            inverse.transform(far.set(x, 1f, 1f, 1f));
            far.div(far.w);
            float hit = firstGround(eye, far.x - eye.x, far.y - eye.y, far.z - eye.z, ground);
            if (Float.isInfinite(hit)) {
                return Float.POSITIVE_INFINITY;
            }
            reach = Math.max(reach, hit);
        }
        return reach;
    }

    /** How far across the ground a ray from {@code eye} goes before it is under the ground, or infinity. */
    static float firstGround(Vector3f eye, float dx, float dy, float dz, Ground ground) {
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-6f) {
            return Float.POSITIVE_INFINITY;
        }
        float steps = (float) Math.ceil(length / STEP);
        for (int k = 1; k <= steps; k++) {
            float t = Math.min(1f, k * STEP / length);
            float x = eye.x + dx * t;
            float y = eye.y + dy * t;
            float z = eye.z + dz * t;
            if (y <= ground.heightAt(x, z)) {
                return (float) Math.hypot(dx * t, dz * t);
            }
        }
        return Float.POSITIVE_INFINITY;
    }
}
