package org.zeronura.nulzs.fluid;

import org.zeronura.nulzs.physics.SoftBodyMaterial;

/**
 * One kind of fluid, as physics: how fast it spreads, whether it disappears on its own, and how fast a
 * source of it wells up.
 *
 * <p>This is the whole reason the fluid system is a system rather than a water simulation with a lava flag
 * bolted on. The behaviour that used to be three constants inside the sim — a flow rate that water and
 * lava shared whether that was right or not, a cooling rate that only applied to lava, a supply rate that
 * only vents used — is now <b>data the caller registers</b>. Adding oil, acid, mud or swamp water is a
 * record here plus an appearance; the simulation does not learn a new name.
 *
 * <p>{@link #index} is the fluid's slot in the caller's palette, and it is carried per cell and per vertex,
 * so the renderer can look up how a cell should look without the sim knowing what any of them look like.
 * Indices must be dense from 0 and distinct — the sim stores one as a byte per cell and the shader uses it
 * to index a uniform array.
 *
 * @param name       for diagnostics and test failures; never shown to a player
 * @param index      slot in the appearance palette, dense from 0
 * @param flowRate   fraction of the excess moved per substep. Water spreads fast (near the 0.5 stability
 *                   ceiling); a viscous fluid crawls. <b>Must stay ≤ 0.5</b>, or a pair of cells overshoots
 *                   level and oscillates instead of settling.
 * @param coolRate   depth lost per second while not being fed, in cell units. Zero means the fluid is
 *                   conserved (water): it only ever moves. Non-zero is a fluid that solidifies where it
 *                   stops being supplied, which is the sink that gives a fed source a bounded flow — see
 *                   {@link FluidSim} on why an unbounded one piles up forever.
 * @param supplyRate depth per second a {@link FluidSource} cell of this fluid may gain. This is what ties
 *                   the size of a flow to the size of its opening: a vent's output is this rate times its
 *                   opening <i>area</i>, so widening the hole is the only way to get more out of it.
 * @param surface    how the fluid's {@link FluidSurface} deforms — how fast a ripple crosses it, how long one
 *                   lasts, and how hard it is pulled back to level. The three rates above say how the
 *                   <i>mass</i> moves between cells; this says how the skin over that mass behaves, which is
 *                   most of what tells water from molten rock at a glance
 * @param minDrawDepth cells holding less than this are simulated but <b>not meshed</b>. Zero draws
 *                   everything, which is what a fluid whose thin edge matters wants: a waterfall's falling
 *                   sheet and a flood's leading edge are both very thin and both very much want to be seen.
 *                   It is for the opposite case — a fluid spreading over ground that is rough at cell scale
 *                   breaks into shallow puddles with dry cells between them, and drawn at full strength each
 *                   one is a separate speck. That reads as <b>dithering along the edge of a flow</b>, and it
 *                   is a property of the cell grid rather than of the shading, so no amount of fading or
 *                   texturing addresses it — the specks have to stop being geometry. Molten rock is the
 *                   fluid that can afford this: a film of it too thin to draw has cooled to rock, which is
 *                   what the dark ground already looks like, whereas a film of water too thin to draw is a
 *                   hole in a waterfall
 */
public record FluidType(String name, int index, float flowRate, float coolRate, float supplyRate,
                        SoftBodyMaterial surface, float minDrawDepth) {

    /**
     * As above, for a fluid that wants every cell it occupies drawn — {@code minDrawDepth} 0.
     *
     * <p>Kept so the arity that predates that field still compiles: it is the right default, since drawing
     * everything is what the mesher did for every fluid before any of them asked otherwise.
     */
    public FluidType(String name, int index, float flowRate, float coolRate, float supplyRate,
                     SoftBodyMaterial surface) {
        this(name, index, flowRate, coolRate, supplyRate, surface, 0f);
    }

    /** Above this a flow rate can overshoot level between a pair of cells and oscillate rather than settle. */
    public static final float MAX_STABLE_FLOW_RATE = 0.5f;

    public FluidType {
        if (index < 0) {
            throw new IllegalArgumentException("fluid index must be dense from 0: " + index);
        }
        if (flowRate <= 0f || flowRate > MAX_STABLE_FLOW_RATE) {
            throw new IllegalArgumentException(
                    name + " flowRate must be in (0, " + MAX_STABLE_FLOW_RATE + "]: " + flowRate);
        }
        if (coolRate < 0f || supplyRate < 0f) {
            throw new IllegalArgumentException(name + " rates cannot be negative");
        }
        if (surface == null) {
            throw new IllegalArgumentException(name + " needs a surface material; see FluidType.STILL");
        }
    }

    /**
     * A surface so heavily damped that it never visibly moves — what a fluid whose look nobody has decided
     * yet should carry.
     *
     * <p>Deliberately not "no surface at all". A null would mean every caller of the sheet had to test for
     * it, and the case it would exist for — a fluid that should not ripple — is expressible as a material,
     * which is the same reasoning that keeps {@code WorldFeedback.NONE} a no-op object rather than a null.
     */
    public static final SoftBodyMaterial STILL = new SoftBodyMaterial("Still", 0.1f, 40f, 20f);

    /** Whether this fluid solidifies where nothing is supplying it. */
    public boolean cools() {
        return coolRate > 0f;
    }
}
