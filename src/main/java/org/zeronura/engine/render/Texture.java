package org.zeronura.engine.render;

import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.stb.STBImage.*;

public class Texture {

    private final int id;
    private final int width;
    private final int height;

    /** The size cap in force when this was uploaded. See {@link #anyArtAtOtherCap}. */
    private int uploadCap;

    /**
     * {@code GL_TEXTURE_2D} for an ordinary image, {@code GL_TEXTURE_2D_ARRAY} for a layered one.
     *
     * <p>Carried on the texture rather than passed at every {@link #bind} because a caller that binds an
     * array texture to the 2D target gets a silently black sampler, not an error.
     */
    private final int target;

    /** How many layers this texture holds — 1 for an ordinary image, more for an array. */
    private final int layers;

    /**
     * Every byte this texture occupies on the GPU, mip tail included, so {@link #delete} gives back
     * exactly what {@link #upload} took. Held per texture rather than recomputed, because the dimensions
     * a texture reports are its <i>base level's</i> and the accounting has to survive a future path that
     * uploads something else.
     */
    private final long bytes;

    private static long residentBytes;
    private static int residentCount;
    private static long peakBytes;

    private Texture(int id, int width, int height) {
        this(id, width, height, GL_TEXTURE_2D);
    }

    private Texture(int id, int width, int height, int target) {
        this(id, width, height, target, 1);
    }

    private Texture(int id, int width, int height, int target, int layers) {
        this(id, width, height, target, layers, vramBytes(width, height, layers));
    }

