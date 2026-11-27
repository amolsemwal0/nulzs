"""Builds the BlenderNodes sample's scene in Blender and exports it as a node package.

A row of objects in procedural materials (the shader nodes the engine compiles to GLSL) and a few objects made by
geometry nodes (which the engine runs itself), so the sample shows both halves working in the engine's lighting:

    blender --background --factory-startup --python tools/blender_nodes/make_showcase.py -- \\
        projects/BlenderNodes/assets/blender/showcase

Open the .blend it saves next to the package to see the same scene in Blender.
"""

import math
import os
import sys

import bmesh
import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import node_export  # noqa: E402

OUT = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else "projects/BlenderNodes/assets/blender/showcase"
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene


# --- Helpers -----------------------------------------------------------------------------------------------

def material(name, build, blended=False):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    tree = mat.node_tree
    tree.nodes.clear()
    out = tree.nodes.new("ShaderNodeOutputMaterial")
    tree.links.new(build(tree.nodes, tree.links), out.inputs["Surface"])
    if blended:
        mat.surface_render_method = "BLENDED"
    return mat


def node(nodes, kind, **settings):
    n = nodes.new(kind)
    for key, value in settings.items():
        if key in n.inputs:
            n.inputs[key].default_value = value
        else:
            setattr(n, key, value)
    return n


def ramp(nodes, stops, interpolation="LINEAR"):
    r = nodes.new("ShaderNodeValToRGB")
    r.color_ramp.interpolation = interpolation
    elements = r.color_ramp.elements
    while len(elements) < len(stops):
        elements.new(0.5)
    for element, (position, color) in zip(elements, stops):
        element.position = position
        element.color = color
    return r


def principled(nodes, links, color_socket=None, color=None, roughness=0.5, metallic=0.0, normal=None):
    p = nodes.new("ShaderNodeBsdfPrincipled")
    if color_socket is not None:
        links.new(color_socket, p.inputs["Base Color"])
    elif color is not None:
        p.inputs["Base Color"].default_value = color
    p.inputs["Roughness"].default_value = roughness
    p.inputs["Metallic"].default_value = metallic
    if normal is not None:
        links.new(normal, p.inputs["Normal"])
    return p


def bump(nodes, links, height, strength=0.4, distance=0.1):
    b = node(nodes, "ShaderNodeBump", Strength=strength, Distance=distance)
    links.new(height, b.inputs["Height"])
    return b.outputs["Normal"]


def sphere(name, mat, location, radius=0.9):
    mesh = bpy.data.meshes.new(name)
    bm = bmesh.new()
    bm.loops.layers.uv.new("UVMap")  # calc_uvs fills an existing layer; it makes none itself
    bmesh.ops.create_uvsphere(bm, u_segments=48, v_segments=24, radius=radius, calc_uvs=True)
    bm.to_mesh(mesh)
    bm.free()
    mesh.shade_smooth()
    mesh.materials.append(mat)
    obj = bpy.data.objects.new(name, mesh)
    obj.location = location
    scene.collection.objects.link(obj)
    return obj


def cube(name, mat, location, size=1.6):
    mesh = bpy.data.meshes.new(name)
    bm = bmesh.new()
    bm.loops.layers.uv.new("UVMap")
    bmesh.ops.create_cube(bm, size=size, calc_uvs=True)
    bm.to_mesh(mesh)
    bm.free()
    mesh.materials.append(mat)
    obj = bpy.data.objects.new(name, mesh)
    obj.location = location
    scene.collection.objects.link(obj)
    return obj


# --- Materials -----------------------------------------------------------------------------------------------

def marble(nodes, links):
    noise = node(nodes, "ShaderNodeTexNoise", Scale=2.5, Detail=8.0, Roughness=0.62, Distortion=1.6)
    r = ramp(nodes, [(0.35, (0.95, 0.93, 0.9, 1)), (0.55, (0.55, 0.55, 0.6, 1)), (0.62, (0.15, 0.15, 0.2, 1))])
    links.new(noise.outputs["Fac"], r.inputs[0])
    return principled(nodes, links, r.outputs["Color"], roughness=0.25,
                      normal=bump(nodes, links, noise.outputs["Fac"], 0.25)).outputs[0]


