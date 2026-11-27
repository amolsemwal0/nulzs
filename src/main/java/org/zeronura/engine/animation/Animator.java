package org.zeronura.engine.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.zeronura.engine.asset.AnimationClip;
import org.zeronura.engine.asset.Model;
import org.zeronura.engine.asset.NodeAnimChannel;
import org.zeronura.engine.asset.SkeletonNode;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Poses a <b>skinned</b> model: plays a clip through the skeleton and produces the bone-matrix array the
 * skinning shader multiplies vertices by.
 *
 * <p>The keyframe interpolation itself lives in {@link ClipSampler}, which knows nothing about bones —
 * node-animated models with no skeleton at all (the slime) read the same keys through it.
 *
 * <h2>Forward kinematics, and then inverse</h2>
 * The walk below <b>is</b> forward kinematics: every node's pose is its parent's composed with its own,
 * which is what FK means and what every clip in this project has always played through. Note that an FK
 * rig's <i>controls</i> — and an IK rig's — do not survive a glTF export at all: what arrives in a
 * {@code .glb} is baked per-bone keys, which is FK by construction. Whatever a model is rigged with in a
 * DCC tool, the constraints are the runtime's to add.
 *
 * <p>{@link IkChain} is that addition, and it runs <b>after</b> the FK pass rather than instead of it: the
 * clip decides the pose, and a constraint then bends part of it to reach something the animator could not
 * know about — the ground under a foot on a hillside, the grip of a held weapon. Chains are applied in the
 * order they were added, so a chain inside another one's subtree must be added second.
 *
 * <h2>The pass is iterative now, not recursive, and stores every node</h2>
 * Two things IK needs that the old recursion could not give: a node's pose has to be <i>readable</i>
 * (a solver works from where the joints are), and a subtree has to be <i>recomputable</i> after a joint
 * is moved. Nodes are numbered in pre-order at construction, which makes a subtree a contiguous run and a
 * parent always lower-numbered than its children — so one forward sweep is a whole FK pass, with no
 * recursion and no allocation.
 *
 * <p>Poses are kept in <b>model space</b> — the model's own inverse global transform is folded in at the
 * root rather than per bone, which it may be because everything after it is a right-multiply. That is the
 * space a socket rides in and the space an IK target is given in, so nothing has to convert.
 */
public class Animator {

    private final Model model;

    /** Every node, parents before children, so a subtree is the contiguous run {@code [i, subtreeEnd[i])}. */
    private final List<SkeletonNode> nodes = new ArrayList<>();
    private final Map<String, Integer> indexByName = new HashMap<>();
    private final int[] parentIndex;
    private final int[] subtreeEnd;

    /** Each node's pose this frame, in model space. */
    private final Matrix4f[] nodePoses;

    /** Nodes an IK chain has already placed, which a subtree refresh must not overwrite. */
    private final boolean[] pinned;

    /**
     * The pose the clip alone produced, before any modifier touched it.
     *
     * <p>Kept because a constraint usually has to ask what the animation was <b>going</b> to do. Foot
     * planting is the case that makes it necessary: whether a foot should be pinned to the ground is
     * decided by how far the clip has lifted it off its own rest height — a stance foot is down, a swing
     * foot is up — and reading that off the constrained pose is a feedback loop, since the constraint is
     * what moved it. Measured against the clip, the signal is independent of anything IK does.
     */
    private final Matrix4f[] fkPoses;

    private final Matrix4f[] finalBoneMatrices;

    /**
     * Each bone's pose in <b>model space</b> — where the bone is, rather than what it does to a vertex.
     *
     * <p>The distinction is the whole of what a socket needs and is easy to get wrong, because the array
     * next door looks like the answer and is not. A skinning matrix is {@code modelPose * offset}, and
     * that trailing {@code offset} is the inverse bind pose: it exists to take a vertex <i>out</i> of its
     * rest position first. Multiply a sword by it and the sword is dragged wherever the hand's bind pose
     * happened to be authored.
     */
    private final Matrix4f[] boneModelMatrices;

    private final List<PoseModifier> modifiers = new ArrayList<>();

    /**
     * Second clips playing on parts of the skeleton — see {@link PoseOverlay}.
     *
     * <p>Separate from {@link #modifiers} because they are a different kind of thing and run at a
     * different point: a modifier bends the finished pose, an overlay is one of the pose's own
     * inputs and is read inside {@link #localTransform}.
     */
    private final List<PoseOverlay> overlays = new ArrayList<>();

