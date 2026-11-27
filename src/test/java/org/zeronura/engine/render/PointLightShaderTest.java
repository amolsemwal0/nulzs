package org.zeronura.engine.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every scene shader that lamps light lights by them the same way, with the same number of them as
 * {@link PointLights} gives. GLSL 330 has no {@code #include}, so {@code pointLight} is copied into each and the
 * copies are read back off disk and pinned, as {@link AerialPerspectiveTest} does the fog.
 *
 * <p>The ground, the props on it, the walls and trees round it and the people on it are two shaders. Copies that
 * disagreed would draw a lamp's pool stopping at the edge of the paving and starting again, differently, on the bench.
 */
class PointLightShaderTest {

    private static final String SHADERS = "src/main/resources/shaders/";
    private static final List<String> LIT = List.of("terrain.frag", "foliage.frag", "anime_leaves.frag");

    @Test
    void everyCopyAgreesAndIsCalled() throws IOException {
        String reference = null;
        for (String shader : LIT) {
            String source = Files.readString(Path.of(SHADERS + shader));
            assertTrue(source.contains("const int MAX_POINT_LIGHTS = " + PointLights.MAX + ";"),
                    shader + " takes a different number of lights from what PointLights gives");
            assertTrue(source.contains("albedo * pointLight(worldPos, normal)"),
                    shader + " declares pointLight but never lights with it");
            String body = function(shader, source);
            if (reference == null) {
                reference = body;
            } else {
                assertEquals(reference, body, shader + " lights by its lamps differently from " + LIT.getFirst());
            }
        }
    }

    /** The function's statements, comments and blank lines left out, to its closing brace. */
    private static String function(String shader, String source) {
        List<String> body = new ArrayList<>();
        int depth = 0;
        boolean inside = false;
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("vec3 pointLight(")) {
                inside = true;
            }
            if (!inside || trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }
            body.add(trimmed);
            depth += trimmed.chars().filter(c -> c == '{').count() - trimmed.chars().filter(c -> c == '}').count();
            if (depth == 0 && trimmed.contains("}")) {
                return String.join("\n", body);
            }
        }
        throw new AssertionError(shader + " has no whole pointLight to compare");
    }
}