def cells(nodes, links):
    vor = node(nodes, "ShaderNodeTexVoronoi", Scale=6.0)
    edge = node(nodes, "ShaderNodeTexVoronoi", Scale=6.0, feature="DISTANCE_TO_EDGE")
    r = ramp(nodes, [(0.0, (0.02, 0.02, 0.02, 1)), (0.08, (1, 1, 1, 1))])
    links.new(edge.outputs["Distance"], r.inputs[0])
    mix = node(nodes, "ShaderNodeMix", data_type="RGBA", blend_type="MULTIPLY")
    mix.inputs["Factor"].default_value = 1.0
    links.new(vor.outputs["Color"], mix.inputs[6])
    links.new(r.outputs["Color"], mix.inputs[7])
    return principled(nodes, links, mix.outputs[2], roughness=0.6).outputs[0]


def bricks(nodes, links):
    tc = node(nodes, "ShaderNodeTexCoord")
    brick = node(nodes, "ShaderNodeTexBrick", Scale=4.0, **{"Mortar Size": 0.03, "Color1": (0.55, 0.12, 0.06, 1),
                                                         "Color2": (0.35, 0.09, 0.05, 1), "Mortar": (0.6, 0.58, 0.55, 1)})
    links.new(tc.outputs["UV"], brick.inputs["Vector"])
    inv = node(nodes, "ShaderNodeMath", operation="SUBTRACT")
    inv.inputs[0].default_value = 1.0
    links.new(brick.outputs["Fac"], inv.inputs[1])
    return principled(nodes, links, brick.outputs["Color"], roughness=0.8,
                      normal=bump(nodes, links, inv.outputs[0], 0.6, 0.05)).outputs[0]


def wood(nodes, links):
    tc = node(nodes, "ShaderNodeTexCoord")
    mapping = node(nodes, "ShaderNodeMapping")
    mapping.inputs["Scale"].default_value = (1.0, 1.0, 0.25)
    links.new(tc.outputs["Object"], mapping.inputs["Vector"])
    wave = node(nodes, "ShaderNodeTexWave", wave_type="RINGS", rings_direction="Z", wave_profile="SAW", Scale=2.0,
                Distortion=4.0, Detail=3.0, **{"Detail Scale": 1.5})
    links.new(mapping.outputs[0], wave.inputs["Vector"])
    r = ramp(nodes, [(0.0, (0.25, 0.1, 0.03, 1)), (0.7, (0.55, 0.3, 0.1, 1)), (1.0, (0.3, 0.13, 0.04, 1))], "EASE")
    links.new(wave.outputs["Fac"], r.inputs[0])
    return principled(nodes, links, r.outputs["Color"], roughness=0.45).outputs[0]


def toon(nodes, links):
    diffuse = node(nodes, "ShaderNodeBsdfDiffuse", Color=(0.9, 0.35, 0.45, 1))
    to_rgb = nodes.new("ShaderNodeShaderToRGB")
    links.new(diffuse.outputs[0], to_rgb.inputs[0])
    r = ramp(nodes, [(0.0, (0.25, 0.08, 0.2, 1)), (0.12, (0.65, 0.22, 0.32, 1)), (0.45, (1.0, 0.62, 0.62, 1))], "CONSTANT")
    links.new(to_rgb.outputs["Color"], r.inputs[0])
    layer = node(nodes, "ShaderNodeLayerWeight", Blend=0.3)
    rim = ramp(nodes, [(0.6, (0, 0, 0, 1)), (0.65, (0.5, 0.45, 0.6, 1))], "CONSTANT")
    links.new(layer.outputs["Facing"], rim.inputs[0])
    add = node(nodes, "ShaderNodeMix", data_type="RGBA", blend_type="ADD")
    add.inputs["Factor"].default_value = 1.0
    links.new(r.outputs["Color"], add.inputs[6])
    links.new(rim.outputs["Color"], add.inputs[7])
    emission = node(nodes, "ShaderNodeEmission", Strength=1.0)
    links.new(add.outputs[2], emission.inputs["Color"])
    return emission.outputs[0]


