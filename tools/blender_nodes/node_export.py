"""Exports Blender shader and geometry node trees as a node package the engine runs itself.

A node package is one JSON file (plus the images it uses) holding everything the engine's node system
(org.zeronura.engine.nodes) needs to rebuild a scene from its nodes rather than from baked meshes:

  * every node tree in use -- materials' shader trees, geometry-nodes modifiers and the groups they call --
    node by node, with each socket's value, every node's settings, colour ramps and sampled curves;
  * every object: its base mesh or curve (before its first geometry-nodes modifier), its transform, its
    material slots and its modifier stack with the values set on the modifier;
  * the objects and collections those trees point at (Object Info, Collection Info), so they can be
    instanced at run time;
  * images, copied next to the package; and
  * a bake of each object as Blender evaluates it, which the engine falls back on for any tree that uses
    a node it does not implement, and which the tests compare the engine's own evaluation against.

Everything stays in Blender's own space (Z up, metres) and colours stay scene-linear: the engine converts
at the edge, so node maths sees exactly the numbers Blender's does.

Two ways to run it:

  From the command line, like the other tools here (run from the repository root):

      blender --background scene.blend --python tools/blender_nodes/node_export.py -- \\
          --out projects/MyGame/assets/blender/scene [--collection Props] [--objects Rock,Fence] \\
          [--no-bake] [--all-materials]

  As an add-on: Edit > Preferences > Add-ons > Install from Disk, pick this file, then
  File > Export > Engine Node Package (.nodes.json). The exported objects are the selection, or the
  whole scene when nothing is selected.
"""

bl_info = {
    "name": "Engine Node Package",
    "author": "zeronura",
    "version": (1, 0, 0),
    "blender": (4, 2, 0),
    "location": "File > Export > Engine Node Package (.nodes.json)",
    "description": "Export shader and geometry node trees for the zeronura engine's node system",
    "category": "Import-Export",
}

import base64
import json
import os
import shutil
import sys

import bpy

try:
    import numpy as np
except ImportError:  # Blender always ships numpy; this only matters for linting outside it.
    np = None

FORMAT = "zeronura-node-package"
VERSION = 1

# Properties every node has, which say nothing about what it computes.
_BASE_NODE_PROPS = None

# Node settings that are UI state, not behaviour.
_SKIPPED_PROPS = {
    "rna_type", "active_index", "active_item", "active_input_index", "active_output_index",
    "active_generation_index", "active_main_index", "show_options", "show_preview", "show_texture",
    "inspection_index", "warning_propagation", "texture_mapping", "color_mapping",
    "image_user", "interface", "node_tree", "paired_output", "color_ramp", "mapping", "image", "bake_items",
}


def _base_node_props():
    global _BASE_NODE_PROPS
    if _BASE_NODE_PROPS is None:
        _BASE_NODE_PROPS = {p.identifier for p in bpy.types.Node.bl_rna.properties}
    return _BASE_NODE_PROPS


def _b64(array, dtype):
    return base64.b64encode(np.ascontiguousarray(array, dtype=dtype).tobytes()).decode("ascii")


def _id_kind(value):
    for kind, cls in (("OBJECT", bpy.types.Object), ("COLLECTION", bpy.types.Collection),
                      ("MATERIAL", bpy.types.Material), ("IMAGE", bpy.types.Image),
                      ("TEXTURE", bpy.types.Texture), ("FONT", bpy.types.VectorFont),
                      ("NODETREE", bpy.types.NodeTree)):
        if isinstance(value, cls):
            return kind
    return "ID"


