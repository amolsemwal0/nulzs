package org.zeronura.nulzs.editor;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.worldmap.TerrainRay;
import org.zeronura.nulzs.worldmap.WorldMap;

/**
 * An editor's camera on a world laid out in tiles — the tile map editor's on the map, a game's room editor's on a room —
 * seen as the game shows it: from above at the game's slant, north up until it is turned a quarter at a time, about a
 * point on the ground the editor moves around, from as close as the game's camera to far enough out to take in a town.
 * Straight down on request, for laying things out square.
 *
 * <p>Turns, the switch to straight down and the climb over a step in the ground all ease rather than jump, so the view
 * never leaves the editor unsure where it is.
 */
public final class TileWorldView {

    /** As near as the view comes, in world units from the point it looks at. */
    static final float MIN_DISTANCE = 6f;
    /** As far as it goes: a town and the land round it. Further out the flat map serves better. */
    static final float MAX_DISTANCE = 140f;
    /** The game's camera hangs this far over the ground it looks at, as a follow camera does over a player's feet. */
    private static final float PIVOT_HEIGHT = 1.5f;
    private static final float TOP_DOWN_PITCH = (float) Math.toRadians(-89.0);
    /** Looking north, toward -z, before any turn. */
    private static final float NORTH = (float) (-Math.PI / 2);
    private static final float EASE_RATE = 12f;

    private final TileLook.View game;
    private float focusX;
    private float focusZ;
    private float distance;
    /** Quarter turns from north, to the right; the eased yaw follows it. */
    private int turns;
    private boolean topDown;
    private float yaw = NORTH;
    private float pitch;
    private float pivotY = Float.NaN;
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f clip = new Vector4f();

    /** A view through the game's camera: its slant, its field of view, and how far it stands from what it looks at. */
    public TileWorldView(TileLook.View game) {
        this.game = game;
        this.pitch = game.pitch();
        this.distance = Math.clamp(game.distance(), MIN_DISTANCE, MAX_DISTANCE);
    }

    public float focusX() {
        return focusX;
    }

    public float focusZ() {
        return focusZ;
    }

    public float distance() {
        return distance;
    }

    public boolean topDown() {
        return topDown;
    }

    /** Looks at a point on the ground from where the view is now, at once. */
    public void lookAt(float x, float z) {
        focusX = x;
        focusZ = z;
    }

    /** Moves the point looked at, clamped to the map. */
    public void move(WorldMap map, float dx, float dz) {
        move(dx, dz, map.worldWidth(), map.worldDepth());
    }

    /** Moves the point looked at, kept from 0 to {@code width} across and 0 to {@code depth} down. */
    public void move(float dx, float dz, float width, float depth) {
        focusX = Math.clamp(focusX + dx, 0f, width);
        focusZ = Math.clamp(focusZ + dz, 0f, depth);
    }

    public void setDistance(float newDistance) {
        distance = Math.clamp(newDistance, MIN_DISTANCE, MAX_DISTANCE);
    }

    /** Back to the game's own distance and slant. */
    public void resetToGame() {
        distance = Math.clamp(game.distance(), MIN_DISTANCE, MAX_DISTANCE);
        topDown = false;
    }

    /** Turns the view a quarter: +1 to the right, -1 to the left. What was on that side is up the screen after. */
    public void turn(int quarters) {
        turns += quarters;
    }

    /** Quarter turns to the right of north, as asked for: where the view is going, whatever it shows mid-turn. */
    public int turns() {
        return turns;
    }

    public void setTopDown(boolean straightDown) {
        topDown = straightDown;
    }

    /** Up the screen, along the ground: x and z of a unit vector. */
    public float upX() {
        return (float) Math.cos(yaw);
    }

    public float upZ() {
        return (float) Math.sin(yaw);
    }

    /** Right along the screen, along the ground. */
    public float rightX() {
        return -(float) Math.sin(yaw);
    }

    public float rightZ() {
        return (float) Math.cos(yaw);
    }

    /** Eases toward the view asked for and puts the camera there. */
    public void apply(Camera camera, WorldMap map, float dt) {
        apply(camera, map.heightAt(focusX, focusZ), dt);
    }

    /** Eases toward the view asked for and puts the camera there, over ground this high at the point looked at. */
    public void apply(Camera camera, float groundHeight, float dt) {
        float ease = 1f - (float) Math.exp(-EASE_RATE * dt);
        float wantedYaw = NORTH + turns * (float) (Math.PI / 2);
        yaw += (wantedYaw - yaw) * ease;
        pitch += ((topDown ? TOP_DOWN_PITCH : game.pitch()) - pitch) * ease;
        float ground = groundHeight + PIVOT_HEIGHT;
        pivotY = Float.isNaN(pivotY) ? ground : pivotY + (ground - pivotY) * ease;
        camera.setFovDegrees(game.fovDegrees());
        camera.setOrientation(yaw, pitch);
        Vector3f forward = camera.getForward(new Vector3f());
        camera.setPosition(new Vector3f(focusX, pivotY, focusZ).sub(forward.mul(distance)));
        camera.getProjectionMatrix().mul(camera.getViewMatrix(), viewProjection);
    }

    /**
     * Where the ray under a screen point meets the level of the ground the view looks at, as {x, z}; null when it
     * runs above the horizon. For dragging and zooming about the cursor, which want a plane, not the tiles.
     */
    public float[] onGroundLevel(Camera camera, float screenX, float screenY, float screenWidth, float screenHeight) {
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        TerrainRay.screenRay(camera, screenX, screenY, screenWidth, screenHeight, origin, direction);
        float level = (Float.isNaN(pivotY) ? 0f : pivotY) - PIVOT_HEIGHT;
        if (direction.y > -1e-4f) {
            return null;
        }
        float t = (level - origin.y) / direction.y;
        return new float[]{origin.x + direction.x * t, origin.z + direction.z * t};
    }

    /**
     * A world point on the screen as {@link #apply} last placed the camera, in the coordinates of a screen of the size
     * given, y down; false when it is behind the camera.
     */
    public boolean toScreen(float x, float y, float z, float width, float height, Vector2f out) {
        viewProjection.transform(clip.set(x, y, z, 1f));
        if (clip.w <= 0.01f) {
            return false;
        }
        out.set((clip.x / clip.w + 1f) * 0.5f * width, (1f - clip.y / clip.w) * 0.5f * height);
        return true;
    }
}
