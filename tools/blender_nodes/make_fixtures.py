"""Builds the reference data the node system's tests compare against: Blender evaluating its own nodes.

Each case is a small geometry-nodes setup run on a grid of points; what Blender computes per point is recorded
next to the inputs that produced it. The Java tests compute the same thing and must agree, so a test failure
means the engine has drifted from Blender, not from someone's idea of it.

    blender --background --factory-startup --python tools/blender_nodes/make_fixtures.py -- \\
        src/test/resources/nodes

Writes textures.json (procedural textures and hashes, compared function by function) and, for each tree in
TREES, <name>.nodes.json plus its bake (compared by running the whole tree in the engine's evaluator).
"""

import json
import os
import sys

import bpy
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import node_export  # noqa: E402

OUT = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else "src/test/resources/nodes"
os.makedirs(OUT, exist_ok=True)

bpy.ops.wm.read_factory_settings(use_empty=True)


def new_tree(name):
    tree = bpy.data.node_groups.new(name, "GeometryNodeTree")
    tree.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
    tree.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
    tree.nodes.new("NodeGroupInput")
    tree.nodes.new("NodeGroupOutput")
    return tree


def grid_object(name, tree):
    mesh = bpy.data.meshes.new(name)
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    modifier = obj.modifiers.new("GN", "NODES")
    modifier.node_group = tree
    return obj


# --- Texture cases: one Store Named Attribute per output, all on one grid ----------------------------------

tree = new_tree("Textures")
nodes, links = tree.nodes, tree.links
grid = nodes.new("GeometryNodeMeshGrid")
grid.inputs["Size X"].default_value = 7.3
grid.inputs["Size Y"].default_value = 5.9
grid.inputs["Vertices X"].default_value = 23
grid.inputs["Vertices Y"].default_value = 19
position = nodes.new("GeometryNodeInputPosition")
# A third axis and a fourth coordinate that vary, so 3D and 4D textures are not sampled on a plane.
separate = nodes.new("ShaderNodeSeparateXYZ")
links.new(position.outputs[0], separate.inputs[0])
zmath = nodes.new("ShaderNodeMath")
zmath.operation = "MULTIPLY_ADD"
links.new(separate.outputs["X"], zmath.inputs[0])
zmath.inputs[1].default_value = 0.37
links.new(separate.outputs["Y"], zmath.inputs[2])
combine = nodes.new("ShaderNodeCombineXYZ")
links.new(separate.outputs["X"], combine.inputs["X"])
links.new(separate.outputs["Y"], combine.inputs["Y"])
links.new(zmath.outputs[0], combine.inputs["Z"])
wmath = nodes.new("ShaderNodeMath")
wmath.operation = "MULTIPLY_ADD"
links.new(separate.outputs["Y"], wmath.inputs[0])
wmath.inputs[1].default_value = -1.3
links.new(separate.outputs["X"], wmath.inputs[2])
index = nodes.new("GeometryNodeInputIndex")

geometry_socket = grid.outputs["Mesh"]
cases = []


def store(name, socket, data_type):
    global geometry_socket
    node = nodes.new("GeometryNodeStoreNamedAttribute")
    node.data_type = data_type
    node.domain = "POINT"
    node.inputs["Name"].default_value = name
    links.new(geometry_socket, node.inputs["Geometry"])
    value_input = [s for s in node.inputs if s.name == "Value" and s.enabled][0]
    links.new(socket, value_input)
    geometry_socket = node.outputs["Geometry"]


