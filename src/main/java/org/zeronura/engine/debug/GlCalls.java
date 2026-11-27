package org.zeronura.engine.debug;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every OpenGL call a game makes, counted and timed where it is made: by the function and the method that called it.
 *
 * <p>Nothing calls this by hand. In debug mode the game starts with {@link GlCallAgent}, which rewrites each class as
 * it loads so that every call into {@code org.lwjgl.opengl} is wrapped in {@link #enter} and {@link #exit}. A call
 * site is registered once, when its class loads; after that a call costs two clock reads and two array writes.
 *
 * <p>The time is the CPU's: how long the call took to return, which is the driver's work of taking the command, not
 * the graphics card's of carrying it out (that is {@link GpuTimer}'s). A call that stalls — a read back, a buffer
 * still in use — shows up here as a long one.
 *
 * <p>Counted on the thread that draws. OpenGL is only ever called on the thread that owns its context, so the counters
 * are plain fields; {@link DebugMode} reads and clears them on that same thread.
 */
public final class GlCalls {

    /** More call sites than this share one row, the first: a game has a few thousand at most. */
    static final int MAX_SITES = 16384;

    private static final String[] functions = new String[MAX_SITES];
    private static final String[] callers = new String[MAX_SITES];
    private static final Map<String, Integer> ids = new HashMap<>();
    private static volatile int sites = 1;
    private static volatile boolean traced;

    /** Classes whose calls are wrapped, and classes that call OpenGL but could not be rewritten (so go uncounted). */
    static final AtomicInteger rewrittenClasses = new AtomicInteger();
    static final AtomicInteger skippedClasses = new AtomicInteger();

    static final long[] counts = new long[MAX_SITES];
    static final long[] nanos = new long[MAX_SITES];
    private static final boolean[] draws = new boolean[MAX_SITES];

    /** The calls, draws and their time by the frame part they were made in ({@link DebugMode}'s scope numbers). */
    static final int SCOPES = 1024;
    static final long[] scopeCalls = new long[SCOPES];
    static final long[] scopeDraws = new long[SCOPES];
    static final long[] scopeNanos = new long[SCOPES];
    /** The frame part open now; {@link DebugMode} keeps it. */
    static int scope;

    private static int current;
    private static long start;

    static {
        functions[0] = "(other)";
        callers[0] = "(more call sites than are counted apart)";
    }

    private GlCalls() {
    }

    /** Called just before an OpenGL function, with its call site. */
    public static void enter(int site) {
        current = site;
        start = System.nanoTime();
    }

    /** Called just after it returns. */
    public static void exit() {
        long took = System.nanoTime() - start;
        int site = current;
        counts[site]++;
        nanos[site] += took;
        int part = scope;
        scopeCalls[part]++;
        scopeNanos[part] += took;
        if (draws[site]) {
            scopeDraws[part]++;
        }
    }

    /** Whether {@code function} draws: what a draw call is counted as. glDrawBuffers only picks where drawing goes. */
    static boolean isDraw(String function) {
        return (function.startsWith("glDraw") && !function.startsWith("glDrawBuffer")) || function.startsWith("glMultiDraw");
    }

    /** Whether the calls are being traced: the game was started with the agent. */
    public static boolean traced() {
        return traced;
    }

    static void startTracing() {
        traced = true;
    }

    /** The row for {@code function} called from {@code caller} ("render.GrassRenderer.draw"), made the first time. */
    static synchronized int register(String function, String caller) {
        String key = function + '\n' + caller;
        Integer id = ids.get(key);
        if (id != null) {
            return id;
        }
        int next = sites;
        if (next >= MAX_SITES) {
            return 0;
        }
        functions[next] = function;
        callers[next] = caller;
        draws[next] = isDraw(function);
        ids.put(key, next);
        sites = next + 1;
        return next;
    }

    static int sites() {
        return sites;
    }

    static String function(int site) {
        return functions[site];
    }

    static String caller(int site) {
        return callers[site];
    }

    /** Zeroes the counters; on the drawing thread, between frames. */
    static void clear() {
        int n = sites;
        for (int i = 0; i < n; i++) {
            counts[i] = 0;
            nanos[i] = 0;
        }
        java.util.Arrays.fill(scopeCalls, 0);
        java.util.Arrays.fill(scopeDraws, 0);
        java.util.Arrays.fill(scopeNanos, 0);
    }
}
