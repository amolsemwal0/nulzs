package org.zeronura.nulzs.core;

import org.zeronura.nulzs.Window;
import org.zeronura.nulzs.audio.AudioSystem;
import org.zeronura.nulzs.debug.DebugMode;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.graphics2d.SpriteBatch;
import org.zeronura.nulzs.gui.Gui;
import org.zeronura.nulzs.gui.TouchControls;
import org.zeronura.nulzs.input.Input;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.StandardActions;
import org.zeronura.nulzs.render.Renderer;
import org.zeronura.nulzs.render.ScreenEffects;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.scene.SceneManager;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.opengl.GL33.*;

/**
 * Owns the window and the main loop, and gives every game the same services: input, time, scenes,
 * assets, audio, and the 2D, 3D and GUI renderers.
 *
 * <h2>The loop</h2>
 * Each frame polls input, runs zero or more {@code fixedUpdate} steps at a constant rate (physics wants a
 * constant dt so its results do not depend on frame rate), runs one variable {@code update}, then renders.
 * Leftover simulation time is handed to rendering as an interpolation factor.
 *
 * <h2>Renderers are created on first use</h2>
 * A card game never pays for the 3D renderer's shaders and shadow map, and a 3D game with no HUD never
 * builds the GUI atlas. Ask for them through {@link #spriteBatch()}, {@link #gui()} and
 * {@link #renderer3D()}; scenes do this for you.
 *
 * <h2>Developer keys</h2>
 * F3 toggles the stats overlay, F11 fullscreen, F12 saves a screenshot to {@code screenshots/}.
 *
 * <h2>Crashes</h2>
 * An exception that reaches the loop leaves a report in {@link Diagnostics#FOLDER} before the game goes down.
 *
 * <h2>Player settings</h2>
 * {@link #settings()} holds the options every game has — window, audio, mouse — loaded per game and applied
 * here. A game offers them by drawing {@code SettingsView}; a change takes effect through
 * {@link #applySettings()}.
 */
public final class Engine {

    /** Longest frame fed to the simulation; see {@link #loop}. */
    private static final double MAX_FRAME_TIME = 0.25;

    private final Window window;
    private final Input input = new Input();
    private final TouchControls touchControls = new TouchControls();
    private final Time time;
    private final SceneManager scenes;
    private final AudioSystem audio = new AudioSystem();

    private EngineConfig config;
    private UserSettings settings;
    private Assets assets;

    private SpriteBatch spriteBatch;
    private Gui gui;
    private Renderer renderer3D;
    private ScreenEffects screenEffects;

    private Game game;
    private Game launcher;
    private EngineConfig launcherConfig;
    private Game pendingGame;
    private EngineConfig pendingConfig;

    private boolean running;
    private boolean screenshotRequested;
    private Path frameCaptureFile;
    private Runnable frameCaptured;
    /** The scene a waiting capture is to look under, or null for a picture of the whole frame. */
    private Scene frameCaptureBelow;
    private int framebufferWidth;
    private int framebufferHeight;

    // Automation hooks, for capturing a frame from a script: -Dengine.screenshot=out.png. -Dengine.screenshotFrames=300,310
    // captures several instead, as out-300.png and so on, to follow something moving through one run.
    private final String autoScreenshot = System.getProperty("engine.screenshot");
    private final long autoScreenshotFrame = Long.getLong("engine.screenshotFrame", 120);
    private final java.util.Set<Long> autoScreenshotFrames = java.util.Arrays.stream(
                    System.getProperty("engine.screenshotFrames", "").split(","))
            .map(String::trim).filter(s -> s.matches("\\d+")).map(Long::valueOf)
            .collect(java.util.stream.Collectors.toSet());
    private final long exitAfterFrames = Long.getLong("engine.exitAfterFrames", -1);

    public Engine(EngineConfig config) {
        this.config = config;
        // Loaded here rather than in run() so the window opens at the player's size, not the project's.
        this.settings = UserSettings.load(UserSettings.fileFor(config), config);
        this.window = new Window(config.title(), settings.windowWidth(), settings.windowHeight());
        this.time = new Time(1f / config.fixedUpdateRate());
        this.scenes = new SceneManager(this);
        this.assets = new Assets(config.assetRoots(), audio::isAvailable);
    }

