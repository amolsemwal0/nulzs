package org.zeronura.engine.physics;

import org.joml.Vector3f;

import java.util.Collection;
import java.util.List;

/**
 * Moves {@link CharacterBody}s through a {@link SolidField} and a set of solid hitboxes: gravity,
 * collision, ground contact and stepping over small ledges.
 *
 * <h2>Sampled collision, not surface heights</h2>
 * Terrain is a volumetric density field, so there is no single "ground height" to stand a body on — the
 * old approach of pinning the character to the procedural surface is exactly why terraforming left it
 * hovering over a hole it had just dug. Instead a body's box is tested by <b>sampling the field on a
 * grid across the box</b>. That costs a handful of field evaluations per test, which is why every test
 * here is written to bail early and why the sweep uses a bisection rather than small fixed steps.
 *
 * <h2>Axis-separated sweeps</h2>
 * Each axis is resolved independently (X, then Z, then Y) against a fresh test of the world. Resolving
 * a 3D move as one vector needs a contact normal to slide along; per-axis resolution gets sliding for
 * free — walking into a wall at an angle keeps the component parallel to it — and cannot produce the
 * "caught on a seam" behaviour that plagues normal-based resolution on a dual-meshed surface.
 *
 * <h2>Substepping</h2>
 * A long frame (a chunk upload hitch, a debugger pause) would otherwise move the body far enough in one
 * go to sweep past thin terrain entirely. The step is split so no substep exceeds {@link #MAX_SUBSTEP}.
 */
public class PhysicsWorld {

    /** Deliberately stronger than real gravity: 9.81 reads as floaty and moon-like at this scale. */
    public static final float GRAVITY = -24.0f;

    /** Caps fall speed so a long drop cannot outrun the sweep resolution. */
    public static final float TERMINAL_VELOCITY = -55.0f;

    /** Ledges up to this tall are walked over rather than blocking; taller ones need a jump. */
    public static final float STEP_HEIGHT = 0.6f;

    /**
     * How far the body is pulled back down after a blocked-then-stepped-up move, and when walking off a
     * gentle downslope. Must exceed {@link #STEP_HEIGHT}, or stepping up a ledge would leave the body
     * hovering the step height above it until gravity caught up, which reads as bouncing downhill.
     */
    private static final float SNAP_DISTANCE = 0.65f;

    /** Downward probe distance for the grounded test. Small enough not to grab ground while jumping. */
    private static final float GROUND_PROBE = 0.05f;

    /** Gap left between a body and whatever stopped it, so contact never reads as penetration. */
    private static final float SKIN = 0.002f;

    /** Grid spacing for sampling the field across a body's box. Finer costs field evaluations. */
    private static final float SAMPLE_SPACING = 0.6f;

    /** Bisection depth for a blocked sweep: 6 halvings resolve a 1-unit move to under 2 cm. */
    private static final int SWEEP_ITERATIONS = 6;

    private static final float MAX_SUBSTEP = 1.0f / 60.0f;

    /** Ceiling on how far an embedded body is pushed out; beyond this it is buried, not nudged. */
    private static final float MAX_UNSTICK = 2.5f;
    private static final float UNSTICK_STEP = 0.1f;

    private final float gravity;
    private final float stepHeight;

    public PhysicsWorld() {
        this(GRAVITY, STEP_HEIGHT);
    }

    public PhysicsWorld(float gravity, float stepHeight) {
        this.gravity = gravity;
        this.stepHeight = stepHeight;
    }

    private record Sweep(float moved, boolean blocked) {
    }

    /** Advances a body by {@code dt}, colliding against terrain and the given solid hitboxes. */
    public void step(CharacterBody body, SolidField field, Collection<AABB> obstacles, float dt) {
        if (dt <= 0f) {
            return;
        }
        Collection<AABB> solids = obstacles == null ? List.of() : obstacles;

        int substeps = Math.max(1, (int) Math.ceil(dt / MAX_SUBSTEP));
        float substepDt = dt / substeps;
        for (int i = 0; i < substeps; i++) {
            substep(body, field, solids, substepDt);
        }
    }