def texture_case(node_type, props, inputs, outputs, uses_w=False, vector=True):
    node = nodes.new(node_type)
    for key, value in props.items():
        setattr(node, key, value)
    applied = {}
    for key, value in inputs.items():
        sockets = [s for s in node.inputs if s.name == key and s.enabled]
        if sockets:
            sockets[0].default_value = value
            applied[key] = list(value) if isinstance(value, tuple) else value
    inputs = applied
    if vector:
        links.new(combine.outputs[0], [s for s in node.inputs if s.name == "Vector"][0])
    if uses_w:
        w = [s for s in node.inputs if s.name == "W" and s.enabled]
        if w:
            links.new(wmath.outputs[0], w[0])
    case = {"type": node_type, "props": props, "inputs": inputs, "outputs": {}}
    for out_name in outputs:
        sockets = [s for s in node.outputs if s.name == out_name and s.enabled]
        if not sockets:
            continue
        socket = sockets[0]
        attr = "c%d_%s" % (len(cases), out_name.replace(" ", "_"))
        kind = {"VALUE": "FLOAT", "RGBA": "FLOAT_COLOR", "VECTOR": "FLOAT_VECTOR", "INT": "INT",
                "BOOLEAN": "BOOLEAN"}[socket.type]
        store(attr, socket, kind)
        case["outputs"][out_name] = attr
    cases.append(case)


DIMS = ["1D", "2D", "3D", "4D"]
for dims in DIMS:
    for noise_type, normalize in (("FBM", True), ("FBM", False), ("MULTIFRACTAL", False),
                                  ("HYBRID_MULTIFRACTAL", False), ("RIDGED_MULTIFRACTAL", False),
                                  ("HETERO_TERRAIN", False)):
        for distortion in (0.0, 0.7):
            texture_case("ShaderNodeTexNoise",
                         {"noise_dimensions": dims, "noise_type": noise_type, "normalize": normalize},
                         {"Scale": 1.7, "Detail": 2.5, "Roughness": 0.6, "Lacunarity": 2.2, "Offset": 0.3,
                          "Gain": 1.1, "Distortion": distortion},
                         ["Fac", "Color"], uses_w=True)

for dims in DIMS:
    metrics = ["EUCLIDEAN"] if dims == "1D" else ["EUCLIDEAN", "MANHATTAN", "CHEBYCHEV", "MINKOWSKI"]
    for feature in ("F1", "F2", "SMOOTH_F1", "DISTANCE_TO_EDGE", "N_SPHERE_RADIUS"):
        for metric in metrics:
            if feature in ("DISTANCE_TO_EDGE", "N_SPHERE_RADIUS") and metric != "EUCLIDEAN":
                continue
            for detail, normalize in ((0.0, False), (2.5, True)):
                if feature == "N_SPHERE_RADIUS" and detail > 0:
                    continue
                texture_case("ShaderNodeTexVoronoi",
                             {"voronoi_dimensions": dims, "feature": feature, "distance": metric,
                              "normalize": normalize},
                             {"Scale": 2.3, "Detail": detail, "Roughness": 0.55, "Lacunarity": 2.1,
                              "Smoothness": 0.8, "Exponent": 1.7, "Randomness": 0.85},
                             ["Distance", "Color", "Position", "W", "Radius"], uses_w=True)

for dims in DIMS:
    texture_case("ShaderNodeTexWhiteNoise", {"noise_dimensions": dims}, {}, ["Value", "Color"], uses_w=True)

for gradient in ("LINEAR", "QUADRATIC", "EASING", "DIAGONAL", "SPHERICAL", "QUADRATIC_SPHERE", "RADIAL"):
    texture_case("ShaderNodeTexGradient", {"gradient_type": gradient}, {}, ["Fac"])

texture_case("ShaderNodeTexChecker", {}, {"Scale": 3.1, "Color1": (0.9, 0.2, 0.1, 1.0), "Color2": (0.1, 0.3, 0.8, 1.0)},
             ["Fac", "Color"])

for wave_type, direction in (("BANDS", "X"), ("BANDS", "DIAGONAL"), ("RINGS", "SPHERICAL"), ("RINGS", "Z")):
    for profile in ("SIN", "SAW", "TRI"):
        props = {"wave_type": wave_type, "wave_profile": profile}
        props["bands_direction" if wave_type == "BANDS" else "rings_direction"] = direction
        texture_case("ShaderNodeTexWave", props,
                     {"Scale": 1.3, "Distortion": 2.1, "Detail": 2.5, "Detail Scale": 1.4,
                      "Detail Roughness": 0.6, "Phase Offset": 0.4}, ["Fac", "Color"])

for depth in (0, 2, 5):
    texture_case("ShaderNodeTexMagic", {"turbulence_depth": depth}, {"Scale": 1.9, "Distortion": 1.3},
                 ["Fac", "Color"])

