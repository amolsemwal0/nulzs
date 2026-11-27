package org.zeronura.engine.studio;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.ui.Rect;

/**
 * A tool that works in the Scene view on the scene itself, from the Tools menu: it can be drawing something new, and it
 * puts handles on what it made when that is selected. Over the viewport it goes first, before the gizmo and picking.
 */
interface SceneTool {

    /** Its name in the Tools menu. */
    String name();

    /** What to do, shown over the viewport while it is drawing. */
    String hint();

    /** Whether it is drawing something new. */
    boolean drawing();

    /** Whether it works in the 2D Scene view rather than the 3D one. */
    default boolean twoD() {
        return false;
    }

    /** Starts drawing something new. */
    void start();

    /** Stops drawing, keeping what was drawn if there is enough of it. */
    void finish();

    /** Takes back the last point drawn. */
    void back();

    /**
     * Draws its handles and lines over the viewport and handles the pointer for them. Returns whether it has the
     * pointer, so the gizmo and picking leave the press alone.
     *
     * @param free whether the pointer is over the view and not busy turning the camera or dragging a file
     */
    boolean viewport(Ui ui, ViewContext view, Rect content, boolean free);

    // --- Shared ------------------------------------------------------------------------------------------------

    float HANDLE = 6.5f;

    /** Where the ray under the pointer meets the level plane at height {@code y}, or null if it never does in front. */
    static Vector3f onLevel(ViewContext view, float mx, float my, float y) {
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        view.ray(mx, my, origin, direction);
        if (Math.abs(direction.y) < 1e-5f) {
            return null;
        }
        float t = (y - origin.y) / direction.y;
        if (t <= 0f || t > 5000f) {
            return null;
        }
        return direction.mul(t).add(origin);
    }

    /** Where the ray under the pointer meets the upright plane through {@code through} square to the camera. */
    static Vector3f onUpright(ViewContext view, float mx, float my, Vector3f through) {
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        view.ray(mx, my, origin, direction);
        Vector3f normal = new Vector3f(view.forward.x, 0f, view.forward.z);
        if (normal.lengthSquared() < 1e-6f) {
            normal.set(0f, 0f, 1f);
        }
        normal.normalize();
        float facing = direction.dot(normal);
        if (Math.abs(facing) < 1e-5f) {
            return null;
        }
        float t = new Vector3f(through).sub(origin).dot(normal) / facing;
        return t <= 0f ? null : direction.mul(t).add(origin);
    }

    /** A world point in an entity's own space. */
    static Vector3f toLocal(Entity entity, Vector3f world) {
        return entity.transform().worldMatrix(new Matrix4f()).invert().transformPosition(new Vector3f(world));
    }

    static Vector3f toWorld(Entity entity, float x, float y, float z) {
        return entity.transform().worldMatrix(new Matrix4f()).transformPosition(new Vector3f(x, y, z));
    }

    /** A world point on a grid of {@code step} when snapping is on. */
    static Vector3f snap(StudioScene studio, Vector3f point) {
        if (point == null || !studio.snapping()) {
            return point;
        }
        float step = studio.prefs.moveSnap;
        return point.set(Math.round(point.x / step) * step, point.y, Math.round(point.z / step) * step);
    }

    /** A handle: a ring with a dot, lit when the pointer is on it. */
    static void handle(Ui ui, float x, float y, boolean hot, boolean first, Color color) {
        float r = first ? HANDLE + 1.5f : HANDLE;
        ui.gui.circle(x, y, r + 2f, new Color(0f, 0f, 0f, 0.55f));
        ui.gui.circle(x, y, r, hot ? Theme.HIGHLIGHT : color);
        ui.gui.circle(x, y, r * 0.45f, hot ? Color.WHITE : Theme.WINDOW);
    }

    /** A hint banner along the top of the viewport. */
    static void banner(Ui ui, Rect content, String text) {
        float w = Math.min(content.width() - 20f, ui.width(text, Theme.SMALL_SCALE) + 28f);
        Rect box = new Rect(content.centerX() - w * 0.5f, content.y() + 10f, w, 26f);
        ui.gui.rounded(box, 13f, new Color(0.08f, 0.09f, 0.11f, 0.85f));
        ui.gui.roundedOutline(box, 13f, 1f, Theme.ACCENT);
        ui.textCentered(ui.fit(text, w - 16f, Theme.SMALL_SCALE), box, Theme.SMALL_SCALE, Theme.TEXT_BRIGHT);
    }
}
