package org.zeronura.engine.physics;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A box that tumbles: the engine's first body with an <b>orientation</b>.
 *
 * <p>Everything moving in this world so far has been upright by construction. {@link CharacterBody} is an
 * axis-aligned box with a position and no rotation at all, which is exactly right for a person and for a
 * creature — a walking body that rolls over is a bug — and it is why the collision resolution it uses can
 * be per-axis and normal-free. A rock knocked off a ledge is the opposite case: which way it is facing is
 * most of what makes it read as a rock rather than as a sliding crate, so this carries a quaternion, an
 * angular velocity and an inertia tensor, and is resolved with impulses at contact points.
 *
 * <p><b>The shape is still a box.</b> {@link AABB} is the engine's one collision primitive and this does not
 * change that — what is new is that the box has an orientation, so its collision test is its eight corners
 * against the world rather than a min/max overlap. A convex hull or a sphere would each be a second shape
 * with a second set of contact-generation rules; a box tumbles convincingly and every prop that would use
 * one is already sized as a box by {@code ObjectHitboxes}.
 *
 * <h2>Sleeping is not an optimisation here, it is what stops the jitter</h2>
 * Contacts are generated from a binary field by probing, so a body resting on the ground is re-deciding
 * every frame, from scratch, exactly where the ground is. The residual disagreement between one frame's
 * answer and the next is small but never zero, and left running it shows as a rock that shivers forever.
 * Freezing a body whose motion has stayed under the thresholds for {@link RigidBodyWorld#SLEEP_SECONDS} is
 * the fix; the saved CPU is a side benefit.
 */
public class RigidBody {

    private final Vector3f halfExtents;
    private final float mass;
    private final float invMass;

    /**
     * Inverse inertia in the body's own frame, which for a box is diagonal — so three floats rather than a
     * matrix. The world-space tensor is {@code R · I⁻¹ · Rᵀ} and is rebuilt per step from the orientation.
     */
    private final Vector3f invInertiaLocal;

    private final Vector3f position = new Vector3f();
    private final Quaternionf orientation = new Quaternionf();
    private final Vector3f velocity = new Vector3f();
    private final Vector3f angularVelocity = new Vector3f();

    /** How much of an impact comes back as bounce, 0 (dead) to 1 (perfectly elastic). */
    private float restitution = 0.18f;
    /** Coulomb friction coefficient against terrain and hitboxes. */
    private float friction = 0.55f;
    private float linearDamping = 0.05f;
    /**
     * Angular damping is far stronger than linear on purpose: nothing here spins in a vacuum, and a box
     * that keeps its spin indefinitely reads as weightless. It is also the cheapest guard against the
     * energy a corner-contact impulse can inject on a bad frame.
     */
    private float angularDamping = 0.6f;

    /**
     * Ratio of the fluid's density to this body's. Above 1 floats, below 1 sinks — so it is the one number
     * that says whether a thing is wood or stone, and it is on the body rather than on the fluid because
     * what a lump does in water is a property of the lump.
     *
     * <p>Defaults just under 1: a body with no opinion sinks slowly rather than either plummeting or
     * bobbing, which is the least surprising thing for a caller who has not thought about it.
     */
    private float buoyancy = 0.85f;

    /** Filled in by {@link RigidBodyWorld} each step: how much of the body is under a fluid surface. */
    private float submersion;

    private boolean sleeping;
    private float idleSeconds;

    private final Matrix3f rotationScratch = new Matrix3f();
    private final Matrix3f invInertiaScratch = new Matrix3f();
    private final Vector3f leverScratch = new Vector3f();
    private final Vector3f torqueScratch = new Vector3f();

    /**
     * @param halfExtents half-size along each of the body's own axes
     * @param mass        kilograms, in the loose sense this engine uses units; must be positive
     */
    public RigidBody(Vector3f centre, Vector3f halfExtents, float mass) {
        if (mass <= 0f) {
            throw new IllegalArgumentException("rigid body mass must be positive: " + mass);
        }
        this.halfExtents = new Vector3f(halfExtents);
        this.mass = mass;
        this.invMass = 1f / mass;
        this.position.set(centre);

        // Solid box about its centre: I = m/12 · (h² + d²) per axis, over the *full* extents.
        float w = halfExtents.x * 2f;
        float h = halfExtents.y * 2f;
        float d = halfExtents.z * 2f;
        float k = mass / 12f;
        this.invInertiaLocal = new Vector3f(
                1f / (k * (h * h + d * d)),
                1f / (k * (w * w + d * d)),
                1f / (k * (w * w + h * h)));
    }

    public Vector3f position() {
        return position;
    }

    public Quaternionf orientation() {
        return orientation;
    }

    public Vector3f velocity() {
        return velocity;
    }

    public Vector3f angularVelocity() {
        return angularVelocity;
    }

    public Vector3f halfExtents() {
        return new Vector3f(halfExtents);
    }

    public float mass() {
        return mass;
    }

    public float invMass() {
        return invMass;
    }

    public float restitution() {
        return restitution;
    }

    public float friction() {
        return friction;
    }

    public float linearDamping() {
        return linearDamping;
    }

    public float angularDamping() {
        return angularDamping;
    }

    public float buoyancy() {
        return buoyancy;
    }

    public float submersion() {
        return submersion;
    }

    public boolean isSleeping() {
        return sleeping;
    }

    public RigidBody withMaterial(float restitution, float friction) {
        this.restitution = Math.clamp(restitution, 0f, 1f);
        this.friction = Math.max(0f, friction);
        return this;
    }

    public RigidBody withDamping(float linear, float angular) {
        this.linearDamping = Math.max(0f, linear);
        this.angularDamping = Math.max(0f, angular);
        return this;
    }

    public RigidBody withBuoyancy(float buoyancy) {
        this.buoyancy = Math.max(0f, buoyancy);
        return this;
    }

    void setSubmersion(float submersion) {
        this.submersion = submersion;
    }

    /**
     * Wakes a sleeping body. Anything that changes the world under a body — digging the ground out from
     * beneath it, dropping something on it — has to call this, because a sleeping body is not looking.
     */
    public void wake() {
        sleeping = false;
        idleSeconds = 0f;
    }

    void sleep() {
        sleeping = true;
        idleSeconds = 0f;
        velocity.zero();
        angularVelocity.zero();
    }

    void addIdleTime(float dt) {
        idleSeconds += dt;
    }

    float idleSeconds() {
        return idleSeconds;
    }

    void resetIdleTime() {
        idleSeconds = 0f;
    }

    /** World-space inverse inertia, {@code R · I⁻¹ · Rᵀ}, rebuilt from the current orientation. */
    public Matrix3f invInertiaWorld(Matrix3f dest) {
        rotationScratch.set(orientation);
        // R · diag(I⁻¹) scales R's columns, and multiplying by Rᵀ completes the similarity transform.
        invInertiaScratch.set(rotationScratch).scale(invInertiaLocal);
        return invInertiaScratch.mul(rotationScratch.transpose(), dest);
    }

    /** Writes the eight corners of the oriented box in world space. {@code out} must hold eight vectors. */
    public void corners(Vector3f[] out) {
        int i = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    out[i].set(halfExtents.x * sx, halfExtents.y * sy, halfExtents.z * sz);
                    orientation.transform(out[i]).add(position);
                    i++;
                }
            }
        }
    }

    /** Velocity of the material point of the body currently at {@code worldPoint}: {@code v + ω × r}. */
    public Vector3f pointVelocity(Vector3f worldPoint, Vector3f dest) {
        dest.set(worldPoint).sub(position);
        angularVelocity.cross(dest, dest);
        return dest.add(velocity);
    }

    /**
     * Applies an impulse at a world point, changing both linear and angular velocity.
     *
     * <p>Scratch vectors are fields rather than locals because this is the innermost call in the solver —
     * every contact, every iteration, every substep — and it is the one place in physics where allocation
     * would actually show up in a profile.
     */
    public void applyImpulse(Vector3f impulse, Vector3f worldPoint, Matrix3f invInertiaWorld) {
        velocity.fma(invMass, impulse);
        leverScratch.set(worldPoint).sub(position);
        leverScratch.cross(impulse, torqueScratch);
        angularVelocity.add(invInertiaWorld.transform(torqueScratch));
    }

    /**
     * The world-axis-aligned box that contains the oriented one.
     *
     * <p>Uses the absolute-value trick (the extent along a world axis is the box's half-extents dotted with
     * the absolute row of its rotation) rather than transforming eight corners and taking a min/max —
     * cheaper, and this is called per body per frame by the broad phase.
     */
    public AABB bounds() {
        rotationScratch.set(orientation);
        return AABB.ofOrientedBox(position.x, position.y, position.z, halfExtents, rotationScratch);
    }

    /** Radius of the sphere containing the box, for cheap distance culling. */
    public float boundingRadius() {
        return halfExtents.length();
    }

    /** The model matrix a renderer draws this body with. */
    public Matrix4f transform(Matrix4f dest) {
        return dest.translationRotateScale(
                position.x, position.y, position.z,
                orientation.x, orientation.y, orientation.z, orientation.w,
                1f, 1f, 1f);
    }
}
