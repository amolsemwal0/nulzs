package org.zeronura.engine.fluid.sph;

/**
 * One kind of fluid, as a particle method sees it.
 *
 * <p>The counterpart to {@code FluidType}, which describes the same liquid as <b>cells</b> — how fast it
 * spreads between neighbours, whether it cools, how fast a vent supplies it. Those numbers say nothing
 * useful about a droplet in the air, because a cell method has no droplets: its whole vocabulary is how much
 * is in a box and how quickly it moves to the next one. These are the numbers that decide whether spray
 * comes off a waterfall as a fine mist or as heavy gobbets, whether a splash holds together on the way up,
 * and how far it flattens when it lands.
 *
 * <p>Two records rather than one because the two halves genuinely disagree about what a fluid <i>is</i>, and
 * a single record would have every field meaningless to one of its readers. What ties them together is
 * {@link #index()}, which is the same palette slot both carry: a cell of lava that sprays produces lava
 * particles, and they draw with lava's appearance, because the index travels with the mass through the
 * handoff.
 *
 * @param name          for diagnostics and test failures; never shown to a player
 * @param index         palette slot, matching the {@code FluidType} of the same liquid, so the handoff
 *                      between the two simulations carries identity as well as mass
 * @param restDensity   the density the fluid is happy at. Pressure is the fluid's objection to being
 *                      squeezed past this, so it is what stops particles collapsing into a point — the
 *                      absolute value is arbitrary (it cancels out of the acceleration up to the mass it
 *                      also sets), it is the <i>ratio</i> to the density a clump actually reaches that does
 *                      the work
 * @param stiffness     how loudly the fluid objects, in pressure per unit of excess density. This is the
 *                      stability limit of the whole method: the speed of sound goes as its square root, and
 *                      a substep must be short enough that a pressure wave cannot cross a smoothing radius
 *                      within one — see {@link SphFluid#MAX_SUBSTEP}. Raising it makes the fluid less
 *                      squashy and the simulation more likely to detonate
 * @param viscosity     how strongly a particle is dragged toward its neighbours' average velocity. Water is
 *                      low and molten rock is high, and this is most of what tells the two apart in flight:
 *                      water shatters into droplets, lava comes off a lip in slow ropes
 * @param cohesion      pairwise attraction, standing in for surface tension. Without it a jet in free fall
 *                      simply disperses — there is no pressure to hold it together once the neighbours
 *                      thin out — and spray reads as dust rather than as water. It is the reason a splash
 *                      has blobs in it
 * @param particleVolume how much fluid one particle is worth, in the cell simulation's units, where 1 is a
 *                      full unit cell. The exchange rate for the handoff in both directions: it decides how
 *                      many particles a spilling cell is worth and how much mass a landing particle gives
 *                      back, and mass is conserved across the trade because both directions read this one
 *                      number
 * @param restitution   how much of the approach speed survives a bounce off terrain, 0 to 1. Water is low;
 *                      what little it has is what makes a splash climb back up out of a pool
 * @param friction      how much of the speed <i>along</i> a surface is lost per bounce, 0 to 1. High enough
 *                      and spray sticks where it lands instead of skating down the slope it hit
 * @param lifetimeSeconds how long a particle may exist before it is given back to the cell simulation
 *                      regardless. The backstop that bounds the population: spray that found a ledge it can
 *                      sit on forever would otherwise accumulate for as long as the vent runs
 */
public record SphMaterial(String name, int index, float restDensity, float stiffness, float viscosity,
                          float cohesion, float particleVolume, float restitution, float friction,
                          float lifetimeSeconds) {

    public SphMaterial {
        if (index < 0) {
            throw new IllegalArgumentException("fluid index must be dense from 0: " + index);
        }
        if (restDensity <= 0f) {
            throw new IllegalArgumentException(name + " needs a positive rest density: " + restDensity);
        }
        if (stiffness <= 0f) {
            throw new IllegalArgumentException(name + " needs a positive stiffness: " + stiffness);
        }
        if (viscosity < 0f || cohesion < 0f) {
            throw new IllegalArgumentException(name + " viscosity and cohesion cannot be negative");
        }
        if (particleVolume <= 0f) {
            throw new IllegalArgumentException(
                    name + " needs a positive particle volume, or the handoff loses mass: " + particleVolume);
        }
        if (restitution < 0f || restitution > 1f) {
            throw new IllegalArgumentException(name + " restitution must be in [0, 1]: " + restitution);
        }
        if (friction < 0f || friction > 1f) {
            throw new IllegalArgumentException(name + " friction must be in [0, 1]: " + friction);
        }
        if (lifetimeSeconds <= 0f) {
            throw new IllegalArgumentException(name + " needs a positive lifetime: " + lifetimeSeconds);
        }
    }

    /**
     * The mass one particle carries in the solver's own units, given the smoothing radius in use.
     *
     * <p>Derived rather than authored, and it has to be: mass, rest density and particle spacing are three
     * statements about the same thing, and a material free to set all three could describe a fluid whose
     * particles at their natural spacing do not add up to their own rest density — which reads as the fluid
     * being permanently under pressure, i.e. as an explosion on the first substep. Spacing is taken as half
     * the smoothing radius, which is what puts roughly thirty particles inside one support sphere.
     */
    public float massAt(float smoothingRadius) {
        float spacing = smoothingRadius * 0.5f;
        return restDensity * spacing * spacing * spacing;
    }

    /** The material's own rest spacing, from the smoothing radius the solver runs at. */
    public static float spacingFor(float smoothingRadius) {
        return smoothingRadius * 0.5f;
    }
}
