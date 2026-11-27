package org.zeronura.engine.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four shaders that discard on a cutout do it by the same rule.
 *
 * <p>GLSL 330 has no {@code #include} and this project has no shader preprocessor, so {@code cutoutAlpha}
 * and the constant it reads are copied into every shader that needs them. That is the position
 * {@code SWAY_RATE_PER_SPEED} is already in, and it is settled the same way: the copies are read back off
 * disk and pinned equal, because the alternative is that they drift and nothing says so.
 *
 * <p><b>What drift would look like, which is why this is worth a test rather than a comment.</b> A canopy
 * is drawn by {@code foliage.frag} and cast by {@code depth_cutout.frag}. If those two disagree about
 * which texels survive, the shadow map records leaves the shading pass has thrown away — so the tree
 * draws shade on the ground under gaps you can see straight through. It is a perfectly convincing
 * picture of a tree, in a frame nobody would look at twice, and it moves with the sun.
 *
 * <p>The impostor is the other half of the same rule: a tree crosses {@code IMPOSTOR_DISTANCE} and swaps
 * from one shader to the other mid-frame, so a billboard that eroded on a different schedule from the
 * mesh it replaces would change thickness at the swap.
 */
class AlphaCutoutTest {

    private static final String SHADERS = "src/main/resources/shaders/";

    /**
     * Every shader that discards on an alpha threshold, and there must be no others.
     *
     * <p>Listed rather than discovered so that adding a fifth is a deliberate act: a new cutout shader
     * that quietly used a plain {@code a < cutoff} would erode at distance exactly as the four here used
     * to, and would be invisible until somebody backed away from it.
     */
    private static final List<String> CUTOUT_SHADERS =
            List.of("foliage.frag", "depth_cutout.frag", "impostor.frag", "grass.frag", "anime_leaves.frag",
                    "lit_impostor.frag");

    @Test
    void everyCutoutShaderRescalesItsAlphaByTheMipLevelBeingRead() throws IOException {
        for (String shader : CUTOUT_SHADERS) {
            String source = Files.readString(Path.of(SHADERS + shader));
            assertTrue(source.contains("float cutoutAlpha(sampler2D tex, vec2 texUv, float sampledAlpha)"),
                    shader + " no longer carries cutoutAlpha, so whatever it discards on is eroding with "
                            + "distance again — see the note in depth_cutout.frag for what that looks like");
            assertTrue(source.contains("cutoutAlpha(albedoTexture"),
                    shader + " declares cutoutAlpha but never calls it, which is the same as not having "
                            + "it and is harder to notice");
        }
    }

    @Test
    void theCopiesOfTheRescaleAgreeToTheCharacter() throws IOException {
        String reference = null;
        String referenceName = null;
        for (String shader : CUTOUT_SHADERS) {
            String body = bodyOf(shader);
            if (reference == null) {
                reference = body;
                referenceName = shader;
                continue;
            }
            assertEquals(reference, body,
                    shader + " and " + referenceName + " no longer compute coverage the same way; a "
                            + "canopy and its own shadow would disagree about which leaves exist");
        }
    }

    @Test
    void theCopiesOfTheScaleConstantAgree() throws IOException {
        float reference = mipAlphaScaleIn(CUTOUT_SHADERS.get(0));
        for (String shader : CUTOUT_SHADERS) {
            assertEquals(reference, mipAlphaScaleIn(shader), 1e-6f,
                    shader + " scales alpha per mip level differently from " + CUTOUT_SHADERS.get(0));
        }
        // Stated as a range rather than as the number, since it is tuning. Zero would be the plain test
        // this replaced; well above 1 would make a distant canopy denser than the near one it matches.
        assertTrue(reference > 0f && reference < 1f,
                "a per-level scale of " + reference + " is either no rescale at all or one that grows a "
                        + "canopy as it recedes");
    }

    /**
     * The function's own statements, with comments and blank lines stripped.
     *
     * <p>Comments are stripped because they are the one part that legitimately differs — each copy sits
     * beside its own caller — and because a test that compared them would pin its own explanation, which
     * is the trap {@code MoltenGradientTest} already records.
     */
    private static String bodyOf(String shader) throws IOException {
        List<String> body = new ArrayList<>();
        boolean inside = false;
        for (String line : Files.readAllLines(Path.of(SHADERS + shader))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("float cutoutAlpha(")) {
                inside = true;
            }
            if (!inside || trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            body.add(trimmed);
            if (trimmed.equals("}")) {
                break;
            }
        }
        if (body.isEmpty()) {
            throw new AssertionError(shader + " declares no cutoutAlpha to compare");
        }
        return String.join("\n", body);
    }

    private static float mipAlphaScaleIn(String shader) throws IOException {
        for (String line : Files.readAllLines(Path.of(SHADERS + shader))) {
            int at = line.indexOf("MIP_ALPHA_SCALE =");
            if (at >= 0) {
                return Float.parseFloat(
                        line.substring(at + "MIP_ALPHA_SCALE =".length()).replace(";", "").trim());
            }
        }
        throw new AssertionError(shader + " no longer declares MIP_ALPHA_SCALE; if the constant moved, "
                + "this test has to follow it or it silently stops checking anything");
    }
}
