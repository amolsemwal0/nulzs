package org.zeronura.engine.asset;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIAnimMesh;
import org.lwjgl.assimp.AIAnimation;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIColor4D;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AINodeAnim;
import org.lwjgl.assimp.AIQuatKey;
import org.lwjgl.assimp.AIQuaternion;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AITexture;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVectorKey;
import org.lwjgl.assimp.AIVertexWeight;
import org.lwjgl.system.MemoryStack;
import org.zeronura.engine.render.Mesh;
import org.zeronura.engine.render.Texture;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

public class AssetLoader {

    private AssetLoader() {
    }

    /**
     * Loads a model as flat, recentred {@link StaticGeometry} — no skeleton, no materials — for a static
     * prop that renders through the {@link org.zeronura.engine.render.StaticMesh} prop path.
     *
     * <p>Uses {@code aiProcess_PreTransformVertices}, which flattens the node hierarchy and bakes each
     * node's transform into its mesh vertices. That is what makes a block's authored node scale (a
     * Blender cube scaled to 1×1×1 or 5×5×1) come through as real world-space dimensions, where
     * {@link #loadModel} deliberately keeps the hierarchy for skinning and would ignore the node scale.
     * The result is then recentred on its bounding box so the exporter's node translation is discarded.
     */
    public static StaticGeometry loadStaticGeometry(String path) {
        return loadStaticGeometry(path, Map.of());
    }

    /**
     * The same, with <b>shape keys applied at load</b> — the geometry as it stands with those channels
     * pushed to those weights.
     *
     * <p>Baked rather than driven on the GPU, and for a door that is not a compromise: the asset is
     * <b>144 triangles</b>, so a mesh per state costs less than the uniform upload a morph path would
     * need per frame, and it keeps the whole building render path exactly as it is — one
     * {@link org.zeronura.engine.render.StaticMesh}, no vertex layout change, no second shader. A model
     * whose shape varied <i>continuously</i> would want the GPU; one with three named states does not.
     *
     * <p><b>Every state is recentred on the BASE pose's bounding box, not its own</b>, and that is the
     * detail the whole thing turns on. Recentring each state on itself would move a door's origin as it
     * swung — the piece is positioned by its centre, so it would visibly jump across the doorway on being
     * opened. The half-extents returned are the base's for the same reason: what a piece occupies is a
     * property of the piece, not of what it is currently doing.
     *
     * @param shapeWeights shape-key name to weight, 0 leaving that channel alone and 1 applying it in
     *                     full. A name the file does not carry is <b>reported</b> rather than ignored:
     *                     shape keys are matched by name, and a name that silently does nothing is
     *                     indistinguishable from a feature that does not work — which this codebase has
     *                     already been caught by once, with the slime's clips.
     */
    public static StaticGeometry loadStaticGeometry(String path, Map<String, Float> shapeWeights) {
        int flags = aiProcess_Triangulate | aiProcess_GenSmoothNormals | aiProcess_JoinIdenticalVertices;
        if (shapeWeights.isEmpty()) {
            // PreTransformVertices flattens the hierarchy and bakes each node's transform into its
            // vertices, which is what makes a block's authored node scale come through as real dimensions.
            // It is dropped when shape keys are wanted because it also discards the anim meshes they live
            // in; the node transform is composed by hand instead, below.
            flags |= aiProcess_PreTransformVertices;
        }
        AIScene scene = aiImportFile(path, flags);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            return buildStaticGeometry(scene, shapeWeights);
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static StaticGeometry buildStaticGeometry(AIScene scene) {
        return buildStaticGeometry(scene, Map.of());
    }

    /**
     * One material's worth of a model, in the whole model's frame.
     *
     * <p>The frame is the point: every part is recentred on the <b>combined</b> bounding box and reports
     * the <b>combined</b> half-extents, so the pieces stand where they were authored relative to one
     * another. Recentring each on its own box would scatter them — a door's handle would sit at the origin
     * on top of its frame.
     *
     * @param materialName the name the artist gave the material, which is how a caller tells the parts
     *                     apart. Names rather than indices, because an index is whatever order the
     *                     exporter happened to write and says nothing about which part it is.
     */
    public record StaticPart(String materialName, StaticGeometry geometry) {
    }

    /**
     * A model split by material, each part in the whole model's frame.
     *
     * <p>For a model whose parts are made of <i>different things</i> — the door, whose {@code Handle},
     * {@code Centre} and {@code Frame} are three separately chosen materials — where
     * {@link #loadStaticGeometry} merges everything into one buffer that can only be drawn in one colour.
     *
     * <p>Ordered as the file declares its materials, which is stable for a given export and is what makes
     * a part addressable by position as well as by name.
     */
    public static java.util.List<StaticPart> loadStaticParts(String path,
                                                             Map<String, Float> shapeWeights) {
        int flags = aiProcess_Triangulate | aiProcess_GenSmoothNormals | aiProcess_JoinIdenticalVertices;
        if (shapeWeights.isEmpty()) {
            flags |= aiProcess_PreTransformVertices;
        }
        AIScene scene = aiImportFile(path, flags);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            return buildStaticParts(scene, shapeWeights);
        } finally {
            aiReleaseImport(scene);
        }
    }

    /** One material's worth of a model, as {@link StaticPart}, with the colour the material was given. */
    public record ColoredPart(String materialName, StaticGeometry geometry, Vector4f color) {
    }

    /**
     * A model split by material, each part in the whole model's frame and carrying its material's base colour
     * (or its emission, for an unlit material — see {@link #readBaseColor}). For drawing a model untextured in flat
     * colours, as the map editor's imported props are. GL-free.
     */
    public static List<ColoredPart> loadColoredStaticParts(String path) {
        return loadColoredStaticParts(path, false);
    }

