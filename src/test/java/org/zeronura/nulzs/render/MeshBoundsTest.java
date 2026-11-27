package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sphere round a mesh, and the culling test World3D makes with it: no GL. */
class MeshBoundsTest {

    /** Interleaved vertices of {@code stride} bytes with only their positions filled in. */
    private static ByteBuffer positions(int stride, float[]... points) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(stride * points.length).order(ByteOrder.nativeOrder());
        for (int k = 0; k < points.length; k++) {
            buffer.putFloat(k * stride, points[k][0]);
            buffer.putFloat(k * stride + 4, points[k][1]);
            buffer.putFloat(k * stride + 8, points[k][2]);
        }
        return buffer;
    }

    @Test
    void theSphereIsCentredOnTheBoxAndReachesTheFarthestVertexWithPadding() {
        MeshBounds bounds = MeshBounds.of(positions(TexturedMesh.STRIDE,
                new float[]{-1f, 0f, -1f}, new float[]{1f, 4f, 1f}, new float[]{0f, 2f, 0f}), TexturedMesh.STRIDE);
        assertEquals(0f, bounds.centerX(), 1e-6f);
        assertEquals(2f, bounds.centerY(), 1e-6f);
        assertEquals(0f, bounds.centerZ(), 1e-6f);
        assertEquals((float) Math.sqrt(1 + 4 + 1) + MeshBounds.PADDING, bounds.radius(), 1e-5f);
    }

    @Test
    void readingTheVerticesLeavesTheBufferWhereItWas() {
        ByteBuffer buffer = positions(StaticMesh.STRIDE, new float[]{0f, 0f, 0f}, new float[]{2f, 0f, 0f});
        int position = buffer.position();
        int limit = buffer.limit();
        MeshBounds.of(buffer, StaticMesh.STRIDE);
        assertEquals(position, buffer.position(), "GL uploads from the position the mesh was given");
        assertEquals(limit, buffer.limit());
    }

    @Test
    void anEmptyMeshIsAPointAtTheOrigin() {
        assertEquals(MeshBounds.EMPTY, MeshBounds.of(ByteBuffer.allocateDirect(0), TexturedMesh.STRIDE));
    }

    /** A camera at the origin looking down +x. */
    private static ViewFrustum lookingAlongX() {
        Camera camera = new Camera(new Vector3f(0f, 0f, 0f), new Vector3f(1f, 0f, 0f), 16f / 9f);
        camera.setFarPlane(900f);
        return new ViewFrustum().set(camera);
    }

    @Test
    void aCopyInFrontIsSeenAndOneBehindIsNot() {
        MeshBounds bounds = new MeshBounds(0f, 2f, 0f, 3f);
        ViewFrustum view = lookingAlongX();
        assertTrue(bounds.visible(new Matrix4f().translation(40f, 0f, 0f), view, 0f, 0f, Float.POSITIVE_INFINITY));
        assertFalse(bounds.visible(new Matrix4f().translation(-40f, 0f, 0f), view, 0f, 0f, Float.POSITIVE_INFINITY),
                "a tree behind the camera is not drawn");
        assertFalse(bounds.visible(new Matrix4f().translation(0f, 0f, 60f), view, 0f, 0f, Float.POSITIVE_INFINITY),
                "nor one off to the side");
    }

    @Test
    void aCopyPartlyInViewIsSeen() {
        // Its centre just behind the camera's side plane, its sphere reaching across it.
        MeshBounds bounds = new MeshBounds(0f, 0f, 0f, 5f);
        ViewFrustum view = lookingAlongX();
        float sideAt20 = 20f * (float) Math.tan(Math.atan(Math.tan(Math.toRadians(Camera.DEFAULT_FOV_DEGREES / 2)) * 16f / 9f));
        assertTrue(bounds.visible(new Matrix4f().translation(20f, 0f, sideAt20 + 2f), view, 0f, 0f, Float.POSITIVE_INFINITY));
    }

    @Test
    void theScaleOfTheCopyGrowsItsSphere() {
        MeshBounds bounds = new MeshBounds(0f, 0f, 0f, 1f);
        ViewFrustum view = lookingAlongX();
        // About 12 m outside the side of the view: at radius 1 it is out; grown twenty times about the same centre it
        // reaches into view.
        Matrix4f at = new Matrix4f().translation(20f, 0f, 30f);
        assertFalse(bounds.visible(at, view, 0f, 0f, Float.POSITIVE_INFINITY));
        assertFalse(bounds.visible(new Matrix4f(at).scale(10f), view, 0f, 0f, Float.POSITIVE_INFINITY));
        assertTrue(bounds.visible(new Matrix4f(at).scale(20f), view, 0f, 0f, Float.POSITIVE_INFINITY));
    }

    @Test
    void theReachLeavesOutWhatIsFartherAcrossTheGround() {
        MeshBounds bounds = new MeshBounds(0f, 0f, 0f, 2f);
        ViewFrustum view = lookingAlongX();
        Matrix4f at50 = new Matrix4f().translation(50f, 0f, 0f);
        assertFalse(bounds.visible(at50, view, 0f, 0f, 40f), "past the reach and its own radius");
        assertTrue(bounds.visible(at50, view, 0f, 0f, 49f), "its sphere reaches back within the reach");
        assertTrue(bounds.visible(at50, view, 20f, 0f, 40f), "measured from where it is asked from");
    }
}