    /** Opens the window, starts {@code initialGame}, and blocks until the engine is closed. */
    public void run(Game initialGame) {
        try {
            window.init();
        } catch (RuntimeException | Error e) {
            Diagnostics.writeCrashReport(e, "The window could not be opened.");
            throw e;
        }
        try {
            Diagnostics.readGraphics();
            glEnable(GL_DEPTH_TEST);
            if (config.audio() && !audio.open()) {
                System.err.println("[Engine] No audio device; continuing silently.");
            }
            input.setTouchMode(Boolean.getBoolean("engine.touch"));
            StandardActions.define(input.actions());
            applySettings();
            framebufferWidth = window.getWidth();
            framebufferHeight = window.getHeight();

            game = initialGame;
            game.start(this);
            scenes.applyPending();
            DebugMode.start(this);
            loop();
        } catch (RuntimeException | Error e) {
            Diagnostics.writeCrashReport(e, scenes.describe());
            throw e;
        } finally {
            shutdown();
        }
    }

    private void loop() {
        running = true;
        double previous = glfwGetTime();
        double accumulator = 0.0;

        while (running && !window.shouldClose()) {
            double now = glfwGetTime();
            // Capped so a long stall (a breakpoint, a window drag) does not make the loop run hundreds of
            // fixed steps to catch up, stalling again while it does.
            double frameTime = Math.min(now - previous, MAX_FRAME_TIME);
            previous = now;
            DebugMode.frameStart();

            DebugMode.begin("Input");
            window.pollEvents();
            input.poll(window);
            time.beginFrame((float) frameTime);
            if (gui != null) {
                // Before scenes update, so a button the controller presses is clicked this frame.
                gui.navigate(time.delta());
                touchControls.update(input, gui, gui.nothingToPress() && !gui.hasKeyboardFocus());
            }
            handleResize();
            handleDeveloperKeys();
            DebugMode.end();

            float fixedDelta = time.fixedDelta();
            accumulator += frameTime * time.timeScale();
            DebugMode.begin("Fixed update");
            while (accumulator >= fixedDelta) {
                scenes.fixedUpdate(fixedDelta);
                accumulator -= fixedDelta;
            }
            DebugMode.end();
            DebugMode.begin("Update");
            game.update(this, time.delta());
            scenes.update(time.delta());
            DebugMode.end();

            if (framebufferWidth > 0 && framebufferHeight > 0) {
                DebugMode.begin("Render");
                glViewport(0, 0, framebufferWidth, framebufferHeight);
                if (gui != null) {
                    gui.newFrame();
                }
                captureBelow();
                scenes.render((float) (accumulator / fixedDelta));
                DebugMode.end();
                DebugMode.begin("Overlay");
                if (gui != null) {
                    gui.beginOverlay(framebufferWidth, framebufferHeight);
                    touchControls.draw(gui);
                    gui.drawFrameOverlay();
                    gui.endOverlay();
                }
                if (settings.showFps()) {
                    drawStats();
                }
                captureScreenshots();
                DebugMode.end();
            }
            // With vsync, or a graphics card that is behind, the CPU waits in here for it.
            DebugMode.begin(DebugMode.SWAP);
            window.swapBuffers();
            DebugMode.end();
            DebugMode.begin(DebugMode.CAP);
            limitFrameRate(now);
            DebugMode.end();

            DebugMode.begin("Scene changes");
            scenes.applyPending();
            applyPendingGame();
            DebugMode.end();
            DebugMode.frameEnd();

            if (exitAfterFrames > 0 && time.frame() >= exitAfterFrames) {
                running = false;
            }
        }
    }

    private void handleResize() {
        int width = window.getWidth();
        int height = window.getHeight();
        if (width != framebufferWidth || height != framebufferHeight) {
            framebufferWidth = width;
            framebufferHeight = height;
            if (width > 0 && height > 0) {
                scenes.resize(width, height);
            }
        }
    }

    private void handleDeveloperKeys() {
        if (input.isKeyPressed(Key.F3)) {
            settings.setShowFps(!settings.showFps());
        }
        if (input.isKeyPressed(Key.F11)) {
            // Through the settings, so the options screen and the next launch agree with the key.
            settings.setFullscreen(!window.isFullscreen());
            applySettings();
            saveSettingsQuietly();
        }
        if (input.isKeyPressed(Key.F12)) {
            screenshotRequested = true;
        }
    }

    private void drawStats() {
        Gui overlay = gui();
        overlay.begin(framebufferWidth, framebufferHeight);
        Scene top = scenes.current();
        String[] lines = {
                String.format("%.0f FPS  %.2f ms", time.fps(), time.unscaledDelta() * 1000f),
                "Scene: " + (top == null ? "none" : top.getClass().getSimpleName()),
                "Entities: " + (top == null ? 0 : top.entities().size()),
                "Scenes on stack: " + scenes.stack().size(),
        };
        float scale = 2f;
        float lineHeight = overlay.textHeight(scale) + 8f;
        overlay.rect(8f, 8f, 360f, lines.length * lineHeight + 12f, new Color(0f, 0f, 0f, 0.6f));
        for (int i = 0; i < lines.length; i++) {
            overlay.text(lines[i], 16f, 16f + i * lineHeight, scale, Color.WHITE);
        }
        overlay.end();
    }