texture_case("ShaderNodeTexBrick", {"offset": 0.4, "offset_frequency": 2, "squash": 1.3, "squash_frequency": 3},
             {"Scale": 2.7, "Mortar Size": 0.04, "Mortar Smooth": 0.3, "Bias": 0.1, "Brick Width": 0.6,
              "Row Height": 0.3, "Color1": (0.8, 0.3, 0.2, 1.0), "Color2": (0.5, 0.4, 0.3, 1.0),
              "Mortar": (0.1, 0.1, 0.1, 1.0)}, ["Fac", "Color"])

# Random Value and Hash Value, keyed by index (the ID) and a seed.
for data_type in ("FLOAT", "INT", "FLOAT_VECTOR", "BOOLEAN"):
    node = nodes.new("FunctionNodeRandomValue")
    node.data_type = data_type
    inputs = {}
    for s in node.inputs:
        if not s.enabled:
            continue
        if s.name == "Seed":
            s.default_value = 17
            inputs["Seed"] = 17
        elif s.name == "Min":
            s.default_value = (-2.0, 1.0, 0.5) if data_type == "FLOAT_VECTOR" else (-3 if data_type == "INT" else -2.5)
            inputs["Min"] = list(s.default_value) if data_type == "FLOAT_VECTOR" else s.default_value
        elif s.name == "Max":
            s.default_value = (3.0, 4.0, 0.75) if data_type == "FLOAT_VECTOR" else (11 if data_type == "INT" else 4.0)
            inputs["Max"] = list(s.default_value) if data_type == "FLOAT_VECTOR" else s.default_value
        elif s.name == "Probability":
            s.default_value = 0.35
            inputs["Probability"] = 0.35
    case = {"type": "FunctionNodeRandomValue", "props": {"data_type": data_type}, "inputs": inputs, "outputs": {}}
    out = [s for s in node.outputs if s.enabled][0]
    attr = "c%d_Value" % len(cases)
    store(attr, out, {"FLOAT": "FLOAT", "INT": "INT", "FLOAT_VECTOR": "FLOAT_VECTOR", "BOOLEAN": "BOOLEAN"}[data_type])
    case["outputs"]["Value"] = attr
    cases.append(case)

for data_type, source in (("INT", index.outputs[0]), ("FLOAT", wmath.outputs[0]), ("VECTOR", combine.outputs[0])):
    node = nodes.new("FunctionNodeHashValue")
    node.data_type = data_type
    links.new(source, [s for s in node.inputs if s.name == "Value" and s.enabled][0])
    seed = [s for s in node.inputs if s.name == "Seed"][0]
    seed.default_value = 5
    attr = "c%d_Hash" % len(cases)
    store(attr, node.outputs["Hash"], "INT")
    cases.append({"type": "FunctionNodeHashValue", "props": {"data_type": data_type}, "inputs": {"Seed": 5},
                  "outputs": {"Hash": attr}})

links.new(geometry_socket, nodes["Group Output"].inputs[0])
obj = grid_object("Textures", tree)
depsgraph = bpy.context.evaluated_depsgraph_get()
evaluated = obj.evaluated_get(depsgraph)
mesh = evaluated.data
count = len(mesh.vertices)
positions = np.empty(count * 3, "f4")
mesh.attributes["position"].data.foreach_get("vector", positions)


def read(name):
    attribute = mesh.attributes[name]
    key = {"FLOAT": "value", "INT": "value", "BOOLEAN": "value", "FLOAT_VECTOR": "vector",
           "FLOAT_COLOR": "color"}[attribute.data_type]
    width = {"value": 1, "vector": 3, "color": 4}[key]
    dtype = "f4" if attribute.data_type in ("FLOAT", "FLOAT_VECTOR", "FLOAT_COLOR") else (
        "bool" if attribute.data_type == "BOOLEAN" else "i4")
    data = np.empty(count * width, dtype)
    attribute.data.foreach_get(key, data)
    if dtype == "bool":
        data = data.astype("i4")
    return data.tolist()


