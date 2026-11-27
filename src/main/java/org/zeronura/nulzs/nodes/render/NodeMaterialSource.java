package org.zeronura.nulzs.nodes.render;

/**
 * Where a {@link BlenderModel}'s materials come from, by the names its geometry gives its material slots: a node
 * package's own ({@link NodeMaterials}), or the project's saved Shader Tool materials by asset path (a Geometry Nodes
 * Tool tree's Set Material names one so).
 */
public interface NodeMaterialSource {

    /** The material of that name, or {@link #fallback} for an unknown or empty one. */
    NodeMaterial get(String name);

    /** What a slot with no material wears: plain light grey. */
    NodeMaterial fallback();
}
