package org.zeronura.nulzs.graphics2d;

import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * An immutable RGBA colour with components in [0, 1].
 *
 * <p>Immutable so constants like {@link #WHITE} can be shared safely — a mutable shared white is how one
 * sprite's flash tint ends up on every sprite in the game.
 */
public record Color(float r, float g, float b, float a) {

    public static final Color WHITE = new Color(1f, 1f, 1f, 1f);
    public static final Color BLACK = new Color(0f, 0f, 0f, 1f);
    public static final Color CLEAR = new Color(0f, 0f, 0f, 0f);
    public static final Color GRAY = new Color(0.5f, 0.5f, 0.5f, 1f);
    public static final Color LIGHT_GRAY = new Color(0.75f, 0.75f, 0.75f, 1f);
    public static final Color DARK_GRAY = new Color(0.25f, 0.25f, 0.25f, 1f);
    public static final Color RED = rgb(0xE5484D);
    public static final Color GREEN = rgb(0x46A758);
    public static final Color BLUE = rgb(0x3E63DD);
    public static final Color YELLOW = rgb(0xF5D90A);
    public static final Color ORANGE = rgb(0xF76808);
    public static final Color PURPLE = rgb(0x8E4EC6);
    public static final Color CYAN = rgb(0x05A2C2);
    public static final Color PINK = rgb(0xD6409F);
    public static final Color BROWN = rgb(0x8A5A3B);

    public Color(float r, float g, float b) {
        this(r, g, b, 1f);
    }

    /** From 0xRRGGBB, opaque. */
    public static Color rgb(int hex) {
        return new Color(((hex >> 16) & 0xFF) / 255f, ((hex >> 8) & 0xFF) / 255f, (hex & 0xFF) / 255f, 1f);
    }

    /** From 0xRRGGBBAA. */
    public static Color rgba(int hex) {
        return new Color(((hex >>> 24) & 0xFF) / 255f, ((hex >> 16) & 0xFF) / 255f,
                ((hex >> 8) & 0xFF) / 255f, (hex & 0xFF) / 255f);
    }

    public Color withAlpha(float alpha) {
        return new Color(r, g, b, alpha);
    }

    /** Multiplies the colour channels, keeping alpha. Above 1 brightens. */
    public Color scaled(float factor) {
        return new Color(r * factor, g * factor, b * factor, a);
    }

    public Color lerp(Color other, float t) {
        return new Color(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t, a + (other.a - a) * t);
    }

    public Vector4f toVector4f(Vector4f dest) {
        return dest.set(r, g, b, a);
    }

    public Vector4f toVector4f() {
        return new Vector4f(r, g, b, a);
    }

    public Vector3f toVector3f() {
        return new Vector3f(r, g, b);
    }
}
