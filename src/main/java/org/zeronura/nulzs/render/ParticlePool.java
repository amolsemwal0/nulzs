package org.zeronura.nulzs.render;

import org.joml.Vector3f;

import java.nio.FloatBuffer;
import java.util.Random;

/**
 * The particle simulation, with no GL in it: a fixed pool that emits, integrates and expires particles,
 * and can lay its live particles out as camera-facing billboard vertices.
 *
 * <p>Split from {@link ParticleSystem} (which owns the shader, texture and VBO) for the same reason
 * {@code WaterSim} is split from its renderer: the emit/integrate maths is where the behaviour lives, and
 * it can be unit-tested against gravity and a stopwatch without a window. {@link #buildBillboards} is the
 * one method that speaks the renderer's language, and even it is pure arithmetic on a {@link FloatBuffer}.
 *
 * <h2>A ring, not a free list</h2>
 * Slots are filled in a rolling cursor, so a burst that would overflow the pool simply overwrites the
 * oldest particles. At the scale this runs (a few thousand) that is invisible and needs no bookkeeping —
 * there is never a "pool full, drop the emit" cliff, the oldest wisp just vanishes a little early.
 */
public class ParticlePool {

    /** Floats per emitted vertex: position (3) + uv (2) + colour rgba (4). */
    public static final int FLOATS_PER_VERTEX = 9;

    /** Six vertices (two triangles) per live particle. */
    private static final int VERTICES_PER_PARTICLE = 6;

    private static final float TWO_PI = (float) (2.0 * Math.PI);
    /** The narrowest a pair of wings closes, as a share of its width: never quite to nothing. */
    private static final float MIN_OPEN = 0.35f;
    /** How far above the floor a landed particle lies, so the ground does not cut through it. */
    private static final float LANDED_LIFT = 0.03f;

    private final int capacity;
    private final Random random;

    private final float[] px;
    private final float[] py;
    private final float[] pz;
    private final float[] vx;
    private final float[] vy;
    private final float[] vz;
    private final float[] age;
    private final float[] life;
    private final float[] startSize;
    private final float[] endSize;
    private final float[] startR;
    private final float[] startG;
    private final float[] startB;
    private final float[] startA;
    private final float[] endR;
    private final float[] endG;
    private final float[] endB;
    private final float[] endA;
    private final float[] drag;
    private final float[] gravity;
    private final BlendMode[] blend;
    private final ParticleShape[] shape;
    private final float[] phase;
    private final float[] spinStart;
    private final float[] spinRate;
    private final float[] tumbleRate;
    private final float[] flapRate;
    private final float[] flutter;
    private final float[] pulse;
    private final float[] fadeIn;
    private final float[] fadeOut;
    private final float[] windShare;
    private final float[] sizeScale;
    private final boolean[] flat;
    /** The height a particle comes to rest at, or negative infinity for one that never lands. */
    private final float[] floor;
    private final boolean[] resting;

    private float windX;
    private float windY;
    private float windZ;
    private int cursor;

