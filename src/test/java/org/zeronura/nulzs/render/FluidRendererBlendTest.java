package org.zeronura.nulzs.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fluid pass blends against fluid.frag's second output, per-channel transmittance, so water can hide red before
 * blue (Beer's law over three absorption rates). Read out of the source: the blend state has no GL-free way to be
 * asked about.
 */
class FluidRendererBlendTest {

    /**
     * The renderer must be asking for it. A blend left at plain alpha would ignore the second output
     * entirely and quietly go back to hiding every channel by the same fraction — the exact look this
     * replaced, with all the machinery still in place and doing nothing.
     */
    @Test
    void theRendererAsksForTheSecondSourceAndPutsTheOrdinaryBlendBack() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/org/zeronura/nulzs/render/FluidRenderer.java"));
        assertTrue(src.contains("glBlendFuncSeparate(GL_ONE, GL_SRC1_COLOR"),
                "the fluid pass should blend against the shader's second source");
        assertTrue(src.contains("glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)"),
                "and must restore the ordinary blend, or every translucent pass after it reads an "
                        + "undefined second source");
    }
}