def checker(nodes, links):
    tc = node(nodes, "ShaderNodeTexCoord")
    ch = node(nodes, "ShaderNodeTexChecker", Scale=8.0, Color1=(0.9, 0.9, 0.85, 1), Color2=(0.08, 0.1, 0.15, 1))
    links.new(tc.outputs["UV"], ch.inputs["Vector"])
    return principled(nodes, links, ch.outputs["Color"], roughness=0.35).outputs[0]


def glass(nodes, links):
    transparent = node(nodes, "ShaderNodeBsdfTransparent", Color=(0.6, 0.9, 1.0, 1))
    glossy = node(nodes, "ShaderNodeBsdfAnisotropic", Color=(0.8, 0.95, 1.0, 1), Roughness=0.1)
    layer = node(nodes, "ShaderNodeLayerWeight", Blend=0.4)
    mix = nodes.new("ShaderNodeMixShader")
    links.new(layer.outputs["Facing"], mix.inputs[0])
    links.new(transparent.outputs[0], mix.inputs[1])
    links.new(glossy.outputs[0], mix.inputs[2])
    return mix.outputs[0]


def lava(nodes, links):
    time = nodes.new("GeometryNodeInputSceneTime")
    speed = node(nodes, "ShaderNodeMath", operation="MULTIPLY")
    speed.inputs[1].default_value = 0.15
    links.new(time.outputs["Seconds"], speed.inputs[0])
    noise = node(nodes, "ShaderNodeTexNoise", noise_dimensions="4D", Scale=3.0, Detail=4.0, Distortion=0.8)
    links.new(speed.outputs[0], noise.inputs["W"])
    r = ramp(nodes, [(0.35, (0.05, 0.01, 0.0, 1)), (0.5, (0.9, 0.18, 0.0, 1)), (0.65, (1.0, 0.8, 0.2, 1))])
    links.new(noise.outputs["Fac"], r.inputs[0])
    glow = nodes.new("ShaderNodeValue")
    glow.name = glow.label = "Glow"
    glow.outputs[0].default_value = 2.5
    emission = nodes.new("ShaderNodeEmission")
    links.new(r.outputs["Color"], emission.inputs["Color"])
    links.new(glow.outputs[0], emission.inputs["Strength"])
    crust = principled(nodes, links, color=(0.04, 0.03, 0.03, 1), roughness=0.9)
    mask = ramp(nodes, [(0.42, (1, 1, 1, 1)), (0.48, (0, 0, 0, 1))])
    links.new(noise.outputs["Fac"], mask.inputs[0])
    mix = nodes.new("ShaderNodeMixShader")
    links.new(mask.outputs["Color"], mix.inputs[0])
    links.new(emission.outputs[0], mix.inputs[1])
    links.new(crust.outputs[0], mix.inputs[2])
    return mix.outputs[0]


def magic(nodes, links):
    m = node(nodes, "ShaderNodeTexMagic", Scale=3.0, turbulence_depth=4)
    hue = node(nodes, "ShaderNodeHueSaturation", Saturation=0.7)
    links.new(m.outputs["Color"], hue.inputs["Color"])
    return principled(nodes, links, hue.outputs[0], roughness=0.3).outputs[0]


def gradient(nodes, links):
    tc = node(nodes, "ShaderNodeTexCoord")
    g = node(nodes, "ShaderNodeTexGradient", gradient_type="SPHERICAL")
    links.new(tc.outputs["Object"], g.inputs["Vector"])
    r = ramp(nodes, [(0.0, (0.1, 0.2, 0.8, 1)), (0.5, (0.2, 0.9, 0.6, 1)), (1.0, (1.0, 1.0, 0.4, 1))])
    links.new(g.outputs["Fac"], r.inputs[0])
    return principled(nodes, links, r.outputs["Color"], roughness=0.5, metallic=0.4).outputs[0]


