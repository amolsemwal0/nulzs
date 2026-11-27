package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * How a map's water is drawn when it has art of its own: four tiling pictures, moved by the shader, and the map's
 * flow (a {@link org.zeronura.engine.worldmap.WaterFlow} picture) saying where they go.
 *
 * <ul>
 *   <li>Running rivers wear {@link #RUNNING}, flat, its picture's x laid along their current and slid down it. The map is cut
 *   into cells a couple of metres a side, each turning the picture to its own flow (to one of a set of ways and
 *   speeds, so neighbouring cells that run alike lay exactly the same picture and it never doubles), and each point
 *   mixes its four nearest cells, with the picture's contrast kept where cells running differently are mixed. Still
 *   water, a lake, wears {@link #RIVER} itself, drifting.</li>
 *   <li>Falls pour {@link #RUNNING} down their face, its x straight down, from a bright lip; where one comes down into
 *   water, white water boils up its foot and out over the water below, settling into bubbles (the flow texture's
 *   second picture, {@link org.zeronura.engine.worldmap.WaterFlow#falls}).</li>
 *   <li>The sea wears {@link #OCEAN} in two layers drifting different ways, lighter in the shallows.</li>
 *   <li>Waves made of {@link #FOAM} (white where it is white) roll in to every shore in bands, and a frill of it
 *   laps at the water's edge.</li>
 * </ul>
 *
 * @param albedo       array texture, a layer each in the order {@link #RIVER}, {@link #OCEAN}, {@link #FOAM},
 *                     {@link #RUNNING}
 * @param normalMap    the matching relief of {@link #RIVER}, {@link #OCEAN} and {@link #FOAM}, or null; {@link #RUNNING}
 *                     is drawn flat, and needs no layer here
 * @param flow         the map's flow, one texel a cell, laid over {@code originX/Z} to {@code + sizeX/Z}, with its falls
 *                     picture under it ({@link org.zeronura.engine.worldmap.WaterFlow#texture})
 * @param tiles        how many metres one picture spans: river (still), ocean, foam, running
 * @param flowSpeed    how fast a river runs at full flow, metres a second
 * @param shallowColor what the sea leans toward in the shallows by the shore
 * @param waveLength   how far apart the waves come in, metres
 * @param waveReach    how far out from the shore waves are seen, metres
 */
public record WaterLook(Texture albedo, Texture normalMap, Texture flow, float originX, float originZ, float sizeX,
                        float sizeZ, Vector4f tiles, float flowSpeed, Vector3f shallowColor, float waveLength,
                        float waveReach) {

    /** How far from land the flow picture's distance reaches before it stops counting, in metres. */
    public static final float SHORE_RANGE = 32f;

    public static final int RIVER = 0;
    public static final int OCEAN = 1;
    public static final int FOAM = 2;
    /** Running water's picture: a river's current and a fall's face, streaks running along the picture's x. */
    public static final int RUNNING = 3;
}