    /** {@code bytes} is what it occupies on the GPU, for one without the mip tail {@link #vramBytes} assumes. */
    private Texture(int id, int width, int height, int target, int layers, long bytes) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.target = target;
        this.layers = layers;
        this.bytes = bytes;
        residentBytes += bytes;
        residentCount++;
        peakBytes = Math.max(peakBytes, residentBytes);
    }

    /**
     * What one texture costs on the GPU: RGBA8 plus its mip tail.
     *
     * <p>The tail is the <b>4/3</b> a full mip chain adds — each level is a quarter of the one above, and
     * the sum of that series is a third again. It is not optional and it is not small: mipmapping is what
     * makes a distant surface sample a few texels instead of thrashing the cache, so the alternative to
     * paying it is a world that shimmers. What it does mean is that <b>a 2048² texture is 21.3 MB rather
     * than the 16 MB its base level suggests</b>, and that every level is resident whether anything is
     * near enough to sample it or not.
     */
    private static long vramBytes(int width, int height, int layers) {
        return (long) width * height * layers * 4L * 4L / 3L;
    }

    /**
     * Every byte of texture currently on the GPU, and the count of textures holding it.
     *
     * <p>Exists because <b>texture memory is the one cost in this renderer that no frame timing can
     * show</b>: it is paid at load, never appears in a pass, and is only felt when the driver starts
     * evicting — at which point the symptom is a stutter somewhere else entirely. Everything else here is
     * measured by a probe; this is the number those probes had no way to report.
     *
     * <p>Accounted at upload and given back at {@link #delete}, so it tracks residency rather than total
     * bytes ever loaded. {@code peak} is what a session actually needed at its worst, which for a game
     * that swaps character models and builds pools lazily is a different number from the current one.
     */
    public static synchronized long residentTextureBytes() {
        return residentBytes;
    }

    /** @see #residentTextureBytes() */
    public static synchronized int residentTextureCount() {
        return residentCount;
    }

    /** @see #residentTextureBytes() */
    public static synchronized long peakTextureBytes() {
        return peakBytes;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * No cap: every texture is uploaded at whatever size its file is authored at.
     *
     * <p>Declared above {@link #maxDimension} rather than beside it, because a static field initialised
     * from a constant below it is an <b>illegal forward reference</b> — a compile error rather than a
     * wrong value, but one this codebase has already met once and spent a build on.
     */
    public static final int UNCAPPED = Integer.MAX_VALUE;

    /**
     * The largest side any texture loaded from art may have, or {@link #UNCAPPED} for the file's own size.
     *
     * <p><b>This is the one lever that reduces what textures cost, and it is worth being clear about what
     * it is not.</b> The instinct with a folder of 2048&sup2; art is "sample something smaller when the
     * object is far away", and that is already happening, in hardware, better than a hand-rolled version
     * could: every texture here is uploaded with a full mip chain and sampled
     * {@code GL_LINEAR_MIPMAP_LINEAR}, so the level a fragment reads is chosen per fragment from the
     * screen-space derivatives of its own texture coordinates — which accounts for distance, for
     * perspective, and for the angle the surface is seen at, none of which a distance test knows.
     *
     * <p>What mipmapping does not do is make a texture smaller <b>in memory</b>. Every level is resident
     * whether anything is near enough to sample it or not, and the tail makes each texture a third larger
     * than its base level suggests. Measured with {@code tools.TextureMemoryProbe}, a session's art is
     * <b>787 MB</b> — and since bytes go as the square of the side, each halving of this leaves a quarter.
     * That is the saving; the cost is that near surfaces genuinely lose detail, because the level the
     * hardware would have picked up close no longer exists.
     */
    private static volatile int maxDimension = UNCAPPED;

    /**
     * Caps every texture loaded <i>after</i> this call. Set it before anything loads.
     *
     * <p>Deliberately not retroactive, and it cannot be: a texture is uploaded once and its levels are
     * the driver's from then on, so changing this re-sizes nothing already resident. {@code Engine} sets
     * it from the settings before a session's art is loaded, which is why the setting takes effect on the
     * next world rather than immediately.
     *
     * <p>Applies to art — files and embedded images — and <b>not</b> to {@link #fromPixels} or
     * {@link #solidColor}, whose sizes the program chose for itself. Downsampling the font atlas, a baked
     * impostor sprite or a 1x1 stand-in would be shrinking something already sized to its purpose.
     */
    public static void setMaxDimension(int maxSide) {
        maxDimension = maxSide <= 0 ? UNCAPPED : maxSide;
    }

    /** @see #setMaxDimension(int) */
    public static int getMaxDimension() {
        return maxDimension;
    }

    /** Trilinear only: one isotropic sample per fragment, whatever angle the surface is seen at. */
    public static final int NO_ANISOTROPY = 1;

    /**
     * How many samples the filter may take along a fragment's long axis in texture space.
     *
     * <p><b>This is the other half of mipmapping and it fixes what mipmapping gets wrong.</b> A mip level
     * is chosen per fragment from the screen-space derivatives of its texture coordinates, and there is
     * only one level to choose — so the hardware has to pick a level coarse enough for the <i>widest</i>
     * of the two axes or the narrow one aliases. On a surface seen face-on the two axes are the same and
     * that choice is exact. On one raking away from the camera they are not: the footprint is a long thin
     * sliver, the level is picked for its length, and the fragment is served art blurred by that much
     * across its width as well. That is why ground textures go to mush a few metres ahead of the player
     * and why this codebase's own notes record a triplanar sample arriving as "a mosaic of its own mip
     * texels" at grazing angles.
     *
     * <p>Anisotropic filtering takes several samples along the long axis instead and picks the level for
     * the <i>short</i> one, so a raking surface keeps its detail. It is the one texture setting here that
     * <b>costs</b> frame time rather than saving it — up to this many samples per fetch, though only for
     * the fragments that are actually skewed, which is why the measured cost is far below the multiplier.
     *
     * <p>It also removes shimmer rather than adding it, which is the non-obvious part: the alternative to
     * a too-coarse level is a too-fine one, and a too-fine level on a skewed fragment is undersampling —
     * the ground crawling as the player walks. Anisotropy is what lets the level be correct in both axes
     * at once.
     */
    private static volatile int anisotropy = NO_ANISOTROPY;

    /**
     * Sets the filter's sample budget, for textures already resident as well as ones loaded afterwards.
     *
     * <p><b>RETROACTIVE, unlike {@link #setMaxDimension} beside it, and the difference is worth being
     * precise about because this file first assumed otherwise.</b> A texture's <i>size</i> is decided when
     * its pixels are uploaded and cannot be revisited without uploading them again. Anisotropy is not part
     * of the upload at all — it is a sampler parameter, settable on a live texture object at any time — so
     * there is nothing stopping it applying to what is already loaded.
     *
     * <p>That matters more than it sounds. This was shipped as "takes effect on the next world", which in
     * play is a row that visibly does nothing: the player changes it, looks at the ground, sees no
     * difference, and reasonably concludes the menu is broken. It is the same failure this project's own
     * rule already names — a control that appears inert is indistinguishable from one that is — and it was
     * reported as exactly that.
     *
     * <p>Must be called on the GL thread, since it re-parameterises live textures. Only mipmapped ones are
     * touched: anisotropy refines <i>which mip level</i> to read, so on a texture with one level there is
     * nothing for the hardware to do with it.
     *
     * <p>Clamped to what the driver actually offers rather than trusted — {@code GL_TEXTURE_MAX_ANISOTROPY}
     * is silently ignored above the implementation's maximum on some drivers and an error on others, and
     * neither is a thing to find out about from a rendering artefact. A driver without the extension
     * leaves this at {@link #NO_ANISOTROPY} whatever it is set to, which is trilinear — the filtering this
     * renderer had before, so the degraded case is the old behaviour rather than a failure.
     */
    public static void setAnisotropy(int samples) {
        int clamped = Math.max(NO_ANISOTROPY, Math.min(samples, maxSupportedAnisotropy()));
        if (clamped == anisotropy) {
            return;
        }
        anisotropy = clamped;
        for (Texture texture : MIPMAPPED) {
            glBindTexture(texture.target, texture.id);
            applyAnisotropy(texture.target);
        }
    }

    /**
     * Every live mipmapped texture, so a change of anisotropy can reach the ones already loaded.
     *
     * <p>A plain list rather than anything weaker: these are all explicitly deleted (see {@link #delete}),
     * because a GL object is not something the garbage collector can free, so there is no lifetime here
     * that a weak reference would be tracking. Entries are removed on delete, which is what keeps a
     * swapped character model — the one thing here that is loaded and released repeatedly — from growing
     * this without bound.
     *
     * <p>Holds only mipmapped textures. The font atlas at {@code GL_NEAREST}, a 1x1 stand-in and every
     * framebuffer attachment are not in it and have no business being re-parameterised.
     */
    private static final java.util.List<Texture> MIPMAPPED = new java.util.ArrayList<>();

    /** @see #setAnisotropy(int) */
    public static int getAnisotropy() {
        return anisotropy;
    }

    /**
     * The largest sample count this driver supports, or {@link #NO_ANISOTROPY} if it has no anisotropic
     * filtering at all.
     *
     * <p>Needs a current GL context — it queries one. {@code EXT_texture_filter_anisotropic} is twenty-five
     * years old and core since GL 4.6, so the fallback is close to unreachable in practice; it is here
     * because a renderer that refuses to start over a missing filter is a worse failure than one that
     * looks slightly softer.
     */
    public static int maxSupportedAnisotropy() {
        if (!GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
            return NO_ANISOTROPY;
        }
        return (int) glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
    }

    /**
     * Applies the current anisotropy to whatever texture is bound to {@code target}.
     *
     * <p>Called from every path that generates a mip chain, and from none that does not: anisotropy is a
     * refinement of <i>which mip level</i> to read, so on an unmipped texture it is a parameter the
     * hardware has nothing to do with.
     */
    private static void applyAnisotropy(int target) {
        int samples = anisotropy;
        if (samples <= NO_ANISOTROPY) {
            return;
        }
        glTexParameterf(target, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, samples);
    }

    public static Texture fromFile(String path) {
        return fromFile(path, GL_REPEAT, false);
    }

    /**
     * Loads a texture with an explicit wrap mode, optionally repairing its transparent pixels.
     *
     * @param wrapMode        {@code GL_REPEAT} for anything that tiles (bark up a trunk),
     *                        {@code GL_CLAMP_TO_EDGE} for a cutout sprite that must not bleed across
     *                        its own edges
     * @param bleedRgbIntoTransparent see {@link #bleedRgbIntoTransparent} — needed for any cutout image
     *                                whose transparent pixels are not already the colour of their
     *                                visible neighbours
     */
    public static Texture fromFile(String path, int wrapMode, boolean bleedRgbIntoTransparent) {
        return fromFile(path, wrapMode, bleedRgbIntoTransparent, UNCAPPED);
    }

    /**
     * {@link #fromFile(String, int, boolean)}, no larger on a side than {@code maxSide} either — halved until it fits, as
     * {@link #setMaxDimension} halves everything — for art authored bigger than it is ever seen: bark up a trunk, say.
     * The smaller of this and the cap for all art is the one kept to.
     */
    public static Texture fromFile(String path, int wrapMode, boolean bleedRgbIntoTransparent, int maxSide) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            ByteBuffer pixels = stbi_load(path, w, h, channels, 4);
            if (pixels == null) {
                throw new RuntimeException("Failed to load texture '" + path + "': " + stbi_failure_reason());
            }
            try {
                // BLED FIRST, THEN SHRUNK, and the order is not interchangeable: the bleed exists because
                // averaging transparent black into an edge texel puts a dark halo round cutout art, and a
                // downsample is exactly such an average. Shrinking first would bake in the very halo the
                // next line is there to prevent.
                if (bleedRgbIntoTransparent) {
                    bleedRgbIntoTransparent(pixels, w.get(0), h.get(0));
                }
                return uploadCapped(pixels, w.get(0), h.get(0), wrapMode, maxSide);
            } finally {
                stbi_image_free(pixels);
            }
        }
    }

    public static Texture fromMemory(ByteBuffer encodedImage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            ByteBuffer pixels = stbi_load_from_memory(encodedImage, w, h, channels, 4);
            if (pixels == null) {
                throw new RuntimeException("Failed to decode embedded texture: " + stbi_failure_reason());
            }
            try {
                return uploadCapped(pixels, w.get(0), h.get(0), GL_REPEAT);
            } finally {
                stbi_image_free(pixels);
            }
        }
    }

    /**
     * Uploads pixels the program produced itself rather than loaded from disk.
     *
     * <p>The path a <b>baked impostor</b> takes: grass billboards are rendered from the real tuft mesh
     * at startup and read back, so there is no file to decode and the bytes are already RGBA. Clamped
     * rather than repeated, since a sprite that wraps bleeds its far edge into its near one.
     *
     * @param pixels tightly packed RGBA, {@code width * height * 4} bytes
     */
    public static Texture fromPixels(ByteBuffer pixels, int width, int height) {
        return upload(pixels, width, height, GL_CLAMP_TO_EDGE);
    }

    /**
     * Uploads one frame of an animation streamed from disk: a picture on screen for a moment, then replaced by the
     * next. Unlike {@link #fromPixels} it gets <b>no mip chain</b> — building one for a picture gone a frame later is
     * wasted work, and a frame is drawn at about its own size, where only the full-size level is read. Smoothly
     * filtered and clamped, so a quad drawn past its edges spreads its edge pixels outward.
     *
     * @param pixels tightly packed RGBA, {@code width * height * 4} bytes
     */
    public static Texture frame(ByteBuffer pixels, int width, int height) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        // The one level is the whole chain, so the texture is complete whichever filter is asked of it later.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        return new Texture(id, width, height, GL_TEXTURE_2D, 1, (long) width * height * 4L);
    }

    /**
     * An empty picture to draw into through a framebuffer: RGBA8, cleared to nothing, smoothly filtered and clamped,
     * with no mip chain (what is drawn into it changes every frame). Attach {@link #id} as a framebuffer's colour.
     */
    public static Texture renderTarget(int width, int height) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        return new Texture(id, width, height, GL_TEXTURE_2D, 1, (long) width * height * 4L);
    }

    /** The OpenGL name of this texture, for attaching it to a framebuffer. */
    public int id() {
        return id;
    }

    public static Texture solidColor(int r, int g, int b, int a) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        return upload(pixel, 1, 1);
    }

    /**
     * Floods visible colour outward into fully transparent pixels, leaving alpha untouched.
     *
     * <p>Cutout art is usually authored with transparent pixels left at {@code (0,0,0,0)} — which is
     * exactly what {@code leaf_maple.png} has. That is invisible at full resolution, because those texels
     * are discarded anyway, but mipmap generation averages the black straight into the edge texels and
     * every leaf grows a dark halo as it recedes. Filling those pixels with the colour of their visible
     * neighbours means the averages stay leaf-coloured however far the chain is walked.
     *
     * <p>Runs as repeated one-pixel dilation passes until the transparent region is covered, and each pass
     * averages only over pixels that were known <i>before</i> it began — so a pixel's colour comes from the
     * nearest real content rather than from a value smeared outward within the same sweep. Pure array work
     * — no GL, so it is directly testable.
     *
     * <h2>It walks a frontier, and the whole-image version of this cost EIGHT SECONDS OF EVERY SESSION</h2>
     * The obvious implementation rescans the entire image on every pass. That is fine for a 126x130 leaf,
     * which is what it was written against and where the transparent margin is a handful of pixels wide.
     * It is catastrophic for the 1536x1024 inventory icons: the apple is 868x937 of picture in the middle
     * of its canvas, so the fill has to travel some <b>330 pixels</b>, and each of those passes sweeps 1.6
     * million pixels probing eight neighbours apiece — about four billion operations per icon. Measured,
     * the nine icons took <b>7.8 seconds</b>, and because {@code UiIcons.warm()} is called from
     * {@code Hud.render} that was paid on the first frame that drew the HUD: the loading screen finished
     * and the game then froze for eight seconds exactly as the player gained control.
     *
     * <p>Keeping a frontier — the pixels filled by the previous pass — and examining only their unknown
     * neighbours makes the whole dilation <b>linear in the image</b> instead of quadratic in the distance
     * it has to travel, because each pixel is filled once and considered a bounded number of times. Same
     * output, since a candidate still averages over every neighbour known at the start of its pass;
     * {@code TextureBleedTest} pins that equivalence rather than leaving it to be believed.
     *
     * @param pixels tightly packed RGBA, modified in place
     * @return the number of pixels filled, for tests and diagnostics
     */
    public static int bleedRgbIntoTransparent(ByteBuffer pixels, int width, int height) {
        boolean[] known = new boolean[width * height];
        for (int i = 0; i < known.length; i++) {
            known[i] = (pixels.get(i * 4 + 3) & 0xFF) != 0;
        }

        int filledTotal = 0;
        int[] neighbourX = {-1, 1, 0, 0, -1, 1, -1, 1};
        int[] neighbourY = {0, 0, -1, 1, -1, -1, 1, 1};

        // The frontier is what the previous pass filled; the first pass grows from the art itself.
        int[] frontier = new int[width * height];
        int frontierCount = 0;
        for (int i = 0; i < known.length; i++) {
            if (known[i]) {
                frontier[frontierCount++] = i;
            }
        }

        int[] candidates = new int[width * height];
        int[] filledNow = new int[width * height];
        boolean[] queued = new boolean[width * height];

        while (frontierCount > 0) {
            // Every unknown pixel touching the frontier, each listed once — `queued` is what stops a
            // candidate with several frontier neighbours being averaged and written repeatedly.
            int candidateCount = 0;
            for (int f = 0; f < frontierCount; f++) {
                int index = frontier[f];
                int x = index % width;
                int y = index / width;
                for (int n = 0; n < neighbourX.length; n++) {
                    int nx = x + neighbourX[n];
                    int ny = y + neighbourY[n];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                        continue;
                    }
                    int neighbour = ny * width + nx;
                    if (known[neighbour] || queued[neighbour]) {
                        continue;
                    }
                    queued[neighbour] = true;
                    candidates[candidateCount++] = neighbour;
                }
            }

            int filled = 0;
            for (int c = 0; c < candidateCount; c++) {
                int index = candidates[c];
                queued[index] = false;
                int x = index % width;
                int y = index / width;
                int r = 0;
                int g = 0;
                int b = 0;
                int count = 0;
                // Averaged over every neighbour known at the START of this pass, not only the frontier
                // ones — which is what makes this identical to sweeping the whole image, rather than
                // merely similar.
                for (int n = 0; n < neighbourX.length; n++) {
                    int nx = x + neighbourX[n];
                    int ny = y + neighbourY[n];
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                        continue;
                    }
                    int neighbour = ny * width + nx;
                    if (!known[neighbour]) {
                        continue;
                    }
                    r += pixels.get(neighbour * 4) & 0xFF;
                    g += pixels.get(neighbour * 4 + 1) & 0xFF;
                    b += pixels.get(neighbour * 4 + 2) & 0xFF;
                    count++;
                }
                if (count == 0) {
                    continue;
                }
                pixels.put(index * 4, (byte) (r / count));
                pixels.put(index * 4 + 1, (byte) (g / count));
                pixels.put(index * 4 + 2, (byte) (b / count));
                // Alpha is deliberately left alone: this changes what the pixel looks like when
                // averaged, never whether it is drawn.
                filledNow[filled++] = index;
            }

            // Marked known only after the whole pass, or a pixel filled early in it would feed the average
            // of one filled later and the fill would smear along the scan order rather than outward.
            for (int i = 0; i < filled; i++) {
                known[filledNow[i]] = true;
            }
            System.arraycopy(filledNow, 0, frontier, 0, filled);
            frontierCount = filled;
            filledTotal += filled;
        }
        return filledTotal;
    }

    private static Texture upload(ByteBuffer pixels, int width, int height) {
        return upload(pixels, width, height, GL_REPEAT);
    }

    /**
     * Uploads art, shrunk first if {@link #setMaxDimension} says so.
     *
     * <p>Separate from {@link #upload} rather than folded into it, because the cap is a statement about
     * <i>art</i>: a font atlas, a baked impostor and a 1x1 stand-in all go through the plain path and none
     * of them wants shrinking.
     */
    private static Texture uploadCapped(ByteBuffer pixels, int width, int height, int wrapMode) {
        return uploadCapped(pixels, width, height, wrapMode, UNCAPPED);
    }

    private static Texture uploadCapped(ByteBuffer pixels, int width, int height, int wrapMode, int maxSide) {
        Capped capped = capToMaxDimension(pixels, width, height, Math.min(maxDimension, maxSide));
        try {
            return upload(capped.pixels(), capped.width(), capped.height(), wrapMode);
        } finally {
            // memAlloc/memFree, never allocateDirect and let go: the allocator that hands memory out is
            // the only thing entitled to take it back, which this codebase has already paid for once in a
            // double free that crashed inside the graphics driver.
            capped.free();
        }
    }

    /**
     * Pixels at or under the cap, and whatever has to be freed afterwards.
     *
     * <p>{@code owned} is null when nothing was shrunk, which is both the common case and the one that
     * must not free the caller's buffer — stb owns that, and freeing it here would be the double free this
     * codebase has already been bitten by.
     */
    record Capped(ByteBuffer pixels, int width, int height, ByteBuffer owned) {
        void free() {
            if (owned != null) {
                MemoryUtil.memFree(owned);
            }
        }
    }

    /** The size {@link #capToMaxDimension(ByteBuffer, int, int, int)} brings a picture to, without the pixels. */
    static int[] halvedWithin(int width, int height, int cap) {
        while (Math.max(width, height) > cap && width > 1 && height > 1) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
        return new int[]{width, height};
    }

    /** Repeatedly halves until both sides are within {@link #maxDimension}. */
    static Capped capToMaxDimension(ByteBuffer pixels, int width, int height) {
        return capToMaxDimension(pixels, width, height, maxDimension);
    }

    /** Repeatedly halves until both sides are within {@code cap}. */
    static Capped capToMaxDimension(ByteBuffer pixels, int width, int height, int cap) {
        if (cap >= Math.max(width, height)) {
            return new Capped(pixels, width, height, null);
        }
        ByteBuffer source = pixels;
        ByteBuffer owned = null;
        int sourceWidth = width;
        int sourceHeight = height;
        while (Math.max(sourceWidth, sourceHeight) > cap && sourceWidth > 1 && sourceHeight > 1) {
            int halfWidth = Math.max(1, sourceWidth / 2);
            int halfHeight = Math.max(1, sourceHeight / 2);
            ByteBuffer next = MemoryUtil.memAlloc(halfWidth * halfHeight * 4);
            halve(source, sourceWidth, sourceHeight, next, halfWidth, halfHeight);
            if (owned != null) {
                MemoryUtil.memFree(owned);
            }
            owned = next;
            source = next;
            sourceWidth = halfWidth;
            sourceHeight = halfHeight;
        }
        return new Capped(source, sourceWidth, sourceHeight, owned);
    }

    /** One 2:1 box filter step, averaging each 2x2 block of RGBA source texels into one. */
    private static void halve(ByteBuffer src, int srcWidth, int srcHeight,
                              ByteBuffer dst, int dstWidth, int dstHeight) {
        for (int y = 0; y < dstHeight; y++) {
            int y0 = Math.min(y * 2, srcHeight - 1);
            int y1 = Math.min(y * 2 + 1, srcHeight - 1);
            for (int x = 0; x < dstWidth; x++) {
                int x0 = Math.min(x * 2, srcWidth - 1);
                int x1 = Math.min(x * 2 + 1, srcWidth - 1);
                int a = (y0 * srcWidth + x0) * 4;
                int b = (y0 * srcWidth + x1) * 4;
                int c = (y1 * srcWidth + x0) * 4;
                int d = (y1 * srcWidth + x1) * 4;
                int out = (y * dstWidth + x) * 4;
                for (int channel = 0; channel < 4; channel++) {
                    int sum = (src.get(a + channel) & 0xFF) + (src.get(b + channel) & 0xFF)
                            + (src.get(c + channel) & 0xFF) + (src.get(d + channel) & 0xFF);
                    dst.put(out + channel, (byte) ((sum + 2) / 4));
                }
            }
        }
    }

    private static Texture upload(ByteBuffer pixels, int width, int height, int wrapMode) {
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        applyAnisotropy(GL_TEXTURE_2D);
        return register(new Texture(id, width, height));
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(target, id);
    }

    /**
     * Switches between smooth (the default) and nearest-neighbour sampling.
     *
     * <p>Nearest is what pixel art wants: scaled up with linear filtering, every hard pixel edge becomes a
     * blurred ramp. It also skips the mip chain when minifying, because averaging a sprite sheet's texels
     * bleeds neighbouring frames into each other.
     *
     * @return this texture, for chaining after a load
     */
    /**
     * Replaces a rectangle of a 2D texture's pixels from a buffer holding the <i>whole</i> image, for a picture drawn
     * by the program and changed a little at a time — an editor's map. Mipmaps are left alone, so use it on a texture
     * with {@linkplain #setNearestFiltering nearest filtering}, which never reads them.
     *
     * @param pixels    tightly packed RGBA for the whole texture, {@code width * height * 4} bytes
     */
    public void updateRegion(ByteBuffer pixels, int x, int y, int regionWidth, int regionHeight) {
        if (regionWidth <= 0 || regionHeight <= 0) {
            return;
        }
        glBindTexture(GL_TEXTURE_2D, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, width);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, x);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, y);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, regionWidth, regionHeight, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    }

    public Texture setNearestFiltering(boolean nearest) {
        glBindTexture(target, id);
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, nearest ? GL_NEAREST : GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, nearest ? GL_NEAREST : GL_LINEAR);
        return this;
    }

    public void delete() {
        glDeleteTextures(id);
        residentBytes -= bytes;
        residentCount--;
        // Or a swapped character model — the one thing here loaded and released repeatedly — would leave
        // this list holding handles that no longer name anything, and the next anisotropy change would
        // bind deleted textures.
        MIPMAPPED.remove(this);
    }

    /** Adds a freshly uploaded mipmapped texture to the set {@link #setAnisotropy} re-parameterises. */
    private static Texture register(Texture texture) {
        texture.uploadCap = maxDimension;
        MIPMAPPED.add(texture);
        return texture;
    }

    /**
     * Whether any art currently resident was uploaded at a different size cap from the one now set.
     *
     * <p>Exists so the menu can say so. {@link #setMaxDimension} cannot be retroactive — a texture's size
     * is decided when its pixels are uploaded — so changing that setting mid-session does nothing the
     * player can see, and a row that appears inert is indistinguishable from a broken one. It was
     * reported as exactly that.
     *
     * <p>Asked of the textures themselves rather than tracked as a session flag, which makes it exact in
     * both directions and needs no notion of a session at all: it becomes true the moment the setting
     * moves away from what the resident art was loaded at, and clears itself when the next world reloads
     * that art. Lazy loading is handled for free — a highland tree loaded mid-session at the new cap does
     * not clear the flag while everything around it is still at the old one.
     */
    public static boolean anyArtAtOtherCap() {
        for (Texture texture : MIPMAPPED) {
            if (texture.uploadCap != maxDimension) {
                return true;
            }
        }
        return false;
    }

    /** How many layers this texture holds — 1 unless it is an array. */
    public int getLayers() {
        return layers;
    }

    /**
     * Pixels read back from a texture: tightly packed RGBA, row by row from the image's top and, for an array, layer
     * after layer. Free them with {@link #free} when done.
     */
    public record Pixels(ByteBuffer rgba, int width, int height, int layers) {

        /** One channel (0 red to 3 alpha) of one pixel, 0 to 255. */
        public int channel(int layer, int x, int y, int channel) {
            return rgba.get(((layer * height + y) * width + x) * 4 + channel) & 0xFF;
        }

        public void free() {
            MemoryUtil.memFree(rgba);
        }
    }

    /**
     * This texture's pixels at a mip level, every layer of them, read back from the GPU. For looking at art on the CPU
     * — what colour a picture is along its edge — without loading its files again; a level a few steps down the chain
     * is small and quick to read.
     */
    public Pixels readLevel(int level) {
        glBindTexture(target, id);
        int levelWidth = glGetTexLevelParameteri(target, level, GL_TEXTURE_WIDTH);
        int levelHeight = glGetTexLevelParameteri(target, level, GL_TEXTURE_HEIGHT);
        ByteBuffer rgba = MemoryUtil.memAlloc(levelWidth * levelHeight * layers * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glGetTexImage(target, level, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        return new Pixels(rgba, levelWidth, levelHeight, layers);
    }

    /** What this one texture costs on the GPU, mip tail included. */
    public long getBytes() {
        return bytes;
    }

    /**
     * Loads several equally-sized images into one {@code GL_TEXTURE_2D_ARRAY}, one layer each.
     *
     * <p>Exists because <b>GLSL 3.30 only permits an array of samplers to be indexed by a constant
     * expression</b>. A shader that picks a material per fragment therefore cannot hold
     * {@code sampler2D[N]} and index it by anything it computed — that is undefined behaviour which
     * happens to work on some drivers, the same trap as a reversed-edge {@code smoothstep}. A layer of an
     * array texture is just another texture <i>coordinate</i>, so it may be any expression at all. It also
     * costs one texture unit however many layers there are.
     *
     * <p>Every layer must be the same size, which is checked rather than assumed: a mismatched image would
     * otherwise be uploaded into a differently-shaped layer and come out as garbage rather than as an
     * error.
     *
     * @throws RuntimeException if any file fails to load, or the sizes disagree
     */
    public static Texture arrayFromFiles(java.util.List<String> paths) {
        return arrayFromFiles(paths, UNCAPPED);
    }

    /**
     * {@link #arrayFromFiles(java.util.List)}, every layer no larger on a side than {@code maxSide} either — halved
     * until it fits, as {@link #fromFile(String, int, boolean, int)} does — for layers authored at different sizes
     * that are all seen at the smaller: a layer counts as matching when it comes to the same size at that limit (a
     * 2048 picture beside 1024 ones, at 1024). The smaller of this and the cap for all art is the one uploaded at.
     *
     * @throws RuntimeException if any file fails to load, or the sizes disagree at {@code maxSide}
     */
    public static Texture arrayFromFiles(java.util.List<String> paths, int maxSide) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("no layers");
        }
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

        int width = 0;
        int height = 0;
        // The size on disk (brought within the caller's own maxSide, which is a statement about this art), kept apart
        // from the size uploaded: with the cap for all art in force those differ, and it is the authored one that says
        // whether two layers actually match.
        int layerWidth = 0;
        int layerHeight = 0;
        int cap = Math.min(maxDimension, maxSide);
        for (int layer = 0; layer < paths.size(); layer++) {
            String path = paths.get(layer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                ByteBuffer pixels = stbi_load(path, w, h, channels, 4);
                if (pixels == null) {
                    glDeleteTextures(id);
                    throw new RuntimeException(
                            "Failed to load texture '" + path + "': " + stbi_failure_reason());
                }
                // Shrunk to the cap, exactly as a 2D texture is, and the check below still compares the
                // FILE's own size — a layer that disagrees with layer 0 is a mismatched export and must be
                // reported as one, not silently rescued by both being capped to the same number.
                Capped capped = null;
                int[] own = halvedWithin(w.get(0), h.get(0), maxSide);
                try {
                    if (layer == 0) {
                        capped = capToMaxDimension(pixels, w.get(0), h.get(0), cap);
                        width = capped.width();
                        height = capped.height();
                        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, width, height, paths.size(),
                                0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                    } else if (own[0] != layerWidth || own[1] != layerHeight) {
                        glDeleteTextures(id);
                        throw new RuntimeException("Layer '" + path + "' is " + own[0] + "x" + own[1]
                                + " but layer 0 is " + layerWidth + "x" + layerHeight
                                + "; every layer of an array texture must be the same size");
                    } else {
                        capped = capToMaxDimension(pixels, w.get(0), h.get(0), cap);
                    }
                    if (layer == 0) {
                        layerWidth = own[0];
                        layerHeight = own[1];
                    }
                    glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, width, height, 1,
                            GL_RGBA, GL_UNSIGNED_BYTE, capped.pixels());
                } finally {
                    if (capped != null) {
                        capped.free();
                    }
                    stbi_image_free(pixels);
                }
            }
        }

        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        // The ground and building materials, which are the surfaces this matters most on: they are tiled
        // over terrain the player walks along, so most of what is on screen is seen at a grazing angle.
        applyAnisotropy(GL_TEXTURE_2D_ARRAY);
        // Layer count carried through, or an eight-layer array would be accounted as one image and the
        // largest single allocation in the renderer would read as the smallest.
        return register(new Texture(id, width, height, GL_TEXTURE_2D_ARRAY, paths.size()));
    }

    /**
     * An array texture from pixels already in memory, every layer {@code width} by {@code height} RGBA: for layers a
     * caller has brought to one size itself, as a terrain does with ground pictures of different sizes.
     */
    public static Texture arrayFromPixels(java.util.List<ByteBuffer> layers, int width, int height) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("no layers");
        }
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, id);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, width, height, layers.size(), 0, GL_RGBA, GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        for (int layer = 0; layer < layers.size(); layer++) {
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, width, height, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                    layers.get(layer));
        }
        glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        applyAnisotropy(GL_TEXTURE_2D_ARRAY);
        return register(new Texture(id, width, height, GL_TEXTURE_2D_ARRAY, layers.size()));
    }
}
