package org.zeronura.nulzs.ui;

import org.joml.Vector2f;
import org.zeronura.nulzs.render.Texture;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.zeronura.nulzs.render.Shader;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Immediate-mode 2D drawing in screen pixels: the substrate every HUD widget is built from.
 *
 * <p>Widgets call {@link #rect}/{@link #text} in draw order between {@link #begin} and {@link #end};
 * everything accumulates into one buffer and goes to the GPU in as few draws as the ordering allows.
 * Immediate mode rather than a retained widget tree because HUD content changes every frame anyway
 * (health, XP, a minimap that scrolls) — a retained tree would be rebuilt each frame and buy nothing.
 *
 * <h2>Coordinates</h2>
 * Pixels, origin top-left, y down — the convention UI layout is written in. The conversion to NDC is a
 * two-line job in {@code ui.vert}, so no widget ever deals with aspect ratio. Contrast {@code Crosshair},
 * which works directly in NDC and has to divide by the aspect ratio itself; that is fine for one
 * centred shape and unbearable for a panel of text.
 *
 * <h2>Batching and ordering</h2>
 * Solid quads and glyphs need different fragment paths, so the batch is flushed whenever the mode
 * changes. Sorting all quads by mode into two draws would be fewer calls but would put every panel
 * background <i>on top of</i> the text drawn before it. Draw order is the one thing a UI cannot get
 * wrong, so it is preserved and the flush is accepted; a typical HUD frame is a handful of draws.
 *
 * <h2>GL state</h2>
 * {@link #begin} takes depth testing and face culling off and blending on, and {@link #end} puts all three
 * back — same contract as {@code Skybox}, which is the existing convention here for a self-contained pass.
 */
public class UIRenderer {

    /** Position (2) + UV (2) + colour (4). */
    private static final int FLOATS_PER_VERTEX = 8;
    private static final int VERTICES_PER_QUAD = 6;

    /** Buffer capacity. The minimap alone is ~1700 quads, so this leaves room for it plus a panel. */
    private static final int MAX_QUADS = 4096;
    private static final int MAX_VERTICES = MAX_QUADS * VERTICES_PER_QUAD;

    private final Shader shader;
    /** Each face's atlases, uploaded the first time it is drawn in. */
    private final java.util.Map<Typeface, BitmapFont> fonts = new java.util.EnumMap<>(Typeface.class);
    /** The face whose atlas is bound to unit 0, with {@link #fontTier}. */
    private BitmapFont boundFont;
    private final int vao;
    private final int vbo;
    private final FloatBuffer vertices;

    /**
     * Vertices buffered since the last flush, <b>not</b> quads.
     *
     * <p>Counted in vertices because not everything drawn here is a quad any more: {@link #triangle} exists
     * so a round shape can be a fan rather than a stack of scanned rows, and a quad counter cannot express
     * a batch that holds three of one and six of the other.
     */
    private int vertexCount;
    /** 0 solid, 1 glyph, 2 sprite. Was a boolean until artwork arrived beside the font. */
    private int mode;
    /** Which of the font's size tiers is bound to unit 0 — switching one is a flush, like a sprite. */
    private int fontTier;
    private Texture sprite;
    private boolean drawing;
    private float screenWidth;
    private float screenHeight;
    private float rotationCos = 1f;
    private float rotationSin;
    private float scale = 1f;
    /** Extra horizontal scale before turning, 1 for none: a card part-way through being flipped over. */
    private float squash = 1f;
    private float pivotX;
    private float pivotY;

    /** The clip rectangles in force, innermost on top, each as x0, y0, x1, y1 in layout units; see {@link #pushClip}. */
    private final java.util.ArrayDeque<float[]> clips = new java.util.ArrayDeque<>();
    /** The GL viewport this layer was begun in, which a clip rectangle is converted into pixels against. */
    private final int[] viewport = new int[4];

    public UIRenderer() {
        this.shader = new Shader("/shaders/ui.vert", "/shaders/ui.frag");
        fonts.put(Typeface.BODY, new BitmapFont(Typeface.BODY));
        this.vertices = BufferUtils.createFloatBuffer(MAX_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) vertices.capacity() * Float.BYTES, GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
    }

    public void begin(int framebufferWidth, int framebufferHeight) {
        begin((float) framebufferWidth, (float) framebufferHeight);
    }

    /**
     * Starts a 2D layer whose coordinates run from 0 to {@code width} and {@code height} across the whole viewport,
     * which need not be its size in pixels: the GUI draws a 1080p layout onto any screen this way.
     */
    public void begin(float width, float height) {
        screenWidth = width;
        screenHeight = height;
        drawing = true;
        vertexCount = 0;
        clips.clear();
        glGetIntegerv(GL_VIEWPORT, viewport);
        mode = 0;
        sprite = null;
        resetRotation();

        glDisable(GL_DEPTH_TEST);
        // Culling off, because a 2D layer has no back faces to cull. It went unnoticed while everything
        // here was a quad — pushQuad happens to wind the way the scene passes leave the GL expecting —
        // and the first triangle wound the other way vanished entirely, wedges and hub and pointer at
        // once, while the text over them drew perfectly. Winding is not a property any widget should have
        // to know, so it is taken out of the equation rather than documented.
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.use();
        shader.setUniform("screenSize", new Vector2f(screenWidth, screenHeight));
        shader.setUniform("useTexture", 0);
        shader.setUniform("fontAtlas", 0);
        shader.setUniform("spriteAtlas", 1);
        fontTier = 0;
        boundFont = font(Typeface.BODY);
        boundFont.bind(0, fontTier);
    }

    /** A face's atlases, uploaded on first use. */
    private BitmapFont font(Typeface face) {
        return fonts.computeIfAbsent(face, BitmapFont::new);
    }

    public void end() {
        flush();
        if (!clips.isEmpty()) {
            clips.clear();
            glDisable(GL_SCISSOR_TEST);
        }
        drawing = false;
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    public void rect(float x, float y, float width, float height, Vector4f color) {
        setMode(false);
        if (rotationSin == 0f && scale == 1f && squash == 1f) {
            pushQuad(x, y, width, height, 0f, 0f, 1f, 1f, color);
            return;
        }
        // Turned or scaled, the edges no longer land on pixel boundaries and ui.frag fades them over a
        // pixel instead. That fade straddles the edge, so the quad grows a pixel each way to hold its outer
        // half, with the UVs carried past 0 and 1 to match; without it a thin border comes out thinner.
        float grow = 1f / (scale * Math.max(0.05f, Math.min(1f, squash)));
        float uGrow = width > 0f ? grow / width : 0f;
        float vGrow = height > 0f ? grow / height : 0f;
        pushQuad(x - grow, y - grow, width + 2f * grow, height + 2f * grow,
                -uGrow, -vGrow, 1f + 2f * uGrow, 1f + 2f * vGrow, color);
    }

    /**
     * A solid triangle, in the same pixel coordinates as {@link #rect}.
     *
     * <p>The one shape here that is not axis-aligned, and it exists for round UI: an arc drawn as a fan of
     * these is exact and costs a handful of triangles, where the same curve scanned as rows of rectangles
     * costs one quad per row and can only ever be a staircase. Widgets that <i>are</i> rectangular should
     * still use {@link #rect} — it is the same batch either way, and a rectangle written as two triangles
     * is two chances to get a corner wrong.
     */
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, Vector4f color) {
        setMode(false);
        reserve(3);
        // A constant UV has no gradient, which ui.frag reads as "no edges to fade" — rect's 0..1 UVs would
        // fade a triangle's edges wrongly, since they are not the sides of a box.
        pushVertex(x1, y1, 0.5f, 0.5f, color);
        pushVertex(x2, y2, 0.5f, 0.5f, color);
        pushVertex(x3, y3, 0.5f, 0.5f, color);
        vertexCount += 3;
    }

    /** A hollow rectangle drawn as four thin filled ones — panels and bars all want a border. */
    public void rectOutline(float x, float y, float width, float height, float thickness, Vector4f color) {
        rect(x, y, width, thickness, color);
        rect(x, y + height - thickness, width, thickness, color);
        if (rotationSin == 0f && scale == 1f && squash == 1f) {
            rect(x, y + thickness, thickness, height - 2 * thickness, color);
            rect(x + width - thickness, y + thickness, thickness, height - 2 * thickness, color);
            return;
        }
        // Turned, rect fades every side, so sides that merely met the top and bottom would leave a faint
        // seam across each corner. Run them the full height instead: the overlap is hidden in an opaque
        // border and is only a few pixels in a translucent one.
        rect(x, y, thickness, height, color);
        rect(x + width - thickness, y, thickness, height, color);
    }

    /**
     * Draws a string with the top-left of its <b>capitals</b> at {@code (x, y)}.
     *
     * <p>Capitals rather than the line box, because that is what {@link BitmapFont#heightOf} measures and
     * what every caller positioning text against a row already assumes. Descenders reach below it.
     *
     * <p>Each glyph is placed and sized from its own metrics rather than stepped through a fixed cell:
     * the font is proportional now, so a shared advance would set every letter on the pixel font's grid
     * and read as a monospaced typewriter.
     *
     * @param scale pixels per font unit — 2 gives capitals 14 pixels tall
     * @return the x coordinate just past the last glyph, for chaining runs of differently coloured text
     */
    public float text(String text, float x, float y, float scale, Vector4f color) {
        return text(text, x, y, scale, color, Typeface.BODY);
    }

    /** The same, set in a face of the UI's: see {@link Typeface}. */
    public float text(String text, float x, float y, float scale, Vector4f color, Typeface face) {
        return text(text, x, y, scale, color, color, face);
    }

    /**
     * Text shaded from {@code top} at the top of its letters to {@code bottom} at their foot — a gold logo, say. Each
     * glyph is shaded down its own height, which for a line of capitals is the line's.
     */
    public float text(String text, float x, float y, float scale, Vector4f top, Vector4f bottom, Typeface face) {
        setMode(true);
        BitmapFont font = font(face);
        // Picked from the size on screen, so a card scaled up by setTransform counts as the larger text
        // it is. A title needs a different atlas from body text, or it is a magnified blur.
        int tier = font.tierFor(scale * this.scale);
        if (tier != fontTier || font != boundFont) {
            flush();
            fontTier = tier;
            boundFont = font;
            font.bind(0, tier);
        }
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            GlyphSet.Glyph glyph = font.glyph(tier, c);
            // A blank glyph — the space, and whatever else the face leaves empty — has no pixels to
            // sample, and pushing a zero-area quad for it is a wasted six vertices per space.
            if (glyph.width() > 0f && glyph.height() > 0f) {
                pushQuad(cursor + glyph.xOffset() * scale, y + glyph.yOffset() * scale,
                        glyph.width() * scale, glyph.height() * scale,
                        glyph.u0(), glyph.v0(), glyph.u1() - glyph.u0(), glyph.v1() - glyph.v0(), top, bottom);
            }
            // Stepped by the base tier whatever atlas drew it: that is the advance widthOf measured, so
            // centred text stays centred when a larger tier rounds a glyph a texel differently.
            cursor += font.glyph(c).advance() * scale;
        }
        return cursor;
    }

    /** Draws text ending at {@code rightX} — the layout every right-aligned number wants. */
    public void textRightAligned(String text, float rightX, float y, float scale, Vector4f color) {
        textRightAligned(text, rightX, y, scale, color, Typeface.BODY);
    }

    public void textRightAligned(String text, float rightX, float y, float scale, Vector4f color, Typeface face) {
        text(text, rightX - BitmapFont.widthOf(text, scale, face), y, scale, color, face);
    }

    public void textCentered(String text, float centerX, float y, float scale, Vector4f color) {
        textCentered(text, centerX, y, scale, color, Typeface.BODY);
    }

    public void textCentered(String text, float centerX, float y, float scale, Vector4f color, Typeface face) {
        text(text, centerX - BitmapFont.widthOf(text, scale, face) * 0.5f, y, scale, color, face);
    }

    // --- Rounded shapes ---------------------------------------------------------------------------------

    /**
     * How wide the soft rim round a rounded shape is, in layout pixels. Its triangles carry no edges for ui.frag to
     * fade, so each shape fades its own: a ring outside its edge, from its colour to clear.
     */
    private static final float FEATHER = 1f;

    /**
     * A rectangle with rounded corners, shaded from {@code top} at its top edge to {@code bottom} at its bottom — the
     * same colour twice for a flat one — with a soft edge.
     *
     * @param radius the corners' radius, held to half the shorter side
     */
    public void roundedRect(float x, float y, float width, float height, float radius, Vector4f top, Vector4f bottom) {
        if (width <= 0f || height <= 0f) {
            return;
        }
        float r = Math.clamp(radius, 0f, Math.min(width, height) * 0.5f);
        int n = cornerSegments(r + FEATHER);
        float[] edge = roundedPath(x, y, width, height, r, n);
        float[] rim = roundedPath(x - FEATHER, y - FEATHER, width + 2f * FEATHER, height + 2f * FEATHER, r + FEATHER, n);
        Gradient shade = new Gradient(y, height, top, bottom, false);
        float cx = x + width * 0.5f;
        float cy = y + height * 0.5f;
        int points = edge.length / 2;
        for (int i = 0; i < points; i++) {
            int j = (i + 1) % points;
            fill(cx, cy, shade.at(cx, cy, 1f), edge[i * 2], edge[i * 2 + 1], shade.at(edge[i * 2], edge[i * 2 + 1], 1f),
                    edge[j * 2], edge[j * 2 + 1], shade.at(edge[j * 2], edge[j * 2 + 1], 1f));
        }
        ring(edge, 1f, rim, 0f, shade);
    }

    /**
     * The border of a rounded rectangle, {@code thickness} wide inside its edge, with soft edges either side: a
     * panel's rim, a card's frame.
     */
    public void roundedOutline(float x, float y, float width, float height, float radius, float thickness, Vector4f color) {
        if (width <= 0f || height <= 0f || thickness <= 0f) {
            return;
        }
        float r = Math.clamp(radius, 0f, Math.min(width, height) * 0.5f);
        float t = Math.min(thickness, Math.min(width, height) * 0.5f);
        int n = cornerSegments(r + FEATHER);
        float[] rim = roundedPath(x - FEATHER, y - FEATHER, width + 2f * FEATHER, height + 2f * FEATHER, r + FEATHER, n);
        float[] outer = roundedPath(x, y, width, height, r, n);
        float[] inner = roundedPath(x + t, y + t, width - 2f * t, height - 2f * t, Math.max(0f, r - t), n);
        Gradient shade = new Gradient(y, height, color, color, false);
        ring(rim, 0f, outer, 1f, shade);
        ring(outer, 1f, inner, 1f, shade);
        float inset = t + FEATHER;
        if (width > 2f * inset && height > 2f * inset) {
            float[] fade = roundedPath(x + inset, y + inset, width - 2f * inset, height - 2f * inset,
                    Math.max(0f, r - inset), n);
            ring(inner, 1f, fade, 0f, shade);
        }
    }

    /**
     * A soft glow, or shadow, round a rounded rectangle: {@code color} at its edge, fading out to nothing
     * {@code spread} beyond it. Drawn before the shape it surrounds; it leaves the shape's own area alone.
     */
    public void roundedGlow(float x, float y, float width, float height, float radius, float spread, Vector4f color) {
        if (width <= 0f || height <= 0f || spread <= 0f) {
            return;
        }
        float r = Math.clamp(radius, 0f, Math.min(width, height) * 0.5f);
        int n = cornerSegments(r + spread);
        float near = spread * 0.35f;
        float[] edge = roundedPath(x, y, width, height, r, n);
        float[] middle = roundedPath(x - near, y - near, width + 2f * near, height + 2f * near, r + near, n);
        float[] far = roundedPath(x - spread, y - spread, width + 2f * spread, height + 2f * spread, r + spread, n);
        Gradient shade = new Gradient(y, height, color, color, false);
        // Falling off faster close in than far out, as a blurred shadow does, rather than in a straight line.
        ring(edge, 1f, middle, 0.35f, shade);
        ring(middle, 0.35f, far, 0f, shade);
    }

    /** A filled circle with a soft edge. */
    public void circle(float centerX, float centerY, float radius, Vector4f color) {
        roundedRect(centerX - radius, centerY - radius, 2f * radius, 2f * radius, radius, color, color);
    }

    // --- Any convex shape -------------------------------------------------------------------------------

    /**
     * A filled convex shape, shaded from {@code top} at its highest point to {@code bottom} at its lowest, with a soft
     * edge: a slanted button, a leaning panel — anything a rounded rectangle is not.
     *
     * @param outline the shape's corners as x, y pairs, clockwise on screen; curves as runs of short edges
     */
    public void polygon(float[] outline, Vector4f top, Vector4f bottom) {
        polygon(outline, shadeOver(outline, top, bottom, false));
    }

    /**
     * As {@link #polygon}, shaded across instead: from {@code left} at the shape's leftmost point to {@code right} at
     * its rightmost.
     */
    public void polygonAcross(float[] outline, Vector4f left, Vector4f right) {
        polygon(outline, shadeOver(outline, left, right, true));
    }

    private void polygon(float[] outline, Gradient shade) {
        if (outline.length < 6) {
            return;
        }
        float cx = 0f;
        float cy = 0f;
        int points = outline.length / 2;
        for (int i = 0; i < points; i++) {
            cx += outline[i * 2];
            cy += outline[i * 2 + 1];
        }
        cx /= points;
        cy /= points;
        for (int i = 0; i < points; i++) {
            int j = (i + 1) % points;
            fill(cx, cy, shade.at(cx, cy, 1f), outline[i * 2], outline[i * 2 + 1], shade.at(outline[i * 2], outline[i * 2 + 1], 1f),
                    outline[j * 2], outline[j * 2 + 1], shade.at(outline[j * 2], outline[j * 2 + 1], 1f));
        }
        ring(outline, 1f, offsetPath(outline, FEATHER), 0f, shade);
    }

    /**
     * The border of a convex shape, {@code thickness} wide inside its edge, shaded from {@code top} down to
     * {@code bottom}, with soft edges either side.
     */
    public void polygonOutline(float[] outline, float thickness, Vector4f top, Vector4f bottom) {
        if (outline.length < 6 || thickness <= 0f) {
            return;
        }
        Gradient shade = shadeOver(outline, top, bottom, false);
        float[] inner = offsetPath(outline, -thickness);
        ring(offsetPath(outline, FEATHER), 0f, outline, 1f, shade);
        ring(outline, 1f, inner, 1f, shade);
        ring(inner, 1f, offsetPath(outline, -thickness - FEATHER), 0f, shade);
    }

    /**
     * As {@link #polygonOutline}, shaded across instead: from {@code left} at the shape's leftmost point to
     * {@code right} at its rightmost, so a border can fade out towards the side where the shape runs into another.
     */
    public void polygonOutlineAcross(float[] outline, float thickness, Vector4f left, Vector4f right) {
        if (outline.length < 6 || thickness <= 0f) {
            return;
        }
        Gradient shade = shadeOver(outline, left, right, true);
        float[] inner = offsetPath(outline, -thickness);
        ring(offsetPath(outline, FEATHER), 0f, outline, 1f, shade);
        ring(outline, 1f, inner, 1f, shade);
        ring(inner, 1f, offsetPath(outline, -thickness - FEATHER), 0f, shade);
    }

    /**
     * A soft glow, or shadow, round a convex shape: {@code color} at its edge, fading out to nothing {@code spread}
     * beyond it. Drawn before the shape it surrounds; it leaves the shape's own area alone.
     */
    public void polygonGlow(float[] outline, float spread, Vector4f color) {
        if (outline.length < 6 || spread <= 0f) {
            return;
        }
        Gradient shade = shadeOver(outline, color, color, false);
        float[] middle = offsetPath(outline, spread * 0.35f);
        ring(outline, 1f, middle, 0.35f, shade);
        ring(middle, 0.35f, offsetPath(outline, spread), 0f, shade);
    }

    /** A shading from the top of an outline to its foot, or with {@code across} from its left side to its right. */
    private static Gradient shadeOver(float[] outline, Vector4f from, Vector4f to, boolean across) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = across ? 0 : 1; i < outline.length; i += 2) {
            min = Math.min(min, outline[i]);
            max = Math.max(max, outline[i]);
        }
        return new Gradient(min, max - min, from, to, across);
    }

    /**
     * A convex outline (clockwise on screen) pushed {@code distance} outwards — inwards for a negative one — keeping
     * its edges parallel to where they were, point for point, so a ring can be drawn between the two. A sharp corner's
     * point is held to three times the distance, where a true one would run off to a spike.
     */
    static float[] offsetPath(float[] outline, float distance) {
        int points = outline.length / 2;
        float[] moved = new float[outline.length];
        for (int i = 0; i < points; i++) {
            float x = outline[i * 2];
            float y = outline[i * 2 + 1];
            // The edges into and out of this point; a point doubled up on its neighbour looks one further along.
            float inX = 0f, inY = 0f, outX = 0f, outY = 0f;
            for (int step = 1; step < points; step++) {
                int before = ((i - step) % points + points) % points;
                inX = x - outline[before * 2];
                inY = y - outline[before * 2 + 1];
                if (inX * inX + inY * inY > 1e-6f) {
                    break;
                }
            }
            for (int step = 1; step < points; step++) {
                int after = (i + step) % points;
                outX = outline[after * 2] - x;
                outY = outline[after * 2 + 1] - y;
                if (outX * outX + outY * outY > 1e-6f) {
                    break;
                }
            }
            float inLength = (float) Math.hypot(inX, inY);
            float outLength = (float) Math.hypot(outX, outY);
            if (inLength < 1e-3f || outLength < 1e-3f) {
                moved[i * 2] = x;
                moved[i * 2 + 1] = y;
                continue;
            }
            // Clockwise on a y-down screen, the outside of an edge going (dx, dy) is towards (dy, -dx).
            float n1x = inY / inLength, n1y = -inX / inLength;
            float n2x = outY / outLength, n2y = -outX / outLength;
            float along = Math.max(1f / 3f, 1f + n1x * n2x + n1y * n2y);
            moved[i * 2] = x + (n1x + n2x) / along * distance;
            moved[i * 2 + 1] = y + (n1y + n2y) / along * distance;
        }
        return moved;
    }

    /** Segments per quarter of a corner: enough that a large radius stays round, few for a small one. */
    private static int cornerSegments(float radius) {
        return Math.clamp((int) Math.ceil(radius / 2.5f), 2, 16);
    }

    /**
     * The edge of a rounded rectangle as x, y pairs, clockwise on screen from the top-left corner: {@code n + 1}
     * points round each corner, at the same angles whatever the radius, so two such paths pair point for point —
     * which is how a ring is drawn between them.
     */
    static float[] roundedPath(float x, float y, float width, float height, float radius, int n) {
        float[] points = new float[4 * (n + 1) * 2];
        float[][] centres = {{x + radius, y + radius}, {x + width - radius, y + radius},
                {x + width - radius, y + height - radius}, {x + radius, y + height - radius}};
        int k = 0;
        for (int corner = 0; corner < 4; corner++) {
            // Top-left from pointing left round to up, then each corner a further quarter on, clockwise on a
            // y-down screen.
            double start = Math.PI * (1.0 + corner * 0.5);
            for (int s = 0; s <= n; s++) {
                double angle = start + (Math.PI * 0.5) * s / n;
                points[k++] = centres[corner][0] + radius * (float) Math.cos(angle);
                points[k++] = centres[corner][1] + radius * (float) Math.sin(angle);
            }
        }
        return points;
    }

    /** The band between two paths that pair point for point, its colour's opacity scaled from {@code a} to {@code b}. */
    private void ring(float[] from, float a, float[] to, float b, Gradient shade) {
        int points = from.length / 2;
        for (int i = 0; i < points; i++) {
            int j = (i + 1) % points;
            float x1 = from[i * 2], y1 = from[i * 2 + 1], x2 = from[j * 2], y2 = from[j * 2 + 1];
            float x3 = to[j * 2], y3 = to[j * 2 + 1], x4 = to[i * 2], y4 = to[i * 2 + 1];
            fill(x1, y1, shade.at(x1, y1, a), x2, y2, shade.at(x2, y2, a), x3, y3, shade.at(x3, y3, b));
            fill(x1, y1, shade.at(x1, y1, a), x3, y3, shade.at(x3, y3, b), x4, y4, shade.at(x4, y4, b));
        }
    }

    /** A triangle with a colour at each corner. */
    private void fill(float x1, float y1, Vector4f c1, float x2, float y2, Vector4f c2, float x3, float y3, Vector4f c3) {
        setMode(false);
        reserve(3);
        // A constant UV, which ui.frag reads as no edges to fade: these shapes fade their own.
        pushVertex(x1, y1, 0.5f, 0.5f, c1);
        pushVertex(x2, y2, 0.5f, 0.5f, c2);
        pushVertex(x3, y3, 0.5f, 0.5f, c3);
        vertexCount += 3;
    }

    /**
     * A colour shaded from one colour to another over a length that starts at {@code start}: down the screen, or with
     * {@code across} from left to right. Its opacity can be scaled; a fresh vector each time.
     */
    private record Gradient(float start, float length, Vector4f from, Vector4f to, boolean across) {

        Vector4f at(float px, float py, float opacity) {
            float t = length <= 0f ? 0f : Math.clamp(((across ? px : py) - start) / length, 0f, 1f);
            return new Vector4f(from).lerp(to, t).mul(1f, 1f, 1f, opacity);
        }
    }

    private void setMode(boolean textured) {
        setMode(textured ? 1 : 0);
    }

    private void setMode(int wanted) {
        if (wanted != mode) {
            flush();
            mode = wanted;
            shader.setUniform("useTexture", wanted);
        }
    }

    /**
     * Draws a piece of artwork, mapped to the whole image.
     *
     * <p>Takes the texture per call rather than being set up front, and <b>flushes when it changes</b>:
     * one bound texture is one draw, so a panel drawing many copies of one frame costs a single call while
     * alternating two would cost one each. That is the same trade {@link #setMode} makes and is why the
     * batch is flushed on a change rather than sorted — draw order is the one thing a UI cannot get wrong.
     *
     * @param tint multiplied into the art; white leaves it exactly as authored
     */
    public void sprite(Texture texture, float x, float y, float width, float height, Vector4f tint) {
        sprite(texture, x, y, width, height, 0f, 0f, 1f, 1f, tint);
    }

    /**
     * Draws part of a piece of artwork, given as UV bounds.
     *
     * <p>The sub-rectangle form exists because art is not always tight to its canvas: an icon painted with
     * wide transparent margins would otherwise be drawn small and squashed, since the quad it lands in is
     * square and the file need not be. The caller passes the region that actually holds the picture.
     */
    public void sprite(Texture texture, float x, float y, float width, float height,
                       float u0, float v0, float u1, float v1, Vector4f tint) {
        if (texture == null) {
            return;
        }
        if (texture != sprite) {
            flush();
            sprite = texture;
        }
        // Bound every call rather than only on a change. One glBindTexture for a handful of HUD sprites is
        // nothing, and it means anything else that touches this unit — a texture created mid-frame binds
        // itself to whichever unit is active — cannot leave the batch drawing the wrong picture.
        texture.bind(1);
        setMode(2);
        // pushQuad reserves and counts for itself; doing either here again double-counts the batch.
        pushQuad(x, y, width, height, u0, v0, u1 - u0, v1 - v0, tint);
    }

    private void pushQuad(float x, float y, float width, float height,
                          float u, float v, float uSize, float vSize, Vector4f color) {
        pushQuad(x, y, width, height, u, v, uSize, vSize, color, color);
    }

    /** A quad shaded from {@code top} along its top edge to {@code bottom} along its foot. */
    private void pushQuad(float x, float y, float width, float height,
                          float u, float v, float uSize, float vSize, Vector4f top, Vector4f bottom) {
        reserve(VERTICES_PER_QUAD);
        pushVertex(x, y, u, v, top);
        pushVertex(x, y + height, u, v + vSize, bottom);
        pushVertex(x + width, y + height, u + uSize, v + vSize, bottom);

        pushVertex(x, y, u, v, top);
        pushVertex(x + width, y + height, u + uSize, v + vSize, bottom);
        pushVertex(x + width, y, u + uSize, v, top);
        vertexCount += VERTICES_PER_QUAD;
    }

    /** Makes room for a primitive, flushing first if it would not fit — never silently drop geometry. */
    private void reserve(int vertices) {
        if (vertexCount + vertices > MAX_VERTICES) {
            flush();
        }
    }

    /**
     * Turns everything drawn after it — solids, glyphs and sprites alike — by {@code radians} about a pivot, until
     * {@link #resetRotation}. Positive is clockwise on screen, since y points down.
     *
     * <p>Applied to vertices on the CPU rather than as a shader uniform, so a rotated card and the upright panel
     * beside it still share one batch. Widgets keep laying themselves out in plain axis-aligned pixels.
     */
    public void setRotation(float radians, float pivotX, float pivotY) {
        setTransform(radians, 1f, pivotX, pivotY);
    }

    /** Like {@link #setRotation}, and also scales everything drawn about the same pivot. */
    public void setTransform(float radians, float scale, float pivotX, float pivotY) {
        setTransform(radians, scale, 1f, pivotX, pivotY);
    }

    /**
     * Like {@link #setTransform(float, float, float, float)}, and also squeezes everything sideways by {@code squash}
     * before turning it — 1 for none, towards 0 for a card seen edge-on as it is flipped over.
     */
    public void setTransform(float radians, float scale, float squash, float pivotX, float pivotY) {
        this.squash = squash;
        rotationCos = (float) Math.cos(radians);
        rotationSin = (float) Math.sin(radians);
        this.scale = scale;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
    }

    public void resetRotation() {
        rotationCos = 1f;
        rotationSin = 0f;
        scale = 1f;
        squash = 1f;
    }

    /** The one colour everything is drawn in while a silhouette is being drawn, or null. */
    private Vector4f flat;

    /**
     * Draws everything from here on in one flat colour, keeping only each shape's own opacity, until it is given null:
     * the silhouette of whatever is drawn, for a drop shadow under a picture made of several colours.
     */
    public void setFlatColor(Vector4f color) {
        flat = color == null ? null : new Vector4f(color);
    }

    private void pushVertex(float x, float y, float u, float v, Vector4f color) {
        if (rotationSin != 0f || scale != 1f || squash != 1f) {
            float dx = (x - pivotX) * squash;
            float dy = y - pivotY;
            x = pivotX + (dx * rotationCos - dy * rotationSin) * scale;
            y = pivotY + (dx * rotationSin + dy * rotationCos) * scale;
        }
        if (flat != null) {
            vertices.put(x).put(y).put(u).put(v).put(flat.x).put(flat.y).put(flat.z).put(color.w * flat.w);
            return;
        }
        vertices.put(x).put(y).put(u).put(v).put(color.x).put(color.y).put(color.z).put(color.w);
    }

    private void flush() {
        if (vertexCount == 0) {
            return;
        }
        vertices.flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        vertices.clear();
        vertexCount = 0;
    }

    // --- Clipping ---------------------------------------------------------------------------------------------

    /**
     * Keeps everything drawn from here to the matching {@link #popClip} inside a rectangle, in layout units: the
     * inside of a scrolling list, a panel that text must not run out of. Clips nest, each inside the last.
     *
     * <p>Done with the GL scissor rather than by cutting quads, so text and pictures are cut cleanly mid-glyph. A change
     * of clip is a flush, like a change of texture. Turned or scaled drawing ({@link #setTransform}) is clipped by the
     * upright rectangle all the same.
     */
    public void pushClip(float x, float y, float width, float height) {
        float[] next = {x, y, x + Math.max(0f, width), y + Math.max(0f, height)};
        float[] outer = clips.peek();
        if (outer != null) {
            next[0] = Math.max(next[0], outer[0]);
            next[1] = Math.max(next[1], outer[1]);
            next[2] = Math.max(next[0], Math.min(next[2], outer[2]));
            next[3] = Math.max(next[1], Math.min(next[3], outer[3]));
        }
        flush();
        clips.push(next);
        applyClip();
    }

    /** Drops every clip in force, for drawing that must start again from a known state after a failure. */
    public void clearClips() {
        if (!clips.isEmpty()) {
            flush();
            clips.clear();
            applyClip();
        }
    }

    /** Ends the innermost {@link #pushClip}. */
    public void popClip() {
        if (clips.isEmpty()) {
            return;
        }
        flush();
        clips.pop();
        applyClip();
    }

    /** The innermost clip as x0, y0, x1, y1 in layout units, or null when nothing is clipped. Not to be changed. */
    public float[] clip() {
        return clips.peek();
    }

    private void applyClip() {
        float[] clip = clips.peek();
        if (clip == null) {
            glDisable(GL_SCISSOR_TEST);
            return;
        }
        float scaleX = viewport[2] / Math.max(1f, screenWidth);
        float scaleY = viewport[3] / Math.max(1f, screenHeight);
        int left = (int) Math.floor(viewport[0] + clip[0] * scaleX);
        int right = (int) Math.ceil(viewport[0] + clip[2] * scaleX);
        int top = (int) Math.floor(clip[1] * scaleY);
        int bottom = (int) Math.ceil(clip[3] * scaleY);
        glEnable(GL_SCISSOR_TEST);
        // GL counts rows from the bottom of the viewport, the layout from the top.
        glScissor(left, viewport[1] + viewport[3] - bottom, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    /** A straight line {@code thickness} wide from one point to another: a wire, a grid line, an arrow's shaft. */
    public void line(float x1, float y1, float x2, float y2, float thickness, Vector4f color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1e-4f) {
            return;
        }
        float nx = -dy / length * thickness * 0.5f;
        float ny = dx / length * thickness * 0.5f;
        triangle(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, color);
        triangle(x1 + nx, y1 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, color);
    }

    /** Screen size the current frame was begun with, for widgets laying themselves out from an edge. */
    public float getScreenWidth() {
        return screenWidth;
    }

    public float getScreenHeight() {
        return screenHeight;
    }

    public boolean isDrawing() {
        return drawing;
    }

    public void delete() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        fonts.values().forEach(BitmapFont::delete);
        shader.delete();
    }
}
