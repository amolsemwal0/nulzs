package org.zeronura.engine.graphics3d;

import org.joml.Vector3f;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.worldmap.Brushes;
import org.zeronura.engine.worldmap.WorldMap;

import java.util.List;

/**
 * Ground an editor's Terrain Tool can sculpt and paint: a heightfield ({@link WorldMap}) with its corner somewhere in
 * the world, each of its vertices painted with one of a list of grounds. A {@link Terrain3D} is one, and so is the map
 * a {@link WorldMap3D} shows.
 */
public interface EditableGround {

    /** A ground it can be painted with; its place in {@link #paints} is the number the map's paint stores. */
    record Paint(String name, Color color) {
    }

    /** The heightfield. Its x and z run from 0 at its {@linkplain #origin corner}. */
    WorldMap map();

    /** Where the map's corner (its x = 0, z = 0) is in the world. */
    Vector3f origin(Vector3f dest);

    /** Asks for the ground a brush touched to be built again. */
    void changed(Brushes.Area area);

    /** Asks for all of it to be built again. */
    void changedAll();

    /** The grounds it can be painted with, by number. */
    List<Paint> paints();

    /**
     * Null when its heights can be brushed; otherwise why they can't — a map whose heights are laid from its areas
     * each time it loads, say, where brushing them would come to nothing.
     */
    default String heightsLocked() {
        return null;
    }

    /** Before a stroke starts changing it. */
    default void beginStroke() {
    }

    /** After a stroke has changed it: what lies on the ground is placed again, and the change kept to undo. */
    default void endStroke() {
    }
}
