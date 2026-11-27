package org.zeronura.engine.fluid;

/**
 * Where the grid announces that fluid is <b>moving fast</b> — whitewater over a rapid, embers off a running
 * lava tongue.
 *
 * <p>The companion to {@link SprayEmitter}, and the two are easy to confuse because both end in particles.
 * They answer different questions and only one of them moves mass. {@code SprayEmitter} is a <b>handoff</b>:
 * the grid cannot describe a droplet in the air, so it gives the mass away and the particle solver carries
 * it until it lands. This is a <b>report</b>: the fluid is exactly where the grid says it is and is drawn by
 * the surface mesh as usual, and what the cells cannot express is that a sheet tearing down a slope throws
 * mist off itself. Nothing is subtracted, so a flow that has been retuned by eye — the volcano's flank
 * tongue above all — cannot be disturbed by attaching one of these.
 *
 * <p><b>The grid decides when and where; the sink decides what it looks like.</b> That split is the reason
 * this is an interface at all. Which cells are moving, how fast, and how often that is worth a puff are
 * facts only the simulation holds; that fast water is a pale mist and fast lava is a rising ember is art
 * direction, and belongs in the game's effect catalogue beside every other such decision. The same division
 * {@code WorldEffects} already draws between "the player mined something" and "a mining hit looks like dust
 * plus sparks".
 */
public interface FluidMotionEmitter {

    /**
     * How fast this fluid has to be running before it is worth throwing anything, in cells per second, or 0
     * if it never throws.
     *
     * <p>Per fluid rather than one constant in the simulation because it is a statement about the substance:
     * water breaks up into spray at a speed molten rock is still moving as a sheet at. Returning 0 switches
     * the fluid out of the walk entirely — the same way {@link SprayEmitter#parcelVolume} returning 0 means
     * "nothing sprays this".
     */
    float motionThreshold(int fluid);

    /**
     * Fluid at this spot is running fast enough to throw something off itself.
     *
     * <p>Fired at a <b>free surface</b> cell — the top of the water, where anything thrown would actually
     * leave — and at a rate the simulation scales by how far past the threshold the flow is, so a rapid
     * smokes and a brisk stream merely glints.
     *
     * @param x     the surface point, in world units, with {@code y} already lifted to the top of the fluid
     *              rather than the cell's centre
     * @param vx    the horizontal velocity there, in cells per second, so a puff can be carried downstream
     *              rather than dropped straight up out of a moving surface
     * @param speed the magnitude of that velocity, which the sink would otherwise have to recompute
     * @param fluid the palette index, so a lava tongue and a river throw different things
     */
    void motion(float x, float y, float z, float vx, float vz, float speed, int fluid);

    /** A world where moving fluid throws nothing — exactly the behaviour before motion particles existed. */
    FluidMotionEmitter NONE = new FluidMotionEmitter() {
        @Override
        public float motionThreshold(int fluid) {
            return 0f;
        }

        @Override
        public void motion(float x, float y, float z, float vx, float vz, float speed, int fluid) {
        }
    };
}