def picture(nodes, links):
    image = bpy.data.images.new("Showcase Grid", 256, 256)
    image.generated_type = "COLOR_GRID"
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = image
    return principled(nodes, links, tex.outputs["Color"], roughness=0.5).outputs[0]


def ground(nodes, links):
    noise = node(nodes, "ShaderNodeTexNoise", Scale=0.4, Detail=5.0)
    vor = node(nodes, "ShaderNodeTexVoronoi", Scale=1.2, feature="SMOOTH_F1")
    mix = node(nodes, "ShaderNodeMix", data_type="RGBA", blend_type="OVERLAY")
    mix.inputs["Factor"].default_value = 0.5
    r = ramp(nodes, [(0.3, (0.12, 0.3, 0.08, 1)), (0.6, (0.3, 0.45, 0.12, 1)), (0.8, (0.45, 0.4, 0.2, 1))])
    links.new(noise.outputs["Fac"], r.inputs[0])
    links.new(r.outputs["Color"], mix.inputs[6])
    links.new(vor.outputs["Color"], mix.inputs[7])
    return principled(nodes, links, mix.outputs[2], roughness=0.9).outputs[0]


materials = {
    "Marble": material("Marble", marble), "Cells": material("Cells", cells), "Bricks": material("Bricks", bricks),
    "Wood": material("Wood", wood), "Toon": material("Toon", toon), "Checker": material("Checker", checker),
    "Glass": material("Glass", glass, blended=True), "Lava": material("Lava", lava), "Magic": material("Magic", magic),
    "Gradient": material("Gradient", gradient), "Picture": material("Picture", picture), "Ground": material("Ground", ground),
}

# --- A row of shapes, one per material -----------------------------------------------------------------------

row = ["Marble", "Cells", "Wood", "Toon", "Glass", "Lava", "Magic", "Gradient"]
for k, name in enumerate(row):
    sphere(name + " sphere", materials[name], (k * 2.4 - 8.4, 0.0, 1.0))
for k, name in enumerate(["Bricks", "Checker", "Picture"]):
    cube(name + " cube", materials[name], (k * 2.4 - 2.4, 4.0, 0.8))


# --- Geometry-node objects ------------------------------------------------------------------------------------

def gn_object(name, build, inputs=()):
    tree = bpy.data.node_groups.new(name, "GeometryNodeTree")
    tree.interface.new_socket("Geometry", in_out="INPUT", socket_type="NodeSocketGeometry")
    tree.interface.new_socket("Geometry", in_out="OUTPUT", socket_type="NodeSocketGeometry")
    sockets = {}
    for label, kind, default in inputs:
        sockets[label] = tree.interface.new_socket(label, in_out="INPUT", socket_type=kind)
        sockets[label].default_value = default
    gi = tree.nodes.new("NodeGroupInput")
    go = tree.nodes.new("NodeGroupOutput")
    tree.links.new(build(tree.nodes, tree.links, gi), go.inputs[0])
    obj = bpy.data.objects.new(name, bpy.data.meshes.new(name))
    scene.collection.objects.link(obj)
    obj.modifiers.new("GeometryNodes", "NODES").node_group = tree
    return obj


def set_material(nodes, links, geometry, mat):
    s = nodes.new("GeometryNodeSetMaterial")
    s.inputs["Material"].default_value = mat
    links.new(geometry, s.inputs["Geometry"])
    return s.outputs[0]


