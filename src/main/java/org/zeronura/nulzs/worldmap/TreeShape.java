package org.zeronura.nulzs.worldmap;

import org.joml.Vector3f;
import org.zeronura.nulzs.render.Spline;
import org.zeronura.nulzs.render.TexturedMeshBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The shape a {@link MapTree}'s points and sliders describe, measured from its root: the limbs (each a tapering line, the
 * trunk first), the meatballs of foliage at the limbs' ends, and the leaf objects filling them. Plain data with no GL, so
 * the same settings always grow the same tree and a test can look at one with no window open.
 *
 * <h2>The meatballs</h2>
 * The meatballs are metaballs: each adds to a field that falls off to nothing a little beyond its radius, and the foliage
 * is what lies above one level of the field. Two near each other therefore swell toward each other and join in one lump
 * rather than sitting apart as two spheres, and a leaf's shading normal comes from the field's slope, so a whole crown
 * shades as the rounded mass it is rather than card by card.
 */
public final class TreeShape {

    /** A length of trunk or branch: {@code level} 0 is the trunk, 1 a branch or a limb forked from one, 2 a sub-branch. */
    public record Limb(List<Vector3f> points, float rootRadius, float tipRadius, float taper, int level) {

        public Vector3f tip() {
            return points.getLast();
        }

        public float length() {
            float total = 0f;
            for (int k = 1; k < points.size(); k++) {
                total += points.get(k - 1).distance(points.get(k));
            }
            return total;
        }
    }

    /** A ball of foliage. {@code shade} varies each a little from the next, so clumps show against each other. */
    public record Meatball(Vector3f center, float radius, float shade) {
    }

    /**
     * A leaf object: a card spanning {@code right} and {@code up} about {@code center}.
     *
     * @param normal outward from the foliage as a whole at that place, not from the card
     * @param shade  how lit it is, darker the deeper in
     */
    public record Leaf(Vector3f center, Vector3f right, Vector3f up, Vector3f normal, float shade) {
    }

    /** The most limbs a tree grows, whatever its sliders say. */
    static final int MAX_LIMBS = 1400;
    /** The most meatballs a tree carries. */
    static final int MAX_MEATBALLS = 500;
    /** The most leaf objects a tree carries; beyond it every meatball gets fewer. */
    static final int MAX_LEAVES = 26000;

    /** How far beyond its radius a meatball's field reaches, as a multiple of it: how readily near ones join. */
    private static final float SUPPORT = 1.7f;
    /** The field's level at a lone meatball's radius; the foliage is what lies above it. */
    private static final float SURFACE = cube(1f - 1f / (SUPPORT * SUPPORT));
    /** The field's level beyond which a place is buried too deep inside joined meatballs for a leaf there to show. */
    private static final float BURIED = SURFACE * 3.2f;
    /** How big a meatball on a sub-branch is beside one on a branch. */
    private static final float SUB_BRANCH_MEATBALL = 0.7f;
    /** How much each leaf object's shade is its own, either way: the clump-to-clump variation a painted crown has. */
    private static final float CARD_SHADE = 0.1f;
    /** How many places a leaf tries before going without. */
    private static final int PLACE_ATTEMPTS = 16;
    /** Each point of a limb's curve, this many along one limb. */
    private static final int LIMB_POINTS = 5;
    private static final float GOLDEN_ANGLE = 2.3999631f;

    private final List<Limb> limbs = new ArrayList<>();
    private final List<Limb> ends = new ArrayList<>();
    private final List<Meatball> meatballs = new ArrayList<>();
    private final List<Leaf> leaves = new ArrayList<>();
    private final MapTree tree;
    private float height;

    private TreeShape(MapTree tree) {
        this.tree = tree;
    }

    /** The trunk's limbs, meatballs and leaves as a tree's settings have them; empty for a tree of too few points. */
    public static TreeShape of(MapTree tree) {
        TreeShape shape = new TreeShape(tree);
        if (tree.drawable()) {
            shape.growTrunk();
            shape.hangMeatballs();
            shape.fillWithLeaves();
        }
        return shape;
    }

    public List<Limb> limbs() {
        return limbs;
    }

