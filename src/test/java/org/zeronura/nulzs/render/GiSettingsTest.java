package org.zeronura.nulzs.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the indirect-light settings promise, stated as properties rather than as the tuned numbers.
 *
 * <p>The numbers are art direction and are meant to move; every assertion here is about a relationship that
 * has to survive them moving. The pass itself is GL-bound and can only be judged from a render — see
 * {@code GiPreview} — so what is testable is exactly this: the contracts around it.
 */
class GiSettingsTest {

    @Test
    void offIsInactiveAndTheTunedSettingIsNot() {
        // The "no GI pass ran" state, and the one every renderer without one behaves as. GlobalIllumination
        // skips its whole pipeline on this, so a setting that reported itself active while changing nothing
        // would cost a full gather to produce the frame it started with.
        assertFalse(GiSettings.OFF.isActive(), "OFF must switch the pass off");
        assertTrue(GiSettings.tuned().isActive(), "the tuned default must do something");
    }

    @Test
    void scalingToZeroSwitchesThePassOff() {
        // The video setting's lowest choice goes through scaled(0), and it has to reach the same state as
        // OFF rather than merely a very weak effect: the point of that choice is buying the frame time back.
        assertFalse(GiSettings.tuned().scaled(0f).isActive());
    }

    @Test
    void aQualitySettingChangesTheStrengthAndNeverTheShape() {
        // Radius, bias and range decide what the effect *is* — how far a surface gathers from, and so
        // whether it is reading creases or whole hollows. Only the strengths may follow the setting, or the
        // choices would be different effects rather than different amounts of one, and a player turning it
        // down would see the shading change character rather than fade.
        GiSettings tuned = GiSettings.tuned();
        GiSettings weaker = tuned.scaled(0.5f);

        assertEquals(tuned.radius(), weaker.radius());
        assertEquals(tuned.bias(), weaker.bias());
        assertEquals(tuned.range(), weaker.range());
        assertEquals(tuned.fillFloor(), weaker.fillFloor());
        assertEquals(tuned.samples(), weaker.samples());

        assertEquals(tuned.occlusion() * 0.5f, weaker.occlusion(), 1e-5f);
        assertEquals(tuned.bounce() * 0.5f, weaker.bounce(), 1e-5f);
    }

    @Test
    void theSampleCountIsClampedIntoWhatTheShaderDeclares() {
        // Not defensiveness. gi.frag declares a fixed-size kernel array and the pass uploads `samples` of
        // them; asking for more reads past the end of a uniform array, which is undefined rather than an
        // error. Zero or fewer would divide by zero in the gather.
        assertEquals(GiSettings.MAX_SAMPLES,
                new GiSettings(1f, 0f, 1f, 1f, 0f, 100f, GiSettings.MAX_SAMPLES + 8).samples());
        assertEquals(1, new GiSettings(1f, 0f, 1f, 1f, 0f, 100f, 0).samples());
        assertEquals(1, new GiSettings(1f, 0f, 1f, 1f, 0f, 100f, -5).samples());
    }

    /**
     * The shader's kernel array is as long as {@link GiSettings#MAX_SAMPLES} says it is.
     *
     * <p>The one genuinely dangerous piece of drift in this feature, and it lives in two files in two
     * languages that no compiler checks against each other. Raising the Java constant alone makes the pass
     * upload uniforms past the end of the array and read them back in the loop — undefined, and on the
     * driver this was written against it does not crash or warn, it just returns whatever is next in the
     * uniform block. The same rule {@code WorldWindTest} applies to the sway rate the two foliage shaders
     * each carry a copy of.
     */
    @Test
    void theShaderDeclaresTheSameKernelSizeTheSettingsAllow() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/shaders/gi.frag"));
        Integer declared = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#define MAX_SAMPLES")) {
                declared = Integer.parseInt(trimmed.substring("#define MAX_SAMPLES".length()).trim());
            }
        }
        assertEquals(GiSettings.MAX_SAMPLES, declared,
                "gi.frag's kernel array and GiSettings.MAX_SAMPLES must agree");
    }
}
