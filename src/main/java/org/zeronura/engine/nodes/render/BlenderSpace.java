package org.zeronura.engine.nodes.render;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.zeronura.engine.scene.Transform;

/**
 * Blender is Z up; the engine is Y up (as glTF is). Node trees run in Blender's space so their maths matches
 * Blender's; this converts at the edge, where geometry becomes something to draw: (x, y, z) becomes (x, z, -y).
 */
public final class BlenderSpace {

    private BlenderSpace() {
    }

    /** Blender's axes in the engine's: x stays, Blender's y is the engine's -z, Blender's z is the engine's y. */
    private static final Matrix4f TO_ENGINE = new Matrix4f(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f);
    private static final Matrix4f TO_BLENDER = new Matrix4f(TO_ENGINE).transpose();

    public static void point(float[] p, int o, float[] out, int oo) {
        float x = p[o], y = p[o + 1], z = p[o + 2];
        out[oo] = x;
        out[oo + 1] = z;
        out[oo + 2] = -y;
    }

    /** A Blender column-major matrix (an object's or an instance's transform) as the engine's equivalent. */
    public static Matrix4f matrix(float[] blender, int offset) {
        Matrix4f m = new Matrix4f().set(blender, offset);
        return new Matrix4f(TO_ENGINE).mul(m).mul(TO_BLENDER);
    }

    public static Matrix4f matrix(float[] blender) {
        return matrix(blender, 0);
    }

    /** Places a transform as a Blender object matrix says (shear, which a transform cannot hold, is dropped). */
    public static void apply(float[] blenderMatrix, Transform transform) {
        Matrix4f m = matrix(blenderMatrix);
        Vector3f translation = m.getTranslation(new Vector3f());
        Vector3f scale = m.getScale(new Vector3f());
        Quaternionf rotation = m.getUnnormalizedRotation(new Quaternionf()).normalize();
        if (m.determinant3x3() < 0f) {
            scale.x = -scale.x;
            rotation = new Matrix4f(m).scale(-1f, 1f, 1f).getUnnormalizedRotation(new Quaternionf()).normalize();
        }
        transform.position.set(translation);
        transform.rotation.set(rotation);
        transform.scale.set(scale);
    }
}