    private void substep(CharacterBody body, SolidField field, Collection<AABB> solids, float dt) {
        // Terrain can appear inside the body between frames — filling a hole the player stands in does
        // exactly that — and a body starting the step embedded would find every direction blocked.
        unstick(body, field, solids);

        Vector3f velocity = body.velocity();
        velocity.y = Math.max(velocity.y + gravity * dt, TERMINAL_VELOCITY);

        boolean wasOnGround = body.isOnGround();

        moveHorizontal(body, 0, velocity.x * dt, field, solids, wasOnGround);
        moveHorizontal(body, 2, velocity.z * dt, field, solids, wasOnGround);

        Sweep vertical = sweep(body, 1, velocity.y * dt, field, solids);
        body.position().y += vertical.moved();
        if (vertical.blocked()) {
            velocity.y = 0f;
        }

        boolean grounded = !isClear(body.boundsAt(
                body.position().x, body.position().y - GROUND_PROBE, body.position().z), field, solids);

        // Walking off a downslope or over a stepped-up ledge leaves the body briefly airborne. Without
        // the snap it would arc off every bump instead of following the ground.
        if (!grounded && wasOnGround && velocity.y <= 0f) {
            Sweep snap = sweep(body, 1, -SNAP_DISTANCE, field, solids);
            if (snap.blocked()) {
                body.position().y += snap.moved();
                velocity.y = 0f;
                grounded = true;
            }
        }

        body.setOnGround(grounded);
        if (grounded && velocity.y < 0f) {
            velocity.y = 0f;
        }
    }

    /**
     * Moves along one horizontal axis, stepping up over a low obstruction if the body is on the ground.
     *
     * <p>The step-up does not search for the ledge's height: it raises the body by the full step height
     * and lets the ground snap at the end of the substep drop it onto whatever it is now standing over.
     * One trial position instead of a scan, and it lands correctly on ledges of any height up to the
     * limit.
     */
    private void moveHorizontal(CharacterBody body, int axis, float delta, SolidField field,
                                Collection<AABB> solids, boolean canStepUp) {
        Sweep result = sweep(body, axis, delta, field, solids);
        if (!result.blocked()) {
            applyAxis(body.position(), axis, result.moved());
            return;
        }

        if (canStepUp) {
            Vector3f p = body.position();
            AABB raised = body.boundsAt(p.x, p.y + stepHeight, p.z);
            if (isClear(raised, field, solids)) {
                float wanted = delta;
                AABB stepped = axis == 0
                        ? body.boundsAt(p.x + wanted, p.y + stepHeight, p.z)
                        : body.boundsAt(p.x, p.y + stepHeight, p.z + wanted);
                if (isClear(stepped, field, solids)) {
                    p.y += stepHeight;
                    applyAxis(p, axis, wanted);
                    return;
                }
            }
        }

        applyAxis(body.position(), axis, result.moved());
        // Zeroing the blocked component stops the body accumulating speed into a wall it is pressed
        // against, which would fling it away the moment the wall is dug out.
        applyAxis(body.velocity(), axis, 0f, true);
    }

    /**
     * How far the body can move along one axis before something stops it.
     *
     * <p>Bisection rather than fixed micro-steps: a blocked sweep costs a fixed
     * {@link #SWEEP_ITERATIONS} field tests regardless of how fast the body is going, so a fall at
     * terminal velocity is no more expensive than a walk.
     */
    private Sweep sweep(CharacterBody body, int axis, float delta, SolidField field, Collection<AABB> solids) {
        if (delta == 0f) {
            return new Sweep(0f, false);
        }
        if (isClear(offsetBounds(body, axis, delta), field, solids)) {
            return new Sweep(delta, false);
        }

        float clear = 0f;
        float blocked = delta;
        for (int i = 0; i < SWEEP_ITERATIONS; i++) {
            float mid = (clear + blocked) * 0.5f;
            if (isClear(offsetBounds(body, axis, mid), field, solids)) {
                clear = mid;
            } else {
                blocked = mid;
            }
        }

        float skin = Math.copySign(SKIN, delta);
        float moved = Math.abs(clear) > SKIN ? clear - skin : 0f;
        return new Sweep(moved, true);
    }

