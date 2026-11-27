package org.zeronura.engine.render;

/**
 * Paints every {@link ParticleShape} into one texture, in code, with no asset to load: alpha is the outline and the
 * colour channels a grey shading that the particle's own colour multiplies. {@link ParticleShape#DOT} is plain white,
 * so a particle that only ever was a tinted dot looks as it did.
 *
 * <p>Every shape stays clear of its cell's border, so smooth filtering never brings in a neighbour.
 */
final class ParticleAtlas {

    static final int CELL = 64;
    static final int COLUMNS = 4;
    static final int ROWS = 2;
    static final int WIDTH = CELL * COLUMNS;
    static final int HEIGHT = CELL * ROWS;

    /** How far in from a cell's border its texture coordinates stop, in pixels. */
    private static final float INSET = 0.5f;

    private ParticleAtlas() {
    }

    static float u0(ParticleShape shape) {
        return (column(shape) * CELL + INSET) / WIDTH;
    }

    static float u1(ParticleShape shape) {
        return ((column(shape) + 1) * CELL - INSET) / WIDTH;
    }

    static float v0(ParticleShape shape) {
        return (row(shape) * CELL + INSET) / HEIGHT;
    }

    static float v1(ParticleShape shape) {
        return ((row(shape) + 1) * CELL - INSET) / HEIGHT;
    }

    private static int column(ParticleShape shape) {
        return shape.ordinal() % COLUMNS;
    }

    private static int row(ParticleShape shape) {
        return shape.ordinal() / COLUMNS;
    }

    /** The texture's pixels, RGBA, the bottom row first: {@code WIDTH * HEIGHT * 4} bytes. */
    static byte[] bake() {
        byte[] pixels = new byte[WIDTH * HEIGHT * 4];
        float[] out = new float[2];
        for (ParticleShape shape : ParticleShape.values()) {
            int left = column(shape) * CELL;
            int bottom = row(shape) * CELL;
            float centre = (CELL - 1) * 0.5f;
            for (int j = 0; j < CELL; j++) {
                for (int i = 0; i < CELL; i++) {
                    paint(shape, (i - centre) / centre, (j - centre) / centre, out);
                    int at = ((bottom + j) * WIDTH + left + i) * 4;
                    byte grey = (byte) Math.round(Math.clamp(out[0], 0f, 1f) * 255f);
                    pixels[at] = grey;
                    pixels[at + 1] = grey;
                    pixels[at + 2] = grey;
                    pixels[at + 3] = (byte) Math.round(Math.clamp(out[1], 0f, 1f) * 255f);
                }
            }
        }
        return pixels;
    }

    /** One point of a shape, x and y from -1 to 1 across its cell, y up: {@code out} takes its shade and its alpha. */
    private static void paint(ParticleShape shape, float x, float y, float[] out) {
        float r = (float) Math.sqrt(x * x + y * y);
        out[0] = 1f;
        switch (shape) {
            case DOT -> {
                float a = Math.clamp(1f - r, 0f, 1f);
                out[1] = a * a;
            }
            case LEAF -> {
                float along = y / 0.92f;
                float half = 0.46f * (float) Math.pow(Math.max(0f, 1f - along * along), 0.75f);
                out[1] = Math.abs(along) >= 1f ? 0f : edge(half - Math.abs(x));
                // The midrib, and one side of it a shade darker.
                out[0] = Math.abs(x) < 0.05f ? 0.68f : x > 0f ? 0.86f : 1f;
            }
            case PETAL -> {
                float along = (y - 0.08f) / 0.82f;
                float width = 0.60f * (float) Math.sqrt(Math.max(0f, 1f - along * along)) * (0.45f + 0.55f * (y + 0.74f) / 1.64f);
                out[1] = Math.abs(along) >= 1f ? 0f : edge(width - Math.abs(x));
                out[0] = 0.80f + 0.20f * Math.clamp((y + 0.74f) / 1.64f, 0f, 1f);
            }
            case BUTTERFLY -> paintButterfly(x, y, out);
            case SPARKLE -> {
                float star = 1f - ((float) Math.sqrt(Math.abs(x)) + (float) Math.sqrt(Math.abs(y))) * 1.02f;
                out[1] = Math.clamp(star * 3.2f, 0f, 1f);
            }
            case PUFF -> {
                double angle = Math.atan2(y, x);
                float rim = 0.74f + 0.09f * (float) Math.sin(3.0 * angle + 1.0) + 0.06f * (float) Math.sin(5.0 * angle + 2.3);
                float a = Math.clamp((rim - r) / 0.55f, 0f, 1f);
                out[1] = a * a * (3f - 2f * a);
            }
            case RING -> out[1] = edge(0.09f - Math.abs(r - 0.80f));
            case BIRD -> {
                float across = Math.min(1f, Math.abs(x) / 0.94f);
                float line = 0.30f * (float) Math.sin(Math.PI * across) - 0.14f * across - 0.04f;
                float thickness = 0.016f + 0.10f * (1f - across);
                out[1] = Math.abs(x) >= 0.94f ? 0f : edge(thickness - Math.abs(y - line));
            }
        }
    }

    private static void paintButterfly(float x, float y, float[] out) {
        float side = Math.abs(x);
        float upper = ellipse(side - 0.47f, y - 0.26f, 0.42f, 0.54f);
        float lower = ellipse(side - 0.34f, y + 0.38f, 0.28f, 0.36f);
        boolean body = side < 0.055f && Math.abs(y) < 0.62f;
        out[1] = body ? 1f : Math.max(edge(upper), edge(lower));
        if (body) {
            out[0] = 0.14f;
            return;
        }
        // Dark margins, and a pale spot on each upper wing.
        float margin = Math.max(upper, lower);
        out[0] = margin < 0.09f ? 0.62f : 1f;
        if (ellipse(side - 0.56f, y - 0.32f, 0.12f, 0.15f) > 0f) {
            out[0] = 0.52f;
        }
    }

    /** Above zero inside an ellipse, in about the units of its radii. */
    private static float ellipse(float dx, float dy, float radiusX, float radiusY) {
        float u = dx / radiusX;
        float v = dy / radiusY;
        return (1f - (float) Math.sqrt(u * u + v * v)) * Math.min(radiusX, radiusY);
    }

    /** An outline's alpha from how far inside it a point is, softened over a couple of pixels. */
    private static float edge(float inside) {
        return Math.clamp(inside / 0.05f + 0.5f, 0f, 1f);
    }
}
