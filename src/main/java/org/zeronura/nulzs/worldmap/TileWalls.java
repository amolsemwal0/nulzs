package org.zeronura.nulzs.worldmap;

import org.zeronura.nulzs.render.FoliageMaterial;

import java.util.List;

/**
 * Pictures on the walls of a {@linkplain WorldMap#isTiled tiled} map, so that a raised tile reads as the top of a
 * block of earth: a grass-edged side under grass, plain earth further down. Give them with
 * {@link MapTerrain#setTileWalls}.
 *
 * <p>A wall is the side of a tile standing over the one beside it, and it is drawn as cubes stacked down from the
 * tile's top, each {@link #pictureSize} tall and one picture wide: the top cube wears the picture {@link #top} picks
 * for that side, and every cube under it the one {@link #below} picks. A wall shorter than a cube shows the top part of
 * the top picture, so a half-height step still has its grass edge. A picture runs left to right as the wall is seen
 * from outside, its top row along the top of the cube, and repeats along the wall and down it — so the picture below
 * should tile, and continue the top one's bottom edge.
 *
 * @param materials   the pictures, which the pickers name by their place in this list
 * @param pictureSize world units one picture spans, across and down
 * @param top         the picture on the top cube of a tile's wall
 * @param below       the picture on the cubes under it
 */
public record TileWalls(List<FoliageMaterial> materials, float pictureSize, Picker top, Picker below) {

    /** Picks the picture for one side of one tile. */
    @FunctionalInterface
    public interface Picker {

        /**
         * @param cellX the tile's column
         * @param cellZ the tile's row
         * @param sideX the side the wall faces, as the step to the tile across it: one of sideX and sideZ is 1 or -1,
         *              the other 0
         * @param sideZ see sideX
         * @return the picture's place in {@link #materials}
         */
        int pick(int cellX, int cellZ, int sideX, int sideZ);
    }

    public TileWalls {
        materials = List.copyOf(materials);
        if (materials.isEmpty()) {
            throw new IllegalArgumentException("no wall pictures");
        }
        if (!(pictureSize > 0f)) {
            throw new IllegalArgumentException("picture size must be positive, got " + pictureSize);
        }
    }
}
