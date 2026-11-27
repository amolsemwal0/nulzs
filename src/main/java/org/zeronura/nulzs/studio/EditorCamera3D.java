package org.zeronura.nulzs.studio;

import org.joml.Vector3f;
import org.zeronura.nulzs.render.Camera;

/**
 * The 3D viewport's own camera, apart from any camera in the scene: it circles a point it looks at, and can fly. Right
 * drag looks round (with WASD, Q and E to fly), middle drag pans, Alt + left drag orbits, the wheel zooms, and F frames
 * what is selected. Framing glides there rather than jumping.
 */
final class EditorCamera3D {

    private static final float MAX_PITCH = (float) Math.toRadians(89);

    final Vector3f pivot = new Vector3f(0f, 0.5f, 0f);
    float distance = 11f;
    float yaw = (float) Math.toRadians(-118);
    float pitch = (float) Math.toRadians(-28);
    float fov = 60f;

    private final Vector3f glideTarget = new Vector3f();
    private float glideDistance;
    private float glide;

    Vector3f forward(Vector3f dest) {
        float cp = (float) Math.cos(pitch);
        return dest.set(cp * (float) Math.cos(yaw), (float) Math.sin(pitch), cp * (float) Math.sin(yaw));
    }

    Vector3f right(Vector3f dest) {
        return forward(dest).cross(0f, 1f, 0f).normalize();
    }

    Vector3f up(Vector3f dest) {
        Vector3f f = forward(new Vector3f());
        return right(dest).cross(f).normalize();
    }

    Vector3f position(Vector3f dest) {
        return forward(dest).mul(-distance).add(pivot);
    }

    void applyTo(Camera camera, float aspect) {
        camera.setPosition(position(new Vector3f()));
        camera.setOrientation(yaw, pitch);
        camera.setFovDegrees(fov);
        camera.setFarPlane(Math.max(1000f, distance * 20f));
        if (aspect > 0f) {
            camera.setAspectRatio(Math.round(aspect * 1000f), 1000);
        }
    }

    /** Circles the pivot. */
    void orbit(float dx, float dy) {
        yaw += dx * 0.006f;
        pitch = Math.clamp(pitch - dy * 0.006f, -MAX_PITCH, MAX_PITCH);
        glide = 0f;
    }

    /** Turns in place: the camera stays put and the pivot swings round it. */
    void look(float dx, float dy) {
        Vector3f eye = position(new Vector3f());
        yaw += dx * 0.004f;
        pitch = Math.clamp(pitch - dy * 0.004f, -MAX_PITCH, MAX_PITCH);
        pivot.set(forward(new Vector3f()).mul(distance).add(eye));
        glide = 0f;
    }

    /** Slides sideways and up, by a drag of so many GUI units in a view so many tall. */
    void pan(float dx, float dy, float viewHeight) {
        float perUnit = 2f * distance * (float) Math.tan(Math.toRadians(fov) * 0.5) / Math.max(1f, viewHeight);
        Vector3f right = right(new Vector3f());
        Vector3f up = up(new Vector3f());
        pivot.add(right.mul(-dx * perUnit)).add(up.mul(dy * perUnit));
        glide = 0f;
    }

    void zoom(float notches) {
        distance = Math.clamp(distance * (float) Math.pow(0.86, notches), 0.05f, 20000f);
        glide = 0f;
    }

    /** Flies: {@code move} is right, up and forward, each -1 to 1. */
    void fly(Vector3f move, float speed, float dt) {
        Vector3f step = new Vector3f();
        step.add(right(new Vector3f()).mul(move.x));
        step.add(0f, move.y, 0f);
        step.add(forward(new Vector3f()).mul(move.z));
        pivot.add(step.mul(speed * dt));
        glide = 0f;
    }

    /** Glides to look at a ball of {@code radius} round {@code centre}. */
    void frame(Vector3f centre, float radius) {
        glideTarget.set(centre);
        glideDistance = Math.max(0.5f, radius / (float) Math.sin(Math.toRadians(fov) * 0.5) * 1.15f);
        glide = 1f;
    }

    void update(float dt) {
        if (glide <= 0f) {
            return;
        }
        float t = 1f - (float) Math.exp(-dt * 12f);
        pivot.lerp(glideTarget, t);
        distance += (glideDistance - distance) * t;
        if (pivot.distance(glideTarget) < 0.001f && Math.abs(distance - glideDistance) < 0.001f) {
            glide = 0f;
        }
    }

    /** Looks straight along an axis: 0 front, 1 right, 2 top, as the view menu offers. */
    void view(int which) {
        switch (which) {
            case 0 -> {
                yaw = (float) Math.toRadians(-90);
                pitch = 0f;
            }
            case 1 -> {
                yaw = (float) Math.toRadians(180);
                pitch = 0f;
            }
            default -> {
                yaw = (float) Math.toRadians(-90);
                pitch = -MAX_PITCH;
            }
        }
    }
}
