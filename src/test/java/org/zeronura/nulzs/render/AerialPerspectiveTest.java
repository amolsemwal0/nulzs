package org.zeronura.nulzs.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shader that fogs anything works out the air between the eye and a fragment the same way.
 *
 * <p>Same position {@code cutoutAlpha} and {@code SWAY_RATE_PER_SPEED} are already in, and settled the
 * same way: GLSL 330 has no {@code #include} and there is no shader preprocessor here, so
 * {@code aerialPerspective} is copied into all eight and the copies are read back off disk and pinned.
 *
 * <p><b>What drift would look like, which is why it is worth a test rather than a comment.</b> A hillside,
 * the trees standing on it, the grass between them and the water at its foot are four different shaders
 * drawing one place. If they disagreed about how much haze lies between that place and the camera, the
 * seam would run along the boundary between materials rather than along anything in the world — and it
 * would move as the player walked, since it is the distance the copies would be disagreeing about. No
 * still frame explains a fault of that shape.
 */
class AerialPerspectiveTest {

    private static final String SHADERS = "src/main/resources/shaders/";

    /**
     * Every shader that fogs, and there must be no others.
     *
     * <p>Listed rather than discovered so that adding a ninth is a deliberate act. A new scene shader that
     * quietly went back to a plain {@code smoothstep(fogStart, fogEnd, distance)} would look perfectly
     * correct on flat ground — that is exactly what the stratified form reduces to there — and would only
     * give itself away on a mountain, which is the one place this whole mechanism exists for.
     */
    private static final List<String> FOG_SHADERS = List.of(
            "terrain.frag", "foliage.frag", "anime_leaves.frag", "character.frag", "entity.frag",
            "grass.frag", "impostor.frag", "fluid.frag", "sph_composite.frag", "nodes/node_material.frag",
            "lit_impostor.frag");

    @Test
    void everyFoggingShaderGoesThroughTheSharedTerm() throws IOException {
        for (String shader : FOG_SHADERS) {
            String source = Files.readString(Path.of(SHADERS + shader));
            assertTrue(source.contains("float aerialPerspective(vec3 fragWorldPos, vec3 eyeWorldPos)"),
                    shader + " no longer carries aerialPerspective, so whatever it fogs with is a second "
                            + "opinion about the air");
            assertTrue(source.contains("aerialPerspective(worldPos, viewPos)"),
                    shader + " declares aerialPerspective but never calls it, which is the same as not "
                            + "having it and is harder to notice");
        }
    }

    @Test
    void theCopiesAgreeToTheCharacter() throws IOException {
        String reference = null;
        String referenceName = null;
        for (String shader : FOG_SHADERS) {
            String body = bodyOf(shader);
            if (reference == null) {
                reference = body;
                referenceName = shader;
                continue;
            }
            assertEquals(reference, body,
                    shader + " and " + referenceName + " no longer measure the air the same way; two "
                            + "surfaces in one frame would carry different amounts of haze");
        }
    }

    /**
     * The haze is a layer, and every copy says so.
     *
     * <p>The one line that could be deleted while leaving a shader that still compiles, still fogs, and
     * still looks right everywhere except on the thing the layer exists for.
     */
    @Test
    void everyCopyWeightsTheHazeByAltitudeAndCapsItAtTheHorizon() throws IOException {
        for (String shader : FOG_SHADERS) {
            String body = bodyOf(shader);
            assertTrue(body.contains("fogHeightFalloff"),
                    shader + " fogs on distance alone again, so a summit and the plain at its foot "
                            + "dissolve at the same rate and the summit is not a silhouette");
            assertTrue(body.contains("fogHorizon"),
                    shader + " has no horizon term, so tall ground escapes the haze without limit and "
                            + "then pops out at the far plane instead of fading");
        }
    }

    /**
     * The function's own statements, with comments and blank lines stripped.
     *
     * <p>Comments are stripped because a test that compared them would pin its own explanation — the trap
     * {@code MoltenGradientTest} records — and because each copy is free to sit beside its own caller.
     */
    private static String bodyOf(String shader) throws IOException {
        List<String> body = new ArrayList<>();
        boolean inside = false;
        for (String line : Files.readAllLines(Path.of(SHADERS + shader))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("float aerialPerspective(")) {
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
            throw new AssertionError(shader + " declares no aerialPerspective to compare");
        }
        return String.join("\n", body);
    }
}
