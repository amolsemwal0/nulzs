package org.zeronura.engine.fluid;

/**
 * Where fluid is sealed behind geometry no camera and no tool can ever get past.
 *
 * <p>The sibling of {@link SprayZone}, and deliberately a separate question. That one asks whether fluid
 * is worth turning into <i>particles</i>; this asks whether it is worth <b>simulating and drawing at
 * all</b>. A body of lava inside a mountain, under rock that no tool removes and that the density field
 * forces solid, is looked at by nobody from any angle at any time — so every cell of it stepped, every
 * ripple marked on it and every face emitted for it is work spent on something that cannot appear on a
 * screen. Measured at the volcano rim, that was a third of the resident fluid.
 *
 * <p>An interface for {@link PooledFluid}'s reason: the grid must not know what a volcano is. It answers a
 * question only the world can answer — whether a place is reachable — and the grid asks it without
 * learning why.
 *
 * <p><b>This is a strictly stronger claim than {@link SprayZone} makes, and it must be made carefully.</b>
 * Declining spray only declines an embellishment: the fluid still flows, still meshes, still behaves.
 * Declining simulation means the fluid <i>stops moving</i> — it holds whatever mass it had, feeding it
 * accumulates against a ceiling and nothing drains. That is invisible and free while the region really is
 * sealed, and it is wrong the moment the region stops being so. A zone that ever becomes reachable — magma
 * made mineable, a cavern opened, a pipe that is meant to carry pressure from below up to a vent — must
 * stop answering true here before it is opened, not after.
 */
@FunctionalInterface
public interface SealedZone {

    /**
     * Whether fluid at this cell is unreachable, and so need be neither stepped, rippled nor drawn.
     *
     * <p>Asked at cell centres and cached per cell for the life of the chunk, on the assumption that a
     * region's reachability is a property of the world's shape rather than of its state. A zone whose
     * answer can change during a session needs that cache invalidated, exactly as a terrain edit
     * invalidates the solidity cache beside it.
     */
    boolean sealedAt(float worldX, float worldY, float worldZ);

    /** The default: nothing is sealed, which is what a world with no sealed cavity in it wants. */
    SealedZone NONE = (x, y, z) -> false;
}
