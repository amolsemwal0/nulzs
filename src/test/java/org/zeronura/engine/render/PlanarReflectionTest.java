package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing the mirror plane, which is the one piece of judgement in the planar reflection.
 *
 * <p>A planar reflection assumes a single flat mirror and this world's fluid is <b>volumetric</b>: a pond
 * in a hollow, a river down a mountainside and a waterfall off a cliff are at different heights and are
 * routinely all on screen. One plane can only be right for one of them, so which one it picks is the whole
 * question — and it is decided from the surface mesh, which makes it pure arithmetic and testable with no
 * window.
 */
class PlanarReflectionTest {

    private static final int FLOATS_PER_VERTEX = 10;

    /** Builds a patch of surface vertices at one height, centred on (x, z). */
    private static void addSurface(FloatBuffer mesh, float x, float z, float y, int vertices,
                                   float normalY) {
        for (int i = 0; i < vertices; i++) {
            mesh.put(x + (i % 4)).put(y).put(z + (i / 4f));
            mesh.put(0f).put(normalY).put(0f);
            mesh.put(1f).put(0f);          // depth, fluid
            mesh.put(0f).put(0f);          // flow
        }
    }

    private static FloatBuffer buffer(int vertices) {
        return BufferUtils.createFloatBuffer(vertices * FLOATS_PER_VERTEX);
    }

    @Test
    void oneBodyGivesItsOwnHeight() {
        FluidMesh mesh = FluidMesh.of(120);
        addSurface(mesh.data, 0f, 0f, 4.25f, 120, 1f);

        assertEquals(4.25f, PlanarReflection.choosePlane(mesh.data, 120, new Vector3f(0f, 6f, 0f)), 0.13f);
    }

    /** Too little water on screen is not worth a second render of the whole scene. */
    @Test
    void tooLittleWaterChoosesNoPlaneAtAll() {
        FluidMesh mesh = FluidMesh.of(6);
        addSurface(mesh.data, 0f, 0f, 4f, 6, 1f);

        assertTrue(Float.isNaN(PlanarReflection.choosePlane(mesh.data, 6, new Vector3f())),
                "a puddle's worth of vertices must not buy a whole extra scene pass");
    }

    /**
     * The body being looked at wins over a bigger one further off.
     *
     * <p>This is the assertion the weighting exists for: a reflection is judged on the water you are
     * standing beside, and picking the plane by raw area would hand it to a lake on the horizon.
     */
    @Test
    void theNearBodyWinsOverALargerDistantOne() {
        FluidMesh mesh = FluidMesh.of(360);
        addSurface(mesh.data, 0f, 0f, 3f, 60, 1f);        // small, at the camera
        addSurface(mesh.data, 400f, 400f, 50f, 300, 1f);  // five times the size, far away

        float plane = PlanarReflection.choosePlane(mesh.data, 360, new Vector3f(0f, 5f, 0f));

        assertEquals(3f, plane, 0.13f,
                "the plane must follow the water the player is beside, not the biggest surface in the mesh");
    }

    /**
     * Vertical faces get no vote.
     *
     * <p>A waterfall's curtain and a pond's side walls are vertical, and a vertical face has no business
     * choosing a horizontal mirror — left voting, a tall waterfall would drag the plane to some height
     * partway up itself, where no water lies flat at all.
     */
    @Test
    void verticalFacesDoNotVote() {
        FluidMesh mesh = FluidMesh.of(400);
        addSurface(mesh.data, 0f, 0f, 2f, 100, 1f);       // a flat pool
        addSurface(mesh.data, 3f, 3f, 20f, 300, 0f);      // a curtain, three times as many vertices

        float plane = PlanarReflection.choosePlane(mesh.data, 400, new Vector3f(0f, 4f, 0f));

        assertEquals(2f, plane, 0.13f, "only upward-facing surface may choose the mirror height");
    }

    /** Water more than a tolerance off the plane is what the sky fallback in fluid.frag is for. */
    @Test
    void theToleranceIsTightEnoughToSeparateTwoBodies() {
        assertTrue(PlanarReflection.PLANE_TOLERANCE < 1.0f,
                "a tolerance of " + PlanarReflection.PLANE_TOLERANCE + " would let a stream a metre below "
                        + "the pond share its mirror, which puts everything in that reflection at the "
                        + "wrong distance");
    }

    /** A tiny holder so the tests read as surfaces rather than as buffer bookkeeping. */
    private record FluidMesh(FloatBuffer data) {
        static FluidMesh of(int vertices) {
            return new FluidMesh(buffer(vertices));
        }
    }
}