    private final Matrix4f scratchLocal = new Matrix4f();

    /**
     * Each node's bind transform taken apart, for blending a clip against a node it has no channel for.
     *
     * <p>A blend mixes translation, rotation and scale separately — mixing two matrices entry by entry
     * shears and shrinks whatever turns between them — so the bind pose has to be in the same three parts
     * as a sampled key. Taken apart once here rather than per node per frame.
     */
    /** Every node's bind pose in model space, and the inverse of each — see {@link #nodeFromBind}. */
    private final Matrix4f[] bindPoses;
    private final Matrix4f[] bindPoseInverses;
    private final Vector3f[] bindPositions;
    private final Quaternionf[] bindRotations;
    private final Vector3f[] bindScales;

    private final Vector3f blendPositionA = new Vector3f();
    private final Vector3f blendPositionB = new Vector3f();
    private final Quaternionf blendRotationA = new Quaternionf();
    private final Quaternionf blendRotationB = new Quaternionf();
    private final Vector3f blendScaleA = new Vector3f();
    private final Vector3f blendScaleB = new Vector3f();

    private AnimationClip currentClip;
    private double timeSeconds;
    private double timeTicks;

    /** The clip mixed into the main one, or null — see {@link #setBlend}. */
    private AnimationClip blendClip;
    private float blendWeight;
    private double blendTicks;

    public Animator(Model model) {
        this.model = model;

        List<Integer> parents = new ArrayList<>();
        number(model.getRootNode(), -1, parents);
        this.parentIndex = new int[nodes.size()];
        for (int i = 0; i < parentIndex.length; i++) {
            parentIndex[i] = parents.get(i);
        }
        // A subtree is contiguous in pre-order, so its end is simply the next index at or above it whose
        // parent is not inside it — which the numbering pass has already recorded.
        this.subtreeEnd = new int[nodes.size()];
        for (int i = nodes.size() - 1; i >= 0; i--) {
            subtreeEnd[i] = i + 1;
            for (int child = i + 1; child < nodes.size() && parentIndex[child] >= i; child++) {
                subtreeEnd[i] = subtreeEnd[child];
                child = subtreeEnd[child] - 1;
            }
        }

        this.nodePoses = new Matrix4f[nodes.size()];
        this.fkPoses = new Matrix4f[nodes.size()];
        this.pinned = new boolean[nodes.size()];
        for (int i = 0; i < nodePoses.length; i++) {
            nodePoses[i] = new Matrix4f();
            fkPoses[i] = new Matrix4f();
        }

        this.bindPositions = new Vector3f[nodes.size()];
        this.bindRotations = new Quaternionf[nodes.size()];
        this.bindScales = new Vector3f[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            Matrix4f bind = nodes.get(i).localBindTransform;
            bindPositions[i] = bind.getTranslation(new Vector3f());
            bindRotations[i] = bind.getNormalizedRotation(new Quaternionf());
            bindScales[i] = bind.getScale(new Vector3f());
        }

        // The whole skeleton at rest, in model space, and its inverse — what "how far has this node moved"
        // is measured against. Built the same way poseNode builds a live pose, from the same root, so the
        // two agree exactly on a node nothing has animated. Pre-order numbering puts every parent first.
        this.bindPoses = new Matrix4f[nodes.size()];
        this.bindPoseInverses = new Matrix4f[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            int parent = parentIndex[i];
            Matrix4f parentBind = parent < 0 ? model.getGlobalInverseTransform() : bindPoses[parent];
            bindPoses[i] = new Matrix4f(parentBind).mul(nodes.get(i).localBindTransform);
            bindPoseInverses[i] = new Matrix4f(bindPoses[i]).invert();
        }
        this.finalBoneMatrices = new Matrix4f[model.getBoneCount()];
        this.boneModelMatrices = new Matrix4f[model.getBoneCount()];
        for (int i = 0; i < finalBoneMatrices.length; i++) {
            finalBoneMatrices[i] = new Matrix4f();
            boneModelMatrices[i] = new Matrix4f();
        }
        update(0.0);
    }

    private void number(SkeletonNode node, int parent, List<Integer> parents) {
        int index = nodes.size();
        nodes.add(node);
        parents.add(parent);
        // First wins: a rig with two nodes of one name is a broken rig, and answering with the first is
        // both deterministic and what a name lookup meant before there was a map.
        indexByName.putIfAbsent(node.name, index);
        for (SkeletonNode child : node.children) {
            number(child, index, parents);
        }
    }

