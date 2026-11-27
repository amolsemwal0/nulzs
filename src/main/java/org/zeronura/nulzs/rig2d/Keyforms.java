package org.zeronura.nulzs.rig2d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How one part of a rig changes with the rig's parameters: shapes ("keyforms") stored at chosen values of one to three
 * parameters, and blended between for the values in between — the way Live2D poses a model.
 *
 * <p>Every keyform is a list of numbers whose meaning the owner gives (a mesh's vertex offsets, a warp's grid-point
 * offsets, a rotation's angle and scale), measured <b>from the rest shape</b>: a part with no keyforms, or posed at a
 * keyform of all zeros, keeps its rest shape. That makes tracks add up: a part can move with its breathing on one track
 * and with its lean on another, and both happen at once — Live2D's blend shapes. Within a track, keyforms cover every
 * combination of its parameters' keys (two keys of one and three of another make six), and a pose between keys blends
 * the nearest ones (multilinear interpolation). Past the first or last key the end keyform holds.
 */
public final class Keyforms {

    /** Keyforms over one, two or three parameters. */
    public static final class Track {
        /** The parameters this track is keyed on, by id. */
        public final List<String> parameters = new ArrayList<>();
        /** The keys of each parameter, ascending: {@code keys.get(i)} belongs to {@code parameters.get(i)}. */
        public final List<float[]> keys = new ArrayList<>();
        /**
         * The keyforms, one per combination of keys, the first parameter's key changing fastest: with keys {a0, a1}
         * and {b0, b1, b2}, the order is (a0 b0) (a1 b0) (a0 b1) (a1 b1) (a0 b2) (a1 b2).
         */
        public final List<float[]> forms = new ArrayList<>();

        int[] resolved;

        public Track() {
        }

        /** A track keyed on one parameter: {@code forms[i]} at {@code keys[i]}. */
        public static Track of(String parameter, float[] keys, float[]... forms) {
            Track track = new Track();
            track.parameters.add(parameter);
            track.keys.add(keys.clone());
            for (float[] form : forms) {
                track.forms.add(form.clone());
            }
            return track;
        }

        /** How many keyforms a track with these keys holds. */
        public int combinations() {
            int n = 1;
            for (float[] k : keys) {
                n *= Math.max(1, k.length);
            }
            return n;
        }

        /** Index of the keyform at these key indices (one per parameter). */
        public int indexOf(int... keyIndices) {
            int index = 0;
            int stride = 1;
            for (int p = 0; p < keys.size(); p++) {
                index += keyIndices[p] * stride;
                stride *= Math.max(1, keys.get(p).length);
            }
            return index;
        }

        public Track copy() {
            Track copy = new Track();
            copy.parameters.addAll(parameters);
            for (float[] k : keys) {
                copy.keys.add(k.clone());
            }
            for (float[] f : forms) {
                copy.forms.add(f.clone());
            }
            return copy;
        }
    }

    /** Floats in each keyform. */
    private int size;
    public final List<Track> tracks = new ArrayList<>();

    public Keyforms(int size) {
        this.size = size;
    }

    public int size() {
        return size;
    }

    /** Changes how many floats each keyform holds, padding with zeros or cutting the end off: a re-meshed part. */
    public void resize(int newSize) {
        for (Track track : tracks) {
            track.forms.replaceAll(form -> Arrays.copyOf(form, newSize));
        }
        size = newSize;
    }

    public Keyforms copy() {
        Keyforms copy = new Keyforms(size);
        for (Track t : tracks) {
            copy.tracks.add(t.copy());
        }
        return copy;
    }

    /** Looks up each track's parameters by id in the rig's list; an unknown parameter keys the track at its first key. */
    void resolve(RigModel model) {
        for (Track track : tracks) {
            track.resolved = new int[track.parameters.size()];
            for (int i = 0; i < track.resolved.length; i++) {
                track.resolved[i] = model.parameterIndex(track.parameters.get(i));
            }
        }
    }

    /**
     * Adds the blend of every track, posed by {@code values} (indexed as the rig's parameters), into {@code out}.
     * Call {@link #resolve} first.
     */
    void addTo(float[] values, float[] out) {
        for (Track track : tracks) {
            addTrack(track, values, out);
        }
    }

    private static final int MAX_PARAMETERS = 3;

    private void addTrack(Track track, float[] values, float[] out) {
        int n = Math.min(track.keys.size(), MAX_PARAMETERS);
        int[] lower = new int[n];
        float[] t = new float[n];
        int[] strides = new int[n];
        int stride = 1;
        for (int p = 0; p < n; p++) {
            float[] keys = track.keys.get(p);
            strides[p] = stride;
            stride *= Math.max(1, keys.length);
            int index = track.resolved == null || p >= track.resolved.length ? -1 : track.resolved[p];
            float value = index < 0 ? (keys.length > 0 ? keys[0] : 0f) : values[index];
            segment(keys, value, lower, t, p);
        }
        int corners = 1 << n;
        for (int corner = 0; corner < corners; corner++) {
            float weight = 1f;
            int form = 0;
            boolean valid = true;
            for (int p = 0; p < n; p++) {
                boolean upper = (corner & (1 << p)) != 0;
                weight *= upper ? t[p] : 1f - t[p];
                int key = lower[p] + (upper ? 1 : 0);
                if (upper && key >= Math.max(1, track.keys.get(p).length)) {
                    valid = false;
                }
                form += key * strides[p];
            }
            if (!valid || weight <= 0f || form >= track.forms.size()) {
                continue;
            }
            float[] values2 = track.forms.get(form);
            int m = Math.min(values2.length, Math.min(out.length, size));
            for (int i = 0; i < m; i++) {
                out[i] += values2[i] * weight;
            }
        }
    }

    /** The key below {@code value} and how far it is toward the next, held at the ends. */
    static void segment(float[] keys, float value, int[] lower, float[] t, int p) {
        if (keys.length <= 1 || value <= keys[0]) {
            lower[p] = 0;
            t[p] = 0f;
            return;
        }
        int last = keys.length - 1;
        if (value >= keys[last]) {
            lower[p] = last - 1;
            t[p] = 1f;
            return;
        }
        int k = 0;
        while (k < last - 1 && value > keys[k + 1]) {
            k++;
        }
        float span = keys[k + 1] - keys[k];
        lower[p] = k;
        t[p] = span <= 0f ? 0f : (value - keys[k]) / span;
    }
}
