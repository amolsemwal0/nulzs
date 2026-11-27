package org.zeronura.nulzs.worldmap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.StaticMesh;
import org.zeronura.nulzs.render.TexturedMesh;

import java.util.List;

/**
 * One textured way a prefab can look: its surfaces as UV-mapped meshes, each with the material it is drawn in, plus
 * whatever is left in flat colours. A prefab given looks is drawn as one of them in place of its own mesh, which then
 * serves only where a single flat mesh is wanted (thumbnails, the editor's placement ghost).
 *
 * <p>Looks of one prefab usually share their meshes and differ only in materials — a house's walls are the same walls
 * in brick or in siding — so a street of houses in a dozen styles is still one instanced draw per material.
 *
 * <p>A look can have parts that swing ({@link Swing}): a building's doors, drawn shut until something opens them
 * ({@link MapTerrain#setSwing}).
 *
 * @param pieces the textured surfaces
 * @param plain  the parts drawn in flat colours, or null for none
 * @param swings the parts that swing on hinges, apart from the rest; empty for none
 */
public record PrefabLook(List<Piece> pieces, StaticMesh plain, List<Swing> swings) {

    /** A UV-mapped surface and the material it wears. */
    public record Piece(TexturedMesh mesh, FoliageMaterial material) {
    }

    /**
     * A part that swings about an upright hinge — a door — through the turns its clip was made with. Its surfaces are
     * built where it stands shut, in the look's space, as the rest of the look's are.
     *
     * @param pieces its textured surfaces
     * @param plain  its parts in flat colours, or null for none
     * @param hinge  a point on the hinge, in the look's space
     * @param times  seconds into the swing, rising from 0
     * @param angles how far it has turned about +y at each of {@code times}, in radians, from shut
     */
    public record Swing(List<Piece> pieces, StaticMesh plain, Vector3f hinge, float[] times, float[] angles) {

        public Swing {
            pieces = List.copyOf(pieces);
            if (times.length != angles.length || times.length == 0) {
                throw new IllegalArgumentException("a swing needs a turn for each time, and at least one");
            }
        }

        /** How long the swing takes, in seconds. */
        public float duration() {
            return times[times.length - 1];
        }

        /** How far it has turned {@code seconds} into the swing: shut before it, all the way after it. */
        public float angleAt(float seconds) {
            if (seconds <= times[0]) {
                return angles[0];
            }
            for (int k = 1; k < times.length; k++) {
                if (seconds < times[k]) {
                    float t = (seconds - times[k - 1]) / Math.max(1e-6f, times[k] - times[k - 1]);
                    return angles[k - 1] + (angles[k] - angles[k - 1]) * t;
                }
            }
            return angles[angles.length - 1];
        }

        /** Where it stands {@code seconds} into the swing, for something standing at {@code placed}: into dest. */
        public Matrix4f pose(Matrix4f placed, float seconds, Matrix4f dest) {
            return dest.set(placed).translate(hinge).rotateY(angleAt(seconds)).translate(-hinge.x, -hinge.y, -hinge.z);
        }
    }

    public PrefabLook {
        pieces = List.copyOf(pieces);
        swings = swings == null ? List.of() : List.copyOf(swings);
    }

    /** A look with nothing that swings. */
    public PrefabLook(List<Piece> pieces, StaticMesh plain) {
        this(pieces, plain, List.of());
    }

    /** How long the slowest of its swings takes, in seconds; 0 for a look with none. */
    public float swingDuration() {
        float longest = 0f;
        for (Swing swing : swings) {
            longest = Math.max(longest, swing.duration());
        }
        return longest;
    }

    /**
     * Which of a prefab's looks an object wears: picked from where it stands, so the same building keeps its look from
     * one visit to the next and in every view of the map, with nothing stored in the map.
     */
    public static int pick(int lookCount, float x, float z) {
        int hash = Math.round(x * 2f) * 73856093 ^ Math.round(z * 2f) * 19349663;
        hash ^= hash >>> 13;
        hash *= 0x5bd1e995;
        hash ^= hash >>> 15;
        return Math.floorMod(hash, lookCount);
    }
}
