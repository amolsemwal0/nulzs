package org.zeronura.engine.nodes.render;

import org.zeronura.engine.nodes.NodePackage;
import org.zeronura.engine.nodes.shader.ShaderCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A package's materials compiled for the engine, each once, on first use. Needs a GL context, as any shader does.
 *
 * <p>{@code cel} chooses the lighting: the engine's four-step cel bands, as terrain and props here are lit (the
 * default, so imported materials sit in the game's look), or smooth shading closer to Blender's viewport.
 */
public final class NodeMaterials implements NodeMaterialSource {

    private final NodePackage pkg;
    private final boolean cel;
    private final Map<String, NodeMaterial> compiled = new LinkedHashMap<>();
    private NodeMaterial fallback;

    public NodeMaterials(NodePackage pkg, boolean cel) {
        this.pkg = pkg;
        this.cel = cel;
    }

    public NodeMaterials(NodePackage pkg) {
        this(pkg, true);
    }

    public NodePackage nodePackage() {
        return pkg;
    }

    /** A material by name, compiled the first time it is asked for; the plain grey default for an unknown name. */
    @Override
    public NodeMaterial get(String name) {
        if (name == null || name.isEmpty()) {
            return fallback();
        }
        NodeMaterial material = compiled.get(name);
        if (material == null) {
            NodePackage.MaterialDef def = pkg.material(name);
            if (def == null) {
                return fallback();
            }
            ShaderCompiler.Result result = ShaderCompiler.compile(pkg, def, cel);
            material = new NodeMaterial(name, result, pkg, def.blended(), !def.backfaceCulling(), def.alphaThreshold());
            compiled.put(name, material);
        }
        return material;
    }

    /** The material Blender draws faces with when their slot is empty: plain light grey. */
    @Override
    public NodeMaterial fallback() {
        if (fallback == null) {
            NodePackage.MaterialDef grey = new NodePackage.MaterialDef("Default", null, new float[]{0.8f, 0.8f, 0.8f, 1f},
                    0f, 0.5f, false, "DITHERED", "OPAQUE", 0.5f);
            fallback = new NodeMaterial("Default", ShaderCompiler.compile(pkg, grey, cel), pkg, false, true, 0.5f);
        }
        return fallback;
    }

    /** Every problem any compiled material reported. */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        for (NodeMaterial material : compiled.values()) {
            for (String problem : material.problems) {
                out.add(material.name + ": " + problem);
            }
        }
        return out;
    }

    public void delete() {
        for (NodeMaterial material : compiled.values()) {
            material.delete();
        }
        compiled.clear();
        if (fallback != null) {
            fallback.delete();
            fallback = null;
        }
    }
}
