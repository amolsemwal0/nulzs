package org.zeronura.nulzs.asset;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.render.TexturedMeshBuilder;
import org.zeronura.nulzs.render.TexturedMeshData;

import java.nio.ByteBuffer;

/**
 * One material's worth of an imported model: its geometry, and what it is drawn with.
 *
 * <p>Parts stay separate rather than being merged into one buffer — which is what
 * {@link AssetLoader#loadStaticGeometry} does — because a model's materials are not interchangeable.
 * The slime is the case that forced this: its outer blob is <b>translucent</b> (base colour alpha 0.5)
 * and its inner core is opaque, so they need different blend state and have to be drawn in a particular
 * order. Merged into one mesh there would be no way to express that.
 *
 * <p>GL-free: the geometry is plain arrays and the texture is still the <i>encoded</i> image bytes, so
 * a model can be imported and asserted on with no context (see {@code SlimeAssetTest}). {@link #upload}
 * and {@link #uploadTexture} are the two steps that need one.
 *
 * @param baseColor      the material's base colour factor; <b>its alpha is the part's opacity</b>
 * @param encodedTexture PNG/JPEG bytes, or null for an untextured material
 * @param twoSided       the material is made to be seen from both sides (glTF's {@code doubleSided}): a roof that
 *                       is a single sheet has no back face of its own to draw
 * @param node           the name of the node in the file the part hangs from — what a clip that moves it names — or
 *                       "" when it is not known
 */
public record TexturedPart(String name, float[] positions, float[] normals, float[] uvs, int[] indices,
                           Vector4f baseColor, ByteBuffer encodedTexture, boolean twoSided, String node) {

    /** A part hung from no node in particular. */
    public TexturedPart(String name, float[] positions, float[] normals, float[] uvs, int[] indices,
                        Vector4f baseColor, ByteBuffer encodedTexture, boolean twoSided) {
        this(name, positions, normals, uvs, indices, baseColor, encodedTexture, twoSided, "");
    }

    /** Whether this part needs alpha blending rather than being drawn opaque. */
    public boolean isTranslucent() {
        return baseColor.w < 0.999f;
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /**
     * Buffers in {@link org.zeronura.nulzs.render.TexturedMesh}'s layout. GL-free.
     *
     * <p>Vertex tint is white and the tangent is zero: this layout is shared with the tree meshes, which
     * bake a tint per vertex and carry an analytic tangent for normal mapping. An imported model has
     * neither — its colour comes from a per-instance tint uniform, and it has no normal map — so rather
     * than a fifth vertex layout for the sake of 24 bytes, the unused channels are filled and the entity
     * shader simply does not read them.
     */
    public TexturedMeshData toMeshData() {
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        Vector3f white = new Vector3f(1f, 1f, 1f);
        Vector3f noTangent = new Vector3f();
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();

        for (int v = 0; v < vertexCount(); v++) {
            position.set(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2]);
            normal.set(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2]);
            builder.addVertex(position, normal, uvs[v * 2], uvs[v * 2 + 1], white, noTangent);
        }
        for (int i = 0; i < indices.length; i += 3) {
            builder.addTriangle(indices[i], indices[i + 1], indices[i + 2]);
        }
        return builder.buildData();
    }

    /** The part's texture, or a white 1x1 for an untextured material. Must run on the GL thread. */
    public Texture uploadTexture() {
        return encodedTexture == null
                ? Texture.solidColor(255, 255, 255, 255)
                : Texture.fromMemory(encodedTexture.duplicate());
    }
}