    private void captureScreenshots() {
        if (autoScreenshot != null && autoScreenshotFrames.isEmpty() && time.frame() == autoScreenshotFrame) {
            Path saved = Screenshot.capture(framebufferWidth, framebufferHeight, Path.of(autoScreenshot));
            System.out.println("[Engine] Screenshot saved to " + saved);
        }
        if (autoScreenshot != null && autoScreenshotFrames.contains(time.frame())) {
            String numbered = autoScreenshot.replaceFirst("(\\.png)?$", "-" + time.frame() + ".png");
            Path saved = Screenshot.capture(framebufferWidth, framebufferHeight, Path.of(numbered));
            System.out.println("[Engine] Screenshot saved to " + saved);
        }
        if (screenshotRequested) {
            screenshotRequested = false;
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path saved = Screenshot.capture(framebufferWidth, framebufferHeight,
                    Path.of("screenshots", "screenshot-" + stamp + ".png"));
            System.out.println("[Engine] Screenshot saved to " + saved);
        }
        // A capture that looks under a scene is taken before its frame is drawn, not from the finished one.
        if (frameCaptured != null && frameCaptureBelow == null) {
            finishCapture();
        }
    }

    /**
     * Takes a waiting capture that looks under a scene: draws the frame without that scene and what is over it, and
     * saves that. The frame proper is drawn over it straight after, so none of this reaches the screen. Where nothing
     * is under the scene any more, the capture becomes one of the whole frame.
     */
    private void captureBelow() {
        if (frameCaptured == null || frameCaptureBelow == null) {
            return;
        }
        Scene below = frameCaptureBelow;
        frameCaptureBelow = null;
        if (scenes.renderBelow(below)) {
            finishCapture();
        }
    }

    private void finishCapture() {
        Path file = frameCaptureFile;
        Runnable then = frameCaptured;
        frameCaptureFile = null;
        frameCaptured = null;
        try {
            Screenshot.capture(framebufferWidth, framebufferHeight, file);
        } catch (RuntimeException e) {
            System.err.println("[Engine] Could not capture the frame: " + e);
        }
        then.run();
    }

    /**
     * Saves the frame being drawn as a PNG once it is finished, then runs {@code then}, whether or not that worked.
     * Replaces a capture still waiting for its frame.
     *
     * @param file an absolute path: the image writer is native, and can't see a folder only Java was told about
     */
    public void captureFrame(Path file, Runnable then) {
        frameCaptureFile = file;
        frameCaptured = then;
        frameCaptureBelow = null;
    }

    /**
     * As {@link #captureFrame}, but of the game as it looks without {@code scene} and the scenes over it: what a menu
     * is covering, not the menu. It is taken as the next frame starts; a scene with nothing under it gives a picture
     * of the whole frame.
     */
    public void captureFrameBelow(Scene scene, Path file, Runnable then) {
        frameCaptureFile = file;
        frameCaptured = then;
        frameCaptureBelow = scene;
    }

    // --- Switching games --------------------------------------------------------------------------------

    /**
     * Replaces the running game at the end of this frame: its scenes exit, its assets are released, and
     * {@code next} starts in the same window.
     */
    public void switchGame(Game next, EngineConfig nextConfig) {
        pendingGame = next;
        pendingConfig = nextConfig;
    }

    /**
     * Remembers the game that launched the current one — the project hub — so {@link #exitToLauncher}
     * can return to it.
     */
    public void setLauncher(Game launcherGame, EngineConfig launcherGameConfig) {
        this.launcher = launcherGame;
        this.launcherConfig = launcherGameConfig;
    }

    /** Returns to the project hub if the game was started from it, otherwise closes the engine. */
    public void exitToLauncher() {
        if (launcher != null && game != launcher) {
            switchGame(launcher, launcherConfig);
        } else {
            quit();
        }
    }

    private void applyPendingGame() {
        if (pendingGame == null) {
            return;
        }
        Game next = pendingGame;
        EngineConfig nextConfig = pendingConfig;
        pendingGame = null;
        pendingConfig = null;

        scenes.clear();
        game.stop(this);
        // The voices let go of this game's sounds before they are deleted: OpenAL won't delete one a voice still holds.
        audio.stopEffects();
        assets.dispose();
        input.setCursorCaptured(false);
        time.setTimeScale(1f);

        config = nextConfig;
        assets = new Assets(nextConfig.assetRoots(), audio::isAvailable);
        window.setTitle(nextConfig.title());
        settings = UserSettings.load(UserSettings.fileFor(nextConfig), nextConfig);
        // The next game's controls start from the defaults and its own saved changes, not this game's.
        input.actions().resetToDefaults();
        applySettings();

        game = next;
        game.start(this);
        scenes.applyPending();
    }

