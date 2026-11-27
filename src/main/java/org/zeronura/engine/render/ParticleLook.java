package org.zeronura.engine.render;

/**
 * The optional part of a {@link ParticleEffect}: a shape other than a dot, and motion beyond a thrown arc. Everything
 * defaults to off, so {@link #PLAIN} is the original soft dot. Set what a look needs, in a line that reads:
 * {@code ParticleLook.of(ParticleShape.LEAF).spin(1.5f).tumble(0.8f).flutter(0.9f).wind(1f)}.
 *
 * @param shape   what is drawn
 * @param spin    how fast it turns in its own plane, radians a second; each particle starts at its own angle and goes
 *                its own way round
 * @param tumble  turns a second it rolls about its width, so its height swings through nothing and back: a falling
 *                leaf, or a bird's wings going from up to down
 * @param flap    beats a second its width closes and opens about its middle: a butterfly's wings
 * @param flutter how fast, in units a second, it wanders about on its own, each particle on its own path
 * @param pulse   how many times a second it brightens and dims: a firefly's blink; 0 is steady
 * @param fadeIn  the share of its life it takes to appear, 0 for at once
 * @param fadeOut the share of its life it takes to go; with this its colour need not fade to clear
 * @param wind    how much of {@link ParticlePool#setWind the wind} carries it: 1 is all of it, 0 none
 * @param sizeVariety how far each particle's size may differ from the effect's, as a share of it
 * @param flat    lies flat, facing up, instead of facing the camera: a ripple on water
 */
public record ParticleLook(ParticleShape shape, float spin, float tumble, float flap, float flutter, float pulse,
                           float fadeIn, float fadeOut, float wind, float sizeVariety, boolean flat) {

    /** A plain soft dot, moved only by its throw. */
    public static final ParticleLook PLAIN = of(ParticleShape.DOT);

    public static ParticleLook of(ParticleShape shape) {
        return new ParticleLook(shape, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false);
    }

    public ParticleLook spin(float radiansPerSecond) {
        return new ParticleLook(shape, radiansPerSecond, tumble, flap, flutter, pulse, fadeIn, fadeOut, wind, sizeVariety, flat);
    }

    public ParticleLook tumble(float turnsPerSecond) {
        return new ParticleLook(shape, spin, turnsPerSecond, flap, flutter, pulse, fadeIn, fadeOut, wind, sizeVariety, flat);
    }

    public ParticleLook flap(float beatsPerSecond) {
        return new ParticleLook(shape, spin, tumble, beatsPerSecond, flutter, pulse, fadeIn, fadeOut, wind, sizeVariety, flat);
    }

    public ParticleLook flutter(float unitsPerSecond) {
        return new ParticleLook(shape, spin, tumble, flap, unitsPerSecond, pulse, fadeIn, fadeOut, wind, sizeVariety, flat);
    }

    public ParticleLook pulse(float timesPerSecond) {
        return new ParticleLook(shape, spin, tumble, flap, flutter, timesPerSecond, fadeIn, fadeOut, wind, sizeVariety, flat);
    }

    public ParticleLook fades(float in, float out) {
        return new ParticleLook(shape, spin, tumble, flap, flutter, pulse, in, out, wind, sizeVariety, flat);
    }

    public ParticleLook wind(float share) {
        return new ParticleLook(shape, spin, tumble, flap, flutter, pulse, fadeIn, fadeOut, share, sizeVariety, flat);
    }

    public ParticleLook sizeVariety(float share) {
        return new ParticleLook(shape, spin, tumble, flap, flutter, pulse, fadeIn, fadeOut, wind, share, flat);
    }

    public ParticleLook laidFlat() {
        return new ParticleLook(shape, spin, tumble, flap, flutter, pulse, fadeIn, fadeOut, wind, sizeVariety, true);
    }
}
