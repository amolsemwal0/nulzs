package org.zeronura.nulzs.graphics3d;

import org.zeronura.nulzs.core.Assets;
import org.zeronura.nulzs.nodes.NodePackage;
import org.zeronura.nulzs.nodes.render.NodeMaterial;
import org.zeronura.nulzs.nodes.render.NodeMaterialSource;
import org.zeronura.nulzs.nodes.render.NodeMaterials;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Materials named by the project's saved shaders' asset paths ({@code materials/Stone.nodes.json}), from the
 * {@link MaterialLibrary}: what a Geometry Nodes Tool tree's Set Material picks. Anything else wears plain grey.
 */
public final class AssetMaterials implements NodeMaterialSource {

    private static NodeMaterials grey;

    private final Assets assets;

    public AssetMaterials(Assets assets) {
        this.assets = assets;
    }

    @Override
    public NodeMaterial get(String name) {
        if (name == null || name.isBlank() || !name.endsWith(".nodes.json")) {
            return fallback();
        }
        NodeMaterial material = MaterialLibrary.get(assets, name, "");
        return material != null ? material : fallback();
    }

    @Override
    public NodeMaterial fallback() {
        synchronized (AssetMaterials.class) {
            if (grey == null) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("format", "zeronura-node-package");
                grey = new NodeMaterials(NodePackage.fromJson(Path.of("grey.nodes.json"), empty));
            }
            return grey.fallback();
        }
    }
}
