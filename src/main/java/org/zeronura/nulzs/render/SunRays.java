package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Sun rays: shafts of sunlight slanting down through the low air, cut off by the trees and buildings that stand in
 * them. A screen-space pass over the finished 3D frame, run by {@link ScreenEffects}; the {@link PostProcessor}'s
 * composite adds what it gathers to the picture, tinted the sun's colour.
 *
 * <h2>Shafts, as through a canopy (a world with no cloud sky)</h2>
 * The sunlight comes down in shafts: a soft pattern of patches across its path ({@value #SHAFT_SIZE} m or so wide,
 * about {@value #SHAFT_COVER} of it lit), fixed in the world and drifting slowly, each lighting a column of air all the
 * way along the sun's direction. Seen from above, as the games here mostly are, a column reads as a slanting beam from
 * the ground up toward the sun. Plain scattering, with every bit of sunlit air glowing, was tried first: from above,
 * the light along a view ray lies over the very ground it lights, so it only veiled the picture, beams or not.
 *
 * <h2>Cut by what is in the sun's way</h2>
 * Each pixel's view ray, from the eye to whatever the pixel shows, is walked in {@value #STEPS} steps
 * ({@code sun_rays.frag}); at each, the air glows if it is in a shaft and the sun's {@link ShadowMap} says nothing stands
 * between it and the sun. So a canopy or a roof stops the shafts it is in, and they go on past its edge.
 *
 * <h2>In the low air</h2>
 * The air glows thickest at the ground and thins above it ({@value #HAZE_HEIGHT} m to fall by e), so the shafts stand
 * between the trees and houses and the air between the camera and the treetops stays clear. The ground is the game's
 * to say ({@code groundY}): where the player stands.
 *
 * <h2>Brightest toward the sun</h2>
 * The air scatters partly forward, so shafts are strongest looking toward the sun, and an even part keeps them there,
 * fainter, whichever way the camera faces.
 *
 * <h2>Under a cloud sky: only real things in the sun's way</h2>
 * Under a cloud sky ({@link SkyDome}) there is no pattern: the light reaching a spot of air is what the clouds between
 * it and the sun let through (the dome's sun mask, as though the clouds were {@value #CLOUD_REACH} m off) and what the
 * shadow map says the trees, roofs and hills let past. A cloud over the sun dims every shaft and they come back as it
 * passes. And sunlit air only glows where it is a beam: where a ring of air round it, {@value #SHAFT_WIDTH} m off on
 * the level, is nearly all in shadow ({@code sun_rays.frag}). So beams stand between the trunks of a forest and past
 * the edge of a roof, and open sunshine, a square or a clearing, has none: the eye sees no shafts on a clear day in the
 * open, and lit air there would only veil the picture. Only the air within about {@value #NEAR_REACH} m of the player
 * counts, across the ground, fading after: farther off, a ray crosses beam after beam and they blur into a glow. The
 * walk is shorter, {@value #NEAR_DISTANCE} m, so its steps are short enough for narrow beams, and the beams brighter,
 * {@value #SHAFT_BRIGHTNESS} of the sun, as nothing else is lit.
 *
 * <p>Tried on the way: the old pattern gated on a cloud near the sun (it put shafts in clear air that nothing made);
 * every lit spot of air (a forest became a white veil); keeping only what was brighter than the blurred light round
 * it on the screen (that picked out the far gaps between trunks, not the beams).
 *
 * <p>The same mask makes the beams in the sky ({@link #renderBeams}): the open sky round the sun gives off light where
 * no cloud, hill or tree covers it, and that light is drawn out along the lines from the sun (a radial blur at a
 * quarter of the window's size), so every gap between clouds streams a beam across the picture and every cloud edge
 * and treetop on the skyline a shadow. They show only while something covers part of the sky round the sun, cloud or
 * whatever stands in front of it on the screen; a sun in open sky has only its glow. Nothing is painted over them: the
 * rays are the shapes of real clouds, trees and roofs. The composite adds them tinted by the sun.
 *
 * <h2>Cheap enough to leave on</h2>
 * Half the window's size each way, one hardware-filtered shadow tap a step (none where no shaft is), a starting
 * fraction of a step that differs from pixel to pixel, and a depth-aware blur across and then down
 * ({@code sun_rays_blur.frag}) that turns the staggered steps into smooth light without bleeding a shaft over the edge
 * of the building in front of it. About 0.8 ms a frame at 1920x1080 on a GeForce RTX 2060.
 */
public final class SunRays {

    /** Steps along each ray. */
    static final int STEPS = 32;
    /** How far along a ray the air is walked, in world units: about the shadow map's reach in front of the camera. */
    static final float MAX_DISTANCE = 56f;
    /** How high, above the ground, the haze takes to thin by e. */
    static final float HAZE_HEIGHT = 3f;
    /** How much more the air scatters forward than back: 0 evenly, toward 1 only straight on. */
    static final float ANISOTROPY = 0.35f;
    /**
     * How bright a metre of sunlit haze at ground level is, as a share of the sun's own light. Small: a beam is a few
     * metres of it, and a whole ray through open sunlit air is tens. It was 0.26 at first, which lit the air over the
     * ground white toward a low sun and hid the town under it.
     */
    static final float BRIGHTNESS = 0.08f;
    /**
     * The same under a cloud sky, where only air with shade round it glows: the narrow beams through a canopy or
     * between roofs, never open sunshine, so they can be brighter without veiling anything.
     */
    static final float SHAFT_BRIGHTNESS = 0.2f;
    /**
     * The most lit haze, in metres, the composite lets a pixel show: it eases toward this rather than adding without
     * end, so looking into the sun the shafts stay a veil, at most this times {@link #BRIGHTNESS} of the sun's light.
     */
    static final float MAX_HAZE = 3.5f;
    /** How wide a patch of light is across the sunlight, in world units: a shaft's thickness, give or take. */
    static final float SHAFT_SIZE = 2.5f;
    /** About how much of the sunlight comes through as shafts, 0 to 1. */
    static final float SHAFT_COVER = 0.4f;
    /** How fast the pattern of shafts drifts, in world units a second: slowly, as leaves and cloud shift. */
    static final float DRIFT_SPEED = 0.25f;
    /**
     * How far off the clouds are taken to be when a spot of air looks through them toward the sun, in world units: the
     * nearer, the more of the clouds round the sun the air under the camera spans, and the narrower each shaft.
     */
    static final float CLOUD_REACH = 400f;
    /** How bright the sky beams are at their brightest, as a share of the sun's light, and the most they add. */
    static final float BEAM_BRIGHTNESS = 0.8f;
    static final float BEAM_MAX = 0.4f;
    /**
     * Under a cloud sky, how far from the player (the shafts' focus) the air counts in full, across the ground, and how
     * much farther it takes to fade by e, in world units. The shafts are the near air's: farther off, the light through
     * a canopy lies on the air all alike and, a ray crossing beam after beam, only veils the picture.
     */
    static final float NEAR_REACH = 6f;
    static final float NEAR_FADE = 3f;
    /**
     * Under a cloud sky, how far along a ray the air is walked: past the player and the air round them, from either
     * camera, in steps short enough to cross a narrow shaft more than once.
     */
    static final float NEAR_DISTANCE = 34f;
    /**
     * Under a cloud sky, how far round a spot of sunlit air, across the sunlight, is looked at for shade, in world units:
     * the air glows as much as that ring is in shadow, so beams narrower than about twice this through a canopy or
     * between roofs glow, and open sunshine does not.
     */
    static final float SHAFT_WIDTH = 2.5f;
    /** Steps along each pixel's line toward the sun, in the first spreading and the second. */
    static final int BEAM_STEPS = 48;
    static final int BEAM_SMOOTH_STEPS = 20;

    private final Shader march;
    private final Shader blur;
    private final Shader beamLight;
    private final Shader beamSpread;
    private final int vao;
    /** 0: the walk, then the blur across. 1: the blur down, read by the composite. */
    private final int[] fbo = new int[2];
    private final int[] texture = new int[2];
    /** The sky beams, at a quarter of the window's size: 0 the light, then the second spreading; 1 the first. */
    private final int[] beamFbo = new int[2];
    private final int[] beamTexture = new int[2];
    private int width;
    private int height;
    private int beamWidth;
    private int beamHeight;

    private final long started = System.nanoTime();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();

    public SunRays(int width, int height) {
        march = new Shader("/shaders/fullscreen.vert", "/shaders/sun_rays.frag");
        blur = new Shader("/shaders/fullscreen.vert", "/shaders/sun_rays_blur.frag");
        beamLight = new Shader("/shaders/fullscreen.vert", "/shaders/sky_beams.frag");
        beamSpread = new Shader("/shaders/fullscreen.vert", "/shaders/sky_beams_blur.frag");
        vao = glGenVertexArrays();
        create(width, height);
    }

    /** Half the window's size each way. */
    static int size(int window) {
        return Math.max(1, window / 2);
    }

    private void create(int windowWidth, int windowHeight) {
        width = size(windowWidth);
        height = size(windowHeight);
        beamWidth = Math.max(1, windowWidth / 4);
        beamHeight = Math.max(1, windowHeight / 4);
        for (int i = 0; i < 2; i++) {
            fbo[i] = glGenFramebuffers();
            texture[i] = glGenTextures();
            target(fbo[i], texture[i], width, height, GL_CLAMP_TO_EDGE);
            beamFbo[i] = glGenFramebuffers();
            beamTexture[i] = glGenTextures();
            // Dark past the edges: a line toward a sun off the screen gathers nothing from beyond it.
            target(beamFbo[i], beamTexture[i], beamWidth, beamHeight, GL_CLAMP_TO_BORDER);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private static void target(int fbo, int texture, int width, int height, int wrap) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, width, height, 0, GL_RED, GL_FLOAT, (ByteBuffer) null);
        // Linear: the composite reads it at the window's size, and the shafts are soft light with no edge to keep.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{0f, 0f, 0f, 0f});
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    }

    public void resize(int windowWidth, int windowHeight) {
        if (size(windowWidth) == width && size(windowHeight) == height) {
            return;
        }
        deleteTargets();
        create(windowWidth, windowHeight);
    }

    /**
     * Gathers the sunlit air over a finished, resolved frame and returns the texture holding it: in metres of sunlit
     * haze, scaled by how the air scatters toward the eye, for the composite to tint with the sun's colour and
     * {@link #BRIGHTNESS}.
     *
     * @param scene     the frame, resolved, for its depth
     * @param shadowMap the sun's, drawn this frame
     * @param toSun     toward the sun
     * @param groundY   the height the haze is thickest at: the ground where the player stands
     * @param focus     where the shafts gather under a cloud sky: the player's place on the ground
     * @param clouds    the sky's clouds as the sun sees them, which then make the shafts; null for no cloud sky
     */
    public int render(Framebuffer scene, Camera camera, ShadowMap shadowMap, Vector3f toSun, float groundY,
                      Vector3f focus, SkyDome.SunMask clouds) {
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        glViewport(0, 0, width, height);

        camera.getProjectionMatrix().mul(camera.getViewMatrix(), viewProjection);
        viewProjection.invert(inverse);

        // --- the walk ---------------------------------------------------------------------------------
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0]);
        march.use();
        bind(scene.depthTexture(), 0);
        march.setUniform("sceneDepth", 0);
        shadowMap.bind(1);
        march.setUniform("shadowMap", 1);
        march.setUniform("lightSpaceMatrix", shadowMap.lightSpaceMatrix());
        march.setUniform("shadowBias", shadowMap.depthBias());
        march.setUniform("invViewProjection", inverse);
        march.setUniform("cameraPosition", camera.getPosition());
        march.setUniform("toSun", toSun);
        march.setUniform("maxDistance", clouds != null ? NEAR_DISTANCE : MAX_DISTANCE);
        march.setUniform("groundY", groundY);
        march.setUniform("hazeHeight", HAZE_HEIGHT);
        march.setUniform("anisotropy", ANISOTROPY);
        march.setUniform("shaftSize", SHAFT_SIZE);
        march.setUniform("shaftCover", SHAFT_COVER);
        // Drifting on a slant, and wrapped so the numbers stay small enough for the shader's precision.
        double seconds = (System.nanoTime() - started) / 1e9;
        float drifted = (float) ((seconds * DRIFT_SPEED) % 4096.0);
        march.setUniform("drift", new Vector2f(drifted, drifted * 0.37f));
        march.setUniform("steps", STEPS);
        march.setUniform("hasCloudMask", clouds != null ? 1 : 0);
        if (clouds != null) {
            bind(clouds.texture(), 2);
            march.setUniform("cloudMask", 2);
            march.setUniform("cloudMaskViewProjection", clouds.viewProjection());
            march.setUniform("cloudReach", CLOUD_REACH);
            march.setUniform("focus", focus);
            march.setUniform("nearReach", NEAR_REACH);
            march.setUniform("nearFade", NEAR_FADE);
            march.setUniform("shaftWidth", SHAFT_WIDTH);
        }
        draw();

        // --- the blur, across into the second target and down back into the first ---------------------
        blur.use();
        bind(scene.depthTexture(), 1);
        blur.setUniform("sceneDepth", 1);
        blur.setUniform("near", camera.getNearPlane());
        blur.setUniform("far", camera.getFarPlane());
        blur.setUniform("rays", 0);

        glBindFramebuffer(GL_FRAMEBUFFER, fbo[1]);
        bind(texture[0], 0);
        blur.setUniform("texelStep", new Vector2f(1f / width, 0f));
        draw();

        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0]);
        bind(texture[1], 0);
        blur.setUniform("texelStep", new Vector2f(0f, 1f / height));
        draw();

        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        return texture[0];
    }

    /**
     * The beams in the sky: the light the open sky gives off round the sun where the clouds let it through, drawn out
     * from the sun across the picture. Returns the texture holding them, as a share of the sun's light before
     * {@link #BEAM_BRIGHTNESS}, or 0 when the sun is behind the camera and none can show.
     *
     * @param scene  the frame, resolved, for its depth: only open sky gives off light
     * @param clouds the sky's clouds as the sun sees them
     */
    public int renderBeams(Framebuffer scene, Camera camera, Vector3f toSun, SkyDome.SunMask clouds) {
        camera.getProjectionMatrix().mul(camera.getViewMatrix(), viewProjection);
        Vector4f sun = new Vector4f(camera.getPosition(), 1f).add(toSun.x * 500f, toSun.y * 500f, toSun.z * 500f, 0f);
        viewProjection.transform(sun);
        if (sun.w <= 0f) {
            return 0;
        }
        Vector2f sunUv = new Vector2f(sun.x / sun.w * 0.5f + 0.5f, sun.y / sun.w * 0.5f + 0.5f);
        viewProjection.invert(inverse);

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        glViewport(0, 0, beamWidth, beamHeight);

        glBindFramebuffer(GL_FRAMEBUFFER, beamFbo[0]);
        beamLight.use();
        bind(scene.depthTexture(), 0);
        beamLight.setUniform("sceneDepth", 0);
        bind(clouds.texture(), 1);
        beamLight.setUniform("cloudMask", 1);
        beamLight.setUniform("cloudMaskViewProjection", clouds.viewProjection());
        beamLight.setUniform("invViewProjection", inverse);
        beamLight.setUniform("viewProjection", viewProjection);
        beamLight.setUniform("cameraPosition", camera.getPosition());
        beamLight.setUniform("toSun", toSun);
        draw();

        beamSpread.use();
        beamSpread.setUniform("source", 0);
        beamSpread.setUniform("sunUv", sunUv);
        glBindFramebuffer(GL_FRAMEBUFFER, beamFbo[1]);
        bind(beamTexture[0], 0);
        beamSpread.setUniform("reach", 1f);
        beamSpread.setUniform("decay", 0.97f);
        beamSpread.setUniform("samples", BEAM_STEPS);
        beamSpread.setUniform("streaks", 0);
        draw();
        glBindFramebuffer(GL_FRAMEBUFFER, beamFbo[0]);
        bind(beamTexture[1], 0);
        beamSpread.setUniform("reach", 0.18f);
        beamSpread.setUniform("decay", 0.99f);
        beamSpread.setUniform("samples", BEAM_SMOOTH_STEPS);
        beamSpread.setUniform("streaks", 1);
        beamSpread.setUniform("aspect", beamWidth / (float) beamHeight);
        draw();

        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        return beamTexture[0];
    }

    private void draw() {
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    private static void bind(int id, int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    private void deleteTargets() {
        for (int i = 0; i < 2; i++) {
            glDeleteFramebuffers(fbo[i]);
            glDeleteTextures(texture[i]);
            glDeleteFramebuffers(beamFbo[i]);
            glDeleteTextures(beamTexture[i]);
        }
    }

    public void delete() {
        deleteTargets();
        glDeleteVertexArrays(vao);
        march.delete();
        blur.delete();
        beamLight.delete();
        beamSpread.delete();
    }
}
