package org.zeronura.nulzs.fluid;

/**
 * Where fluid leaving the ground is worth turning into particles.
 *
 * <p>Spray is a <b>rendering</b> feature: it exists because a cell grid has no way to describe a droplet
 * in the air, so a fall drawn from the grid alone comes out as a slab of boxes. Everything it costs is
 * therefore spent on something being looked at — and fluid that <i>cannot</i> be looked at, however
 * energetically it is falling, is the one case where the whole trade is a loss.
 *
 * <p>An interface for {@link PooledFluid}'s and {@link FluidSource}'s reason: the grid must not know what
 * a volcano is. It answers a question only the world can answer — whether a place is reachable — and the
 * grid asks it without learning why.
 *
 * <p><b>Asked per cell, not per particle, and only of cells that have already passed the three cheap
 * tests</b> in {@code FluidSim.shedSpray}, so a world that sprays everywhere pays one predicate call on
 * the handful of cells that were about to be shed anyway.
 */
@FunctionalInterface
public interface SprayZone {

    /**
     * Whether fluid at this cell may become spray.
     *
     * <p>Returning false does not stop the fluid moving, cool it, or change what the surface mesh draws —
     * it only declines to hand it to the particle solver, so the fall goes back to being drawn as the grid
     * draws everything else. That is exactly the state every fall was in before the particles existed.
     */
    boolean spraysAt(float worldX, float worldY, float worldZ);

    /** The default: every fall throws spray, which is what a world with nothing sealed inside it wants. */
    SprayZone EVERYWHERE = (x, y, z) -> true;
}
