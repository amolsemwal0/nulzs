package org.zeronura.engine.rig2d;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics2d.SpriteBatch;

/**
 * Draws a posed rig with a {@link SpriteBatch}: every art mesh, back to front, as textured triangles from the rig's
 * {@link RigAtlas}. The canvas is placed by an affine map — {@code worldX = a*x + b*y + c}, {@code worldY = d*x + e*y + f}
 * from canvas pixels — or, more simply, by where its pivot stands, how big a canvas pixel is and whether it is flipped.
 */
public final class RigRenderer {

    private RigRenderer() {
    }

    /**
     * Draws the rig standing with its pivot at (x, y) in a y-up world, {@code scale} world units per canvas pixel,
     * mirrored left to right when {@code flipX}.
     */
    public static void draw(SpriteBatch batch, RigInstance rig, float x, float y, float scale, boolean flipX, Color tint) {
        draw(batch, rig, x, y, scale, scale, flipX, tint);
    }

    /** As {@link #draw(SpriteBatch, RigInstance, float, float, float, boolean, Color)}, stretched apart in x and y. */
    public static void draw(SpriteBatch batch, RigInstance rig, float x, float y, float scaleX, float scaleY,
                            boolean flipX, Color tint) {
        RigModel model = rig.model();
        float sx = flipX ? -scaleX : scaleX;
        draw(batch, rig, sx, 0f, x - model.pivotX * sx, 0f, -scaleY, y + model.pivotY * scaleY, tint);
    }

    /** Draws the rig through an affine map from canvas pixels; {@code tint} multiplies every mesh, its alpha fading. */
    public static void draw(SpriteBatch batch, RigInstance rig, float a, float b, float c, float d, float e, float f,
                            Color tint) {
        RigModel model = rig.model();
        if (model.meshes.isEmpty()) {
            return;
        }
        RigAtlas atlas = RigAtlas.of(model);
        boolean wasPremultiplied = batch.isPremultipliedAlpha();
        batch.setPremultipliedAlpha(true);
        Color t = tint == null ? Color.WHITE : tint;
        int[] order = rig.drawOrder();
        for (int index : order) {
            if (index >= model.meshes.size()) {
                continue;
            }
            ArtMesh mesh = model.meshes.get(index);
            float opacity = rig.opacity(index) * t.a();
            if (opacity <= 0.002f) {
                continue;
            }
            float[] uv = atlas.uvs(mesh);
            if (uv == null) {
                continue;
            }
            float[] p = rig.positions(index);
            // Premultiplied: colour scaled by opacity; a glow keeps no alpha, so it only ever adds light.
            Color color = new Color(t.r() * opacity, t.g() * opacity, t.b() * opacity,
                    atlas.additive(mesh) ? 0f : opacity);
            int[] tris = mesh.triangles;
            for (int i = 0; i + 2 < tris.length; i += 3) {
                int i0 = tris[i] * 2;
                int i1 = tris[i + 1] * 2;
                int i2 = tris[i + 2] * 2;
                if (i0 + 1 >= p.length || i1 + 1 >= p.length || i2 + 1 >= p.length) {
                    continue;
                }
                float x0 = a * p[i0] + b * p[i0 + 1] + c;
                float y0 = d * p[i0] + e * p[i0 + 1] + f;
                float x1 = a * p[i1] + b * p[i1 + 1] + c;
                float y1 = d * p[i1] + e * p[i1 + 1] + f;
                float x2 = a * p[i2] + b * p[i2 + 1] + c;
                float y2 = d * p[i2] + e * p[i2 + 1] + f;
                // A triangle is a quad with its last corner doubled up: the batch's second triangle has no area.
                batch.quad(atlas.texture(), x0, y0, uv[i0], uv[i0 + 1], x1, y1, uv[i1], uv[i1 + 1],
                        x2, y2, uv[i2], uv[i2 + 1], x2, y2, uv[i2], uv[i2 + 1], color);
            }
        }
        batch.setPremultipliedAlpha(wasPremultiplied);
    }
}
