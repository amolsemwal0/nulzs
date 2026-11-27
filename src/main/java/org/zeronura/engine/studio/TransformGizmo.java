package org.zeronura.engine.studio;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.gui.Gui;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.scene.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The handles drawn on what is selected in the viewport, for moving, turning and resizing it by dragging: arrows along
 * the three axes and squares for the planes between them, rings round each axis, and boxes on stalks. They keep the
 * same size on screen however far away the selection is. Drawn in screen space over the picture, so they are never
 * hidden behind what they move.
 */
final class TransformGizmo {

    enum Mode { MOVE, ROTATE, SCALE }

    enum Handle { NONE, X, Y, Z, XY, XZ, YZ, VIEW }

    /** What the gizmo moves: an entity, or something an entity holds, such as an object standing on its map. */
    interface Target {
        /** Where it is in the world, turned and sized. */
        Matrix4f world(Matrix4f dest);

        /** Its own sizes, which a resize starts from. */
        Vector3f scale(Vector3f dest);

        /** A drag on it begins. */
        default void begin() {
        }

        void moveTo(Vector3f worldPosition);

        void turnTo(Quaternionf worldRotation);

        void scaleTo(Vector3f scale);

        /** Whether it stands upright on the ground whatever is done: it moves along it and turns only about the up axis. */
        default boolean upright() {
            return false;
        }

        /** Whether it has one size, not one along each axis. */
        default boolean oneScale() {
            return false;
        }
    }

    /** An entity as the gizmo moves it: its transform, whatever its parent. */
    static Target of(Entity entity) {
        return new Target() {
            @Override
            public Matrix4f world(Matrix4f dest) {
                return entity.transform().worldMatrix(dest);
            }

            @Override
            public Vector3f scale(Vector3f dest) {
                return dest.set(entity.transform().scale);
            }

            @Override
            public void moveTo(Vector3f worldPosition) {
                SceneDocument.setWorldPosition(entity, worldPosition);
            }

            @Override
            public void turnTo(Quaternionf worldRotation) {
                Quaternionf parentRotation = entity.parent() == null ? new Quaternionf()
                        : entity.parent().transform().worldMatrix(new Matrix4f()).getNormalizedRotation(new Quaternionf());
                entity.transform().rotation.set(parentRotation.conjugate().mul(worldRotation)).normalize();
            }

            @Override
            public void scaleTo(Vector3f scale) {
                entity.transform().scale.set(scale);
            }
        };
    }

    /** How long an axis is on screen, in GUI units. */
    private static final float LENGTH = 92f;
    private static final float PICK = 7f;

    private Handle hot = Handle.NONE;
    private Handle active = Handle.NONE;

    private final List<Target> targets = new ArrayList<>();
    private final List<Matrix4f> startWorld = new ArrayList<>();
    private final List<Vector3f> startScale = new ArrayList<>();
    private final Vector3f origin = new Vector3f();
    private final Vector3f dragAxis = new Vector3f();
    private final Vector3f dragNormal = new Vector3f();
    private final Vector3f startHit = new Vector3f();
    private float startT;
    private float startMouseX;
    private float startMouseY;
    private float lastAngle;
    private float totalAngle;
    private final Vector3f screenAxis = new Vector3f();
    private String readout;
    /** What is being moved stands upright ({@link Target#upright}) or has one size ({@link Target#oneScale}). */
    private boolean upright;
    private boolean oneScale;

    boolean dragging() {
        return active != Handle.NONE;
    }

    /** Whether the pointer is on a handle, so a click is the gizmo's and not a pick. */
    boolean hot() {
        return hot != Handle.NONE || active != Handle.NONE;
    }

