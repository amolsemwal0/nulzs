package org.zeronura.engine.ui;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.stb.STBTruetype.*;

/**
 * Where the UI's glyphs come from: a coverage atlas plus per-character metrics, with no GL in it.
 *
 * <p>Two sources, one shape. {@link #trueType} rasterises a real typeface with {@code stb_truetype};
 * {@link #builtIn} returns the hand-typed 5x7 pixel glyphs this project shipped with. {@link BitmapFont}
 * uploads whichever it gets and never asks which, so a missing font file degrades to the pixel font rather
 * than to an empty UI.
 *
 * <h2>stb_truetype was available the whole time</h2>
 * The pixel font's own note said there was "no TrueType rasteriser wired up (the STB module in use is
 * {@code stb_image}, not {@code stb_truetype})". That is a statement about which <i>classes</i> were being
 * called, not about the dependency: LWJGL ships {@code STBImage} and {@code STBTruetype} in the <b>same</b>
 * {@code lwjgl-stb} artifact and the same native library, both of which this project has always declared.
 * So real type cost no new dependency and no new native. Worth keeping as a class of mistake — a note
 * about what is <i>in use</i> quietly hardened into a belief about what is <i>possible</i>.
 *
 * <h2>Metrics are in "font units", and one unit is what it always was</h2>
 * Everything here is expressed in the same logical units the pixel font used, where a line of capitals is
 * {@link BitmapFont#GLYPH_HEIGHT} units tall and a widget multiplies by its own scale. That is the whole
 * reason the swap did not require re-laying-out the game: {@code heightOf(scale)} still means "how tall is
 * a line of capitals", so every row height, centring offset and panel size written against it stays true.
 *
 * <p><b>The mapping is cap height, deliberately, and the alternatives are worse.</b> A 5x7 pixel glyph is
 * all cap — descenders are squeezed inside the box — so a real face fitted <i>box to box</i> comes out
 * looking half the size: Noto Serif's ascent-to-descent is 1.9x its cap height, so mapping the full line
 * onto 7 units leaves capitals 3.7 units tall against the 7 they used to be. Mapping cap to cap keeps text
 * the size the game was designed around and spends the difference where it costs least — descenders reach
 * about two units below the nominal box, into padding that rows already have.
 *
 * <h2>Rasterised once, near the size it is drawn at</h2>
 * "Bake it huge and every size is a downsample" is the obvious reasoning and it is wrong — see
 * {@link #RASTER_CAP_PIXELS}, where trying it cost the thin strokes of every small letter. The atlas is
 * baked at the largest size the UI draws, padded generously, and mipmapped, which is the combination that
 * survives all three of this HUD's text scales.
 */
public final class GlyphSet {

    /**
     * Height of a capital in the atlas, in pixels — <b>the size the largest text on screen is drawn at</b>.
     *
     * <p>This is the constant the whole legibility of small text hangs off, and getting it wrong is not
     * subtle. The obvious reasoning — bake it huge so every size is a downsample and can only look better —
     * is wrong: a first attempt at a 96-pixel raster rendered small text with its <b>thin horizontal
     * strokes missing</b>, an E coming out as an L and an F as an I, while stems and diagonals survived.
     * A crossbar is one texel of the raster, and a single bilinear tap across nine of them either lands on
     * it or does not.
     *
     * <p>Mipmaps are what make a downsample safe and are enabled alongside this, but they only turn a
     * missing stroke into a faint one — <b>the fix is to not minify much in the first place</b>. Backing
     * off to a 64-pixel raster left capitals 34 pixels tall against the 10.5 the HUD's small text actually
     * draws, still a 3.2x minification, and it still ate the crossbars: {@code FOOD} rendered with the F's
     * arms washed away. Matching the largest drawn size instead makes titles pixel-exact and puts the
     * small text at exactly 2x, which is mip level 1 — a clean box filter rather than an interpolation
     * between two of them.
     *
     * <p><b>Expressed as a cap height and converted per font, not as a raster height.</b> stb takes an
     * ascent-to-descent size, and how much of that a capital occupies is a property of the typeface — Noto
     * Serif Display spends barely half of it on caps — so a fixed raster height means the thing that
     * actually matters lands wherever the next font happens to put it. See {@link #trueType}, which
     * measures the face and solves for the size that puts a capital exactly here.
     */
    private static final float RASTER_CAP_PIXELS = BitmapFont.GLYPH_HEIGHT * BitmapFont.MAX_UI_SCALE;