    public ParticlePool(int capacity, long seed) {
        this.capacity = capacity;
        this.random = new Random(seed);
        px = new float[capacity];
        py = new float[capacity];
        pz = new float[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        vz = new float[capacity];
        age = new float[capacity];
        life = new float[capacity];
        startSize = new float[capacity];
        endSize = new float[capacity];
        startR = new float[capacity];
        startG = new float[capacity];
        startB = new float[capacity];
        startA = new float[capacity];
        endR = new float[capacity];
        endG = new float[capacity];
        endB = new float[capacity];
        endA = new float[capacity];
        drag = new float[capacity];
        gravity = new float[capacity];
        blend = new BlendMode[capacity];
        shape = new ParticleShape[capacity];
        phase = new float[capacity];
        spinStart = new float[capacity];
        spinRate = new float[capacity];
        tumbleRate = new float[capacity];
        flapRate = new float[capacity];
        flutter = new float[capacity];
        pulse = new float[capacity];
        fadeIn = new float[capacity];
        fadeOut = new float[capacity];
        windShare = new float[capacity];
        sizeScale = new float[capacity];
        flat = new boolean[capacity];
        floor = new float[capacity];
        resting = new boolean[capacity];
        // All slots start dead (age at/after life), so nothing draws until something is emitted.
        for (int i = 0; i < capacity; i++) {
            age[i] = 1f;
            life[i] = 1f;
            blend[i] = BlendMode.ALPHA;
            shape[i] = ParticleShape.DOT;
            floor[i] = Float.NEGATIVE_INFINITY;
        }
    }

    public int capacity() {
        return capacity;
    }

    /** Vertices a full pool would produce — the size a rendering buffer must be able to hold. */
    public int maxVertices() {
        return capacity * VERTICES_PER_PARTICLE;
    }

    /**
     * Spawns {@link ParticleEffect#count} particles at {@code position}. Each is thrown in a random
     * direction within the effect's cone, at a random speed and lifetime, and carries its own size/colour
     * arc — so one effect definition still gives a burst variety rather than a synchronised clone army.
     */
    public void emit(ParticleEffect effect, Vector3f position) {
        emit(effect, position, Float.NEGATIVE_INFINITY);
    }

    /**
     * The same, for particles that come down: one reaching height {@code floor} stops there and lies flat until its
     * life runs out, a leaf on the ground. Negative infinity for particles that never land.
     */
    public void emit(ParticleEffect effect, Vector3f position, float floorHeight) {
        ParticleLook look = effect.look();
        Vector3f dir = new Vector3f(effect.direction());
        if (dir.lengthSquared() < 1e-8f) {
            dir.set(0f, 1f, 0f);
        }
        dir.normalize();

        for (int i = 0; i < effect.count(); i++) {
            int s = cursor;
            cursor = (cursor + 1) % capacity;

            Vector3f offset = randomInSphere(effect.spawnRadius());
            px[s] = position.x + offset.x;
            py[s] = position.y + offset.y;
            pz[s] = position.z + offset.z;

            Vector3f throwDir = randomInCone(dir, effect.coneAngle());
            float speed = lerp(effect.speedMin(), effect.speedMax(), random.nextFloat());
            vx[s] = throwDir.x * speed;
            vy[s] = throwDir.y * speed;
            vz[s] = throwDir.z * speed;

            age[s] = 0f;
            life[s] = lerp(effect.lifeMin(), effect.lifeMax(), random.nextFloat());
            startSize[s] = effect.startSize();
            endSize[s] = effect.endSize();
            startR[s] = effect.startColor().x;
            startG[s] = effect.startColor().y;
            startB[s] = effect.startColor().z;
            startA[s] = effect.startColor().w;
            endR[s] = effect.endColor().x;
            endG[s] = effect.endColor().y;
            endB[s] = effect.endColor().z;
            endA[s] = effect.endColor().w;
            drag[s] = effect.drag();
            gravity[s] = effect.gravity();
            blend[s] = effect.blend();

            shape[s] = look.shape();
            phase[s] = random.nextFloat() * TWO_PI;
            boolean spins = look.spin() != 0f;
            spinStart[s] = spins ? random.nextFloat() * TWO_PI : 0f;
            spinRate[s] = spins && random.nextBoolean() ? -look.spin() : look.spin();
            tumbleRate[s] = look.tumble() * TWO_PI * (0.8f + 0.4f * random.nextFloat());
            flapRate[s] = look.flap() * TWO_PI * (0.8f + 0.4f * random.nextFloat());
            flutter[s] = look.flutter();
            pulse[s] = look.pulse() * TWO_PI * (0.8f + 0.4f * random.nextFloat());
            fadeIn[s] = look.fadeIn();
            fadeOut[s] = look.fadeOut();
            windShare[s] = look.wind();
            sizeScale[s] = 1f + look.sizeVariety() * (2f * random.nextFloat() - 1f);
            flat[s] = look.flat();
            floor[s] = floorHeight;
            resting[s] = false;
        }
    }

    /** The wind, in units a second, that carries particles by their look's {@link ParticleLook#wind share} of it. */
    public void setWind(float x, float y, float z) {
        windX = x;
        windY = y;
        windZ = z;
    }

    /**
     * Integrates every live particle by {@code dt}: gravity into velocity, drag onto velocity, velocity
     * into position, and age. A dead particle (age past its life) is left alone until its slot is reused,
     * so a paused frame ({@code dt == 0}) moves nothing. A particle that has landed only ages.
     */
    public void update(double dt) {
        if (dt <= 0.0) {
            return;
        }
        float d = (float) dt;
        for (int i = 0; i < capacity; i++) {
            if (age[i] >= life[i]) {
                continue;
            }
            age[i] += d;
            if (resting[i]) {
                continue;
            }
            vy[i] += gravity[i] * d;
            float damp = (float) Math.pow(drag[i], d);
            vx[i] *= damp;
            vy[i] *= damp;
            vz[i] *= damp;
            float moveX = vx[i] + windX * windShare[i];
            float moveY = vy[i] + windY * windShare[i];
            float moveZ = vz[i] + windZ * windShare[i];
            if (flutter[i] != 0f) {
                float time = age[i];
                moveX += flutter[i] * (float) Math.sin(time * 1.3f + phase[i]);
                moveY += 0.5f * flutter[i] * (float) Math.sin(time * 0.9f + phase[i] * 2.3f);
                moveZ += flutter[i] * (float) Math.cos(time * 1.1f + phase[i] * 1.7f);
            }
            px[i] += moveX * d;
            py[i] += moveY * d;
            pz[i] += moveZ * d;
            if (py[i] <= floor[i]) {
                py[i] = floor[i] + LANDED_LIFT;
                resting[i] = true;
            }
        }
    }

    /** How many particles are currently alive — a test and profiling hook. */
    public int liveCount() {
        int n = 0;
        for (int i = 0; i < capacity; i++) {
            if (age[i] < life[i]) {
                n++;
            }
        }
        return n;
    }

    /** Position of a live particle, for tests. Returns null if that slot is dead. */
    public Vector3f positionOf(int slot) {
        if (age[slot] >= life[slot]) {
            return null;
        }
        return new Vector3f(px[slot], py[slot], pz[slot]);
    }

    /**
     * Writes camera-facing billboard quads for every live particle of the given blend mode into
     * {@code out}, returning the vertex count. The two blend modes are laid out in separate calls because
     * they are drawn in separate passes.
     *
     * @param right the camera's right vector; {@code up} its up vector — the billboard plane
     */
    public int buildBillboards(BlendMode mode, Vector3f right, Vector3f up, FloatBuffer out) {
        out.clear();
        int vertices = 0;
        for (int i = 0; i < capacity; i++) {
            if (age[i] >= life[i] || blend[i] != mode) {
                continue;
            }
            float t = age[i] / life[i];
            float size = lerp(startSize[i], endSize[i], t) * sizeScale[i];
            float r = lerp(startR[i], endR[i], t);
            float g = lerp(startG[i], endG[i], t);
            float b = lerp(startB[i], endB[i], t);
            float a = lerp(startA[i], endA[i], t);
            if (t < fadeIn[i]) {
                a *= t / fadeIn[i];
            }
            if (1f - t < fadeOut[i]) {
                a *= (1f - t) / fadeOut[i];
            }
            if (pulse[i] != 0f) {
                float beat = 0.5f + 0.5f * (float) Math.sin(age[i] * pulse[i] + phase[i]);
                a *= 0.2f + 0.8f * beat * beat;
            }

            float wide = size;
            float tall = size;
            boolean lying = flat[i] || resting[i];
            if (!resting[i]) {
                if (flapRate[i] != 0f) {
                    wide *= Math.max(MIN_OPEN, Math.abs((float) Math.cos(age[i] * flapRate[i] + phase[i])));
                }
                if (tumbleRate[i] != 0f) {
                    tall *= (float) Math.cos(age[i] * tumbleRate[i] + phase[i]);
                }
            }

            // The quad's two axes: facing the camera, or lying flat; then turned in that plane.
            float ax = lying ? 1f : right.x;
            float ay = lying ? 0f : right.y;
            float az = lying ? 0f : right.z;
            float bx = lying ? 0f : up.x;
            float by = lying ? 0f : up.y;
            float bz = lying ? 1f : up.z;
            float angle = spinStart[i] + spinRate[i] * age[i];
            float cos = 1f;
            float sin = 0f;
            if (angle != 0f) {
                cos = (float) Math.cos(angle);
                sin = (float) Math.sin(angle);
            }
            float rx = (ax * cos + bx * sin) * wide;
            float ry = (ay * cos + by * sin) * wide;
            float rz = (az * cos + bz * sin) * wide;
            float ux = (bx * cos - ax * sin) * tall;
            float uy = (by * cos - ay * sin) * tall;
            float uz = (bz * cos - az * sin) * tall;

            float u0 = ParticleAtlas.u0(shape[i]);
            float u1 = ParticleAtlas.u1(shape[i]);
            float v0 = ParticleAtlas.v0(shape[i]);
            float v1 = ParticleAtlas.v1(shape[i]);

            // Four corners of the quad around the particle centre.
            float cx = px[i];
            float cy = py[i];
            float cz = pz[i];
            // bottom-left, bottom-right, top-right, top-left
            corner(out, cx - rx - ux, cy - ry - uy, cz - rz - uz, u0, v0, r, g, b, a);
            corner(out, cx + rx - ux, cy + ry - uy, cz + rz - uz, u1, v0, r, g, b, a);
            corner(out, cx + rx + ux, cy + ry + uy, cz + rz + uz, u1, v1, r, g, b, a);
            corner(out, cx - rx - ux, cy - ry - uy, cz - rz - uz, u0, v0, r, g, b, a);
            corner(out, cx + rx + ux, cy + ry + uy, cz + rz + uz, u1, v1, r, g, b, a);
            corner(out, cx - rx + ux, cy - ry + uy, cz - rz + uz, u0, v1, r, g, b, a);
            vertices += VERTICES_PER_PARTICLE;
        }
        out.flip();
        return vertices;
    }

    private static void corner(FloatBuffer out, float x, float y, float z, float u, float v,
                               float r, float g, float b, float a) {
        out.put(x).put(y).put(z).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    private Vector3f randomInSphere(float radius) {
        if (radius <= 0f) {
            return new Vector3f();
        }
        Vector3f v;
        do {
            v = new Vector3f(random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f,
                    random.nextFloat() * 2f - 1f);
        } while (v.lengthSquared() > 1f);
        return v.mul(radius);
    }

    /**
     * A random unit vector within {@code coneAngle} of {@code axis}. cos(theta) is drawn uniformly over
     * the spherical cap so directions are area-uniform (not bunched at the axis), and at {@code coneAngle
     * = PI} it degrades to a uniform sphere.
     */
    private Vector3f randomInCone(Vector3f axis, float coneAngle) {
        float cosMin = (float) Math.cos(Math.min(coneAngle, Math.PI));
        float cosTheta = lerp(cosMin, 1f, random.nextFloat());
        float sinTheta = (float) Math.sqrt(Math.max(0f, 1f - cosTheta * cosTheta));
        float phi = (float) (random.nextFloat() * 2.0 * Math.PI);

        // Build an orthonormal basis around the axis.
        Vector3f up = Math.abs(axis.y) > 0.99f ? new Vector3f(1f, 0f, 0f) : new Vector3f(0f, 1f, 0f);
        Vector3f t1 = new Vector3f(axis).cross(up).normalize();
        Vector3f t2 = new Vector3f(axis).cross(t1);

        Vector3f dir = new Vector3f(axis).mul(cosTheta)
                .fma((float) Math.cos(phi) * sinTheta, t1)
                .fma((float) Math.sin(phi) * sinTheta, t2);
        return dir.normalize();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
