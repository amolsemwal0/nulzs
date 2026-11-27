package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where a dome of clouds draws its clouds: at their directions and sizes, shown by coverage, turned by the wind; and the
 * layer overhead, flat over the camera, carried across by the wind and faded toward the horizon.
 */
class CloudFieldTest {

    /** Twice as wide as it is tall, its bottom row hanging from 0.8 of the way down. */
    private static final CloudField.Picture WIDE = new CloudField.Picture(0f, 0f, 0.5f, 0.25f, 2f, 0.8f);
    private static final float DEGREE = (float) Math.toRadians(1.0);

    private static CloudField.Cloud cloud(float azimuthDegrees, float elevationDegrees, float threshold, float drift) {
        return new CloudField.Cloud(azimuthDegrees * DEGREE, elevationDegrees * DEGREE, 20f * DEGREE, 0, threshold,
                drift, false);
    }

    private static CloudField field(CloudField.Cloud... clouds) {
        return new CloudField(List.of(WIDE), List.of(clouds));
    }

    private static CloudField.Look look(float coverage, float wind) {
        return new CloudField.Look(new Vector3f(1f), new Vector3f(1f), coverage, 1f, 0.5f, wind, 1f);
    }

    private static float elevation(Vector3f direction) {
        return (float) Math.toDegrees(Math.asin(direction.y / direction.length()));
    }

    private static float azimuth(Vector3f direction) {
        return (float) Math.toDegrees(Math.atan2(direction.z, direction.x));
    }

    @Test
    void aCloudStandsAtItsDirectionFacingTheCamera() {
        CloudField.Placed placed = field(cloud(30f, 10f, 0f, 1f)).place().getFirst();
        // 20 degrees wide is 10 tall; its base 0.8 of the way down puts its middle 3 degrees over the base.
        assertEquals(13f, elevation(placed.middle()), 0.01f);
        assertEquals(30f, azimuth(placed.middle()), 0.01f);
        assertEquals((float) Math.tan(10f * DEGREE), placed.halfWidth(), 1e-5f);
        assertEquals((float) Math.tan(5f * DEGREE), placed.halfHeight(), 1e-5f);
        // Across and up both lie on the dome there, at right angles; across is level, up leans toward overhead.
        assertEquals(0f, placed.right().dot(placed.middle()), 1e-5f);
        assertEquals(0f, placed.up().dot(placed.middle()), 1e-5f);
        assertEquals(0f, placed.right().y, 1e-6f);
        assertTrue(placed.up().y > 0.9f);
        // Seen from the middle of the dome, across is to the right: forward x up.
        Vector3f seenRight = new Vector3f(placed.middle()).cross(new Vector3f(0f, 1f, 0f)).normalize();
        assertTrue(seenRight.dot(placed.right()) > 0.99f);
    }

    @Test
    void aCloudStraightOverheadStillHasAWayAcrossAndUp() {
        CloudField.Placed placed = field(cloud(70f, 80f, 0f, 1f)).place().getFirst();
        assertFalse(Float.isNaN(placed.up().x) || Float.isNaN(placed.right().x));
        assertEquals(1f, placed.up().length(), 1e-5f);
        // Too near the top of the dome is refused, rather than drawn turned over.
        assertThrows(IllegalArgumentException.class, () -> field(cloud(0f, 85f, 0f, 1f)));
    }

    @Test
    void aCloudShowsOnceTheCoverageIsPastItsThreshold() {
        CloudField field = field(cloud(0f, 10f, 0.2f, 1f), cloud(90f, 10f, 0.6f, 1f));
        field.setLook(look(0.1f, 0f));
        assertTrue(field.place().isEmpty(), "a clear sky");
        field.setLook(look(0.4f, 0f));
        assertEquals(1, field.place().size());
        field.setLook(look(1f, 0f));
        assertEquals(2, field.place().size());
        // Fog that hides them all.
        field.setLook(new CloudField.Look(new Vector3f(1f), new Vector3f(1f), 1f, 0f, 0.5f, 0f, 1f));
        assertTrue(field.place().isEmpty());
    }

    @Test
    void theyAreDrawnInTheOrderGiven() {
        CloudField field = field(cloud(0f, 0f, 0f, 1f), cloud(10f, 40f, 0f, 1f), cloud(20f, 20f, 0f, 1f));
        List<CloudField.Placed> placed = field.place();
        assertEquals(List.of(field.clouds().get(0), field.clouds().get(1), field.clouds().get(2)),
                placed.stream().map(CloudField.Placed::cloud).toList());
    }

    @Test
    void theWindTurnsEachAtItsOwnDriftAndItsWrapMovesNothing() {
        CloudField field = field(cloud(0f, 0f, 0f, 0.125f), cloud(0f, 30f, 0f, 1f));
        field.setLook(look(1f, 8f * DEGREE));
        field.update(1f);
        List<CloudField.Placed> placed = field.place();
        assertEquals(1f, azimuth(placed.get(0).middle()), 0.01f, "the horizon's heap, an eighth as fast");
        assertEquals(8f, azimuth(placed.get(1).middle()), 0.01f);
        assertEquals(placed.get(1).middle().y, field.place().get(1).middle().y, 1e-6f, "its height stays");
        // Long enough for the turn to wrap many times: every cloud is still where the wind alone puts it.
        for (int i = 0; i < 3000; i++) {
            field.update(1f);
        }
        placed = field.place();
        float slow = (float) Math.toDegrees(Math.IEEEremainder(Math.toRadians(3001.0), 2.0 * Math.PI));
        float fast = (float) Math.toDegrees(Math.IEEEremainder(Math.toRadians(8.0 * 3001.0), 2.0 * Math.PI));
        assertEquals(slow, azimuth(placed.get(0).middle()), 0.05f);
        assertEquals(fast, azimuth(placed.get(1).middle()), 0.05f);
    }