for case in cases:
    case["outputs"] = {k: read(v) for k, v in case["outputs"].items()}

with open(os.path.join(OUT, "textures.json"), "w") as f:
    json.dump({"blender": bpy.app.version_string, "positions": positions.tolist(), "cases": cases}, f)
print("fixtures: %d texture cases on %d points" % (len(cases), count))


# --- Whole trees: built here, exported with Blender's bake, re-run by the engine and compared ---------------

def tree_object(name, build, base=None):
    """An object running a fresh geometry tree. build(tree, nodes, links, group_in, group_out) wires it."""
    tree = new_tree(name)
    group_in, group_out = tree.nodes["Group Input"], tree.nodes["Group Output"]
    out_socket = build(tree, tree.nodes, tree.links, group_in)
    tree.links.new(out_socket, group_out.inputs[0])
    mesh = base if base is not None else bpy.data.meshes.new(name)
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    obj.modifiers.new("GN", "NODES").node_group = tree
    return obj


def node(nodes, kind, **settings):
    n = nodes.new(kind)
    for key, value in settings.items():
        if key in n.inputs:
            n.inputs[key].default_value = value
        else:
            setattr(n, key, value)
    return n


def inp(n, name):
    return [s for s in n.inputs if s.name == name and s.enabled][0]


def outp(n, name):
    return [s for s in n.outputs if s.name == name and s.enabled][0]


def grid_noise(tree, nodes, links, gi):
    grid = node(nodes, "GeometryNodeMeshGrid", **{"Size X": 6.0, "Size Y": 4.0, "Vertices X": 13, "Vertices Y": 9})
    noise = node(nodes, "ShaderNodeTexNoise", Scale=0.8, Detail=3.0)
    scale = node(nodes, "ShaderNodeVectorMath", operation="SCALE")
    scale.inputs["Scale"].default_value = 0.7
    links.new(outp(noise, "Color"), scale.inputs[0])
    setpos = node(nodes, "GeometryNodeSetPosition")
    links.new(grid.outputs["Mesh"], setpos.inputs["Geometry"])
    links.new(scale.outputs["Vector"], inp(setpos, "Offset"))
    vor = node(nodes, "ShaderNodeTexVoronoi", Scale=1.5)
    store = node(nodes, "GeometryNodeStoreNamedAttribute", data_type="FLOAT_COLOR", domain="POINT")
    store.inputs["Name"].default_value = "col"
    links.new(setpos.outputs[0], store.inputs["Geometry"])
    links.new(outp(vor, "Color"), inp(store, "Value"))
    uv = node(nodes, "GeometryNodeStoreNamedAttribute", data_type="FLOAT2", domain="CORNER")
    uv.inputs["Name"].default_value = "UVMap"
    links.new(store.outputs[0], uv.inputs["Geometry"])
    links.new(grid.outputs["UV Map"], inp(uv, "Value"))
    return uv.outputs[0]


def scatter(tree, nodes, links, gi):
    grid = node(nodes, "GeometryNodeMeshGrid", **{"Size X": 4.0, "Size Y": 3.0, "Vertices X": 5, "Vertices Y": 4})
    dist = node(nodes, "GeometryNodeDistributePointsOnFaces", Density=6.0, Seed=3)
    links.new(grid.outputs["Mesh"], dist.inputs["Mesh"])
    cube = node(nodes, "GeometryNodeMeshCube", Size=(0.2, 0.1, 0.3))
    rand_rot = node(nodes, "FunctionNodeRandomValue", data_type="FLOAT_VECTOR")
    inp(rand_rot, "Max").default_value = (3.0, 3.0, 3.0)
    rand_scale = node(nodes, "FunctionNodeRandomValue", data_type="FLOAT")
    inp(rand_scale, "Min").default_value = 0.5
    inp(rand_scale, "Max").default_value = 1.5
    inp(rand_scale, "Seed").default_value = 9
    euler = node(nodes, "FunctionNodeEulerToRotation")
    links.new(outp(rand_rot, "Value"), euler.inputs[0])
    iop = node(nodes, "GeometryNodeInstanceOnPoints")
    links.new(dist.outputs["Points"], iop.inputs["Points"])
    links.new(cube.outputs["Mesh"], iop.inputs["Instance"])
    links.new(euler.outputs[0], iop.inputs["Rotation"])
    links.new(outp(rand_scale, "Value"), iop.inputs["Scale"])
    realize = node(nodes, "GeometryNodeRealizeInstances")
    links.new(iop.outputs[0], realize.inputs[0])
    return realize.outputs[0]


