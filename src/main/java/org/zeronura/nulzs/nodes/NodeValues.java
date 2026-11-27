package org.zeronura.nulzs.nodes;

import java.util.List;
import java.util.Map;

/** Turns the plain values a package stores (numbers, flags, lists, references) into what nodes use. */
public final class NodeValues {

    private NodeValues() {
    }

    /** A value read from the package's JSON: lists of numbers become float[], references become {@link Ref}. */
    @SuppressWarnings("unchecked")
    public static Object fromJson(Object raw) {
        if (raw instanceof List<?> list) {
            boolean numeric = true;
            for (Object item : list) {
                if (!(item instanceof Number)) {
                    numeric = false;
                    break;
                }
            }
            if (numeric) {
                float[] out = new float[list.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = ((Number) list.get(i)).floatValue();
                }
                return out;
            }
            return list;
        }
        if (raw instanceof Map<?, ?> map && map.get("ref") instanceof String name) {
            Object kind = ((Map<String, Object>) map).get("kind");
            return new Ref(name, kind == null ? "ID" : kind.toString());
        }
        return raw;
    }

    public static float toFloat(Object value) {
        if (value instanceof Number n) {
            return n.floatValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1f : 0f;
        }
        if (value instanceof float[] v && v.length > 0) {
            if (v.length >= 3) {
                return (v[0] + v[1] + v[2]) / 3f;
            }
            return v[0];
        }
        return 0f;
    }

    public static boolean toBool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return toFloat(value) > 0f;
    }

    public static float[] toVector(Object value, int size) {
        float[] out = new float[size];
        if (value instanceof float[] v) {
            for (int i = 0; i < size; i++) {
                out[i] = i < v.length ? v[i] : (i == 3 ? 1f : 0f);
            }
        } else {
            float f = toFloat(value);
            for (int i = 0; i < size; i++) {
                out[i] = i == 3 ? 1f : f;
            }
        }
        return out;
    }

    public static String toText(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Ref ref) {
            return ref.name();
        }
        return value.toString();
    }
}
