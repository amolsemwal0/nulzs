"""Writes the catalog of shader nodes the editor's Shader Tool offers, straight from Blender.

Each node the engine's shader compiler supports is added to a scratch material and written out the way
node_export.py writes nodes -- every socket's id, name, type and default, and every setting -- so a node made in the
editor is exactly the node Blender would have exported. For each dropdown setting (a Math node's operation, a Noise
Texture's dimensions) the catalog also records which sockets show for each choice, as Blender shows them.

    blender --background --factory-startup --python tools/blender_nodes/make_shader_catalog.py -- \\
        --out src/main/resources/nodes/shader_nodes.json
"""

import json
import os
import sys
import tempfile

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import node_export  # noqa: E402

# The Shader Tool's Add menu, in order. "Type:DATA" fixes a Mix node's data type, offered as three nodes.
CATEGORIES = [
    ("Output", ["ShaderNodeOutputMaterial"]),
    ("Shader", ["ShaderNodeBsdfPrincipled", "ShaderNodeBsdfDiffuse", "ShaderNodeBsdfToon", "ShaderNodeEmission",
                "ShaderNodeBsdfMetallic", "ShaderNodeBsdfAnisotropic", "ShaderNodeBsdfGlass", "ShaderNodeBsdfRefraction",
                "ShaderNodeBsdfTranslucent", "ShaderNodeBsdfTransparent", "ShaderNodeBsdfSheen",
                "ShaderNodeSubsurfaceScattering", "ShaderNodeHoldout", "ShaderNodeMixShader", "ShaderNodeAddShader",
                "ShaderNodeShaderToRGB"]),
    ("Input", ["ShaderNodeTexCoord", "ShaderNodeNewGeometry", "ShaderNodeUVMap", "ShaderNodeAttribute",
               "ShaderNodeVertexColor", "ShaderNodeObjectInfo", "ShaderNodeCameraData", "ShaderNodeLightPath",
               "ShaderNodeFresnel", "ShaderNodeLayerWeight", "ShaderNodeValue", "ShaderNodeRGB", "ShaderNodeTangent",
               "ShaderNodeWireframe", "ShaderNodeAmbientOcclusion", "GeometryNodeInputSceneTime"]),
    ("Texture", ["ShaderNodeTexNoise", "ShaderNodeTexVoronoi", "ShaderNodeTexWave", "ShaderNodeTexMagic",
                 "ShaderNodeTexBrick", "ShaderNodeTexChecker", "ShaderNodeTexGradient", "ShaderNodeTexWhiteNoise",
                 "ShaderNodeTexImage"]),
    ("Color", ["ShaderNodeMix:RGBA", "ShaderNodeInvert", "ShaderNodeGamma", "ShaderNodeBrightContrast",
               "ShaderNodeHueSaturation", "ShaderNodeRGBCurve", "ShaderNodeValToRGB", "ShaderNodeRGBToBW",
               "ShaderNodeBlackbody", "ShaderNodeWavelength"]),
    ("Converter", ["ShaderNodeMath", "ShaderNodeMix:FLOAT", "ShaderNodeMapRange", "ShaderNodeClamp",
                   "ShaderNodeFloatCurve", "ShaderNodeCombineXYZ", "ShaderNodeSeparateXYZ", "ShaderNodeCombineColor",
                   "ShaderNodeSeparateColor"]),
    ("Vector", ["ShaderNodeVectorMath", "ShaderNodeMix:VECTOR", "ShaderNodeMapping", "ShaderNodeVectorRotate",
                "ShaderNodeNormal", "ShaderNodeBump", "ShaderNodeNormalMap", "ShaderNodeVectorTransform",
                "ShaderNodeVectorCurve", "ShaderNodeDisplacement"]),
]


def enabled(node):
    def ids(sockets):
        return [s.identifier for s in sockets
                if s.bl_idname != "NodeSocketVirtual" and bool(getattr(s, "enabled", True))
                and not getattr(s, "is_unavailable", False)]
    return ids(node.inputs), ids(node.outputs)


def enum_settings(node, fixed):
    """Each dropdown setting: its choices, and the sockets that show for each, the other settings left as they are."""
    out = {}
    base = node_export._base_node_props()
    for prop in node.bl_rna.properties:
        name = prop.identifier
        if prop.type != "ENUM" or prop.is_readonly or name in base or name in node_export._SKIPPED_PROPS:
            continue
        if name in fixed or getattr(prop, "is_enum_flag", False):
            continue
        items = [[item.identifier, item.name] for item in prop.enum_items]
        if len(items) < 2:
            continue
        original = getattr(node, name)
        shown_inputs = {}
        shown_outputs = {}
        for identifier, _label in items:
            try:
                setattr(node, name, identifier)
            except Exception:
                continue
            ins, outs = enabled(node)
            shown_inputs[identifier] = ins
            shown_outputs[identifier] = outs
        setattr(node, name, original)
        out[name] = {"items": items, "inputs": shown_inputs, "outputs": shown_outputs}
    return out


def main(argv):
    out_path = argv[argv.index("--out") + 1] if "--out" in argv else "shader_nodes.json"
    material = bpy.data.materials.new("Catalog")
    material.use_nodes = True
    tree = material.node_tree
    tree.nodes.clear()
    exporter = node_export.Exporter(tempfile.mkdtemp(), bake=False)
    catalog = {"format": "zeronura-shader-nodes", "blender": bpy.app.version_string, "categories": []}
    skipped = []
    for category, entries in CATEGORIES:
        nodes = []
        for entry in entries:
            idname, _, data_type = entry.partition(":")
            try:
                node = tree.nodes.new(idname)
            except Exception as e:  # not in this Blender
                skipped.append(f"{entry}: {e}")
                continue
            fixed = set()
            if data_type:
                node.data_type = data_type
                fixed.add("data_type")
            data = exporter.node(node)
            data.pop("name", None)
            for socket in data["inputs"] + data["outputs"]:
                socket.pop("linked", None)
            label = node.bl_label
            if data_type:
                label = {"RGBA": "Mix Color", "FLOAT": "Mix", "VECTOR": "Mix Vector"}[data_type]
            nodes.append({"id": entry, "label": label, "node": data, "settings": enum_settings(node, fixed),
                          "fixed": sorted(fixed)})
        catalog["categories"].append({"name": category, "nodes": nodes})
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(catalog, f, indent=1)
    count = sum(len(c["nodes"]) for c in catalog["categories"])
    print(f"Wrote {count} nodes to {out_path}")
    for line in skipped:
        print("Skipped", line)


if __name__ == "__main__":
    main(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])
