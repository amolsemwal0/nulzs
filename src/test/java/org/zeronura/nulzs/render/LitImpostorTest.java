package org.zeronura.nulzs.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a far prop's picture is framed: no GL. */
class LitImpostorTest {

    @Test
    void theFrameHoldsThePropSeenFromAnySideAndTurnedCardsWhole() {
        TexturedMeshBuilder trunk = new TexturedMeshBuilder();
        trunk.addQuad(new Vector3f(0f, 5f, 0f), new Vector3f(0.5f, 0f, 0f), new Vector3f(0f, 5f, 0f),
                new Vector3f(0f, 0f, 1f), new Vector3f(1f, 1f, 1f));
        TexturedMeshBuilder crown = new TexturedMeshBuilder();
        // A card 3 m out to the side and 8 m up, 2 m square: turned to the camera its corners reach sqrt(2) from it.
        crown.addCard(new Vector3f(0f, 8f, 3f), new Vector3f(1f, 0f, 0f), new Vector3f(0f, 1f, 0f),
                new Vector3f(0f, 0f, 1f), new Vector3f(1f, 1f, 1f));
        float[] frame = LitImpostor.frame(List.of(
                new LitImpostor.Part(trunk.buildData(), null, 0f, false),
                new LitImpostor.Part(crown.buildData(), null, 0.5f, true)));
        float reach = 3f + (float) Math.sqrt(2);
        float extent = frame[0];
        float bottom = frame[1];
        assertTrue(extent >= reach * 2f, "as wide as the card's reach both ways from the trunk: " + extent);
        assertTrue(bottom + extent >= 8f + (float) Math.sqrt(2), "as tall as the turned card's top");
        assertTrue(bottom <= 0f && bottom > -extent * 0.1f, "the feet near the bottom edge: " + bottom);
        assertEquals(Math.max(10f, reach * 2f) * 1.06f, extent, 1e-3f);
    }
}
