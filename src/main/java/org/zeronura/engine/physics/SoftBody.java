package org.zeronura.engine.physics;

import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;

/**
 * A deformable body: particles held in shape by distance constraints, solved by position-based dynamics.
 *
 * <p>The third kind of thing that can move in this engine, after the upright box that is a character and
 * the tumbling box that is a {@link RigidBody}. Where both of those have a <b>pose</b> — a position, and for
 * one of them an orientation — a soft body has no pose at all: it is a cloud of points, and its shape is
 * whatever its constraints have most recently agreed on. That is the whole difference, and it is why
 * nothing here returns a transform.
 *
 * <h2>Why position-based dynamics rather than springs</h2>
 * The obvious implementation is Hooke's law between particle pairs, integrated with the forces. It is also
 * the one that explodes: a spring stiff enough to look like jelly rather than like a bag of string needs a
 * timestep far below a frame, and the failure mode when a frame runs long is a body that flies apart.
 * PBD projects positions directly onto the constraints instead, so a stiff body is a matter of iterating
 * more, and the worst a long frame can do is under-solve — the body sags for a frame rather than exploding.
 * The cost is that stiffness is not a physical modulus (see {@link SoftBodyMaterial#stiffness}).
 *
 * <h2>Primitive arrays, not a Particle class</h2>
 * A modest lattice is a few hundred particles and the solver touches every one of them several times per
 * frame. Parallel {@code float[]}s keep that a linear walk over three arrays; an array of small objects
 * would make it a pointer chase, and this is the one place in the engine where that difference is worth
 * the loss in readability.
 */
public class SoftBody {

    /** Positions, and the predicted positions the solver works on before they are committed. */
    private final float[] px;
    private final float[] py;
    private final float[] pz;
    private final float[] qx;
    private final float[] qy;
    private final float[] qz;
    private final float[] vx;
    private final float[] vy;
    private final float[] vz;
    private final float[] invMass;

    /** Distance constraints as parallel arrays: particle a, particle b, and the length they want to be. */
    private final int[] linkA;
    private final int[] linkB;
    private final float[] linkRest;

    private final SoftBodyMaterial material;
    private final int count;
    private final int linkCount;
    private final float restVolume;

    private SoftBody(float[][] particles, int[][] links, float[] rest, float mass,
                     SoftBodyMaterial material, float restVolume) {
        this.count = particles[0].length;
        this.linkCount = rest.length;
        this.material = material;
        this.px = particles[0];
        this.py = particles[1];
        this.pz = particles[2];
        this.qx = new float[count];
        this.qy = new float[count];
        this.qz = new float[count];
        this.vx = new float[count];
        this.vy = new float[count];
        this.vz = new float[count];
        this.invMass = new float[count];
        float per = count / mass;
        java.util.Arrays.fill(this.invMass, per);
        this.linkA = links[0];
        this.linkB = links[1];
        this.linkRest = rest;
        this.restVolume = restVolume;
    }

