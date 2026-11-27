package org.zeronura.nulzs.nodes.render;

import org.joml.Matrix4f;
import org.zeronura.nulzs.graphics3d.World3D;
import org.zeronura.nulzs.scene.Component;

/**
 * Draws a {@link BlenderModel} at its entity's transform, lit and shadowed with the rest of the 3D world: the
 * component a Blender object becomes. Renderers sharing a model share its meshes and are drawn instanced.
 */
public class BlenderMeshRenderer extends Component {

    public BlenderModel model;
    public boolean castShadows = true;

    private final Matrix4f world = new Matrix4f();

    public BlenderMeshRenderer(BlenderModel model) {
        this.model = model;
    }

    public BlenderMeshRenderer withoutShadows() {
        castShadows = false;
        return this;
    }

    @Override
    protected void start() {
        World3D world3D = scene().world3D();
        if (world3D.pass(NodeRenderer.class) == null) {
            world3D.addPass(new NodeRenderer(scene()));
        }
    }

    Matrix4f worldMatrix() {
        return transform().worldMatrix(world);
    }
}
