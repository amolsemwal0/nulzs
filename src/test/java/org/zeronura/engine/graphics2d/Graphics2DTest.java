package org.zeronura.engine.graphics2d;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The GL-free parts of 2D: animation timing, camera maths and tilemaps. */
class Graphics2DTest {

    // --- Animation --------------------------------------------------------------------------------------

    @Test
    void loopingAnimationsWrapAround() {
        Animation<String> walk = Animation.of(0.1f, Animation.PlayMode.LOOP, "a", "b", "c");
        assertEquals("a", walk.frame(0f));
        assertEquals("b", walk.frame(0.15f));
        assertEquals("c", walk.frame(0.25f));
        assertEquals("a", walk.frame(0.31f));
        assertFalse(walk.isFinished(100f));
    }

    @Test
    void playOnceAnimationsHoldTheirLastFrame() {
        Animation<String> attack = Animation.of(0.1f, Animation.PlayMode.ONCE, "a", "b", "c");
        assertEquals("c", attack.frame(5f));
        assertFalse(attack.isFinished(0.29f));
        assertTrue(attack.isFinished(0.3f));
    }

    @Test
    void pingPongAnimationsBounceWithoutRepeatingTheEnds() {
        Animation<Integer> bounce = Animation.of(1f, Animation.PlayMode.PING_PONG, 0, 1, 2);
        int[] expected = {0, 1, 2, 1, 0, 1, 2};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], bounce.frame(i + 0.5f));
        }
    }

    // --- Camera2D ---------------------------------------------------------------------------------------

    @Test
    void theScreenCentreIsTheCameraPosition() {
        Camera2D camera = new Camera2D(800f, 600f);
        camera.position.set(100f, 50f);
        Vector2f world = camera.screenToWorld(400f, 300f, new Vector2f());
        assertEquals(100f, world.x, 1e-4f);
        assertEquals(50f, world.y, 1e-4f);
    }

    @Test
    void screenYPointsDownWhileWorldYPointsUp() {
        Camera2D camera = new Camera2D(800f, 600f);
        Vector2f topLeft = camera.screenToWorld(0f, 0f, new Vector2f());
        assertEquals(-400f, topLeft.x, 1e-4f);
        assertEquals(300f, topLeft.y, 1e-4f);
    }

    @Test
    void screenAndWorldConversionsRoundTripUnderZoomAndRotation() {
        Camera2D camera = new Camera2D(1280f, 720f);
        camera.position.set(-30f, 12f);
        camera.setZoom(2.5f);
        camera.setRotation(0.7f);

        Vector2f world = camera.screenToWorld(123f, 456f, new Vector2f());
        Vector2f screen = camera.worldToScreen(world.x, world.y, new Vector2f());

        assertEquals(123f, screen.x, 1e-2f);
        assertEquals(456f, screen.y, 1e-2f);
    }

    @Test
    void theShaderMatrixAgreesWithWorldToScreen() {
        Camera2D camera = new Camera2D(1280f, 720f);
        camera.position.set(40f, -8f);
        camera.setVisibleHeight(18f);
        camera.setRotation(-0.4f);

        Vector3f ndc = camera.combined(new Matrix4f()).transformProject(43f, -5f, 0f, new Vector3f());
        Vector2f expected = camera.worldToScreen(43f, -5f, new Vector2f());

        assertEquals(expected.x, (ndc.x + 1f) * 0.5f * 1280f, 1e-2f);
        assertEquals(expected.y, (1f - ndc.y) * 0.5f * 720f, 1e-2f);
    }

    @Test
    void aFixedVisibleHeightIgnoresTheWindowSize() {
        Camera2D camera = new Camera2D(800f, 600f);
        camera.setVisibleHeight(18f);
        assertEquals(18f, camera.visibleHeight(), 1e-4f);
        camera.setViewport(1920f, 1080f);
        assertEquals(18f, camera.visibleHeight(), 1e-4f);
        assertEquals(32f, camera.visibleWidth(), 1e-4f);
    }

    @Test
    void limitsKeepTheViewInsideTheLevel() {
        Camera2D camera = new Camera2D(800f, 600f);
        camera.setLimits(0f, 0f, 2000f, 1000f);
        camera.position.set(-500f, 5000f);
        camera.applyLimits();
        assertEquals(400f, camera.position.x, 1e-4f);
        assertEquals(700f, camera.position.y, 1e-4f);
    }

    // --- Tilemap ----------------------------------------------------------------------------------------

    @Test
    void asciiLevelsReadTopRowFirst() {
        Tilemap map = Tilemap.fromAscii(1f, Map.of('#', 1), "#  C", "####");
        map.setSolid(1);

        assertEquals(4, map.width());
        assertEquals(2, map.height());
        assertTrue(map.isSolid(0, 1), "top-left # is at tile y 1");
        assertFalse(map.isSolid(1, 1));
        assertTrue(map.isSolid(3, 0));
        assertFalse(map.isSolid(-1, 0), "outside the map is empty");
        assertEquals(Tilemap.EMPTY, map.get(99, 99));
    }

    @Test
    void markersAreReportedAtTileCentresRelativeToTheOrigin() {
        Tilemap map = Tilemap.fromAscii(2f, Map.of('#', 1), "#  C", "####");
        map.setOrigin(10f, 20f);

        Vector2f coin = map.markers('C').getFirst();
        assertEquals(10f + 3.5f * 2f, coin.x, 1e-4f);
        assertEquals(20f + 1.5f * 2f, coin.y, 1e-4f);
        assertTrue(map.markers('Z').isEmpty());
        assertEquals(3, map.worldToTileX(10f + 7.9f));
        assertEquals(0, map.worldToTileY(20.5f));
    }

    @Test
    void colorsCanBeParsedFromHex() {
        Color color = Color.rgb(0xFF8000);
        assertEquals(1f, color.r(), 1e-4f);
        assertEquals(128f / 255f, color.g(), 1e-4f);
        assertEquals(0f, color.b(), 1e-4f);
        assertEquals(0.5f, Color.rgba(0x00000080).a(), 1e-2f);
    }
}