    /**
     * Handles the pointer and draws the gizmo for the selection. Returns whether the gizmo has the pointer.
     *
     * @param snap whether moves, turns and sizes go in steps
     */
    boolean update(Ui ui, ViewContext view, SceneDocument doc, Mode mode, boolean local, boolean snap, float moveStep,
                   float angleStep, float scaleStep, boolean pointerFree) {
        List<Target> tops = new ArrayList<>();
        Target primary;
        MapPick pick = doc.mapPick();
        if (pick != null) {
            primary = pick.target(doc);
            if (primary != null) {
                tops.add(primary);
            }
        } else {
            for (Entity entity : doc.selectedTops()) {
                tops.add(of(entity));
            }
            Entity entity = doc.primary();
            primary = entity == null ? null : of(entity);
        }
        if (tops.isEmpty() || primary == null) {
            active = Handle.NONE;
            hot = Handle.NONE;
            return false;
        }
        upright = primary.upright();
        oneScale = primary.oneScale();
        Matrix4f primaryWorld = primary.world(new Matrix4f());
        Vector3f center = active == Handle.NONE ? primaryWorld.getTranslation(new Vector3f()) : new Vector3f(origin);
        Quaternionf frame = local ? primaryWorld.getNormalizedRotation(new Quaternionf()) : new Quaternionf();
        if (view.flat && !local) {
            frame.identity();
        }
        Vector3f[] axes = {frame.transform(new Vector3f(1, 0, 0)), frame.transform(new Vector3f(0, 1, 0)),
                frame.transform(new Vector3f(0, 0, 1))};
        if (mode == Mode.SCALE) {
            // Sizes are always along the entity's own axes.
            Quaternionf own = primaryWorld.getNormalizedRotation(new Quaternionf());
            axes = new Vector3f[]{own.transform(new Vector3f(1, 0, 0)), own.transform(new Vector3f(0, 1, 0)),
                    own.transform(new Vector3f(0, 0, 1))};
        }
        float worldLength = LENGTH * view.worldPerUnit(center);
        Vector3f c = new Vector3f();
        if (!view.project(center, c)) {
            return false;
        }
        Vector3f[] ends = new Vector3f[3];
        boolean[] usable = new boolean[3];
        for (int i = 0; i < 3; i++) {
            ends[i] = new Vector3f();
            usable[i] = view.project(new Vector3f(axes[i]).mul(worldLength).add(center), ends[i])
                    && Math.hypot(ends[i].x - c.x, ends[i].y - c.y) > LENGTH * 0.12f
                    && !(view.flat && i == 2)
                    && !(upright && mode == Mode.MOVE && i == 1)
                    && !(oneScale && mode == Mode.SCALE);
        }

        float mx = ui.mouseX();
        float my = ui.mouseY();
        boolean inView = view.rect.contains(mx, my);
        if (active == Handle.NONE) {
            hot = pointerFree && inView ? pick(ui, view, mode, center, axes, worldLength, c, ends, usable, mx, my) : Handle.NONE;
            if (hot != Handle.NONE && ui.input.isMousePressed(MouseButton.LEFT)) {
                begin(view, mode, tops, center, axes, c, ends, mx, my);
            }
        }
        if (active != Handle.NONE) {
            if (ui.input.isMouseDown(MouseButton.LEFT)) {
                drag(view, doc, mode, axes, c, mx, my, snap, moveStep, angleStep, scaleStep);
            } else {
                active = Handle.NONE;
                readout = null;
            }
        }
        draw(ui.gui, view, mode, center, axes, worldLength, c, ends, usable);
        if (readout != null && active != Handle.NONE) {
            float w = ui.width(readout, Theme.SMALL_SCALE) + 14f;
            org.zeronura.engine.ui.Rect box = new org.zeronura.engine.ui.Rect(mx + 16f, my + 14f, w, 22f);
            ui.gui.rounded(box, 4f, new Color(0f, 0f, 0f, 0.7f));
            ui.textIn(readout, box, box.x() + 7f, Theme.SMALL_SCALE, Theme.TEXT_BRIGHT);
        }
        return hot != Handle.NONE || active != Handle.NONE;
    }

    // --- Picking ---------------------------------------------------------------------------------------------------