def scatter_aligned(tree, nodes, links, gi):
    sphere = node(nodes, "GeometryNodeMeshUVSphere", Segments=12, Rings=8, Radius=2.0)
    dist = node(nodes, "GeometryNodeDistributePointsOnFaces", Density=3.0, Seed=11)
    links.new(sphere.outputs["Mesh"], dist.inputs["Mesh"])
    cone = node(nodes, "GeometryNodeMeshCone", Vertices=5, **{"Radius Bottom": 0.1, "Depth": 0.4})
    iop = node(nodes, "GeometryNodeInstanceOnPoints")
    links.new(dist.outputs["Points"], iop.inputs["Points"])
    links.new(cone.outputs["Mesh"], iop.inputs["Instance"])
    links.new(dist.outputs["Rotation"], iop.inputs["Rotation"])
    realize = node(nodes, "GeometryNodeRealizeInstances")
    links.new(iop.outputs[0], realize.inputs[0])
    return realize.outputs[0]


def poisson(tree, nodes, links, gi):
    grid = node(nodes, "GeometryNodeMeshGrid", **{"Size X": 3.0, "Size Y": 3.0, "Vertices X": 4, "Vertices Y": 4})
    dist = node(nodes, "GeometryNodeDistributePointsOnFaces", distribute_method="POISSON", Seed=5)
    inp(dist, "Distance Min").default_value = 0.25
    inp(dist, "Density Max").default_value = 40.0
    links.new(grid.outputs["Mesh"], dist.inputs["Mesh"])
    return dist.outputs["Points"]


def primitives(tree, nodes, links, gi):
    join = node(nodes, "GeometryNodeJoinGeometry")
    parts = [
        node(nodes, "GeometryNodeMeshCube", Size=(1.0, 2.0, 1.5), **{"Vertices X": 3, "Vertices Y": 2, "Vertices Z": 4}),
        node(nodes, "GeometryNodeMeshCylinder", fill_type="TRIANGLE_FAN", Vertices=7, **{"Side Segments": 2, "Fill Segments": 2}),
        node(nodes, "GeometryNodeMeshCone", Vertices=6, **{"Radius Top": 0.3}),
        node(nodes, "GeometryNodeMeshUVSphere", Segments=9, Rings=5),
        node(nodes, "GeometryNodeMeshIcoSphere", Subdivisions=1),
        node(nodes, "GeometryNodeMeshCircle", fill_type="NGON", Vertices=5),
        node(nodes, "GeometryNodeMeshLine", Count=4),
    ]
    for k, part in enumerate(parts):
        transform = node(nodes, "GeometryNodeTransform")
        transform.inputs["Translation"].default_value = (3.0 * k, 0.5 * k, 0.0)
        links.new(part.outputs[0], transform.inputs["Geometry"])
        links.new(transform.outputs[0], join.inputs[0])
    return join.outputs[0]


def curve_pipe(tree, nodes, links, gi):
    spiral = node(nodes, "GeometryNodeCurveSpiral", Resolution=16, Rotations=1.5, Height=3.0)
    resample = node(nodes, "GeometryNodeResampleCurve")
    resample.inputs["Count"].default_value = 30
    links.new(spiral.outputs[0], resample.inputs["Curve"])
    param = node(nodes, "GeometryNodeSplineParameter")
    radius = node(nodes, "ShaderNodeMapRange")
    radius.inputs["To Min"].default_value = 1.0
    radius.inputs["To Max"].default_value = 0.3
    links.new(param.outputs["Factor"], radius.inputs["Value"])
    setr = node(nodes, "GeometryNodeSetCurveRadius")
    links.new(resample.outputs[0], setr.inputs["Curve"])
    links.new(radius.outputs["Result"], inp(setr, "Radius"))
    profile = node(nodes, "GeometryNodeCurvePrimitiveCircle", Resolution=8, Radius=0.25)
    c2m = node(nodes, "GeometryNodeCurveToMesh")
    c2m.inputs["Fill Caps"].default_value = True
    links.new(setr.outputs[0], c2m.inputs["Curve"])
    links.new(profile.outputs["Curve"], c2m.inputs["Profile Curve"])
    return c2m.outputs[0]


