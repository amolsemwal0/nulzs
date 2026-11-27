package org.zeronura.engine.nodes.geometry;

/**
 * What a geometry tree can ask about the world it runs in: the scene time, and the object it belongs to.
 *
 * <p>The game decides these. Time defaults to frame 1 at 24 fps, Blender's start, so a tree that animates comes
 * out as it does on Blender's first frame until the game says otherwise.
 */
public record EvalEnv(float seconds, float frame, String selfName, float[] selfMatrix, boolean viewport) {

    public static final EvalEnv DEFAULT = new EvalEnv(1f / 24f, 1f, "", Transforms.identity(), false);

    static final ThreadLocal<EvalEnv> CURRENT = ThreadLocal.withInitial(() -> DEFAULT);

    public EvalEnv withSelf(String name, float[] matrix) {
        return new EvalEnv(seconds, frame, name, matrix, viewport);
    }

    public EvalEnv atTime(float seconds, float fps) {
        return new EvalEnv(seconds, seconds * fps, selfName, selfMatrix, viewport);
    }
}