    /**
     * The scales each <b>larger</b> tier is rasterised for, smallest first — display text, not body text.
     *
     * <p>{@link #RASTER_CAP_PIXELS} is right for the HUD and wrong for a title: a logo drawn at scale 14
     * from an atlas baked for 3 is a 4.7x <i>magnification</i>, which no filter can recover, and it showed
     * as a visibly soft "ELEMENTALS" the moment a smooth face replaced the pixel one (which hid it, since
     * blocky pixels magnify cleanly). Baking the one atlas bigger is the fix the note above already rules
     * out. So large text gets its own atlases instead, each chosen so the text it serves is at most about a
     * 2.7x minification — mip level 1 or 2, the range that keeps crossbars.
     */
    private static final float[] LARGER_TIER_SCALES = {6f, 16f};

    /** Fallback ascent-to-descent raster size, used only if a face has no measurable capital. */
    private static final float FALLBACK_PIXEL_HEIGHT = 40f;

    /**
     * Blank pixels around each glyph in the atlas.
     *
     * <p>Wider than the one pixel a gutter needs, because the atlas is mipmapped: each level halves the
     * resolution and pulls in twice as far, so a one-pixel gutter has neighbouring glyphs bleeding into
     * each other by the second level and text draws with faint specks beside every letter. Eight covers
     * every level the UI's sizes actually reach.
     */
    private static final int GLYPH_PADDING = 8;

    /**
     * Atlas sides tried in turn. The packer fails loudly rather than silently dropping glyphs, so a tier
     * that does not fit simply moves up a size — the body-text tier fits the first, a title tier needs more.
     */
    private static final int[] ATLAS_SIDES = {1024, 2048, 4096};

    /** Printable ASCII, the range the pixel font covers and the range the UI uses. */
    static final char FIRST_CHAR = ' ';
    static final char LAST_CHAR = '~';

    /** One glyph, in font units, with its patch of the atlas in normalised texture coordinates. */
    public record Glyph(float u0, float v0, float u1, float v1,
                        float xOffset, float yOffset, float width, float height, float advance) {
    }

    private final ByteBuffer pixels;
    private final int atlasWidth;
    private final int atlasHeight;
    private final Glyph[] glyphs;
    private final boolean pixelArt;
    private final String name;
    private final float rasterScale;
    /** This set followed by its larger tiers, smallest first. Just this set for the pixel font. */
    private List<GlyphSet> tiers = List.of(this);

    private GlyphSet(ByteBuffer pixels, int atlasWidth, int atlasHeight, Glyph[] glyphs, boolean pixelArt,
                     String name, float rasterScale) {
        this.pixels = pixels;
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.glyphs = glyphs;
        this.pixelArt = pixelArt;
        this.name = name;
        this.rasterScale = rasterScale;
    }

    /**
     * The same face at every size it was rasterised for, smallest first; index 0 is this set.
     *
     * <p>Every tier carries the same metrics in font units, give or take a texel of rounding, but only the
     * first is ever <i>measured</i> — see {@link #tierFor}.
     */
    public List<GlyphSet> tiers() {
        return tiers;
    }

