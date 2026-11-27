package org.zeronura.engine.asset;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * A model built the way a procedural-geometry exporter builds one: a handful of tiny <b>prototype</b>
 * meshes, and a long list of transforms placing copies of them.
 *
 * <p>This is a different shape of asset from anything {@link AssetLoader}'s other three loaders handle, and
 * the difference is not cosmetic. A scattering tool — Blender's geometry nodes, a speed-tree style
 * generator — does not author a tree as one mesh; it authors a twig and a leaf and then stamps them tens of
 * thousands of times. {@code assets/Environment/tree.glb} is exactly that: three prototypes totalling
 * <b>76 triangles</b> between them, and <b>20,464</b> nodes placing them.
 *
 * <h2>Why the existing loaders cannot read one</h2>
 * {@link AssetLoader#loadTexturedParts} produces a part per node-mesh and copies that material's encoded
 * texture into each one. On this file that is twenty thousand parts each holding a two-to-five megabyte
 * image, and it does not fail gracefully — it exhausted <b>8 GB</b> of direct buffer memory and died. The
 * fix is structural rather than a bigger heap: a texture belongs to a <i>material</i>, so it is held once
 * per prototype here and the instances carry nothing but a transform.
 *
 * <h2>What this buys beyond loading at all</h2>
 * The decomposition is the whole reason a caller can vary the model. Asked for a tree with fewer branches
 * or a thinner canopy, a merged mesh can offer nothing; a list of twig instances and a list of leaf
 * instances can simply be sampled. See {@code HighlandCanopy}.
 *
 * <p>GL-free, like {@link TexturedPart}: geometry is plain arrays and textures are still encoded bytes, so
 * a model can be imported and measured with no context.
 *
 * @param prototypes the distinct meshes, in the order the instances index them
 * @param instances  one placement each; {@link Instance#prototype} indexes {@link #prototypes}
 */
public record InstancedModel(List<Prototype> prototypes, List<Instance> instances) {

    /**
     * One prototype mesh and the material it is drawn with.
     *
     * @param baseColor      the material's base colour factor; its alpha is the part's opacity
     * @param encodedTexture PNG/JPEG bytes, or null for an untextured material
     */
    public record Prototype(String materialName, float[] positions, float[] normals, float[] uvs,
                            int[] indices, Vector4f baseColor, ByteBuffer encodedTexture) {

        public int vertexCount() {
            return positions.length / 3;
        }

        public int triangleCount() {
            return indices.length / 3;
        }
    }

    /** One placement of one prototype. The transform is already resolved into model space. */
    public record Instance(int prototype, Matrix4f transform) {
    }

    /** How many instances reference a given prototype. */
    public int instanceCount(int prototype) {
        int n = 0;
        for (Instance i : instances) {
            if (i.prototype() == prototype) {
                n++;
            }
        }
        return n;
    }

    /** The index of the first prototype whose material has this name, or -1. */
    public int prototypeNamed(String materialName) {
        for (int i = 0; i < prototypes.size(); i++) {
            if (prototypes.get(i).materialName().equalsIgnoreCase(materialName)) {
                return i;
            }
        }
        return -1;
    }
}
