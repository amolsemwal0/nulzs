package org.zeronura.engine.worldmap;

import org.joml.Vector3f;
import org.zeronura.engine.graphics2d.Color;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * A picture of a whole map from above: ground in its materials' colours, shaded by slope, and water blue by depth.
 * The editor's overview and a game's in-world map both draw it.
 */
public final class MapPicture {

    private MapPicture() {
    }

    /**
     * RGBA pixels, {@code size} by {@code size}, top row first, with -z at the top and -x at the left. GL-free, so it
     * can be made off the render thread or in a test.
     */
    public static ByteBuffer pixels(WorldMap map, List<MapMaterial> palette, int size) {
        ByteBuffer out = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
        Vector3f normal = new Vector3f();
        Vector3f light = new Vector3f(-0.5f, 0.8f, -0.35f).normalize();
        float water = map.waterLevel();
        for (int py = 0; py < size; py++) {
            float z = (py + 0.5f) / size * map.worldDepth();
            for (int px = 0; px < size; px++) {
                float x = (px + 0.5f) / size * map.worldWidth();
                float height = map.heightAt(x, z);
                float r;
                float g;
                float b;
                if (map.hasWater() && height < water) {
                    float depth = Math.min(1f, (water - height) / 25f);
                    r = 0.36f + (0.08f - 0.36f) * depth;
                    g = 0.66f + (0.24f - 0.66f) * depth;
                    b = 0.82f + (0.46f - 0.82f) * depth;
                } else {
                    Color base = palette.get(Math.min(map.materialAt(x, z), palette.size() - 1)).color();
                    float shade = 0.55f + 0.6f * Math.max(0f, map.normalAt(x, z, normal).dot(light));
                    r = base.r() * shade;
                    g = base.g() * shade;
                    b = base.b() * shade;
                }
                out.put(toByte(r)).put(toByte(g)).put(toByte(b)).put((byte) 255);
            }
        }
        out.flip();
        return out;
    }

    private static byte toByte(float value) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }
}
