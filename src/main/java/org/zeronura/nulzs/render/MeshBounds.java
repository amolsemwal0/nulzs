package org.zeronura.nulzs.render;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/**
 * A sphere round a mesh's vertices, in the mesh's own space, so a placed copy of it can be tested against what a camera
 * (or the sun's shadow box) sees without looking at its vertices again.
 *
 * <p>Read from the interleaved vertex data a mesh is made from, whose first three floats in each vertex are its
 * position — both {@link StaticMesh} and {@link TexturedMesh} lay theirs out that way. Measured once, when the mesh is
 * made, and kept with it: a culling test a frame is then a matrix multiply and a few dot products per copy.
 *
 * <p>The sphere is centred on the middle of the box round the vertices and reaches the one farthest from there, plus
 * {@link #PADDING}: shaders move some vertices after this was measured — leaves in the wind, a tuft bent aside — and a
 * sphere that fits the mesh at rest exactly would let a swaying edge be cut at the side of the screen.
 */
public record MeshBounds(float centerX, float centerY, float centerZ, float radius) {

    /** How much further than its farthest vertex a mesh's sphere reaches, in metres, for what shaders move. */
    public static final float PADDING = 0.5f;

    /** For a mesh with no vertices: a point at the origin, which every test treats as one. */
    public static final MeshBounds EMPTY = new MeshBounds(0f, 0f, 0f, 0f);

    /**
     * The sphere round the positions in interleaved vertex data, read from its position to its limit without moving
     * either.
     *
     * @param stride bytes from one vertex to the next; its first 12 are the position
     */
    public static MeshBounds of(ByteBuffer vertexData, int stride) {
        int start = vertexData.position();
        int count = vertexData.remaining() / stride;
        if (count == 0) {
            return EMPTY;
        }
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < count; v++) {
            int at = start + v * stride;
            float x = vertexData.getFloat(at);
            float y = vertexData.getFloat(at + 4);
            float z = vertexData.getFloat(at + 8);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float farthest = 0f;
        for (int v = 0; v < count; v++) {
            int at = start + v * stride;
            float dx = vertexData.getFloat(at) - cx;
            float dy = vertexData.getFloat(at + 4) - cy;
            float dz = vertexData.getFloat(at + 8) - cz;
            farthest = Math.max(farthest, dx * dx + dy * dy + dz * dz);
        }
        return new MeshBounds(cx, cy, cz, (float) Math.sqrt(farthest) + PADDING);
    }

    /**
     * Whether a copy of the mesh placed by {@code model} has any part inside {@code view}, and no farther than
     * {@code reach} across the ground from ({@code fromX}, {@code fromZ}) — infinity for no limit.
     */
    public boolean visible(Matrix4f model, ViewFrustum view, float fromX, float fromZ, float reach) {
        // The centre moved as the mesh is, and the radius grown by the largest of the three scales, so a sphere
        // stretched into an ellipsoid is never smaller than the sphere round it.
        float x = model.m00() * centerX + model.m10() * centerY + model.m20() * centerZ + model.m30();
        float y = model.m01() * centerX + model.m11() * centerY + model.m21() * centerZ + model.m31();
        float z = model.m02() * centerX + model.m12() * centerY + model.m22() * centerZ + model.m32();
        float scale2 = Math.max(model.m00() * model.m00() + model.m01() * model.m01() + model.m02() * model.m02(),
                Math.max(model.m10() * model.m10() + model.m11() * model.m11() + model.m12() * model.m12(),
                        model.m20() * model.m20() + model.m21() * model.m21() + model.m22() * model.m22()));
        float r = radius * (float) Math.sqrt(scale2);
        if (reach < Float.POSITIVE_INFINITY) {
            float dx = x - fromX;
            float dz = z - fromZ;
            float limit = reach + r;
            if (dx * dx + dz * dz > limit * limit) {
                return false;
            }
        }
        return view.containsSphere(x, y, z, r);
    }
}
