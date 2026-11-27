package org.zeronura.engine.render;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.core.EngineConfig;
import org.zeronura.engine.core.UserSettings;

import static org.junit.jupiter.api.Assertions.*;

class ScreenEffectsTest {

    private static UserSettings settings() {
        return UserSettings.defaults(EngineConfig.defaults());
    }

    @Test
    void withEveryPictureSettingOffTheFrameGoesStraightToTheWindow() {
        UserSettings settings = settings();
        assertTrue(ScreenEffects.wanted(settings), "occlusion, bloom and multisampling are on out of the box");
        settings.setAmbientOcclusion(UserSettings.AmbientOcclusion.OFF);
        settings.setBloom(UserSettings.Bloom.OFF);
        assertTrue(ScreenEffects.wanted(settings), "multisampling alone");
        settings.setAntiAliasing(UserSettings.AntiAliasing.OFF);
        assertTrue(ScreenEffects.wanted(settings), "sun rays alone");
        settings.setSunRays(UserSettings.SunRays.OFF);
        assertFalse(ScreenEffects.wanted(settings));
        settings.setAntiAliasing(UserSettings.AntiAliasing.FXAA);
        assertTrue(ScreenEffects.wanted(settings));
    }

    @Test
    void occlusionLevelsGetStrongerAndOnlyHighBouncesColour() {
        assertFalse(ScreenEffects.occlusion(UserSettings.AmbientOcclusion.OFF).isActive());
        GiSettings low = ScreenEffects.occlusion(UserSettings.AmbientOcclusion.LOW);
        GiSettings high = ScreenEffects.occlusion(UserSettings.AmbientOcclusion.HIGH);
        assertTrue(low.isActive());
        assertEquals(0f, low.bounce());
        assertTrue(high.bounce() > 0f);
        assertTrue(high.samples() > low.samples());
    }

    @Test
    void thePictureKeepsTheScenesOwnColours() {
        UserSettings settings = settings();
        PostSettings post = ScreenEffects.post(settings);
        assertFalse(post.filmic(), "a scene lit for the window's range only greys under a filmic curve");
        assertEquals(1f, post.exposure());
        assertEquals(1f, post.contrast());
        assertEquals(1f, post.saturation());
        assertEquals(0f, post.vignette());
        assertTrue(post.bloomIntensity() > 0f);
        assertTrue(post.bloomThreshold() < 1f, "sunlit whites, just under white, glow softly");
        settings.setBloom(UserSettings.Bloom.STRONG);
        PostSettings strong = ScreenEffects.post(settings);
        assertTrue(strong.bloomIntensity() > post.bloomIntensity() && strong.bloomThreshold() < post.bloomThreshold());
        settings.setBloom(UserSettings.Bloom.OFF);
        assertEquals(0f, ScreenEffects.post(settings).bloomIntensity());
    }

    @Test
    void sunRayLevelsGetStrongerAndOffHasNone() {
        assertEquals(0f, UserSettings.SunRays.OFF.strength());
        assertTrue(UserSettings.SunRays.SOFT.strength() > 0f);
        assertTrue(UserSettings.SunRays.STRONG.strength() > UserSettings.SunRays.SOFT.strength());
        assertEquals(UserSettings.SunRays.SOFT, settings().sunRays(), "soft shafts out of the box");
        assertEquals(SunRays.size(1920), 960, "half the window's size");
    }
}
