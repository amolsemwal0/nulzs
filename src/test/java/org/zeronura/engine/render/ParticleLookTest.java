package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a look adds to a thrown particle, and that a plain one is left exactly as it was. */
class ParticleLookTest {

    private static final float LIFE = 10f;
    private static final float NEVER = Float.NEGATIVE_INFINITY;

    private static ParticleEffect resting(ParticleLook look) {
        return new ParticleEffect(1, new Vector3f(0f, 1f, 0f), 0f, 0f, 0f, 0f, 1f, LIFE, LIFE, 0.5f, 0.5f,
                new Vector4f(1f), new Vector4f(1f), BlendMode.ALPHA, 0f, look);
    }

    private static ParticleEffect falling(ParticleLook look) {
        return new ParticleEffect(1, new Vector3f(0f, 1f, 0f), 0f, 0f, 0f, -10f, 1f, LIFE, LIFE, 0.5f, 0.5f,
                new Vector4f(1f), new Vector4f(1f), BlendMode.ALPHA, 0f, look);
    }

    private static void step(ParticlePool pool, float seconds) {
        for (float t = 0f; t < seconds; t += 0.05f) {
            pool.update(0.05);
        }
    }

    private static FloatBuffer quad(ParticlePool pool) {
        FloatBuffer out = BufferUtils.createFloatBuffer(pool.maxVertices() * ParticlePool.FLOATS_PER_VERTEX);
        pool.buildBillboards(BlendMode.ALPHA, new Vector3f(1f, 0f, 0f), new Vector3f(0f, 1f, 0f), out);
        return out;
    }

    private static float alpha(FloatBuffer vertices) {
        return vertices.get(8);
    }

    @Test
    void anEffectWithoutALookIsAPlainDot() {
        ParticleEffect effect = new ParticleEffect(1, new Vector3f(), 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 1f, new Vector4f(1f),
                new Vector4f(1f), BlendMode.ALPHA, 0f);
        assertEquals(ParticleLook.PLAIN, effect.look());
        assertEquals(ParticleShape.DOT, effect.look().shape());
    }

    @Test
    void windCarriesOnlyWhatALookLetsItAndByItsShare() {
        ParticlePool pool = new ParticlePool(4, 1L);
        pool.setWind(4f, 0f, 0f);
        pool.emit(resting(ParticleLook.PLAIN), new Vector3f());
        pool.emit(resting(ParticleLook.of(ParticleShape.DOT).wind(1f)), new Vector3f());
        pool.emit(resting(ParticleLook.of(ParticleShape.DOT).wind(0.5f)), new Vector3f());
        step(pool, 1f);
        assertEquals(0f, pool.positionOf(0).x, 1e-4f, "a plain particle ignores the wind");
        assertEquals(4f, pool.positionOf(1).x, 0.25f);
        assertEquals(2f, pool.positionOf(2).x, 0.15f);
    }

    @Test
    void aParticleLandsOnItsFloorAndStays() {
        ParticlePool pool = new ParticlePool(2, 1L);
        pool.setWind(3f, 0f, 0f);
        pool.emit(falling(ParticleLook.of(ParticleShape.LEAF).wind(1f)), new Vector3f(0f, 1f, 0f), 0f);
        step(pool, 2f);
        Vector3f landed = pool.positionOf(0);
        assertNotNull(landed);
        assertEquals(0f, landed.y, 0.1f, "it should come to rest on the floor");
        step(pool, 2f);
        assertEquals(landed.x, pool.positionOf(0).x, 1e-4f, "a leaf on the ground stays put in the wind");
    }

    @Test
    void withoutAFloorItFallsForever() {
        ParticlePool pool = new ParticlePool(2, 1L);
        pool.emit(falling(ParticleLook.PLAIN), new Vector3f(), NEVER);
        step(pool, 2f);
        assertTrue(pool.positionOf(0).y < -5f);
    }

    @Test
    void aLandedParticleLiesFlat() {
        ParticlePool pool = new ParticlePool(2, 1L);
        pool.emit(falling(ParticleLook.of(ParticleShape.LEAF)), new Vector3f(0f, 1f, 0f), 0f);
        step(pool, 2f);
        FloatBuffer vertices = quad(pool);
        float y0 = vertices.get(1);
        for (int vertex = 0; vertex < 6; vertex++) {
            assertEquals(y0, vertices.get(vertex * ParticlePool.FLOATS_PER_VERTEX + 1), 1e-4f, "its corners are level");
        }
    }

    @Test
    void aFlatLookLiesLevelFromTheStart() {
        ParticlePool pool = new ParticlePool(2, 1L);
        pool.emit(resting(ParticleLook.of(ParticleShape.RING).laidFlat()), new Vector3f(1f, 2f, 3f));
        FloatBuffer vertices = quad(pool);
        for (int vertex = 0; vertex < 6; vertex++) {
            assertEquals(2f, vertices.get(vertex * ParticlePool.FLOATS_PER_VERTEX + 1), 1e-4f);
        }
    }

    @Test
    void itFadesInAndOutOverTheSharesOfItsLifeAskedFor() {
        ParticlePool pool = new ParticlePool(2, 1L);
        pool.emit(resting(ParticleLook.of(ParticleShape.DOT).fades(0.2f, 0.2f)), new Vector3f());
        assertEquals(0f, alpha(quad(pool)), 1e-3f, "invisible as it is born");
        step(pool, 5f);
        assertEquals(1f, alpha(quad(pool)), 1e-3f, "whole in the middle of its life");
        step(pool, 4f);
        assertEquals(0.5f, alpha(quad(pool)), 0.08f, "halfway through going");
    }

    @Test
    void itsCornersUseItsOwnCellOfTheTexture() {
        ParticlePool pool = new ParticlePool(2, 1L);
        pool.emit(resting(ParticleLook.of(ParticleShape.LEAF)), new Vector3f());
        FloatBuffer vertices = quad(pool);
        assertEquals(ParticleAtlas.u0(ParticleShape.LEAF), vertices.get(3), 1e-5f);
        assertEquals(ParticleAtlas.v0(ParticleShape.LEAF), vertices.get(4), 1e-5f);
    }

    @Test
    void flappingNarrowsAQuadWithoutClosingIt() {
        ParticlePool pool = new ParticlePool(2, 1L);
        pool.emit(resting(ParticleLook.of(ParticleShape.BUTTERFLY).flap(2f)), new Vector3f());
        float narrowest = Float.MAX_VALUE;
        float widest = 0f;
        for (int i = 0; i < 200; i++) {
            pool.update(0.01);
            FloatBuffer vertices = quad(pool);
            float width = vertices.get(ParticlePool.FLOATS_PER_VERTEX) - vertices.get(0);
            narrowest = Math.min(narrowest, width);
            widest = Math.max(widest, width);
        }
        assertTrue(widest > 0.9f, "open, it is the full width: " + widest);
        assertTrue(narrowest < 0.5f, "it closes up: " + narrowest);
        assertTrue(narrowest > 0.2f, "never to nothing: " + narrowest);
    }
}
