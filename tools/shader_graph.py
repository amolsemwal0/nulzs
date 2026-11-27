"""Builds Shader Tool materials and Geometry Nodes Tool trees (.nodes.json) from code, out of the tools' own node
templates.

The templates are src/main/resources/nodes/shader_nodes.json and geometry_nodes.json, the catalogs the editor's Add
menus are made from, so a file written here opens in Tools > Shader Tool (or Geometry Nodes Tool) exactly like one made
there. Used by crelmons_materials.py and crelmons_geometry.py.

    g = Graph()
    noise = g.add("ShaderNodeTexNoise", props={"noise_dimensions": "4D"}, Scale=3.0)
    g.add("ShaderNodeOutputMaterial", Surface=g.add("ShaderNodeEmission", Color=noise["Color"])["Emission"])
    g.write(path, "My Material", bake_prefix="tex/my/my")

Inputs are given by socket id as keyword arguments, a space written as a double underscore (From__Min for "From Min");
a value sets the socket, an output (node["Socket"]) links it.
"""
import copy
import json
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _engine_root():
    """The engine's folder, whose src/main/resources/nodes holds the catalogs: the one this file is in when it is the
    engine's, else $NULZS_ENGINE_HOME, else nulzs-game-engine beside this folder (a game's tools/ using the engine)."""
    for folder in (ROOT, os.environ.get("NULZS_ENGINE_HOME", ""),
                   os.path.join(os.path.dirname(ROOT), "nulzs-game-engine")):
        if folder and os.path.isfile(os.path.join(folder, "src/main/resources/nodes/shader_nodes.json")):
            return folder
    raise SystemExit("shader_graph.py: can't find the engine's node catalogs; set NULZS_ENGINE_HOME to the "
                     "nulzs-game-engine folder")


ENGINE_ROOT = _engine_root()
CATALOG = os.path.join(ENGINE_ROOT, "src/main/resources/nodes/shader_nodes.json")
ENTRIES = {n["id"]: n for c in json.load(open(CATALOG))["categories"] for n in c["nodes"]}
GEOMETRY_CATALOG = os.path.join(ENGINE_ROOT, "src/main/resources/nodes/geometry_nodes.json")
GEOMETRY_ENTRIES = {n["id"]: n for c in json.load(open(GEOMETRY_CATALOG))["categories"] for n in c["nodes"]}
TAU = 2 * math.pi


