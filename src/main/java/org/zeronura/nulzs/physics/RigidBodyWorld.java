package org.zeronura.nulzs.physics;

import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Moves {@link RigidBody}s: gravity, buoyancy, tumbling, and impulse-resolved contacts against terrain and
 * hitboxes.
 *
 * <p>The rigid counterpart to {@link PhysicsWorld}, and deliberately a separate class rather than another
 * method on it, because the two resolve collisions in ways that contradict each other. A character is
 * resolved <b>per axis against a fresh test of the world</b>, which is what gives it wall-sliding with no
 * contact normal and no chance of snagging on a mesh seam — and which cannot produce a torque, because an
 * axis has no lever arm. A rigid body is resolved <b>as a set of contact points with normals</b>, because
 * the whole point of it is that hitting a ledge with one corner makes it spin. Sharing a solver between
 * those two would mean one of them getting the wrong one.
 *
 * <h2>Sequential impulses</h2>
 * Contacts are gathered from the body's eight corners, then solved by iterating over them a few times,
 * applying a normal impulse (with restitution) and a Coulomb-clamped friction impulse at each. It is the
 * standard game-physics solver and it converges well enough for a box on a hillside in three or four
 * passes; what it cannot do is a stack of bodies leaning on each other, which nothing here builds.
 *
 * <h2>Corners are the contact set, and that is a real limit</h2>
 * A box resting flat has four corners in the ground and behaves; a box that has come to rest exactly on an
 * <i>edge</i> of terrain between two of its corners has none, and will fall through until a corner catches.
 * With terrain cells a unit across and debris a fraction of that, this does not arise in practice — but a
 * body larger than the terrain's features would need face samples as well.
 */
public class RigidBodyWorld {

    /** Shared with the character solver so a rock and a player fall at the same rate. */
    public static final float GRAVITY = PhysicsWorld.GRAVITY;

    private static final float MAX_SUBSTEP = 1.0f / 60.0f;

    /** Passes over the contact list per substep. Three is enough for a box settling on uneven ground. */
    private static final int SOLVER_ITERATIONS = 4;

    /**
     * Approach speed below which a contact is treated as dead rather than bouncy.
     *
     * <p>Without it a body resting under gravity re-acquires a tiny approach velocity every substep and
     * bounces it straight back, so it hums in place at the amplitude of one substep's fall — visibly, and
     * forever, since the energy is being re-supplied.
     */
    private static final float RESTITUTION_THRESHOLD = 1.0f;

    /** How much of a penetration is corrected per substep, and how much is left alone. */
    private static final float CORRECTION_RATE = 0.6f;
    private static final float CORRECTION_SLOP = 0.01f;

    /** Cap on how far a contact is considered to reach into solid matter. */
    private static final float MAX_PENETRATION = 0.6f;

    /** Motion under both of these for {@link #SLEEP_SECONDS} puts a body to sleep. See {@link RigidBody}. */
    public static final float SLEEP_LINEAR = 0.12f;
    public static final float SLEEP_ANGULAR = 0.35f;
    public static final float SLEEP_SECONDS = 0.5f;

    /**
     * How hard a submerged body is pushed up, as a multiple of gravity per unit of {@code buoyancy}.
     *
     * <p>Exactly 1 means a body of {@code buoyancy} 1 hangs weightless at any depth, which is the physically
     * honest reading of neutral density and looks wrong in a game — a thing dropped in a pond should visibly
     * find the surface. Slightly over 1 gives the same body a gentle rise.
     */
    private static final float BUOYANCY_GAIN = 1.08f;

    /** Drag while submerged, per second, linear and angular. Water is thick compared to air. */
    private static final float FLUID_LINEAR_DRAG = 2.6f;
    private static final float FLUID_ANGULAR_DRAG = 3.2f;

    private final float gravity;

    /** Reused per step so a frame of physics allocates nothing per body. */
    private final Vector3f[] cornerScratch = new Vector3f[8];
    private final List<Contact> contacts = new ArrayList<>(16);
    private final Matrix3f invInertia = new Matrix3f();
    private final Vector3f normalScratch = new Vector3f();
    private final Vector3f pointVelocity = new Vector3f();
    private final Vector3f tangent = new Vector3f();
    private final Vector3f impulse = new Vector3f();
    private final Vector3f lever = new Vector3f();
    private final Vector3f cross = new Vector3f();
    private final Vector3f correction = new Vector3f();

    public RigidBodyWorld() {
        this(GRAVITY);
    }

    public RigidBodyWorld(float gravity) {
        this.gravity = gravity;
        for (int i = 0; i < cornerScratch.length; i++) {
            cornerScratch[i] = new Vector3f();
        }
    }

    /**
     * How far ahead of a surface a contact is created.
     *
     * <p>Sized so that a body which has just been pushed out of the ground still has one: below that, a
     * resting body's contacts blink out on the frame after they are resolved, it falls freely for a
     * substep, and it spends forever alternating between the two states — visibly humming, and never still
     * enough to fall asleep.
     */
    private static final float CONTACT_MARGIN = 0.08f;