    /**
     * As {@link #loadColoredStaticParts(String)}, and with {@code keepOrigin} the parts stay where the file puts them
     * rather than centred on their bounds: for a modular piece whose origin means something — a road tile's is the
     * middle of its tile, wherever its curbs happen to reach.
     */
    public static List<ColoredPart> loadColoredStaticParts(String path, boolean keepOrigin) {
        AIScene scene = aiImportFile(path,
                aiProcess_Triangulate | aiProcess_GenSmoothNormals | aiProcess_JoinIdenticalVertices | aiProcess_PreTransformVertices);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            Map<String, Vector4f> colors = new HashMap<>();
            PointerBuffer meshes = scene.mMeshes();
            PointerBuffer materials = scene.mMaterials();
            for (int m = 0; m < scene.mNumMeshes(); m++) {
                AIMesh mesh = AIMesh.create(meshes.get(m));
                int index = mesh.mMaterialIndex();
                Vector4f color = materials != null && index >= 0 && index < scene.mNumMaterials()
                        ? readBaseColor(AIMaterial.create(materials.get(index)))
                        : new Vector4f(1f, 1f, 1f, 1f);
                colors.putIfAbsent(materialNameOf(scene, mesh), color);
            }
            List<ColoredPart> parts = new ArrayList<>();
            for (StaticPart part : buildStaticParts(scene, Map.of(), !keepOrigin)) {
                parts.add(new ColoredPart(part.materialName(), part.geometry(),
                        colors.getOrDefault(part.materialName(), new Vector4f(1f, 1f, 1f, 1f))));
            }
            return parts;
        } finally {
            aiReleaseImport(scene);
        }
    }

    /** The material's authored name for one of a scene's meshes, or a stable stand-in if it has none. */
    private static String materialNameOf(AIScene scene, AIMesh mesh) {
        int index = mesh.mMaterialIndex();
        PointerBuffer materials = scene.mMaterials();
        if (materials == null || index < 0 || index >= scene.mNumMaterials()) {
            return "material" + index;
        }
        AIString name = AIString.calloc();
        try {
            if (aiGetMaterialString(AIMaterial.create(materials.get(index)),
                    AI_MATKEY_NAME, aiTextureType_NONE, 0, name) != aiReturn_SUCCESS) {
                return "material" + index;
            }
            String s = name.dataString();
            return s == null || s.isBlank() ? "material" + index : s;
        } finally {
            name.free();
        }
    }

    private static StaticGeometry buildStaticGeometry(AIScene scene, Map<String, Float> shapeWeights) {
        // Built from the parts and merged, so the two paths cannot disagree about centring or extents —
        // which for a door is the difference between a leaf that stays put when it swings and one that
        // jumps across its own doorway.
        java.util.List<StaticPart> parts = buildStaticParts(scene, shapeWeights);
        if (parts.size() == 1) {
            return parts.get(0).geometry();
        }
        java.util.List<Float> pos = new ArrayList<>();
        java.util.List<Float> nrm = new ArrayList<>();
        java.util.List<Integer> idx = new ArrayList<>();
        for (StaticPart part : parts) {
            int base = pos.size() / 3;
            for (float v : part.geometry().positions()) {
                pos.add(v);
            }
            for (float v : part.geometry().normals()) {
                nrm.add(v);
            }
            for (int i : part.geometry().indices()) {
                idx.add(base + i);
            }
        }
        StaticGeometry any = parts.get(0).geometry();
        float[] p = new float[pos.size()];
        for (int i = 0; i < p.length; i++) {
            p[i] = pos.get(i);
        }
        float[] n = new float[nrm.size()];
        for (int i = 0; i < n.length; i++) {
            n[i] = nrm.get(i);
        }
        int[] in = new int[idx.size()];
        for (int i = 0; i < in.length; i++) {
            in[i] = idx.get(i);
        }
        return new StaticGeometry(p, n, in,
                any.halfExtentX(), any.halfExtentY(), any.halfExtentZ());
    }

    private static java.util.List<StaticPart> buildStaticParts(AIScene scene,
                                                               Map<String, Float> shapeWeights) {
        return buildStaticParts(scene, shapeWeights, true);
    }

    private static java.util.List<StaticPart> buildStaticParts(AIScene scene, Map<String, Float> shapeWeights,
                                                               boolean centre) {
        java.util.Map<String, java.util.List<Float>> partPositions = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.List<Float>> partNormals = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.List<Integer>> partIndices = new java.util.LinkedHashMap<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        int meshCount = scene.mNumMeshes();
        PointerBuffer meshesPtr = scene.mMeshes();
        for (int m = 0; m < meshCount; m++) {
            AIMesh mesh = AIMesh.create(meshesPtr.get(m));
            String part = materialNameOf(scene, mesh);
            java.util.List<Float> positions =
                    partPositions.computeIfAbsent(part, k -> new ArrayList<>());
            java.util.List<Float> normals = partNormals.computeIfAbsent(part, k -> new ArrayList<>());
            java.util.List<Integer> indices = partIndices.computeIfAbsent(part, k -> new ArrayList<>());
            int base = positions.size() / 3;

            AIVector3D.Buffer verts = mesh.mVertices();
            AIVector3D.Buffer norms = mesh.mNormals();
            int vertexCount = mesh.mNumVertices();
            // The offsets this mesh's shape keys ask for, summed per vertex. Empty in the ordinary case,
            // which is every building piece but the door.
            float[] shapeOffset = shapeOffsets(mesh, shapeWeights, vertexCount);
            // Composed by hand because PreTransformVertices is off whenever shape keys are wanted — it is
            // what would otherwise have applied it, and it discards the anim meshes on the way.
            Matrix4f nodeTransform = shapeWeights.isEmpty()
                    ? null : nodeTransformFor(scene, scene.mRootNode(), m, new Matrix4f());
            Vector3f scratch = new Vector3f();
            for (int v = 0; v < vertexCount; v++) {
                AIVector3D p = verts.get(v);
                scratch.set(p.x(), p.y(), p.z());
                // The BASE pose decides the bounds, before any shape is applied — see the note on
                // loadStaticGeometry. A door recentred on its own open pose swings its origin with it.
                if (nodeTransform != null) {
                    nodeTransform.transformPosition(scratch);
                }
                minX = Math.min(minX, scratch.x);
                minY = Math.min(minY, scratch.y);
                minZ = Math.min(minZ, scratch.z);
                maxX = Math.max(maxX, scratch.x);
                maxY = Math.max(maxY, scratch.y);
                maxZ = Math.max(maxZ, scratch.z);
                if (shapeOffset != null) {
                    scratch.set(p.x() + shapeOffset[v * 3], p.y() + shapeOffset[v * 3 + 1],
                            p.z() + shapeOffset[v * 3 + 2]);
                    if (nodeTransform != null) {
                        nodeTransform.transformPosition(scratch);
                    }
                }
                positions.add(scratch.x);
                positions.add(scratch.y);
                positions.add(scratch.z);
                if (norms != null) {
                    AIVector3D n = norms.get(v);
                    Vector3f nv = new Vector3f(n.x(), n.y(), n.z());
                    if (nodeTransform != null) {
                        nodeTransform.transformDirection(nv).normalize();
                    }
                    normals.add(nv.x);
                    normals.add(nv.y);
                    normals.add(nv.z);
                } else {
                    normals.add(0f);
                    normals.add(1f);
                    normals.add(0f);
                }
            }

            AIFace.Buffer faces = mesh.mFaces();
            int faceCount = mesh.mNumFaces();
            for (int f = 0; f < faceCount; f++) {
                IntBuffer faceIndices = faces.get(f).mIndices();
                for (int k = 0; k < faces.get(f).mNumIndices(); k++) {
                    indices.add(base + faceIndices.get(k));
                }
            }
        }

        // Recentre on the bounding-box centre so the piece is positioned by its own centre, whatever
        // translation the exporter left on the node — and on the COMBINED box, shared by every part, so
        // the parts keep their positions relative to each other.
        float cx = centre ? (minX + maxX) * 0.5f : 0f;
        float cy = centre ? (minY + maxY) * 0.5f : 0f;
        float cz = centre ? (minZ + maxZ) * 0.5f : 0f;
        float hx = (maxX - minX) * 0.5f, hy = (maxY - minY) * 0.5f, hz = (maxZ - minZ) * 0.5f;

        java.util.List<StaticPart> parts = new ArrayList<>();
        for (String part : partPositions.keySet()) {
            java.util.List<Float> positions = partPositions.get(part);
            java.util.List<Float> normals = partNormals.get(part);
            java.util.List<Integer> indices = partIndices.get(part);
            float[] pos = new float[positions.size()];
            for (int i = 0; i < pos.length; i += 3) {
                pos[i] = positions.get(i) - cx;
                pos[i + 1] = positions.get(i + 1) - cy;
                pos[i + 2] = positions.get(i + 2) - cz;
            }
            float[] nrm = new float[normals.size()];
            for (int i = 0; i < nrm.length; i++) {
                nrm[i] = normals.get(i);
            }
            int[] idx = new int[indices.size()];
            for (int i = 0; i < idx.length; i++) {
                idx[i] = indices.get(i);
            }
            parts.add(new StaticPart(part, new StaticGeometry(pos, nrm, idx, hx, hy, hz)));
        }
        return parts;
    }

    /**
     * The summed offsets this mesh's shape keys ask for, or null when none are wanted.
     *
     * <p>Assimp exposes a shape key as an <b>anim mesh</b> — a whole copy of the mesh in its deformed
     * state rather than a set of deltas — so the subtraction happens here, exactly as
     * {@code readMorphTargets} does it for the skinned path. Summed rather than blended: two channels at
     * once means both offsets, which for a door is a state the artist did not intend and the caller is
     * responsible for never asking for (see {@code DoorState}, where it is unrepresentable).
     */
    private static float[] shapeOffsets(AIMesh mesh, Map<String, Float> shapeWeights, int vertexCount) {
        if (shapeWeights.isEmpty() || mesh.mNumAnimMeshes() == 0) {
            return null;
        }
        float[] offset = null;
        PointerBuffer animMeshes = mesh.mAnimMeshes();
        java.util.Set<String> matched = new java.util.HashSet<>();
        AIVector3D.Buffer base = mesh.mVertices();
        for (int t = 0; t < mesh.mNumAnimMeshes(); t++) {
            AIAnimMesh anim = AIAnimMesh.create(animMeshes.get(t));
            String name = nameOf(anim, t);
            Float weight = shapeWeights.get(name);
            if (weight == null || weight == 0f) {
                continue;
            }
            matched.add(name);
            AIVector3D.Buffer morphed = anim.mVertices();
            if (morphed == null || anim.mNumVertices() != vertexCount) {
                continue;
            }
            if (offset == null) {
                offset = new float[vertexCount * 3];
            }
            for (int v = 0; v < vertexCount; v++) {
                AIVector3D b = base.get(v);
                AIVector3D d = morphed.get(v);
                offset[v * 3] += (d.x() - b.x()) * weight;
                offset[v * 3 + 1] += (d.y() - b.y()) * weight;
                offset[v * 3 + 2] += (d.z() - b.z()) * weight;
            }
        }
        for (Map.Entry<String, Float> asked : shapeWeights.entrySet()) {
            if (asked.getValue() != 0f && !matched.contains(asked.getKey())) {
                System.err.println("AssetLoader: no shape key named '" + asked.getKey()
                        + "' — it will do nothing. The file's keys are matched by name and this one is not"
                        + " among them.");
            }
        }
        return offset;
    }

    /**
     * The world transform of whichever node carries mesh {@code meshIndex}, or the identity if none does.
     *
     * <p>Only needed because {@code PreTransformVertices} is off whenever shape keys are wanted; it is
     * what would otherwise have baked this in. A scattered model's node routinely carries the offset and
     * scale that put it where it was built, so skipping it is not a small error — the same reason
     * {@code loadInstancedModel} composes its root transform rather than assuming the identity.
     */
    private static Matrix4f nodeTransformFor(AIScene scene, AINode node, int meshIndex, Matrix4f parent) {
        Matrix4f here = new Matrix4f(parent).mul(toJOML(node.mTransformation(), new Matrix4f()));
        IntBuffer meshes = node.mMeshes();
        for (int i = 0; i < node.mNumMeshes(); i++) {
            if (meshes.get(i) == meshIndex) {
                return here;
            }
        }
        PointerBuffer children = node.mChildren();
        for (int i = 0; i < node.mNumChildren(); i++) {
            Matrix4f found = nodeTransformFor(scene, AINode.create(children.get(i)), meshIndex, here);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Imports only a file's animation clips, with no geometry and no GL.
     *
     * <p>The companion to {@link #loadTexturedParts}, which drops animations because a rigid textured part
     * has no use for them. A <b>node-animated</b> model does: the slime has no bones at all, so its motion
     * lives entirely in clips targeting its root node, and the mesh and the motion are loaded by two
     * different calls into two differently-shaped things ({@code TexturedMesh} vs a list of clips).
     *
     * <p>Imported with no post-processing flags — this reads the animation tables, and every flag on offer
     * operates on geometry. That also keeps it cheap enough to call from a unit test.
     */
    /**
     * A file's shape-key names, with no geometry, no deltas and no GL.
     *
     * <p>The companion to {@link #loadAnimations}, and it exists for the same reason: what a shape key is
     * <i>called</i> is the contract between the art and the game — the creation screen builds its sliders
     * out of these names — so it has to be assertable in a unit test, and {@link #loadModel} needs a
     * context because it uploads buffers.
     *
     * <p>Imported with no post-processing flags. Every flag on offer operates on geometry, and reading a
     * name table does not need any of it.
     */
    public static List<String> loadMorphTargetNames(String path) {
        AIScene scene = aiImportFile(path, 0);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            PointerBuffer meshes = scene.mMeshes();
            for (int m = 0; m < scene.mNumMeshes(); m++) {
                AIMesh mesh = AIMesh.create(meshes.get(m));
                PointerBuffer animMeshes = mesh.mAnimMeshes();
                for (int t = 0; t < mesh.mNumAnimMeshes(); t++) {
                    names.add(nameOf(AIAnimMesh.create(animMeshes.get(t)), t));
                }
            }
            return List.copyOf(names);
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * The names of every bone in a file's skeleton, with no GL context and no geometry uploaded.
     *
     * <p>The companion to {@link #loadMorphTargetNames}, and it exists for the same reason: what sockets a
     * character offers is a question about a file, asked by a test or by a menu, long before anything is
     * drawn. Without it "does the shipped rig actually have the bone this socket names" could only be
     * answered by opening a window — and a socket naming a bone that does not exist fails <b>silently</b>,
     * as an item nobody can see.
     *
     * <p>Empty rather than an error for an unrigged model: the shipped base mesh has no bones at all, and
     * "this character can wear nothing" is an answer.
     *
     * <p>Only joints that actually <b>deform</b> something. A Rigify export lists its whole control rig
     * as joints — measured, 410 of which 73 carry weight — and the rest are IK targets and plumbing that
     * no socket can hang off and no constraint should bind to.
     *
     * @return the bone names, in the order the meshes declare them, without duplicates
     */
    public static List<String> loadBoneNames(String path) {
        AIScene scene = aiImportFile(path, 0);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            PointerBuffer meshes = scene.mMeshes();
            for (int m = 0; m < scene.mNumMeshes(); m++) {
                AIMesh mesh = AIMesh.create(meshes.get(m));
                PointerBuffer bones = mesh.mBones();
                for (int b = 0; b < mesh.mNumBones(); b++) {
                    AIBone bone = AIBone.create(bones.get(b));
                    // The same rule buildMesh applies: a joint that weights nothing is not a bone, and
                    // counting it here would have this answer a different question from the one the
                    // loaded model answers — which is the whole point of this being the cheap way to
                    // ask what a rig has.
                    if (deformsAnything(bone)) {
                        names.add(bone.mName().dataString());
                    }
                }
            }
            return List.copyOf(names);
        } finally {
            aiReleaseImport(scene);
        }
    }

    public static List<AnimationClip> loadAnimations(String path) {
        AIScene scene = aiImportFile(path, 0);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            return buildAnimationClips(scene);
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * Imports a model as one {@link TexturedPart} per mesh — geometry, UVs, and the material it is drawn
     * with — for something that is textured and rigid, but whose materials are not interchangeable.
     *
     * <h2>Why not the other two loaders</h2>
     * {@link #loadStaticGeometry} merges every mesh into one buffer and drops UVs and materials, which is
     * right for an untextured building block and useless for a textured creature with a translucent
     * layer over an opaque one. {@link #loadModel} keeps materials but produces vertices in the
     * <i>skinned</i> layout, and its shader multiplies every vertex by a bone matrix — a model with no
     * bones (the slime has none; it is animated at the node level) has all-zero weights there and
     * collapses to a point.
     *
     * <h2>The root node's own transform is deliberately dropped</h2>
     * Node transforms below the root are baked into the vertices, because they are structure — where the
     * slime's core sits inside its body. The <b>root's</b> local transform is not structure, it is the
     * object's pose in the scene it was exported from, and for an animated object that pose is exactly
     * what an animation replaces. Measured on the slime: its root node carries scale (1.30, 0.70, 0.70)
     * while every keyframe of its idle clip is a near-uniform 0.81–1.01, so baking the node would render
     * a permanently squashed pancake that no frame of the animation ever shows. (This is the opposite
     * choice from {@link #loadStaticGeometry}, where baking the node scale is the entire point — there
     * the node scale is how a block's authored dimensions are expressed, and nothing animates it.)
     */
    public static List<TexturedPart> loadTexturedParts(String path) {
        int flags = aiProcess_Triangulate | aiProcess_GenSmoothNormals | aiProcess_JoinIdenticalVertices;
        AIScene scene = aiImportFile(path, flags);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            List<TexturedPart> parts = new ArrayList<>();
            // Identity for the root: its own transform is never composed in, so everything below is
            // expressed relative to it. See the note above for why.
            collectTexturedParts(scene, scene.mRootNode(), new Matrix4f(), parts);
            return parts;
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * Every node's transform by name, as {@link #loadTexturedParts} bakes it into the node's parts: relative to the
     * root, whose own transform is left out. Where a node stands, and which way it is turned, before any clip moves
     * it — the hinge a door's clip swings it about.
     *
     * <p>Imported with no post-processing flags: none of them changes the nodes.
     */
    public static Map<String, Matrix4f> loadNodeTransforms(String path) {
        AIScene scene = aiImportFile(path, 0);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            Map<String, Matrix4f> transforms = new java.util.LinkedHashMap<>();
            Deque<AINode> nodes = new ArrayDeque<>();
            Deque<Matrix4f> below = new ArrayDeque<>();
            nodes.push(scene.mRootNode());
            below.push(new Matrix4f());
            while (!nodes.isEmpty()) {
                AINode node = nodes.pop();
                Matrix4f here = below.pop();
                transforms.putIfAbsent(node.mName().dataString(), here);
                for (int i = 0; i < node.mNumChildren(); i++) {
                    AINode child = AINode.create(node.mChildren().get(i));
                    nodes.push(child);
                    below.push(here.mul(toJOML(child.mTransformation(), new Matrix4f()), new Matrix4f()));
                }
            }
            return transforms;
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * Reads a model authored as <b>prototypes plus placements</b> — see {@link InstancedModel} for what
     * that is and why the other three loaders cannot read one.
     *
     * <p>The prototype geometry is kept in its own local space and is <b>not</b> baked, which is the whole
     * point: baking is what turns twenty thousand placements into twenty thousand copies of a mesh. The
     * caller merges whichever subset it actually wants.
     *
     * <p>The root's own transform <b>is</b> composed in here, unlike {@link #loadTexturedParts}. There the
     * root transform is a stale authoring pose that an animation replaces; here nothing animates, and the
     * root of a scattered model routinely carries the offset that puts the whole thing where it was built
     * (measured on {@code tree.glb}: a pure translation of (9.58, 0, 0.87), so dropping it would leave every
     * instance nine units off the origin the caller expects to centre on).
     *
     * <p>{@code aiProcess_PreTransformVertices} would flatten the hierarchy and is exactly what must not
     * happen: it is what {@link #loadStaticGeometry} uses, and on this asset it produces the 348,000-triangle
     * merge this loader exists to avoid.
     */
    public static InstancedModel loadInstancedModel(String path) {
        int flags = aiProcess_Triangulate | aiProcess_GenSmoothNormals | aiProcess_JoinIdenticalVertices;
        AIScene scene = aiImportFile(path, flags);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            List<InstancedModel.Prototype> prototypes = new ArrayList<>();
            for (int i = 0; i < scene.mNumMeshes(); i++) {
                prototypes.add(buildPrototype(scene, AIMesh.create(scene.mMeshes().get(i))));
            }
            List<InstancedModel.Instance> instances = new ArrayList<>();
            AINode root = scene.mRootNode();
            collectInstances(root, toJOML(root.mTransformation(), new Matrix4f()), instances);
            return new InstancedModel(List.copyOf(prototypes), List.copyOf(instances));
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * Walks the node tree recording placements. Iterative rather than recursive: a scattered model is a
     * root with tens of thousands of direct children, which is fine either way, but nothing guarantees an
     * exporter keeps it flat and a deep chain of that many nodes would overflow the stack.
     */
    private static void collectInstances(AINode root, Matrix4f rootTransform,
                                         List<InstancedModel.Instance> out) {
        Deque<AINode> nodes = new ArrayDeque<>();
        Deque<Matrix4f> transforms = new ArrayDeque<>();
        nodes.push(root);
        transforms.push(rootTransform);
        while (!nodes.isEmpty()) {
            AINode node = nodes.pop();
            Matrix4f here = transforms.pop();
            int meshCount = node.mNumMeshes();
            if (meshCount > 0) {
                IntBuffer meshIndices = node.mMeshes();
                for (int i = 0; i < meshCount; i++) {
                    out.add(new InstancedModel.Instance(meshIndices.get(i), new Matrix4f(here)));
                }
            }
            for (int i = 0; i < node.mNumChildren(); i++) {
                AINode child = AINode.create(node.mChildren().get(i));
                nodes.push(child);
                transforms.push(here.mul(toJOML(child.mTransformation(), new Matrix4f()), new Matrix4f()));
            }
        }
    }

    /** One prototype: its geometry in its own local space, plus the material it is drawn with. */
    private static InstancedModel.Prototype buildPrototype(AIScene scene, AIMesh mesh) {
        int vertexCount = mesh.mNumVertices();
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];

        AIVector3D.Buffer aiPositions = mesh.mVertices();
        AIVector3D.Buffer aiNormals = mesh.mNormals();
        AIVector3D.Buffer aiUvs = mesh.mTextureCoords(0);
        for (int v = 0; v < vertexCount; v++) {
            AIVector3D p = aiPositions.get(v);
            positions[v * 3] = p.x();
            positions[v * 3 + 1] = p.y();
            positions[v * 3 + 2] = p.z();
            if (aiNormals != null) {
                AIVector3D n = aiNormals.get(v);
                normals[v * 3] = n.x();
                normals[v * 3 + 1] = n.y();
                normals[v * 3 + 2] = n.z();
            } else {
                normals[v * 3 + 1] = 1f;
            }
            if (aiUvs != null) {
                AIVector3D t = aiUvs.get(v);
                uvs[v * 2] = t.x();
                uvs[v * 2 + 1] = t.y();
            }
        }

        int[] indices = new int[mesh.mNumFaces() * 3];
        AIFace.Buffer faces = mesh.mFaces();
        int written = 0;
        for (int f = 0; f < mesh.mNumFaces(); f++) {
            IntBuffer face = faces.get(f).mIndices();
            if (face.remaining() != 3) {
                continue;   // triangulation guarantees 3, but a degenerate face is not worth crashing on
            }
            indices[written++] = face.get(0);
            indices[written++] = face.get(1);
            indices[written++] = face.get(2);
        }
        if (written != indices.length) {
            indices = java.util.Arrays.copyOf(indices, written);
        }

        AIMaterial material = AIMaterial.create(scene.mMaterials().get(mesh.mMaterialIndex()));
        return new InstancedModel.Prototype(materialName(material), positions, normals, uvs, indices,
                readBaseColor(material), readEncodedTexture(scene, material));
    }

    private static String materialName(AIMaterial material) {
        AIString name = AIString.calloc();
        try {
            return aiGetMaterialString(material, AI_MATKEY_NAME, aiTextureType_NONE, 0, name)
                    == aiReturn_SUCCESS ? name.dataString() : "";
        } finally {
            name.free();
        }
    }

    /**
     * Walks the node tree, baking transforms into the meshes hanging off each node.
     *
     * @param nodeTransform the transform for <b>this</b> node's own meshes, already resolved by the
     *                      caller. Composed here for the children rather than for the node itself, which
     *                      is what lets the root be entered with an identity and its own transform
     *                      dropped — an "is this the root" test inside the recursion cannot do that job,
     *                      because {@code AIScene.mRootNode()} hands back a fresh wrapper object every
     *                      call and comparing wrappers by identity silently answers no. (It did exactly
     *                      that, and the slime imported as the flattened bind pose until a test caught it.)
     */
    private static void collectTexturedParts(AIScene scene, AINode node, Matrix4f nodeTransform,
                                             List<TexturedPart> parts) {
        int meshCount = node.mNumMeshes();
        if (meshCount > 0) {
            IntBuffer meshIndices = node.mMeshes();
            for (int i = 0; i < meshCount; i++) {
                AIMesh mesh = AIMesh.create(scene.mMeshes().get(meshIndices.get(i)));
                parts.add(buildTexturedPart(scene, mesh, nodeTransform, node.mName().dataString()));
            }
        }

        int childCount = node.mNumChildren();
        for (int i = 0; i < childCount; i++) {
            AINode child = AINode.create(node.mChildren().get(i));
            Matrix4f childTransform = nodeTransform.mul(
                    toJOML(child.mTransformation(), new Matrix4f()), new Matrix4f());
            collectTexturedParts(scene, child, childTransform, parts);
        }
    }

    private static TexturedPart buildTexturedPart(AIScene scene, AIMesh mesh, Matrix4f transform, String node) {
        int vertexCount = mesh.mNumVertices();
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];

        // Normals transform by the inverse-transpose, not by the matrix: a non-uniform scale (which this
        // asset has) skews a direction the other way from a position, and using the same matrix for both
        // tilts every normal and lights the model wrongly.
        Matrix3f normalMatrix = transform.normal(new Matrix3f());

        AIVector3D.Buffer aiPositions = mesh.mVertices();
        AIVector3D.Buffer aiNormals = mesh.mNormals();
        AIVector3D.Buffer aiUvs = mesh.mTextureCoords(0);

        Vector3f scratch = new Vector3f();
        for (int v = 0; v < vertexCount; v++) {
            AIVector3D p = aiPositions.get(v);
            scratch.set(p.x(), p.y(), p.z());
            transform.transformPosition(scratch);
            positions[v * 3] = scratch.x;
            positions[v * 3 + 1] = scratch.y;
            positions[v * 3 + 2] = scratch.z;

            if (aiNormals != null) {
                AIVector3D n = aiNormals.get(v);
                scratch.set(n.x(), n.y(), n.z());
                normalMatrix.transform(scratch);
                if (scratch.lengthSquared() > 1e-12f) {
                    scratch.normalize();
                } else {
                    scratch.set(0f, 1f, 0f);
                }
            } else {
                scratch.set(0f, 1f, 0f);
            }
            normals[v * 3] = scratch.x;
            normals[v * 3 + 1] = scratch.y;
            normals[v * 3 + 2] = scratch.z;

            if (aiUvs != null) {
                AIVector3D uv = aiUvs.get(v);
                uvs[v * 2] = uv.x();
                uvs[v * 2 + 1] = uv.y();
            }
        }

        List<Integer> indexList = new ArrayList<>(mesh.mNumFaces() * 3);
        AIFace.Buffer faces = mesh.mFaces();
        for (int f = 0; f < mesh.mNumFaces(); f++) {
            IntBuffer faceIndices = faces.get(f).mIndices();
            for (int k = 0; k < faces.get(f).mNumIndices(); k++) {
                indexList.add(faceIndices.get(k));
            }
        }
        int[] indices = new int[indexList.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = indexList.get(i);
        }

        AIMaterial aiMaterial = AIMaterial.create(scene.mMaterials().get(mesh.mMaterialIndex()));
        return new TexturedPart(mesh.mName().dataString(), positions, normals, uvs, indices,
                readBaseColor(aiMaterial), readEncodedTexture(scene, aiMaterial), readTwoSided(aiMaterial), node);
    }

    /** Whether the material is made to be seen from both sides: glTF's {@code doubleSided}. */
    private static boolean readTwoSided(AIMaterial aiMaterial) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.mallocInt(1);
            IntBuffer max = stack.ints(1);
            return aiGetMaterialIntegerArray(aiMaterial, AI_MATKEY_TWOSIDED, aiTextureType_NONE, 0, value, max)
                    == aiReturn_SUCCESS && value.get(0) != 0;
        }
    }

    /**
     * The material's base-colour image as encoded bytes, copied out of the scene.
     *
     * <p>Copied, not referenced: the {@code AIScene} is released as soon as the import returns, and a
     * buffer pointing into freed native memory decodes to garbage or crashes the JVM — much later, and
     * nowhere near this method.
     */
    private static ByteBuffer readEncodedTexture(AIScene scene, AIMaterial aiMaterial) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AIString path = AIString.calloc(stack);
            int type = aiGetMaterialTextureCount(aiMaterial, aiTextureType_BASE_COLOR) > 0
                    ? aiTextureType_BASE_COLOR : aiTextureType_DIFFUSE;
            if (aiGetMaterialTextureCount(aiMaterial, type) <= 0) {
                return null;
            }
            int result = aiGetMaterialTexture(aiMaterial, type, 0, path,
                    (IntBuffer) null, (IntBuffer) null, (java.nio.FloatBuffer) null,
                    (IntBuffer) null, (IntBuffer) null, (IntBuffer) null);
            if (result != aiReturn_SUCCESS) {
                return null;
            }

            String texPath = path.dataString();
            if (!texPath.startsWith("*")) {
                return null;   // an external file; nothing here needs one yet
            }
            AITexture aiTexture = AITexture.create(scene.mTextures().get(Integer.parseInt(texPath.substring(1))));
            if (aiTexture.mHeight() != 0) {
                return null;   // raw uncompressed texels, not an encoded image
            }
            ByteBuffer source = aiTexture.pcDataCompressed();
            ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining()).order(ByteOrder.nativeOrder());
            copy.put(source.duplicate()).flip();
            return copy;
        }
    }

    /**
     * How a character is imported — and therefore how anything bound to one must be, since a garment's
     * vertices are matched against the body's and two different sets of flags are two different meshes.
     */
    private static final int CHARACTER_IMPORT_FLAGS =
            aiProcess_Triangulate | aiProcess_CalcTangentSpace | aiProcess_GenSmoothNormals
                    | aiProcess_JoinIdenticalVertices | aiProcess_LimitBoneWeights
                    | aiProcess_PopulateArmatureData;

    public static Model loadModel(String path) {
        AIScene scene = aiImportFile(path, CHARACTER_IMPORT_FLAGS);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }

        try {
            String baseDir = new File(path).getParent();
            return buildModel(scene, baseDir);
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static Model buildModel(AIScene scene, String baseDir) {
        Map<Long, SkeletonNode> nodeByAddress = new HashMap<>();
        SkeletonNode rootNode = buildNodeTree(scene.mRootNode(), null, nodeByAddress);
        Matrix4f globalInverseTransform = new Matrix4f(rootNode.localBindTransform).invert();

        List<Material> materials = buildMaterials(scene, baseDir);

        Map<String, Integer> boneNameToIndex = new HashMap<>();
        List<Matrix4f> boneOffsetMatrices = new ArrayList<>();

        List<Mesh> meshes = new ArrayList<>();
        int meshCount = scene.mNumMeshes();
        PointerBuffer meshesPtr = scene.mMeshes();
        for (int i = 0; i < meshCount; i++) {
            AIMesh aiMesh = AIMesh.create(meshesPtr.get(i));
            Mesh mesh = buildMesh(aiMesh, nodeByAddress, boneNameToIndex, boneOffsetMatrices);
            mesh.setMaterialIndex(aiMesh.mMaterialIndex());
            meshes.add(mesh);
        }

        List<AnimationClip> animationClips = buildAnimationClips(scene);

        Vector3f boundsMin = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f boundsMax = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (int i = 0; i < meshCount; i++) {
            AIMesh aiMesh = AIMesh.create(meshesPtr.get(i));
            AIVector3D.Buffer vertices = aiMesh.mVertices();
            for (int v = 0; v < aiMesh.mNumVertices(); v++) {
                AIVector3D position = vertices.get(v);
                boundsMin.min(new Vector3f(position.x(), position.y(), position.z()));
                boundsMax.max(new Vector3f(position.x(), position.y(), position.z()));
            }
        }
        if (meshCount == 0) {
            boundsMin.set(0f);
            boundsMax.set(0f);
        }

        float facingYaw = measureFacingYaw(scene, boundsMin, boundsMax);

        return new Model(meshes, materials, rootNode, boneNameToIndex, boneOffsetMatrices,
                globalInverseTransform, animationClips, boundsMin, boundsMax, facingYaw);
    }


    /** The share of an upright figure's height taken as "the feet" when measuring which way it faces. */
    private static final float SOLE_BAND = 0.06f;

    /**
     * How far to turn an upright model about Y to bring its front round to +Z, measured from geometry.
     *
     * <p>Needed because a model's rest facing is a property of how it was authored and nothing in the
     * file states it. The two characters here disagree: the player model faces +Z, while the base mesh
     * faces +X and so is drawn in profile by a camera that assumes otherwise, which is exactly what a
     * creation screen must not do.
     *
     * <p>Two measurements, in this order:
     *
     * <ol>
     *   <li><b>The axis</b> is whichever horizontal span is narrower, because a standing figure is wider
     *       across the shoulders than it is front to back. The margin is large and not a judgement call:
     *       measured, 0.93 against 0.33 on the base mesh and 1.57 against 0.35 on the player.</li>
     *   <li><b>The direction</b> is where the mass of the <b>soles</b> sits along that axis, because a
     *       foot reaches further forward than it does back. Taken over the lowest {@value #SOLE_BAND} of
     *       the height, it offsets +0.096 on the base mesh and +0.040 on the player, and on both it
     *       points at the face the model is already known to have.</li>
     * </ol>
     *
     * <p>The second is a cue rather than a fact, so it can be wrong on a model built differently — a
     * figure sitting, or one with no feet. It is therefore only ever used as a <b>starting</b> yaw that a
     * player can drag away from, never as something depended on: the cost of getting it backwards is one
     * drag, where getting the axis wrong is a screen that shows a character's profile and cannot be
     * fixed at all by a viewer who does not know it is meant to face them. A model with no geometry, or
     * one whose horizontal spans tie, answers 0 — which is the "already faces +Z" case.
     */
    private static float measureFacingYaw(AIScene scene, Vector3f boundsMin, Vector3f boundsMax) {
        float spanX = boundsMax.x - boundsMin.x;
        float spanZ = boundsMax.z - boundsMin.z;
        float height = boundsMax.y - boundsMin.y;
        if (height <= 0f || spanX == spanZ) {
            return 0f;
        }
        boolean narrowIsX = spanX < spanZ;
        float bandTop = boundsMin.y + SOLE_BAND * height;
        float centre = narrowIsX ? (boundsMin.x + boundsMax.x) * 0.5f : (boundsMin.z + boundsMax.z) * 0.5f;

        double sum = 0;
        long count = 0;
        PointerBuffer meshesPtr = scene.mMeshes();
        for (int i = 0; i < scene.mNumMeshes(); i++) {
            AIMesh aiMesh = AIMesh.create(meshesPtr.get(i));
            AIVector3D.Buffer vertices = aiMesh.mVertices();
            for (int v = 0; v < aiMesh.mNumVertices(); v++) {
                AIVector3D position = vertices.get(v);
                if (position.y() <= bandTop) {
                    sum += narrowIsX ? position.x() : position.z();
                    count++;
                }
            }
        }
        if (count == 0) {
            return 0f;
        }
        float forward = (float) (sum / count) - centre;
        float frontX = narrowIsX ? Math.signum(forward) : 0f;
        float frontZ = narrowIsX ? 0f : Math.signum(forward);
        if (frontX == 0f && frontZ == 0f) {
            return 0f;
        }
        // The yaw that carries (frontX, frontZ) round to +Z. Rotating by t maps (x, z) to
        // (x cos t + z sin t, -x sin t + z cos t), so the turn wanted is -atan2(frontX, frontZ).
        return (float) -Math.atan2(frontX, frontZ);
    }

    // ---- node hierarchy ----

    private static SkeletonNode buildNodeTree(AINode aiNode, SkeletonNode parent, Map<Long, SkeletonNode> nodeByAddress) {
        Matrix4f localTransform = toJOML(aiNode.mTransformation(), new Matrix4f());
        SkeletonNode node = new SkeletonNode(aiNode.mName().dataString(), localTransform, parent);
        nodeByAddress.put(aiNode.address(), node);
        if (parent != null) {
            parent.children.add(node);
        }

        int childCount = aiNode.mNumChildren();
        if (childCount > 0) {
            PointerBuffer children = aiNode.mChildren();
            for (int i = 0; i < childCount; i++) {
                AINode childNode = AINode.create(children.get(i));
                buildNodeTree(childNode, node, nodeByAddress);
            }
        }
        return node;
    }

    // ---- matrix conversion (row-major AIMatrix4x4 -> column-major JOML Matrix4f, no transpose) ----

    private static Matrix4f toJOML(AIMatrix4x4 m, Matrix4f dst) {
        return dst.set(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }

    // ---- meshes / bones ----

    /**
     * The bone name reserved for geometry no bone claims. Deliberately not a legal node name, so it can
     * never collide with a real bone the file declares.
     */
    private static final String STATIC_BONE = "<static>";

    /**
     * The index of a bone that is, and stays, the identity -- allocated on first use.
     *
     * <p>It is entered in the offset-matrix table like any other bone but is never attached to a
     * {@link SkeletonNode}, and {@code Animator} only writes matrices for nodes that carry a bone index.
     * So its slot keeps the identity every entry is initialised to, for the whole life of the model,
     * whatever clip is playing. A vertex bound to it is therefore transformed by the model matrix alone,
     * which is exactly what "this vertex is not skinned" means.
     *
     * <p>Allocated lazily rather than always, so a fully-rigged character does not carry a spare bone --
     * {@code Model.getBoneCount()} sizes the animator's array and the UBO upload from this list.
     */
    /**
     * Whether a joint actually moves any vertex, as opposed to merely being listed as one.
     *
     * <p><b>Not</b> {@code mNumWeights() == 0}, which is the obvious test and finds nothing: glTF stores
     * four influences per vertex whether or not they are used, so a joint no vertex is painted to still
     * arrives with a full set of weight entries, every one of them <b>zero</b>. Measured on the Rigify
     * character, 3,159 of its 3,280 per-mesh joint records are entirely zero-weighted, and reading only
     * the count reports all 410 joints as bones.
     */
    private static boolean deformsAnything(AIBone bone) {
        AIVertexWeight.Buffer weights = bone.mWeights();
        int count = bone.mNumWeights();
        for (int i = 0; i < count; i++) {
            if (weights.get(i).mWeight() > 0f) {
                return true;
            }
        }
        return false;
    }

    private static int staticBoneIndex(Map<String, Integer> boneNameToIndex,
                                       List<Matrix4f> boneOffsetMatrices) {
        Integer existing = boneNameToIndex.get(STATIC_BONE);
        if (existing != null) {
            return existing;
        }
        int index = boneOffsetMatrices.size();
        boneOffsetMatrices.add(new Matrix4f());
        boneNameToIndex.put(STATIC_BONE, index);
        return index;
    }

    /**
     * Whether a file is a plain unrigged mesh — a garment — rather than a whole scene with an armature.
     *
     * <p>Cheap on purpose: imported with no post-processing, since the question is about what the file
     * declares and not about its geometry. That matters because the case it exists to reject is a
     * <b>21 MB</b> scene export, and importing one properly to find out costs seconds.
     */
    public static boolean isPlainMesh(String path) {
        AIScene scene = aiImportFile(path, 0);
        if (scene == null) {
            return false;
        }
        try {
            PointerBuffer meshes = scene.mMeshes();
            for (int m = 0; m < scene.mNumMeshes(); m++) {
                if (AIMesh.create(meshes.get(m)).mNumBones() > 0) {
                    return false;
                }
            }
            return scene.mNumMeshes() > 0;
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * The body's skinning as flat arrays, for binding a garment to it — see {@link SkinBinding}.
     *
     * <p>Bone <b>names</b> are what it maps through, not the order this import happens to produce, so the
     * indices it hands back are the ones the already-loaded character is posed with. Reading the order
     * instead would work every time the two imports agreed and be silently wrong the once they did not,
     * and what that looks like is a shirt whose sleeve follows the character's leg.
     *
     * <p>A second import of a file already loaded, which is the price of {@link Model} keeping no CPU
     * copy of its vertices — and the right price, since the alternative is every model in the game
     * holding its geometry twice for the sake of the few that are ever dressed.
     */
    public static SkinBinding.Source skinSourceOf(String path, Map<String, Integer> boneNameToIndex) {
        AIScene scene = aiImportFile(path, CHARACTER_IMPORT_FLAGS);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            List<Float> positions = new ArrayList<>();
            List<Float> normals = new ArrayList<>();
            List<int[]> indices = new ArrayList<>();
            List<float[]> weights = new ArrayList<>();
            PointerBuffer meshes = scene.mMeshes();
            for (int m = 0; m < scene.mNumMeshes(); m++) {
                AIMesh aiMesh = AIMesh.create(meshes.get(m));
                int count = aiMesh.mNumVertices();
                int base = indices.size();
                AIVector3D.Buffer verts = aiMesh.mVertices();
                AIVector3D.Buffer norms = aiMesh.mNormals();
                for (int v = 0; v < count; v++) {
                    AIVector3D p = verts.get(v);
                    positions.add(p.x());
                    positions.add(p.y());
                    positions.add(p.z());
                    AIVector3D n = norms == null ? null : norms.get(v);
                    normals.add(n == null ? 0f : n.x());
                    normals.add(n == null ? 0f : n.y());
                    normals.add(n == null ? 0f : n.z());
                    indices.add(new int[SkinBinding.INFLUENCES]);
                    weights.add(new float[SkinBinding.INFLUENCES]);
                }
                PointerBuffer bonesPtr = aiMesh.mBones();
                for (int b = 0; b < aiMesh.mNumBones(); b++) {
                    AIBone aiBone = AIBone.create(bonesPtr.get(b));
                    Integer bone = boneNameToIndex.get(aiBone.mName().dataString());
                    if (bone == null || !deformsAnything(aiBone)) {
                        continue;
                    }
                    AIVertexWeight.Buffer boneWeights = aiBone.mWeights();
                    for (int w = 0; w < aiBone.mNumWeights(); w++) {
                        AIVertexWeight weight = boneWeights.get(w);
                        if (weight.mWeight() <= 0f) {
                            continue;
                        }
                        int v = base + weight.mVertexId();
                        float[] slot = weights.get(v);
                        for (int k = 0; k < SkinBinding.INFLUENCES; k++) {
                            if (slot[k] == 0f) {
                                indices.get(v)[k] = bone;
                                slot[k] = weight.mWeight();
                                break;
                            }
                        }
                    }
                }
            }
            float[] flatPositions = new float[positions.size()];
            float[] flatNormals = new float[normals.size()];
            for (int i = 0; i < flatPositions.length; i++) {
                flatPositions[i] = positions.get(i);
                flatNormals[i] = normals.get(i);
            }
            return new SkinBinding.Source(flatPositions, flatNormals, indices.toArray(new int[0][]),
                    weights.toArray(new float[0][]));
        } finally {
            aiReleaseImport(scene);
        }
    }

    /**
     * A garment — clothing, hair, anything worn — as a {@link Model} posed by the character's own
     * skeleton.
     *
     * <p>It shares the character's bone table, offset matrices and skeleton outright, so the pose already
     * in the bone UBO is the pose it is drawn with: the renderer needs no second upload and cannot get
     * the two out of step. What makes that legitimate is that the garment's vertices are in the same
     * space as the body's, which is the same thing that makes {@link SkinBinding} able to find the body
     * underneath at all.
     *
     * <p>The node transform <b>is</b> composed into the vertices here, unlike the character's own root —
     * see the rule this file already records: a root transform on something animated is a stale pose, on
     * something static it is the shape. A garment file is static, and the shipped shorts carry their
     * placement there.
     */
    /**
     * How far outside the skin a buried garment vertex is put, as a fraction of the character's height.
     *
     * <p>A fraction rather than a distance so it means the same thing on a character of any size — the
     * two models here already differ by half again. Small on purpose: this is the thickness of cloth,
     * not a lift, and everything already outside the body is left exactly where the artist put it. See
     * {@link SkinBinding#bind}, where the difference between the two is what a hairstyle turns on.
     */
    private static final float GARMENT_CLEARANCE = 0.002f;

    public static Model loadGarment(String path, SkinBinding.Source body, Model character) {
        AIScene scene = aiImportFile(path, CHARACTER_IMPORT_FLAGS);
        if (scene == null) {
            throw new RuntimeException("Assimp import failed for '" + path + "': " + aiGetErrorString());
        }
        try {
            List<Material> materials = buildMaterials(scene, new File(path).getParent());
            List<Mesh> meshes = new ArrayList<>();
            Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
            Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
            PointerBuffer aiMeshes = scene.mMeshes();
            for (int m = 0; m < scene.mNumMeshes(); m++) {
                AIMesh aiMesh = AIMesh.create(aiMeshes.get(m));
                Matrix4f placement = nodeTransformFor(scene, scene.mRootNode(), m, new Matrix4f());
                meshes.add(buildGarmentMesh(aiMesh, placement, body,
                        character.getHeight() * GARMENT_CLEARANCE, min, max));
            }
            return new Model(meshes, materials, character.getRootNode(),
                    character.getBoneNameToIndex(), character.getBoneOffsetMatrices(),
                    character.getGlobalInverseTransform(), List.of(), min, max,
                    character.getFacingYaw());
        } finally {
            aiReleaseImport(scene);
        }
    }

    private static Mesh buildGarmentMesh(AIMesh aiMesh, Matrix4f placement, SkinBinding.Source body,
                                         float clearance, Vector3f min, Vector3f max) {
        int vertexCount = aiMesh.mNumVertices();
        AIVector3D.Buffer positions = aiMesh.mVertices();
        AIVector3D.Buffer normals = aiMesh.mNormals();
        AIVector3D.Buffer tangents = aiMesh.mTangents();
        AIVector3D.Buffer uvs = aiMesh.mTextureCoords(0);

        // Directions go through the inverse transpose, or a placement that scales unevenly tilts every
        // normal it touches — the same rule TexturedMeshBuilder.addMesh records. Worked out before the
        // positions because the lift is along the placed normal, not the authored one.
        Matrix3f directions = placement == null ? new Matrix3f()
                : new Matrix3f(placement).invert().transpose();

        float[] placed = new float[vertexCount * 3];
        Vector3f scratch = new Vector3f();
        for (int v = 0; v < vertexCount; v++) {
            AIVector3D p = positions.get(v);
            scratch.set(p.x(), p.y(), p.z());
            if (placement != null) {
                placement.transformPosition(scratch);
            }
            placed[v * 3] = scratch.x;
            placed[v * 3 + 1] = scratch.y;
            placed[v * 3 + 2] = scratch.z;
        }

        int[][] boneIndices = new int[vertexCount][SkinBinding.INFLUENCES];
        float[][] boneWeights = new float[vertexCount][SkinBinding.INFLUENCES];
        // Binds and surfaces in one pass, so the bounds below are of what is actually drawn.
        SkinBinding.bind(body, placed, clearance, boneIndices, boneWeights);
        for (int v = 0; v < vertexCount; v++) {
            min.min(scratch.set(placed[v * 3], placed[v * 3 + 1], placed[v * 3 + 2]));
            max.max(scratch);
        }

        ByteBuffer vertexData = ByteBuffer.allocateDirect(vertexCount * Mesh.STRIDE)
                .order(ByteOrder.nativeOrder());
        for (int v = 0; v < vertexCount; v++) {
            vertexData.putFloat(placed[v * 3]).putFloat(placed[v * 3 + 1]).putFloat(placed[v * 3 + 2]);
            putDirection(vertexData, normals, directions, scratch, v);
            if (uvs != null) {
                AIVector3D uv = uvs.get(v);
                vertexData.putFloat(uv.x()).putFloat(uv.y());
            } else {
                vertexData.putFloat(0f).putFloat(0f);
            }
            putDirection(vertexData, tangents, directions, scratch, v);
            for (int k = 0; k < SkinBinding.INFLUENCES; k++) {
                vertexData.putInt(boneIndices[v][k]);
            }
            for (int k = 0; k < SkinBinding.INFLUENCES; k++) {
                vertexData.putFloat(boneWeights[v][k]);
            }
        }
        vertexData.flip();

        int faceCount = aiMesh.mNumFaces();
        AIFace.Buffer faces = aiMesh.mFaces();
        IntBuffer indexData = ByteBuffer.allocateDirect(faceCount * 3 * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int f = 0; f < faceCount; f++) {
            IntBuffer face = faces.get(f).mIndices();
            for (int i = 0; i < face.remaining(); i++) {
                indexData.put(face.get(i));
            }
        }
        indexData.flip();

        Mesh mesh = new Mesh(vertexData, indexData);
        mesh.setMaterialIndex(aiMesh.mMaterialIndex());
        return mesh;
    }

    private static void putDirection(ByteBuffer out, AIVector3D.Buffer source, Matrix3f directions,
                                     Vector3f scratch, int vertex) {
        if (source == null) {
            out.putFloat(0f).putFloat(0f).putFloat(0f);
            return;
        }
        AIVector3D d = source.get(vertex);
        scratch.set(d.x(), d.y(), d.z());
        directions.transform(scratch);
        if (scratch.lengthSquared() > 0f) {
            scratch.normalize();
        }
        out.putFloat(scratch.x).putFloat(scratch.y).putFloat(scratch.z);
    }

    private static Mesh buildMesh(AIMesh aiMesh, Map<Long, SkeletonNode> nodeByAddress,
                                   Map<String, Integer> boneNameToIndex, List<Matrix4f> boneOffsetMatrices) {
        int vertexCount = aiMesh.mNumVertices();

        AIVector3D.Buffer positions = aiMesh.mVertices();
        AIVector3D.Buffer normals = aiMesh.mNormals();
        AIVector3D.Buffer tangents = aiMesh.mTangents();
        AIVector3D.Buffer uvs = aiMesh.mTextureCoords(0);

        int[][] boneIndices = new int[vertexCount][4];
        float[][] boneWeights = new float[vertexCount][4];
        int[] influenceCount = new int[vertexCount];

        int boneCount = aiMesh.mNumBones();
        if (boneCount > 0) {
            PointerBuffer bonesPtr = aiMesh.mBones();
            for (int b = 0; b < boneCount; b++) {
                AIBone aiBone = AIBone.create(bonesPtr.get(b));
                // A joint that weights nothing is not a bone as far as anything downstream is
                // concerned, and counting it is not merely wasteful — the pose travels to the shader
                // through a uniform block whose guaranteed size is 256 mat4s, so a rig that offers
                // enough of them overflows it and takes the session down. A Rigify export offers a
                // great many: measured on Char_M.glb, 410 joints of which exactly 73 carry weight, the
                // other 337 being its IK targets and its MCH/ORG plumbing. They stay in the hierarchy
                // as ordinary nodes — which is what the 73 hang off, so their transforms still reach
                // the pose — they simply do not occupy a bone slot.
                //
                // Weighting nothing *on this mesh* is the right test even for a model of several
                // meshes: the tables are shared across them, so a joint another mesh weights is
                // registered when that mesh's turn comes.
                if (!deformsAnything(aiBone)) {
                    continue;
                }
                String boneName = aiBone.mName().dataString();

                Integer existingIndex = boneNameToIndex.get(boneName);
                int boneIndex;
                if (existingIndex != null) {
                    boneIndex = existingIndex;
                } else {
                    boneIndex = boneOffsetMatrices.size();
                    boneOffsetMatrices.add(toJOML(aiBone.mOffsetMatrix(), new Matrix4f()));
                    boneNameToIndex.put(boneName, boneIndex);
                }

                AINode boneNode = aiBone.mNode();
                if (boneNode != null) {
                    SkeletonNode skeletonNode = nodeByAddress.get(boneNode.address());
                    if (skeletonNode != null) {
                        skeletonNode.boneIndex = boneIndex;
                    }
                }

                AIVertexWeight.Buffer weights = aiBone.mWeights();
                int weightCount = aiBone.mNumWeights();
                for (int w = 0; w < weightCount; w++) {
                    AIVertexWeight weight = weights.get(w);
                    int vertexId = weight.mVertexId();
                    int slot = influenceCount[vertexId];
                    if (slot < 4) {
                        boneIndices[vertexId][slot] = boneIndex;
                        boneWeights[vertexId][slot] = weight.mWeight();
                        influenceCount[vertexId]++;
                    }
                }
            }
        }

        // aiProcess_LimitBoneWeights truncates to 4 influences but does not renormalize
        for (int v = 0; v < vertexCount; v++) {
            float sum = boneWeights[v][0] + boneWeights[v][1] + boneWeights[v][2] + boneWeights[v][3];
            if (sum > 0.0f) {
                for (int k = 0; k < 4; k++) {
                    boneWeights[v][k] /= sum;
                }
            } else {
                // No bone claims this vertex, so it is drawn where it was authored -- see
                // staticBoneIndex. Without this the skinning shader sums four zero-weighted matrices
                // into a zero matrix and the vertex collapses to the origin, taking the whole mesh
                // with it: an unrigged model renders as nothing at all rather than as itself.
                boneIndices[v][0] = staticBoneIndex(boneNameToIndex, boneOffsetMatrices);
                boneWeights[v][0] = 1.0f;
            }
        }

        ByteBuffer vertexData = ByteBuffer.allocateDirect(vertexCount * Mesh.STRIDE).order(ByteOrder.nativeOrder());
        for (int v = 0; v < vertexCount; v++) {
            AIVector3D pos = positions.get(v);
            vertexData.putFloat(pos.x()).putFloat(pos.y()).putFloat(pos.z());

            if (normals != null) {
                AIVector3D n = normals.get(v);
                vertexData.putFloat(n.x()).putFloat(n.y()).putFloat(n.z());
            } else {
                vertexData.putFloat(0f).putFloat(0f).putFloat(0f);
            }

            if (uvs != null) {
                AIVector3D uv = uvs.get(v);
                vertexData.putFloat(uv.x()).putFloat(uv.y());
            } else {
                vertexData.putFloat(0f).putFloat(0f);
            }

            if (tangents != null) {
                AIVector3D t = tangents.get(v);
                vertexData.putFloat(t.x()).putFloat(t.y()).putFloat(t.z());
            } else {
                vertexData.putFloat(0f).putFloat(0f).putFloat(0f);
            }

            for (int k = 0; k < 4; k++) {
                vertexData.putInt(boneIndices[v][k]);
            }
            for (int k = 0; k < 4; k++) {
                vertexData.putFloat(boneWeights[v][k]);
            }
        }
        vertexData.flip();

        int faceCount = aiMesh.mNumFaces();
        AIFace.Buffer faces = aiMesh.mFaces();
        IntBuffer indexData = ByteBuffer.allocateDirect(faceCount * 3 * Integer.BYTES)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int f = 0; f < faceCount; f++) {
            AIFace face = faces.get(f);
            IntBuffer faceIndices = face.mIndices();
            for (int k = 0; k < face.mNumIndices(); k++) {
                indexData.put(faceIndices.get(k));
            }
        }
        indexData.flip();

        Mesh built = new Mesh(vertexData, indexData);
        List<MorphTarget> targets = readMorphTargets(aiMesh, positions, normals, vertexCount);
        if (!targets.isEmpty()) {
            built.setMorphTargets(targets, vertexData);
        }
        return built;
    }

    /**
     * The mesh's shape keys, as sparse offsets from the base pose.
     *
     * <p>Assimp exposes them as <b>anim meshes</b>, and each one is a full copy of the mesh in its
     * deformed state rather than a set of deltas — so the subtraction happens here, once, at load. That
     * is also the only place it can happen cheaply: the base pose is right there in the buffers this
     * method is handed, and every later consumer wants deltas.
     *
     * <p>Note it reads the vertex arrays this same build pass used, <i>after</i> assimp's
     * {@code JoinIdenticalVertices} has run. Assimp keeps anim meshes indexed in step with the base
     * mesh through that pass, so the two agree; taking the base pose from a second, un-processed read
     * would not.
     */
    private static List<MorphTarget> readMorphTargets(AIMesh aiMesh, AIVector3D.Buffer basePositions,
                                                      AIVector3D.Buffer baseNormals, int vertexCount) {
        int targetCount = aiMesh.mNumAnimMeshes();
        if (targetCount == 0) {
            return List.of();
        }

        List<MorphTarget> targets = new ArrayList<>(targetCount);
        PointerBuffer animMeshes = aiMesh.mAnimMeshes();
        for (int t = 0; t < targetCount; t++) {
            AIAnimMesh anim = AIAnimMesh.create(animMeshes.get(t));
            AIVector3D.Buffer morphedPositions = anim.mVertices();
            AIVector3D.Buffer morphedNormals = anim.mNormals();
            if (morphedPositions == null || anim.mNumVertices() != vertexCount) {
                continue;
            }

            // Two passes: count what moved, then fill. One pass into growable lists would allocate a
            // boxed Float per component for a shape that may touch tens of thousands of vertices.
            int moved = 0;
            for (int v = 0; v < vertexCount; v++) {
                if (movesVertex(basePositions, morphedPositions, v)) {
                    moved++;
                }
            }
            if (moved == 0) {
                // A shape that moves nothing is still a real channel — an exporter emits one for the
                // rest pose — so it is kept rather than dropped, or the names would not line up with
                // what the artist sees and a weight would silently drive the wrong shape.
                targets.add(new MorphTarget(nameOf(anim, t), new int[0], new float[0], new float[0]));
                continue;
            }

            boolean withNormals = morphedNormals != null && baseNormals != null;
            int[] indices = new int[moved];
            float[] positionDeltas = new float[moved * 3];
            float[] normalDeltas = withNormals ? new float[moved * 3] : new float[0];

            int at = 0;
            for (int v = 0; v < vertexCount; v++) {
                if (!movesVertex(basePositions, morphedPositions, v)) {
                    continue;
                }
                AIVector3D base = basePositions.get(v);
                AIVector3D morphed = morphedPositions.get(v);
                indices[at] = v;
                positionDeltas[at * 3] = morphed.x() - base.x();
                positionDeltas[at * 3 + 1] = morphed.y() - base.y();
                positionDeltas[at * 3 + 2] = morphed.z() - base.z();
                if (withNormals) {
                    AIVector3D baseNormal = baseNormals.get(v);
                    AIVector3D morphedNormal = morphedNormals.get(v);
                    normalDeltas[at * 3] = morphedNormal.x() - baseNormal.x();
                    normalDeltas[at * 3 + 1] = morphedNormal.y() - baseNormal.y();
                    normalDeltas[at * 3 + 2] = morphedNormal.z() - baseNormal.z();
                }
                at++;
            }
            targets.add(new MorphTarget(nameOf(anim, t), indices, positionDeltas, normalDeltas));
        }
        return targets;
    }

    private static boolean movesVertex(AIVector3D.Buffer base, AIVector3D.Buffer morphed, int v) {
        AIVector3D a = base.get(v);
        AIVector3D b = morphed.get(v);
        return Math.abs(b.x() - a.x()) > MorphTarget.EPSILON
                || Math.abs(b.y() - a.y()) > MorphTarget.EPSILON
                || Math.abs(b.z() - a.z()) > MorphTarget.EPSILON;
    }

    /**
     * A shape key's name.
     *
     * <p>glTF keeps target names in {@code mesh.extras.targetNames} and assimp copies them onto the anim
     * mesh, but that is an <i>extra</i> — a file may legitimately carry none. An index-based fallback
     * keeps the channel addressable rather than leaving several shapes all called the empty string,
     * which would collapse to one entry the moment they were put in a map.
     */
    private static String nameOf(AIAnimMesh anim, int index) {
        String name = anim.mName().dataString();
        return name == null || name.isBlank() ? "Shape" + index : name;
    }

    // ---- materials / textures ----

    private static List<Material> buildMaterials(AIScene scene, String baseDir) {
        List<Material> materials = new ArrayList<>();
        int materialCount = scene.mNumMaterials();
        if (materialCount == 0) {
            return materials;
        }
        PointerBuffer materialsPtr = scene.mMaterials();
        for (int i = 0; i < materialCount; i++) {
            AIMaterial aiMaterial = AIMaterial.create(materialsPtr.get(i));
            materials.add(buildMaterial(scene, aiMaterial, baseDir));
        }
        return materials;
    }

    private static Material buildMaterial(AIScene scene, AIMaterial aiMaterial, String baseDir) {
        Vector4f baseColor = readBaseColor(aiMaterial);
        Texture texture = readBaseColorTexture(scene, aiMaterial, baseDir);
        if (texture == null) {
            texture = Texture.solidColor(255, 255, 255, 255);
        }
        return new Material(materialName(aiMaterial), baseColor, texture);
    }

    /**
     * How dark a base colour has to be before the emissive one is read instead — see
     * {@link #readBaseColor}.
     */
    private static final float UNLIT_BASE_COLOR = 0.02f;

    /**
     * A material's colour, from its base colour or — where that is black and it emits — from its
     * emission.
     *
     * <h2>Why the second case is not an oddity</h2>
     * A surface authored in Blender with an <b>Emission</b> shader, which is how flat unlit anime
     * materials are usually built, exports as {@code baseColorFactor} <b>(0,0,0,1)</b> with the colour in
     * {@code emissiveFactor}. Read literally that is a black surface, and this renderer has no emissive
     * term to put it back — so every such material rendered as black. Measured on the shipped character,
     * six of its eight do this: hair, lips, eyes, face markings and the hairstyle and shorts that go with
     * it were all coming out black, which reads as the art being drawn that way rather than as a material
     * being misread.
     *
     * <p>Only when the base is <i>black</i>. A material with a real base colour and an emissive on top is
     * a genuinely glowing surface and keeps its own colour; this is a fallback for a surface with no other
     * colour at all, not a preference for emission.
     */
    private static Vector4f readBaseColor(AIMaterial aiMaterial) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AIColor4D color = AIColor4D.calloc(stack);
            int result = aiGetMaterialColor(aiMaterial, AI_MATKEY_BASE_COLOR, aiTextureType_NONE, 0, color);
            if (result != aiReturn_SUCCESS) {
                result = aiGetMaterialColor(aiMaterial, AI_MATKEY_COLOR_DIFFUSE, aiTextureType_NONE, 0, color);
            }
            Vector4f base = result == aiReturn_SUCCESS
                    ? new Vector4f(color.r(), color.g(), color.b(), color.a())
                    : new Vector4f(1f, 1f, 1f, 1f);
            if (Math.max(base.x, Math.max(base.y, base.z)) > UNLIT_BASE_COLOR) {
                return base;
            }
            AIColor4D emissive = AIColor4D.calloc(stack);
            if (aiGetMaterialColor(aiMaterial, AI_MATKEY_COLOR_EMISSIVE, aiTextureType_NONE, 0, emissive)
                    == aiReturn_SUCCESS
                    && Math.max(emissive.r(), Math.max(emissive.g(), emissive.b())) > UNLIT_BASE_COLOR) {
                return new Vector4f(emissive.r(), emissive.g(), emissive.b(), base.w);
            }
            return base;
        }
    }

    private static Texture readBaseColorTexture(AIScene scene, AIMaterial aiMaterial, String baseDir) {
        Texture texture = tryLoadTexture(scene, aiMaterial, aiTextureType_BASE_COLOR, baseDir);
        if (texture == null) {
            texture = tryLoadTexture(scene, aiMaterial, aiTextureType_DIFFUSE, baseDir);
        }
        return texture;
    }

    private static Texture tryLoadTexture(AIScene scene, AIMaterial aiMaterial, int textureType, String baseDir) {
        if (aiGetMaterialTextureCount(aiMaterial, textureType) <= 0) {
            return null;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            AIString path = AIString.calloc(stack);
            int result = aiGetMaterialTexture(aiMaterial, textureType, 0, path,
                    (IntBuffer) null, (IntBuffer) null, (java.nio.FloatBuffer) null,
                    (IntBuffer) null, (IntBuffer) null, (IntBuffer) null);
            if (result != aiReturn_SUCCESS) {
                return null;
            }

            String texPath = path.dataString();
            if (texPath.startsWith("*")) {
                int embeddedIndex = Integer.parseInt(texPath.substring(1));
                AITexture aiTexture = AITexture.create(scene.mTextures().get(embeddedIndex));
                if (aiTexture.mHeight() == 0) {
                    return Texture.fromMemory(aiTexture.pcDataCompressed());
                }
                return null; // raw uncompressed AITexel data — not needed for this asset, skip
            }

            String resolvedPath = (baseDir != null ? baseDir + File.separator : "") + texPath;
            return Texture.fromFile(resolvedPath);
        }
    }

    // ---- animations ----

    private static List<AnimationClip> buildAnimationClips(AIScene scene) {
        List<AnimationClip> clips = new ArrayList<>();
        int animationCount = scene.mNumAnimations();
        if (animationCount == 0) {
            return clips;
        }
        PointerBuffer animationsPtr = scene.mAnimations();
        for (int i = 0; i < animationCount; i++) {
            AIAnimation aiAnimation = AIAnimation.create(animationsPtr.get(i));
            clips.add(buildAnimationClip(aiAnimation));
        }
        return clips;
    }

    private static AnimationClip buildAnimationClip(AIAnimation aiAnimation) {
        String name = aiAnimation.mName().dataString();
        double durationTicks = aiAnimation.mDuration();
        double ticksPerSecond = aiAnimation.mTicksPerSecond() == 0 ? 25.0 : aiAnimation.mTicksPerSecond();

        List<NodeAnimChannel> channels = new ArrayList<>();
        int channelCount = aiAnimation.mNumChannels();
        PointerBuffer channelsPtr = aiAnimation.mChannels();
        for (int c = 0; c < channelCount; c++) {
            AINodeAnim aiChannel = AINodeAnim.create(channelsPtr.get(c));
            channels.add(buildChannel(aiChannel));
        }
        return new AnimationClip(name, durationTicks, ticksPerSecond, channels);
    }

    private static NodeAnimChannel buildChannel(AINodeAnim aiChannel) {
        String nodeName = aiChannel.mNodeName().dataString();

        List<NodeAnimChannel.PositionKey> positionKeys = new ArrayList<>();
        AIVectorKey.Buffer posKeys = aiChannel.mPositionKeys();
        for (int i = 0; i < aiChannel.mNumPositionKeys(); i++) {
            AIVectorKey key = posKeys.get(i);
            AIVector3D v = key.mValue();
            positionKeys.add(new NodeAnimChannel.PositionKey(key.mTime(), new Vector3f(v.x(), v.y(), v.z())));
        }

        List<NodeAnimChannel.RotationKey> rotationKeys = new ArrayList<>();
        AIQuatKey.Buffer rotKeys = aiChannel.mRotationKeys();
        for (int i = 0; i < aiChannel.mNumRotationKeys(); i++) {
            AIQuatKey key = rotKeys.get(i);
            AIQuaternion q = key.mValue();
            rotationKeys.add(new NodeAnimChannel.RotationKey(key.mTime(), new Quaternionf(q.x(), q.y(), q.z(), q.w())));
        }

        List<NodeAnimChannel.ScaleKey> scaleKeys = new ArrayList<>();
        AIVectorKey.Buffer sclKeys = aiChannel.mScalingKeys();
        for (int i = 0; i < aiChannel.mNumScalingKeys(); i++) {
            AIVectorKey key = sclKeys.get(i);
            AIVector3D v = key.mValue();
            scaleKeys.add(new NodeAnimChannel.ScaleKey(key.mTime(), new Vector3f(v.x(), v.y(), v.z())));
        }

        return new NodeAnimChannel(nodeName, positionKeys, rotationKeys, scaleKeys);
    }
}
