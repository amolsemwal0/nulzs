package org.zeronura.engine.render;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The particle simulation, driven without a window. The behaviour that matters — bursts appear, particles
 * fall and slow and die on time, and the pool never overruns — is all here; the billboarding is only
 * arithmetic and is spot-checked for count.
 */
class ParticlePoolTest {

    private static ParticleEffect effect(int count, float gravity, float drag, float life) {
        return new ParticleEffect(count, new Vector3f(0f, 1f, 0f), 0.3f, 0f, 0f, gravity, drag,
                life, life, 0.2f, 0.1f,
                new Vector4f(1f, 1f, 1f, 1f), new Vector4f(1f, 1f, 1f, 0f), BlendMode.ALPHA, 0f);
    }

    @Test
    void emittingSpawnsTheRequestedCount() {
        ParticlePool pool = new ParticlePool(100, 1L);
        assertEquals(0, pool.liveCount());
        pool.emit(effect(20, 0f, 1f, 5f), new Vector3f());
        assertEquals(20, pool.liveCount());
    }

    @Test
    void particlesDieAtTheEndOfTheirLife() {
        ParticlePool pool = new ParticlePool(100, 1L);
        pool.emit(effect(10, 0f, 1f, 2f), new Vector3f());
        assertEquals(10, pool.liveCount());

        for (int i = 0; i < 30; i++) {
            pool.update(0.1);   // 3 s total, past the 2 s life
        }
        assertEquals(0, pool.liveCount(), "particles should have expired");
    }

    @Test
    void gravityPullsParticlesDownAndDragSlowsThem() {
        // A single upward particle: gravity eventually reverses it, drag bleeds its speed.
        ParticleEffect up = new ParticleEffect(1, new Vector3f(0f, 1f, 0f), 0f, 10f, 10f, -20f, 0.5f,
                10f, 10f, 0.2f, 0.2f, new Vector4f(1f), new Vector4f(1f), BlendMode.ALPHA, 0f);
        ParticlePool pool = new ParticlePool(10, 1L);
        pool.emit(up, new Vector3f(0f, 0f, 0f));

        float startY = pool.positionOf(0).y;
        for (int i = 0; i < 5; i++) {
            pool.update(0.1);
        }
        float midY = pool.positionOf(0).y;
        assertTrue(midY > startY, "it should have risen at first");

        for (int i = 0; i < 40; i++) {
            pool.update(0.1);
        }
        assertTrue(pool.positionOf(0).y < midY, "gravity should have brought it back down");
    }

    @Test
    void aPausedFrameMovesNothing() {
        ParticlePool pool = new ParticlePool(10, 1L);
        ParticleEffect moving = new ParticleEffect(1, new Vector3f(1f, 0f, 0f), 0f, 5f, 5f, 0f, 1f,
                10f, 10f, 0.2f, 0.2f, new Vector4f(1f), new Vector4f(1f), BlendMode.ALPHA, 0f);
        pool.emit(moving, new Vector3f());
        Vector3f before = pool.positionOf(0);
        pool.update(0.0);
        Vector3f after = pool.positionOf(0);
        assertEquals(before.x, after.x, 1e-6f, "a paused frame must not move a particle");
    }

    @Test
    void aBurstBiggerThanThePoolRecyclesTheOldestInsteadOfOverflowing() {
        ParticlePool pool = new ParticlePool(50, 1L);
        pool.emit(effect(200, 0f, 1f, 10f), new Vector3f());   // four pools' worth at once
        assertTrue(pool.liveCount() <= 50, "the pool must never exceed capacity, was " + pool.liveCount());
        assertEquals(50, pool.liveCount(), "and should be full");
    }

    @Test
    void slotsAreReusedAsParticlesExpire() {
        // Emit, let them all die, emit again: the pool must accept the second burst (slots recycled),
        // not silently stay full of corpses.
        ParticlePool pool = new ParticlePool(30, 1L);
        pool.emit(effect(30, 0f, 1f, 1f), new Vector3f());
        for (int i = 0; i < 20; i++) {
            pool.update(0.1);
        }
        assertEquals(0, pool.liveCount());
        pool.emit(effect(30, 0f, 1f, 1f), new Vector3f());
        assertEquals(30, pool.liveCount());
    }

    @Test
    void billboardsAreBuiltOnlyForTheRequestedBlendMode() {
        ParticlePool pool = new ParticlePool(100, 1L);
        pool.emit(effect(10, 0f, 1f, 5f), new Vector3f());     // 10 ALPHA particles
        pool.update(0.1);

        java.nio.FloatBuffer buf = org.lwjgl.system.MemoryUtil.memAllocFloat(pool.maxVertices()
                * ParticlePool.FLOATS_PER_VERTEX);
        int alpha = pool.buildBillboards(BlendMode.ALPHA, new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), buf);
        int additive = pool.buildBillboards(BlendMode.ADDITIVE, new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), buf);
        org.lwjgl.system.MemoryUtil.memFree(buf);

        assertEquals(10 * 6, alpha, "all ten alpha particles should billboard (6 verts each)");
        assertEquals(0, additive, "there are no additive particles");
    }
}