    @Test
    void turningTheSkyIsTheWindAtOnce() {
        CloudField field = field(cloud(0f, 20f, 0f, 1f));
        field.turn(25f * DEGREE);
        assertEquals(25f, azimuth(field.place().getFirst().middle()), 0.01f);
    }

    /** A layer overhead with one picture, as wide as it is tall, and these clouds, carried toward +x. */
    private static CloudField layered(CloudField.Overhead... clouds) {
        CloudField.Picture square = new CloudField.Picture(0f, 0f, 0.5f, 0.5f, 1f, 1f);
        return new CloudField(List.of(WIDE), List.of(), new CloudField.Layer(List.of(square), List.of(clouds), 0f, 1f));
    }

    private static CloudField.Overhead overhead(float x, float z, float width, float threshold, float turnDegrees) {
        return new CloudField.Overhead(x, z, width, 0, threshold, turnDegrees * DEGREE, false);
    }

    @Test
    void aCloudOverheadLiesFlatOnItsLayer() {
        CloudField field = layered(overhead(0.5f, -0.25f, 0.6f, 0f, 90f));
        CloudField.Laid laid = field.lay().getFirst();
        Vector3f[] c = laid.corners();
        for (Vector3f corner : c) {
            assertEquals(1f, corner.y, 1e-6f, "on the layer, one unit over the camera");
        }
        // Its middle where it was put, as wide and as deep as its picture, turned a quarter.
        Vector3f middle = new Vector3f(c[0]).add(c[2]).mul(0.5f);
        assertEquals(0.5f, middle.x, 1e-5f);
        assertEquals(-0.25f, middle.z, 1e-5f);
        assertEquals(0.6f, c[0].distance(c[1]), 1e-5f);
        assertEquals(0.6f, c[1].distance(c[2]), 1e-5f);
        assertEquals(0f, c[1].x - c[0].x, 1e-5f, "turned a quarter, its way across runs along z");
        // High in the sky it is all there; nearer the horizon than the layer fades, it is not laid at all.
        for (float alpha : laid.alpha()) {
            assertEquals(1f, alpha, 1e-5f);
        }
        assertTrue(layered(overhead(3.6f, 0f, 0.4f, 0f, 0f)).lay().isEmpty(), "about 16 degrees up: gone");
        CloudField.Laid low = layered(overhead(1.6f, 0f, 0.6f, 0f, 0f)).lay().getFirst();
        assertTrue(low.alpha()[1] < low.alpha()[0], "fading toward the horizon, across itself");
    }

    @Test
    void cloudsOverheadShowByCoverageToo() {
        CloudField field = layered(overhead(0f, 0f, 0.5f, 0.3f, 0f), overhead(1f, 1f, 0.5f, 0.7f, 0f));
        field.setLook(look(0.2f, 0f));
        assertTrue(field.lay().isEmpty());
        field.setLook(look(0.5f, 0f));
        assertEquals(1, field.lay().size());
        field.setLook(look(1f, 0f));
        assertEquals(2, field.lay().size());
    }

    @Test
    void theWindCarriesTheLayerOneWayAndItsWrapMovesNothingInSight() {
        CloudField field = layered(overhead(0f, 0f, 0.5f, 0f, 0f));
        field.setLook(look(1f, 0.1f));
        field.update(2f);
        Vector3f[] c = field.lay().getFirst().corners();
        assertEquals(0.2f, (c[0].x + c[2].x) * 0.5f, 1e-5f, "carried 0.1 a second toward +x");
        assertEquals(0f, (c[0].z + c[2].z) * 0.5f, 1e-5f);
        // Once round the layer's square, it is back where it started; on the way it wraps far out of sight.
        float lap = 2f * CloudField.LAYER_REACH;
        for (int i = 0; i < 100; i++) {
            field.update((lap / 0.1f) / 100f);
        }
        c = field.lay().getFirst().corners();
        assertEquals(0.2f, (c[0].x + c[2].x) * 0.5f, 1e-3f);
        assertEquals(1f, (float) CloudField.wrap(1f + 3f * lap), 1e-4f);
        assertEquals(-CloudField.LAYER_REACH, (float) CloudField.wrap(CloudField.LAYER_REACH), 1e-5f,
                "the square runs from one edge up to the other");
        float seen = (float) Math.toRadians(CloudField.LAYER_FADE_LOW_DEGREES);
        assertTrue(Math.atan2(1.0, CloudField.LAYER_REACH - 1.1) < seen, "a cloud wraps only once out of sight");
    }

    @Test
    void driftsThatWouldJumpAndMissingPicturesAreRefused() {
        assertTrue(CloudField.steadyDrift(1f));
        assertTrue(CloudField.steadyDrift(1.25f));
        assertTrue(CloudField.steadyDrift(0.125f));
        assertFalse(CloudField.steadyDrift(1.3f));
        assertThrows(IllegalArgumentException.class, () -> field(cloud(0f, 0f, 0f, 1.3f)));
        assertThrows(IllegalArgumentException.class, () -> new CloudField(List.of(WIDE),
                List.of(new CloudField.Cloud(0f, 0f, 0.1f, 1, 0f, 1f, false))));
        assertThrows(IllegalArgumentException.class, () -> layered(overhead(6f, 0f, 0.5f, 0f, 0f)), "off its layer");
        assertThrows(IllegalArgumentException.class, () -> layered(new CloudField.Overhead(0f, 0f, 0.5f, 1, 0f, 0f,
                false)), "no such picture");
    }
}
