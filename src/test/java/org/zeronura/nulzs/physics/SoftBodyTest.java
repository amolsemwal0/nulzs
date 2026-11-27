package org.zeronura.nulzs.physics;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deformable solver.
 *
 * <p>What is worth asserting about a soft body is not where it is — it has no single position — but whether
 * it <b>held together</b>, whether it <b>gave way</b> when something hit it, and whether the material it was
 * built with made a difference to either. All three are numbers ({@link SoftBody#averageStrain()}, the
 * body's own bounds), which is the whole reason those exist: "it looked squishy" is not a test.
 */
class SoftBodyTest {

    private static final float FRAME = 1f / 60f;

    private static SolidField ground() {
        return (x, y, z) -> y < 0f;
    }

    private static SoftBody jelly(float y, SoftBodyMaterial material) {
        return SoftBody.lattice(new Vector3f(0f, y, 0f), new Vector3f(0.5f, 0.5f, 0.5f), 3, 8f, material);
    }

    private static void run(SoftBodyWorld world, SoftBody body, SolidField field, float seconds) {
        int frames = Math.round(seconds / FRAME);
        for (int i = 0; i < frames; i++) {
            world.step(body, field, FRAME);
        }
    }

    /** The lowest the body's own top ever gets — how far it was squashed at the moment of impact. */
    private static float minimumHeight(SoftBodyWorld world, SoftBody body, SolidField field, float seconds) {
        int frames = Math.round(seconds / FRAME);
        float minimum = Float.MAX_VALUE;
        for (int i = 0; i < frames; i++) {
            world.step(body, field, FRAME);
            AABB bounds = body.bounds();
            minimum = Math.min(minimum, bounds.maxY() - bounds.minY());
        }
        return minimum;
    }

    @Test
    void aLatticeIsBuiltWithLinksAcrossItsCellsNotJustAlongItsEdges() {
        SoftBody body = jelly(2f, SoftBodyMaterial.JELLY);

        assertEquals(27, body.particleCount(), "3 per axis");
        // 12 edges + 12 face diagonals + 4 body diagonals is 28 per cell; eight cells share many of them.
        assertTrue(body.linkCount() > 27 * 3,
                "a lattice with only edge links cannot resist shear, got " + body.linkCount());
        assertEquals(0f, body.averageStrain(), 1e-5f, "a freshly built lattice is at its rest shape");
    }

    @Test
    void aDroppedBodyComesToRestOnTheGroundStillHoldingItsShape() {
        SoftBodyWorld world = new SoftBodyWorld();
        SoftBody body = jelly(3f, SoftBodyMaterial.JELLY);

        run(world, body, ground(), 4f);

        AABB bounds = body.bounds();
        assertEquals(0f, bounds.minY(), 0.06f, "it must settle onto the ground, not through or above it");
        assertTrue(body.averageStrain() < 0.12f,
                "it must still be recognisably a cube, strained " + body.averageStrain());

        Vector3f velocity = new Vector3f();
        float fastest = 0f;
        for (int i = 0; i < body.particleCount(); i++) {
            fastest = Math.max(fastest, body.particleVelocity(i, velocity).length());
        }
        assertTrue(fastest < 0.5f, "it must have stopped wobbling, fastest particle " + fastest);
    }

    /**
     * The property that separates this from a rigid body: it has to <b>give</b> on impact. A soft body that
     * lands without deforming is a rigid body with more state.
     */
    @Test
    void aBodyIsSquashedByAnImpactAndSpringsBack() {
        SoftBodyWorld world = new SoftBodyWorld();
        SoftBody body = jelly(4f, SoftBodyMaterial.JELLY);
        float original = body.bounds().maxY() - body.bounds().minY();

        float squashed = minimumHeight(world, body, ground(), 2f);
        assertTrue(squashed < original * 0.92f,
                "the impact must visibly flatten it: " + squashed + " of " + original);

        run(world, body, ground(), 3f);
        float recovered = body.bounds().maxY() - body.bounds().minY();
        assertEquals(original, recovered, original * 0.15f,
                "and it must spring back to about its own height, got " + recovered);
    }

    /** If the material did not change the deformation, it would not be a material. */
    @Test
    void aStifferMaterialSquashesLess() {
        SoftBodyWorld world = new SoftBodyWorld();
        SoftBody soft = jelly(4f, SoftBodyMaterial.JELLY);
        SoftBody stiff = jelly(4f, SoftBodyMaterial.RUBBER);

        float softSquash = minimumHeight(world, soft, ground(), 2f);
        float stiffSquash = minimumHeight(new SoftBodyWorld(), stiff, ground(), 2f);

        assertTrue(stiffSquash > softSquash,
                "rubber must deform less than jelly: " + stiffSquash + " vs " + softSquash);
    }

    @Test
    void noParticleEverEndsUpInsideRock() {
        SoftBodyWorld world = new SoftBodyWorld();
        SoftBody body = jelly(6f, SoftBodyMaterial.JELLY);
        SolidField field = ground();

        Vector3f p = new Vector3f();
        for (int frame = 0; frame < 300; frame++) {
            world.step(body, field, FRAME);
            for (int i = 0; i < body.particleCount(); i++) {
                body.particle(i, p);
                assertTrue(p.y > -0.1f,
                        "particle " + i + " sank into the ground at frame " + frame + ": " + p.y);
            }
        }
    }

    @Test
    void aBodyThrownAtAWallIsStoppedByIt() {
        SoftBodyWorld world = new SoftBodyWorld(0f);
        SoftBody body = jelly(2f, SoftBodyMaterial.JELLY);
        SolidField wall = (x, y, z) -> x > 3f;

        body.addVelocity(8f, 0f, 0f);
        run(world, body, wall, 2f);

        assertTrue(body.bounds().maxX() < 3.15f,
                "the body went through the wall, reaching " + body.bounds().maxX());
        assertTrue(body.averageStrain() < 0.3f,
                "and it must not have been torn apart by the impact, strained " + body.averageStrain());
    }

    @Test
    void theSolverIsDeterministic() {
        SoftBody a = jelly(3f, SoftBodyMaterial.JELLY);
        SoftBody b = jelly(3f, SoftBodyMaterial.JELLY);
        run(new SoftBodyWorld(), a, ground(), 2f);
        run(new SoftBodyWorld(), b, ground(), 2f);

        Vector3f pa = new Vector3f();
        Vector3f pb = new Vector3f();
        for (int i = 0; i < a.particleCount(); i++) {
            assertEquals(0f, a.particle(i, pa).sub(b.particle(i, pb)).length(), 1e-6f,
                    "two identical bodies diverged at particle " + i);
        }
    }
}
