package org.zeronura.engine.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.physics.AABB;
import org.zeronura.engine.render.FoliageMaterial;
import org.zeronura.engine.scene.Asset;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.Range;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.scene.StoresData;
import org.zeronura.engine.scene.Tooltip;
import org.zeronura.engine.worldmap.MapTree;
import org.zeronura.engine.worldmap.SplineMaterials;
import org.zeronura.engine.worldmap.TreeMeshes;
import org.zeronura.engine.worldmap.TreeShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A tree grown from a trunk drawn as a spline — thickest at the root, a point at the top — with branches, splits and
 * sub-branches grown from it by its settings, and its leaves gathered in balls round the branch ends: the map editor's
 * spline trees, as a component of a scene. The editor's Tree Tool draws the trunk; the sliders in the Inspector grow the
 * rest.
 *
 * <p>The trunk's points are in the entity's own space, the first at its root (0, 0, 0). Changing a setting grows the tree
 * again; while a slider is dragged it is grown at most ten times a second, a big tree taking a moment to build.
 */
public class SplineTree extends Component implements StoresData, Collides3D {

    @Range(min = 0.05, max = 2)
    public float trunkRadius = 0.45f;
    @Range(min = 0.5, max = 4)
    @Tooltip("How quickly the trunk narrows towards the top")
    public float taper = 1.6f;
    @Range(min = 0, max = 48)
    public int branches = 14;
    @Range(min = 0, max = 0.9)
    @Tooltip("How far up the trunk the branches begin, as a share of its height")
    public float branchStart = 0.35f;
    @Range(min = 0.05, max = 1.5)
    public float branchLength = 0.55f;
    @Range(min = 10, max = 120)
    public float branchAngle = 58f;
    @Range(min = -1, max = 1)
    public float branchBend = 0.3f;
    @Range(min = 0.15, max = 0.9)
    public float branchThickness = 0.55f;
    public MapTree.Crown crown = MapTree.Crown.ROUND;
    @Range(min = 0, max = 4)
    @Tooltip("How many times each branch forks")
    public int splits = 2;
    @Range(min = 5, max = 70)
    public float splitAngle = 30f;
    @Range(min = 0, max = 10)
    public int subBranches = 2;
    @Range(min = 0.1, max = 1)
    public float subLength = 0.45f;
    @Range(min = 0, max = 1)
    @Tooltip("How much the branches wander from their even places")
    public float wildness = 0.35f;
    @Range(min = 0, max = 6)
    @Tooltip("Balls of leaves at each branch end")
    public int leafBalls = 1;
    @Range(min = 0.2, max = 6)
    public float ballSize = 2f;
    @Range(min = 0, max = 200)
    @Tooltip("Leaf cards in each ball")
    public int leaves = 70;
    @Range(min = 0.05, max = 2.5)
    public float leafSize = 0.9f;
    @Tooltip("What colour a pale leaf picture takes on; Picture leaves it as painted")
    public MapTree.LeafTint tint = MapTree.LeafTint.SUMMER;
    @Asset({"png", "jpg", "jpeg", "tga"})
    public String barkTexture = SplineLooks.DEFAULT_BARK;
    @Range(min = 0.25, max = 6)
    @Tooltip("How many times the bark picture goes round the trunk")
    public float barkScale = 1f;
    @Asset({"png", "tga"})
    @Tooltip("A picture of leaves on a clear background, laid on every leaf card")
    public String leafTexture = SplineLooks.DEFAULT_LEAVES;
    @Tooltip("Change it for another tree from the same settings")
    public int seed;
    @Tooltip("Characters bump into the trunk")
    public boolean solid = true;
    public boolean castShadows = true;

    private final List<Vector3f> points = new ArrayList<>();
    private final LaidParts parts = new LaidParts();
    private final Matrix4f world = new Matrix4f();
    private String built;
    private long lastBuild;
    private float height = 6f;

    public SplineTree() {
        // A tree added with nothing drawn yet is a young one with a gently leaning trunk.
        points.add(new Vector3f(0f, 0f, 0f));
        points.add(new Vector3f(0.15f, 2.6f, 0.05f));
        points.add(new Vector3f(-0.1f, 5f, 0.1f));
        points.add(new Vector3f(0.05f, 7f, 0f));
    }

    // --- Points -------------------------------------------------------------------------------------------------

    /** The trunk's points from the root up, in the entity's own space; the first is the root. */
    public List<Vector3f> points() {
        return points;
    }

    @Override
    public Object saveData() {
        List<Object> out = new ArrayList<>();
        for (Vector3f point : points) {
            out.add(List.of(round(point.x), round(point.y), round(point.z)));
        }
        return out;
    }