def srgb(hex_colour, alpha=1.0):
    """A colour as an artist picks it (sRGB hex) in the scene-linear values Blender's colour sockets hold."""
    h = hex_colour.lstrip("#")
    out = []
    for i in (0, 2, 4):
        c = int(h[i:i + 2], 16) / 255.0
        out.append(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
    return out + [alpha]


def ramp(stops, interpolation="LINEAR"):
    """A Color Ramp's stops: [(position, colour), ...]."""
    return {"interpolation": interpolation, "color_mode": "RGB", "hue_interpolation": "NEAR",
            "stops": [{"position": p, "color": c} for p, c in stops]}


def bands(colours, edges, softness=0.015):
    """Crisp cel bands: colours[i] up to edges[i], each change a narrow soft step so it doesn't alias."""
    stops = [(0.0, colours[0])]
    for edge, below, above in zip(edges, colours, colours[1:]):
        stops.append((max(0.0, edge - softness), below))
        stops.append((min(1.0, edge + softness), above))
    stops.append((1.0, colours[-1]))
    return ramp(stops)


class Out:
    def __init__(self, node, socket):
        self.node = node
        self.socket = socket


class Node:
    def __init__(self, json_node):
        self.json = json_node

    def __getitem__(self, socket):
        ids = [s["id"] for s in self.json["outputs"]]
        if socket not in ids:
            raise KeyError(f"{self.json['name']} has no output {socket}; it has {ids}")
        return Out(self, socket)

    @property
    def name(self):
        return self.json["name"]


class Graph:
    def __init__(self, entries=None):
        self.nodes = []
        self.links = []
        self.entries = entries or ENTRIES

    # --- Nodes ---------------------------------------------------------------------------------------------------

    def add(self, kind, label=None, props=None, ramp=None, **inputs):
        entry = self.entries[kind]
        node = copy.deepcopy(entry["node"])
        node["name"] = self._unique(entry["label"])
        if label:
            node["label"] = label
        node["props"].update(props or {})
        self._apply_settings(entry, node)
        if ramp is not None:
            node["ramp"] = ramp
        made = Node(node)
        for key, value in inputs.items():
            self.set(made, key.replace("__", " "), value)
        self.nodes.append(made)
        return made

    def set(self, node, socket, value):
        sockets = {s["id"]: s for s in node.json["inputs"]}
        if socket not in sockets:
            raise KeyError(f"{node.name} has no input {socket}; it has {list(sockets)}")
        target = sockets[socket]
        if not target.get("enabled", True):
            raise ValueError(f"{node.name}'s {socket} is hidden by its settings")
        if isinstance(value, Out):
            self.links.append((value.node, value.socket, node, socket))
        else:
            target["value"] = value

    # Shorthands for the nodes every material here uses.

    def math(self, op, a, b=None, label=None, clamp=False):
        inputs = {"Value": a} if b is None else {"Value": a, "Value_001": b}
        return self.add("ShaderNodeMath", label=label, props={"operation": op, "use_clamp": clamp}, **inputs)["Value"]

    def vmath(self, op, a, b=None, scale=None, label=None):
        inputs = {"Vector": a}
        if b is not None:
            inputs["Vector_001"] = b
        if scale is not None:
            inputs["Scale"] = scale
        node = self.add("ShaderNodeVectorMath", label=label, props={"operation": op}, **inputs)
        return node["Value"] if op in ("DOT_PRODUCT", "LENGTH", "DISTANCE") else node["Vector"]

    def map_range(self, value, from_min, from_max, to_min, to_max, label=None, smooth=False, clamp=True):
        props = {"clamp": clamp, "interpolation_type": "SMOOTHSTEP" if smooth else "LINEAR"}
        return self.add("ShaderNodeMapRange", label=label, props=props, Value=value, From__Min=from_min,
                        From__Max=from_max, To__Min=to_min, To__Max=to_max)["Result"]

    def mix(self, factor, a, b, label=None, blend="MIX"):
        return self.add("ShaderNodeMix:RGBA", label=label, props={"blend_type": blend}, Factor_Float=factor,
                        A_Color=a, B_Color=b)["Result_Color"]

    def colours(self, value, ramp_stops, label=None):
        return self.add("ShaderNodeValToRGB", label=label, Fac=value, ramp=ramp_stops)["Color"]

    # --- Seamless coordinates ------------------------------------------------------------------------------------

    def torus(self):
        """
        UV's square wrapped round a flat (Clifford) torus: u and v each once round a circle of radius 1, the four numbers
        a 4D texture's Vector (x, y, z) and W. Anything read there repeats exactly at the square's edges, and is the same
        size every way. Returns the vector, W, and the circles' sines and cosines (for the directions along u and v).
        """
        coords = self.add("ShaderNodeTexCoord")
        uv = self.add("ShaderNodeSeparateXYZ", Vector=coords["UV"])
        a = self.math("MULTIPLY", uv["X"], TAU, "u round")
        b = self.math("MULTIPLY", uv["Y"], TAU, "v round")
        cos_a, sin_a = self.math("COSINE", a), self.math("SINE", a)
        cos_b, sin_b = self.math("COSINE", b), self.math("SINE", b)
        vector = self.add("ShaderNodeCombineXYZ", label="Torus", X=cos_a, Y=sin_a, Z=cos_b)["Vector"]
        return {"vector": vector, "w": sin_b, "a": a, "b": b, "cos_a": cos_a, "sin_a": sin_a, "cos_b": cos_b,
                "sin_b": sin_b}

    def noise(self, torus, scale, detail=2.0, roughness=0.5, distortion=0.0, label=None, vector=None, w=None,
              kind="FBM"):
        return self.add("ShaderNodeTexNoise", label=label,
                        props={"noise_dimensions": "4D", "normalize": True, "noise_type": kind},
                        Vector=vector or torus["vector"], W=w or torus["w"], Scale=scale, Detail=detail,
                        Roughness=roughness, Distortion=distortion)

    def voronoi(self, torus, scale, feature="F1", randomness=1.0, label=None, vector=None, w=None, detail=0.0):
        inputs = {"Vector": vector or torus["vector"], "W": w or torus["w"], "Scale": scale, "Randomness": randomness}
        if feature not in ("N_SPHERE_RADIUS",):
            inputs["Detail"] = detail
        return self.add("ShaderNodeTexVoronoi", label=label, props={"voronoi_dimensions": "4D", "feature": feature},
                        **inputs)

    def bend(self, torus, amount, scale=1.4, detail=2.0, label="Bent torus"):
        """The torus pushed about by noise, so what is read there wanders instead of lying in round cells."""
        wobble = self.noise(torus, scale, detail, label=label + ": wobble")
        centred = self.vmath("SUBTRACT", wobble["Color"], [0.5, 0.5, 0.5])
        return self.vmath("ADD", torus["vector"], self.vmath("SCALE", centred, scale=amount), label=label)

    def cell_offset(self, torus, cells, vector=None, w=None):
        """
        Where a point lies from its Voronoi cell's feature point, in cell sizes, along u and v: the 4D offset projected on
        the torus's directions along u (-sin a, cos a, 0, 0) and along v (0, 0, -sin b, cos b).
        """
        vector = vector or torus["vector"]
        w = w or torus["w"]
        off = self.vmath("SUBTRACT", vector, cells["Position"])
        off_w = self.math("SUBTRACT", w, cells["W"])
        split = self.add("ShaderNodeSeparateXYZ", Vector=off)
        along_u = self.math("ADD", self.math("MULTIPLY", split["X"], self.math("MULTIPLY", torus["sin_a"], -1.0)),
                            self.math("MULTIPLY", split["Y"], torus["cos_a"]), "Along u")
        along_v = self.math("ADD", self.math("MULTIPLY", split["Z"], self.math("MULTIPLY", torus["sin_b"], -1.0)),
                            self.math("MULTIPLY", off_w, torus["cos_b"]), "Along v")
        return along_u, along_v

    # --- Files ---------------------------------------------------------------------------------------------------

    def write(self, path, name, bake_prefix=None, bake_size=1024):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w") as f:
            json.dump(self.package(os.path.basename(path), name, bake_prefix, bake_size), f, indent=2)
            f.write("\n")
        print("wrote", path, f"({len(self.nodes)} nodes, {len(self.links)} links)")

    def package(self, file_name, name, bake_prefix=None, bake_size=1024):
        linked_in = {(to.name, socket) for _, _, to, socket in self.links}
        linked_out = {(frm.name, socket) for frm, socket, _, _ in self.links}
        nodes = []
        for n in self.nodes:
            j = copy.deepcopy(n.json)
            for s in j["inputs"]:
                if (n.name, s["id"]) in linked_in:
                    s["linked"] = True
            for s in j["outputs"]:
                if (n.name, s["id"]) in linked_out:
                    s["linked"] = True
            nodes.append(j)
        self._layout(nodes)
        tree_key = "Material: " + name
        material = {"name": name, "diffuse_color": [0.8, 0.8, 0.8, 1.0], "metallic": 0.0, "roughness": 0.5,
                    "backface_culling": False, "render_method": "DITHERED", "blend_method": "OPAQUE",
                    "alpha_threshold": 0.5, "shadow": True, "tree": tree_key}
        if bake_prefix:
            # Where the Shader Tool's Bake... (and tools/bake_material.sh without a prefix) writes its pictures.
            material["bake"] = {"prefix": bake_prefix, "size": bake_size}
        return {
            "format": "zeronura-node-package", "version": 1, "blender": "Nulzs Game Engine Shader Tool", "source": file_name,
            "fps": 24.0, "roots": [], "objects": {}, "collections": {},
            "materials": {name: material},
            "trees": {tree_key: {
                "name": "Shader Nodetree", "type": "SHADER", "interface": [], "nodes": nodes,
                "links": [{"from_node": f.name, "from_socket": fs, "to_node": t.name, "to_socket": ts}
                          for f, fs, t, ts in self.links]}},
            "images": {}, "meshes": {},
        }

    def _links_json(self):
        return [{"from_node": f.name, "from_socket": fs, "to_node": t.name, "to_socket": ts} for f, fs, t, ts in self.links]

    def _nodes_json(self):
        linked_in = {(to.name, socket) for _, _, to, socket in self.links}
        linked_out = {(frm.name, socket) for frm, socket, _, _ in self.links}
        nodes = []
        for n in self.nodes:
            j = copy.deepcopy(n.json)
            for s in j["inputs"]:
                if (n.name, s["id"]) in linked_in:
                    s["linked"] = True
            for s in j["outputs"]:
                if (n.name, s["id"]) in linked_out:
                    s["linked"] = True
            nodes.append(j)
        self._layout(nodes)
        return nodes

    def _unique(self, base):
        taken = {n.name for n in self.nodes}
        if base not in taken:
            return base
        i = 1
        while f"{base}.{i:03d}" in taken:
            i += 1
        return f"{base}.{i:03d}"

    @staticmethod
    def _apply_settings(entry, node):
        """As ShaderGraph.applySettings: a setting changed from its first value shows and hides its sockets."""
        defaults = entry["node"].get("props", {})
        for name, setting in entry.get("settings", {}).items():
            now = node["props"].get(name)
            first = defaults.get(name)
            if now is None or now == first:
                continue
            for side, sockets in (("inputs", node["inputs"]), ("outputs", node["outputs"])):
                with_now = setting.get(side, {}).get(str(now))
                with_first = setting.get(side, {}).get(str(first))
                if with_now is None or with_first is None:
                    continue
                for s in sockets:
                    if (s["id"] in with_now) != (s["id"] in with_first):
                        s["enabled"] = s["id"] in with_now

    def _layout(self, nodes):
        """Columns by distance from the output, as the Shader Tool's own Arrange lays them."""
        depth = {n["name"]: 0 for n in nodes}
        for _ in nodes:
            moved = False
            for f, _, t, _ in self.links:
                if depth[f.name] < depth[t.name] + 1:
                    depth[f.name] = depth[t.name] + 1
                    moved = True
            if not moved:
                break
        column_y = {}
        for n in nodes:
            d = depth[n["name"]]
            y = column_y.get(d, 0.0)
            n["location"] = [float(-d * 260), float(y)]
            shown = sum(1 for s in n["inputs"] + n["outputs"] if s.get("enabled", True))
            column_y[d] = y + 70 + 24 * shown + (70 if "ramp" in n else 0)


# The interface socket classes Blender names a geometry tree's inputs by (NodeGraph.socketIdname).
SOCKET_IDNAMES = {"GEOMETRY": "NodeSocketGeometry", "INT": "NodeSocketInt", "BOOLEAN": "NodeSocketBool",
                  "VECTOR": "NodeSocketVector", "RGBA": "NodeSocketColor", "ROTATION": "NodeSocketRotation",
                  "STRING": "NodeSocketString", "MATERIAL": "NodeSocketMaterial", "VALUE": "NodeSocketFloat"}


class GeometryGraph(Graph):
    """
    A Geometry Nodes Tool tree: nodes from geometry_nodes.json, an interface of inputs (which the Group Input hands
    out, and which a scene or the game sets) and one Geometry output, as NodeGraph.toPackage writes it.

        g = GeometryGraph("Rocks")
        count = g.input("Count", "INT", 12, 1, 200)
        g.output(g.add("GeometryNodeMeshCube")["Mesh"])
        g.write(path)
    """

    def __init__(self, name):
        super().__init__(GEOMETRY_ENTRIES)
        self.name = name
        self.interface = []
        self.group_input = self._group("NodeGroupInput", "Group Input")
        self.group_output = self._group("NodeGroupOutput", "Group Output")
        self.interface.append({"in_out": "OUTPUT", "name": "Geometry", "id": "Socket_0", "type": "GEOMETRY"})
        self.group_output.json["inputs"].append({"id": "Socket_0", "name": "Geometry", "type": "GEOMETRY",
                                                 "enabled": True})

    def _group(self, kind, name):
        node = Node({"name": name, "type": kind, "props": {}, "inputs": [], "outputs": []})
        self.nodes.append(node)
        return node

    def input(self, name, kind, default=None, minimum=None, maximum=None):
        """An interface input, after the others; returns the Group Input's output for it."""
        socket_id = "Socket_%d" % (len(self.interface))
        item = {"in_out": "INPUT", "name": name, "id": socket_id, "type": kind}
        if default is not None:
            item["default"] = {"ref": default, "kind": "MATERIAL"} if kind == "MATERIAL" else default
        if minimum is not None:
            item["min"] = float(minimum)
        if maximum is not None:
            item["max"] = float(maximum)
        inputs = [i for i in self.interface if i["in_out"] == "INPUT"]
        self.interface.insert(len(inputs), item)
        socket = {"id": socket_id, "name": name, "type": kind, "enabled": True}
        if default is not None:
            socket["value"] = default
        self.group_input.json["outputs"].append(socket)
        return Out(self.group_input, socket_id)

    def output(self, geometry):
        self.links.append((geometry.node, geometry.socket, self.group_output, "Socket_0"))

    def write(self, path, name=None, bake_prefix=None, bake_size=1024):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w") as f:
            json.dump(self.package(os.path.basename(path)), f, indent=2)
            f.write("\n")
        print("wrote", path, f"({len(self.nodes)} nodes, {len(self.links)} links)")

    def package(self, file_name, name=None, bake_prefix=None, bake_size=1024):
        interface = []
        for item in self.interface:
            json_item = {"in_out": item["in_out"], "name": item["name"], "id": item["id"],
                         "socket": SOCKET_IDNAMES.get(item["type"], "NodeSocketFloat"), "type": item["type"]}
            for key in ("default", "min", "max"):
                if key in item:
                    json_item[key] = item[key]
            json_item.update({"subtype": "NONE", "default_attribute_name": "", "attribute_domain": "POINT",
                              "hide_value": False, "default_input": "VALUE"})
            interface.append(json_item)
        tree = {"name": self.name, "type": "GEOMETRY", "interface": interface, "nodes": self._nodes_json(),
                "links": self._links_json()}
        return {
            "format": "zeronura-node-package", "version": 1, "blender": "Nulzs Game Engine Geometry Nodes Tool",
            "source": file_name, "fps": 24.0, "roots": [], "objects": {}, "collections": {}, "materials": {},
            "geometry": {"tree": self.name}, "trees": {self.name: tree}, "images": {}, "meshes": {},
        }

    # Shorthands for the function nodes a geometry tree is built of.

    def random(self, minimum, maximum, seed, kind="FLOAT", label=None):
        node = self.add("FunctionNodeRandomValue", label=label, props={"data_type": kind}, Min=minimum, Max=maximum,
                        Seed=seed)
        return node["Value"]

    def xyz(self, x=0.0, y=0.0, z=0.0, label=None):
        return self.add("ShaderNodeCombineXYZ", label=label, X=x, Y=y, Z=z)["Vector"]

    def split(self, vector, label=None):
        node = self.add("ShaderNodeSeparateXYZ", label=label, Vector=vector)
        return node["X"], node["Y"], node["Z"]
