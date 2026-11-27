package org.zeronura.nulzs.physics;

/**
 * How soft a soft thing is: three numbers that describe deformation, shared by everything squishy in the
 * engine.
 *
 * <p>They are worth naming once because the same three answer the question for two quite different
 * lattices. A jelly cube is a {@link SoftBody} — particles in three dimensions held together by distance
 * constraints — while a fluid's surface is a sheet of nodes that only move up and down. What they have in
 * common is not their geometry, it is their <i>behaviour</i>: how hard the shape fights being deformed, how
 * fast that fight dies down, and how strongly it is drawn back to where it should be. Two vocabularies for
 * that would drift, and it would stop being obvious that lava is the same kind of thing as a stiff jelly
 * with a lot of damping — which it is, and which is why one record covers both.
 *
 * @param name      for diagnostics and test failures; never shown to a player
 * @param stiffness how strongly neighbouring particles hold their relationship, in {@code (0, 1]}. In a
 *                  solid this is how much a stretched link is pulled back per solver pass; on a fluid
 *                  surface it is the coupling that carries a ripple to the next node, so it reads directly
 *                  as <b>wave speed</b>. Note it is a per-pass fraction, so the effective stiffness of a
 *                  {@link SoftBody} also depends on the solver's iteration count — the usual position-based
 *                  dynamics caveat, and the reason this is a feel knob rather than a modulus
 * @param damping   how fast motion bleeds away, per second. This is what separates water from lava far more
 *                  than stiffness does: a ripple that persists for a second and one that dies in a tenth of
 *                  one are the difference between a pond and molten rock
 * @param restore   how strongly the body is pulled back toward its rest shape, per second. Zero for a free
 *                  body, whose shape is held by its own links; positive for something anchored to a target
 *                  it must return to — a fluid surface pulled back to the level the simulation says it
 *                  should be at
 */
public record SoftBodyMaterial(String name, float stiffness, float damping, float restore) {

    public SoftBodyMaterial {
        if (stiffness <= 0f || stiffness > 1f) {
            throw new IllegalArgumentException(name + " stiffness must be in (0, 1]: " + stiffness);
        }
        if (damping < 0f || restore < 0f) {
            throw new IllegalArgumentException(name + " damping and restore cannot be negative");
        }
    }

    /** A springy solid that holds its shape and wobbles for a while: the default for a free soft body. */
    public static final SoftBodyMaterial JELLY = new SoftBodyMaterial("Jelly", 0.55f, 1.4f, 0f);

    /** Barely deforms and stops moving almost at once — a soft body that reads as a firm one. */
    public static final SoftBodyMaterial RUBBER = new SoftBodyMaterial("Rubber", 0.95f, 3.0f, 0f);
}