    /** Ends the loop after the current frame. */
    public void quit() {
        running = false;
    }

    private void shutdown() {
        DebugMode.stop();
        try {
            scenes.clear();
            if (game != null) {
                game.stop(this);
            }
            assets.dispose();
            if (spriteBatch != null) {
                spriteBatch.delete();
            }
            if (gui != null) {
                gui.delete();
            }
            if (renderer3D != null) {
                renderer3D.delete();
            }
            if (screenEffects != null) {
                screenEffects.delete();
            }
            audio.destroy();
        } finally {
            window.destroy();
        }
    }

    // --- Services ---------------------------------------------------------------------------------------

    public Window window() {
        return window;
    }

    public Input input() {
        return input;
    }

    /** The running game's player settings. Change them, then call {@link #applySettings()}. */
    public UserSettings settings() {
        return settings;
    }

    /** Puts {@link #settings()} into effect: window mode and size, frame pacing, volumes. */
    public void applySettings() {
        // -Dengine.window=1280x800 opens a window that size whatever the settings say, to try a Steam Deck's screen.
        String forced = System.getProperty("engine.window", "");
        if (forced.matches("\\d+x\\d+")) {
            String[] size = forced.split("x");
            window.setWindowedSize(Integer.parseInt(size[0]), Integer.parseInt(size[1]));
            window.setFullscreen(false);
        } else {
            window.setWindowedSize(settings.windowWidth(), settings.windowHeight());
            window.setFullscreen(settings.fullscreen());
        }
        if (window.isVsync() != settings.vsync()) {
            window.setVsync(settings.vsync());
        }
        audio.setVolumes(settings.masterVolume(), settings.musicVolume(), settings.ambienceVolume(),
                settings.effectsVolume());
        Texture.setAnisotropy(settings.textureFiltering());
        input.actions().restore(settings.bindings());
    }

    /**
     * Holds the frame to the player's frame rate cap, if any: sleeps most of what is left of it, then spins the last
     * millisecond, since a sleep can overshoot by that much.
     *
     * @param frameStart when this frame began, in GLFW seconds
     */
    private void limitFrameRate(double frameStart) {
        int limit = settings.frameRateLimit();
        if (limit <= 0 || exitAfterFrames > 0) {
            return;
        }
        double end = frameStart + 1.0 / limit;
        double left;
        while ((left = end - glfwGetTime()) > 0.0) {
            if (left > 0.0015) {
                try {
                    Thread.sleep((long) ((left - 0.001) * 1000.0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void saveSettingsQuietly() {
        try {
            settings.save();
        } catch (java.io.IOException e) {
            System.err.println("[Engine] Could not save settings: " + e.getMessage());
        }
    }

    public Time time() {
        return time;
    }

    public SceneManager scenes() {
        return scenes;
    }

    public Assets assets() {
        return assets;
    }

    public AudioSystem audio() {
        return audio;
    }

    public EngineConfig config() {
        return config;
    }

    public int width() {
        return framebufferWidth;
    }

    public int height() {
        return framebufferHeight;
    }

    /** The shared 2D sprite renderer. Created on first use. */
    public SpriteBatch spriteBatch() {
        if (spriteBatch == null) {
            spriteBatch = new SpriteBatch();
        }
        return spriteBatch;
    }

    /** The shared immediate-mode GUI. Created on first use. */
    public Gui gui() {
        if (gui == null) {
            gui = new Gui(input);
        }
        return gui;
    }

    /** Whether the GUI drew under the pointer recently, so world clicks should be ignored. */
    public boolean guiWantsPointer() {
        return gui != null && gui.wantsPointer();
    }

    /** The shared 3D renderer (lit meshes, models, terrain, shadows). Created on first use. */
    public Renderer renderer3D() {
        if (renderer3D == null) {
            renderer3D = new Renderer();
        }
        return renderer3D;
    }

    /**
     * The passes the player's picture settings add to a 3D scene (ambient occlusion, bloom, antialiasing, filmic
     * colour). Created on first use; its buffers only once a setting asks for them.
     */
    public ScreenEffects screenEffects() {
        if (screenEffects == null) {
            screenEffects = new ScreenEffects();
        }
        return screenEffects;
    }
}
