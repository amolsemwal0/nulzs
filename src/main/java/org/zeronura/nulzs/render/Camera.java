package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {

    private static final float MAX_PITCH = (float) Math.toRadians(89.0);

    private final Vector3f position;
    private final Vector3f target;
    private final Vector3f up = new Vector3f(0, 1, 0);

    /** Look direction is stored as yaw/pitch; {@link #target} is always derived from it. */
    private float yaw;
    private float pitch;

    public static final float DEFAULT_FOV_DEGREES = 45.0f;

    private float fovDegrees = DEFAULT_FOV_DEGREES;
    private float aspectRatio;
    private float nearPlane = 0.1f;
    // Set from how far terrain actually streams (see setFarPlane); this is only the fallback for a camera
    // nobody has told. The aerial-perspective fog saturates just inside whatever it is, so the clip is
    // never seen.
    private float farPlane = 400.0f;

    public Camera(Vector3f position, Vector3f target, float aspectRatio) {
        this.position = position;
        this.target = target;
        this.aspectRatio = aspectRatio;

        Vector3f forward = target.sub(position, new Vector3f()).normalize();
        this.pitch = (float) Math.asin(clamp(forward.y, -1.0f, 1.0f));
        this.yaw = (float) Math.atan2(forward.z, forward.x);
        updateTarget();
    }

    public void setAspectRatio(int width, int height) {
        this.aspectRatio = (float) width / (float) height;
    }

    /** Vertical field of view. A player setting: how much of the world one screen holds. */
    public void setFovDegrees(float fovDegrees) {
        this.fovDegrees = Math.max(30f, Math.min(110f, fovDegrees));
    }

    /** Width over height, as the projection uses it. Needed to build a camera matching this one. */
    public float getAspectRatio() {
        return aspectRatio;
    }

    public float getFovDegrees() {
        return fovDegrees;
    }

    /**
     * How far the camera can see, which has to be set from how far the world is actually built.
     *
     * <p>Clipping short of the streamed terrain wastes the streaming; clipping past it shows the player
     * the edge of the world. The near plane is deliberately not exposed alongside it — depth precision is
     * governed by the near plane far more than the far one, and a near plane a player could raise would
     * clip the third-person camera through the character's own shoulder.
     */
    public void setFarPlane(float farPlane) {
        this.farPlane = Math.max(nearPlane * 10f, farPlane);
    }

    public float getFarPlane() {
        return farPlane;
    }

    public float getNearPlane() {
        return nearPlane;
    }

    public Matrix4f getViewMatrix() {
        return new Matrix4f().lookAt(position, target, up);
    }

    public Matrix4f getProjectionMatrix() {
        return new Matrix4f().perspective((float) Math.toRadians(fovDegrees), aspectRatio, nearPlane, farPlane);
    }

    public Vector3f getPosition() {
        return position;
    }

    public Vector3f getTarget() {
        return target;
    }

    public Vector3f getUp() {
        return up;
    }

    public Vector3f getForward(Vector3f dest) {
        return target.sub(position, dest).normalize();
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    /** Turns the camera in place. Pitch is clamped short of straight up/down so lookAt never degenerates. */
    public void rotate(float deltaYaw, float deltaPitch) {
        yaw += deltaYaw;
        pitch = clamp(pitch + deltaPitch, -MAX_PITCH, MAX_PITCH);
        updateTarget();
    }

    /** Sets the aim direction absolutely (pitch clamped), for loading a save. */
    public void setOrientation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = clamp(pitch, -MAX_PITCH, MAX_PITCH);
        updateTarget();
    }

    public void translate(Vector3f delta) {
        position.add(delta);
        target.add(delta);
    }

    /**
     * Moves the camera to a point without changing where it looks.
     *
     * <p>Used by follow cameras, which decide position from the player and the view angles rather than by
     * accumulating deltas. The look direction stays owned by {@link #yaw}/{@link #pitch}, so a third-person
     * rig can place itself along the backward ray and still be aiming exactly where the player is looking.
     */
    public void setPosition(Vector3f newPosition) {
        position.set(newPosition);
        updateTarget();
    }

    private void updateTarget() {
        float cosPitch = (float) Math.cos(pitch);
        target.set(
                position.x + cosPitch * (float) Math.cos(yaw),
                position.y + (float) Math.sin(pitch),
                position.z + cosPitch * (float) Math.sin(yaw)
        );
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
