package org.zeronura.nulzs.render;

import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The parameters that make one burst of particles look like a dust puff and another like a spark shower.
 *
 * <p>Pure data, no game concepts: the whole vocabulary of a particle effect is here, and the game layer's
 * effect catalogue is just a set of these with chosen numbers and colours. A particle's life is a single
 * arc — it is born somewhere in a small sphere, thrown in a direction within a cone at some speed, pulled
 * by gravity and slowed by drag, growing/shrinking and fading from one colour to another until it dies.
 *
 * @param count       how many particles a single {@code emit} spawns
 * @param direction   the burst's central throw direction (need not be normalised)
 * @param coneAngle   half-angle in radians the throw spreads around {@link #direction}; {@code PI} is a
 *                    full sphere (omnidirectional)
 * @param speedMin    slowest / fastest initial speed; each particle picks uniformly between them
 * @param speedMax    see {@link #speedMin}
 * @param gravity     vertical acceleration in units/s² — negative falls (dust), positive rises (smoke),
 *                    zero drifts
 * @param drag        fraction of velocity <b>kept per second</b> (applied as {@code drag^dt}); 1 is
 *                    frictionless, 0.1 stops almost at once
 * @param lifeMin     shortest / longest lifetime in seconds; each particle picks uniformly between them
 * @param lifeMax     see {@link #lifeMin}
 * @param startSize   half-extent of the billboard at birth / death, interpolated over life
 * @param endSize     see {@link #startSize}
 * @param startColor  RGBA at birth / death, interpolated over life; alpha usually ends at 0 so the
 *                    particle fades out rather than blinking off
 * @param endColor    see {@link #startColor}
 * @param blend       {@link BlendMode#ALPHA} for occluding particles, {@link BlendMode#ADDITIVE} for glow
 * @param spawnRadius particles are born within this radius of the emit point, so a burst has a little size
 * @param look        its shape and any motion beyond the arc; {@link ParticleLook#PLAIN} for a plain dot
 */
public record ParticleEffect(
        int count,
        Vector3f direction,
        float coneAngle,
        float speedMin,
        float speedMax,
        float gravity,
        float drag,
        float lifeMin,
        float lifeMax,
        float startSize,
        float endSize,
        Vector4f startColor,
        Vector4f endColor,
        BlendMode blend,
        float spawnRadius,
        ParticleLook look) {

    /** A plain soft dot. */
    public ParticleEffect(int count, Vector3f direction, float coneAngle, float speedMin, float speedMax, float gravity,
                          float drag, float lifeMin, float lifeMax, float startSize, float endSize, Vector4f startColor,
                          Vector4f endColor, BlendMode blend, float spawnRadius) {
        this(count, direction, coneAngle, speedMin, speedMax, gravity, drag, lifeMin, lifeMax, startSize, endSize,
                startColor, endColor, blend, spawnRadius, ParticleLook.PLAIN);
    }
}