    /**
     * One contact point: where, which way is out, and the <b>signed</b> gap — positive for a surface not
     * reached yet, negative for one already crossed.
     */
    private static final class Contact {
        final Vector3f point = new Vector3f();
        final Vector3f normal = new Vector3f();
        float separation;

        float depth() {
            return Math.max(0f, -separation);
        }
    }

    private final List<Contact> pool = new ArrayList<>(16);

    /** Advances a body through terrain and hitboxes, with no fluid in the world. */
    public void step(RigidBody body, SolidField field, Collection<AABB> obstacles, float dt) {
        step(body, field, obstacles, FluidField.EMPTY, dt);
    }

    /**
     * Advances a body by {@code dt}.
     *
     * <p>A sleeping body is skipped entirely — it does not even read the world, which is what makes a field
     * of settled debris free. Whatever changes the world under it is responsible for calling
     * {@link RigidBody#wake()}.
     */
    public void step(RigidBody body, SolidField field, Collection<AABB> obstacles, FluidField fluid, float dt) {
        if (dt <= 0f || body.isSleeping()) {
            return;
        }
        Collection<AABB> solids = obstacles == null ? List.of() : obstacles;
        int substeps = Math.max(1, (int) Math.ceil(dt / MAX_SUBSTEP));
        float substepDt = dt / substeps;
        for (int i = 0; i < substeps; i++) {
            substep(body, field, solids, fluid, substepDt);
        }
    }

    private void substep(RigidBody body, SolidField field, Collection<AABB> solids, FluidField fluid, float dt) {
        applyForces(body, fluid, dt);

        body.position().fma(dt, body.velocity());
        Vector3f w = body.angularVelocity();
        body.orientation().integrate(dt, w.x, w.y, w.z).normalize();

        gatherContacts(body, field, solids);
        if (!contacts.isEmpty()) {
            body.invInertiaWorld(invInertia);
            for (int iteration = 0; iteration < SOLVER_ITERATIONS; iteration++) {
                for (Contact contact : contacts) {
                    resolve(body, contact, dt);
                }
            }
            pushOut(body);
        }

        updateSleep(body, dt);
    }

    /**
     * Gravity, buoyancy, drag and damping.
     *
     * <p>Buoyancy is applied as a fraction of gravity scaled by how much of the body is under the surface,
     * rather than as a force computed from a displaced volume: the submerged fraction is measured from the
     * body's world-aligned box, which is an approximation anyway, and a second approximation dressed up in
     * density units would only look more precise than it is.
     */
    private void applyForces(RigidBody body, FluidField fluid, float dt) {
        Vector3f v = body.velocity();
        v.y += gravity * dt;

        float submersion = submergedFraction(body, fluid);
        body.setSubmersion(submersion);
        if (submersion > 0f) {
            v.y -= gravity * submersion * body.buoyancy() * BUOYANCY_GAIN * dt;
            float drag = 1f / (1f + FLUID_LINEAR_DRAG * submersion * dt);
            v.mul(drag);
            body.angularVelocity().mul(1f / (1f + FLUID_ANGULAR_DRAG * submersion * dt));
        }

        v.mul(1f / (1f + body.linearDamping() * dt));
        body.angularVelocity().mul(1f / (1f + body.angularDamping() * dt));

        float speed = v.length();
        if (speed > -PhysicsWorld.TERMINAL_VELOCITY) {
            v.mul(-PhysicsWorld.TERMINAL_VELOCITY / speed);
        }
    }

    /**
     * How much of the body is under the fluid surface, 0 to 1.
     *
     * <p>Probed at the body's <b>centre first and its underside second</b>, and it has to be both: a crate
     * floating with its middle in the air is not in any fluid body at its centre, so a centre-only probe
     * would say it was dry and let it sink through its own wake.
     */
    private static float submergedFraction(RigidBody body, FluidField fluid) {
        if (fluid == FluidField.EMPTY) {
            return 0f;
        }
        AABB box = body.bounds();
        Vector3f p = body.position();
        float level = fluid.surfaceLevelAt(p.x, p.y, p.z);
        if (Float.isNaN(level)) {
            level = fluid.surfaceLevelAt(p.x, box.minY() + 0.01f, p.z);
        }
        if (Float.isNaN(level)) {
            return 0f;
        }
        float height = box.maxY() - box.minY();
        if (height <= 0f) {
            return 0f;
        }
        return Math.clamp((level - box.minY()) / height, 0f, 1f);
    }

    /**
     * Every corner of the body that is inside a surface or within {@link #CONTACT_MARGIN} of one.
     *
     * <p>Terrain contacts cost a 26-sample normal each, so this runs once per substep and the solver's
     * several passes then reuse the result — the contact set is treated as fixed for the substep, which is
     * the usual bargain and is why the margin has to be wide enough to cover a substep's worth of motion.
     */
    private void gatherContacts(RigidBody body, SolidField field, Collection<AABB> solids) {
        recycle();
        body.corners(cornerScratch);
        for (Vector3f corner : cornerScratch) {
            for (AABB solid : solids) {
                float gap = FieldContact.boxDistance(solid, corner.x, corner.y, corner.z, normalScratch);
                if (gap > CONTACT_MARGIN) {
                    continue;
                }
                if (gap > 0f) {
                    addContact(corner, normalScratch, gap);
                } else {
                    float depth = FieldContact.boxEscape(solid, corner.x, corner.y, corner.z, normalScratch);
                    addContact(corner, normalScratch, -depth);
                }
            }

            FieldContact.normalAt(field, corner.x, corner.y, corner.z, normalScratch);
            float separation = FieldContact.signedDistance(field, corner.x, corner.y, corner.z,
                    normalScratch, MAX_PENETRATION);
            if (separation < CONTACT_MARGIN) {
                addContact(corner, normalScratch, separation);
            }
        }
    }

