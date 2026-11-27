package org.zeronura.nulzs.debug;

import static org.lwjgl.opengl.GL33.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL33.GL_RENDERER;
import static org.lwjgl.opengl.GL33.GL_TIMESTAMP;
import static org.lwjgl.opengl.GL33.GL_VERSION;
import static org.lwjgl.opengl.GL33.glGenQueries;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;
import static org.lwjgl.opengl.GL33.glGetString;
import static org.lwjgl.opengl.GL33.glQueryCounter;

/**
 * How long the graphics card spends on each part of a frame: a timestamp query where a part begins and another where it
 * ends, read back {@link #LAG} frames later, by when the card has long finished them, so reading never makes the game
 * wait for it.
 *
 * <p>The difference between two timestamps is when the card reached the one and then the other. On a frame the card
 * is the limit, that is the work between them; on a frame the CPU is the limit, the card is waiting for commands and
 * the difference follows how fast they came. {@link DebugReport} reads them that way.
 *
 * <p>Desktop OpenGL 3.3. Android has its own of this class, which measures nothing.
 */
final class GpuTimer {

    /** Frames between asking and reading: more than a driver keeps queued. */
    private static final int LAG = 5;

    private final int[][] queries = new int[LAG][];
    private final int[] issued = new int[LAG];
    private final int[][] spans = new int[LAG][];
    private final int[] spanCount = new int[LAG];
    private int slot;
    private boolean working = true;

    GpuTimer() {
        for (int i = 0; i < LAG; i++) {
            queries[i] = new int[0];
            spans[i] = new int[0];
        }
    }

    /** The graphics card and its OpenGL version, for the report. */
    String describe() {
        return glGetString(GL_RENDERER) + ", OpenGL " + glGetString(GL_VERSION);
    }

    boolean working() {
        return working;
    }

    /**
     * Starts a frame in the slot used {@link #LAG} frames ago, first adding what that frame's queries measured to
     * {@code gpuNanos} (by scope).
     */
    void beginFrame(long[] gpuNanos) {
        if (!working) {
            return;
        }
        slot = (slot + 1) % LAG;
        int[] list = spans[slot];
        int[] ids = queries[slot];
        try {
            for (int i = 0, n = spanCount[slot]; i < n; i++) {
                int scope = list[i * 3];
                long begin = glGetQueryObjecti64(ids[list[i * 3 + 1]], GL_QUERY_RESULT);
                long end = glGetQueryObjecti64(ids[list[i * 3 + 2]], GL_QUERY_RESULT);
                if (end > begin) {
                    gpuNanos[scope] += end - begin;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            working = false;
            System.err.println("[Debug] GPU timing is off: " + e);
        }
        issued[slot] = 0;
        spanCount[slot] = 0;
    }

    /** A timestamp where the card reaches this point; its number, for {@link #span}. -1 if the card can't time. */
    int mark() {
        if (!working) {
            return -1;
        }
        int[] ids = queries[slot];
        int n = issued[slot];
        if (n == ids.length) {
            int[] grown = java.util.Arrays.copyOf(ids, Math.max(64, ids.length * 2));
            try {
                int[] made = new int[grown.length - ids.length];
                glGenQueries(made);
                System.arraycopy(made, 0, grown, ids.length, made.length);
            } catch (RuntimeException | LinkageError e) {
                working = false;
                System.err.println("[Debug] GPU timing is off: " + e);
                return -1;
            }
            queries[slot] = grown;
            ids = grown;
        }
        glQueryCounter(ids[n], GL_TIMESTAMP);
        issued[slot] = n + 1;
        return n;
    }

    /** The card's time from mark {@code begin} to mark {@code end} counts to {@code scope}. */
    void span(int scope, int begin, int end) {
        if (!working || begin < 0 || end < 0) {
            return;
        }
        int[] list = spans[slot];
        int n = spanCount[slot];
        if ((n + 1) * 3 > list.length) {
            list = java.util.Arrays.copyOf(list, Math.max(96, list.length * 2));
            spans[slot] = list;
        }
        list[n * 3] = scope;
        list[n * 3 + 1] = begin;
        list[n * 3 + 2] = end;
        spanCount[slot] = n + 1;
    }
}
