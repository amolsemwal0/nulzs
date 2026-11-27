package org.zeronura.nulzs.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything the engine needs to know before the window exists.
 *
 * <p>Immutable, with {@code with*} copies, so a project file, a command line and a game's own defaults
 * can each adjust it in turn without any of them seeing a half-applied state.
 *
 * @param title           window title
 * @param width           initial window width in pixels
 * @param height          initial window height in pixels
 * @param vsync           cap the frame rate to the display
 * @param fullscreen      start in borderless fullscreen
 * @param fixedUpdateRate simulation steps per second for {@code fixedUpdate}
 * @param assetRoots      directories searched, in order, when an asset is loaded by relative path
 * @param audio           open an audio device at startup
 */
public record EngineConfig(
        String title,
        int width,
        int height,
        boolean vsync,
        boolean fullscreen,
        int fixedUpdateRate,
        List<Path> assetRoots,
        boolean audio
) {

    public static final int DEFAULT_WIDTH = 1920;
    public static final int DEFAULT_HEIGHT = 1080;
    public static final int DEFAULT_FIXED_UPDATE_RATE = 60;

    public EngineConfig {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window size must be positive: " + width + "x" + height);
        }
        if (fixedUpdateRate <= 0) {
            throw new IllegalArgumentException("fixedUpdateRate must be positive: " + fixedUpdateRate);
        }
        assetRoots = List.copyOf(assetRoots);
    }

    public static EngineConfig defaults() {
        return new EngineConfig("Nulzs Game Engine", DEFAULT_WIDTH, DEFAULT_HEIGHT, true, false,
                DEFAULT_FIXED_UPDATE_RATE, List.of(Path.of("assets")), true);
    }

    public EngineConfig withTitle(String newTitle) {
        return new EngineConfig(newTitle, width, height, vsync, fullscreen, fixedUpdateRate, assetRoots, audio);
    }

    public EngineConfig withSize(int newWidth, int newHeight) {
        return new EngineConfig(title, newWidth, newHeight, vsync, fullscreen, fixedUpdateRate, assetRoots, audio);
    }

    public EngineConfig withVsync(boolean enabled) {
        return new EngineConfig(title, width, height, enabled, fullscreen, fixedUpdateRate, assetRoots, audio);
    }

    public EngineConfig withFullscreen(boolean enabled) {
        return new EngineConfig(title, width, height, vsync, enabled, fixedUpdateRate, assetRoots, audio);
    }

    public EngineConfig withFixedUpdateRate(int rate) {
        return new EngineConfig(title, width, height, vsync, fullscreen, rate, assetRoots, audio);
    }

    public EngineConfig withAssetRoots(List<Path> roots) {
        return new EngineConfig(title, width, height, vsync, fullscreen, fixedUpdateRate, roots, audio);
    }

    public EngineConfig withAudio(boolean enabled) {
        return new EngineConfig(title, width, height, vsync, fullscreen, fixedUpdateRate, assetRoots, enabled);
    }
}
