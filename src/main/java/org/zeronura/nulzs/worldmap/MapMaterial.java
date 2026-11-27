package org.zeronura.nulzs.worldmap;

import org.zeronura.nulzs.graphics2d.Color;

/**
 * A ground type that can be painted onto a map. A map stores only the index into the game's palette, so
 * a game can retune its colours without re-saving its maps.
 */
public record MapMaterial(String name, Color color) {
}
