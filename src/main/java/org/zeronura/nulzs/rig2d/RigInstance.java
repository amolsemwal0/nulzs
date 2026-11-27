package org.zeronura.nulzs.rig2d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One rig on screen, posed: its parameter values, the motions playing on it, its physics swinging, and the shapes all
 * that gives its art meshes. Many instances can share a {@link RigModel}.
 *
 * <p>Each {@link #update}: parameters start from what code {@link #set}; the eyes ({@link #eyes}) blink, glance and
 * hold their expression over that; the looping motion ({@link #loop}) plays over them; one-shot motions ({@link #play})
 * play over that, fading in and out; physics adds follow-through; then every deformer is posed and every mesh bent
 * through its deformers ({@link #positions}).
 */
public final class RigInstance {

    private final RigModel model;
    private int seenRevision = -1;

    private float[] set = new float[0];
    private float[] values = new float[0];
    private float[][] deformerStates = new float[0][];
    private float[][] positions = new float[0][];
    private float[] opacity = new float[0];
    private int[] order = new int[0];
    private int[] orderKeys = new int[0];
    private float[] scratch = new float[0];

    private float[] physicsX = new float[0];
    private float[] physicsV = new float[0];
    private boolean physicsSettled;
    public boolean physicsEnabled = true;

    private RigMotion loop;
    private float loopTime;
    private RigMotion fadingLoop;
    private float fadingLoopTime;
    private float loopBlend = 1f;

    private static final class Playing {
        final RigMotion motion;
        float time;
        /** When it was asked to stop early: fading out from here. */
        float stopAt = -1f;

        Playing(RigMotion motion) {
            this.motion = motion;
        }
    }

    private final List<Playing> playing = new ArrayList<>();
    /** Speeds every motion up or slows it down. */
    public float speed = 1f;

    /**
     * The eyes' own life (blinks, glances, expression), for a rig with eye parameters; null for none. Set
     * {@link RigEyes#style} to give them a temperament.
     */
    public RigEyes eyes;
    public boolean eyesEnabled = true;
    private int[] eyeIndices = new int[0];
    private static final String[] EYE_PARAMETERS = {AutoRig.EYE_OPEN, AutoRig.EYE_LID, AutoRig.EYE_SMILE,
            AutoRig.EYE_WIDE, AutoRig.EYE_BALL_X, AutoRig.EYE_BALL_Y, AutoRig.BROW_ANGLE, AutoRig.BROW_Y};

    public RigInstance(RigModel model) {
        this.model = model;
        sync();
        boolean hasEyes = false;
        for (int index : eyeIndices) {
            hasEyes |= index >= 0;
        }
        if (hasEyes) {
            eyes = new RigEyes(RigEyes.lively(), System.nanoTime() ^ System.identityHashCode(this));
        }
        evaluate();
    }

    public RigModel model() {
        return model;
    }

    /** Re-sizes everything for the model's current parts after it changed (the editor's edits). */
    private void sync() {
        if (seenRevision == model.revision) {
            return;
        }
        seenRevision = model.revision;
        int p = model.parameters.size();
        float[] oldSet = set;
        set = new float[p];
        for (int i = 0; i < p; i++) {
            set[i] = model.parameters.get(i).defaultValue();
        }
        // Keep what was set before for parameters still there, matched by position (ids rarely change order).
        if (oldSet.length == p) {
            System.arraycopy(oldSet, 0, set, 0, p);
        }
        values = set.clone();
        deformerStates = new float[model.deformers.size()][];
        for (int i = 0; i < deformerStates.length; i++) {
            deformerStates[i] = new float[model.deformers.get(i).formSize()];
        }
        positions = new float[model.meshes.size()][];
        int largest = 0;
        for (int i = 0; i < positions.length; i++) {
            ArtMesh mesh = model.meshes.get(i);
            positions[i] = new float[mesh.vertices.length];
            largest = Math.max(largest, mesh.keyforms.size());
        }
        scratch = new float[largest];
        opacity = new float[positions.length];
        order = new int[positions.length];
        orderKeys = new int[positions.length];
        if (physicsX.length != model.physics.size()) {
            physicsX = new float[model.physics.size()];
            physicsV = new float[model.physics.size()];
            physicsSettled = false;
        }
        eyeIndices = new int[EYE_PARAMETERS.length];
        for (int i = 0; i < EYE_PARAMETERS.length; i++) {
            eyeIndices[i] = model.parameterIndex(EYE_PARAMETERS[i]);
        }
        if (loop != null && !model.motions.containsValue(loop)) {
            RigMotion again = model.motions.get(loop.id);
            loop = again;
        }
        playing.removeIf(play -> !model.motions.containsValue(play.motion));
    }

    // --- Parameters -----------------------------------------------------------------------------------------------

    /** Sets a parameter from code: the value motions and physics start from. Unknown ids are ignored. */
    public void set(String parameter, float value) {
        sync();
        int index = model.parameterIndex(parameter);
        if (index >= 0) {
            set[index] = model.parameters.get(index).clamp(value);
            // Seen at once by evaluate(); the next update() starts from it.
            values[index] = set[index];
        }
    }

    /** Puts every parameter set from code back to its default. */
    public void reset() {
        sync();
        for (int i = 0; i < set.length; i++) {
            set[i] = model.parameters.get(i).defaultValue();
        }
        System.arraycopy(set, 0, values, 0, set.length);
    }

    /** A parameter's value in the last pose, or NaN for an unknown id. */
    public float get(String parameter) {
        sync();
        int index = model.parameterIndex(parameter);
        return index < 0 ? Float.NaN : values[index];
    }

    /** The value set from code, before motions and physics. */
    public float getSet(String parameter) {
        sync();
        int index = model.parameterIndex(parameter);
        return index < 0 ? Float.NaN : set[index];
    }

    // --- Motions --------------------------------------------------------------------------------------------------

    /** Loops a motion underneath everything else (crossfading from the one before); null or unknown stops looping. */
    public void loop(String motion) {
        RigMotion next = motion == null ? null : model.motions.get(motion);
        if (next == loop) {
            return;
        }
        fadingLoop = loop;
        fadingLoopTime = loopTime;
        loop = next;
        loopTime = 0f;
        loopBlend = fadingLoop == null ? 1f : 0f;
    }

    public String looping() {
        return loop == null ? null : loop.id;
    }

    /** Plays a motion once over the loop; restarts it if it is already playing. False if the rig has no such motion. */
    public boolean play(String motion) {
        RigMotion m = motion == null ? null : model.motions.get(motion);
        if (m == null) {
            return false;
        }
        playing.removeIf(p -> p.motion == m);
        playing.add(new Playing(m));
        return true;
    }

    public boolean has(String motion) {
        return motion != null && model.motions.containsKey(motion);
    }

    /** Whether a one-shot motion is still playing (fading out counts). */
    public boolean isPlaying(String motion) {
        for (Playing p : playing) {
            if (p.motion.id.equals(motion)) {
                return true;
            }
        }
        return false;
    }

    /** Fades every one-shot motion out. */
    public void stopMotions() {
        for (Playing p : playing) {
            if (p.stopAt < 0f) {
                p.stopAt = p.time;
            }
        }
    }

    /** Starts the physics from rest: for a rig that has just appeared, so nothing swings in from nowhere. */
    public void resetPhysics() {
        Arrays.fill(physicsV, 0f);
        physicsSettled = false;
    }

    // --- Posing ---------------------------------------------------------------------------------------------------

    /** Moves time on by {@code dt} seconds and poses the rig. */
    public void update(float dt) {
        sync();
        dt = Math.max(0f, Math.min(0.1f, dt)) * speed;
        System.arraycopy(set, 0, values, 0, values.length);
        if (eyes != null && eyesEnabled) {
            eyes.update(dt);
            applyEyes();
        }

        if (loop != null) {
            loopTime = (loopTime + dt) % loop.duration;
        }
        if (fadingLoop != null) {
            fadingLoopTime = (fadingLoopTime + dt) % fadingLoop.duration;
            loopBlend = Math.min(1f, loopBlend + dt / 0.3f);
            applyMotion(fadingLoop, fadingLoopTime, 1f);
            if (loopBlend >= 1f) {
                fadingLoop = null;
            }
        }
        if (loop != null) {
            applyMotion(loop, loopTime, fadingLoop == null ? 1f : loopBlend);
        }
        for (int i = 0; i < playing.size(); i++) {
            Playing p = playing.get(i);
            p.time += dt;
            float weight = envelope(p);
            if (weight <= 0f && (p.time >= p.motion.duration || p.stopAt >= 0f)) {
                playing.remove(i--);
                continue;
            }
            applyMotion(p.motion, Math.min(p.time, p.motion.duration), weight);
        }
        if (physicsEnabled) {
            stepPhysics(dt);
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = model.parameters.get(i).clamp(values[i]);
        }
        evaluate();
    }

    /** Writes the eyes' state into the parameters, the gaze turned to the way the rig faces. */
    private void applyEyes() {
        float facing = model.facing < 0 ? -1f : 1f;
        float[] state = {eyes.open, eyes.lid, eyes.smile, eyes.wide, eyes.ballX * facing, eyes.ballY, eyes.brow,
                eyes.browY};
        for (int i = 0; i < eyeIndices.length; i++) {
            int index = eyeIndices[i];
            if (index >= 0) {
                values[index] = model.parameters.get(index).clamp(state[i]);
            }
        }
    }

    /** Poses the rig as one motion has it at a time — no loop, no physics: the editor's timeline. */
    public void poseAt(RigMotion motion, float time) {
        sync();
        System.arraycopy(set, 0, values, 0, values.length);
        if (motion != null) {
            applyMotion(motion, motion.loop ? time % motion.duration : Math.min(time, motion.duration), 1f);
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = model.parameters.get(i).clamp(values[i]);
        }
        evaluate();
    }

    private static float envelope(Playing p) {
        RigMotion m = p.motion;
        float in = m.fadeIn <= 0f ? 1f : Math.min(1f, p.time / m.fadeIn);
        float out;
        if (p.stopAt >= 0f) {
            out = m.fadeOut <= 0f ? 0f : 1f - (p.time - p.stopAt) / m.fadeOut;
        } else {
            float left = m.duration - p.time;
            out = m.fadeOut <= 0f ? (left > 0f ? 1f : 0f) : left / m.fadeOut;
        }
        float w = Math.max(0f, Math.min(in, Math.min(1f, out)));
        return w * w * (3f - 2f * w);
    }

    private void applyMotion(RigMotion motion, float time, float weight) {
        if (weight <= 0f) {
            return;
        }
        for (RigMotion.Curve curve : motion.curves) {
            int index = curve.index;
            if (index < 0 || index >= values.length) {
                continue;
            }
            float v = curve.sample(time, motion.duration, motion.loop);
            values[index] += (v - values[index]) * weight;
        }
    }

    private void stepPhysics(float dt) {
        List<RigPhysics> rigs = model.physics;
        if (rigs.isEmpty() || dt <= 0f) {
            return;
        }
        int steps = Math.max(1, (int) Math.ceil(dt / (1f / 120f)));
        float h = dt / steps;
        for (int r = 0; r < rigs.size(); r++) {
            RigPhysics p = rigs.get(r);
            if (p.outputIndex < 0) {
                continue;
            }
            float target = 0f;
            for (int i = 0; i < p.inputIndices.length; i++) {
                int index = p.inputIndices[i];
                if (index >= 0) {
                    target += model.parameters.get(index).normalized(values[index]) * p.inputs.get(i).weight();
                }
            }
            if (!physicsSettled) {
                physicsX[r] = target;
                physicsV[r] = 0f;
            }
            for (int s = 0; s < steps; s++) {
                float accel = p.stiffness * (target - physicsX[r]) - p.damping * physicsV[r];
                physicsV[r] += accel * h;
                physicsX[r] += physicsV[r] * h;
            }
            float lag = (target - physicsX[r]) * p.gain;
            RigParameter out = model.parameters.get(p.outputIndex);
            float n = out.normalized(values[p.outputIndex]) - lag;
            values[p.outputIndex] = out.fromNormalized(Math.max(-1f, Math.min(1f, n)));
        }
        physicsSettled = true;
    }

    /** Poses every deformer and bends every mesh, from the parameter values as they are. */
    public void evaluate() {
        sync();
        int[] deformerOrder = model.deformerOrder();
        for (int index : deformerOrder) {
            Deformer deformer = model.deformers.get(index);
            float[] state = deformerStates[index];
            Arrays.fill(state, 0f);
            deformer.keyforms.addTo(values, state);
            deformer.pose(state);
        }
        for (int m = 0; m < positions.length; m++) {
            ArtMesh mesh = model.meshes.get(m);
            float[] out = positions[m];
            if (out.length != mesh.vertices.length) {
                out = positions[m] = new float[mesh.vertices.length];
            }
            int size = mesh.keyforms.size();
            if (scratch.length < size) {
                scratch = new float[size];
            }
            Arrays.fill(scratch, 0, size, 0f);
            mesh.keyforms.addTo(values, scratch);
            for (int i = 0; i < out.length; i++) {
                out[i] = mesh.vertices[i] + (ArtMesh.VERTICES + i < size ? scratch[ArtMesh.VERTICES + i] : 0f);
            }
            opacity[m] = Math.max(0f, Math.min(1f, 1f + scratch[ArtMesh.OPACITY]));
            orderKeys[m] = mesh.drawOrder + Math.round(scratch[ArtMesh.ORDER]);
            int d = mesh.parentIndex;
            int guard = 0;
            while (d >= 0 && guard++ <= model.deformers.size()) {
                model.deformers.get(d).apply(deformerStates[d], out, 0, out.length / 2);
                d = model.deformers.get(d).parentIndex;
            }
        }
        // Back to front by draw order, ties in the layered file's order: an insertion sort, as the order barely
        // changes from one frame to the next.
        for (int m = 0; m < order.length; m++) {
            order[m] = m;
        }
        for (int i = 1; i < order.length; i++) {
            int moving = order[i];
            int j = i - 1;
            while (j >= 0 && (orderKeys[order[j]] > orderKeys[moving]
                    || orderKeys[order[j]] == orderKeys[moving] && order[j] > moving)) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = moving;
        }
    }

    /** The posed vertices of mesh {@code index} (model order), x, y pairs in canvas pixels. */
    public float[] positions(int index) {
        return positions[index];
    }

    public float opacity(int index) {
        return opacity[index];
    }

    /** Mesh indices in the order to draw them, back to front. */
    public int[] drawOrder() {
        return order;
    }

    /** A deformer's posed state (see {@link Deformer}), for drawing its handles. */
    public float[] deformerState(int index) {
        return deformerStates[index];
    }

    /** Moves points (x, y pairs) through a deformer and everything above it, as its children are moved. */
    public void deform(int deformerIndex, float[] points) {
        int d = deformerIndex;
        int guard = 0;
        while (d >= 0 && guard++ <= model.deformers.size()) {
            model.deformers.get(d).apply(deformerStates[d], points, 0, points.length / 2);
            d = model.deformers.get(d).parentIndex;
        }
    }
}
