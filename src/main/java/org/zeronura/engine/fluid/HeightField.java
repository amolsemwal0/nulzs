package org.zeronura.engine.fluid;

/**
 * A world height at a horizontal point — the shape both of "where the ground surface is" and of "what
 * level fluid rests at here".
 *
 * <p>One interface for two jobs because they are the same question asked of different fields, and keeping
 * it a single method is what lets the whole simulation be driven by lambdas in a test: a flat plane, a
 * tilted ramp, a basin. In the game the ground is the edited density field's surface and the resting level
 * is a noise field, neither of which {@code engine.fluid} has any business knowing about.
 *
 * <p>Note the sim uses this only to <b>seed</b>. Once fluid exists it moves by the volumetric rules against
 * {@link org.zeronura.engine.physics.SolidField}, which is the thing that knows about caves and overhangs;
 * a height field cannot express either.
 */
@FunctionalInterface
public interface HeightField {

    float heightAt(float worldX, float worldZ);

    /** Nothing here — a resting level far below any ground, i.e. no pools seeded at all. */
    HeightField NONE = (x, z) -> Float.NEGATIVE_INFINITY;
}
