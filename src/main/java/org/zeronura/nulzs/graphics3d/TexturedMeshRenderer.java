package org.zeronura.nulzs.graphics3d;

import org.joml.Matrix4f;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.TexturedInstance;
import org.zeronura.nulzs.render.TexturedMesh;
import org.zeronura.nulzs.scene.Component;

/**
 * Draws a UV-mapped {@link TexturedMesh} in a {@link FoliageMaterial} at its entity's transform: the textured
 * counterpart of {@link MeshRenderer}, for a room's plastered walls and planked floor or a prop dressed in one of a
 * prefab's textured looks.
 *
 * <p>The mesh and the material stay the caller's: a scene that built the mesh deletes it when it is done, and textures
 * belong to whoever loaded them. Renderers sharing a material are drawn in one pass. Keep scale uniform, as for
 * {@link MeshRenderer}.
 */
public class TexturedMeshRenderer extends Component {

    public TexturedMesh mesh;
    public FoliageMaterial material;
    public boolean castShadows = true;

    private final Matrix4f matrix = new Matrix4f();

    public TexturedMeshRenderer(TexturedMesh mesh, FoliageMaterial material) {
        this.mesh = mesh;
        this.material = material;
    }

    public TexturedMeshRenderer withoutShadows() {
        castShadows = false;
        return this;
    }

    TexturedInstance instance() {
        return new TexturedInstance(mesh, new Matrix4f(transform().worldMatrix(matrix)));
    }
}
