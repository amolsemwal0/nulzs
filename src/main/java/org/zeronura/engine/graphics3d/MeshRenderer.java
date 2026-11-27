package org.zeronura.engine.graphics3d;

import org.joml.Matrix4f;
import org.zeronura.engine.render.StaticInstance;
import org.zeronura.engine.render.StaticMesh;
import org.zeronura.engine.scene.Component;

/**
 * Draws a lit, shadowed {@link StaticMesh} at its entity's transform.
 *
 * <p>Many renderers can share one mesh; the world draws all instances of a mesh in a single instanced
 * call, so a forest of one tree mesh costs one draw. Keep scale uniform — lighting assumes it.
 */
public class MeshRenderer extends Component {

    public StaticMesh mesh;
    public boolean castShadows = true;

    private final Matrix4f matrix = new Matrix4f();

    public MeshRenderer(StaticMesh mesh) {
        this.mesh = mesh;
    }

    public MeshRenderer withoutShadows() {
        castShadows = false;
        return this;
    }

    StaticInstance instance() {
        return new StaticInstance(mesh, transform().worldMatrix(matrix));
    }
}