    public List<Meatball> meatballs() {
        return meatballs;
    }

    public List<Leaf> leaves() {
        return leaves;
    }

    /** The top of the trunk's height above the root. */
    public float height() {
        return height;
    }

    /**
     * The trunk's line drawn through its points, as evenly spaced points. A hand-placed trunk has its points any distance
     * apart, and a tube's taper is even in the steps along it, so the line is walked by length first.
     *
     * @param count how many points, at the least 2
     */
    public static List<Vector3f> trunkLine(MapTree tree, int count) {
        List<Vector3f> controls = new ArrayList<>();
        for (MapTree.Point point : tree.points()) {
            controls.add(new Vector3f(point.x, point.y, point.z));
        }
        Spline curve = new Spline(controls);
        int samples = Math.max(24, controls.size() * 16);
        Vector3f[] walked = new Vector3f[samples + 1];
        float[] along = new float[samples + 1];
        for (int k = 0; k <= samples; k++) {
            walked[k] = curve.pointAt(k / (float) samples);
            along[k] = k == 0 ? 0f : along[k - 1] + walked[k].distance(walked[k - 1]);
        }
        List<Vector3f> even = new ArrayList<>(count);
        int at = 0;
        for (int k = 0; k < count; k++) {
            float wanted = along[samples] * k / (count - 1);
            while (at < samples - 1 && along[at + 1] < wanted) {
                at++;
            }
            float span = along[at + 1] - along[at];
            float blend = span <= 1e-6f ? 0f : Math.clamp((wanted - along[at]) / span, 0f, 1f);
            even.add(new Vector3f(walked[at]).lerp(walked[at + 1], blend));
        }
        return even;
    }

    // --- Limbs ------------------------------------------------------------------------------------------

    private void growTrunk() {
        int rings = Math.clamp(Math.round(lengthOf(tree) / 0.6f), 8, 48);
        List<Vector3f> line = trunkLine(tree, rings + 1);
        Limb trunk = new Limb(line, tree.trunkRadius, 0f, Math.max(0.2f, tree.taper), 0);
        limbs.add(trunk);
        ends.add(trunk);
        height = line.getLast().y;
        float trunkLength = trunk.length();

        int count = Math.max(0, tree.branches);
        float start = Math.clamp(tree.branchStart, 0f, 0.95f);
        for (int i = 0; i < count && limbs.size() < MAX_LIMBS; i++) {
            // Each branch has its own stream of random numbers, so taking one away leaves the others as they were.
            Random random = new Random(tree.seed * 1000003L + i * 7919L);
            float rise = (i + 0.5f) / count;
            float h = start + (0.97f - start) * rise + (random.nextFloat() - 0.5f) * tree.wildness * 0.8f / count;
            h = Math.clamp(h, 0f, 0.98f);
            float through = start >= 0.97f ? 0f : Math.clamp((h - start) / (0.97f - start), 0f, 1f);
            int at = Math.round(h * (line.size() - 1));
            Vector3f from = new Vector3f(line.get(at));
            Vector3f along = tangent(line, at);
            float spin = i * GOLDEN_ANGLE + (random.nextFloat() - 0.5f) * tree.wildness * 1.2f;
            Vector3f outward = perpendicular(along, spin);
            // The higher up, the closer to the trunk's own direction, as branches reach for the light.
            double angle = Math.toRadians(tree.branchAngle) * (1f - 0.35f * through);
            Vector3f direction = new Vector3f(along).mul((float) Math.cos(angle)).fma((float) Math.sin(angle), outward).normalize();

            float length = tree.branchLength * trunkLength * envelope(tree.crown, through)
                    * (1f + (random.nextFloat() - 0.5f) * tree.wildness);
            float thickness = TexturedMeshBuilder.taperedRadius(tree.trunkRadius, 0f, trunk.taper(), h);
            float radius = Math.max(0.015f, Math.min(thickness * 0.92f, thickness * tree.branchThickness));
            if (length > 0.05f) {
                grow(from, direction, length, length, radius, Math.max(0, tree.splits), Math.max(0, tree.subBranches), 1, random);
            }
        }
    }