    /**
     * Switches the playing clip, restarting playback from the beginning.
     *
     * <p>Re-setting the clip already playing does nothing. Callers pick a clip from state ("moving, so
     * walk") and naturally call this every frame; without the guard that would reset the time each frame
     * and freeze the animation on its first pose.
     */
    public void setClip(AnimationClip clip) {
        if (clip == currentClip) {
            return;
        }
        this.currentClip = clip;
        this.timeSeconds = 0.0;
    }

    public AnimationClip getClip() {
        return currentClip;
    }

    /**
     * Mixes a second whole-body clip into the main one, {@code weight} of the way from the main clip's
     * pose to this one's. A weight of 0, or a null clip, turns the blend off.
     *
     * <p>What this is for is locomotion: a walk turning into a run as a character speeds up. Swapping clips
     * at some speed pops from one pose to the other, and the in-between speeds belong to neither clip —
     * a walk played fast enough to cover a jog is a cartoon, and a run played that slowly floats. Mixed, the
     * stride grows from the one to the other as the weight does.
     *
     * <p><b>The blend clip is phase-locked to the main clip</b>, not played on its own clock. A walk and a
     * run both put the left foot down at some fraction of their cycle, and that fraction is what has to
     * line up; their cycles are different lengths, so two clocks would drift apart and mix a stride with
     * the other leg's. So the blend clip is always sampled at the same fraction of its cycle that the main
     * clip is at of its own — and a caller speeding the animation up for a longer cycle does it through the
     * main clip's rate, since that is the one clock there is.
     *
     * <p>Does not restart anything: the main clip keeps its time, so the weight can be moved every frame.
     * {@link PoseOverlay}s still win over both clips on their own part of the skeleton.
     */
    public void setBlend(AnimationClip clip, float weight) {
        this.blendClip = clip;
        this.blendWeight = clip == null ? 0f : Math.clamp(weight, 0f, 1f);
    }

    /** The clip mixed into the main one, or null when there is no blend. */
    public AnimationClip getBlendClip() {
        return blendWeight > 0f ? blendClip : null;
    }

    public float getBlendWeight() {
        return blendWeight;
    }

    /**
     * Where playback currently is, in seconds since the clip started.
     *
     * <p>Unwrapped: it grows without bound while a looping clip repeats, because that is what
     * {@link ClipSampler#loopTicks} takes and what {@code onceTicks} needs to know a one-shot has run
     * out. Use {@code % (clip.cycleTicks() / clip.ticksPerSecond)} for a position within the cycle.
     */
    public double getTime() {
        return timeSeconds;
    }

    /**
     * Seeks playback, without touching which clip is playing.
     *
     * <p>The missing half of {@link #setClip}, which sets the time to zero and gives no way to set it to
     * anything else — so until this there was no way to <b>hold a chosen frame</b>. Everything in the game
     * plays clips forward from wherever they are, which is why it went so long unneeded; what wants it is
     * a tool, where picking the pose you are working against is the whole point.
     *
     * <p>Does not itself re-pose the skeleton: like {@link #setClip} it only moves the clock, and the
     * bones follow on the next {@link #update}. Call {@code update(0.0)} to see the new frame without
     * advancing time.
     */
    public void setTime(double seconds) {
        this.timeSeconds = seconds;
    }

    public void update(double deltaSeconds) {
        timeTicks = 0.0;
        if (currentClip != null) {
            timeSeconds += deltaSeconds;
            timeTicks = ClipSampler.loopTicks(currentClip, timeSeconds);
            if (blendClip != null && blendWeight > 0f && currentClip.cycleTicks() > 0.0) {
                double phase = (timeTicks - currentClip.startTicks()) / currentClip.cycleTicks();
                blendTicks = blendClip.startTicks() + phase * blendClip.cycleTicks();
            }
        }
        // Each overlay keeps its own clock, so a second clip plays at its own rate rather than at some
        // phase of this one's — see PoseOverlay.
        for (int i = 0; i < overlays.size(); i++) {
            overlays.get(i).advance(deltaSeconds);
        }

        java.util.Arrays.fill(pinned, false);
        for (int i = 0; i < nodes.size(); i++) {
            poseNode(i);
        }
        if (!modifiers.isEmpty()) {
            for (int i = 0; i < nodePoses.length; i++) {
                fkPoses[i].set(nodePoses[i]);
            }
            for (PoseModifier modifier : modifiers) {
                modifier.apply(this);
            }
        }
        for (int i = 0; i < nodes.size(); i++) {
            SkeletonNode node = nodes.get(i);
            if (node.boneIndex >= 0) {
                boneModelMatrices[node.boneIndex].set(nodePoses[i]);
                finalBoneMatrices[node.boneIndex].set(nodePoses[i])
                        .mul(model.getBoneOffsetMatrices().get(node.boneIndex));
            }
        }
    }