    private static double round(float value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    @Override
    public void loadData(Object data) {
        if (!(data instanceof List<?> list) || list.size() < 2) {
            return;
        }
        points.clear();
        for (Object item : list) {
            if (item instanceof List<?> p && p.size() >= 3 && p.get(0) instanceof Number x && p.get(1) instanceof Number y
                    && p.get(2) instanceof Number z) {
                points.add(new Vector3f(x.floatValue(), y.floatValue(), z.floatValue()));
            }
        }
    }

    // --- Growing ------------------------------------------------------------------------------------------------

    @Override
    protected void onEnterScene(Scene scene) {
        parts.attach(scene);
    }

    @Override
    protected void onExitScene(Scene scene) {
        parts.detach();
        built = null;
    }

    @Override
    protected void preRender(float alpha) {
        parts.attach(scene());
        parts.castShadows = castShadows;
        transform().worldMatrix(world);
        parts.place(world);
        String key = key();
        long now = System.nanoTime();
        if (!key.equals(built) && (built == null || now - lastBuild > 100_000_000L)) {
            rebuild();
            built = key;
            lastBuild = now;
        }
    }

    private String key() {
        StringBuilder key = new StringBuilder(String.format(Locale.ROOT,
                "%s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s %s|", trunkRadius, taper, branches,
                branchStart, branchLength, branchAngle, branchBend, branchThickness, crown, splits, splitAngle, subBranches,
                subLength, wildness, leafBalls, ballSize, leaves, leafSize, tint, barkTexture, barkScale, leafTexture, seed));
        for (Vector3f point : points) {
            key.append(point.x).append(',').append(point.y).append(',').append(point.z).append(';');
        }
        return key.toString();
    }

    /** The tree as the map's tree code knows it, rooted at the origin. */
    public MapTree tree() {
        MapTree tree = new MapTree(0f, 0f);
        tree.trunkRadius = trunkRadius;
        tree.taper = taper;
        tree.branches = branches;
        tree.branchStart = branchStart;
        tree.branchLength = branchLength;
        tree.branchAngle = branchAngle;
        tree.branchBend = branchBend;
        tree.branchThickness = branchThickness;
        tree.crown = crown;
        tree.splits = splits;
        tree.splitAngle = splitAngle;
        tree.subBranches = subBranches;
        tree.subLength = subLength;
        tree.wildness = wildness;
        tree.meatballs = leafBalls;
        tree.meatballSize = ballSize;
        tree.leaves = leaves;
        tree.leafSize = leafSize;
        tree.tint = tint;
        tree.barkTexture = blank(barkTexture) ? null : barkTexture.trim();
        tree.barkScale = barkScale;
        tree.leafTexture = blank(leafTexture) ? null : leafTexture.trim();
        tree.seed = seed;
        Vector3f root = points.isEmpty() ? new Vector3f() : points.getFirst();
        for (Vector3f point : points) {
            tree.addPoint(point.x - root.x, point.y - root.y, point.z - root.z);
        }
        return tree;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private void rebuild() {
        parts.clear();
        Scene scene = scene();
        if (scene == null || scene.engine() == null || points.size() < 2) {
            return;
        }
        try {
            MapTree tree = tree();
            TreeShape shape = TreeShape.of(tree);
            height = shape.height();
            TreeMeshes.Built laid = TreeMeshes.build(tree, shape);
            SplineMaterials looks = SplineLooks.of(scene.assets());
            FoliageMaterial bark = tree.barkTexture == null ? null : looks.material(tree.barkTexture);
            parts.add(laid.bark(), bark != null ? bark : SplineLooks.plain(org.zeronura.engine.graphics2d.Color.rgb(0x6B4E36)));
            parts.add(laid.leaves(), looks.leafMaterial(tree.leafTexture));
        } catch (RuntimeException e) {
            System.err.println("[SplineTree] Could not grow " + entity().name() + ": " + e);
        }
    }

    /** How tall the tree grew last, from its root. */
    public float grownHeight() {
        return height;
    }

    // --- Colliding ----------------------------------------------------------------------------------------------

    @Override
    public void addColliders(List<AABB> into) {
        if (!solid || points.size() < 2 || entity() == null || !entity().isActive()) {
            return;
        }
        Vector3f root = transform().worldMatrix(new Matrix4f()).transformPosition(new Vector3f(points.getFirst()));
        float scale = Math.max(transform().scale.x, transform().scale.z);
        float half = Math.max(0.2f, trunkRadius * 0.85f * scale);
        into.add(new AABB(root.x - half, root.y, root.z - half, root.x + half, root.y + 4f * transform().scale.y, root.z + half));
    }

    /** The box round the tree as it last grew, in its own space, for picking and framing it. */
    public boolean bounds(Vector3f min, Vector3f max) {
        float reach = Math.max(1.5f, height * 0.45f);
        min.set(-reach, 0f, -reach);
        max.set(reach, Math.max(height, 1f), reach);
        if (!points.isEmpty()) {
            Vector3f root = points.getFirst();
            min.add(root);
            max.add(root);
        }
        return true;
    }
}
