package org.zeronura.nulzs.nodes;

import org.zeronura.nulzs.nodes.tex.ColorMath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A Color Ramp's stops and how it blends between them, evaluated as Blender evaluates a colour band.
 *
 * <p>Constant ramps are what toon materials are built from, so the edges here follow Blender exactly: a value
 * landing on a stop takes that stop's colour, everything before the first stop takes the first colour and
 * everything after the last the last.
 */
public final class ColorRamp {

    public enum Interpolation { CONSTANT, LINEAR, EASE, B_SPLINE, CARDINAL }

    public record Stop(float position, float[] color) {
    }

    public final Interpolation interpolation;
    /** RGB, HSV or HSL: the space the blend happens in. */
    public final String colorMode;
    /** NEAR, FAR, CW or CCW: which way round the hue wheel an HSV or HSL blend goes. */
    public final String hueInterpolation;
    public final List<Stop> stops;

    public ColorRamp(Interpolation interpolation, String colorMode, String hueInterpolation, List<Stop> stops) {
        this.interpolation = interpolation;
        this.colorMode = colorMode;
        this.hueInterpolation = hueInterpolation;
        List<Stop> sorted = new ArrayList<>(stops);
        sorted.sort(Comparator.comparingDouble(Stop::position));
        this.stops = List.copyOf(sorted);
    }

    @SuppressWarnings("unchecked")
    static ColorRamp fromJson(Map<String, Object> json) {
        if (json == null) {
            return null;
        }
        List<Stop> stops = new ArrayList<>();
        for (Object entry : (List<Object>) json.getOrDefault("stops", List.of())) {
            Map<String, Object> stop = (Map<String, Object>) entry;
            stops.add(new Stop(((Number) stop.get("position")).floatValue(),
                    NodeValues.toVector(NodeValues.fromJson(stop.get("color")), 4)));
        }
        Interpolation interpolation;
        try {
            interpolation = Interpolation.valueOf(String.valueOf(json.getOrDefault("interpolation", "LINEAR")));
        } catch (IllegalArgumentException unknown) {
            interpolation = Interpolation.LINEAR;
        }
        return new ColorRamp(interpolation, String.valueOf(json.getOrDefault("color_mode", "RGB")),
                String.valueOf(json.getOrDefault("hue_interpolation", "NEAR")), stops);
    }

    /** Whether the ramp can be written as straight-line segments and steps (everything but the splines in RGB). */
    public boolean isPiecewiseLinear() {
        return "RGB".equals(colorMode)
                && (interpolation == Interpolation.CONSTANT || interpolation == Interpolation.LINEAR
                || interpolation == Interpolation.EASE);
    }