    /**
     * A solid block of jelly: a lattice of {@code nx × ny × nz} particles filling the given box.
     *
     * <p>Linked <b>within each cell of the lattice, not just along its edges</b>. Edge links alone leave the
     * cube free to shear — it collapses into a rhombus the first time it lands on a corner, because nothing
     * resists an angle changing while every edge keeps its length. The face and body diagonals are what
     * carry that, so a cell contributes all 28 pairs among its eight corners and duplicates are dropped.
     *
     * @param resolution particles per axis, at least 2
     */
    public static SoftBody lattice(Vector3f centre, Vector3f halfExtents, int resolution, float mass,
                                   SoftBodyMaterial material) {
        if (resolution < 2) {
            throw new IllegalArgumentException("a lattice needs at least 2 particles per axis");
        }
        int n = resolution;
        int count = n * n * n;
        float[] xs = new float[count];
        float[] ys = new float[count];
        float[] zs = new float[count];

        for (int ix = 0; ix < n; ix++) {
            for (int iy = 0; iy < n; iy++) {
                for (int iz = 0; iz < n; iz++) {
                    int i = index(ix, iy, iz, n);
                    xs[i] = centre.x + lerp(-halfExtents.x, halfExtents.x, ix, n);
                    ys[i] = centre.y + lerp(-halfExtents.y, halfExtents.y, iy, n);
                    zs[i] = centre.z + lerp(-halfExtents.z, halfExtents.z, iz, n);
                }
            }
        }

        Set<Long> seen = new HashSet<>();
        java.util.List<int[]> pairs = new java.util.ArrayList<>();
        for (int ix = 0; ix < n - 1; ix++) {
            for (int iy = 0; iy < n - 1; iy++) {
                for (int iz = 0; iz < n - 1; iz++) {
                    int[] cell = new int[8];
                    int c = 0;
                    for (int dx = 0; dx <= 1; dx++) {
                        for (int dy = 0; dy <= 1; dy++) {
                            for (int dz = 0; dz <= 1; dz++) {
                                cell[c++] = index(ix + dx, iy + dy, iz + dz, n);
                            }
                        }
                    }
                    for (int a = 0; a < cell.length; a++) {
                        for (int b = a + 1; b < cell.length; b++) {
                            int lo = Math.min(cell[a], cell[b]);
                            int hi = Math.max(cell[a], cell[b]);
                            if (seen.add(((long) lo << 32) | hi)) {
                                pairs.add(new int[]{lo, hi});
                            }
                        }
                    }
                }
            }
        }

        int[] as = new int[pairs.size()];
        int[] bs = new int[pairs.size()];
        float[] rest = new float[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            as[i] = pairs.get(i)[0];
            bs[i] = pairs.get(i)[1];
            float dx = xs[as[i]] - xs[bs[i]];
            float dy = ys[as[i]] - ys[bs[i]];
            float dz = zs[as[i]] - zs[bs[i]];
            rest[i] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        float volume = 8f * halfExtents.x * halfExtents.y * halfExtents.z;
        return new SoftBody(new float[][]{xs, ys, zs}, new int[][]{as, bs}, rest, mass, material, volume);
    }

    private static int index(int ix, int iy, int iz, int n) {
        return (ix * n + iy) * n + iz;
    }

    private static float lerp(float min, float max, int step, int steps) {
        return min + (max - min) * step / (steps - 1);
    }

    public int particleCount() {
        return count;
    }

    public int linkCount() {
        return linkCount;
    }

    public SoftBodyMaterial material() {
        return material;
    }

    public Vector3f particle(int i, Vector3f dest) {
        return dest.set(px[i], py[i], pz[i]);
    }

    public Vector3f particleVelocity(int i, Vector3f dest) {
        return dest.set(vx[i], vy[i], vz[i]);
    }

    /** Nudges the whole body — what a caller uses to throw one, or to have something bump into it. */
    public void addVelocity(float dx, float dy, float dz) {
        for (int i = 0; i < count; i++) {
            vx[i] += dx;
            vy[i] += dy;
            vz[i] += dz;
        }
    }

    /** The centre of mass, which is as close to a "position" as a soft body has. */
    public Vector3f centre(Vector3f dest) {
        double sx = 0;
        double sy = 0;
        double sz = 0;
        for (int i = 0; i < count; i++) {
            sx += px[i];
            sy += py[i];
            sz += pz[i];
        }
        return dest.set((float) (sx / count), (float) (sy / count), (float) (sz / count));
    }

    /** The world-aligned box containing every particle — for culling, and for measuring squash in a test. */
    public AABB bounds() {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            minX = Math.min(minX, px[i]);
            minY = Math.min(minY, py[i]);
            minZ = Math.min(minZ, pz[i]);
            maxX = Math.max(maxX, px[i]);
            maxY = Math.max(maxY, py[i]);
            maxZ = Math.max(maxZ, pz[i]);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** The volume the body was built with, for comparing against how much it has been squashed. */
    public float restVolume() {
        return restVolume;
    }

    /**
     * Mean relative stretch across every link: 0 for a body at its rest shape, 0.1 for one deformed a tenth.
     *
     * <p>The measurement a test asks for instead of eyeballing a render — "did it hold together" is a
     * statement about the constraints, and this is that statement as a number.
     */
    public float averageStrain() {
        if (linkCount == 0) {
            return 0f;
        }
        double sum = 0;
        for (int i = 0; i < linkCount; i++) {
            int a = linkA[i];
            int b = linkB[i];
            float dx = px[a] - px[b];
            float dy = py[a] - py[b];
            float dz = pz[a] - pz[b];
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            sum += Math.abs(length - linkRest[i]) / linkRest[i];
        }
        return (float) (sum / linkCount);
    }

    // ------------------------------------------------------------- solver access

    float[] positionsX() {
        return px;
    }

    float[] positionsY() {
        return py;
    }

    float[] positionsZ() {
        return pz;
    }

    float[] predictedX() {
        return qx;
    }

    float[] predictedY() {
        return qy;
    }

    float[] predictedZ() {
        return qz;
    }

    float[] velocitiesX() {
        return vx;
    }

    float[] velocitiesY() {
        return vy;
    }

    float[] velocitiesZ() {
        return vz;
    }

    float[] inverseMasses() {
        return invMass;
    }

    int[] linkStarts() {
        return linkA;
    }

    int[] linkEnds() {
        return linkB;
    }

    float[] linkRestLengths() {
        return linkRest;
    }
}