    /**
     * How long branches are at a height through the crown, 0 at the lowest branch and 1 at the highest, as a share of the
     * longest.
     */
    static float envelope(MapTree.Crown crown, float through) {
        float t = Math.clamp(through, 0f, 1f);
        return switch (crown) {
            case ROUND -> Math.max(0.2f, (float) Math.sin(Math.PI * (0.12f + 0.86f * t)));
            case CONE -> Math.max(0.12f, 1f - t);
            case FLAME -> Math.max(0.15f, (float) Math.sin(Math.PI * Math.pow(t, 0.55f)));
            case UMBRELLA -> 0.25f + 0.75f * (float) Math.pow(t, 1.4f);
        };
    }

    /**
     * One length of a branch and all that grows from it.
     *
     * @param remaining how long the rest of this branch is, from here to its tips
     * @param whole     how long the branch was where it left the trunk
     * @param splits    how many forks it has yet to make
     */
    private void grow(Vector3f from, Vector3f direction, float remaining, float whole, float radius, int splits, int subs,
                      int level, Random random) {
        if (limbs.size() >= MAX_LIMBS) {
            return;
        }
        boolean forks = splits > 0;
        float section = forks ? remaining * 0.45f : remaining;

        // The limb's own curve, bending a little at each step: up or down as the Bend slider has it, and some way at random.
        List<Vector3f> points = new ArrayList<>(LIMB_POINTS);
        points.add(new Vector3f(from));
        Vector3f heading = new Vector3f(direction).normalize();
        float step = section / (LIMB_POINTS - 1);
        Vector3f at = new Vector3f(from);
        for (int k = 1; k < LIMB_POINTS; k++) {
            heading.add(0f, tree.branchBend * 0.3f, 0f).fma(tree.wildness * 0.22f, randomUnit(random)).normalize();
            at.fma(step, heading);
            points.add(new Vector3f(at));
        }

        float endRadius = forks ? radius * 0.72f : 0f;
        Limb limb = new Limb(points, radius, endRadius, 1f, level);
        limbs.add(limb);

        if (subs > 0 && level == 1) {
            growSubBranches(limb, whole, subs, random);
        }

        if (!forks) {
            ends.add(limb);
            return;
        }
        float spread = (float) Math.toRadians(tree.splitAngle);
        Vector3f axis = perpendicular(heading, random.nextFloat() * 6.2831855f);
        float rest = remaining - section;
        for (int side = -1; side <= 1; side += 2) {
            float turn = side * spread * (1f + (random.nextFloat() - 0.5f) * tree.wildness * 0.6f);
            Vector3f child = new Vector3f(heading).rotateAxis(turn, axis.x, axis.y, axis.z);
            float share = 1f + (random.nextFloat() - 0.5f) * tree.wildness * 0.5f;
            grow(at, child, rest * share, whole, endRadius * 0.85f, splits - 1, subs, level, random);
        }
    }

    /** Smaller branches leaving the side of a limb, as many as this limb's share of the whole branch's length earns. */
    private void growSubBranches(Limb limb, float whole, int subs, Random random) {
        float length = limb.length();
        int count = Math.round(subs * length / Math.max(0.01f, whole));
        if (count == 0 && random.nextFloat() < subs * length / Math.max(0.01f, whole)) {
            count = 1;
        }
        List<Vector3f> line = limb.points();
        for (int k = 0; k < count && limbs.size() < MAX_LIMBS; k++) {
            float t = 0.2f + 0.75f * (k + 0.5f) / count + (random.nextFloat() - 0.5f) * tree.wildness * 0.12f;
            t = Math.clamp(t, 0.05f, 0.98f);
            float place = t * (line.size() - 1);
            int index = Math.min((int) place, line.size() - 2);
            Vector3f from = new Vector3f(line.get(index)).lerp(line.get(index + 1), place - index);
            Vector3f along = new Vector3f(line.get(index + 1)).sub(line.get(index)).normalize();
            Vector3f side = perpendicular(along, k * GOLDEN_ANGLE + random.nextFloat() * tree.wildness * 2f);
            Vector3f direction = new Vector3f(along).mul(0.62f).fma(0.78f, side).normalize();
            float own = TexturedMeshBuilder.taperedRadius(limb.rootRadius(), limb.tipRadius(), limb.taper(), t);
            float size = tree.subLength * whole * (1f - 0.35f * t) * (1f + (random.nextFloat() - 0.5f) * tree.wildness);
            if (size > 0.04f) {
                // A sub-branch can fork once, but grows no sub-branches of its own.
                grow(from, direction, size, size, Math.max(0.012f, own * 0.6f), Math.min(1, tree.splits), 0, 2, random);
            }
        }
    }

