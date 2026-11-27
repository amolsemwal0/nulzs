package org.zeronura.nulzs.editor;

import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.worldmap.MapMaterial;
import org.zeronura.nulzs.worldmap.MapPicture;
import org.zeronura.nulzs.worldmap.WorldMap;

import java.util.List;

/**
 * A picture of the whole map from above, for finding your way around a big one: ground in its colours, shaded by
 * slope, and water blue by depth. Redrawn a moment after edits, not on every brush stroke.
 */
final class MapOverview {

    static final int SIZE = 256;
    private static final float REFRESH_SECONDS = 0.75f;

    private Texture texture;
    private boolean stale = true;
    private float sinceRefresh = REFRESH_SECONDS;

    void invalidate() {
        stale = true;
    }

    void update(float dt, WorldMap map, List<MapMaterial> palette) {
        sinceRefresh += dt;
        if (stale && sinceRefresh >= REFRESH_SECONDS) {
            if (texture != null) {
                texture.delete();
            }
            texture = Texture.fromPixels(MapPicture.pixels(map, palette, SIZE), SIZE, SIZE);
            stale = false;
            sinceRefresh = 0f;
        }
    }

    /** The picture, or null before the first {@link #update}. */
    Texture texture() {
        return texture;
    }

    void delete() {
        if (texture != null) {
            texture.delete();
            texture = null;
        }
        stale = true;
    }
}
