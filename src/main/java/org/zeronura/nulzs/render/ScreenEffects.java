package org.zeronura.nulzs.render;

import org.joml.Vector3f;
import org.zeronura.nulzs.core.UserSettings;
import org.zeronura.nulzs.debug.DebugMode;
import org.zeronura.nulzs.graphics2d.Color;

import static org.lwjgl.opengl.GL33.*;

/**
 * The player's picture settings — ambient occlusion, bloom and anti-aliasing — put into a 3D scene's frame.
 *
 * <h2>The frame with them on</h2>
 * The scene is drawn into an HDR {@link Framebuffer} instead of straight to the window ({@link #begin}), at twice the
 * window's size each way when supersampling, or with several samples a pixel along its edges with MSAA. Once its
 * opaque passes are in, {@link #occlude} gathers ambient occlusion over them ({@link GlobalIllumination}) — from the
 * samples averaged, darkening every sample, so what is drawn after keeps its smooth edges; the translucent passes
 * follow, and {@link #finish} writes the picture to the window through the {@link PostProcessor} — bloom, FXAA, and
 * the scaling down that is the supersampling's smoothing — ready for the 2D layer and the GUI, which stay crisp on top.
 * A scene with sun rays hands them over first ({@link #sunRays}): beams of sunlight in the air, gathered from the
 * sun's shadow map ({@link SunRays}) and added in the composite.
 *
 * <h2>And with them off</h2>
 * Nothing here runs at all and the scene draws to the window as it always did: {@link #wanted} is false, and the
 * buffers are not even made until a setting asks for them. The composite clips at white as the window does rather
 * than tone-mapping, so the settings add to the picture without changing its colours: this engine's scenes are lit
 * for the window's range, and a filmic curve only greys their whites.
 *
 * <h2>Self-contained</h2>
 * Made by the engine on first use and resized to the window as frames ask; {@link #finish} leaves the GL state as the
 * 3D passes expect it — depth test and culling on, blending off, the window's framebuffer bound at its full size.
 */
public final class ScreenEffects {

    /** Occlusion alone, in the creases and under things: cheaper, and no colour bleeds between surfaces. */
    private static final GiSettings LOW = new GiSettings(1.6f, 0.035f, 3.0f, 0f, 0.4f, 120f, 12);

    /**
     * Where bloom starts, in HDR luminance, and how strongly it is added back, for each level. The bright pass keeps
     * only the light past the threshold. A scene drawn for the window keeps its sunlit whites under 1 and its sky about
     * 0.8, so a threshold of 1 glows only what is pushed past white — a lamp, a glowing move — too little to see by
     * day. Soft starts just under the sky: the lamps glow plainly, the brightest whites a little, and the sky barely
     * hazes the horizon. Strong starts lower, for a dreamy glow off the sky and every white. The strengths allow for
     * the bloom chain adding its five levels together, which multiplies a broad bright area like the sky about fivefold
     * against a lamp's few pixels. Never off a sprite (see {@code Renderer.setSpritesUnbloomed}).
     */
    private static final float SOFT_THRESHOLD = 0.8f;
    private static final float SOFT_INTENSITY = 0.6f;
    private static final float STRONG_THRESHOLD = 0.68f;
    private static final float STRONG_INTENSITY = 0.7f;

    private Framebuffer target;
    private GlobalIllumination occlusion;
    private PostProcessor post;
    private SunRays rays;
    /** The player's sun-ray setting's strength this frame, 0 for none. */
    private float raySetting;
    /** What the scene asked for this frame ({@link #sunRays}); null for no rays. */
    private Camera rayCamera;
    private ShadowMap rayShadows;
    private SkyDome.SunMask rayClouds;
    private SkyDome.SunMask rayShaftClouds;
    private final Vector3f toSun = new Vector3f();
    private final Vector3f rayColor = new Vector3f();
    private final Vector3f beamColor = new Vector3f();
    private float rayGround;
    private final Vector3f rayFocus = new Vector3f();
    private GiSettings occlusionSettings = GiSettings.OFF;
    private PostSettings postSettings;
    private boolean fxaa;
    /** The window's size, which the picture is written at. */
    private int width;
    private int height;