    // --- Foliage ----------------------------------------------------------------------------------------

    private void hangMeatballs() {
        int perEnd = Math.max(0, tree.meatballs);
        float size = Math.max(0.05f, tree.meatballSize);
        if (perEnd == 0) {
            return;
        }
        Random random = new Random(tree.seed * 524287L + 11L);
        for (Limb limb : ends) {
            // The trunk carries just the one at its top.
            int count = limb.level() == 0 ? 1 : perEnd;
            float length = limb.length();
            float spacing = length <= 1e-3f ? 1f : size * 1.1f / length;
            for (int k = 0; k < count && meatballs.size() < MAX_MEATBALLS; k++) {
                float t = 1f - k * spacing;
                if (t < 0.15f) {
                    break;
                }
                float radius = size * (limb.level() >= 2 ? SUB_BRANCH_MEATBALL : 1f) * (float) Math.pow(0.85f, k)
                        * (1f - random.nextFloat() * tree.wildness * 0.35f);
                Vector3f center = pointOn(limb.points(), t).fma(radius * 0.25f * tree.wildness, randomUnit(random));
                float shade = 1f + (random.nextFloat() - 0.5f) * (0.08f + tree.wildness * 0.16f);
                meatballs.add(new Meatball(center, radius, shade));
            }
        }
    }

    private void fillWithLeaves() {
        if (meatballs.isEmpty() || tree.leaves <= 0) {
            return;
        }
        float size = Math.max(0.05f, tree.meatballSize);
        float scale = Math.min(1f, (float) MAX_LEAVES / (meatballs.size() * tree.leaves));
        int[][] near = neighbours();
        Random random = new Random(tree.seed * 2147483647L + 3L);
        Vector3f gradient = new Vector3f();
        Vector3f place = new Vector3f();
        float lowest = Math.max(0.45f, Math.clamp(tree.core, 0f, 0.9f));
        for (int b = 0; b < meatballs.size(); b++) {
            Meatball ball = meatballs.get(b);
            // Fewer on a smaller ball, by the surface there is to cover.
            float share = ball.radius() / size;
            int count = Math.max(1, Math.round(tree.leaves * share * share * scale));
            for (int n = 0; n < count && leaves.size() < MAX_LEAVES; n++) {
                // Where meatballs crowd together most places in one are buried in the others, so a leaf looks a while.
                for (int attempt = 0; attempt < PLACE_ATTEMPTS; attempt++) {
                    float depth = lowest + (1.25f - lowest) * (float) Math.pow(random.nextFloat(), 0.55f);
                    place.set(ball.center()).fma(depth * ball.radius(), randomUnit(random));
                    float field = field(place, near[b], gradient);
                    if (field < SURFACE || field > BURIED) {
                        continue;
                    }
                    leaves.add(leaf(place, gradient, field, ball, random));
                    break;
                }
            }
        }
    }

    /** For each meatball, the meatballs whose fields reach into its own, itself first. */
    private int[][] neighbours() {
        int count = meatballs.size();
        int[][] out = new int[count][];
        int[] scratch = new int[count];
        for (int a = 0; a < count; a++) {
            Meatball first = meatballs.get(a);
            int found = 0;
            scratch[found++] = a;
            for (int b = 0; b < count; b++) {
                Meatball second = meatballs.get(b);
                float reach = (first.radius() + second.radius()) * SUPPORT;
                if (b != a && first.center().distanceSquared(second.center()) < reach * reach) {
                    scratch[found++] = b;
                }
            }
            out[a] = java.util.Arrays.copyOf(scratch, found);
        }
        return out;
    }

