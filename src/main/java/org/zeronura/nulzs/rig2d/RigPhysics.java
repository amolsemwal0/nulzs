package org.zeronura.nulzs.rig2d;

import java.util.ArrayList;
import java.util.List;

/**
 * Follow-through for a loose part — a tail, a fin, an antenna, a flame: the part trails behind when the body moves and
 * swings past before it settles. A weight on a spring chases a point that the {@link #inputs} move (each input
 * parameter, as -1..1 about its default, times its weight); how far the weight lags behind, times {@link #gain}, is
 * added to the {@link #output} parameter (again as -1..1 of its range). Stiffer springs settle faster; more damping
 * swings less.
 */
public final class RigPhysics {

    public record Input(String parameter, float weight) {
    }

    public String id;
    public final List<Input> inputs = new ArrayList<>();
    public String output;
    public float gain = 1.5f;
    /** Spring strength, per second squared. */
    public float stiffness = 60f;
    /** Damping, per second. */
    public float damping = 6f;

    int[] inputIndices;
    int outputIndex = -1;

    public RigPhysics(String id, String output) {
        this.id = id;
        this.output = output;
    }

    public RigPhysics copy() {
        RigPhysics copy = new RigPhysics(id, output);
        copy.inputs.addAll(inputs);
        copy.gain = gain;
        copy.stiffness = stiffness;
        copy.damping = damping;
        return copy;
    }
}
