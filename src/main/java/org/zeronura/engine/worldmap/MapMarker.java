package org.zeronura.engine.worldmap;

/**
 * A named point on the map for the game to find: a healing spring, a shop, an NPC, a warp. Like zones, the
 * {@link #kind} is defined by the game.
 */
public final class MapMarker {

    public String kind;
    public String name;
    public float x;
    public float z;
    public float yaw;

    public MapMarker(String kind, String name, float x, float z, float yaw) {
        this.kind = kind;
        this.name = name;
        this.x = x;
        this.z = z;
        this.yaw = yaw;
    }

    public MapMarker copy() {
        return new MapMarker(kind, name, x, z, yaw);
    }
}
