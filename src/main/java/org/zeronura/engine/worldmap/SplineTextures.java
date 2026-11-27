package org.zeronura.engine.worldmap;

/**
 * What {@link SplineMeshes} needs to know about the textures splines wear, beyond their names: the texture each kind
 * wears where a spline names none, which textures there are (for a road's junction pieces, {@link
 * SplineMeshes#familyMember}), and where across a road's picture it is plain — no lines, no verge — for a junction to
 * be laid in, and where its verges are, for a junction's corners to bend them round. Plain data; {@link SplineMaterials}
 * answers from a game's assets.
 */
public interface SplineTextures {

    /** The texture a spline of a kind wears when it names none. */
    String defaultTexture(MapSpline.Kind kind);

    /** Whether there is such a texture. */
    boolean exists(String texture);

    /**
     * The widest stretch across a texture's picture that is plain — the asphalt between a road's lines, the dirt between
     * a path's verges — as {from, to} between 0 and 1, or null when it isn't known. Across is down the picture, or along
     * it for a picture that is {@code turned}.
     */
    default float[] plainBand(String texture, boolean turned) {
        return null;
    }

    /**
     * Where a road's picture changes, from each side, from its verge — the grass along a path, a road's painted edge line
     * and what lies outside it — to plain: {@code {left, leftPlain, rightPlain, right}} between 0 and 1. The left verge
     * runs from 0 to {@code left}, the plain beside it from there to {@code leftPlain}; the right verge from
     * {@code right} to 1, the plain beside it from {@code rightPlain} to there. A side with no verge has {@code left} 0 or
     * {@code right} 1. Null when it isn't known. Across as for {@link #plainBand}.
     */
    default float[] verges(String texture, boolean turned) {
        return null;
    }

    /** Only defaults: no other textures known, nothing known about them. */
    static SplineTextures defaults(java.util.function.Function<MapSpline.Kind, String> defaults) {
        return new SplineTextures() {
            @Override
            public String defaultTexture(MapSpline.Kind kind) {
                return defaults.apply(kind);
            }

            @Override
            public boolean exists(String texture) {
                return false;
            }
        };
    }
}