def mesh_ops(tree, nodes, links, gi):
    grid = node(nodes, "GeometryNodeMeshGrid", **{"Size X": 3.0, "Size Y": 3.0, "Vertices X": 5, "Vertices Y": 5})
    index = node(nodes, "GeometryNodeInputIndex")
    mod = node(nodes, "ShaderNodeMath", operation="FLOORED_MODULO")
    mod.inputs[1].default_value = 3.0
    links.new(index.outputs[0], mod.inputs[0])
    cmp = node(nodes, "FunctionNodeCompare", data_type="FLOAT", operation="EQUAL")
    links.new(mod.outputs[0], cmp.inputs["A"])
    extrude = node(nodes, "GeometryNodeExtrudeMesh", mode="FACES")
    extrude.inputs["Offset Scale"].default_value = 0.5
    links.new(grid.outputs[0], extrude.inputs["Mesh"])
    links.new(cmp.outputs[0], extrude.inputs["Selection"])
    tri = node(nodes, "GeometryNodeTriangulate")
    links.new(extrude.outputs["Mesh"], tri.inputs["Mesh"])
    sub = node(nodes, "GeometryNodeSubdivideMesh")
    sub.inputs["Level"].default_value = 1
    links.new(tri.outputs[0], sub.inputs["Mesh"])
    return sub.outputs[0]


def fields_line(tree, nodes, links, gi):
    line = node(nodes, "GeometryNodeMeshLine", Count=40, Offset=(0.1, 0.0, 0.0))
    pos = node(nodes, "GeometryNodeInputPosition")
    sep = node(nodes, "ShaderNodeSeparateXYZ")
    links.new(pos.outputs[0], sep.inputs[0])
    sine = node(nodes, "ShaderNodeMath", operation="SINE")
    mul = node(nodes, "ShaderNodeMath", operation="MULTIPLY")
    mul.inputs[1].default_value = 4.0
    links.new(sep.outputs["X"], mul.inputs[0])
    links.new(mul.outputs[0], sine.inputs[0])
    curve = node(nodes, "ShaderNodeFloatCurve")
    curve.mapping.curves[0].points.new(0.5, 0.9)
    links.new(sep.outputs["X"], inp(curve, "Value"))
    ramp = node(nodes, "ShaderNodeValToRGB")
    ramp.color_ramp.interpolation = "CONSTANT"
    ramp.color_ramp.elements.new(0.4).color = (0.1, 0.8, 0.2, 1.0)
    links.new(sep.outputs["X"], ramp.inputs[0])
    mix = node(nodes, "ShaderNodeMix", data_type="RGBA", blend_type="OVERLAY")
    links.new(ramp.outputs["Color"], inp(mix, "A"))
    inp(mix, "B").default_value = (0.3, 0.5, 0.9, 1.0)
    inp(mix, "Factor").default_value = 0.6
    combine = node(nodes, "ShaderNodeCombineXYZ")
    links.new(sep.outputs["X"], combine.inputs["X"])
    links.new(sine.outputs[0], combine.inputs["Y"])
    links.new(outp(curve, "Value"), combine.inputs["Z"])
    setpos = node(nodes, "GeometryNodeSetPosition")
    links.new(line.outputs[0], setpos.inputs["Geometry"])
    links.new(combine.outputs[0], inp(setpos, "Position"))
    store = node(nodes, "GeometryNodeStoreNamedAttribute", data_type="FLOAT_COLOR", domain="POINT")
    store.inputs["Name"].default_value = "col"
    links.new(setpos.outputs[0], store.inputs["Geometry"])
    links.new(outp(mix, "Result"), inp(store, "Value"))
    rng = node(nodes, "ShaderNodeMapRange", interpolation_type="SMOOTHSTEP")
    links.new(sep.outputs["X"], rng.inputs["Value"])
    rng.inputs["From Max"].default_value = 4.0
    store2 = node(nodes, "GeometryNodeStoreNamedAttribute", data_type="FLOAT", domain="POINT")
    store2.inputs["Name"].default_value = "ramp"
    links.new(store.outputs[0], store2.inputs["Geometry"])
    links.new(rng.outputs["Result"], inp(store2, "Value"))
    return store2.outputs[0]


