package org.zeronura.nulzs.worldmap;

import org.joml.Vector3f;
import org.zeronura.nulzs.terrain.Noise;
import org.zeronura.nulzs.terrain.NoiseHeights;

/**
 * Generates a starting island — rolling lowlands, a mountain range, beaches, and sea at the edges — for a
 * map editor's "new map", or as a base a game's own generator dresses further.
 */
public final class IslandGenerator {

    /** Which palette index to paint each kind of ground with. */
    public record Materials(int grass, int sand, int rock, int snow, int dirt) {
    }

    private IslandGenerator() {
    }

    public static WorldMap generate(String name, int size, float cellSize, long seed, float waterLevel, Materials materials) {
        WorldMap map = new WorldMap(name, size, size, cellSize);
        map.setWater(true);
        map.setWaterLevel(waterLevel);

        NoiseHeights land = new NoiseHeights(seed, 0.006f, waterLevel + 7f, 9f, 55f);
        Noise shape = new Noise(seed * 31 + 7);
        Noise patches = new Noise(seed * 17 + 3);
        float half = map.worldWidth() / 2f;

        for (int j = 0; j <= size; j++) {
            for (int i = 0; i <= size; i++) {
                float x = i * cellSize;
                float z = j * cellSize;
                float dx = (x - half) / half;
                float dz = (z - half) / half;
                // Distance from the centre, wobbled by noise so the coastline is not a circle.
                float distance = (float) Math.sqrt(dx * dx + dz * dz) + shape.fbm(x * 0.008f, z * 0.008f, 3, 2f, 0.5f) * 0.25f;
                float island = 1f - Noise.smoothstep(0.55f, 0.92f, distance);
                float height = land.heightAt(x - half, z - half);
                float seabed = waterLevel - 12f;
                map.setVertexHeight(i, j, seabed + (Math.max(height, waterLevel + 1.5f) - seabed) * island);
            }
        }

        Vector3f normal = new Vector3f();
        for (int j = 0; j <= size; j++) {
            for (int i = 0; i <= size; i++) {
                map.setVertexMaterial(i, j, groundMaterial(map, i, j, materials, patches, normal));
            }
        }

        placeSpawn(map, waterLevel);
        return map;
    }

    /**
     * The ground a vertex gets from its shape: sand along the shore, rock on steep slopes, snow high up, dirt in noisy
     * patches and grass elsewhere. Shared by generation and the editor's land tools, so painted land matches generated
     * land.
     *
     * @param normal scratch space
     */
    public static int groundMaterial(WorldMap map, int i, int j, Materials materials, Noise patches, Vector3f normal) {
        float x = i * map.cellSize();
        float z = j * map.cellSize();
        float height = map.vertexHeight(i, j);
        float waterLevel = map.waterLevel();
        float slope = 1f - map.normalAt(x, z, normal).y;
        if (height < waterLevel + 1.3f) {
            return materials.sand();
        }
        if (slope > 0.32f) {
            return materials.rock();
        }
        if (height > waterLevel + 45f) {
            return materials.snow();
        }
        if (patches.fbm(x * 0.03f, z * 0.03f, 3, 2f, 0.5f) > 0.28f) {
            return materials.dirt();
        }
        return materials.grass();
    }

    /** Spirals out from the centre to flat, dry ground. */
    private static void placeSpawn(WorldMap map, float waterLevel) {
        Vector3f normal = new Vector3f();
        float cx = map.worldWidth() / 2f;
        float cz = map.worldDepth() / 2f;
        for (float radius = 0f; radius < map.worldWidth() / 2f; radius += 4f) {
            for (int step = 0; step < 16; step++) {
                double angle = step * Math.PI / 8.0;
                float x = cx + radius * (float) Math.cos(angle);
                float z = cz + radius * (float) Math.sin(angle);
                float h = map.heightAt(x, z);
                if (h > waterLevel + 2f && h < waterLevel + 20f && map.normalAt(x, z, normal).y > 0.95f) {
                    map.setSpawn(x, z, 0f);
                    return;
                }
            }
        }
    }
}
