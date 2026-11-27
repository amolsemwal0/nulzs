package org.zeronura.nulzs.fluid;

/**
 * Where a cell simulation throws fluid it can no longer describe.
 *
 * <p>{@link FluidSim} is a grid: its whole vocabulary is how much fluid is in a box and how fast it moves to
 * the next one, and that is the right model for a pond, a flooded cavern and a sheet running down a slope.
 * It has no way to express a <b>droplet</b> — a piece of fluid in the air with no neighbours, following its
 * own arc. Water leaving a lip is exactly that, and drawn as cells it comes out as a curtain of boxes
 * stepping down through the air.
 *
 * <p>So the grid hands it over. Where fluid spills off an edge fast enough to leave the ground behind, mass
 * is taken out of the cells and given to whatever implements this, which in the game is the particle method
 * — and the particles hand it back through {@code SphSink} when they land. One number, the material's
 * particle volume, governs the exchange in both directions, so nothing is created or destroyed in the trade.
 *
 * <p>An interface rather than a direct call because the grid must not depend on the particles: a world with
 * no spray at all is {@link #NONE}, every existing test drives a simulation that has never heard of a
 * particle, and the two halves are separately testable.
 */
public interface SprayEmitter {

    /**
     * The smallest parcel worth offering for this fluid — one droplet's worth of volume — or 0 if nothing
     * sprays it.
     *
     * <p>Not a detail. Fluid comes off a lip as a <b>thin</b> sheet, and a share of a thin cell per frame is
     * a very small number: measured on a real spill, a cell shedding five percent of itself offers about
     * seven tenths of one droplet, every frame, forever. An emitter that can only make whole droplets
     * rounds that to nothing, and the result is a waterfall that never sprays at all while both halves of
     * the system report themselves working perfectly — the grid dutifully offering, the solver dutifully
     * declining. It took a test to find, because there is no error anywhere in it.
     *
     * <p>So the quantum is published, and the grid offers at least one of them (or the whole cell, if the
     * cell is smaller than a droplet, in which case the dust stays where it is). What the emitter takes may
     * therefore be <i>more</i> than the share the rate asked for, which is correct: the alternative to
     * handing over a whole droplet is handing over none.
     */
    float parcelVolume(int fluid);

    /**
     * Takes a parcel of fluid out of the grid and into the air.
     *
     * @param x      where it left, in world units
     * @param vx     the velocity it left with — the grid's own momentum, so a droplet carries on in the
     *               direction the stream was running rather than dropping vertically out of nothing
     * @param volume how much fluid, in cell units, where 1 is a full unit cell
     * @param fluid  the palette index, so a lava spill sprays lava
     * @return how much of the volume was actually taken. The grid subtracts <b>this</b>, not what it
     *         offered, so a particle budget that has run out costs nothing rather than quietly deleting
     *         water — which would drain a waterfall into nowhere over a few minutes
     */
    float spray(float x, float y, float z, float vx, float vy, float vz, float volume, int fluid);

    /** A world where nothing sprays: fluid stays in the grid, exactly as it did before particles existed. */
    SprayEmitter NONE = new SprayEmitter() {
        @Override
        public float spray(float x, float y, float z, float vx, float vy, float vz, float volume,
                           int fluid) {
            return 0f;
        }

        @Override
        public float parcelVolume(int fluid) {
            return 0f;
        }
    };
}