    private void poseNode(int index) {
        int parent = parentIndex[index];
        Matrix4f parentPose = parent < 0 ? model.getGlobalInverseTransform() : nodePoses[parent];
        nodePoses[index].set(parentPose).mul(localTransform(index));
    }

    public Matrix4f[] getFinalBoneMatrices() {
        return finalBoneMatrices;
    }

    /**
     * Where a bone is this frame, in the model's own space — see {@link #boneModelMatrices}.
     *
     * @return {@code dest}, or {@code dest} left as the identity for an index that is not a bone, so an
     *         attachment on a socket the rig does not have rides the character's origin rather than
     *         landing at an undefined place or throwing mid-frame
     */
    public Matrix4f boneModelTransform(int boneIndex, Matrix4f dest) {
        if (boneIndex < 0 || boneIndex >= boneModelMatrices.length) {
            return dest.identity();
        }
        return dest.set(boneModelMatrices[boneIndex]);
    }

    /** How many bones this rig poses — what a caller sizing a buffer for them needs. */
    public int getBoneCount() {
        return finalBoneMatrices.length;
    }

    public void writeBoneMatrices(FloatBuffer buffer) {
        for (int i = 0; i < Math.min(finalBoneMatrices.length, buffer.capacity() / 16); i++) {
            finalBoneMatrices[i].get(i * 16, buffer);
        }
    }

    // --- Inverse kinematics -------------------------------------------------------------------------

    /**
     * Adds a two-bone constraint over three named joints — hip/knee/foot, shoulder/elbow/hand.
     *
     * @return the chain, to be given a target each frame, or <b>null</b> if the rig does not have all
     *         three joints. Null rather than an exception because "this character has no legs" is an
     *         ordinary answer here — the shipped base mesh has no bones at all — and because a constraint
     *         that threw at construction would take a session down over a rig it could simply not
     *         constrain.
     */
    public IkChain ikChain(String rootName, String midName, String endName) {
        Integer root = indexByName.get(rootName);
        Integer mid = indexByName.get(midName);
        Integer end = indexByName.get(endName);
        if (root == null || mid == null || end == null) {
            return null;
        }
        // The joints must actually be a chain, or the solve is meaningless: it rotates the root and
        // expects the others to follow, which they only do if they hang off it. Descendant rather than
        // direct child, because a rig is entitled to put joints in between: a Rigify deform leg splits
        // each of the thigh and the shin into two segments, so the knee is the root's grandchild and the
        // ankle the knee's. What the solve needs is that turning the root carries the other two, which
        // any ancestry gives; the pre-order numbering makes it a range test.
        if (!isDescendant(mid, root) || !isDescendant(end, mid)) {
            return null;
        }
        IkChain chain = new IkChain(root, mid, end);
        modifiers.add(chain);
        return chain;
    }

    /** Whether {@code node} hangs off {@code ancestor}, at any depth. Pre-order, so it is a range. */
    private boolean isDescendant(int node, int ancestor) {
        return node > ancestor && node < subtreeEnd[ancestor];
    }

    /**
     * Adds a modifier of the caller's own, applied in turn with the rest.
     *
     * <p>For a constraint that decides what the built-in ones should aim at — foot planting reads the
     * ground and hands the leg chains their targets — and which therefore has to run <b>inside</b> the
     * pose pass rather than between frames. Working it out afterwards makes every target one frame stale,
     * which for a planted foot is invisible and for a swinging one drags it back toward where it was.
     */
    public void addModifier(PoseModifier modifier) {
        modifiers.add(modifier);
    }

    /**
     * The same, but applied before everything already added.
     *
     * <p>For a modifier that <b>decides what the others aim at</b>: foot planting reads the ground and
     * hands the leg chains their targets, so it has to have run by the time they solve — and it creates
     * those chains itself, which appends them. Inserting is how it gets in front of its own dependents
     * without every caller having to know the order.
     */
    public void addModifierFirst(PoseModifier modifier) {
        modifiers.add(0, modifier);
    }