def ground_tree(nodes, links, gi):
    grid = node(nodes, "GeometryNodeMeshGrid", **{"Size X": 40.0, "Size Y": 30.0, "Vertices X": 81, "Vertices Y": 61})
    noise = node(nodes, "ShaderNodeTexNoise", Scale=0.08, Detail=3.0)
    height = node(nodes, "ShaderNodeMath", operation="MULTIPLY_ADD")
    height.inputs[1].default_value = 1.2
    height.inputs[2].default_value = -0.8
    links.new(noise.outputs["Fac"], height.inputs[0])
    offset = node(nodes, "ShaderNodeCombineXYZ")
    links.new(height.outputs[0], offset.inputs["Z"])
    pos = node(nodes, "GeometryNodeInputPosition")
    length = node(nodes, "ShaderNodeVectorMath", operation="LENGTH")
    links.new(pos.outputs[0], length.inputs[0])
    flat = node(nodes, "ShaderNodeMapRange", **{"From Min": 10.0, "From Max": 16.0})
    links.new(length.outputs["Value"], flat.inputs["Value"])
    scaled = node(nodes, "ShaderNodeVectorMath", operation="SCALE")
    links.new(offset.outputs[0], scaled.inputs[0])
    links.new(flat.outputs["Result"], scaled.inputs["Scale"])
    setpos = nodes.new("GeometryNodeSetPosition")
    links.new(grid.outputs["Mesh"], setpos.inputs["Geometry"])
    links.new(scaled.outputs["Vector"], setpos.inputs["Offset"])
    smooth = nodes.new("GeometryNodeSetShadeSmooth")
    links.new(setpos.outputs[0], smooth.inputs[0])
    return set_material(nodes, links, smooth.outputs[0], materials["Ground"])


def scatter_tree(nodes, links, gi):
    patch = node(nodes, "GeometryNodeMeshCircle", Vertices=24, Radius=3.0, fill_type="NGON")
    dist = node(nodes, "GeometryNodeDistributePointsOnFaces", Density=3.0, Seed=4)
    links.new(patch.outputs[0], dist.inputs["Mesh"])
    rock = node(nodes, "GeometryNodeMeshIcoSphere", Radius=0.22, Subdivisions=2)
    rock_mat = set_material(nodes, links, rock.outputs[0], materials["Cells"])
    rot = node(nodes, "FunctionNodeRandomValue", data_type="FLOAT_VECTOR")
    [s for s in rot.inputs if s.name == "Max" and s.enabled][0].default_value = (6.3, 6.3, 6.3)
    scale = node(nodes, "FunctionNodeRandomValue", data_type="FLOAT_VECTOR")
    [s for s in scale.inputs if s.name == "Min" and s.enabled][0].default_value = (0.5, 0.5, 0.35)
    [s for s in scale.inputs if s.name == "Max" and s.enabled][0].default_value = (1.4, 1.2, 0.9)
    euler = nodes.new("FunctionNodeEulerToRotation")
    links.new([s for s in rot.outputs if s.enabled][0], euler.inputs[0])
    iop = nodes.new("GeometryNodeInstanceOnPoints")
    links.new(dist.outputs["Points"], iop.inputs["Points"])
    links.new(rock_mat, iop.inputs["Instance"])
    links.new(euler.outputs[0], iop.inputs["Rotation"])
    links.new([s for s in scale.outputs if s.enabled][0], iop.inputs["Scale"])
    return iop.outputs[0]


def pipe_tree(nodes, links, gi):
    spiral = node(nodes, "GeometryNodeCurveSpiral", Resolution=24, Rotations=2.5, **{"Start Radius": 1.4,
                                                                                      "End Radius": 0.6, "Height": 3.0})
    profile = node(nodes, "GeometryNodeCurvePrimitiveCircle", Resolution=12, Radius=0.18)
    c2m = nodes.new("GeometryNodeCurveToMesh")
    c2m.inputs["Fill Caps"].default_value = True
    links.new(spiral.outputs[0], c2m.inputs["Curve"])
    links.new(profile.outputs["Curve"], c2m.inputs["Profile Curve"])
    smooth = nodes.new("GeometryNodeSetShadeSmooth")
    links.new(c2m.outputs[0], smooth.inputs[0])
    return set_material(nodes, links, smooth.outputs[0], materials["Toon"])