    /** The colour at {@code in}, into {@code out} (RGBA). */
    public float[] evaluate(float in, float[] out) {
        int count = stops.size();
        if (count == 0) {
            out[0] = out[1] = out[2] = 0f;
            out[3] = 1f;
            return out;
        }
        Interpolation ipo = "RGB".equals(colorMode) ? interpolation : Interpolation.LINEAR;
        boolean simple = ipo == Interpolation.LINEAR || ipo == Interpolation.EASE || ipo == Interpolation.CONSTANT;
        Stop first = stops.get(0);
        if (count == 1 || (in <= first.position && simple)) {
            return copy(first.color, out);
        }
        int a = 0;
        while (a < count && stops.get(a).position <= in) {
            a++;
        }
        Stop right;
        Stop left;
        if (a == count) {
            left = stops.get(count - 1);
            right = new Stop(1f, left.color);
        } else if (a == 0) {
            right = stops.get(0);
            left = new Stop(0f, right.color);
        } else {
            right = stops.get(a);
            left = stops.get(a - 1);
        }
        if ((a == count && simple) || ipo == Interpolation.CONSTANT) {
            return copy(left.color, out);
        }
        float fac;
        if (left.position != right.position) {
            fac = (in - right.position) / (left.position - right.position);
        } else {
            fac = a != count ? 0f : 1f;
        }
        if (ipo == Interpolation.B_SPLINE || ipo == Interpolation.CARDINAL) {
            Stop before = a >= count - 1 ? right : stops.get(a + 1);
            Stop after = a < 2 ? left : stops.get(a - 2);
            fac = Math.clamp(fac, 0f, 1f);
            float[] t = new float[4];
            curveWeights(fac, t, ipo == Interpolation.CARDINAL);
            for (int c = 0; c < 4; c++) {
                out[c] = Math.clamp(t[3] * after.color[c] + t[2] * left.color[c] + t[1] * right.color[c]
                        + t[0] * before.color[c], 0f, 1f);
            }
            return out;
        }
        if (ipo == Interpolation.EASE) {
            float f2 = fac * fac;
            fac = 3f * f2 - 2f * f2 * fac;
        }
        float mfac = 1f - fac;
        if ("HSV".equals(colorMode) || "HSL".equals(colorMode)) {
            boolean hsv = "HSV".equals(colorMode);
            float[] c1 = hsv ? ColorMath.rgbToHsv(right.color, new float[3]) : ColorMath.rgbToHsl(right.color, new float[3]);
            float[] c2 = hsv ? ColorMath.rgbToHsv(left.color, new float[3]) : ColorMath.rgbToHsl(left.color, new float[3]);
            float[] mixed = {hueInterp(mfac, fac, c1[0], c2[0]), mfac * c1[1] + fac * c2[1], mfac * c1[2] + fac * c2[2]};
            float[] rgb = hsv ? ColorMath.hsvToRgb(mixed, new float[3]) : ColorMath.hslToRgb(mixed, new float[3]);
            out[0] = rgb[0];
            out[1] = rgb[1];
            out[2] = rgb[2];
            out[3] = mfac * right.color[3] + fac * left.color[3];
            return out;
        }
        for (int c = 0; c < 4; c++) {
            out[c] = mfac * right.color[c] + fac * left.color[c];
        }
        return out;
    }

    private float hueInterp(float mfac, float fac, float h1, float h2) {
        float h1Fixed = h1;
        float h2Fixed = h2;
        switch (hueInterpolation) {
            case "NEAR" -> {
                if (h1 < h2 && h2 - h1 > 0.5f) {
                    h1Fixed += 1f;
                } else if (h1 > h2 && h2 - h1 < -0.5f) {
                    h2Fixed += 1f;
                }
            }
            case "FAR" -> {
                if (h1 < h2 && h2 - h1 < 0.5f) {
                    h1Fixed += 1f;
                } else if (h1 > h2 && h2 - h1 > -0.5f) {
                    h2Fixed += 1f;
                }
            }
            case "CCW" -> {
                if (h1 > h2) {
                    h2Fixed += 1f;
                }
            }
            case "CW" -> {
                if (h1 < h2) {
                    h1Fixed += 1f;
                }
            }
            default -> {
            }
        }
        float mixed = mfac * h1Fixed + fac * h2Fixed;
        return mixed - (float) Math.floor(mixed);
    }

    /** Blender's four-point key weights, B-spline or cardinal, for {@code t} in [0, 1]. */
    static void curveWeights(float t, float[] data, boolean cardinal) {
        float t2 = t * t;
        float t3 = t2 * t;
        if (cardinal) {
            float fc = 0.71f;
            data[0] = -fc * t3 + 2f * fc * t2 - fc * t;
            data[1] = (2f - fc) * t3 + (fc - 3f) * t2 + 1f;
            data[2] = (fc - 2f) * t3 + (3f - 2f * fc) * t2 + fc * t;
            data[3] = fc * t3 - fc * t2;
        } else {
            data[0] = -0.16666666f * t3 + 0.5f * t2 - 0.5f * t + 0.16666666f;
            data[1] = 0.5f * t3 - t2 + 0.66666666f;
            data[2] = -0.5f * t3 + 0.5f * t2 + 0.5f * t + 0.16666666f;
            data[3] = 0.16666666f * t3;
        }
    }

    private static float[] copy(float[] color, float[] out) {
        System.arraycopy(color, 0, out, 0, 4);
        return out;
    }
}
