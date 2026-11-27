package org.zeronura.engine.nodes.tex;

/**
 * Colour maths shared by the node implementations: HSV and HSL, the Mix node's blend modes, luminance and the
 * sRGB transfer curve. Colours are scene-linear RGBA, as Blender's sockets carry them.
 */
public final class ColorMath {

    /** Rec. 709 luminance, the coefficients Blender's default colour management uses. */
    public static final float LUMA_R = 0.2126f;
    public static final float LUMA_G = 0.7152f;
    public static final float LUMA_B = 0.0722f;

    private ColorMath() {
    }

    public static float luminance(float r, float g, float b) {
        return LUMA_R * r + LUMA_G * g + LUMA_B * b;
    }

    public static float[] rgbToHsv(float[] rgb, float[] out) {
        float r = rgb[0], g = rgb[1], b = rgb[2];
        float cmax = Math.max(r, Math.max(g, b));
        float cmin = Math.min(r, Math.min(g, b));
        float cdelta = cmax - cmin;
        float v = cmax;
        float s = cmax != 0f ? cdelta / cmax : 0f;
        float h = 0f;
        if (s != 0f) {
            float cr = (cmax - r) / cdelta;
            float cg = (cmax - g) / cdelta;
            float cb = (cmax - b) / cdelta;
            if (r == cmax) {
                h = cb - cg;
            } else if (g == cmax) {
                h = 2f + cr - cb;
            } else {
                h = 4f + cg - cr;
            }
            h /= 6f;
            if (h < 0f) {
                h += 1f;
            }
        }
        out[0] = h;
        out[1] = s;
        out[2] = v;
        return out;
    }