class Exporter:
    def __init__(self, out_dir, bake=True, all_materials=False, all_groups=True):
        self.out_dir = os.path.abspath(out_dir)
        self.bake = bake
        self.all_materials = all_materials
        self.all_groups = all_groups
        self.trees = {}
        self.materials = {}
        self.images = {}
        self.meshes = {}
        self.objects = {}
        self.collections = {}
        self.warnings = []
        self._object_queue = []
        self._temporary = None

    # --- References ------------------------------------------------------------------------------

    def ref(self, value):
        """A data-block a socket or setting points at, exported alongside so the engine can resolve it."""
        if value is None:
            return None
        kind = _id_kind(value)
        if kind == "OBJECT":
            self.queue_object(value)
        elif kind == "COLLECTION":
            self.export_collection(value)
        elif kind == "MATERIAL":
            self.export_material(value)
        elif kind == "IMAGE":
            self.export_image(value)
        elif kind == "NODETREE":
            self.export_tree(value)
        return {"ref": value.name, "kind": kind}

    def plain(self, value):
        """A socket or property value as JSON: numbers, text, vectors, or a reference."""
        if value is None:
            return None
        if isinstance(value, bpy.types.ID):
            return self.ref(value)
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float, str)):
            return value
        if isinstance(value, (set, frozenset)):
            # An enum flag's choices, such as the channels a node works on.
            return sorted(str(item) for item in value)
        try:
            flat = []
            for item in value:
                try:
                    flat.extend(float(x) for x in item)
                except TypeError:
                    flat.append(float(item))
            return flat
        except (TypeError, ValueError):
            return None

    # --- Node trees ------------------------------------------------------------------------------

    def tree_key(self, tree):
        return tree.name if tree.library is None else tree.name + " [" + tree.library.name + "]"

    def export_tree(self, tree, key=None):
        key = key or self.tree_key(tree)
        if key in self.trees:
            return key
        self.trees[key] = None  # Reserves the key, so a tree that reaches itself through a group stops.
        kind = {"GeometryNodeTree": "GEOMETRY", "ShaderNodeTree": "SHADER"}.get(tree.bl_idname, tree.bl_idname)
        data = {"name": tree.name, "type": kind, "interface": self.interface(tree), "nodes": [], "links": []}
        for node in tree.nodes:
            if node.bl_idname == "NodeFrame":
                continue
            data["nodes"].append(self.node(node))
        for link in tree.links:
            if not link.is_valid or link.is_muted:
                continue
            if link.from_node.bl_idname == "NodeFrame" or link.to_node.bl_idname == "NodeFrame":
                continue
            data["links"].append({
                "from_node": link.from_node.name, "from_socket": link.from_socket.identifier,
                "to_node": link.to_node.name, "to_socket": link.to_socket.identifier,
                "from_type": link.from_socket.type, "to_type": link.to_socket.type,
                "order": getattr(link, "multi_input_sort_id", 0),
            })
        self.trees[key] = data
        return key

    def interface(self, tree):
        items = []
        for item in tree.interface.items_tree:
            if item.item_type != "SOCKET":
                continue
            entry = {"in_out": item.in_out, "name": item.name, "id": item.identifier,
                     "socket": item.socket_type, "type": _socket_kind(item.socket_type)}
            for attr in ("default_value", "min_value", "max_value"):
                if hasattr(item, attr):
                    entry[attr.replace("_value", "")] = self.plain(getattr(item, attr))
            for attr in ("subtype", "default_attribute_name", "attribute_domain", "hide_value", "structure_type",
                         "default_input", "force_non_field"):
                if hasattr(item, attr):
                    entry[attr] = getattr(item, attr)
            items.append(entry)
        return items

    def socket(self, socket):
        entry = {"id": socket.identifier, "name": socket.name, "type": socket.type,
                 "enabled": bool(getattr(socket, "enabled", True)) and not getattr(socket, "is_unavailable", False)}
        if socket.is_linked:
            entry["linked"] = True
        if hasattr(socket, "default_value"):
            try:
                entry["value"] = self.plain(socket.default_value)
            except Exception:
                pass
        if getattr(socket, "hide_value", False):
            entry["hide_value"] = True
        return entry

    def node(self, node):
        data = {"name": node.name, "type": node.bl_idname, "props": {},
                "inputs": [self.socket(s) for s in node.inputs if s.bl_idname != "NodeSocketVirtual"],
                "outputs": [self.socket(s) for s in node.outputs if s.bl_idname != "NodeSocketVirtual"]}
        if node.label:
            data["label"] = node.label
        if node.mute:
            data["mute"] = True
            data["internal_links"] = [[l.from_socket.identifier, l.to_socket.identifier] for l in node.internal_links]
        base = _base_node_props()
        for prop in node.bl_rna.properties:
            name = prop.identifier
            if name in base or name in _SKIPPED_PROPS:
                continue
            try:
                value = getattr(node, name)
            except Exception:
                continue
            if prop.type == "COLLECTION":
                data["props"][name] = [self.item(item) for item in value]
            elif prop.type == "POINTER":
                if isinstance(value, bpy.types.ID):
                    data["props"][name] = self.ref(value)
            else:
                converted = self.plain(value)
                if converted is not None:
                    data["props"][name] = converted
        tree = getattr(node, "node_tree", None)
        if tree is not None:
            data["tree"] = self.export_tree(tree)
        paired = getattr(node, "paired_output", None)
        if paired is not None:
            data["pair"] = paired.name
        image = getattr(node, "image", None)
        if isinstance(image, bpy.types.Image):
            data["image"] = self.export_image(image)
        ramp = getattr(node, "color_ramp", None)
        if ramp is not None:
            data["ramp"] = {"interpolation": ramp.interpolation, "color_mode": ramp.color_mode,
                            "hue_interpolation": ramp.hue_interpolation,
                            "stops": [{"position": e.position, "color": list(e.color)} for e in ramp.elements]}
        mapping = getattr(node, "mapping", None)
        if mapping is not None and hasattr(mapping, "curves"):
            data["curves"] = self.curve_mapping(mapping)
        return data

    def item(self, item):
        """An entry of a node's item list: a repeat zone's state, a capture's attribute, a menu's option."""
        out = {}
        for prop in item.bl_rna.properties:
            if prop.identifier == "rna_type":
                continue
            try:
                value = getattr(item, prop.identifier)
            except Exception:
                continue
            if prop.type in ("POINTER", "COLLECTION") and not isinstance(value, bpy.types.ID):
                continue
            converted = self.plain(value)
            if converted is not None:
                out[prop.identifier] = converted
        return out

    def curve_mapping(self, mapping, samples=257):
        """A curve widget as Blender evaluates it: a 257-entry table across its clipping range, read with straight
        lines between entries. Sampling exactly that table reproduces Blender's values."""
        try:
            mapping.initialize()
        except Exception:
            pass
        out = {"extend": mapping.extend, "black_level": list(mapping.black_level),
               "white_level": list(mapping.white_level), "curves": [], "use_clip": mapping.use_clip,
               "clip_min_y": mapping.clip_min_y, "clip_max_y": mapping.clip_max_y}
        lo, hi = mapping.clip_min_x, mapping.clip_max_x
        if hi - lo < 1e-5:
            hi = lo + 1e-5
        for curve in mapping.curves:
            values = [mapping.evaluate(curve, lo + (hi - lo) * i / (samples - 1)) for i in range(samples)]
            span = hi - lo
            slope_in = (values[0] - mapping.evaluate(curve, lo - span)) / span
            slope_out = (mapping.evaluate(curve, hi + span) - values[-1]) / span
            out["curves"].append({"min": lo, "max": hi, "values": values, "slope_in": slope_in, "slope_out": slope_out,
                                  "points": [list(p.location) for p in curve.points]})
        return out

    # --- Materials and images --------------------------------------------------------------------

    def export_material(self, material):
        if material is None or material.name in self.materials:
            return material.name if material else None
        self.materials[material.name] = None
        data = {
            "name": material.name,
            "diffuse_color": list(material.diffuse_color),
            "metallic": material.metallic,
            "roughness": material.roughness,
            "backface_culling": material.use_backface_culling,
            "render_method": getattr(material, "surface_render_method", "DITHERED"),
            "blend_method": getattr(material, "blend_method", "OPAQUE"),
            "alpha_threshold": getattr(material, "alpha_threshold", 0.5),
            "shadow": getattr(material, "use_transparent_shadow", True),
        }
        if material.node_tree is not None:
            data["tree"] = self.export_tree(material.node_tree, "Material: " + material.name)
        self.materials[material.name] = data
        return material.name

    def export_image(self, image):
        if image.name in self.images:
            return image.name
        entry = {"name": image.name, "colorspace": image.colorspace_settings.name,
                 "alpha_mode": image.alpha_mode, "size": list(image.size), "source": image.source}
        self.images[image.name] = entry
        os.makedirs(os.path.join(self.out_dir, "textures"), exist_ok=True)
        safe = "".join(c if c.isalnum() or c in "-_." else "_" for c in os.path.splitext(image.name)[0])
        try:
            if image.packed_file is not None:
                ext = os.path.splitext(image.filepath or image.name)[1].lower() or ".png"
                if ext not in (".png", ".jpg", ".jpeg", ".tga", ".bmp"):
                    ext = ".png"
                    target = os.path.join("textures", safe + ext)
                    self._save_as_png(image, target)
                else:
                    target = os.path.join("textures", safe + ext)
                    with open(os.path.join(self.out_dir, target), "wb") as f:
                        f.write(image.packed_file.data)
            else:
                source = bpy.path.abspath(image.filepath, library=image.library)
                ext = os.path.splitext(source)[1].lower()
                if ext in (".png", ".jpg", ".jpeg", ".tga", ".bmp") and os.path.isfile(source):
                    target = os.path.join("textures", safe + ext)
                    shutil.copyfile(source, os.path.join(self.out_dir, target))
                else:
                    target = os.path.join("textures", safe + ".png")
                    self._save_as_png(image, target)
            entry["file"] = target.replace(os.sep, "/")
        except Exception as error:  # A missing file must not sink the export: the engine shows magenta.
            self.warnings.append("image %s: %s" % (image.name, error))
        return image.name

    def _save_as_png(self, image, target):
        copy = image.copy()
        try:
            copy.filepath_raw = os.path.join(self.out_dir, target)
            copy.file_format = "PNG"
            copy.save()
        finally:
            bpy.data.images.remove(copy)

    # --- Geometry --------------------------------------------------------------------------------

    def mesh(self, mesh, key):
        if key in self.meshes:
            return key
        nv, ne, nf, nc = len(mesh.vertices), len(mesh.edges), len(mesh.polygons), len(mesh.loops)
        pos = np.empty(nv * 3, "f4")
        mesh.vertices.foreach_get("co", pos)
        edges = np.empty(ne * 2, "i4")
        mesh.edges.foreach_get("vertices", edges)
        starts = np.empty(nf, "i4")
        mesh.polygons.foreach_get("loop_start", starts)
        corner_verts = np.empty(nc, "i4")
        mesh.loops.foreach_get("vertex_index", corner_verts)
        corner_edges = np.empty(nc, "i4")
        mesh.loops.foreach_get("edge_index", corner_edges)
        offsets = np.append(starts, np.int32(nc)).astype("i4")
        data = {"vertices": nv, "edges": ne, "faces": nf, "corners": nc,
                "position": _b64(pos, "<f4"), "edge_verts": _b64(edges, "<i4"),
                "face_offsets": _b64(offsets, "<i4"), "corner_verts": _b64(corner_verts, "<i4"),
                "corner_edges": _b64(corner_edges, "<i4"),
                "materials": [m.name if m else None for m in mesh.materials],
                "attributes": [],
                "texspace_location": list(mesh.texspace_location), "texspace_size": list(mesh.texspace_size),
                "custom_normals": bool(getattr(mesh, "has_custom_normals", False))}
        for material in mesh.materials:
            if material is not None:
                self.export_material(material)
        if mesh.uv_layers.active is not None:
            data["active_uv"] = mesh.uv_layers.active.name
        for layer in mesh.uv_layers:
            if layer.active_render:
                data["render_uv"] = layer.name
        colors = mesh.color_attributes
        if colors.active_color_name:
            data["active_color"] = colors.active_color_name
        if 0 <= getattr(colors, "render_color_index", -1) < len(colors):
            data["render_color"] = colors[colors.render_color_index].name
        for attribute in mesh.attributes:
            payload = self.attribute(attribute)
            if payload is not None:
                data["attributes"].append(payload)
        if nc:
            normals = np.empty(nc * 3, "f4")
            try:
                mesh.corner_normals.foreach_get("vector", normals)
                data["corner_normals"] = _b64(normals, "<f4")
            except Exception:
                pass
        self.meshes[key] = data
        return key

    def point_cloud(self, cloud):
        """A point cloud as a mesh of loose vertices, with its attributes."""
        count = len(cloud.points)
        pos = np.empty(count * 3, "f4")
        cloud.attributes["position"].data.foreach_get("vector", pos)
        data = {"vertices": count, "edges": 0, "faces": 0, "corners": 0, "position": _b64(pos, "<f4"),
                "edge_verts": "", "face_offsets": _b64(np.zeros(1, "i4"), "<i4"), "corner_verts": "",
                "corner_edges": "", "materials": [], "attributes": [], "point_cloud": True}
        for attribute in cloud.attributes:
            payload = self.attribute(attribute)
            if payload is not None:
                data["attributes"].append(payload)
        return data

    def attribute(self, attribute):
        name = attribute.name
        if name.startswith(".") or name == "position":
            return None
        layout = {
            "FLOAT": ("value", 1, "<f4"), "INT": ("value", 1, "<i4"), "BOOLEAN": ("value", 1, "?"),
            "INT8": ("value", 1, "<i4"), "FLOAT_VECTOR": ("vector", 3, "<f4"), "FLOAT2": ("vector", 2, "<f4"),
            "FLOAT_COLOR": ("color", 4, "<f4"), "BYTE_COLOR": ("color", 4, "<f4"),
            "QUATERNION": ("value", 4, "<f4"), "INT32_2D": ("value", 2, "<i4"), "FLOAT4X4": ("value", 16, "<f4"),
        }.get(attribute.data_type)
        if layout is None:
            return None
        key, width, dtype = layout
        count = len(attribute.data)
        buffer = np.empty(count * width, "f4" if dtype == "<f4" else ("bool" if dtype == "?" else "i4"))
        try:
            attribute.data.foreach_get(key, buffer)
        except Exception:
            return None
        if dtype == "?":
            buffer = buffer.astype("i4")
            dtype = "<i4"
        return {"name": name, "domain": attribute.domain, "type": attribute.data_type, "data": _b64(buffer, dtype)}

    def curves(self, curve):
        splines = []
        for spline in curve.splines:
            entry = {"type": spline.type, "cyclic": spline.use_cyclic_u, "resolution": spline.resolution_u,
                     "order": spline.order_u, "material_index": spline.material_index}
            if spline.type == "BEZIER":
                points = spline.bezier_points
                entry["points"] = [list(p.co) for p in points]
                entry["handles_left"] = [list(p.handle_left) for p in points]
                entry["handles_right"] = [list(p.handle_right) for p in points]
                entry["handle_types"] = [[p.handle_left_type, p.handle_right_type] for p in points]
            else:
                points = spline.points
                entry["points"] = [list(p.co)[:3] for p in points]
                entry["weights"] = [p.co[3] for p in points]
            entry["radius"] = [p.radius for p in points]
            entry["tilt"] = [p.tilt for p in points]
            splines.append(entry)
        for material in curve.materials:
            if material is not None:
                self.export_material(material)
        return {"splines": splines, "materials": [m.name if m else None for m in curve.materials]}

    # --- Objects and collections -----------------------------------------------------------------

    def queue_object(self, obj):
        if obj.name not in self.objects and obj not in self._object_queue:
            self._object_queue.append(obj)

    def flush(self):
        while self._object_queue:
            self.export_object(self._object_queue.pop(0))

    def export_collection(self, collection):
        if collection.name in self.collections:
            return collection.name
        self.collections[collection.name] = None
        for obj in collection.objects:
            self.queue_object(obj)
        for child in collection.children:
            self.export_collection(child)
        self.collections[collection.name] = {
            "objects": [o.name for o in collection.objects],
            "children": [c.name for c in collection.children],
            "offset": list(collection.instance_offset),
        }
        return collection.name

    def in_scene(self, obj):
        """Links an object the scene does not show (one kept in a library collection) into it for the export,
        so the depsgraph evaluates it; undone when the export ends."""
        scene = bpy.context.scene
        if scene.objects.get(obj.name) is obj:
            return
        if self._temporary is None:
            self._temporary = bpy.data.collections.new("Node Export (temporary)")
            scene.collection.children.link(self._temporary)
        self._temporary.objects.link(obj)

    def export_object(self, obj):
        if obj.name in self.objects:
            return
        self.objects[obj.name] = None
        self.in_scene(obj)
        entry = {"name": obj.name, "type": obj.type, "matrix": _matrix(obj.matrix_world),
                 "parent": obj.parent.name if obj.parent else None,
                 "visible": not obj.hide_render, "modifiers": [], "materials": []}
        for slot in obj.material_slots:
            entry["materials"].append(self.export_material(slot.material) if slot.material else None)
        if obj.parent is not None:
            entry["matrix_local"] = _matrix(obj.matrix_local)
            self.queue_object(obj.parent)
        first_nodes = None
        for index, modifier in enumerate(obj.modifiers):
            if modifier.type == "NODES" and modifier.node_group is not None:
                if first_nodes is None:
                    first_nodes = index
                entry["modifiers"].append({"name": modifier.name, "type": "NODES", "enabled": modifier.show_render,
                                           "tree": self.export_tree(modifier.node_group),
                                           "inputs": self.modifier_inputs(modifier)})
            elif first_nodes is not None and modifier.show_render:
                entry["modifiers"].append({"name": modifier.name, "type": modifier.type, "enabled": True})
                self.warnings.append("%s: %s modifier after geometry nodes is only in the bake" % (obj.name, modifier.type))
        if obj.type in ("MESH", "CURVE", "SURFACE", "FONT", "CURVES", "POINTCLOUD"):
            base = self.base_geometry(obj, first_nodes)
            if base is not None:
                entry.update(base)
        if obj.instance_type == "COLLECTION" and obj.instance_collection is not None:
            entry["instance_collection"] = self.export_collection(obj.instance_collection)
        if self.bake and obj.type in ("MESH", "CURVE", "SURFACE", "FONT", "CURVES", "POINTCLOUD"):
            entry["bake"] = self.bake_object(obj)
        self.objects[obj.name] = entry

    def base_geometry(self, obj, first_nodes):
        """The geometry a node modifier receives: the object's data with every modifier before the first
        node modifier applied, and none from there on."""
        if obj.type == "CURVE" and first_nodes == 0:
            return {"curves": self.curves(obj.data)}
        disabled = []
        for index, modifier in enumerate(obj.modifiers):
            if first_nodes is not None and index >= first_nodes and modifier.show_viewport:
                modifier.show_viewport = False
                disabled.append(modifier)
        try:
            depsgraph = bpy.context.evaluated_depsgraph_get()
            depsgraph.update()
            evaluated = obj.evaluated_get(depsgraph)
            if obj.type == "MESH" and not obj.modifiers or (first_nodes == 0 and obj.type == "MESH"):
                return {"mesh": self.mesh(obj.data, "Mesh: " + obj.data.name)}
            mesh = evaluated.to_mesh()
            if mesh is None:
                return None
            try:
                return {"mesh": self.mesh(mesh, "Base: " + obj.name)}
            finally:
                evaluated.to_mesh_clear()
        finally:
            for modifier in disabled:
                modifier.show_viewport = True

    def bake_object(self, obj):
        """The object as Blender evaluates it for render: its own mesh plus every instance its nodes made,
        with the transform of each relative to the object."""
        depsgraph = bpy.context.evaluated_depsgraph_get()
        depsgraph.update()
        inverse = obj.matrix_world.inverted()
        parts = []
        cache = {}
        for instance in depsgraph.object_instances:
            source = instance.object
            owner = instance.parent.original if instance.is_instance and instance.parent else source.original
            if owner != obj:
                continue
            if source.type not in ("MESH", "CURVE", "SURFACE", "FONT", "POINTCLOUD"):
                continue
            data_key = (source.data.original.name if source.data else source.name, instance.is_instance,
                        source.original.name)
            key = cache.get(data_key)
            if key is None and source.type == "POINTCLOUD":
                key = "Bake: %s/%d" % (obj.name, len(cache))
                self.meshes[key] = self.point_cloud(source.data)
                cache[data_key] = key
            if key is None:
                try:
                    mesh = source.to_mesh()
                except RuntimeError:
                    continue
                if mesh is None or len(mesh.vertices) == 0:
                    source.to_mesh_clear()
                    continue
                key = "Bake: %s/%d" % (obj.name, len(cache))
                self.mesh(mesh, key)
                source.to_mesh_clear()
                cache[data_key] = key
            parts.append({"mesh": key, "matrix": _matrix(inverse @ instance.matrix_world)})
        return parts

    def modifier_inputs(self, modifier):
        values = {}
        tree = modifier.node_group
        holder = getattr(getattr(modifier, "properties", None), "inputs", None)
        for item in tree.interface.items_tree:
            if item.item_type != "SOCKET" or item.in_out != "INPUT":
                continue
            ident = item.identifier
            entry = None
            if holder is not None and hasattr(holder, ident):
                slot = getattr(holder, ident)
                entry = {}
                if hasattr(slot, "value"):
                    entry["value"] = self.plain(slot.value)
                if getattr(slot, "type", "VALUE") == "ATTRIBUTE":
                    entry["attribute"] = slot.attribute_name
            else:
                try:
                    entry = {"value": self.plain(modifier[ident])}
                    if modifier.get(ident + "_use_attribute"):
                        entry["attribute"] = modifier.get(ident + "_attribute_name", "")
                except (KeyError, TypeError):
                    entry = None
            if entry:
                values[ident] = entry
        return values

    # --- The package -----------------------------------------------------------------------------

    def run(self, objects):
        try:
            return self._run(objects)
        finally:
            if self._temporary is not None:
                bpy.data.collections.remove(self._temporary)
                self._temporary = None

    def _run(self, objects):
        os.makedirs(self.out_dir, exist_ok=True)
        roots = []
        for obj in objects:
            self.queue_object(obj)
            roots.append(obj.name)
        self.flush()
        if self.all_materials:
            for material in bpy.data.materials:
                self.export_material(material)
        if self.all_groups:
            for tree in bpy.data.node_groups:
                if tree.bl_idname in ("GeometryNodeTree", "ShaderNodeTree"):
                    self.export_tree(tree)
        self.flush()
        package = {
            "format": FORMAT, "version": VERSION,
            "blender": bpy.app.version_string,
            "source": os.path.basename(bpy.data.filepath) or "untitled",
            "frame": bpy.context.scene.frame_current, "fps": bpy.context.scene.render.fps,
            "roots": roots, "objects": self.objects, "collections": self.collections,
            "materials": self.materials, "trees": self.trees, "images": self.images, "meshes": self.meshes,
            "warnings": self.warnings,
        }
        name = os.path.basename(os.path.normpath(self.out_dir)) + ".nodes.json"
        path = os.path.join(self.out_dir, name)
        with open(path, "w") as f:
            json.dump(package, f, separators=(",", ":"))
        return path


