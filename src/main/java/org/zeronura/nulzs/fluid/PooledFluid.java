package org.zeronura.nulzs.fluid;

/**
 * Which fluid rests at a horizontal point — what a basin here would fill with.
 *
 * <p>Consulted only when a column is <b>seeded</b>, never while fluid moves, and that is the whole
 * distinction: where a pool sits is a property of the world (a volcanic wasteland pools lava, a meadow
 * pools water), while what happens to it afterwards is a property of the fluid. Moving depth between cells
 * does not move which cells are volcanic, so a lava flow that runs downhill into the grasslands is still
 * lava when it gets there.
 */
@FunctionalInterface
public interface PooledFluid {

    /** The fluid a basin here fills with. Never null — a world with one fluid returns it everywhere. */
    FluidType fluidAt(float worldX, float worldZ);

    /** Everywhere fills with the same thing — the single-fluid world, and what every test wants. */
    static PooledFluid of(FluidType type) {
        return (x, z) -> type;
    }
}