    /**
     * Which tier to draw text of this scale from: the smallest one baked at least that large, so text is
     * only ever minified, or the largest if nothing is.
     *
     * <p>Only the atlas and the quad come from the tier. Layout keeps stepping by the base set's advances,
     * since those are what {@link #widthOf} reported when the text was centred.
     */
    public int tierFor(float scale) {
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).rasterScale >= scale) {
                return i;
            }
        }
        return tiers.size() - 1;
    }

    public ByteBuffer pixels() {
        return pixels;
    }

    public int atlasWidth() {
        return atlasWidth;
    }

    public int atlasHeight() {
        return atlasHeight;
    }

    /** Whether this is the hand-typed pixel font, which must be sampled {@code GL_NEAREST} to stay crisp. */
    public boolean isPixelArt() {
        return pixelArt;
    }

    /** What was loaded, for the one line of startup logging that says which font the game is wearing. */
    public String name() {
        return name;
    }

    public Glyph glyph(char c) {
        int index = c < FIRST_CHAR || c > LAST_CHAR ? -1 : c - FIRST_CHAR;
        return glyphs[index < 0 ? glyphs.length - 1 : index];
    }

    public boolean covers(char c) {
        return c >= FIRST_CHAR && c <= LAST_CHAR;
    }

    /** Width of a run of text in font units. Proportional, so this is a sum and never a character count. */
    public float widthOf(String text) {
        float width = 0f;
        for (int i = 0; i < text.length(); i++) {
            width += glyph(text.charAt(i)).advance();
        }
        return width;
    }

    /**
     * A representative single-character step, for the few callers that step a fixed number of characters
     * rather than measuring a string.
     *
     * <p>The digit zero, because a proportional face still sets its digits on one width — a HUD counter
     * whose figures were free to differ would jitter every time it changed.
     */
    public float advance() {
        return glyph('0').advance();
    }

    /**
     * The hand-typed 5x7 pixel font: one texture, 16 glyphs across, each in the top-left of a 6x8 cell.
     *
     * <p>The gutter matters. Without it a filter or a half-pixel sampling error pulls in the neighbouring
     * glyph's leftmost column, which shows up as faint specks beside every letter.
     */
    public static GlyphSet builtIn() {
        int cellWidth = BitmapFont.GLYPH_WIDTH + 1;
        int cellHeight = BitmapFont.GLYPH_HEIGHT + 1;
        int columns = 16;
        int rows = (BuiltInGlyphs.ART.length + columns - 1) / columns;
        int width = columns * cellWidth;
        int height = rows * cellHeight;

        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height);
        Glyph[] glyphs = new Glyph[BuiltInGlyphs.ART.length + 1];
        for (int i = 0; i < BuiltInGlyphs.ART.length; i++) {
            int cellX = (i % columns) * cellWidth;
            int cellY = (i / columns) * cellHeight;
            blit(pixels, width, cellX, cellY, BuiltInGlyphs.ART[i]);
            glyphs[i] = cell(cellX, cellY, width, height);
        }
        // The fallback box, drawn for anything outside the range so unmappable text is visible not silent.
        int lastX = (BuiltInGlyphs.ART.length % columns) * cellWidth;
        int lastY = (BuiltInGlyphs.ART.length / columns) * cellHeight;
        blit(pixels, width, lastX, lastY, BuiltInGlyphs.FALLBACK);
        glyphs[glyphs.length - 1] = cell(lastX, lastY, width, height);

        pixels.position(0).limit(width * height);
        // Its raster scale is 1, but it never has another tier: nearest-sampled pixels magnify cleanly.
        return new GlyphSet(pixels, width, height, glyphs, true, "built-in 5x7", 1f);
    }

    private static Glyph cell(int cellX, int cellY, int atlasWidth, int atlasHeight) {
        return new Glyph(
                cellX / (float) atlasWidth, cellY / (float) atlasHeight,
                (cellX + BitmapFont.GLYPH_WIDTH) / (float) atlasWidth,
                (cellY + BitmapFont.GLYPH_HEIGHT) / (float) atlasHeight,
                0f, 0f, BitmapFont.GLYPH_WIDTH, BitmapFont.GLYPH_HEIGHT, BitmapFont.GLYPH_WIDTH + 1);
    }

    private static void blit(ByteBuffer pixels, int atlasWidth, int cellX, int cellY, String art) {
        String[] rows = art.split("\\|");
        for (int y = 0; y < BitmapFont.GLYPH_HEIGHT && y < rows.length; y++) {
            String row = rows[y];
            for (int x = 0; x < BitmapFont.GLYPH_WIDTH && x < row.length(); x++) {
                if (row.charAt(x) == '#') {
                    pixels.put((cellY + y) * atlasWidth + cellX + x, (byte) 0xFF);
                }
            }
        }
    }

    /**
     * A real typeface, rasterised into an atlas.
     *
     * @param ttf   the font file's bytes; must be a direct buffer, which is what {@link #load} produces
     * @param name  what to call it in the startup line
     * @param widthScale horizontal squeeze applied to advances and glyphs alike — see
     *                   {@code BitmapFont.WIDTH_SCALE}, which is where the reason for it lives
     */
    public static GlyphSet trueType(ByteBuffer ttf, String name, float widthScale) {
        STBTTFontinfo info = STBTTFontinfo.create();
        if (!stbtt_InitFont(info, ttf)) {
            throw new IllegalArgumentException("not a font stb_truetype can read: " + name);
        }
        GlyphSet base = rasterise(info, ttf, name, widthScale, RASTER_CAP_PIXELS / BitmapFont.GLYPH_HEIGHT);
        List<GlyphSet> tiers = new ArrayList<>();
        tiers.add(base);
        for (float scale : LARGER_TIER_SCALES) {
            try {
                tiers.add(rasterise(info, ttf, name, widthScale, scale));
            } catch (IllegalStateException e) {
                // Titles going soft is a far smaller failure than the whole font falling back.
                System.err.println("font " + name + " has no " + scale + "x tier (" + e.getMessage() + ")");
                break;
            }
        }
        base.tiers = List.copyOf(tiers);
        return base;
    }

    /** One tier: the face rasterised so a capital is {@code GLYPH_HEIGHT * rasterScale} pixels tall. */
    private static GlyphSet rasterise(STBTTFontinfo info, ByteBuffer ttf, String name, float widthScale,
                                      float rasterScale) {
        IllegalStateException last = null;
        for (int side : ATLAS_SIDES) {
            try {
                return rasterise(info, ttf, name, widthScale, rasterScale, side);
            } catch (IllegalStateException e) {
                last = e;
            }
        }
        throw last;
    }

    private static GlyphSet rasterise(STBTTFontinfo info, ByteBuffer ttf, String name, float widthScale,
                                      float rasterScale, int atlasSide) {
        float rasterPixelHeight = pixelHeightForCap(info, BitmapFont.GLYPH_HEIGHT * rasterScale);

        // GC-managed, because this one outlives the method: it is handed to the GlyphSet and uploaded by
        // BitmapFont whenever a context turns up, so there is no point at which anything could free it.
        ByteBuffer pixels = BufferUtils.createByteBuffer(atlasSide * atlasSide);
        int count = LAST_CHAR - FIRST_CHAR + 1;

        // malloc, *not* create, and the pairing is the whole point — see the free in the finally below.
        STBTTPackedchar.Buffer packed = STBTTPackedchar.malloc(count);
        try {
            // The packer rather than stbtt_BakeFontBitmap, for one reason: it takes a padding, and the
            // baker hard-codes a single pixel. A mipmapped atlas needs more than that — see GLYPH_PADDING.
            try (STBTTPackContext context = STBTTPackContext.malloc()) {
                if (!stbtt_PackBegin(context, pixels, atlasSide, atlasSide, 0, GLYPH_PADDING, 0)) {
                    throw new IllegalStateException("could not begin packing " + name);
                }
                stbtt_PackSetOversampling(context, 1, 1);
                boolean fitted = stbtt_PackFontRange(context, ttf, 0, rasterPixelHeight, FIRST_CHAR, packed);
                stbtt_PackEnd(context);
                if (!fitted) {
                    // The caller wants the pixel font rather than a UI missing half its letters.
                    throw new IllegalStateException(name + " does not fit a " + atlasSide + "px atlas at "
                            + rasterPixelHeight + "px");
                }
            }

            // Cap height, measured off the packed 'H' rather than read from the font's metrics table: what
            // matters is the height of the pixels actually drawn, and OS/2 capHeight is optional and often
            // wrong. This is the number the whole logical scale hangs off.
            STBTTPackedchar capital = packed.get('H' - FIRST_CHAR);
            float capPixels = capital.y1() - capital.y0();
            if (capPixels <= 0f) {
                throw new IllegalStateException(name + " has no 'H' to measure a cap height from");
            }
            float unitsPerPixel = BitmapFont.GLYPH_HEIGHT / capPixels;
            // Everything vertical is measured down from the top of a capital, because that is where a
            // widget thinks its text starts: `text(s, x, y, ...)` puts y at the top of the line.
            float capTop = capital.yoff();

            Glyph[] glyphs = new Glyph[count + 1];
            for (int i = 0; i < count; i++) {
                STBTTPackedchar b = packed.get(i);
                glyphs[i] = new Glyph(
                        b.x0() / (float) atlasSide, b.y0() / (float) atlasSide,
                        b.x1() / (float) atlasSide, b.y1() / (float) atlasSide,
                        b.xoff() * unitsPerPixel * widthScale,
                        (b.yoff() - capTop) * unitsPerPixel,
                        (b.x1() - b.x0()) * unitsPerPixel * widthScale,
                        (b.y1() - b.y0()) * unitsPerPixel,
                        b.xadvance() * unitsPerPixel * widthScale);
            }
            tabulariseDigits(glyphs);

            // Anything outside the range draws as a question mark rather than as nothing: silently
            // dropping characters is how a wrong string looks like a short one.
            glyphs[count] = glyphs['?' - FIRST_CHAR];

            pixels.position(0).limit(atlasSide * atlasSide);
            return new GlyphSet(pixels, atlasSide, atlasSide, glyphs, false, name, rasterScale);
        } finally {
            // Paired with malloc above, and that pairing is load-bearing rather than tidiness. The first
            // version allocated this with STBTTPackedchar.create(count) and freed it here, which compiles,
            // rasterises a perfectly correct atlas, and passes every test — because it is not a rendering
            // fault at all. create() allocates a *JVM-owned* direct ByteBuffer; free() is nmemFree on that
            // same address; so the block is released by libc here and released again by the GC later. A
            // double free corrupts the C heap, and what a corrupted heap does is crash *somebody else*
            // later: two runs died in libnvidia-eglcore at glDrawArrays and at glDeleteTextures, neither
            // of which has anything to do with fonts, and one of them only after every frame had already
            // been written correctly. Worth keeping as a class of bug — the allocator that hands memory
            // out is the only thing entitled to take it back, and LWJGL spells that as malloc/free versus
            // create/let-go. A finally rather than a straight-line call so a font that fails to pack
            // (which is a supported outcome — the caller falls back) does not also leak.
            packed.free();
        }
    }

    /**
     * Puts every digit on one advance, centring the narrow ones in it — "tabular figures".
     *
     * <p><b>A HUD is nearly all numbers that change while being looked at</b>: a health count, the day
     * number, a damage popup, an item tally. Set with a proportional face's natural widths, a 1 is much
     * narrower than a 0 — measured on Metamorphous, 4.48 units against 5.96 — so {@code 97 / 140} becoming
     * {@code 97 / 139} shuffles every figure after it sideways, and a bar's label twitches on every hit.
     * Real typefaces ship a tabular set for exactly this and it is not reachable through
     * {@code stb_truetype}, which reads the default {@code cmap} and no OpenType features, so it is done
     * here instead.
     *
     * <p><b>This is what makes the font genuinely swappable.</b> Rejecting a face for having proportional
     * figures would rule out most decorative ones, which is the opposite of the point of
     * {@code PREFERRED_FONTS} — and it is one of very few typographic properties a UI can simply impose.
     * Doing it once here, on the metrics both {@link #widthOf} and the renderer read, is also what stops
     * the drawn width and the measured width disagreeing: they are the same numbers.
     *
     * <p>Centred rather than left-aligned in the cell, because a 1 pushed to the left of its slot reads as
     * a spacing error in the middle of a number. The widest digit keeps its own advance, so this only ever
     * adds space and can never make a number narrower than the face intended.
     */
    private static void tabulariseDigits(Glyph[] glyphs) {
        float widest = 0f;
        for (char d = '0'; d <= '9'; d++) {
            widest = Math.max(widest, glyphs[d - FIRST_CHAR].advance());
        }
        for (char d = '0'; d <= '9'; d++) {
            Glyph g = glyphs[d - FIRST_CHAR];
            float centring = (widest - g.advance()) * 0.5f;
            glyphs[d - FIRST_CHAR] = new Glyph(g.u0(), g.v0(), g.u1(), g.v1(),
                    g.xOffset() + centring, g.yOffset(), g.width(), g.height(), widest);
        }
    }

    /**
     * The ascent-to-descent size to rasterise at so that a capital comes out {@code capPixels} tall.
     *
     * <p>stb sizes a face by its <b>line</b> — {@code stbtt_ScaleForPixelHeight(h)} is
     * {@code h / (ascent - descent)} — and what this UI cares about is the height of a <b>capital</b>,
     * since that is what one font unit means here and what every row height was laid out against. The two
     * are related by a ratio that belongs to the typeface and nothing else: Noto Serif Display spends only
     * about 52% of its line on caps, a squarer face far more. So the conversion is measured per font rather
     * than folded into a constant, and a face dropped in later gets capitals at the same pixel height this
     * one does instead of wherever its own metrics happen to land.
     *
     * <p>Measured from the glyph outline's own bounding box rather than from {@code OS/2.sCapHeight},
     * which is optional, frequently absent and frequently wrong — the same reason the packed atlas is
     * re-measured afterwards in {@link #trueType} rather than trusted to come out at this size.
     */
    private static float pixelHeightForCap(STBTTFontinfo info, float capPixels) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer ascent = stack.mallocInt(1);
            IntBuffer descent = stack.mallocInt(1);
            IntBuffer lineGap = stack.mallocInt(1);
            stbtt_GetFontVMetrics(info, ascent, descent, lineGap);

            IntBuffer x0 = stack.mallocInt(1);
            IntBuffer y0 = stack.mallocInt(1);
            IntBuffer x1 = stack.mallocInt(1);
            IntBuffer y1 = stack.mallocInt(1);
            int line = ascent.get(0) - descent.get(0);
            if (!stbtt_GetCodepointBox(info, 'H', x0, y0, x1, y1) || line <= 0) {
                return FALLBACK_PIXEL_HEIGHT;
            }
            int cap = y1.get(0) - y0.get(0);
            return cap <= 0 ? FALLBACK_PIXEL_HEIGHT : capPixels * line / cap;
        }
    }

    /**
     * Reads a font file into a direct buffer, which is what stb needs.
     *
     * @return null if it cannot be read, so the caller falls back rather than failing to start
     */
    public static ByteBuffer load(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads a font the engine carries inside its own jar, by classpath resource name, into a direct buffer.
     *
     * @return null if there is no such resource or it cannot be read
     */
    public static ByteBuffer loadResource(String resource) {
        try (var in = GlyphSet.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Font files in a directory, most preferred first.
     *
     * <p>Named files win, then any other {@code .ttf}, and {@code .otf} last — stb_truetype reads
     * TrueType outlines natively and CFF ones through a separate path that is easier to trip up, so a
     * project that drops in both should get the one more likely to work.
     */
    public static List<Path> candidates(Path directory, List<String> preferred) {
        List<Path> found = new ArrayList<>();
        for (String name : preferred) {
            Path candidate = directory.resolve(name);
            if (Files.isReadable(candidate)) {
                found.add(candidate);
            }
        }
        if (!Files.isDirectory(directory)) {
            return found;
        }
        try (var listing = Files.list(directory)) {
            List<Path> rest = listing.filter(Files::isRegularFile).sorted().toList();
            for (Path path : rest) {
                String lower = path.getFileName().toString().toLowerCase();
                if (lower.endsWith(".ttf") && !found.contains(path)) {
                    found.add(path);
                }
            }
            for (Path path : rest) {
                String lower = path.getFileName().toString().toLowerCase();
                if (lower.endsWith(".otf") && !found.contains(path)) {
                    found.add(path);
                }
            }
        } catch (IOException e) {
            return found;
        }
        return found;
    }
}
