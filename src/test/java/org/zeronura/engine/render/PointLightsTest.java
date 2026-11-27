package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PointLightsTest {

    private static final Vector3f WARM = new Vector3f(1f, 0.8f, 0.5f);

    /** A light every {@code spacing} along x from the origin. */
    private static PointLights row(int count, float spacing) {
        List<PointLight> lights = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lights.add(new PointLight(new Vector3f(i * spacing, 3f, 0f), WARM, 9f));
        }
        PointLights all = new PointLights();
        all.set(lights);
        return all;
    }

    @Test
    void aFewLightsAreAllGivenAtFullStrength() {
        PointLights.Frame frame = row(3, 10f).nearest(new Vector3f(), new PointLights.Frame());
        assertEquals(3, frame.count());
        for (int i = 0; i < 3; i++) {
            assertEquals(1f, frame.color(i, 0), 1e-6f, "light " + i);
            assertEquals(0.5f, frame.color(i, 2), 1e-6f, "light " + i);
            assertEquals(9f, frame.radius(i));
        }
    }

    @Test
    void ofManyOnlyTheNearestAreGivenAndTheFurthestOfThemFadeOut() {
        // Forty lights a metre apart along x, the focus at the 20th: the 16 nearest are those 8 either side.
        PointLights.Frame frame = row(40, 1f).nearest(new Vector3f(20.2f, 3f, 0f), new PointLights.Frame());
        assertTrue(frame.count() <= PointLights.MAX);
        Vector3f at = new Vector3f();
        for (int i = 0; i < frame.count(); i++) {
            frame.position(i, at);
            assertTrue(Math.abs(at.x - 20.2f) < 8.5f, "light at " + at.x + " is not among the nearest");
        }
        // The nearest left out is 8.2 m away (at 12); one at 7.8 m is 0.4 m nearer, so all but out.
        for (int i = 0; i < frame.count(); i++) {
            float distance = Math.abs(frame.position(i, at).x - 20.2f);
            float expected = Math.clamp((8.2f - distance) / PointLights.FADE, 0f, 1f);
            assertEquals(expected, frame.color(i, 0), 1e-4f, "light " + at.x);
        }
    }

    @Test
    void aLightChangingPlacesIsDarkAsItDoes() {
        // Walking along the row, the strength each light is given never jumps: it comes in and goes out at 0.
        PointLights lights = row(40, 1f);
        PointLights.Frame frame = new PointLights.Frame();
        float[] last = null;
        Vector3f at = new Vector3f();
        for (float x = 10f; x < 30f; x += 0.05f) {
            float[] now = new float[40];
            lights.nearest(new Vector3f(x, 3f, 0f), frame);
            for (int i = 0; i < frame.count(); i++) {
                now[Math.round(frame.position(i, at).x)] = frame.color(i, 0);
            }
            for (int i = 0; last != null && i < 40; i++) {
                assertEquals(last[i], now[i], 0.02f, "light " + i + " at x " + x);
            }
            last = now;
        }
    }

    @Test
    void brightnessScalesThemAllAndNoneAreGivenWhenTheyAreOut() {
        PointLights lights = row(3, 10f);
        lights.setBrightness(0.25f);
        PointLights.Frame frame = lights.nearest(new Vector3f(), new PointLights.Frame());
        assertEquals(0.25f, frame.color(0, 0), 1e-6f);
        lights.setBrightness(0f);
        assertEquals(0, lights.nearest(new Vector3f(), frame).count(), "out by day: the shaders loop over nothing");
        assertEquals(0, new PointLights().nearest(new Vector3f(), frame).count(), "no lights at all");
    }

    @Test
    void aLightNeedsARadius() {
        assertThrows(IllegalArgumentException.class, () -> new PointLight(new Vector3f(), WARM, 0f));
    }
}
