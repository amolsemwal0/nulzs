package org.zeronura.engine.render;

/**
 * How a particle's colour combines with what is already on screen.
 *
 * <p>The two modes are drawn in separate passes because they need different {@code glBlendFunc} state:
 * {@link #ALPHA} is ordinary over-blending for anything that occludes (dust, smoke, splash), while
 * {@link #ADDITIVE} accumulates light for anything that glows (sparks, fireflies) — additive particles
 * get brighter where they overlap and never darken the scene, which is what reads as emission.
 */
public enum BlendMode {
    ALPHA,
    ADDITIVE
}
