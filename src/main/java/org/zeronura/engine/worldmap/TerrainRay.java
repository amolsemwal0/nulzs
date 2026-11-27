package org.zeronura.engine.worldmap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.render.Camera;

/** Mouse picking on a map: turns a screen point into a ray and finds where the ray meets the ground. */
public final class TerrainRay {

    private TerrainRay() {
    }

    /** The world-space ray under a screen pixel (y down), written into {@code origin} and {@code direction}. */
    public static void screenRay(Camera camera, float screenX, float screenY, float screenWidth, float screenHeight,
                                 Vector3f origin, Vector3f direction) {
        screenRay(camera.getProjectionMatrix(), camera.getViewMatrix(), screenX, screenY, screenWidth, screenHeight,
                origin, direction);
    }

    public static void screenRay(Matrix4f projection, Matrix4f view, float screenX, float screenY,
                                 float screenWidth, float screenHeight, Vector3f origin, Vector3f direction) {
        Matrix4f inverse = new Matrix4f(projection).mul(view).invert();
        float ndcX = screenX / screenWidth * 2f - 1f;
        float ndcY = 1f - screenY / screenHeight * 2f;
        Vector3f near = inverse.transformProject(ndcX, ndcY, -1f, new Vector3f());
        Vector3f far = inverse.transformProject(ndcX, ndcY, 1f, new Vector3f());
        origin.set(near);
        direction.set(far).sub(near).normalize();
    }

    /**
     * Where a ray first goes below the map surface, within the map's bounds.
     *
     * @return true and the point in {@code hit}, or false if the ray misses the map
     */
    public static boolean intersect(WorldMap map, Vector3f origin, Vector3f direction, float maxDistance, Vector3f hit) {
        float step = map.cellSize() * 0.5f;
        float previous = 0f;
        boolean previousAbove = true;
        for (float t = 0f; t <= maxDistance; t += step) {
            float x = origin.x + direction.x * t;
            float y = origin.y + direction.y * t;
            float z = origin.z + direction.z * t;
            boolean inside = map.contains(x, z);
            boolean above = !inside || y > map.heightAt(x, z);
            if (inside && !above && previousAbove && t > 0f) {
                float low = previous;
                float high = t;
                for (int i = 0; i < 12; i++) {
                    float mid = (low + high) * 0.5f;
                    float mx = origin.x + direction.x * mid;
                    float mz = origin.z + direction.z * mid;
                    if (origin.y + direction.y * mid > map.heightAt(mx, mz)) {
                        low = mid;
                    } else {
                        high = mid;
                    }
                }
                hit.set(origin).add(direction.x * high, direction.y * high, direction.z * high);
                return true;
            }
            previous = t;
            previousAbove = above;
        }
        return false;
    }
}
