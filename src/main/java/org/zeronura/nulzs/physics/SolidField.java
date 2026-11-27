package org.zeronura.nulzs.physics;

/**
 * The terrain, as physics needs to see it: a function from a world point to "is there rock here".
 *
 * <p>This is the whole engine-side view of the world. It exists so {@code engine.physics} can collide
 * against volumetric terrain without knowing anything about density fields, chunk streaming or player
 * edits — the game layer implements it (a signed density field thresholded at zero, edits included) and
 * hands it in. Keeping the interface this narrow also makes physics testable against analytic shapes: a
 * flat plane or a single step is a one-line lambda.
 */
@FunctionalInterface
public interface SolidField {

    /** Whether the world is solid at this point. */
    boolean isSolid(float worldX, float worldY, float worldZ);

    /** A field with nothing in it, for bodies moving through empty space. */
    SolidField EMPTY = (x, y, z) -> false;
}
