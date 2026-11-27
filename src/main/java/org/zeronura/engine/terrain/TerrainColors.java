package org.zeronura.engine.terrain;

import org.zeronura.engine.graphics2d.Color;

/**
 * Colours terrain per vertex from where the vertex is and how steep the ground is there.
 *
 * <p>Called from worker threads while chunks build; keep it a pure function.
 */
@FunctionalInterface
public interface TerrainColors {

    /**
     * @param slope 0 on flat ground, approaching 1 on a vertical cliff
     */
    Color colorAt(float x, float height, float z, float slope);

    /** Sand at the shoreline, grass that darkens with height, rock on steep slopes, snow on peaks. */
    static TerrainColors grassland(float waterLevel, float snowLine) {
        Color sand = Color.rgb(0xD8C690);
        Color lowGrass = Color.rgb(0x6FA24A);
        Color highGrass = Color.rgb(0x46703A);
        Color rock = Color.rgb(0x7D766E);
        Color darkRock = Color.rgb(0x5B5650);
        Color snow = Color.rgb(0xF2F4F7);
        return (x, height, z, slope) -> {
            float shore = Float.isNaN(waterLevel) ? Float.NEGATIVE_INFINITY : waterLevel;
            // A little per-vertex variation so large flat areas do not read as a single colour.
            int hash = (int) (Math.floor(x) * 73856093) ^ (int) (Math.floor(z) * 19349663);
            float jitter = ((hash & 0xFF) / 255f - 0.5f) * 0.06f;

            Color base;
            if (height < shore + 1.5f) {
                base = sand;
            } else {
                float t = Math.max(0f, Math.min(1f, (height - shore) / 45f));
                base = lowGrass.lerp(highGrass, t);
                if (height > snowLine) {
                    base = base.lerp(snow, Math.min(1f, (height - snowLine) / 8f));
                }
            }
            float rockiness = Noise.smoothstep(0.28f, 0.45f, slope);
            if (rockiness > 0f) {
                base = base.lerp(rock.lerp(darkRock, Math.min(1f, slope)), rockiness);
            }
            return base.scaled(1f + jitter);
        };
    }
}
