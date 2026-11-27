package org.zeronura.engine.worldmap;

import org.joml.Vector3f;
import org.zeronura.engine.render.Spline;
import org.zeronura.engine.render.TexturedMeshBuilder;
import org.zeronura.engine.render.TexturedMeshData;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the meshes of a {@link TreeShape}, measured from its root: bark for the limbs, a solid core inside each
 * meatball for an editor to look at, and the leaf objects filling them. Touches no GL, so a tree can be built and looked
 * at with no window.
 */
public final class TreeMeshes {

    /**
     * @param bark   the trunk and branches, to draw opaque in the bark texture
     * @param core   the meatballs' solid cores, to draw opaque in plain white with their tint; empty for no core
     * @param leaves the leaf objects, to draw as two-sided cutouts in the leaf picture
     */
    public record Built(TexturedMeshData bark, TexturedMeshData core, TexturedMeshData leaves) {
    }

    /** How dark a meatball's core is beside its leaves. */
    private static final float CORE_SHADE = 0.6f;
    private static final int CORE_SEGMENTS = 10;
    private static final Vector3f WHITE = new Vector3f(1f, 1f, 1f);
    /** How much bigger each leaf object a half-density tree keeps is: twice the area, for half as many. */
    private static final float HALF_LEAF_GROWTH = (float) Math.sqrt(2.0);

    private TreeMeshes() {
    }

    public static Built build(MapTree tree, TreeShape shape) {
        return build(tree, shape, false);
    }

    /**
     * The same tree at half density, for drawing farther off: half its leaf objects, each grown by the square root of
     * two so the crown covers as much; its bark round in half as many sides, its branches in half as many rings, and
     * without the sub-branches, which at that distance are lost in the crown. No cores: they are never drawn in a game.
     */
    public static Built buildHalf(MapTree tree, TreeShape shape) {
        return build(tree, shape, true);
    }

    private static Built build(MapTree tree, TreeShape shape, boolean half) {
        TexturedMeshBuilder bark = new TexturedMeshBuilder();
        for (TreeShape.Limb limb : shape.limbs()) {
            if (half && limb.level() >= 2) {
                continue;
            }
            addLimb(bark, limb, tree.barkScale, half);
        }

        TexturedMeshBuilder core = new TexturedMeshBuilder();
        float coreSize = Math.clamp(tree.core, 0f, 0.9f);
        Vector3f tint = new Vector3f();
        if (coreSize > 0.02f && !half) {
            for (TreeShape.Meatball ball : shape.meatballs()) {
                float shade = ball.shade() * CORE_SHADE;
                tint.set(tree.tint.r * shade, tree.tint.g * shade, tree.tint.b * shade);
                core.addSphere(ball.center(), ball.radius() * coreSize, CORE_SEGMENTS, new Vector3f(tint));
            }
        }

        TexturedMeshBuilder leaves = new TexturedMeshBuilder();
        List<TreeShape.Leaf> all = shape.leaves();
        for (int k = 0; k < all.size(); k += half ? 2 : 1) {
            TreeShape.Leaf leaf = all.get(k);
            if (half) {
                leaf = new TreeShape.Leaf(leaf.center(), new Vector3f(leaf.right()).mul(HALF_LEAF_GROWTH),
                        new Vector3f(leaf.up()).mul(HALF_LEAF_GROWTH), leaf.normal(), leaf.shade());
            }
            tint.set(tree.tint.r * leaf.shade(), tree.tint.g * leaf.shade(), tree.tint.b * leaf.shade());
            // Cards that carry their centres: the anime leaf shader turns each to face the camera about it.
            leaves.addCard(leaf.center(), leaf.right(), leaf.up(), leaf.normal(), tint);
        }
        return new Built(bark.buildData(), core.buildData(), leaves.buildData());
    }

    private static void addLimb(TexturedMeshBuilder builder, TreeShape.Limb limb, float barkScale, boolean half) {
        boolean trunk = limb.level() == 0;
        List<Vector3f> points = limb.points();
        if (trunk) {
            // Sunk into the ground, so a trunk on a slope has no gap on its downhill side.
            points = new ArrayList<>(points);
            points.addFirst(new Vector3f(points.getFirst()).sub(0f, limb.rootRadius() + 0.4f, 0f));
        }
        int radial = trunk ? 12 : limb.rootRadius() > 0.09f ? 7 : limb.level() == 1 ? 5 : 4;
        int rings = trunk ? points.size() - 1 : (points.size() - 1) * (limb.level() == 1 ? 2 : 1);
        if (half) {
            radial = Math.max(3, radial / 2);
            rings = trunk ? rings : Math.max(1, rings / 2);
        }
        // Three times round a trunk at the bark's own scale, fewer the bigger it is drawn, down to once; once round a branch.
        int around = trunk ? Math.max(1, Math.round(3f / Math.max(0.1f, barkScale))) : 1;
        // One picture of bark as tall as it is wide, however thick the limb is where it starts; drawn bigger than once
        // round goes, it is stretched up the limb.
        float pictureHeight = Math.max(0.15f, 6.2831855f * limb.rootRadius() / around * Math.max(1f, barkScale / 3f));
        builder.addTube(new Spline(points), new TexturedMeshBuilder.TubeProfile(limb.rootRadius(), limb.tipRadius(),
                limb.taper(), radial, rings, pictureHeight, around, false, false), WHITE);
    }
}