def _matrix(matrix):
    """Column-major, as the engine's matrices are."""
    return [matrix[row][col] for col in range(4) for row in range(4)]


def _socket_kind(socket_idname):
    for prefix, kind in (("NodeSocketFloat", "VALUE"), ("NodeSocketInt", "INT"), ("NodeSocketBool", "BOOLEAN"),
                         ("NodeSocketVector", "VECTOR"), ("NodeSocketColor", "RGBA"), ("NodeSocketString", "STRING"),
                         ("NodeSocketRotation", "ROTATION"), ("NodeSocketMatrix", "MATRIX"),
                         ("NodeSocketMenu", "MENU"), ("NodeSocketGeometry", "GEOMETRY"),
                         ("NodeSocketShader", "SHADER"), ("NodeSocketObject", "OBJECT"),
                         ("NodeSocketCollection", "COLLECTION"), ("NodeSocketMaterial", "MATERIAL"),
                         ("NodeSocketImage", "IMAGE"), ("NodeSocketTexture", "TEXTURE"),
                         ("NodeSocketBundle", "BUNDLE"), ("NodeSocketClosure", "CLOSURE")):
        if socket_idname.startswith(prefix):
            return kind
    return "CUSTOM"


def export(out_dir, objects=None, bake=True, all_materials=False, all_groups=True):
    """Writes the package for these objects (default: every object in the scene); returns its path."""
    if objects is None:
        objects = [o for o in bpy.context.scene.objects if o.parent is None]
    exporter = Exporter(out_dir, bake=bake, all_materials=all_materials, all_groups=all_groups)
    path = exporter.run(objects)
    for warning in exporter.warnings:
        print("node export: warning:", warning)
    print("node export: wrote %s (%d objects, %d trees, %d materials, %d images)"
          % (path, len(exporter.objects), len(exporter.trees), len(exporter.materials), len(exporter.images)))
    return path


