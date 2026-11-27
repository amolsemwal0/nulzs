package org.zeronura.nulzs.scene;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Position, rotation and scale of an entity, relative to its parent.
 *
 * <p>One transform type serves 2D and 3D. A 2D game uses x and y and a rotation about z, through the
 * {@code 2D} helpers; nothing about the storage changes, which is what lets a 2D HUD entity sit in a 3D
 * scene or a 3D model be parented to a 2D-driven object without a conversion layer.
 *
 * <p>The fields are public and mutable on purpose — {@code transform.position.x += 1} is the most common
 * line in any game — and are final so a reference handed out stays live.
 */
public final class Transform {

    public final Vector3f position = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();
    public final Vector3f scale = new Vector3f(1f);

    Transform parent;

    public Transform parent() {
        return parent;
    }

    public Matrix4f localMatrix(Matrix4f dest) {
        return dest.translationRotateScale(position, rotation, scale);
    }

    /** This transform composed with every parent's, root first. */
    public Matrix4f worldMatrix(Matrix4f dest) {
        localMatrix(dest);
        if (parent != null) {
            Matrix4f parentWorld = parent.worldMatrix(new Matrix4f());
            parentWorld.mul(dest, dest);
        }
        return dest;
    }

    public Vector3f worldPosition(Vector3f dest) {
        if (parent == null) {
            return dest.set(position);
        }
        return worldMatrix(new Matrix4f()).getTranslation(dest);
    }

    /** The direction this transform faces: local -Z, the OpenGL camera convention. */
    public Vector3f forward(Vector3f dest) {
        return rotation.transform(dest.set(0f, 0f, -1f));
    }

    public Vector3f right(Vector3f dest) {
        return rotation.transform(dest.set(1f, 0f, 0f));
    }

    public Vector3f up(Vector3f dest) {
        return rotation.transform(dest.set(0f, 1f, 0f));
    }

    public Transform setPosition(float x, float y, float z) {
        position.set(x, y, z);
        return this;
    }

    /** Sets the uniform scale. */
    public Transform setScale(float uniform) {
        scale.set(uniform);
        return this;
    }

    /** Rotation about the world up axis, in radians. Positive turns +Z toward +X. */
    public Transform setYaw(float radians) {
        rotation.rotationY(radians);
        return this;
    }

    // --- 2D helpers -------------------------------------------------------------------------------------

    public float x() {
        return position.x;
    }

    public float y() {
        return position.y;
    }

    public Transform setPosition(float x, float y) {
        position.x = x;
        position.y = y;
        return this;
    }

    public Transform translate(float dx, float dy) {
        position.x += dx;
        position.y += dy;
        return this;
    }

    /** Rotation about z in radians, counter-clockwise. Exact for a pure z rotation, which 2D keeps it to. */
    public float rotation2D() {
        return 2f * (float) Math.atan2(rotation.z, rotation.w);
    }

    public Transform setRotation2D(float radians) {
        rotation.rotationZ(radians);
        return this;
    }
}
