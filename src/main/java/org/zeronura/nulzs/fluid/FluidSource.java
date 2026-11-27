package org.zeronura.nulzs.fluid;

/**
 * Somewhere fluid is <b>continuously supplied</b>: a vent, a spring, a pipe.
 *
 * <p>The distinction from a resting level is the whole of it. A {@link HeightField} resting level fills a
 * basin <i>once</i> and leaves it to flow; a source is <i>held</i>, so every tick its cells are topped back
 * up and fluid keeps welling out and running downhill. A pond needs the first, a volcano's flank vent needs
 * the second.
 *
 * <p>Supply is limited by {@link FluidType#supplyRate}, not by the level alone, and that is deliberate: a
 * source held at a level with no rate limit is an <i>infinite reservoir</i> — whatever drains away is
 * replaced for free, so how much a vent produces is set by how fast the ground carries it off and has
 * nothing to do with the size of the hole. With a rate, output is the rate times the opening's area, and
 * widening the hole is the only way to get more out.
 *
 * <p>Two-dimensional on purpose: a source is a column of supply with a head, which is what a vent, a spring
 * and a burst pipe all are. Where it applies vertically falls out of the level — cells below it are topped
 * up, cells above are not.
 */
public interface FluidSource {

    /**
     * The head this source supplies up to at a horizontal point, or {@link Float#NEGATIVE_INFINITY} where
     * this source does not reach. Fluid in the column never rises above it, which is what lets a contained
     * pool sit at its own surface and stop asking for more.
     */
    float supplyLevelAt(float worldX, float worldZ);

    /** What comes out. */
    FluidType fluid();
}
