package org.zeronura.engine.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The settings every game has — display, audio and mouse — kept per game and applied by the {@link Engine}
 * itself, so a game gets working options by drawing {@code SettingsView} and nothing else.
 *
 * <p>Game-specific preferences stay with the game. This is only the part no game should have to re-invent:
 * a window, four volume buses and a camera that turns.
 *
 * <p>A missing file, a missing key or a value that does not parse keeps its default, and the defaults for
 * the window come from the project's {@link EngineConfig}, so a fresh install starts the way the project
 * file says and only diverges once the player changes something.
 */
public final class UserSettings {

    /** Window sizes offered when not fullscreen. The settings view only lists the ones the monitor fits. */
    public static final int[][] WINDOW_SIZES = {
            {1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

    public static final float MIN_SENSITIVITY = 0.25f;
    public static final float MAX_SENSITIVITY = 3f;

    /** Frame rate caps offered, 0 for none. */
    public static final int[] FRAME_RATE_LIMITS = {0, 30, 60, 90, 120, 144, 165, 240};

    /** How sharp shadows are, from none to the shadow map's full size. */
    public enum ShadowQuality {
        OFF("Off", 0), LOW("Low", 1024), MEDIUM("Medium", 2048), HIGH("High", 4096);

        private final String label;
        private final int resolution;

        ShadowQuality(String label, int resolution) {
            this.label = label;
            this.resolution = resolution;
        }

        public String label() {
            return label;
        }

        /** The shadow map's side in texels, 0 when off. */
        public int resolution() {
            return resolution;
        }
    }

    /**
     * How much the bright parts of a 3D scene glow out over what is round them: not at all; softly, the lights, the
     * sunlit whites and the sky; or strongly, a hazy glow off everything bright. Pixel-art sprites never glow.
     */
    public enum Bloom {
        OFF("Off"), SOFT("Soft"), STRONG("Strong");

        private final String label;

        Bloom(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * How much grass grows over a world's grassy ground: blades in clumps around the player, out to a distance. Off
     * leaves the ground as its painted texture alone.
     */
    public enum Grass {
        OFF("Off", 0f, 0f), LOW("Low", 0.55f, 0.65f), HIGH("High", 1f, 1f);

        private final String label;
        private final float density;
        private final float reach;

        Grass(String label, float density, float reach) {
            this.label = label;
            this.density = density;
            this.reach = reach;
        }

        public String label() {
            return label;
        }

        /** How thick it grows, as a share of the full. */
        public float density() {
            return density;
        }

        /** How far out it is drawn, as a share of the full distance. */
        public float reach() {
            return reach;
        }
    }

    /** How much a world's particle effects fill its air: falling leaves, fireflies, dust at the feet. */
    public enum Particles {
        OFF("Off", 0f), LOW("Low", 0.5f), HIGH("High", 1f);

        private final String label;
        private final float amount;

        Particles(String label, float amount) {
            this.label = label;
            this.amount = amount;
        }

        public String label() {
            return label;
        }

        /** The share of a world's particles there are, 0 for none. */
        public float amount() {
            return amount;
        }
    }

    /**
     * Beams of sunlight through the air of a 3D scene, where the sun gets past trees and buildings: none, soft shafts, or
     * strong ones. They need the sun's shadows, so with shadows off there are none.
     */
    public enum SunRays {
        OFF("Off", 0f), SOFT("Soft", 1f), STRONG("Strong", 1.7f);

        private final String label;
        private final float strength;

        SunRays(String label, float strength) {
            this.label = label;
            this.strength = strength;
        }

        public String label() {
            return label;
        }

        /** How bright the beams are against the soft setting's, 0 for none. */
        public float strength() {
            return strength;
        }
    }

    /**
     * How strongly the screen-space ambient occlusion shades the creases and the ground under things, which the
     * sky's even fill light leaves flat: none, the occlusion alone, or the occlusion with the colour that nearby
     * surfaces bounce onto each other.
     */
    public enum AmbientOcclusion {
        OFF("Off"), LOW("Low"), HIGH("High");

        private final String label;

        AmbientOcclusion(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * How a 3D scene's jagged edges are smoothed: not at all; by FXAA over the finished picture, which is cheap but
     * softens pixel art along with the edges; by multisampling (MSAA), which takes several samples of each pixel along
     * the edges of what is drawn and averages them, leaving textures and pixel art as they are; or by drawing the scene
     * at twice the window's size each way and scaling it down, which smooths everything for four times the pixels.
     */
    public enum AntiAliasing {
        OFF("Off", 1, 1), FXAA("FXAA", 1, 1), MSAA_4X("4x MSAA", 1, 4), MSAA_8X("8x MSAA", 1, 8),
        SUPERSAMPLING("2x supersampling", 2, 1);

        private final String label;
        private final int scale;
        private final int samples;

        AntiAliasing(String label, int scale, int samples) {
            this.label = label;
            this.scale = scale;
            this.samples = samples;
        }

        /** How many samples each pixel takes along the edges of what is drawn: 1 for none beyond its middle. */
        public int samples() {
            return samples;
        }

        public String label() {
            return label;
        }

        /** How many times the window's size, each way, the scene is drawn at. */
        public int scale() {
            return scale;
        }
    }

    /** Anisotropic filtering levels offered, 1 for plain trilinear. */
    public static final int[] TEXTURE_FILTERING = {1, 2, 4, 8, 16};

    /** The third-person camera's vertical field of view, in degrees. */
    public static final float MIN_FIELD_OF_VIEW = 35f;
    public static final float MAX_FIELD_OF_VIEW = 75f;
    public static final float DEFAULT_FIELD_OF_VIEW = 45f;

    private static final String BINDING_PREFIX = "bind.";

    private final Path file;

    private boolean fullscreen;
    private boolean vsync;
    private int windowWidth;
    private int windowHeight;
    private boolean showFps;
    private float masterVolume = 1f;
    private float musicVolume = 0.8f;
    private float ambienceVolume = 0.8f;
    private float effectsVolume = 1f;
    private float mouseSensitivity = 1f;
    private boolean invertY;
    private int frameRateLimit;
    private ShadowQuality shadowQuality = ShadowQuality.HIGH;
    private int textureFiltering = 8;
    private float fieldOfView = DEFAULT_FIELD_OF_VIEW;
    private AmbientOcclusion ambientOcclusion = AmbientOcclusion.HIGH;
    private Bloom bloom = Bloom.SOFT;
    private SunRays sunRays = SunRays.SOFT;
    private Particles particles = Particles.HIGH;
    private Grass grass = Grass.HIGH;
    /** 4x MSAA: it smooths the edges of a 3D world for a modest cost, and leaves pixel art as it is. */
    private AntiAliasing antiAliasing = DEFAULT_ANTI_ALIASING;
    /** The player's changes to the controls, as {@code InputMap.saved()} writes them. */
    private final Map<String, String> bindings = new LinkedHashMap<>();

    private UserSettings(Path file, EngineConfig config) {
        this.file = file;
        this.fullscreen = config.fullscreen();
        this.vsync = config.vsync();
        this.windowWidth = config.width();
        this.windowHeight = config.height();
    }

    /** The defaults for {@code config}, not backed by a file — {@link #save} does nothing. */
    public static UserSettings defaults(EngineConfig config) {
        return new UserSettings(null, config);
    }

    /**
     * Where a game's settings live: one file per game under {@code saves/settings/}, named after its
     * title, since the saves folder is shared by every project the engine runs.
     */
    public static Path fileFor(EngineConfig config) {
        String slug = config.title().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return Path.of("saves", "settings", (slug.isEmpty() ? "game" : slug) + ".properties");
    }

    public static UserSettings load(Path file, EngineConfig config) {
        UserSettings settings = new UserSettings(file, config);
        if (file == null || !Files.isRegularFile(file)) {
            return settings;
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            p.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[Engine] Ignoring unreadable settings " + file + ": " + e.getMessage());
            return settings;
        }
        settings.fullscreen = bool(p, "fullscreen", settings.fullscreen);
        settings.vsync = bool(p, "vsync", settings.vsync);
        settings.windowWidth = (int) number(p, "windowWidth", settings.windowWidth, 320, 16384);
        settings.windowHeight = (int) number(p, "windowHeight", settings.windowHeight, 240, 16384);
        settings.showFps = bool(p, "showFps", settings.showFps);
        settings.masterVolume = number(p, "masterVolume", settings.masterVolume, 0f, 1f);
        settings.musicVolume = number(p, "musicVolume", settings.musicVolume, 0f, 1f);
        settings.ambienceVolume = number(p, "ambienceVolume", settings.ambienceVolume, 0f, 1f);
        settings.effectsVolume = number(p, "effectsVolume", settings.effectsVolume, 0f, 1f);
        settings.mouseSensitivity = number(p, "mouseSensitivity", settings.mouseSensitivity,
                MIN_SENSITIVITY, MAX_SENSITIVITY);
        settings.invertY = bool(p, "invertY", settings.invertY);
        settings.frameRateLimit = (int) number(p, "frameRateLimit", settings.frameRateLimit, 0, 1000);
        String shadows = p.getProperty("shadowQuality", "").trim().toUpperCase(Locale.ROOT);
        for (ShadowQuality quality : ShadowQuality.values()) {
            if (quality.name().equals(shadows)) {
                settings.shadowQuality = quality;
            }
        }
        settings.textureFiltering = (int) number(p, "textureFiltering", settings.textureFiltering, 1, 16);
        settings.fieldOfView = number(p, "fieldOfView", settings.fieldOfView, MIN_FIELD_OF_VIEW, MAX_FIELD_OF_VIEW);
        String occlusion = p.getProperty("ambientOcclusion", "").trim().toUpperCase(Locale.ROOT);
        for (AmbientOcclusion level : AmbientOcclusion.values()) {
            if (level.name().equals(occlusion)) {
                settings.ambientOcclusion = level;
            }
        }
        String glow = p.getProperty("bloom", "").trim().toUpperCase(Locale.ROOT);
        for (Bloom level : Bloom.values()) {
            if (level.name().equals(glow)) {
                settings.bloom = level;
            }
        }
        // Saved as a switch before it had levels: off stays off, and on is the soft glow.
        if (glow.equals("FALSE")) {
            settings.bloom = Bloom.OFF;
        }
        String rays = p.getProperty("sunRays", "").trim().toUpperCase(Locale.ROOT);
        for (SunRays level : SunRays.values()) {
            if (level.name().equals(rays)) {
                settings.sunRays = level;
            }
        }
        String grassLevel = p.getProperty("grass", "").trim().toUpperCase(Locale.ROOT);
        for (Grass level : Grass.values()) {
            if (level.name().equals(grassLevel)) {
                settings.grass = level;
            }
        }
        String particleLevel = p.getProperty("particles", "").trim().toUpperCase(Locale.ROOT);
        for (Particles level : Particles.values()) {
            if (level.name().equals(particleLevel)) {
                settings.particles = level;
            }
        }
        String smoothing = p.getProperty("antiAliasing", "").trim().toUpperCase(Locale.ROOT);
        for (AntiAliasing kind : AntiAliasing.values()) {
            if (kind.name().equals(smoothing)) {
                settings.antiAliasing = kind;
            }
        }
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(BINDING_PREFIX)) {
                settings.bindings.put(key.substring(BINDING_PREFIX.length()), p.getProperty(key).trim());
            }
        }
        return settings;
    }

    public void save() throws IOException {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("fullscreen", Boolean.toString(fullscreen));
        p.setProperty("vsync", Boolean.toString(vsync));
        p.setProperty("windowWidth", Integer.toString(windowWidth));
        p.setProperty("windowHeight", Integer.toString(windowHeight));
        p.setProperty("showFps", Boolean.toString(showFps));
        p.setProperty("masterVolume", Float.toString(masterVolume));
        p.setProperty("musicVolume", Float.toString(musicVolume));
        p.setProperty("ambienceVolume", Float.toString(ambienceVolume));
        p.setProperty("effectsVolume", Float.toString(effectsVolume));
        p.setProperty("mouseSensitivity", Float.toString(mouseSensitivity));
        p.setProperty("invertY", Boolean.toString(invertY));
        p.setProperty("frameRateLimit", Integer.toString(frameRateLimit));
        p.setProperty("shadowQuality", shadowQuality.name());
        p.setProperty("textureFiltering", Integer.toString(textureFiltering));
        p.setProperty("fieldOfView", Float.toString(fieldOfView));
        p.setProperty("ambientOcclusion", ambientOcclusion.name());
        p.setProperty("bloom", bloom.name());
        p.setProperty("sunRays", sunRays.name());
        p.setProperty("particles", particles.name());
        p.setProperty("grass", grass.name());
        p.setProperty("antiAliasing", antiAliasing.name());
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            p.setProperty(BINDING_PREFIX + binding.getKey(), binding.getValue());
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            p.store(writer, "Game settings");
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, "").trim();
        return value.equalsIgnoreCase("true") || (!value.equalsIgnoreCase("false") && fallback);
    }

    private static float number(Properties p, String key, float fallback, float min, float max) {
        try {
            float value = Float.parseFloat(p.getProperty(key, "").trim());
            return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float unit(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public Path file() {
        return file;
    }

    public boolean fullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public boolean vsync() {
        return vsync;
    }

    public void setVsync(boolean vsync) {
        this.vsync = vsync;
    }

    public int windowWidth() {
        return windowWidth;
    }

    public int windowHeight() {
        return windowHeight;
    }

    public void setWindowSize(int width, int height) {
        this.windowWidth = Math.max(320, width);
        this.windowHeight = Math.max(240, height);
    }

    public boolean showFps() {
        return showFps;
    }

    public void setShowFps(boolean showFps) {
        this.showFps = showFps;
    }

    public float masterVolume() {
        return masterVolume;
    }

    public void setMasterVolume(float volume) {
        this.masterVolume = unit(volume);
    }

    public float musicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(float volume) {
        this.musicVolume = unit(volume);
    }

    public float ambienceVolume() {
        return ambienceVolume;
    }

    public void setAmbienceVolume(float volume) {
        this.ambienceVolume = unit(volume);
    }

    public float effectsVolume() {
        return effectsVolume;
    }

    public void setEffectsVolume(float volume) {
        this.effectsVolume = unit(volume);
    }

    /** A multiplier on each camera controller's own sensitivity, so 1 is the game's intended feel. */
    public float mouseSensitivity() {
        return mouseSensitivity;
    }

    public void setMouseSensitivity(float sensitivity) {
        this.mouseSensitivity = Math.max(MIN_SENSITIVITY, Math.min(MAX_SENSITIVITY, sensitivity));
    }

    public boolean invertY() {
        return invertY;
    }

    public void setInvertY(boolean invertY) {
        this.invertY = invertY;
    }

    /** Frames per second the engine holds itself to, 0 for no cap (VSync may still hold it to the display). */
    public int frameRateLimit() {
        return frameRateLimit;
    }

    public void setFrameRateLimit(int limit) {
        this.frameRateLimit = Math.max(0, limit);
    }

    public ShadowQuality shadowQuality() {
        return shadowQuality;
    }

    public void setShadowQuality(ShadowQuality quality) {
        this.shadowQuality = java.util.Objects.requireNonNull(quality);
    }

    /** Anisotropic filtering samples, 1 for none; the engine applies no more than the driver supports. */
    public int textureFiltering() {
        return textureFiltering;
    }

    public void setTextureFiltering(int samples) {
        this.textureFiltering = Math.max(1, Math.min(16, samples));
    }

    /** The third-person camera's vertical field of view, in degrees. */
    public float fieldOfView() {
        return fieldOfView;
    }

    public void setFieldOfView(float degrees) {
        this.fieldOfView = Math.max(MIN_FIELD_OF_VIEW, Math.min(MAX_FIELD_OF_VIEW, degrees));
    }

    public AmbientOcclusion ambientOcclusion() {
        return ambientOcclusion;
    }

    public void setAmbientOcclusion(AmbientOcclusion level) {
        this.ambientOcclusion = java.util.Objects.requireNonNull(level);
    }

    /** How much the bright parts of a 3D scene glow out over what is round them. */
    public Bloom bloom() {
        return bloom;
    }

    public void setBloom(Bloom level) {
        this.bloom = java.util.Objects.requireNonNull(level);
    }

    /** How strongly beams of sunlight show in the air of a 3D scene. */
    public Particles particles() {
        return particles;
    }

    public Grass grass() {
        return grass;
    }

    public void setGrass(Grass level) {
        this.grass = java.util.Objects.requireNonNull(level);
    }

    public void setParticles(Particles level) {
        this.particles = java.util.Objects.requireNonNull(level);
    }

    public SunRays sunRays() {
        return sunRays;
    }

    public void setSunRays(SunRays level) {
        this.sunRays = java.util.Objects.requireNonNull(level);
    }

    /** The anti-aliasing a player starts with, and the video defaults put back. */
    public static final AntiAliasing DEFAULT_ANTI_ALIASING = AntiAliasing.MSAA_4X;

    public AntiAliasing antiAliasing() {
        return antiAliasing;
    }

    public void setAntiAliasing(AntiAliasing kind) {
        this.antiAliasing = java.util.Objects.requireNonNull(kind);
    }

    /** The player's changes to the controls, by action, as {@code InputMap.saved()} writes them. */
    public Map<String, String> bindings() {
        return Collections.unmodifiableMap(bindings);
    }

    public void setBindings(Map<String, String> changed) {
        bindings.clear();
        bindings.putAll(changed);
    }
}
