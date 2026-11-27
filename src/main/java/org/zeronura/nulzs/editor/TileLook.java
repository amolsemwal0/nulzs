package org.zeronura.nulzs.editor;

import org.zeronura.nulzs.graphics3d.World3D;
import org.zeronura.nulzs.render.Renderer;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.worldmap.MapTerrain;

/**
 * How a game's tiled world looks, for the {@link TileMapEditorScene}'s world view: the textures its ground, walls,
 * paths and water wear in the game, the decorations it grows from the map, and pictures of its ground types for the
 * editor's palette. Without one the world view draws the map in its palette's flat colours.
 */
@FunctionalInterface
public interface TileLook {

    /**
     * Dresses the editor's terrain as the game dresses the map, and returns the dressing, which the editor keeps up to
     * date with its edits and takes off when it closes. Called on the GL thread.
     */
    Dressing dress(MapTerrain terrain, World3D world, Renderer renderer);

    /** How the game's camera looks at the map, which the world view starts as; see {@link View}. */
    default View view() {
        return View.DEFAULT;
    }

    /**
     * How the game looks at a tiled map. The world view starts as this camera and keeps its slant while it is turned
     * a quarter at a time and zoomed in and out.
     *
     * @param pitch      radians, negative looking down
     * @param fovDegrees the vertical field of view
     * @param distance   how far back the camera stands from the point it looks at, in world units
     */
    record View(float pitch, float fovDegrees, float distance) {

        /** Down at about 52 degrees, narrow-angled, from 19 m: a classic top-down game's view of its tiles. */
        public static final View DEFAULT = new View(-0.9f, 38f, 19f);
    }

    /** One map, dressed: what it has to hear about the editor's changes, and what it can show of itself. */
    interface Dressing {

        /**
         * A small picture of a ground type as it is laid in the game — an overlay's as it is laid over the ground — or
         * null to show its flat colour. The dressing owns it.
         */
        Texture swatch(int material);

        /** The ground or overlays of the tiles in a rectangle (cells, inclusive) changed: redraw what follows them. */
        void tilesChanged(int x0, int z0, int x1, int z1);

        /** The zones or markers changed: lay again whatever the game grows from them, the grass of a wild-grass zone. */
        void contentChanged();

        /** Shows the overlays' own art, or hides it to show the ground under them — the overlays' layer hidden. */
        void setOverlaysShown(boolean shown);

        /** Takes the dressing off the renderer and frees what it made. */
        void remove();
    }
}