    /**
     * Plays a second clip on everything <b>below</b> a named node, over whatever the main clip is doing.
     *
     * <p>The way to pose one body part independently: a hand grips while the arm it is on goes on
     * walking. See {@link PoseOverlay} for why this is read inside the pose pass rather than being a
     * {@link PoseModifier}, and for why it covers a node's descendants and not the node itself — in
     * short, the subtree below the wrist is the fingers, and the wrist belongs to the arm's swing.
     *
     * @return the overlay, to be given a clip when it should play, or <b>null</b> if the rig has no such
     *         node <i>or nothing below it</i>. Null in the second case as well, because an overlay
     *         covering nothing is the silent-no-op failure this whole area is written against: it would
     *         accept a clip, report itself active, and change not one pixel.
     */
    public PoseOverlay overlayBelow(String nodeName) {
        Integer index = indexByName.get(nodeName);
        if (index == null || subtreeEnd[index] <= index + 1) {
            return null;
        }
        PoseOverlay overlay = new PoseOverlay(index + 1, subtreeEnd[index]);
        overlays.add(overlay);
        return overlay;
    }

    /**
     * Adds an offset on a named node and everything below it — see {@link NodeOffset}.
     *
     * @return the offset, or null if the rig has no such node
     */
    public NodeOffset nodeOffset(String nodeName) {
        Integer index = indexByName.get(nodeName);
        if (index == null) {
            return null;
        }
        NodeOffset offset = new NodeOffset(index);
        modifiers.add(offset);
        return offset;
    }

    /**
     * Adds an offset on the whole skeleton — {@link #nodeOffset} on the root node.
     *
     * <p>For a rig with no single named bone that parents the whole body. A VRM hips bone is the root of
     * the legs <i>and</i> the spine, so shifting it shifts the character; a Rigify export has no such
     * bone — its deform spine hangs off {@code root} while its deform legs hang off {@code ORG-spine} —
     * and the only node above both is the armature itself. The offset is a model-space translation
     * either way (see {@link NodeOffset}), so the two are the same move.
     */
    public NodeOffset rootOffset() {
        NodeOffset offset = new NodeOffset(0);
        modifiers.add(offset);
        return offset;
    }

    /** Every modifier added, in the order they are applied. */
    public List<PoseModifier> poseModifiers() {
        return List.copyOf(modifiers);
    }

    /** Whether the rig has a node of this name at all — what a naming convention is matched on. */
    public boolean hasNode(String nodeName) {
        return indexByName.containsKey(nodeName);
    }

    /** Where a named node is this frame, in model space, or null if the rig has no such node. */
    public org.joml.Vector3f nodePosition(String nodeName, org.joml.Vector3f dest) {
        Integer index = indexByName.get(nodeName);
        return index == null ? null : nodePoses[index].getTranslation(dest);
    }

    /**
     * How a named node has moved from its bind pose this frame, in model space: the matrix that carries a
     * point or direction on the node <i>at rest</i> to where it is <i>now</i>. Null if the rig has no such
     * node.
     *
     * <p>A delta rather than the pose itself because a bone's own axes mean nothing portable — a VRoid head
     * is aligned with the model, a Rigify or Mixamo one points its Y along the neck and its Z wherever the
     * rigger left it. "The model's forward, turned however the head has turned" is the same question on
     * every rig, and this answers it: {@code nodeFromBind(head).transformDirection(modelForward)}.
     */
    public Matrix4f nodeFromBind(String nodeName, Matrix4f dest) {
        Integer index = indexByName.get(nodeName);
        return index == null ? null : dest.set(nodePoses[index]).mul(bindPoseInverses[index]);
    }

    /** Where a named node sits in the bind pose, in model space, or null if the rig has no such node. */
    public Vector3f nodeBindPosition(String nodeName, Vector3f dest) {
        Integer index = indexByName.get(nodeName);
        return index == null ? null : bindPoses[index].getTranslation(dest);
    }

    /** Where the <b>clip alone</b> put a node this frame, before any modifier — see {@link #fkPoses}. */
    public org.joml.Vector3f fkNodePosition(String nodeName, org.joml.Vector3f dest) {
        Integer index = indexByName.get(nodeName);
        return index == null ? null : fkPoses[index].getTranslation(dest);
    }

