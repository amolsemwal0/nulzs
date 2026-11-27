package org.zeronura.nulzs.ui;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * The UI's font: a real typeface rasterised into a coverage atlas, or the hand-typed 5x7 pixel glyphs if
 * no font file is present.
 *
 * <p>The class keeps its name because it is still exactly that — a bitmap atlas of glyphs, uploaded once
 * and sampled per character. What changed is where the bitmaps come from: {@link GlyphSet} bakes them from
 * a {@code .ttf} with {@code stb_truetype} when one is found in {@link #FONT_DIRECTORY}, and falls back to
 * {@link BuiltInGlyphs} otherwise, so the UI layer still draws with zero assets.
 *
 * <h2>One font for the process, like the keymap</h2>
 * {@link #widthOf}, {@link #heightOf} and {@link #advance} are static and called from several dozen places
 * across the HUD, the menus and their tests — measuring text is what layout <i>is</i> here. A proportional
 * face makes those answers depend on which typeface is loaded, so the loaded face is process-wide, resolved
 * lazily and GL-free. That is the same call {@code Keymap.active()} makes and for the same reason: the
 * alternative is threading a font through every widget in a game that has one.
 *
 * <p>Resolution is GL-free on purpose. Rasterising is CPU work; only the upload needs a context. So a test
 * or a headless tool can ask how wide a string is, which is what the layout tests do.
 *
 * <h2>What the units mean, and why nothing had to be re-laid-out</h2>
 * A "font unit" is what it always was: a line of capitals is {@value #GLYPH_HEIGHT} units tall and a widget
 * multiplies by its own scale. {@link GlyphSet} maps a typeface's <b>cap height</b> onto that, so
 * {@code heightOf(scale)} still answers the question every row height and centring offset in this UI was
 * written against. See {@link GlyphSet} for why cap height rather than the full line — fitting box to box
 * halves the apparent size.
 *
 * <p>The visible consequence is that <b>descenders now reach below the nominal box</b>, by about two units,
 * where the pixel font squeezed them inside it. Rows here carry more padding than that, which is what makes
 * the substitution affordable; it is the first thing to check in a rendered panel if lines look crowded.
 */
public class BitmapFont {

    /**
     * The pixel font's glyph box, and still the meaning of one line of capitals.
     *
     * <p>{@link #GLYPH_WIDTH} is only the fallback's fixed cell — a real face is proportional, so nothing
     * may assume it. {@link #widthOf} is the only honest way to measure a string, and was already the rule
     * before this: the pixel font had a one-pixel gap that a character count silently dropped.
     */
    public static final int GLYPH_WIDTH = 5;
    public static final int GLYPH_HEIGHT = 7;

    /**
     * The largest scale the atlas is rasterised to serve, in pixels per font unit.
     *
     * <p>{@link GlyphSet} bakes capitals at exactly {@code MAX_UI_SCALE * GLYPH_HEIGHT} pixels, so text at
     * this scale is drawn one atlas texel to one screen pixel and everything smaller is a downsample.
     * Drawing <i>larger</i> than this magnifies a bitmap, which is the one direction that cannot be
     * recovered — a title at scale 4 would be visibly soft with nothing anywhere reporting why. It is
     * public so the game layer's own text scales can be pinned against it; {@code HudTextScaleTest} does
     * exactly that, since {@code engine} may not look at {@code HudTheme} itself.
     */
    public static final float MAX_UI_SCALE = 3f;

    /** Where a project drops its own typeface. Anything here beats the built-in glyphs. */
    public static final Path FONT_DIRECTORY = Path.of("assets", "Fonts");

    /**
     * Where the engine's jar carries its own UI faces (Montserrat, Grandstander, DejaVu Sans Mono, with their
     * licences), used for any face none of whose files are in {@link #FONT_DIRECTORY}: a game that uses the engine as a
     * library draws in them without copying the files.
     */
    public static final String FONT_RESOURCES = "/fonts/";

    /**
     * Font files tried first, in order, before any other file in the directory.
     *
     * <p>A list rather than one name, the pattern the water and lava art already follow: dropping a new
     * face in must not mean editing code, and removing one must degrade to the next rather than to
     * nothing.
     */
    public static final List<String> PREFERRED_FONTS = Typeface.BODY.files();

    /**
     * Horizontal squeeze applied to a rasterised face — <b>1, i.e. none, for the font that ships</b>.
     *
     * <p>It exists because this UI was laid out against a fixed six-unit cell and its labels are largely
     * uppercase, which is where a serif is widest; a face that came out too wide would push a footer hint
     * past its panel, and a footer hint is the one string no rectangle constrains. So the knob is the
     * cheap fix for the <i>next</i> font rather than dead weight.
     *
     * <p><b>This one does not need it, which was measured rather than assumed.</b> Every string the UI
     * actually draws is <i>narrower</i> than the pixel font's fixed cell made it — 0.96 for the longest
     * inventory name, 0.78 for the widest footer hint, 0.72 for a full creature nameplate — because a
     * proportional face wins back far more on lowercase and digits than it loses on capitals. Only a
     * contrived all-{@code M} string grows. Squeezing anyway would distort a typeface to buy room that is
     * already there, so it is set to 1 and {@code BitmapFontTest} pins that the widest real strings fit.
     */
    public static final float WIDTH_SCALE = 1f;

    private static GlyphSet active;
    /** The faces other than {@link Typeface#BODY}, resolved on first use: each its own, or the one it falls back to. */
    private static final java.util.Map<Typeface, GlyphSet> faces = new java.util.EnumMap<>(Typeface.class);

    private final GlyphSet glyphs;
    /** One atlas per tier of {@link #glyphs}, in the same order. */
    private final int[] textureIds;

    /**
     * The loaded typeface, resolved on first use.
     *
     * <p>Falls back rather than throws at every step: an unreadable file, a format stb cannot parse, a
     * glyph set too large for the atlas. A game that will not start because a font is missing is a worse
     * failure than one drawn in pixel glyphs.
     */
    public static synchronized GlyphSet active() {
        if (active != null) {
            return active;
        }
        for (Path candidate : GlyphSet.candidates(FONT_DIRECTORY, PREFERRED_FONTS)) {
            ByteBuffer data = GlyphSet.load(candidate);
            if (data == null) {
                continue;
            }
            try {
                active = GlyphSet.trueType(data, candidate.getFileName().toString(), WIDTH_SCALE);
                System.out.println("UI font: " + active.name());
                return active;
            } catch (RuntimeException e) {
                System.err.println("font " + candidate + " could not be rasterised (" + e.getMessage()
                        + "); trying the next one");
            }
        }
        for (String file : PREFERRED_FONTS) {
            ByteBuffer data = GlyphSet.loadResource(FONT_RESOURCES + file);
            if (data == null) {
                continue;
            }
            try {
                active = GlyphSet.trueType(data, file, WIDTH_SCALE);
                System.out.println("UI font: " + active.name() + " (the engine's own)");
                return active;
            } catch (RuntimeException e) {
                System.err.println("font " + FONT_RESOURCES + file + " could not be rasterised (" + e.getMessage() + ")");
            }
        }
        active = GlyphSet.builtIn();
        // Said out loud, because every step of the resolution above degrades quietly by design and this is
        // the end of that chain. The whole point of FONT_DIRECTORY is that a face is chosen by dropping a
        // file in rather than by editing code — so a file that was dropped in and then rejected, or a
        // directory that is not where the process thinks it is, has to be distinguishable from a project
        // that simply ships no font. Without a line here both look identical: a UI drawn in pixel glyphs.
        System.out.println("UI font: " + active.name() + " (no usable font file in "
                + FONT_DIRECTORY.toAbsolutePath() + ")");
        return active;
    }

    /**
     * Replaces the process-wide face, for a tool that has to draw more than one in a frame.
     *
     * <p>This exists for {@code FontPreview}, and the need is real rather than convenience: choosing a
     * typeface is a judgement about how it looks at the sizes the game actually draws, so the only useful
     * comparison is several faces in <b>one image</b> — separate screenshots taken minutes apart, each
     * with its own lighting and framing, are exactly what a font comparison must not be. Since the whole
     * subsystem is deliberately one-font-per-process (see the class note), the only way to get a second
     * one into a frame is to say so out loud.
     *
     * <p>Not for gameplay: every widget measures text through the static {@link #widthOf}, so swapping
     * the face mid-frame changes what a layout computed a moment earlier. A tool that uses it must build
     * its {@link UIRenderer} <i>after</i> the call, since the atlas is uploaded in the constructor.
     *
     * @param set the face to use, or null to let the next {@link #active()} resolve from disk again
     */
    public static synchronized void use(GlyphSet set) {
        active = set;
        faces.clear();
    }

    /**
     * A face's glyphs, resolved on first use and GL-free like {@link #active()}, which is {@link Typeface#BODY}'s: the
     * first of its files in {@link #FONT_DIRECTORY} that rasterises, then the engine's own copy in
     * {@link #FONT_RESOURCES}, or else the face it falls back to.
     */
    public static synchronized GlyphSet face(Typeface face) {
        if (face == Typeface.BODY) {
            return active();
        }
        GlyphSet set = faces.get(face);
        if (set != null) {
            return set;
        }
        // The project's files first, then the engine's own copies of the same faces.
        for (boolean own : new boolean[] {false, true}) {
            for (String file : face.files()) {
                if (set != null) {
                    break;
                }
                ByteBuffer data = own ? GlyphSet.loadResource(FONT_RESOURCES + file)
                        : GlyphSet.load(FONT_DIRECTORY.resolve(file));
                if (data == null) {
                    continue;
                }
                try {
                    set = GlyphSet.trueType(data, file, WIDTH_SCALE);
                    System.out.println("UI font (" + face.name().toLowerCase(java.util.Locale.ROOT) + "): " + set.name()
                            + (own ? " (the engine's own)" : ""));
                } catch (RuntimeException e) {
                    System.err.println("font " + file + " could not be rasterised (" + e.getMessage() + ")");
                }
            }
        }
        if (set == null) {
            set = face(face.fallback());
        }
        faces.put(face, set);
        return set;
    }

    /** The UI's running-text face, {@link Typeface#BODY}. */
    public BitmapFont() {
        this(Typeface.BODY);
    }

    /** One face's atlases, uploaded. Must run on the GL thread. */
    public BitmapFont(Typeface face) {
        this.glyphs = face(face);
        List<GlyphSet> tiers = glyphs.tiers();
        this.textureIds = new int[tiers.size()];
        for (int i = 0; i < tiers.size(); i++) {
            textureIds[i] = upload(tiers.get(i));
        }
    }

    private static int upload(GlyphSet glyphs) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        // One byte per pixel and rows that are not a multiple of four wide: the default 4-byte unpack
        // alignment would shear the atlas diagonally.
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, glyphs.atlasWidth(), glyphs.atlasHeight(), 0,
                GL_RED, GL_UNSIGNED_BYTE, glyphs.pixels());
        // A pixel font that gets linearly filtered is just a blurry font; a rasterised one that gets
        // point-sampled throws away the antialiasing it was baked with and crawls as text moves. So the
        // filter follows the source rather than being a constant.
        //
        // The rasterised one is also *mipmapped*, and that is not an optimisation — it is what makes it
        // legible. Small text minifies the atlas by around three, and a single bilinear tap across three
        // texels either lands on a crossbar or does not: the first attempt at this rendered E as L and F
        // as I, losing exactly the thin horizontal strokes while stems survived. A mip level is a box
        // filter, so a stroke too thin to hit becomes grey rather than nothing.
        if (glyphs.isPixelArt()) {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        } else {
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }

    /** Where a character sits in the atlas and how it is placed, in font units. */
    public GlyphSet.Glyph glyph(char c) {
        return glyphs.glyph(c);
    }

    /** The same character in a larger tier's atlas — see {@link GlyphSet#tierFor}. */
    public GlyphSet.Glyph glyph(int tier, char c) {
        return glyphs.tiers().get(tier).glyph(c);
    }

    /** Which atlas text drawn at {@code scale} pixels per font unit should sample. */
    public int tierFor(float scale) {
        return glyphs.tierFor(scale);
    }

    /** Whether a character has a real glyph; false means it renders as the fallback. */
    public static boolean covers(char c) {
        return active().covers(c);
    }

    /**
     * Width in pixels of a string at the given scale.
     *
     * <p>Never a character count times an advance — that was already wrong for the pixel font, whose last
     * glyph carries no trailing gap, and it is meaningless for a proportional one.
     */
    public static float widthOf(String text, float scale) {
        return active().widthOf(text) * scale;
    }

    /** Width in pixels of a string set in a face, at the given scale. */
    public static float widthOf(String text, float scale, Typeface face) {
        return face(face).widthOf(text) * scale;
    }

    /** The height of a line of capitals — which is what every row height here is written against. */
    public static float heightOf(float scale) {
        return GLYPH_HEIGHT * scale;
    }

    /** A representative one-character step, for callers stepping a fixed number of characters. */
    public static float advance(float scale) {
        return active().advance() * scale;
    }

    /**
     * Renders a built-in glyph back to text, for proof-reading the table without a GL context.
     *
     * <p>About {@link BuiltInGlyphs} specifically, not about whatever face is loaded: this is the
     * verification path for the hand-typed art, where a wrong bit is otherwise invisible until it draws.
     */
    public static String toAsciiArt(char c) {
        int index = c < GlyphSet.FIRST_CHAR || c > GlyphSet.LAST_CHAR ? -1 : c - GlyphSet.FIRST_CHAR;
        String glyph = index < 0 || index >= BuiltInGlyphs.ART.length
                ? BuiltInGlyphs.FALLBACK : BuiltInGlyphs.ART[index];
        return String.join("\n", glyph.split("\\|"));
    }

    /** Number of built-in glyphs defined; a test pins this against the covered ASCII range. */
    public static int glyphCount() {
        return BuiltInGlyphs.ART.length;
    }

    public void bind(int unit) {
        bind(unit, 0);
    }

    public void bind(int unit, int tier) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, textureIds[tier]);
    }

    public void delete() {
        glDeleteTextures(textureIds);
    }
}
