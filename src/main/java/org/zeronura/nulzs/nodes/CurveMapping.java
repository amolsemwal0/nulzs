package org.zeronura.nulzs.nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The curves of a Float Curve, RGB Curves or Vector Curves node, as the exporter sampled them: each curve is a
 * table of evenly spaced values across the range its points cover, read back with straight-line steps between
 * samples. Outside that range a curve holds its end value ({@code HORIZONTAL}) or carries on along its end
 * slope ({@code EXTRAPOLATED}), as the curve widget's "Extend" setting says.
 */
public final class CurveMapping {

    public record Curve(float min, float max, float[] values, float slopeIn, float slopeOut) {

        public float evaluate(float x, boolean extrapolate) {
            int n = values.length;
            if (n == 0) {
                return x;
            }
            if (n == 1) {
                return values[0];
            }
            float step = (max - min) / (n - 1);
            if (x <= min) {
                return extrapolate ? values[0] + (x - min) * slopeIn : values[0];
            }
            if (x >= max) {
                return extrapolate ? values[n - 1] + (x - max) * slopeOut : values[n - 1];
            }
            float f = (x - min) / step;
            int i = Math.min((int) f, n - 2);
            float t = f - i;
            return values[i] + (values[i + 1] - values[i]) * t;
        }
    }

    public final List<Curve> curves;
    public final boolean extrapolate;
    /** Whether results are clamped to the widget's clipping range, as Float Curve and Vector Curves clamp them. */
    public final boolean clip;
    public final float clipMinY;
    public final float clipMaxY;
    public final float[] blackLevel;
    public final float[] whiteLevel;

    public CurveMapping(List<Curve> curves, boolean extrapolate, float[] blackLevel, float[] whiteLevel, boolean clip,
                        float clipMinY, float clipMaxY) {
        this.curves = List.copyOf(curves);
        this.extrapolate = extrapolate;
        this.clip = clip;
        this.clipMinY = clipMinY;
        this.clipMaxY = clipMaxY;
        this.blackLevel = blackLevel;
        this.whiteLevel = whiteLevel;
    }

    @SuppressWarnings("unchecked")
    static CurveMapping fromJson(Map<String, Object> json) {
        if (json == null) {
            return null;
        }
        List<Curve> curves = new ArrayList<>();
        for (Object entry : (List<Object>) json.getOrDefault("curves", List.of())) {
            Map<String, Object> curve = (Map<String, Object>) entry;
            float[] values = NodeValues.toVector(NodeValues.fromJson(curve.get("values")),
                    ((List<Object>) curve.get("values")).size());
            int n = values.length;
            float min = ((Number) curve.get("min")).floatValue();
            float max = ((Number) curve.get("max")).floatValue();
            float step = n > 1 ? (max - min) / (n - 1) : 1f;
            float slopeIn = curve.get("slope_in") instanceof Number s ? s.floatValue() : n > 1 ? (values[1] - values[0]) / step : 0f;
            float slopeOut = curve.get("slope_out") instanceof Number s ? s.floatValue()
                    : n > 1 ? (values[n - 1] - values[n - 2]) / step : 0f;
            curves.add(new Curve(min, max, values, slopeIn, slopeOut));
        }
        return new CurveMapping(curves, "EXTRAPOLATED".equals(json.get("extend")),
                NodeValues.toVector(NodeValues.fromJson(json.get("black_level")), 3),
                NodeValues.toVector(NodeValues.fromJson(json.get("white_level")), 3),
                Boolean.TRUE.equals(json.get("use_clip")),
                json.get("clip_min_y") instanceof Number lo ? lo.floatValue() : 0f,
                json.get("clip_max_y") instanceof Number hi ? hi.floatValue() : 1f);
    }

    public float evaluate(int curve, float x) {
        if (curve >= curves.size()) {
            return x;
        }
        return curves.get(curve).evaluate(x, extrapolate);
    }

    /** A curve's value clamped to the clipping range when the widget clips: what Float and Vector Curves give. */
    public float evaluateClipped(int curve, float x) {
        float value = evaluate(curve, x);
        return clip ? Math.clamp(value, clipMinY, clipMaxY) : value;
    }

    /** Whether the black and white levels are the identity, so RGB Curves need not rescale its input. */
    public boolean levelsAreIdentity() {
        for (int i = 0; i < 3; i++) {
            if (blackLevel[i] != 0f || whiteLevel[i] != 1f) {
                return false;
            }
        }
        return true;
    }
}
