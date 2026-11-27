package org.zeronura.engine.render;

/** What a particle looks like, as a cell of the texture {@link ParticleAtlas} paints. Tinted by the particle's colour. */
public enum ParticleShape {
    /** A soft round dot: dust, smoke, sparks, glowing specks. */
    DOT,
    /** A pointed leaf with a midrib. */
    LEAF,
    /** A rounded petal, narrow at its foot. */
    PETAL,
    /** A butterfly from above: four wings and a dark body. */
    BUTTERFLY,
    /** A thin four-pointed star: a glint of light. */
    SPARKLE,
    /** A lumpy soft cloud: a puff of dust, mist, foam. */
    PUFF,
    /** A thin circle: a ripple, usually laid {@linkplain ParticleLook#flat flat}. */
    RING,
    /** A bird far off, wings spread: a dark curve. */
    BIRD
}
