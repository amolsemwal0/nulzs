package org.zeronura.engine.asset;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.render.Mesh;

import java.util.List;
import java.util.Map;

public class Model {

    private final List<Mesh> meshes;
    private final List<Material> materials;
    private final SkeletonNode rootNode;
    private final Map<String, Integer> boneNameToIndex;
    private final List<Matrix4f> boneOffsetMatrices;
    private final Matrix4f globalInverseTransform;
    private final List<AnimationClip> animationClips;
    private final Vector3f boundsMin;
    private final Vector3f boundsMax;
    private final float facingYaw;

    public Model(List<Mesh> meshes, List<Material> materials, SkeletonNode rootNode,
                 Map<String, Integer> boneNameToIndex, List<Matrix4f> boneOffsetMatrices,
                 Matrix4f globalInverseTransform, List<AnimationClip> animationClips,
                 Vector3f boundsMin, Vector3f boundsMax, float facingYaw) {
        this.meshes = meshes;
        this.materials = materials;
        this.rootNode = rootNode;
        this.boneNameToIndex = boneNameToIndex;
        this.boneOffsetMatrices = boneOffsetMatrices;
        this.globalInverseTransform = globalInverseTransform;
        this.animationClips = animationClips;
        this.boundsMin = boundsMin;
        this.boundsMax = boundsMax;
        this.facingYaw = facingYaw;
    }

    /**
     * How far to turn this model about Y to bring an upright figure's front round to +Z, in radians.
     *
     * <p>Measured from the geometry at load — see {@code AssetLoader.measureFacingYaw}, which also says
     * why it is a starting point rather than something to depend on. Zero for a model that already faces
     * +Z, which is what the player model measures and what every other consumer here has assumed.
     */
    public float getFacingYaw() {
        return facingYaw;
    }

    /**
     * The model's bind-pose extent, in its own space.
     *
     * <p>Carried because a caller that has to <b>frame</b> a model cannot assume anything about where it
     * sits: the player model stands with its feet on y=0 and reaches 1.78, while the base mesh is centred
     * on its own waist and runs from -1.07 to 0.94. A camera placed from constants tuned against one of
     * them looks at empty air when handed the other, which is exactly how the creation screen's framing
     * would have broken the first time the model changed.
     *
     * <p>Bind pose, not the current pose: it is measured once at load from the geometry as authored, so
     * it does not move as a clip plays or a shape key is dragged. Both would be far more expensive to
     * answer and neither is what "how big is this character" means.
     */
    public Vector3f getBoundsMin() {
        return boundsMin;
    }

    public Vector3f getBoundsMax() {
        return boundsMax;
    }

    /** The model's own height -- the span the framing above is expressed in fractions of. */
    public float getHeight() {
        return boundsMax.y - boundsMin.y;
    }

    /** The material with this exact name, or null. Names are a model's only description of a surface. */
    public Material findMaterial(String name) {
        for (Material material : materials) {
            if (material.getName().equals(name)) {
                return material;
            }
        }
        return null;
    }

    public List<Mesh> getMeshes() {
        return meshes;
    }

    public List<Material> getMaterials() {
        return materials;
    }

    public SkeletonNode getRootNode() {
        return rootNode;
    }

    /**
     * The index of a named bone, or {@link org.zeronura.engine.animation.Socket#UNBOUND} if the skeleton
     * has no such bone.
     *
     * <p>A named lookup rather than a map handed out, because "does this rig have that bone" is the whole
     * question a socket asks and it has a right answer for a model with no skeleton at all — the shipped
     * base mesh has zero bones, so every socket on it is legitimately absent rather than an error.
     */
    public int boneIndexOf(String boneName) {
        Integer index = boneNameToIndex.get(boneName);
        return index == null ? -1 : index;
    }

    public Map<String, Integer> getBoneNameToIndex() {
        return boneNameToIndex;
    }

    public List<Matrix4f> getBoneOffsetMatrices() {
        return boneOffsetMatrices;
    }

    public Matrix4f getGlobalInverseTransform() {
        return globalInverseTransform;
    }

    public List<AnimationClip> getAnimationClips() {
        return animationClips;
    }

    /**
     * Every shape key the model carries, in a stable order, with no duplicates.
     *
     * <p>Shape keys live on <b>meshes</b>, and an exported character splits its face across several —
     * this model's face is seven primitives all carrying the same 57 targets. What a caller wants is
     * the character's list of shapes, not a mesh's, so the union is taken here. First-seen order is
     * kept rather than sorting, because that is the artist's order in the authoring tool and any UI
     * built from it should read the way the file does.
     */
    public List<String> getMorphTargetNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Mesh mesh : meshes) {
            for (MorphTarget target : mesh.getMorphTargets()) {
                names.add(target.name());
            }
        }
        return List.copyOf(names);
    }

    /**
     * Sets the model's shape to the named weights; anything unnamed goes to zero.
     *
     * <p>By name rather than by index, because indices are per-mesh and a caller holding a character's
     * appearance has no business knowing which mesh a shape lives on — or that the same shape lives on
     * seven of them. Each mesh translates the map into its own order.
     *
     * @return how many meshes were actually rebuilt, which is zero when nothing changed
     */
    public int applyMorphWeights(Map<String, Float> weights) {
        int rebuilt = 0;
        for (Mesh mesh : meshes) {
            List<MorphTarget> targets = mesh.getMorphTargets();
            if (targets.isEmpty()) {
                continue;
            }
            float[] perMesh = new float[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                Float weight = weights.get(targets.get(i).name());
                perMesh[i] = weight == null ? 0f : weight;
            }
            if (mesh.applyMorphWeights(perMesh)) {
                rebuilt++;
            }
        }
        return rebuilt;
    }

    public int getBoneCount() {
        return boneOffsetMatrices.size();
    }

    /**
     * Releases every GL buffer and texture this model owns.
     *
     * <p>Needed once a model can be <b>swapped</b> rather than loaded once and kept for the process: the
     * creation screen reloads whenever the player picks a different character, and without this each
     * switch would strand a set of vertex buffers and a set of textures — which for the player model is
     * some 13 images and 36,000 triangles a time.
     *
     * <p>Safe to call on any model here because nothing is shared between two of them: an untextured
     * material is given its own 1x1 stand-in by {@code AssetLoader} rather than a cached one, so no
     * texture deleted here can still be in use somewhere else.
     */
    public void delete() {
        for (Mesh mesh : meshes) {
            mesh.delete();
        }
        for (Material material : materials) {
            if (material.getBaseColorTexture() != null) {
                material.getBaseColorTexture().delete();
            }
        }
    }
}