def repeat(tree, nodes, links, gi):
    cube = node(nodes, "GeometryNodeMeshCube", Size=(0.5, 0.5, 0.5))
    rin = nodes.new("GeometryNodeRepeatInput")
    rout = nodes.new("GeometryNodeRepeatOutput")
    rin.pair_with_output(rout)
    rin.inputs["Iterations"].default_value = 4
    links.new(cube.outputs[0], rin.inputs[1])
    transform = node(nodes, "GeometryNodeTransform")
    transform.inputs["Translation"].default_value = (0.8, 0.0, 0.2)
    transform.inputs["Rotation"].default_value = (0.0, 0.0, 0.3)
    links.new(rin.outputs[1], transform.inputs["Geometry"])
    join = node(nodes, "GeometryNodeJoinGeometry")
    links.new(transform.outputs[0], join.inputs[0])
    links.new(rin.outputs[1], join.inputs[0])
    links.new(join.outputs[0], rout.inputs[0])
    return rout.outputs[0]


def smooth_by_angle(tree, nodes, links, gi):
    cyl = node(nodes, "GeometryNodeMeshCylinder", Vertices=12)
    angle = node(nodes, "GeometryNodeInputMeshEdgeAngle")
    cmp = node(nodes, "FunctionNodeCompare", data_type="FLOAT", operation="LESS_EQUAL")
    cmp.inputs["B"].default_value = 0.6
    links.new(angle.outputs["Unsigned Angle"], cmp.inputs["A"])
    smooth = node(nodes, "GeometryNodeSetShadeSmooth", domain="EDGE")
    links.new(cyl.outputs[0], smooth.inputs[0])
    links.new(cmp.outputs[0], inp(smooth, "Shade Smooth"))
    faces = node(nodes, "GeometryNodeSetShadeSmooth", domain="FACE")
    links.new(smooth.outputs[0], faces.inputs[0])
    return faces.outputs[0]


def instance_ops(tree, nodes, links, gi):
    points = node(nodes, "GeometryNodeMeshLine", Count=6, Offset=(1.0, 0.0, 0.0))
    ico = node(nodes, "GeometryNodeMeshIcoSphere", Radius=0.3, Subdivisions=1)
    iop = node(nodes, "GeometryNodeInstanceOnPoints")
    links.new(points.outputs[0], iop.inputs["Points"])
    links.new(ico.outputs[0], iop.inputs["Instance"])
    index = node(nodes, "GeometryNodeInputIndex")
    rot = node(nodes, "ShaderNodeCombineXYZ")
    links.new(index.outputs[0], rot.inputs["Z"])
    euler = node(nodes, "FunctionNodeEulerToRotation")
    links.new(rot.outputs[0], euler.inputs[0])
    rotate = node(nodes, "GeometryNodeRotateInstances")
    links.new(iop.outputs[0], rotate.inputs["Instances"])
    links.new(euler.outputs[0], rotate.inputs["Rotation"])
    scale = node(nodes, "GeometryNodeScaleInstances")
    scale.inputs["Scale"].default_value = (1.0, 2.0, 0.5)
    links.new(rotate.outputs[0], scale.inputs["Instances"])
    translate = node(nodes, "GeometryNodeTranslateInstances")
    translate.inputs["Translation"].default_value = (0.0, 0.0, 1.0)
    links.new(scale.outputs[0], translate.inputs["Instances"])
    realize = node(nodes, "GeometryNodeRealizeInstances")
    links.new(translate.outputs[0], realize.inputs[0])
    return realize.outputs[0]


