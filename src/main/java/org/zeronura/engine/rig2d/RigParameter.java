package org.zeronura.engine.rig2d;

/**
 * A named value a rig is posed by — how open the mouth is, how far the body leans — kept between {@code min} and
 * {@code max}, resting at {@code defaultValue}. Keyforms are stored at chosen values of parameters; motions and
 * physics move them.
 */
public record RigParameter(String id, float min, float max, float defaultValue) {

    public RigParameter {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a parameter needs an id");
        }
        if (max < min) {
            float swap = max;
            max = min;
            min = swap;
        }
        defaultValue = Math.max(min, Math.min(max, defaultValue));
    }

    public float clamp(float value) {
        return Math.max(min, Math.min(max, value));
    }

    /** The value as -1..1 about the default: -1 at min, 0 at the default, 1 at max. */
    public float normalized(float value) {
        if (value >= defaultValue) {
            return max > defaultValue ? (value - defaultValue) / (max - defaultValue) : 0f;
        }
        return defaultValue > min ? (value - defaultValue) / (defaultValue - min) : 0f;
    }

    /** The inverse of {@link #normalized}. */
    public float fromNormalized(float n) {
        return n >= 0f ? defaultValue + n * (max - defaultValue) : defaultValue + n * (defaultValue - min);
    }
}
