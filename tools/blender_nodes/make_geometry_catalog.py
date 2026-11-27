"""Writes the catalog of geometry nodes the editor's Geometry Nodes Tool offers, straight from Blender.

As make_shader_catalog.py does for shader nodes: each node is added to a scratch geometry node tree and written out
the way node_export.py writes nodes (every socket's id, name, type and default, and every setting), with, for each
dropdown setting, which sockets each choice shows. On top of that a socket that takes many links (Join Geometry's) is
marked "multi_input", and a zone (Repeat, Simulation) is one entry holding both its nodes, paired.

The Add menu is Blender's own: its categories, sub-menus and order are read from Blender's add-menu script
(bl_ui/node_add_menu_geometry.py). Only the nodes the engine's GeometryEvaluator runs are offered: pass their types,
one a line, with --supported (GeometryEvaluator's main writes them). make_geometry_catalog.sh does both:

    tools/blender_nodes/make_geometry_catalog.sh "/path/to/blender"
"""

import json
import os
import re
import sys
import tempfile

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import node_export  # noqa: E402
from make_shader_catalog import enum_settings  # noqa: E402

# Nodes Blender offers that mean nothing in the engine: references to Blender's objects, collections and images, the
# viewport's gizmos and viewer, and the one Group Output a tree has.
LEFT_OUT = {"GeometryNodeObjectInfo", "GeometryNodeCollectionInfo", "GeometryNodeSelfObject", "GeometryNodeImageInfo",
            "GeometryNodeInputImage", "GeometryNodeImageTexture", "GeometryNodeGizmoDial", "GeometryNodeGizmoLinear",
            "GeometryNodeGizmoTransform", "GeometryNodeViewer", "NodeGroupOutput", "GeometryNodeIsViewport"}

ZONES = {"repeat_zone": ("GeometryNodeRepeatInput", "GeometryNodeRepeatOutput"),
         "simulation_zone": ("GeometryNodeSimulationInput", "GeometryNodeSimulationOutput"),
         "for_each_element_zone": ("GeometryNodeForeachGeometryElementInput", "GeometryNodeForeachGeometryElementOutput")}


def read_menus():
    """Blender's geometry Add menu: each menu's path and what it draws, in order (node types, zones, sub-menus)."""
    script = os.path.join(bpy.utils.resource_path("LOCAL"), "scripts", "startup", "bl_ui", "node_add_menu_geometry.py")
    if not os.path.exists(script):
        script = os.path.join(os.path.dirname(bpy.__file__), "..", "bl_ui", "node_add_menu_geometry.py")
    text = open(script).read()
    menus = {}
    for match in re.finditer(r"class (NODE_MT_\w+)\(node_add_menu\.NodeMenu\):(.*?)(?=\nclass |\Z)", text, re.S):
        body = match.group(2)
        label = re.search(r'bl_label = "([^"]*)"', body)
        path = re.search(r'menu_path = "([^"]*)"', body)
        key = path.group(1) if path else (label.group(1) if label else None)
        if key is None:
            continue
        items = []
        draw = body[body.find("def draw"):]
        # Each call in the order drawn; a node's fixed settings (props.settings.add()) follow its call.
        calls = re.finditer(r'node_operator(?:_with_outputs|_with_searchable_enum)?\(\s*(?:context,\s*)?layout,\s*"(\w+)"'
                            r'(?:[^)]*?label="([^"]+)")?'
                            r'|(color_mix_node)\('
                            r'|draw_menu\(layout, (?:path=)?"([^"]+)"'
                            r'|self\.(repeat_zone|simulation_zone|for_each_element_zone)\(layout, label="([^"]+)"', draw)
        calls = list(calls)
        for index, item in enumerate(calls):
            if item.group(1):
                tail = draw[item.end(): calls[index + 1].start() if index + 1 < len(calls) else len(draw)]
                fixed = dict(re.findall(r'ops\.name = "(\w+)"\s*ops\.value = "\'(\w+)\'"', tail))
                items.append(("node", item.group(1), item.group(2), fixed))
            elif item.group(3):
                items.append(("node", "ShaderNodeMix", "Mix Color", {"data_type": "RGBA"}))
            elif item.group(4):
                items.append(("menu", item.group(4)))
            else:
                items.append(("zone", item.group(5), item.group(6)))
        menus[key] = items
    return menus