    /** Whether any of the player's settings asks for this: false draws the frame straight to the window. */
    public static boolean wanted(UserSettings settings) {
        return settings.ambientOcclusion() != UserSettings.AmbientOcclusion.OFF || settings.bloom() != UserSettings.Bloom.OFF
                || settings.antiAliasing() != UserSettings.AntiAliasing.OFF || settings.sunRays() != UserSettings.SunRays.OFF;
    }

    /** The occlusion a setting asks for. */
    static GiSettings occlusion(UserSettings.AmbientOcclusion level) {
        return switch (level) {
            case OFF -> GiSettings.OFF;
            case LOW -> LOW;
            case HIGH -> GiSettings.tuned();
        };
    }

    /** The bloom the settings ask for, over the scene's own colours: no grade, no vignette, no tone curve. */
    static PostSettings post(UserSettings settings) {
        boolean strong = settings.bloom() == UserSettings.Bloom.STRONG;
        float intensity = switch (settings.bloom()) {
            case OFF -> 0f;
            case SOFT -> SOFT_INTENSITY;
            case STRONG -> STRONG_INTENSITY;
        };
        return new PostSettings(1f, strong ? STRONG_THRESHOLD : SOFT_THRESHOLD, intensity, 1f, 1f,
                new Vector3f(1f, 1f, 1f), new Vector3f(0f, 0f, 0f), 0f, false);
    }

    /**
     * Starts a frame: makes the HDR target fit the window (or twice it, supersampling; multisampled for MSAA), binds it
     * and clears it to {@code clear}. Everything the scene draws from here until {@link #finish} goes into it.
     */
    public void begin(int width, int height, UserSettings settings, Color clear) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        occlusionSettings = occlusion(settings.ambientOcclusion());
        postSettings = post(settings);
        fxaa = settings.antiAliasing() == UserSettings.AntiAliasing.FXAA;
        raySetting = settings.sunRays().strength();
        rayCamera = null;
        int scale = settings.antiAliasing().scale();
        int samples = settings.antiAliasing().samples();

