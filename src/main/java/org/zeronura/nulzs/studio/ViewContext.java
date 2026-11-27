package org.zeronura.nulzs.studio;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.nulzs.ui.Rect;

/**
 * How the viewport sees the world this frame: its rectangle on screen in GUI units and the camera's matrices, with the
 * two conversions every tool needs — a world point to where it lands on screen, and a point on screen to the ray of
 * world under it. The same for the 3D view's perspective camera and the 2D view's flat one, so one gizmo serves both.
 */
final class ViewContext {

    final Rect rect;
    final Matrix4f viewProjection;
    final Matrix4f inverse;
    final Vector3f eye;
    final Vector3f forward;
    final boolean flat;
    /** Field of view in radians for the 3D view; for the 2D view, world units per GUI unit. */
    final float lens;

    ViewContext(Rect rect, Matrix4f viewProjection, Vector3f eye, Vector3f forward, boolean flat, float lens) {
        this.rect = rect;
        this.viewProjection = new Matrix4f(viewProjection);
        this.inverse = new Matrix4f(viewProjection).invert();
        this.eye = new Vector3f(eye);
        this.forward = new Vector3f(forward).normalize();
        this.flat = flat;
        this.lens = lens;
    }

    /**
     * Where a world point lands, in GUI units, into {@code dest} (x, y), with z its depth in clip space. False if it is
     * behind the camera.
     */
    boolean project(Vector3f world, Vector3f dest) {
        Vector4f clip = viewProjection.transform(new Vector4f(world, 1f));
        if (clip.w <= 1e-5f) {
            return false;
        }
        float nx = clip.x / clip.w;
        float ny = clip.y / clip.w;
        dest.set(rect.x() + (nx * 0.5f + 0.5f) * rect.width(), rect.y() + (0.5f - ny * 0.5f) * rect.height(), clip.z / clip.w);
        return true;
    }

    /** The ray under a point on screen (GUI units): {@code origin} on the near plane, {@code direction} unit length. */
    void ray(float x, float y, Vector3f origin, Vector3f direction) {
        float nx = (x - rect.x()) / rect.width() * 2f - 1f;
        float ny = 1f - (y - rect.y()) / rect.height() * 2f;
        Vector3f near = inverse.transformProject(new Vector3f(nx, ny, -1f));
        Vector3f far = inverse.transformProject(new Vector3f(nx, ny, 1f));
        origin.set(near);
        direction.set(far).sub(near).normalize();
    }

    /** How many world units one GUI unit covers at a point: what keeps a gizmo the same size on screen. */
    float worldPerUnit(Vector3f at) {
        if (flat) {
            return lens;
        }
        float distance = Math.max(0.01f, new Vector3f(at).sub(eye).dot(forward));
        return 2f * distance * (float) Math.tan(lens * 0.5f) / Math.max(1f, rect.height());
    }
}
