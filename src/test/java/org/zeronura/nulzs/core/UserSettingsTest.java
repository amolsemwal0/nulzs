package org.zeronura.nulzs.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSettingsTest {

    private static final EngineConfig CONFIG = EngineConfig.defaults().withTitle("Elementals");

    @Test
    void aMissingFileStartsFromTheProjectConfig(@TempDir Path dir) {
        EngineConfig config = new EngineConfig("Game", 1600, 900, false, true, 60, java.util.List.of(), false);
        UserSettings settings = UserSettings.load(dir.resolve("none.properties"), config);
        assertTrue(settings.fullscreen());
        assertFalse(settings.vsync());
        assertEquals(1600, settings.windowWidth());
        assertEquals(900, settings.windowHeight());
        assertEquals(1f, settings.mouseSensitivity());
    }

    @Test
    void everySettingSurvivesASaveAndLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        UserSettings settings = UserSettings.load(file, CONFIG);
        settings.setFullscreen(true);
        settings.setVsync(false);
        settings.setWindowSize(1280, 720);
        settings.setShowFps(true);
        settings.setMasterVolume(0.5f);
        settings.setMusicVolume(0.25f);
        settings.setAmbienceVolume(0f);
        settings.setEffectsVolume(0.75f);
        settings.setMouseSensitivity(2f);
        settings.setInvertY(true);
        settings.save();

        UserSettings loaded = UserSettings.load(file, CONFIG);
        assertTrue(loaded.fullscreen());
        assertFalse(loaded.vsync());
        assertEquals(1280, loaded.windowWidth());
        assertEquals(720, loaded.windowHeight());
        assertTrue(loaded.showFps());
        assertEquals(0.5f, loaded.masterVolume());
        assertEquals(0.25f, loaded.musicVolume());
        assertEquals(0f, loaded.ambienceVolume());
        assertEquals(0.75f, loaded.effectsVolume());
        assertEquals(2f, loaded.mouseSensitivity());
        assertTrue(loaded.invertY());
    }

    @Test
    void badValuesKeepTheirDefaultsAndOutOfRangeOnesAreClamped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "vsync=maybe\nmasterVolume=7\nmusicVolume=loud\nmouseSensitivity=-1\n");
        UserSettings loaded = UserSettings.load(file, CONFIG);
        assertEquals(CONFIG.vsync(), loaded.vsync());
        assertEquals(1f, loaded.masterVolume());
        assertEquals(0.8f, loaded.musicVolume());
        assertEquals(UserSettings.MIN_SENSITIVITY, loaded.mouseSensitivity());
    }

    @Test
    void eachGameGetsItsOwnFile() {
        assertEquals(Path.of("saves", "settings", "elementals.properties"), UserSettings.fileFor(CONFIG));
        assertEquals(Path.of("saves", "settings", "gameengine-project-hub.properties"),
                UserSettings.fileFor(CONFIG.withTitle("GameEngine - Project Hub")));
    }

    @Test
    void videoSettingsAndChangedKeysSurviveASaveAndLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        UserSettings settings = UserSettings.load(file, CONFIG);
        settings.setFrameRateLimit(60);
        settings.setShadowQuality(UserSettings.ShadowQuality.LOW);
        settings.setTextureFiltering(4);
        settings.setFieldOfView(60f);
        settings.setBindings(java.util.Map.of("interact", "F", "menu", "M,TAB"));
        settings.save();

        UserSettings loaded = UserSettings.load(file, CONFIG);
        assertEquals(60, loaded.frameRateLimit());
        assertEquals(UserSettings.ShadowQuality.LOW, loaded.shadowQuality());
        assertEquals(4, loaded.textureFiltering());
        assertEquals(60f, loaded.fieldOfView());
        assertEquals(java.util.Map.of("interact", "F", "menu", "M,TAB"), loaded.bindings());
    }

    @Test
    void pictureSettingsSurviveASaveAndLoad(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        UserSettings settings = UserSettings.load(file, CONFIG);
        settings.setAmbientOcclusion(UserSettings.AmbientOcclusion.LOW);
        settings.setBloom(UserSettings.Bloom.STRONG);
        settings.setAntiAliasing(UserSettings.AntiAliasing.MSAA_8X);
        settings.setSunRays(UserSettings.SunRays.STRONG);
        settings.setParticles(UserSettings.Particles.LOW);
        settings.save();

        UserSettings loaded = UserSettings.load(file, CONFIG);
        assertEquals(UserSettings.SunRays.STRONG, loaded.sunRays());
        assertEquals(UserSettings.Particles.LOW, loaded.particles());
        assertEquals(UserSettings.AmbientOcclusion.LOW, loaded.ambientOcclusion());
        assertEquals(UserSettings.Bloom.STRONG, loaded.bloom());
        assertEquals(UserSettings.AntiAliasing.MSAA_8X, loaded.antiAliasing());
    }

    @Test
    void aFileFromBeforeThePictureSettingsGetsOcclusionBloomAndMultisampling(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "shadowQuality=HIGH\nambientOcclusion=EXTREME\nantiAliasing=yes\n");
        UserSettings loaded = UserSettings.load(file, CONFIG);
        assertEquals(UserSettings.AmbientOcclusion.HIGH, loaded.ambientOcclusion(), "an unknown level keeps the default");
        assertEquals(UserSettings.Bloom.SOFT, loaded.bloom());
        assertEquals(UserSettings.SunRays.SOFT, loaded.sunRays(), "a file from before sun rays gets the soft ones");
        assertEquals(UserSettings.Particles.HIGH, loaded.particles(), "and one from before particles gets them all");
        assertEquals(UserSettings.AntiAliasing.MSAA_4X, loaded.antiAliasing(), "smooths edges and leaves pixel art as it is");
        assertEquals(2, UserSettings.AntiAliasing.SUPERSAMPLING.scale());
        assertEquals(1, UserSettings.AntiAliasing.FXAA.scale());
        assertEquals(1, UserSettings.AntiAliasing.MSAA_4X.scale(), "multisampling draws at the window's size");
        assertEquals(4, UserSettings.AntiAliasing.MSAA_4X.samples());
        assertEquals(1, UserSettings.AntiAliasing.SUPERSAMPLING.samples());
    }

    @Test
    void bloomSavedAsASwitchKeepsItsWay(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "bloom=false\n");
        assertEquals(UserSettings.Bloom.OFF, UserSettings.load(file, CONFIG).bloom());
        Files.writeString(file, "bloom=true\n");
        assertEquals(UserSettings.Bloom.SOFT, UserSettings.load(file, CONFIG).bloom());
    }

    @Test
    void anOutOfRangeFieldOfViewIsClamped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "fieldOfView=500\nshadowQuality=ULTRA\n");
        UserSettings loaded = UserSettings.load(file, CONFIG);
        assertEquals(UserSettings.MAX_FIELD_OF_VIEW, loaded.fieldOfView());
        assertEquals(UserSettings.ShadowQuality.HIGH, loaded.shadowQuality(), "an unknown quality keeps the default");
    }
}