    private Handle pick(Ui ui, ViewContext view, Mode mode, Vector3f center, Vector3f[] axes, float worldLength,
                        Vector3f c, Vector3f[] ends, boolean[] usable, float mx, float my) {
        Handle[] axisHandles = {Handle.X, Handle.Y, Handle.Z};
        switch (mode) {
            case MOVE -> {
                if (Math.hypot(mx - c.x, my - c.y) < 9f) {
                    return Handle.VIEW;
                }
                Handle[] planes = {Handle.YZ, Handle.XZ, Handle.XY};
                for (int i = 0; i < 3; i++) {
                    int a = (i + 1) % 3;
                    int b = (i + 2) % 3;
                    if (!usable[a] || !usable[b]) {
                        continue;
                    }
                    Vector3f[] quad = planeQuad(view, center, axes[a], axes[b], worldLength);
                    if (quad != null && inQuad(quad, mx, my)) {
                        return planes[i];
                    }
                }
                Handle best = Handle.NONE;
                float bestDistance = PICK;
                for (int i = 0; i < 3; i++) {
                    if (!usable[i]) {
                        continue;
                    }
                    float d = segmentDistance(mx, my, c.x, c.y, ends[i].x + (ends[i].x - c.x) * 0.12f,
                            ends[i].y + (ends[i].y - c.y) * 0.12f);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = axisHandles[i];
                    }
                }
                return best;
            }
            case SCALE -> {
                if (Math.abs(mx - c.x) < 8f && Math.abs(my - c.y) < 8f) {
                    return Handle.VIEW;
                }
                Handle best = Handle.NONE;
                float bestDistance = PICK;
                for (int i = 0; i < 3; i++) {
                    if (!usable[i]) {
                        continue;
                    }
                    float d = segmentDistance(mx, my, c.x, c.y, ends[i].x, ends[i].y);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = axisHandles[i];
                    }
                }
                return best;
            }
            case ROTATE -> {
                Handle best = Handle.NONE;
                float bestDistance = PICK;
                for (int i = 0; i < 3; i++) {
                    if (view.flat && i != 2 || upright && i != 1) {
                        continue;
                    }
                    List<Vector3f> ring = ring(view, center, axes, i, worldLength);
                    for (int k = 1; k < ring.size(); k++) {
                        Vector3f a = ring.get(k - 1);
                        Vector3f b = ring.get(k);
                        if (a == null || b == null) {
                            continue;
                        }
                        float d = segmentDistance(mx, my, a.x, a.y, b.x, b.y);
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = axisHandles[i];
                        }
                    }
                }
                if (!view.flat && !upright && best == Handle.NONE
                        && Math.abs(Math.hypot(mx - c.x, my - c.y) - LENGTH * 1.18f) < PICK) {
                    best = Handle.VIEW;
                }
                return best;
            }
        }
        return Handle.NONE;
    }

    private void begin(ViewContext view, Mode mode, List<Target> tops, Vector3f center, Vector3f[] axes, Vector3f c,
                       Vector3f[] ends, float mx, float my) {
        active = hot;
        targets.clear();
        startWorld.clear();
        startScale.clear();
        for (Target target : tops) {
            target.begin();
            targets.add(target);
            startWorld.add(target.world(new Matrix4f()));
            startScale.add(target.scale(new Vector3f()));
        }
        origin.set(center);
        startMouseX = mx;
        startMouseY = my;
        Vector3f rayOrigin = new Vector3f();
        Vector3f rayDirection = new Vector3f();
        view.ray(mx, my, rayOrigin, rayDirection);
        int axis = axisIndex(active);
        switch (mode) {
            case MOVE -> {
                if (axis >= 0) {
                    dragAxis.set(axes[axis]);
                    startT = closestOnAxis(origin, dragAxis, rayOrigin, rayDirection);
                } else {
                    planeNormal(active, axes, view, dragNormal);
                    intersectPlane(rayOrigin, rayDirection, origin, dragNormal, startHit);
                }
            }
            case ROTATE -> {
                dragAxis.set(axis >= 0 ? axes[axis] : new Vector3f(view.forward).negate());
                lastAngle = (float) Math.atan2(my - c.y, mx - c.x);
                totalAngle = 0f;
            }
            case SCALE -> {
                if (axis >= 0) {
                    screenAxis.set(ends[axis].x - c.x, ends[axis].y - c.y, 0f).normalize();
                }
            }
        }
    }

    private void drag(ViewContext view, SceneDocument doc, Mode mode, Vector3f[] axes, Vector3f c, float mx, float my,
                      boolean snap, float moveStep, float angleStep, float scaleStep) {
        Vector3f rayOrigin = new Vector3f();
        Vector3f rayDirection = new Vector3f();
        view.ray(mx, my, rayOrigin, rayDirection);
        int axis = axisIndex(active);
        switch (mode) {
            case MOVE -> {
                Vector3f delta = new Vector3f();
                if (axis >= 0) {
                    float t = closestOnAxis(origin, dragAxis, rayOrigin, rayDirection) - startT;
                    if (Float.isNaN(t)) {
                        return;
                    }
                    if (snap) {
                        t = Math.round(t / moveStep) * moveStep;
                    }
                    delta.set(dragAxis).mul(t);
                } else {
                    Vector3f hit = new Vector3f();
                    if (!intersectPlane(rayOrigin, rayDirection, origin, dragNormal, hit)) {
                        return;
                    }
                    delta.set(hit).sub(startHit);
                    if (snap) {
                        delta.set(Math.round(delta.x / moveStep) * moveStep, Math.round(delta.y / moveStep) * moveStep,
                                Math.round(delta.z / moveStep) * moveStep);
                    }
                }
                for (int i = 0; i < targets.size(); i++) {
                    Vector3f start = startWorld.get(i).getTranslation(new Vector3f());
                    targets.get(i).moveTo(start.add(delta));
                }
                readout = String.format(Locale.ROOT, "%.2f, %.2f, %.2f", delta.x, delta.y, delta.z);
            }
            case ROTATE -> {
                float angle = (float) Math.atan2(my - c.y, mx - c.x);
                float step = angle - lastAngle;
                if (step > Math.PI) {
                    step -= (float) (Math.PI * 2);
                } else if (step < -Math.PI) {
                    step += (float) (Math.PI * 2);
                }
                totalAngle += step;
                lastAngle = angle;
                // A turn that looks clockwise on screen is one way round an axis pointing at the viewer, the other way
                // round one pointing away.
                boolean toward = view.flat ? dragAxis.z > 0f : dragAxis.dot(new Vector3f(view.eye).sub(origin)) > 0f;
                float applied = toward ? -totalAngle : totalAngle;
                if (snap) {
                    float stepRadians = (float) Math.toRadians(angleStep);
                    applied = Math.round(applied / stepRadians) * stepRadians;
                }
                Quaternionf turn = new Quaternionf().rotateAxis(applied, dragAxis.x, dragAxis.y, dragAxis.z);
                for (int i = 0; i < targets.size(); i++) {
                    Target target = targets.get(i);
                    Matrix4f start = startWorld.get(i);
                    Quaternionf worldRotation = turn.mul(start.getNormalizedRotation(new Quaternionf()), new Quaternionf());
                    Vector3f position = start.getTranslation(new Vector3f()).sub(origin);
                    turn.transform(position).add(origin);
                    target.turnTo(worldRotation);
                    target.moveTo(position);
                }
                readout = String.format(Locale.ROOT, "%.1f°", Math.toDegrees(applied)).replace("°", " deg");
            }
            case SCALE -> {
                float travel;
                if (axis >= 0) {
                    travel = (mx - startMouseX) * screenAxis.x + (my - startMouseY) * screenAxis.y;
                } else {
                    travel = (mx - startMouseX) - (my - startMouseY);
                }
                float factor = Math.max(0.01f, 1f + travel / LENGTH);
                for (int i = 0; i < targets.size(); i++) {
                    Vector3f scale = new Vector3f(startScale.get(i));
                    if (axis >= 0) {
                        scale.setComponent(axis, scaled(scale.get(axis), factor, snap, scaleStep));
                    } else {
                        scale.set(scaled(scale.x, factor, snap, scaleStep), scaled(scale.y, factor, snap, scaleStep),
                                scaled(scale.z, factor, snap, scaleStep));
                    }
                    targets.get(i).scaleTo(scale);
                }
                readout = String.format(Locale.ROOT, "x%.2f", factor);
            }
        }
        doc.changed();
    }

    private static float scaled(float start, float factor, boolean snap, float step) {
        float value = start * factor;
        return snap ? Math.max(step, Math.round(value / step) * step) : value;
    }

    // --- Drawing ---------------------------------------------------------------------------------------------------

    private void draw(Gui gui, ViewContext view, Mode mode, Vector3f center, Vector3f[] axes, float worldLength,
                      Vector3f c, Vector3f[] ends, boolean[] usable) {
        Color[] colors = {Theme.AXIS_X, Theme.AXIS_Y, Theme.AXIS_Z};
        Handle[] axisHandles = {Handle.X, Handle.Y, Handle.Z};
        Handle shown = active != Handle.NONE ? active : hot;
        switch (mode) {
            case MOVE -> {
                Handle[] planes = {Handle.YZ, Handle.XZ, Handle.XY};
                for (int i = 0; i < 3; i++) {
                    int a = (i + 1) % 3;
                    int b = (i + 2) % 3;
                    if (!usable[a] || !usable[b]) {
                        continue;
                    }
                    Vector3f[] quad = planeQuad(view, center, axes[a], axes[b], worldLength);
                    if (quad == null) {
                        continue;
                    }
                    Color color = shown == planes[i] ? Theme.HIGHLIGHT.withAlpha(0.75f) : colors[i].withAlpha(0.4f);
                    gui.triangle(quad[0].x, quad[0].y, quad[1].x, quad[1].y, quad[2].x, quad[2].y, color);
                    gui.triangle(quad[0].x, quad[0].y, quad[2].x, quad[2].y, quad[3].x, quad[3].y, color);
                }
                for (int i = 0; i < 3; i++) {
                    if (!usable[i]) {
                        continue;
                    }
                    Color color = shown == axisHandles[i] ? Theme.HIGHLIGHT : colors[i];
                    gui.line(c.x, c.y, ends[i].x, ends[i].y, 2.6f, color);
                    float dx = ends[i].x - c.x;
                    float dy = ends[i].y - c.y;
                    float length = (float) Math.hypot(dx, dy);
                    float ux = dx / length;
                    float uy = dy / length;
                    float tipX = ends[i].x + ux * 15f;
                    float tipY = ends[i].y + uy * 15f;
                    gui.triangle(tipX, tipY, ends[i].x - uy * 6f, ends[i].y + ux * 6f, ends[i].x + uy * 6f, ends[i].y - ux * 6f,
                            color);
                }
                Icon.ring(gui, c.x, c.y, 6.5f, 2f, shown == Handle.VIEW ? Theme.HIGHLIGHT : Color.WHITE.withAlpha(0.9f));
            }
            case SCALE -> {
                for (int i = 0; i < 3; i++) {
                    if (!usable[i]) {
                        continue;
                    }
                    Color color = shown == axisHandles[i] ? Theme.HIGHLIGHT : colors[i];
                    gui.line(c.x, c.y, ends[i].x, ends[i].y, 2.6f, color);
                    gui.rect(ends[i].x - 5.5f, ends[i].y - 5.5f, 11f, 11f, color);
                }
                gui.rect(c.x - 6.5f, c.y - 6.5f, 13f, 13f, shown == Handle.VIEW ? Theme.HIGHLIGHT : Color.WHITE.withAlpha(0.9f));
            }
            case ROTATE -> {
                for (int i = 0; i < 3; i++) {
                    if (view.flat && i != 2 || upright && i != 1) {
                        continue;
                    }
                    Color color = shown == axisHandles[i] ? Theme.HIGHLIGHT : colors[i];
                    List<Vector3f> ring = ring(view, center, axes, i, worldLength);
                    List<Boolean> front = ringFront(view, center, axes, i, worldLength);
                    for (int k = 1; k < ring.size(); k++) {
                        Vector3f a = ring.get(k - 1);
                        Vector3f b = ring.get(k);
                        if (a == null || b == null) {
                            continue;
                        }
                        boolean near = view.flat || front.get(k);
                        gui.line(a.x, a.y, b.x, b.y, near ? 2.6f : 1.4f, near ? color : color.withAlpha(0.3f));
                    }
                }
                if (!view.flat && !upright) {
                    Icon.ring(gui, c.x, c.y, LENGTH * 1.18f, 1.8f,
                            shown == Handle.VIEW ? Theme.HIGHLIGHT : Color.WHITE.withAlpha(0.55f));
                }
                if (active != Handle.NONE) {
                    gui.circle(c.x, c.y, 4f, Theme.HIGHLIGHT);
                }
            }
        }
    }

    // --- Geometry --------------------------------------------------------------------------------------------------

    private static int axisIndex(Handle handle) {
        return switch (handle) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
            default -> -1;
        };
    }

    private static void planeNormal(Handle handle, Vector3f[] axes, ViewContext view, Vector3f dest) {
        switch (handle) {
            case YZ -> dest.set(axes[0]);
            case XZ -> dest.set(axes[1]);
            case XY -> dest.set(axes[2]);
            default -> dest.set(view.forward).negate();
        }
    }

    private static Vector3f[] planeQuad(ViewContext view, Vector3f center, Vector3f a, Vector3f b, float length) {
        float near = length * 0.22f;
        float far = length * 0.42f;
        Vector3f[] world = {
                new Vector3f(center).fma(near, a).fma(near, b), new Vector3f(center).fma(far, a).fma(near, b),
                new Vector3f(center).fma(far, a).fma(far, b), new Vector3f(center).fma(near, a).fma(far, b)};
        Vector3f[] screen = new Vector3f[4];
        for (int i = 0; i < 4; i++) {
            screen[i] = new Vector3f();
            if (!view.project(world[i], screen[i])) {
                return null;
            }
        }
        return screen;
    }

    private static List<Vector3f> ring(ViewContext view, Vector3f center, Vector3f[] axes, int axis, float radius) {
        Vector3f u = axes[(axis + 1) % 3];
        Vector3f v = axes[(axis + 2) % 3];
        List<Vector3f> points = new ArrayList<>();
        for (int k = 0; k <= 64; k++) {
            double t = Math.PI * 2 * k / 64;
            Vector3f p = new Vector3f(center).fma((float) Math.cos(t) * radius, u).fma((float) Math.sin(t) * radius, v);
            Vector3f s = new Vector3f();
            points.add(view.project(p, s) ? s : null);
        }
        return points;
    }

    private static List<Boolean> ringFront(ViewContext view, Vector3f center, Vector3f[] axes, int axis, float radius) {
        Vector3f u = axes[(axis + 1) % 3];
        Vector3f v = axes[(axis + 2) % 3];
        Vector3f toEye = new Vector3f(view.eye).sub(center);
        List<Boolean> front = new ArrayList<>();
        for (int k = 0; k <= 64; k++) {
            double t = Math.PI * 2 * (k - 0.5) / 64;
            Vector3f offset = new Vector3f(u).mul((float) Math.cos(t)).fma((float) Math.sin(t), v);
            front.add(offset.dot(toEye) >= -0.05f * radius);
        }
        return front;
    }

    /** How far along an axis line the point nearest a ray lies. */
    private static float closestOnAxis(Vector3f point, Vector3f axis, Vector3f rayOrigin, Vector3f rayDirection) {
        Vector3f w0 = new Vector3f(point).sub(rayOrigin);
        float b = axis.dot(rayDirection);
        float d = axis.dot(w0);
        float e = rayDirection.dot(w0);
        float denominator = 1f - b * b;
        if (Math.abs(denominator) < 1e-5f) {
            return Float.NaN;
        }
        return (b * e - d) / denominator;
    }

    private static boolean intersectPlane(Vector3f rayOrigin, Vector3f rayDirection, Vector3f point, Vector3f normal,
                                          Vector3f dest) {
        float facing = rayDirection.dot(normal);
        if (Math.abs(facing) < 1e-6f) {
            return false;
        }
        float t = new Vector3f(point).sub(rayOrigin).dot(normal) / facing;
        dest.set(rayDirection).mul(t).add(rayOrigin);
        return true;
    }

    static float segmentDistance(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float lengthSquared = dx * dx + dy * dy;
        float t = lengthSquared < 1e-6f ? 0f : Math.clamp(((px - ax) * dx + (py - ay) * dy) / lengthSquared, 0f, 1f);
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static boolean inQuad(Vector3f[] quad, float x, float y) {
        return inTriangle(quad[0], quad[1], quad[2], x, y) || inTriangle(quad[0], quad[2], quad[3], x, y);
    }

    private static boolean inTriangle(Vector3f a, Vector3f b, Vector3f c, float x, float y) {
        float d1 = (x - b.x) * (a.y - b.y) - (a.x - b.x) * (y - b.y);
        float d2 = (x - c.x) * (b.y - c.y) - (b.x - c.x) * (y - c.y);
        float d3 = (x - a.x) * (c.y - a.y) - (c.x - a.x) * (y - a.y);
        boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
        boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(negative && positive);
    }
}