# --- Add-on ----------------------------------------------------------------------------------------

try:
    from bpy_extras.io_utils import ExportHelper
except ImportError:
    ExportHelper = object


class EXPORT_OT_engine_nodes(bpy.types.Operator, ExportHelper):
    """Export node trees, materials and objects for the zeronura engine"""
    bl_idname = "export_scene.engine_nodes"
    bl_label = "Export Engine Node Package"
    filename_ext = ".nodes.json"

    filter_glob: bpy.props.StringProperty(default="*.nodes.json", options={"HIDDEN"})
    bake: bpy.props.BoolProperty(name="Include Bake", default=True,
                                 description="Also write each object as Blender evaluates it, which the engine "
                                             "uses for any tree with a node it does not implement")
    all_materials: bpy.props.BoolProperty(name="All Materials", default=False,
                                          description="Export every material in the file, used or not")

    def execute(self, context):
        folder = os.path.splitext(os.path.splitext(self.filepath)[0])[0]
        selected = [o for o in context.selected_objects]
        export(folder, selected or None, bake=self.bake, all_materials=self.all_materials)
        return {"FINISHED"}


def _menu(self, context):
    self.layout.operator(EXPORT_OT_engine_nodes.bl_idname, text="Engine Node Package (.nodes.json)")


def register():
    bpy.utils.register_class(EXPORT_OT_engine_nodes)
    bpy.types.TOPBAR_MT_file_export.append(_menu)