    private static AABB offsetBounds(CharacterBody body, int axis, float delta) {
        Vector3f p = body.position();
        return switch (axis) {
            case 0 -> body.boundsAt(p.x + delta, p.y, p.z);
            case 1 -> body.boundsAt(p.x, p.y + delta, p.z);
            default -> body.boundsAt(p.x, p.y, p.z + delta);
        };
    }

    private static void applyAxis(Vector3f vector, int axis, float delta) {
        applyAxis(vector, axis, delta, false);
    }

    private static void applyAxis(Vector3f vector, int axis, float value, boolean set) {
        switch (axis) {
            case 0 -> vector.x = set ? value : vector.x + value;
            case 1 -> vector.y = set ? value : vector.y + value;
            default -> vector.z = set ? value : vector.z + value;
        }
    }

    /**
     * Lifts a body that a box has appeared overlapping — a building piece placed on the player — straight up
     * onto the box's top face. A no-op when the two do not overlap. Deliberately <b>always upward</b>, never
     * sideways: a directional least-penetration push reads better in theory but keeps finding pockets where
     * the body ends up shoved into terrain or another piece and stuck, so the rule is simply "you end up
     * standing on what you built". Vertical velocity is zeroed and the body is marked grounded so it settles
     * on the new surface rather than being flung.
     *
     * <p>Static and box-vs-box: the same "get unstuck" idea as {@link #unstick}, but against a single known
     * box and with no cap, which is what {@code unstick}'s straight-up-capped-at-{@link #MAX_UNSTICK} path
     * cannot do for a piece taller than the cap (a 5 m wall).
     */
    public static void liftOntoTopOf(CharacterBody body, AABB box) {
        if (!body.bounds().intersects(box)) {
            return;
        }
        body.position().y = box.maxY() + SKIN;
        body.velocity().y = 0f;
        body.setOnGround(true);
    }

    /** Pushes a body that starts the step inside solid matter straight up until it is free. */
    private void unstick(CharacterBody body, SolidField field, Collection<AABB> solids) {
        if (isClear(body.bounds(), field, solids)) {
            return;
        }
        Vector3f p = body.position();
        for (float lift = UNSTICK_STEP; lift <= MAX_UNSTICK; lift += UNSTICK_STEP) {
            if (isClear(body.boundsAt(p.x, p.y + lift, p.z), field, solids)) {
                p.y += lift;
                body.velocity().y = 0f;
                body.setOnGround(true);
                return;
            }
        }
    }

    /** Whether a box is free of both terrain and every solid hitbox. */
    public boolean isClear(AABB box, SolidField field, Collection<AABB> solids) {
        for (AABB solid : solids) {
            if (box.intersects(solid)) {
                return false;
            }
        }
        return !intersectsField(box, field);
    }

    /**
     * Whether terrain intrudes into a box, by sampling the field across it.
     *
     * <p>Samples are inset by the skin width: a box parked exactly against a surface has its face on the
     * isosurface, and sampling the face itself would flip-flop between solid and clear on rounding.
     */
    public static boolean intersectsField(AABB box, SolidField field) {
        AABB inner = box.expanded(-SKIN);
        int countX = sampleCount(inner.sizeX());
        int countY = sampleCount(inner.sizeY());
        int countZ = sampleCount(inner.sizeZ());

        for (int ix = 0; ix < countX; ix++) {
            float x = lerp(inner.minX(), inner.maxX(), ix, countX);
            for (int iz = 0; iz < countZ; iz++) {
                float z = lerp(inner.minZ(), inner.maxZ(), iz, countZ);
                for (int iy = 0; iy < countY; iy++) {
                    float y = lerp(inner.minY(), inner.maxY(), iy, countY);
                    if (field.isSolid(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int sampleCount(float size) {
        return Math.max(2, (int) Math.ceil(size / SAMPLE_SPACING) + 1);
    }

    private static float lerp(float min, float max, int index, int count) {
        return min + (max - min) * index / (count - 1);
    }

    /** Hitbox-against-hitbox test: the first box in {@code others} that overlaps, or null. */
    public static AABB firstOverlap(AABB box, Iterable<AABB> others) {
        for (AABB other : others) {
            if (box.intersects(other)) {
                return other;
            }
        }
        return null;
    }

    public static boolean overlapsAny(AABB box, Iterable<AABB> others) {
        return firstOverlap(box, others) != null;
    }
}