def flatten(menus, path, out, seen):
    """Depth first from a menu: [(category path, [entries])], each sub-menu after the menu that holds it."""
    if path in seen or path not in menus:
        return
    seen.add(path)
    entries = [item for item in menus[path] if item[0] != "menu"]
    out.append((path, entries))
    for item in menus[path]:
        if item[0] == "menu":
            flatten(menus, item[1], out, seen)


def export(exporter, node):
    data = exporter.node(node)
    data.pop("name", None)
    data.pop("pair", None)
    for socket, source in zip(data["inputs"], [s for s in node.inputs if s.bl_idname != "NodeSocketVirtual"]):
        socket.pop("linked", None)
        if getattr(source, "is_multi_input", False):
            socket["multi_input"] = True
    for socket in data["outputs"]:
        socket.pop("linked", None)
    return data


def main(argv):
    out_path = argv[argv.index("--out") + 1] if "--out" in argv else "geometry_nodes.json"
    supported = None
    if "--supported" in argv:
        supported = {line.strip() for line in open(argv[argv.index("--supported") + 1]) if line.strip()}
    tree = bpy.data.node_groups.new("Catalog", "GeometryNodeTree")
    exporter = node_export.Exporter(tempfile.mkdtemp(), bake=False)
    catalog = {"format": "zeronura-geometry-nodes", "blender": bpy.app.version_string, "categories": []}
    menus = read_menus()
    order = []
    for item in menus.get("Root", []):
        if item[0] == "menu":
            flatten(menus, item[1], order, set())
    skipped = []
    offered = set()
    for path, entries in order:
        nodes = []
        for entry in entries:
            if entry[0] == "zone":
                kinds = ZONES[entry[1]]
                if supported is not None and not all(k in supported for k in kinds):
                    skipped.append(f"{entry[2]} zone: not run by the engine")
                    continue
                tree.nodes.clear()
                zone_in = tree.nodes.new(kinds[0])
                zone_out = tree.nodes.new(kinds[1])
                zone_in.pair_with_output(zone_out)
                nodes.append({"id": "zone:" + kinds[0], "label": entry[2] + " Zone", "node": export(exporter, zone_in),
                              "zone": export(exporter, zone_out), "settings": enum_settings(zone_in, set()),
                              "zone_settings": enum_settings(zone_out, set()), "fixed": []})
                continue
            idname, label, fixed = entry[1], entry[2], entry[3]
            if idname == "ShaderNodeMix" and not fixed:
                fixed = {"data_type": "FLOAT"}
            key = idname + "".join(":" + value for value in fixed.values())
            if key in offered or idname in LEFT_OUT:
                continue
            if supported is not None and idname not in supported:
                skipped.append(f"{idname}: not run by the engine")
                continue
            tree.nodes.clear()
            try:
                node = tree.nodes.new(idname)
                for name, value in fixed.items():
                    setattr(node, name, value)
            except Exception as e:  # not in this Blender
                skipped.append(f"{key}: {e}")
                continue
            offered.add(key)
            offered.add(idname)
            nodes.append({"id": key, "label": label or node.bl_label, "node": export(exporter, node),
                          "settings": enum_settings(node, set(fixed)), "fixed": sorted(fixed)})
        if nodes:
            catalog["categories"].append({"name": path, "nodes": nodes})
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w") as f:
        json.dump(catalog, f, indent=1)
    count = sum(len(c["nodes"]) for c in catalog["categories"])
    print(f"Wrote {count} nodes in {len(catalog['categories'])} menus to {out_path}")
    if supported is not None:
        missing = sorted(supported - offered - {"NodeGroupInput", "NodeGroupOutput", "NodeReroute", "NodeFrame",
                                                "GeometryNodeGroup", "GeometryNodeRepeatInput", "GeometryNodeRepeatOutput",
                                                "GeometryNodeSimulationInput", "GeometryNodeSimulationOutput"})
        if missing:
            print("Run by the engine but in no Add menu:", ", ".join(missing))
    for line in skipped:
        print("Skipped", line)


if __name__ == "__main__":
    main(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])
