package org.zeronura.engine.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A shared {@link TexturedMesh} placed in the world by a per-instance transform.
 *
 * <p>The textured counterpart to {@link StaticInstance}, and the same bargain: one mesh is built once and
 * drawn many times at different positions, rotations and scales. It is what lets a pool of tree variants
 * fill a forest without a mesh per tree.
 *
 * @param tint a per-instance albedo multiplier, on top of the mesh's own baked vertex tint. White is the
 *             no-op default; it exists so the same shared tree mesh can be darkened where a biome demands
 *             it (the dark forest's grey-black overlay) without a second mesh.
 */
public record TexturedInstance(TexturedMesh mesh, Matrix4f modelMatrix, Vector3f tint) {

    private static final Vector3f WHITE = new Vector3f(1f, 1f, 1f);

    /** An untinted instance — the common case for props in the ordinary biomes. */
    public TexturedInstance(TexturedMesh mesh, Matrix4f modelMatrix) {
        this(mesh, modelMatrix, WHITE);
    }
}