def capture_sweep(tree, nodes, links, gi):
    circle = node(nodes, "GeometryNodeCurvePrimitiveCircle", Resolution=24, Radius=2.0)
    param = node(nodes, "GeometryNodeSplineParameter")
    capture = nodes.new("GeometryNodeCaptureAttribute")
    capture.capture_items.new("FLOAT", "Factor")
    links.new(circle.outputs["Curve"], capture.inputs["Geometry"])
    links.new(param.outputs["Factor"], inp(capture, "Factor"))
    line = node(nodes, "GeometryNodeCurvePrimitiveLine", Start=(-0.2, 0.0, 0.0), End=(0.2, 0.0, 0.0))
    c2m = node(nodes, "GeometryNodeCurveToMesh")
    links.new(capture.outputs["Geometry"], c2m.inputs["Curve"])
    links.new(line.outputs[0], c2m.inputs["Profile Curve"])
    store = node(nodes, "GeometryNodeStoreNamedAttribute", data_type="FLOAT", domain="POINT")
    store.inputs["Name"].default_value = "factor"
    links.new(c2m.outputs[0], store.inputs["Geometry"])
    links.new(outp(capture, "Factor"), inp(store, "Value"))
    return store.outputs[0]


def delete_separate(tree, nodes, links, gi):
    sphere = node(nodes, "GeometryNodeMeshUVSphere", Segments=16, Rings=8)
    pos = node(nodes, "GeometryNodeInputPosition")
    sep = node(nodes, "ShaderNodeSeparateXYZ")
    links.new(pos.outputs[0], sep.inputs[0])
    cmp = node(nodes, "FunctionNodeCompare", data_type="FLOAT", operation="GREATER_THAN")
    cmp.inputs["B"].default_value = 0.3
    links.new(sep.outputs["Z"], cmp.inputs["A"])
    delete = node(nodes, "GeometryNodeDeleteGeometry", domain="FACE")
    links.new(sphere.outputs[0], delete.inputs["Geometry"])
    links.new(cmp.outputs[0], delete.inputs["Selection"])
    merge = node(nodes, "GeometryNodeMergeByDistance")
    merge.inputs["Distance"].default_value = 0.3
    links.new(delete.outputs[0], merge.inputs["Geometry"])
    return merge.outputs[0]


def extrude_region(tree, nodes, links, gi):
    grid = node(nodes, "GeometryNodeMeshGrid", **{"Size X": 4.0, "Size Y": 4.0, "Vertices X": 5, "Vertices Y": 5})
    pos = node(nodes, "GeometryNodeInputPosition")
    sep = node(nodes, "ShaderNodeSeparateXYZ")
    links.new(pos.outputs[0], sep.inputs[0])
    length = node(nodes, "ShaderNodeVectorMath", operation="LENGTH")
    links.new(pos.outputs[0], length.inputs[0])
    cmp = node(nodes, "FunctionNodeCompare", data_type="FLOAT", operation="LESS_THAN")
    cmp.inputs["B"].default_value = 1.6
    links.new(outp(length, "Value"), cmp.inputs["A"])
    extrude = node(nodes, "GeometryNodeExtrudeMesh", mode="FACES")
    links.new(grid.outputs[0], extrude.inputs["Mesh"])
    links.new(cmp.outputs[0], extrude.inputs["Selection"])
    extrude.inputs["Offset Scale"].default_value = 0.7
    return extrude.outputs["Mesh"]


TREES = [extrude_region, grid_noise, scatter, scatter_aligned, poisson, primitives, curve_pipe, mesh_ops, fields_line, repeat,
         smooth_by_angle, instance_ops, capture_sweep, delete_separate]
objects = []
for k, build in enumerate(TREES):
    obj = tree_object(build.__name__, build)
    obj.location = (0.0, 0.0, 0.0)
    objects.append(obj)
bpy.context.view_layer.update()
textures_obj = bpy.data.objects.get("Textures")
if textures_obj is not None:
    bpy.data.objects.remove(textures_obj)
path = node_export.export(os.path.join(OUT, "fixtures"), objects, bake=True)
print("fixtures: wrote", path)