    /** The model-space pose of a node this frame — what a solver reads. */
    Matrix4f nodePose(int index) {
        return nodePoses[index];
    }

    /** Marks a node as placed by a constraint, so refreshing its subtree leaves it alone. */
    void pin(int index) {
        pinned[index] = true;
    }

    /**
     * Recomputes a node's descendants from its new pose.
     *
     * <p>Cheap because the numbering is pre-order: a subtree is one contiguous run, so this is a short
     * forward sweep rather than a search. Pinned nodes are skipped — they are the ones the constraint has
     * just placed, and re-deriving them from their parents is exactly undoing the solve.
     */
    void refreshSubtree(int index) {
        for (int i = index + 1; i < subtreeEnd[index]; i++) {
            if (!pinned[i]) {
                poseNode(i);
            }
        }
    }

    /** A node's own transform this frame, which a constraint needs to re-derive its children. */
    Matrix4f localTransformOf(int index) {
        return localTransform(index);
    }

    /**
     * One node's local transform this frame.
     *
     * <p>One function handles both the animated and the bind-pose cases: a node falls back to its static
     * bind transform when there is no clip <i>or</i> when the clip has no channel for it. "No animation
     * assigned" is not a special case — it drops out of the same "no channel found" path that is needed
     * anyway, since most rigs animate only a minority of their nodes.
     */
    private Matrix4f localTransform(int index) {
        SkeletonNode node = nodes.get(index);
        // An overlay speaks for its own part of the skeleton first: a hand gripping something plays the
        // grab pose while the arm carrying it goes on walking. A node it has no channel for falls
        // through to the main clip rather than to the bind pose — see PoseOverlay.
        PoseOverlay overlay = overlayCovering(index);
        if (overlay != null && overlay.clip().findChannel(node.name) != null) {
            return ClipSampler.localTransform(
                    overlay.clip(), node.name, overlay.timeTicks(), scratchLocal);
        }
        if (currentClip == null) {
            return node.localBindTransform;
        }
        if (blendClip != null && blendWeight > 0f) {
            return blendedTransform(index);
        }
        NodeAnimChannel channel = currentClip.findChannel(node.name);
        if (channel == null) {
            return node.localBindTransform;
        }
        // The clip-aware form, so the wrap back onto the first key is interpolated rather than
        // jumped — see ClipSampler.loopTicks.
        return ClipSampler.localTransform(currentClip, node.name, timeTicks, scratchLocal);
    }

    /**
     * A node's local transform with the blend clip mixed in — see {@link #setBlend}. Each clip's part is
     * sampled as the unblended path would, a node a clip has no channel for being at its bind pose there.
     */
    private Matrix4f blendedTransform(int index) {
        String name = nodes.get(index).name;
        sampleInto(currentClip, currentClip.findChannel(name), timeTicks, index,
                blendPositionA, blendRotationA, blendScaleA);
        sampleInto(blendClip, blendClip.findChannel(name), blendTicks, index,
                blendPositionB, blendRotationB, blendScaleB);
        // slerp takes the short way round on its own, so a pair of keys stored on opposite hemispheres
        // does not swing the long way through the in-between weights.
        return scratchLocal.translationRotateScale(
                blendPositionA.lerp(blendPositionB, blendWeight),
                blendRotationA.slerp(blendRotationB, blendWeight),
                blendScaleA.lerp(blendScaleB, blendWeight));
    }

    private void sampleInto(AnimationClip clip, NodeAnimChannel channel, double ticks, int index,
                            Vector3f position, Quaternionf rotation, Vector3f scale) {
        if (channel == null) {
            position.set(bindPositions[index]);
            rotation.set(bindRotations[index]);
            scale.set(bindScales[index]);
            return;
        }
        ClipSampler.position(clip, channel, ticks, position);
        ClipSampler.rotation(clip, channel, ticks, rotation);
        ClipSampler.scale(clip, channel, ticks, scale);
    }

    /** The first overlay playing over a node, or null. Overlays are expected to be disjoint. */
    private PoseOverlay overlayCovering(int index) {
        // Indexed rather than iterated: this runs once per node per frame, which on a Rigify rig is 410
        // times, and the common case is that there are no overlays at all.
        for (int i = 0; i < overlays.size(); i++) {
            PoseOverlay overlay = overlays.get(i);
            if (overlay.covers(index)) {
                return overlay;
            }
        }
        return null;
    }
}