        // The geometry target costs a second full-size float attachment and a draw buffer every opaque pass writes,
        // so it is there only while occlusion is, which is all that reads it.
        boolean geometry = occlusionSettings.isActive();
        if (target != null && (target.hasGeometry() != geometry || target.samples() != Framebuffer.samplesFor(samples))) {
            target.delete();
            target = null;
        }
        if (target == null) {
            target = new Framebuffer(this.width * scale, this.height * scale, geometry, samples);
        } else {
            target.resize(this.width * scale, this.height * scale);
        }
        target.bind();
        glClearColor(clear.r(), clear.g(), clear.b(), 1f);
        target.clear();
    }

    /**
     * Gathers ambient occlusion over what has been drawn and darkens it, if the settings ask for any. Call it once the
     * opaque passes are in and before anything translucent: the water, the weather and the particles are neither
     * occluded nor occluders. Anything drawn after it writes colour only.
     */
    public void occlude(Camera camera) {
        if (target == null || !occlusionSettings.isActive()) {
            return;
        }
        if (occlusion == null) {
            occlusion = new GlobalIllumination(target.width(), target.height());
        } else {
            occlusion.resize(target.width(), target.height());
        }
        target.resolve();
        occlusion.render(target, camera, occlusionSettings);
    }

    /**
     * Asks for sun rays in this frame's picture, if the player's settings have them: call it after the 3D passes and
     * before {@link #finish}. {@code strength} is the scene's own (the time of day, the weather), 0 for none.
     *
     * @param shadowMap the sun's, drawn this frame; null when it was not, and then there are no shafts in the low air,
     *                  since without shadows there is nothing to cut them (beams in the sky need none)
     * @param lighting  the frame's, for which way the sun is and its colour
     * @param groundY   the height the haze is thickest at: the ground where the player stands
     * @param clouds    the sky's clouds as the sun sees them ({@link SkyDome#sunMask}), which then shape the rays and
     *                  stream beams through their gaps; null for a world with no cloud sky
     */
    public void sunRays(Camera camera, ShadowMap shadowMap, SceneLighting lighting, float strength, float groundY,
                        SkyDome.SunMask clouds) {
        sunRays(camera, shadowMap, lighting, strength, groundY, null, clouds);
    }

    /**
     * The same, with the shafts gathered round {@code focus} (x and z), the player's place; null for round the camera.
     * Under a cloud sky only the air near there makes them.
     */
    public void sunRays(Camera camera, ShadowMap shadowMap, SceneLighting lighting, float strength, float groundY,
                        Vector3f focus, SkyDome.SunMask clouds) {
        // The shafts in the low air go by the scene's strength; the beams in the sky by how much of the sun shows,
        // which is the sky's to say: a cloudy day, when the shafts are faint, is when the beams are best.
        float brightness = strength * raySetting;
        float beams = clouds == null ? 0f : clouds.sun() * raySetting;
        rayShadows = shadowMap != null && brightness > 0f ? shadowMap : null;
        rayClouds = beams > 0f ? clouds : null;
        if (target == null || (rayShadows == null && rayClouds == null) || lighting.toSun().y <= 0f) {
            rayCamera = null;
            return;
        }
        rayCamera = camera;
        // The shafts read the clouds too, whenever there are any, to know where their gaps are.
        rayShaftClouds = clouds;
        toSun.set(lighting.toSun()).normalize();
        rayColor.set(lighting.sunColor()).mul(brightness * (clouds != null ? SunRays.SHAFT_BRIGHTNESS : SunRays.BRIGHTNESS));
        beamColor.set(lighting.sunColor()).mul(beams * SunRays.BEAM_BRIGHTNESS);
        rayGround = groundY;
        if (focus != null) {
            rayFocus.set(focus.x, groundY, focus.z);
        } else {
            rayFocus.set(camera.getPosition().x, groundY, camera.getPosition().z);
        }
    }

    /** The same, for a world with no cloud sky. */
    public void sunRays(Camera camera, ShadowMap shadowMap, SceneLighting lighting, float strength, float groundY) {
        sunRays(camera, shadowMap, lighting, strength, groundY, null);
    }

    /**
     * Ends the frame: the picture, with bloom and anti-aliasing as asked, written to the window. A supersampled scene
     * is read back at the window's size through its linear filter, which at exactly twice the size averages each
     * window pixel's four.
     */
    public void finish() {
        if (target == null) {
            return;
        }
        if (post == null) {
            post = new PostProcessor(width, height);
        } else {
            post.resize(width, height);
        }
        post.setAntiAliasing(fxaa);
        DebugMode.begin("Resolve the picture");
        target.resolve();
        DebugMode.end();
        int shafts = 0;
        int beams = 0;
        if (rayCamera != null) {
            DebugMode.begin("Sun rays");
            if (rays == null) {
                rays = new SunRays(width, height);
            } else {
                rays.resize(width, height);
            }
            if (rayShadows != null) {
                shafts = rays.render(target, rayCamera, rayShadows, toSun, rayGround, rayFocus, rayShaftClouds);
            }
            if (rayClouds != null) {
                beams = rays.renderBeams(target, rayCamera, toSun, rayClouds);
            }
            rayCamera = null;
            rayClouds = null;
            rayShaftClouds = null;
            DebugMode.end();
        }
        post.setSunRays(shafts, rayColor);
        post.setSkyBeams(beams, beamColor);
        DebugMode.begin("Bloom, grading, anti-aliasing");
        post.process(target.colorTexture(), postSettings, 0, width, height);
        DebugMode.end();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glDisable(GL_BLEND);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        // The picture has no depth of its own in the window; whatever is drawn over it next starts from a clear one.
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    public void delete() {
        if (target != null) {
            target.delete();
            target = null;
        }
        if (occlusion != null) {
            occlusion.delete();
            occlusion = null;
        }
        if (post != null) {
            post.delete();
            post = null;
        }
        if (rays != null) {
            rays.delete();
            rays = null;
        }
    }
}