def unregister():
    bpy.types.TOPBAR_MT_file_export.remove(_menu)
    bpy.utils.unregister_class(EXPORT_OT_engine_nodes)


def _main(argv):
    import argparse
    parser = argparse.ArgumentParser(prog="node_export.py")
    parser.add_argument("--out", required=True, help="folder to write the package into")
    parser.add_argument("--collection", help="export only this collection's objects")
    parser.add_argument("--objects", help="comma-separated object names to export")
    parser.add_argument("--no-bake", action="store_true", help="leave out Blender's own evaluation")
    parser.add_argument("--all-materials", action="store_true", help="export every material in the file")
    args = parser.parse_args(argv)
    objects = None
    if args.collection:
        collection = bpy.data.collections.get(args.collection)
        if collection is None:
            sys.exit("No collection named %r" % args.collection)
        objects = [o for o in collection.all_objects if o.parent is None or o.parent.name not in collection.all_objects]
    if args.objects:
        objects = []
        for name in args.objects.split(","):
            obj = bpy.data.objects.get(name.strip())
            if obj is None:
                sys.exit("No object named %r" % name)
            objects.append(obj)
    export(args.out, objects, bake=not args.no_bake, all_materials=args.all_materials)


if __name__ == "__main__":
    if "--" in sys.argv:
        _main(sys.argv[sys.argv.index("--") + 1:])
    else:
        register()
