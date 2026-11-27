package org.zeronura.nulzs.render;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * An off-screen render target the scene is drawn into so it can be post-processed before it reaches the
 * window.
 *
 * <h2>HDR, which is the whole point</h2>
 * The colour attachment is {@code RGBA16F}, not the 8-bit-per-channel of the default framebuffer, so
 * values <b>above 1</b> survive — a bright specular glint, the sun disc, a rim highlight. Rendered
 * straight to the window those would clamp to white and there would be nothing for bloom to find; kept in
 * float they can be thresholded and blurred into glow. A depth attachment comes with it (a renderbuffer,
 * since nothing samples the depth) so the scene's opaque and transparent passes sort as they do on screen.
 *
 * <h2>Resizable</h2>
 * The window can be resized, so this can be too: {@link #resize} throws the textures away and remakes them
 * at the new size. A colour target that stayed a fixed size would stretch or crop as the window changed.
 *
 * <h2>Depth is a texture, not a renderbuffer</h2>
 * Nothing sampled it for a long time and a renderbuffer was the honest choice then. {@link GlobalIllumination}
 * reconstructs a view-space position for every pixel out of it, so it has to be readable — and a depth
 * texture is the same format, the same attachment and the same cost, so this is strictly the more capable
 * of the two. Filtered {@code GL_NEAREST}: a depth halfway between two surfaces describes neither, and
 * every reader here reconstructs a position from it rather than shading with it.
 *
 * <h2>The optional geometry attachment</h2>
 * A second colour target carrying what a screen-space pass needs and cannot recover from colour alone: the
 * world normal, and how much of the fragment's light came from the ambient fill rather than the key. Only
 * asked for by the scene buffer — a preview that only wants a picture, and the water's mirrored render,
 * both pass {@code false} and pay nothing.
 *
 * <p>Its draw buffer is switched explicitly rather than left on: a fragment shader that does not declare an
 * output for an enabled draw buffer leaves <b>undefined</b> values in it, so every pass that writes no
 * geometry must go through {@link #bindColorOnly()}. That is the same family of trap as indexing a sampler
 * array by a computed value — it works on the driver it was written against and is not guaranteed anywhere.
 *
 * <h2>Multisampled</h2>
 * Made with {@code samples} above 1, everything drawn goes into a second framebuffer of multisampled renderbuffers
 * of the same formats: each pixel along the edge of a triangle takes that many samples of coverage and depth, while
 * the fragment shader still runs once per pixel, so edges come out smooth and textures — pixel art included — are left
 * exactly as they were. {@link #resolve} averages the samples into the textures, which is what every reader reads;
 * drawing goes on into the samples afterwards, so a pass after a screen-space one keeps its smooth edges too.
 *
 * <h2>Self-contained binding</h2>
 * {@link #bind} records nothing and restores nothing — the caller drives the sequence (bind this, render,
 * bind another target, post-process). It only sets the viewport to its own size, which the caller must
 * reset when it moves on, exactly as it would after any framebuffer switch.
 */
public class Framebuffer {

    private static final int[] COLOR_AND_GEOMETRY = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
    private static final int[] COLOR_ONLY = {GL_COLOR_ATTACHMENT0, GL_NONE};

    /**
     * What the geometry target is cleared to: a zero normal, biased into 0..1, and no ambient fill.
     *
     * <p>A zero normal is the buffer's way of saying "nothing here", and readers test for it. The colour
     * clear cannot supply it — {@code glClear} paints every enabled draw buffer with the one clear colour,
     * and the sky blue the scene wants would decode to a normal pointing somewhere definite.
     */
    private static final float[] NO_GEOMETRY = {0.5f, 0.5f, 0.5f, 0f};

    private final boolean geometry;
    /** Samples per pixel drawn into, 1 for none beyond its middle: then {@link #msaaFbo} is not made. */
    private final int samples;

    private int fbo;
    private int msaaFbo;
    private int msaaColor;
    private int msaaGeometry;
    private int msaaDepth;
    private int colorTexture;
    private int geometryTexture;
    private int depthTexture;
    private int width;
    private int height;

    public Framebuffer(int width, int height) {
        this(width, height, false);
    }

    /**
     * @param geometry whether to carry the normal/fill target a screen-space lighting pass needs. See the
     *                 class note — it also changes what {@link #bind} enables, so it is not free to leave on
     */
    public Framebuffer(int width, int height, boolean geometry) {
        this(width, height, geometry, 1);
    }

    /**
     * @param samples how many samples each pixel takes along the edges of what is drawn — see the class note — or 1
     *                for none; held to what the graphics card can take
     */
    public Framebuffer(int width, int height, boolean geometry, int samples) {
        this.geometry = geometry;
        this.samples = samplesFor(samples);
        create(width, height);
    }

    /** The samples a target asked for {@code samples} takes: as many as the graphics card can, 1 for none. */
    public static int samplesFor(int samples) {
        return samples > 1 ? Math.min(samples, Math.max(1, glGetInteger(GL_MAX_SAMPLES))) : 1;
    }

    private void create(int width, int height) {
        this.width = width;
        this.height = height;

        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        colorTexture = colorAttachment(width, height, GL_COLOR_ATTACHMENT0, GL_LINEAR);
        if (geometry) {
            // Nearest, because this is data rather than a picture: a normal blended halfway between two
            // surfaces points somewhere neither of them faces.
            geometryTexture = colorAttachment(width, height, GL_COLOR_ATTACHMENT1, GL_NEAREST);
        }

        depthTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, width, height, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("scene framebuffer incomplete");
        }

        if (samples > 1) {
            msaaFbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo);
            msaaColor = multisampled(GL_RGBA16F, GL_COLOR_ATTACHMENT0);
            if (geometry) {
                msaaGeometry = multisampled(GL_RGBA16F, GL_COLOR_ATTACHMENT1);
            }
            msaaDepth = multisampled(GL_DEPTH_COMPONENT24, GL_DEPTH_ATTACHMENT);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("multisampled scene framebuffer incomplete");
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** A multisampled renderbuffer at this size, attached to the framebuffer bound. */
    private int multisampled(int format, int attachment) {
        int buffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, buffer);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, format, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, buffer);
        return buffer;
    }

    /** The framebuffer drawn into: the multisampled one, when there is one. */
    private int drawn() {
        return samples > 1 ? msaaFbo : fbo;
    }

    /**
     * Averages what has been drawn into the samples into the textures — colour, geometry and depth — for a pass that
     * reads them: a screen-space pass mid-frame, or the post-processing at its end. Nothing to do for a target that is
     * not multisampled. Leaves this bound for drawing on, every attachment enabled, as {@link #bind} does.
     */
    public void resolve() {
        if (samples <= 1) {
            return;
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST);
        if (geometry) {
            glReadBuffer(GL_COLOR_ATTACHMENT1);
            glDrawBuffer(GL_COLOR_ATTACHMENT1);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glDrawBuffers(COLOR_AND_GEOMETRY);
        }
        bind();
    }

    private static int colorAttachment(int width, int height, int attachment, int filter) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        // Clamp so a blur or composite reading past the edge repeats the border rather than wrapping the
        // opposite side into frame.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, texture, 0);
        return texture;
    }

    /**
     * Makes this the render target and sets the viewport to its size, with every attachment it has enabled
     * for writing.
     *
     * <p>For the passes that fill the geometry buffer. Anything drawing into this afterwards that does not
     * must switch to {@link #bindColorOnly()} first.
     */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, drawn());
        if (geometry) {
            glDrawBuffers(COLOR_AND_GEOMETRY);
        }
        glViewport(0, 0, width, height);
    }

    /**
     * Clears colour and depth, and the geometry target to "no surface here" if there is one.
     *
     * <p>Not the same as {@code glClear} on its own, which is what makes it worth a method. That paints
     * every enabled draw buffer with the single clear colour, so the geometry target would come out holding
     * the scene's own background blue read as a normal — a direction pointing somewhere definite, on every
     * pixel nothing was drawn to.
     *
     * <p>Today no reader would notice, because the sky is the only thing that leaves a pixel unwritten and
     * every reader rejects it on depth first. That is a property of the current pass order rather than of
     * this class, and it is the kind that stops being true when somebody adds a pass.
     */
    public void clear() {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        if (geometry) {
            glClearBufferfv(GL_COLOR, 1, NO_GEOMETRY);
        }
    }

    /**
     * The same, with the geometry target switched off — for a pass whose shader writes colour only.
     *
     * <p>Not an optimisation. An enabled draw buffer that the fragment shader declares no output for gets
     * undefined values, and every translucent pass here (the water, the creatures, the sky, the rain) would
     * additionally have its blend function applied to a normal, which produces a direction pointing
     * somewhere no surface faces.
     */
    public void bindColorOnly() {
        glBindFramebuffer(GL_FRAMEBUFFER, drawn());
        if (geometry) {
            glDrawBuffers(COLOR_ONLY);
        }
        glViewport(0, 0, width, height);
    }

    /** Rebuilds the attachments at a new size, if it actually changed. */
    public void resize(int width, int height) {
        if (width == this.width && height == this.height) {
            return;
        }
        delete();
        create(width, height);
    }

    /**
     * The framebuffer handle, for a pass that renders <b>into</b> this target from outside.
     *
     * <p>{@link #bind} is the ordinary way in and covers everything drawn as part of the scene. This exists
     * for a self-contained pass that owns intermediate targets of its own and has to come back here to
     * composite — the screen-space fluid, which spends three passes in its own framebuffers and one in this
     * one. Such a pass cannot use {@code bind()}, because that would also reset the viewport to this
     * target's size mid-sequence, which is exactly what it is managing.
     */
    public int id() {
        return drawn();
    }

    public int colorTexture() {
        return colorTexture;
    }

    /** The scene depth, for a screen-space pass that reconstructs positions from it. */
    public int depthTexture() {
        return depthTexture;
    }

    /** World normal in {@code rgb} (biased to 0..1) and ambient-fill fraction in {@code a}, or 0 if none. */
    public int geometryTexture() {
        return geometryTexture;
    }

    public boolean hasGeometry() {
        return geometry;
    }

    /** How many samples each pixel takes, 1 for a target that is not multisampled. */
    public int samples() {
        return samples;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void delete() {
        glDeleteFramebuffers(fbo);
        glDeleteTextures(colorTexture);
        if (geometry) {
            glDeleteTextures(geometryTexture);
        }
        glDeleteTextures(depthTexture);
        if (samples > 1) {
            glDeleteFramebuffers(msaaFbo);
            glDeleteRenderbuffers(msaaColor);
            if (geometry) {
                glDeleteRenderbuffers(msaaGeometry);
            }
            glDeleteRenderbuffers(msaaDepth);
        }
    }
}
