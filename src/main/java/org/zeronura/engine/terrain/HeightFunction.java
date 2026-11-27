package org.zeronura.engine.terrain;

/**
 * The shape of a landscape: ground height at every (x, z). Terrain is built entirely from one of these,
 * so any function — noise, a heightmap image, a formula, or a blend of them — is a valid world.
 *
 * <p>Called from terrain worker threads, so implementations must be thread-safe (pure functions are).
 */
@FunctionalInterface
public interface HeightFunction {

    float heightAt(float x, float z);

    static HeightFunction flat(float height) {
        return (x, z) -> height;
    }
}
