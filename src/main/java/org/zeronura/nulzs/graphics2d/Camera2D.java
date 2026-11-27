package org.zeronura.nulzs.graphics2d;

import org.joml.Matrix4f;
import org.joml.Vector2f;

/**
 * An orthographic camera for 2D worlds: position, zoom and rotation, with y pointing up.
 *
 * <h2>Units</h2>
 * By default one world unit is one screen pixel at zoom 1. Most games want their world in tiles or metres
 * instead, and want the same amount of world visible whatever the window size; {@link #setVisibleHeight}
 * does both — {@code setVisibleHeight(18)} shows 18 world units top to bottom on any display, with the
 * width following the aspect ratio.
 *
 * <p>Screen coordinates here are framebuffer pixels with y pointing <b>down</b>, as {@code Input} reports
 * the mouse, so {@code screenToWorld(input.mouseX(), input.mouseY())} is the whole of mouse picking.
 */
public class Camera2D {

    /** The world point at the centre of the screen. */
    public final Vector2f position = new Vector2f();

    private float viewportWidth;
    private float viewportHeight;
    private float zoom = 1f;
    private float rotation;
    private float visibleHeight;

    private boolean limited;
    private float limitMinX;
    private float limitMinY;
    private float limitMaxX;
    private float limitMaxY;

    public Camera2D(float viewportWidth, float viewportHeight) {
        setViewport(viewportWidth, viewportHeight);
    }

    public void setViewport(float width, float height) {
        this.viewportWidth = Math.max(1f, width);
        this.viewportHeight = Math.max(1f, height);
    }

    public float viewportWidth() {
        return viewportWidth;
    }

    public float viewportHeight() {
        return viewportHeight;
    }

    public float zoom() {
        return zoom;
    }

    /** Screen pixels per world unit. Ignored while a visible height is set. */
    public void setZoom(float zoom) {
        this.zoom = Math.max(1e-4f, zoom);
    }

    /** Fixes how many world units are visible vertically, whatever the window size. 0 turns it off. */
    public void setVisibleHeight(float worldUnits) {
        this.visibleHeight = Math.max(0f, worldUnits);
    }

    /** The zoom actually in force, including the visible-height setting. */
    public float effectiveZoom() {
        return visibleHeight > 0f ? viewportHeight / visibleHeight : zoom;
    }

    public float visibleWidth() {
        return viewportWidth / effectiveZoom();
    }

    public float visibleHeight() {
        return viewportHeight / effectiveZoom();
    }

    public float rotation() {
        return rotation;
    }

    /** Counter-clockwise, in radians. */
    public void setRotation(float radians) {
        this.rotation = radians;
    }

    /** World to clip space, for the sprite shader. */
    public Matrix4f combined(Matrix4f dest) {
        float halfWidth = visibleWidth() * 0.5f;
        float halfHeight = visibleHeight() * 0.5f;
        return dest.setOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -1f, 1f)
                .rotateZ(-rotation)
                .translate(-position.x, -position.y, 0f);
    }

    public Vector2f screenToWorld(float screenX, float screenY, Vector2f dest) {
        float z = effectiveZoom();
        float localX = (screenX - viewportWidth * 0.5f) / z;
        float localY = (viewportHeight * 0.5f - screenY) / z;
        float cos = (float) Math.cos(rotation);
        float sin = (float) Math.sin(rotation);
        return dest.set(position.x + localX * cos - localY * sin, position.y + localX * sin + localY * cos);
    }

    public Vector2f worldToScreen(float worldX, float worldY, Vector2f dest) {
        float dx = worldX - position.x;
        float dy = worldY - position.y;
        float cos = (float) Math.cos(-rotation);
        float sin = (float) Math.sin(-rotation);
        float localX = dx * cos - dy * sin;
        float localY = dx * sin + dy * cos;
        float z = effectiveZoom();
        return dest.set(localX * z + viewportWidth * 0.5f, viewportHeight * 0.5f - localY * z);
    }

    /**
     * Eases toward a target. {@code smoothing} is how quickly the gap closes (per second); 0 snaps.
     * Frame-rate independent, unlike lerping by a constant fraction each frame.
     */
    public void follow(float targetX, float targetY, float smoothing, float dt) {
        float t = smoothing <= 0f ? 1f : 1f - (float) Math.exp(-smoothing * dt);
        position.x += (targetX - position.x) * t;
        position.y += (targetY - position.y) * t;
        applyLimits();
    }

    /** Keeps the view inside a world rectangle — the level's bounds — so the camera never shows the void. */
    public void setLimits(float minX, float minY, float maxX, float maxY) {
        limited = true;
        limitMinX = minX;
        limitMinY = minY;
        limitMaxX = maxX;
        limitMaxY = maxY;
        applyLimits();
    }

    public void clearLimits() {
        limited = false;
    }

    public void applyLimits() {
        if (!limited) {
            return;
        }
        float halfWidth = visibleWidth() * 0.5f;
        float halfHeight = visibleHeight() * 0.5f;
        position.x = clampAxis(position.x, limitMinX, limitMaxX, halfWidth);
        position.y = clampAxis(position.y, limitMinY, limitMaxY, halfHeight);
    }

    private static float clampAxis(float value, float min, float max, float halfExtent) {
        if (max - min <= 2f * halfExtent) {
            return (min + max) * 0.5f;
        }
        return Math.max(min + halfExtent, Math.min(max - halfExtent, value));
    }

    /** Half the axis-aligned extent of the view, rotation included. */
    private float boundsHalfWidth() {
        float cos = Math.abs((float) Math.cos(rotation));
        float sin = Math.abs((float) Math.sin(rotation));
        return visibleWidth() * 0.5f * cos + visibleHeight() * 0.5f * sin;
    }

    private float boundsHalfHeight() {
        float cos = Math.abs((float) Math.cos(rotation));
        float sin = Math.abs((float) Math.sin(rotation));
        return visibleWidth() * 0.5f * sin + visibleHeight() * 0.5f * cos;
    }

    public float minVisibleX() {
        return position.x - boundsHalfWidth();
    }

    public float maxVisibleX() {
        return position.x + boundsHalfWidth();
    }

    public float minVisibleY() {
        return position.y - boundsHalfHeight();
    }

    public float maxVisibleY() {
        return position.y + boundsHalfHeight();
    }

    /** Whether a world rectangle overlaps the view — for skipping off-screen work. */
    public boolean isVisible(float minX, float minY, float maxX, float maxY) {
        return maxX >= minVisibleX() && minX <= maxVisibleX() && maxY >= minVisibleY() && minY <= maxVisibleY();
    }
}