    private void recycle() {
        pool.addAll(contacts);
        contacts.clear();
    }

    private void addContact(Vector3f point, Vector3f normal, float separation) {
        Contact contact = pool.isEmpty() ? new Contact() : pool.remove(pool.size() - 1);
        contact.point.set(point);
        contact.normal.set(normal);
        contact.separation = separation;
        contacts.add(contact);
    }

    /**
     * One contact's normal and friction impulses.
     *
     * <p>Friction is applied along the tangential part of the contact velocity and clamped to the friction
     * coefficient times the normal impulse just applied — the Coulomb cone, done the cheap way. That is
     * what makes a box slide down a steep slope and stick on a gentle one, without either being written as
     * a rule about slopes.
     */
    private void resolve(RigidBody body, Contact contact, float dt) {
        body.pointVelocity(contact.point, pointVelocity);
        float normalSpeed = pointVelocity.dot(contact.normal);

        // How fast the point is allowed to keep closing: exactly enough to arrive at the surface at the end
        // of the substep and no faster. A contact already touching — within the slop — allows nothing, which
        // is what turns a resting body genuinely static instead of letting it re-acquire a substep of fall
        // speed forever. Measured against the slop rather than against zero so a body settles the last
        // millimetre rather than stopping a contact margin above the ground and hovering there.
        float allowed = contact.separation > CORRECTION_SLOP
                ? -(contact.separation - CORRECTION_SLOP) / dt
                : 0f;
        if (normalSpeed >= allowed) {
            return;
        }

        float effectiveMass = effectiveMass(body, contact.point, contact.normal);
        if (effectiveMass <= 0f) {
            return;
        }
        float restitution = normalSpeed < -RESTITUTION_THRESHOLD ? body.restitution() : 0f;
        float jn = -(1f + restitution) * (normalSpeed - allowed) / effectiveMass;
        impulse.set(contact.normal).mul(jn);
        body.applyImpulse(impulse, contact.point, invInertia);

        body.pointVelocity(contact.point, pointVelocity);
        tangent.set(pointVelocity).fma(-pointVelocity.dot(contact.normal), contact.normal);
        float tangentSpeed = tangent.length();
        if (tangentSpeed < 1e-4f) {
            return;
        }
        tangent.div(tangentSpeed);
        float tangentMass = effectiveMass(body, contact.point, tangent);
        if (tangentMass <= 0f) {
            return;
        }
        float jt = Math.clamp(-tangentSpeed / tangentMass, -body.friction() * jn, body.friction() * jn);
        impulse.set(tangent).mul(jt);
        body.applyImpulse(impulse, contact.point, invInertia);
    }

    /** {@code 1/m + n · ((I⁻¹ (r × n)) × r)} — how much velocity one unit of impulse buys at this point. */
    private float effectiveMass(RigidBody body, Vector3f point, Vector3f direction) {
        lever.set(point).sub(body.position());
        lever.cross(direction, cross);
        invInertia.transform(cross);
        cross.cross(lever, cross);
        return body.invMass() + direction.dot(cross);
    }

    /**
     * Lifts the body out of whatever it is buried in, along the deepest contact's normal.
     *
     * <p>Position-only, with no rotation: a rotational correction is more faithful and is also how a solver
     * of this kind starts flinging bodies, since the correction feeds back into the contact set on the next
     * frame. The slop leaves a sliver of penetration uncorrected, which is what stops a resting body
     * ping-ponging between "just inside" and "just outside" every frame.
     */
    private void pushOut(RigidBody body) {
        Contact deepest = null;
        for (Contact contact : contacts) {
            if (deepest == null || contact.depth() > deepest.depth()) {
                deepest = contact;
            }
        }
        if (deepest == null || deepest.depth() <= CORRECTION_SLOP) {
            return;
        }
        correction.set(deepest.normal).mul((deepest.depth() - CORRECTION_SLOP) * CORRECTION_RATE);
        body.position().add(correction);
    }

    private void updateSleep(RigidBody body, float dt) {
        boolean still = body.velocity().lengthSquared() < SLEEP_LINEAR * SLEEP_LINEAR
                && body.angularVelocity().lengthSquared() < SLEEP_ANGULAR * SLEEP_ANGULAR;
        if (!still) {
            body.resetIdleTime();
            return;
        }
        body.addIdleTime(dt);
        if (body.idleSeconds() >= SLEEP_SECONDS) {
            body.sleep();
        }
    }
}
