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
 * The near trees' leaves and their far pictures take their colour from one ramp.
 *
 * <p>GLSL 330 has no {@code #include}, so the ramp — its stops, where a clump sits on it, the light of the hour — is
 * copied between the LEAF RAMP lines of {@code anime_leaves.frag} and {@code lit_impostor.frag}, and read back here and
 * pinned equal, as the other copied blocks are. Were they to drift, a tree would change colour as it turned into its
 * picture at the far-tree distance: a seam travelling across every wood as the player walks.
 */
class LeafRampTest {

    private static final String SHADERS = "src/main/resources/shaders/";
    private static final List<String> RAMPED = List.of("anime_leaves.frag", "lit_impostor.frag");

    @Test
    void theCopiesOfTheRampAgreeToTheCharacter() throws IOException {
        String reference = rampOf(RAMPED.getFirst());
        for (String shader : RAMPED) {
            assertEquals(reference, rampOf(shader),
                    shader + " colours its leaves from a different ramp than " + RAMPED.getFirst()
                            + "; near trees and their far pictures would not match");
        }
    }

    @Test
    void bothColourTheirLeavesFromIt() throws IOException {
        for (String shader : RAMPED) {
            String main = Files.readString(Path.of(SHADERS + shader));
            main = main.substring(main.indexOf("void main()"));
            assertTrue(main.contains("crownPlace(normal, toSun)"), shader + " no longer places its leaves on the ramp");
            assertTrue(main.contains("leafRamp(albedo, place) * rampLight(ambientColor, sunColor)"),
                    shader + " no longer takes its colour from the ramp in the light of the hour");
        }
    }

    /** The lines between the markers, comments and blank lines left out: each copy explains itself where it sits. */
    private static String rampOf(String shader) throws IOException {
        List<String> body = new ArrayList<>();
        boolean inside = false;
        for (String line : Files.readAllLines(Path.of(SHADERS + shader))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("// --- LEAF RAMP BEGIN")) {
                inside = true;
                continue;
            }
            if (trimmed.startsWith("// --- LEAF RAMP END")) {
                return String.join("\n", body);
            }
            if (inside && !trimmed.isEmpty() && !trimmed.startsWith("//")) {
                body.add(trimmed);
            }
        }
        throw new AssertionError(shader + " has no complete LEAF RAMP block to compare");
    }
}
