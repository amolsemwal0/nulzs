package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sign convention the third-person shoulder offset depends on: moving the camera along
 * {@code forward × up} must push what it looks at to the <i>left</i> of the crosshair, not the right.
 *
 * <p>Worth a test rather than an eyeball: the framing is subtly wrong-but-plausible if the sign flips —
 * the character simply sits on the other side — and this is pure matrix math with no GL in it.
 */
class CameraShoulderOffsetTest {

    /** Projects a world point to normalised device coordinates; x < 0 is left of screen centre. */
    private static Vector3f toNdc(Camera camera, Vector3f worldPoint) {
        Vector4f clip = new Vector4f(worldPoint, 1f)
                .mul(camera.getViewMatrix())
                .mul(camera.getProjectionMatrix());
        return new Vector3f(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w);
    }

    @Test
    void offsettingTheCameraRightMovesTheSubjectLeftOfTheCrosshair() {
        // Note the defensive copy: Camera keeps the vectors it is handed and rewrites its target in
        // place, so passing the subject straight in would make it follow the camera and hide the shift.
        Vector3f subject = new Vector3f(0f, 0f, 0f);
        Camera camera = new Camera(new Vector3f(0f, 0f, 4f), new Vector3f(subject), 16f / 9f);

        assertEquals(0f, toNdc(camera, subject).x, 1e-5f, "straight-behind camera centres the subject");

        Vector3f forward = camera.getForward(new Vector3f());
        Vector3f right = new Vector3f(forward).cross(0f, 1f, 0f).normalize();
        assertEquals(0f, right.y, 1e-6f, "the shoulder offset must stay horizontal");

        // setPosition moves the camera without changing where it looks, so the aim ray — and therefore
        // the crosshair — is unchanged; only the framing shifts.
        camera.setPosition(new Vector3f(camera.getPosition()).fma(0.8f, right));

        float ndcX = toNdc(camera, subject).x;
        assertTrue(ndcX < -0.05f, "subject should sit clearly left of centre, got ndcX=" + ndcX);
        assertTrue(ndcX > -0.9f, "but still comfortably on screen, got ndcX=" + ndcX);
    }
}