    /** The meatballs' summed field at a place, and in {@code gradient} the way it rises fastest. */
    private float field(Vector3f place, int[] balls, Vector3f gradient) {
        float sum = 0f;
        gradient.zero();
        for (int index : balls) {
            Meatball ball = meatballs.get(index);
            float reach = ball.radius() * SUPPORT;
            float reach2 = reach * reach;
            float dx = place.x - ball.center().x;
            float dy = place.y - ball.center().y;
            float dz = place.z - ball.center().z;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < reach2) {
                float a = 1f - d2 / reach2;
                sum += a * a * a;
                float slope = -6f * a * a / reach2;
                gradient.add(dx * slope, dy * slope, dz * slope);
            }
        }
        return sum;
    }

    private Leaf leaf(Vector3f place, Vector3f gradient, float field, Meatball ball, Random random) {
        Vector3f normal = gradient.lengthSquared() > 1e-10f
                ? new Vector3f(gradient).negate().normalize() : new Vector3f(0f, 1f, 0f);
        // Each card turned its own way, loosely toward the outside: the eye reads a mass of leaves, not a shell of tiles.
        Vector3f facing = new Vector3f(normal).fma(0.85f, randomUnit(random)).normalize();
        Vector3f up = new Vector3f(0f, 1f, 0f).fma(-facing.y, facing);
        if (up.lengthSquared() < 1e-4f) {
            up.set(1f, 0f, 0f).fma(-facing.x, facing);
        }
        up.normalize().rotateAxis((random.nextFloat() - 0.5f) * 1.9f, facing.x, facing.y, facing.z);
        Vector3f right = new Vector3f(up).cross(facing).normalize();
        float half = tree.leafSize * 0.5f * (0.8f + random.nextFloat() * 0.4f);
        float buried = Math.clamp((field - SURFACE) / (BURIED - SURFACE), 0f, 1f);
        // A little of its own, so clumps side by side at one depth still show apart.
        float own = 1f + (random.nextFloat() - 0.5f) * CARD_SHADE;
        float shade = ball.shade() * (1f - 0.42f * buried) * own;
        return new Leaf(new Vector3f(place), right.mul(half), up.mul(half), normal, shade);
    }

    // --- Lines ------------------------------------------------------------------------------------------

    private static float lengthOf(MapTree tree) {
        List<MapTree.Point> points = tree.points();
        float total = 0f;
        for (int k = 1; k < points.size(); k++) {
            MapTree.Point a = points.get(k - 1);
            MapTree.Point b = points.get(k);
            total += (float) Math.sqrt(square(b.x - a.x) + square(b.y - a.y) + square(b.z - a.z));
        }
        return total;
    }

    private static Vector3f tangent(List<Vector3f> line, int at) {
        Vector3f from = line.get(Math.max(0, at - 1));
        Vector3f to = line.get(Math.min(line.size() - 1, at + 1));
        Vector3f out = new Vector3f(to).sub(from);
        return out.lengthSquared() < 1e-10f ? new Vector3f(0f, 1f, 0f) : out.normalize();
    }

    private static Vector3f pointOn(List<Vector3f> line, float t) {
        float place = Math.clamp(t, 0f, 1f) * (line.size() - 1);
        int index = Math.min((int) place, line.size() - 2);
        return new Vector3f(line.get(index)).lerp(line.get(index + 1), place - index);
    }

    /** A unit vector square to a direction, turned {@code spin} radians about it. */
    private static Vector3f perpendicular(Vector3f direction, float spin) {
        Vector3f basis = Math.abs(direction.y) < 0.9f ? new Vector3f(0f, 1f, 0f) : new Vector3f(1f, 0f, 0f);
        Vector3f side = new Vector3f(direction).cross(basis).normalize();
        return side.rotateAxis(spin, direction.x, direction.y, direction.z).normalize();
    }

    private static Vector3f randomUnit(Random random) {
        float y = random.nextFloat() * 2f - 1f;
        float angle = random.nextFloat() * 6.2831855f;
        float ring = (float) Math.sqrt(Math.max(0f, 1f - y * y));
        return new Vector3f(ring * (float) Math.cos(angle), y, ring * (float) Math.sin(angle));
    }

    private static float square(float x) {
        return x * x;
    }

    private static float cube(float x) {
        return x * x * x;
    }
}