    public static float[] hsvToRgb(float[] hsv, float[] out) {
        float h = hsv[0], s = hsv[1], v = hsv[2];
        if (s == 0f) {
            out[0] = out[1] = out[2] = v;
            return out;
        }
        if (h == 1f) {
            h = 0f;
        }
        h *= 6f;
        float i = (float) Math.floor(h);
        float f = h - i;
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));
        int sector = (int) i;
        switch (sector) {
            case 0 -> set(out, v, t, p);
            case 1 -> set(out, q, v, p);
            case 2 -> set(out, p, v, t);
            case 3 -> set(out, p, q, v);
            case 4 -> set(out, t, p, v);
            default -> set(out, v, p, q);
        }
        return out;
    }

    public static float[] rgbToHsl(float[] rgb, float[] out) {
        float r = rgb[0], g = rgb[1], b = rgb[2];
        float cmax = Math.max(r, Math.max(g, b));
        float cmin = Math.min(r, Math.min(g, b));
        float h, s, l = Math.min(1f, (cmax + cmin) / 2f);
        if (cmax == cmin) {
            h = s = 0f;
        } else {
            float d = cmax - cmin;
            s = l > 0.5f ? d / (2f - cmax - cmin) : d / (cmax + cmin);
            if (cmax == r) {
                h = (g - b) / d + (g < b ? 6f : 0f);
            } else if (cmax == g) {
                h = (b - r) / d + 2f;
            } else {
                h = (r - g) / d + 4f;
            }
        }
        out[0] = h / 6f;
        out[1] = s;
        out[2] = l;
        return out;
    }

    public static float[] hslToRgb(float[] hsl, float[] out) {
        float h = hsl[0], s = hsl[1], l = hsl[2];
        float nr = Math.abs(h * 6f - 3f) - 1f;
        float ng = 2f - Math.abs(h * 6f - 2f);
        float nb = 2f - Math.abs(h * 6f - 4f);
        nr = Math.clamp(nr, 0f, 1f);
        ng = Math.clamp(ng, 0f, 1f);
        nb = Math.clamp(nb, 0f, 1f);
        float chroma = (1f - Math.abs(2f * l - 1f)) * s;
        out[0] = (nr - 0.5f) * chroma + l;
        out[1] = (ng - 0.5f) * chroma + l;
        out[2] = (nb - 0.5f) * chroma + l;
        return out;
    }

    private static void set(float[] out, float r, float g, float b) {
        out[0] = r;
        out[1] = g;
        out[2] = b;
    }

    public static float srgbToLinear(float c) {
        if (c < 0.04045f) {
            return c < 0f ? 0f : c * (1f / 12.92f);
        }
        return (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    public static float linearToSrgb(float c) {
        if (c < 0.0031308f) {
            return c < 0f ? 0f : c * 12.92f;
        }
        return 1.055f * (float) Math.pow(c, 1f / 2.4f) - 0.055f;
    }

    /**
     * The Mix node's colour blend, as Blender's {@code ramp_blend}: {@code a} is the bottom colour, {@code b} the
     * one blended over it at {@code fac}. Alpha is {@code a}'s, as Blender keeps it. Writes into {@code out}.
     */
    public static float[] blend(String mode, float fac, float[] a, float[] b, float[] out) {
        float facm = 1f - fac;
        float[] hsv = new float[3];
        float[] tmp = new float[3];
        switch (mode) {
            case "ADD" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = a[c] + fac * b[c];
                }
            }
            case "MULTIPLY" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = a[c] * (facm + fac * b[c]);
                }
            }
            case "SUBTRACT" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = a[c] - fac * b[c];
                }
            }
            case "SCREEN" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = 1f - (facm + fac * (1f - b[c])) * (1f - a[c]);
                }
            }
            case "DIVIDE" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = b[c] != 0f ? facm * a[c] + fac * a[c] / b[c] : a[c];
                }
            }
            case "DIFFERENCE" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = facm * a[c] + fac * Math.abs(a[c] - b[c]);
                }
            }
            case "EXCLUSION" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = Math.max(facm * a[c] + fac * (a[c] + b[c] - 2f * a[c] * b[c]), 0f);
                }
            }
            case "DARKEN" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = a[c] + (Math.min(a[c], b[c]) - a[c]) * fac;
                }
            }
            case "LIGHTEN" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = a[c] + (Math.max(a[c], b[c]) - a[c]) * fac;
                }
            }
            case "OVERLAY" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = a[c] < 0.5f ? a[c] * (facm + 2f * fac * b[c])
                            : 1f - (facm + 2f * fac * (1f - b[c])) * (1f - a[c]);
                }
            }
            case "DODGE" -> {
                for (int c = 0; c < 3; c++) {
                    float v = a[c];
                    if (v != 0f) {
                        float t = 1f - fac * b[c];
                        v = t <= 0f ? 1f : Math.min(v / t, 1f);
                    }
                    out[c] = v;
                }
            }
            case "BURN" -> {
                for (int c = 0; c < 3; c++) {
                    float t = facm + fac * b[c];
                    out[c] = t <= 0f ? 0f : Math.clamp(1f - (1f - a[c]) / t, 0f, 1f);
                }
            }
            case "HUE" -> {
                copy3(a, out);
                rgbToHsv(b, hsv);
                if (hsv[1] != 0f) {
                    float[] ha = rgbToHsv(a, new float[3]);
                    ha[0] = hsv[0];
                    hsvToRgb(ha, tmp);
                    for (int c = 0; c < 3; c++) {
                        out[c] = facm * a[c] + fac * tmp[c];
                    }
                }
            }
            case "SATURATION" -> {
                copy3(a, out);
                float[] ha = rgbToHsv(a, new float[3]);
                if (ha[1] != 0f) {
                    rgbToHsv(b, hsv);
                    ha[1] = facm * ha[1] + fac * hsv[1];
                    hsvToRgb(ha, out);
                }
            }
            case "VALUE" -> {
                float[] ha = rgbToHsv(a, new float[3]);
                rgbToHsv(b, hsv);
                ha[2] = facm * ha[2] + fac * hsv[2];
                hsvToRgb(ha, out);
            }
            case "COLOR" -> {
                copy3(a, out);
                rgbToHsv(b, hsv);
                if (hsv[1] != 0f) {
                    float[] ha = rgbToHsv(a, new float[3]);
                    ha[0] = hsv[0];
                    ha[1] = hsv[1];
                    hsvToRgb(ha, tmp);
                    for (int c = 0; c < 3; c++) {
                        out[c] = facm * a[c] + fac * tmp[c];
                    }
                }
            }
            case "SOFT_LIGHT" -> {
                for (int c = 0; c < 3; c++) {
                    float scr = 1f - (1f - b[c]) * (1f - a[c]);
                    out[c] = facm * a[c] + fac * ((1f - a[c]) * b[c] * a[c] + a[c] * scr);
                }
            }
            case "LINEAR_LIGHT" -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = a[c] + fac * (2f * b[c] - 1f);
                }
            }
            default -> {
                for (int c = 0; c < 3; c++) {
                    out[c] = facm * a[c] + fac * b[c];
                }
            }
        }
        out[3] = a.length > 3 ? a[3] : 1f;
        return out;
    }

    private static void copy3(float[] from, float[] to) {
        to[0] = from[0];
        to[1] = from[1];
        to[2] = from[2];
    }
}