def fence_tree(nodes, links, gi):
    posts_count = gi.outputs["Posts"]
    height = gi.outputs["Height"]
    span = node(nodes, "ShaderNodeMath", operation="MULTIPLY")
    span.inputs[1].default_value = 1.2
    to_float = node(nodes, "ShaderNodeMath", operation="SUBTRACT")
    to_float.inputs[1].default_value = 1.0
    links.new(posts_count, to_float.inputs[0])
    links.new(to_float.outputs[0], span.inputs[0])
    end = node(nodes, "ShaderNodeCombineXYZ")
    links.new(span.outputs[0], end.inputs["X"])
    line = node(nodes, "GeometryNodeMeshLine", mode="END_POINTS")
    links.new(posts_count, line.inputs["Count"])
    links.new(end.outputs[0], line.inputs["Offset"])
    post_size = node(nodes, "ShaderNodeCombineXYZ", X=0.16, Y=0.16)
    links.new(height, post_size.inputs["Z"])
    post = nodes.new("GeometryNodeMeshCube")
    links.new(post_size.outputs[0], post.inputs["Size"])
    half = node(nodes, "ShaderNodeMath", operation="MULTIPLY")
    half.inputs[1].default_value = 0.5
    links.new(height, half.inputs[0])
    lift = node(nodes, "ShaderNodeCombineXYZ")
    links.new(half.outputs[0], lift.inputs["Z"])
    raise_post = nodes.new("GeometryNodeTransform")
    links.new(post.outputs["Mesh"], raise_post.inputs["Geometry"])
    links.new(lift.outputs[0], raise_post.inputs["Translation"])
    iop = nodes.new("GeometryNodeInstanceOnPoints")
    links.new(line.outputs[0], iop.inputs["Points"])
    links.new(raise_post.outputs[0], iop.inputs["Instance"])
    rail_profile = node(nodes, "GeometryNodeCurvePrimitiveQuadrilateral", Width=0.08, Height=0.14)
    join = nodes.new("GeometryNodeJoinGeometry")
    for level in (0.35, 0.8):
        rail_height = node(nodes, "ShaderNodeMath", operation="MULTIPLY")
        rail_height.inputs[1].default_value = level
        links.new(height, rail_height.inputs[0])
        start = node(nodes, "ShaderNodeCombineXYZ")
        links.new(rail_height.outputs[0], start.inputs["Z"])
        rail_end = node(nodes, "ShaderNodeCombineXYZ")
        links.new(span.outputs[0], rail_end.inputs["X"])
        links.new(rail_height.outputs[0], rail_end.inputs["Z"])
        rail = nodes.new("GeometryNodeCurvePrimitiveLine")
        links.new(start.outputs[0], rail.inputs["Start"])
        links.new(rail_end.outputs[0], rail.inputs["End"])
        sweep = nodes.new("GeometryNodeCurveToMesh")
        sweep.inputs["Fill Caps"].default_value = True
        links.new(rail.outputs[0], sweep.inputs["Curve"])
        links.new(rail_profile.outputs[0], sweep.inputs["Profile Curve"])
        links.new(sweep.outputs[0], join.inputs[0])
    links.new(iop.outputs[0], join.inputs[0])
    realize = nodes.new("GeometryNodeRealizeInstances")
    links.new(join.outputs[0], realize.inputs[0])
    return set_material(nodes, links, realize.outputs[0], materials["Wood"])


ground_obj = gn_object("Ground", ground_tree)
scatter = gn_object("Rock scatter", scatter_tree)
scatter.location = (8.0, 6.0, 0.0)
pipe = gn_object("Toon spiral", pipe_tree)
pipe.location = (-8.0, 6.0, 0.0)
fence = gn_object("Fence", fence_tree, [("Posts", "NodeSocketInt", 7), ("Height", "NodeSocketFloat", 1.1)])
fence.location = (-4.0, -4.5, 0.0)

os.makedirs(OUT, exist_ok=True)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, "showcase.blend"))
if os.path.exists(os.path.join(OUT, "showcase.blend1")):
    os.remove(os.path.join(OUT, "showcase.blend1"))
node_export.export(OUT, [o for o in scene.objects], bake=False)
