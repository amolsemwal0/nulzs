package org.zeronura.nulzs.physics;

/**
 * Fluid, as physics needs to see it: where its surface is above a given point.
 *
 * <p>The counterpart to {@link SolidField}, and narrow for the same reason — physics has no business
 * knowing about cells, chunks, cooling or lava. One question, and {@code FluidSim.bodySurfaceAt} already
 * answers exactly it, so the wiring is a method reference rather than an adapter.
 *
 * <p>It asks for the surface <b>of the body containing this point</b>, not for the highest fluid in the
 * column, which is the distinction that makes a shelf over a flooded cavern behave: a crate resting on the
 * shelf is in air, and answering with the cavern's surface far below would have it think it was afloat.
 */
@FunctionalInterface
public interface FluidField {

    /** Surface height of the fluid body containing the point, or {@link Float#NaN} if the point is in air. */
    float surfaceLevelAt(float worldX, float worldY, float worldZ);

    /** A world with nothing wet in it. */
    FluidField EMPTY = (x, y, z) -> Float.NaN;
}
