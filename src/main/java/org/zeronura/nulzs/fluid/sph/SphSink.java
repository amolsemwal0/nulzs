package org.zeronura.nulzs.fluid.sph;

/**
 * Where a particle's fluid goes when it stops being a particle.
 *
 * <p>The return leg of the handoff {@code SprayEmitter} opens. A droplet that has landed, settled, or simply
 * run out of lifetime is no longer something a particle method is good at — it is fluid sitting on the
 * ground, which is what the cell simulation is for — so its volume is given back and the particle is
 * recycled.
 *
 * <p>Having a sink at all is what makes the population <b>bounded without deleting anything</b>. A splash
 * system that simply expires its particles loses that water, which over a running waterfall is a leak the
 * player can eventually see as a pool that never fills. Here the two simulations trade the same volume back
 * and forth and the total is conserved, which {@code SphFluidTest} pins.
 */
@FunctionalInterface
public interface SphSink {

    /**
     * Gives a parcel of fluid back to whatever holds fluid at rest.
     *
     * @param volume how much, in cell units, where 1 is a full unit cell
     * @param fluid  the palette index the particle was carrying
     */
    void absorb(float x, float y, float z, float volume, int fluid);

    /** Nothing catches it — for a test, or a preview that only wants to watch the particles. */
    SphSink NONE = (x, y, z, volume, fluid) -> {
    };
}
