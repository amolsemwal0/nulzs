package org.zeronura.engine.render;

/**
 * A mesh the shadow pass can draw instanced without knowing its vertex layout: positions at location 0, and
 * locations 5-8 left free for {@link InstanceBuffer}'s model matrices. Meshes built from Blender node packages are
 * drawn through this.
 */
public interface InstancedVertexArray {

    int vaoHandle();

    void drawInstanced(int count);
}
