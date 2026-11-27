package org.zeronura.engine.asset;

import org.joml.Vector3f;
import org.zeronura.engine.render.MeshData;
import org.zeronura.engine.render.StaticMesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * CPU-side triangle geometry pulled out of a model file, with its node transforms already baked in and
 * recentred on the origin — position and normal per vertex, no skinning, no material.
 *
 * <p>This is what {@link AssetLoader#loadStaticGeometry} produces for a static prop such as a building
 * block: the same {@code Model}/skinned-{@code Mesh} pipeline that loads the character is far more than a
 * cube needs, and a block wants to render through the untextured, per-vertex-colour {@link StaticMesh}
 * prop path so it can be tinted per material. So the geometry is captured here once, GL-free, and
 * {@link #toMeshData(Vector3f)} bakes a chosen tint into a ready-to-upload {@link MeshData} — the same
 * split (build off the GL thread, upload on it) the terrain mesher uses.
 *
 * <p>Recentring on the bounding-box centre discards whatever arbitrary translation the exporter left on
 * the node, so a placed piece is positioned by its own centre regardless of where it sat in the source
 * file. The retained half-extents are that centred box, which is what collision and snapping measure with.
 */
public record StaticGeometry(float[] positions, float[] normals, int[] indices,
                             float halfExtentX, float halfExtentY, float halfExtentZ) {

    /** Bakes a flat tint into a {@link MeshData} in {@link StaticMesh}'s 36-byte layout. GL-free. */
    public MeshData toMeshData(Vector3f color) {
        int vertexCount = positions.length / 3;
        ByteBuffer vertexData = ByteBuffer.allocateDirect(vertexCount * StaticMesh.STRIDE)
                .order(ByteOrder.nativeOrder());
        for (int v = 0; v < vertexCount; v++) {
            int p = v * 3;
            vertexData.putFloat(positions[p]).putFloat(positions[p + 1]).putFloat(positions[p + 2]);
            vertexData.putFloat(normals[p]).putFloat(normals[p + 1]).putFloat(normals[p + 2]);
            vertexData.putFloat(color.x).putFloat(color.y).putFloat(color.z);
        }
        vertexData.flip();

        IntBuffer indexData = ByteBuffer.allocateDirect(indices.length * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        indexData.put(indices);
        indexData.flip();

        return new MeshData(vertexData, indexData);
    }
}
