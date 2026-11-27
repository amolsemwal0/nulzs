package org.zeronura.nulzs.rig2d;

import java.util.ArrayList;
import java.util.List;

/**
 * An animation of a rig: each parameter it moves follows a curve of keyframes, eased smoothly through them. A looping
 * motion (idle breathing) runs round and round; one that does not (an attack, a flinch) plays once, fading in over
 * {@link #fadeIn} and back out to whatever was underneath over {@link #fadeOut}.
 */
public final class RigMotion {

    /** One parameter's keyframes: times in seconds, ascending, and the value at each. */
    public static final class Curve {
        public String parameter;
        public float[] times;
        public float[] values;
        /** Straight lines between keyframes instead of easing. */
        public boolean linear;

        int index = -1;

        public Curve(String parameter, float[] times, float[] values) {
            if (times.length != values.length || times.length == 0) {
                throw new IllegalArgumentException("a curve needs as many times as values, at least one");
            }
            this.parameter = parameter;
            this.times = times;
            this.values = values;
        }

        /** The curve's value at a time; a looping motion's curve wraps round from its last keyframe to its first. */
        public float sample(float time, float duration, boolean loop) {
            int n = times.length;
            if (n == 1) {
                return values[0];
            }
            if (!loop) {
                if (time <= times[0]) {
                    return values[0];
                }
                if (time >= times[n - 1]) {
                    return values[n - 1];
                }
            }
            // The keyframe span the time falls in, with its neighbours for the easing's slopes.
            int k;
            float t0;
            float t1;
            float v0;
            float v1;
            float before;
            float after;
            float tBefore;
            float tAfter;
            if (loop && (time < times[0] || time >= times[n - 1])) {
                // Between the last keyframe and the first, round the loop.
                k = n - 1;
                t0 = times[n - 1];
                t1 = times[0] + duration;
                if (time < times[0]) {
                    time += duration;
                }
                v0 = values[n - 1];
                v1 = values[0];
                before = values[n - 2];
                tBefore = times[n - 2];
                after = values[1 % n];
                tAfter = times[1 % n] + duration;
            } else {
                k = 0;
                while (k < n - 2 && time >= times[k + 1]) {
                    k++;
                }
                t0 = times[k];
                t1 = times[k + 1];
                v0 = values[k];
                v1 = values[k + 1];
                if (k > 0) {
                    before = values[k - 1];
                    tBefore = times[k - 1];
                } else if (loop) {
                    before = values[n - 1];
                    tBefore = times[n - 1] - duration;
                } else {
                    before = v0;
                    tBefore = t0 - 1f;
                }
                if (k + 2 < n) {
                    after = values[k + 2];
                    tAfter = times[k + 2];
                } else if (loop) {
                    after = values[0];
                    tAfter = times[0] + duration;
                } else {
                    after = v1;
                    tAfter = t1 + 1f;
                }
            }
            float span = Math.max(1e-5f, t1 - t0);
            float s = Math.max(0f, Math.min(1f, (time - t0) / span));
            if (linear) {
                return v0 + (v1 - v0) * s;
            }
            // Cubic Hermite with Catmull-Rom slopes; a keyframe that is a peak or a trough gets a flat slope, so the
            // curve never overshoots past it.
            float m0 = slope(before, v0, v1, t0 - tBefore, span) * span;
            float m1 = slope(v0, v1, after, span, tAfter - t1) * span;
            float s2 = s * s;
            float s3 = s2 * s;
            return (2f * s3 - 3f * s2 + 1f) * v0 + (s3 - 2f * s2 + s) * m0 + (-2f * s3 + 3f * s2) * v1 + (s3 - s2) * m1;
        }

        private static float slope(float a, float b, float c, float dtA, float dtC) {
            if ((b - a) * (c - b) <= 0f) {
                return 0f;
            }
            return (c - a) / Math.max(1e-5f, dtA + dtC);
        }
    }

    public String id;
    public float duration;
    public boolean loop;
    public float fadeIn = 0.15f;
    public float fadeOut = 0.25f;
    public final List<Curve> curves = new ArrayList<>();

    public RigMotion(String id, float duration, boolean loop) {
        this.id = id;
        this.duration = Math.max(0.01f, duration);
        this.loop = loop;
    }

    public RigMotion curve(String parameter, float[] times, float[] values) {
        curves.add(new Curve(parameter, times, values));
        return this;
    }

    public Curve curve(String parameter) {
        for (Curve curve : curves) {
            if (curve.parameter.equals(parameter)) {
                return curve;
            }
        }
        return null;
    }

    public RigMotion copy() {
        RigMotion copy = new RigMotion(id, duration, loop);
        copy.fadeIn = fadeIn;
        copy.fadeOut = fadeOut;
        for (Curve curve : curves) {
            Curve c = new Curve(curve.parameter, curve.times.clone(), curve.values.clone());
            c.linear = curve.linear;
            copy.curves.add(c);
        }
        return copy;
    }
}
