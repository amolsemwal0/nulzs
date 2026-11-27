package org.zeronura.nulzs.physics;

import org.joml.Vector3f;

/**
 * Moves {@link SoftBody}s: predict, project the constraints, then read the velocities back off the result.
 *
 * <p>That last step is what position-based dynamics <i>is</i>, and it is worth stating plainly because it
 * inverts the order everything else in this package works in. A rigid body computes forces, integrates them
 * into velocity, and moves. A soft body moves first — optimistically, ignoring its own constraints — then
 * has its particles shoved back onto the shape they are supposed to hold, and finally <b>derives</b> its
 * velocity from where each particle actually ended up. Collisions therefore need no impulse: pushing a
 * particle out of rock during the projection step is automatically a loss of velocity, because the velocity
 * is measured after.
 */
public class SoftBodyWorld {

    public static final float GRAVITY = PhysicsWorld.GRAVITY;

    private static final float MAX_SUBSTEP = 1.0f / 60.0f;

    /**
     * Constraint passes per substep. More makes a body stiffer as well as more accurate — see
     * {@link SoftBodyMaterial#stiffness} — so this is half a tuning constant, and moving it retunes every
     * material.
     */
    private static final int ITERATIONS = 6;

    /** How far a particle is pushed back out of the surface it has entered, at most. */
    private static final float MAX_ESCAPE = 0.75f;

    /** Fraction of tangential velocity a particle keeps after being pushed out of terrain. */
    private static final float SURFACE_FRICTION = 0.55f;

    private final float gravity;
    private final Vector3f normal = new Vector3f();

    public SoftBodyWorld() {
        this(GRAVITY);
    }

    public SoftBodyWorld(float gravity) {
        this.gravity = gravity;
    }

    public void step(SoftBody body, SolidField field, float dt) {
        if (dt <= 0f) {
            return;
        }
        int substeps = Math.max(1, (int) Math.ceil(dt / MAX_SUBSTEP));
        float substepDt = dt / substeps;
        for (int i = 0; i < substeps; i++) {
            substep(body, field, substepDt);
        }
    }

    private void substep(SoftBody body, SolidField field, float dt) {
        float[] px = body.positionsX();
        float[] py = body.positionsY();
        float[] pz = body.positionsZ();
        float[] qx = body.predictedX();
        float[] qy = body.predictedY();
        float[] qz = body.predictedZ();
        float[] vx = body.velocitiesX();
        float[] vy = body.velocitiesY();
        float[] vz = body.velocitiesZ();
        float[] invMass = body.inverseMasses();
        int count = body.particleCount();

        float damp = 1f / (1f + body.material().damping() * dt);
        for (int i = 0; i < count; i++) {
            vy[i] += gravity * dt;
            vx[i] *= damp;
            vy[i] *= damp;
            vz[i] *= damp;
            qx[i] = px[i] + vx[i] * dt;
            qy[i] = py[i] + vy[i] * dt;
            qz[i] = pz[i] + vz[i] * dt;
        }

        // Per-pass stiffness, so a material's stiffness means the same thing whatever ITERATIONS is set to:
        // applying k' each of n times leaves (1-k')^n of the error, which is the standard PBD correction.
        float perPass = 1f - (float) Math.pow(1f - body.material().stiffness(), 1.0 / ITERATIONS);
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            projectLinks(body, perPass);
        }

        for (int i = 0; i < count; i++) {
            if (invMass[i] == 0f) {
                continue;
            }
            collide(field, qx, qy, qz, vx, vy, vz, i);
        }

        float invDt = 1f / dt;
        for (int i = 0; i < count; i++) {
            vx[i] = (qx[i] - px[i]) * invDt;
            vy[i] = (qy[i] - py[i]) * invDt;
            vz[i] = (qz[i] - pz[i]) * invDt;
            px[i] = qx[i];
            py[i] = qy[i];
            pz[i] = qz[i];
        }
    }

    /**
     * One pass of distance-constraint projection: every link pulls its two particles toward the length it
     * wants, splitting the correction by inverse mass so a heavy particle moves less than a light one.
     */
    private static void projectLinks(SoftBody body, float stiffness) {
        float[] qx = body.predictedX();
        float[] qy = body.predictedY();
        float[] qz = body.predictedZ();
        float[] invMass = body.inverseMasses();
        int[] a = body.linkStarts();
        int[] b = body.linkEnds();
        float[] rest = body.linkRestLengths();

        for (int i = 0; i < rest.length; i++) {
            int ia = a[i];
            int ib = b[i];
            float wa = invMass[ia];
            float wb = invMass[ib];
            float sum = wa + wb;
            if (sum == 0f) {
                continue;
            }
            float dx = qx[ib] - qx[ia];
            float dy = qy[ib] - qy[ia];
            float dz = qz[ib] - qz[ia];
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1e-6f) {
                continue;
            }
            float correction = (length - rest[i]) / length * stiffness / sum;
            qx[ia] += dx * correction * wa;
            qy[ia] += dy * correction * wa;
            qz[ia] += dz * correction * wa;
            qx[ib] -= dx * correction * wb;
            qy[ib] -= dy * correction * wb;
            qz[ib] -= dz * correction * wb;
        }
    }

    /**
     * Pushes one particle back out of terrain and scrubs off the tangential part of its velocity.
     *
     * <p>The velocity change here is not the collision response — that falls out of the position change
     * when velocities are re-derived at the end of the substep — it is <b>friction</b>, which does not,
     * because sliding along a surface changes no position the projection would notice.
     */
    private void collide(SolidField field, float[] qx, float[] qy, float[] qz,
                         float[] vx, float[] vy, float[] vz, int i) {
        if (!field.isSolid(qx[i], qy[i], qz[i])) {
            return;
        }
        FieldContact.normalAt(field, qx[i], qy[i], qz[i], normal);
        // Exactly the penetration, not a step past it. Velocity here is *derived* from how far a particle
        // moved, so over-ejecting by a fixed step gives every resting particle that step times the frame
        // rate as a phantom speed — a settled body that hums at three units a second and never sleeps.
        float escape = -Math.min(0f, FieldContact.signedDistance(field, qx[i], qy[i], qz[i], normal, MAX_ESCAPE));
        qx[i] += normal.x * escape;
        qy[i] += normal.y * escape;
        qz[i] += normal.z * escape;

        float normalSpeed = vx[i] * normal.x + vy[i] * normal.y + vz[i] * normal.z;
        float tx = vx[i] - normal.x * normalSpeed;
        float ty = vy[i] - normal.y * normalSpeed;
        float tz = vz[i] - normal.z * normalSpeed;
        vx[i] = normal.x * normalSpeed + tx * SURFACE_FRICTION;
        vy[i] = normal.y * normalSpeed + ty * SURFACE_FRICTION;
        vz[i] = normal.z * normalSpeed + tz * SURFACE_FRICTION;
    }
}
