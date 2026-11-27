package org.zeronura.engine.debug;

import org.zeronura.engine.core.Engine;
import org.zeronura.engine.core.UserSettings;
import org.zeronura.engine.data.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The engine's debug mode: where a running game's frame time goes, on the CPU and on the graphics card.
 *
 * <ul>
 *   <li><b>Frame parts</b> — the engine marks the parts of each frame ({@link #begin}/{@link #end}: input, update,
 *   render and inside it the shadow map, terrain, each render pass, the screen effects, then the swap and the frame
 *   cap) and each is timed twice: by the CPU's clock, and by the graphics card's ({@link GpuTimer}).</li>
 *   <li><b>OpenGL calls</b> — every one the game makes, by function and the method it was called from, with how long
 *   it took to return ({@link GlCalls}, filled in by {@link GlCallAgent} when the game was started with it).</li>
 *   <li><b>CPU samples</b> — which of the game's methods the drawing thread is running, sampled ({@link CpuSampler}),
 *   with what it allocates and the garbage collector's pauses.</li>
 * </ul>
 *
 * <p>On with {@code -Dengine.debug=true}, as the editor's Debug menu starts a game. Every half second the readings,
 * averaged a frame, go to the editor ({@code -Dengine.debugPort}, a port on this computer it listens on) as a line
 * of JSON; with {@code -Dengine.debugReport=file.txt} the last of them is also written there as text when the game
 * closes ({@link DebugReport}). Off, {@link #begin} and {@link #end} are a field read and a return.
 *
 * <p>It costs a little itself: each frame part two clock reads and two GPU timestamps, each OpenGL call two clock
 * reads. The report says about how much the call tracing adds.
 */
public final class DebugMode {

    public static final String PROPERTY = "engine.debug";
    public static final String PORT_PROPERTY = "engine.debugPort";
    public static final String REPORT_PROPERTY = "engine.debugReport";

    /** How often the readings are averaged and sent. */
    private static final long WINDOW_NANOS = 500_000_000L;
    private static final int MAX_SCOPES = GlCalls.SCOPES;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_FRAMES = 512;

    /** The parts of the frame the engine's loop marks: the swap and the cap are waits, not work. */
    public static final String SWAP = "Swap buffers";
    public static final String CAP = "Frame-rate cap";

    private static boolean on;
    private static DebugMode active;

    private final Engine engine;
    private final GpuTimer gpu;
    private final CpuSampler cpu;
    private final Link link;
    private final Path reportFile;
    private final double clockNanos;

    private final String[] names = new String[MAX_SCOPES];
    private final int[] parents = new int[MAX_SCOPES];
    private final int[] firstChild = new int[MAX_SCOPES];
    private final int[] nextSibling = new int[MAX_SCOPES];
    private final long[] cpuNanos = new long[MAX_SCOPES];
    private final long[] gpuNanos = new long[MAX_SCOPES];
    private final long[] entered = new long[MAX_SCOPES];
    private int scopes = 1;

    private final int[] stack = new int[MAX_DEPTH];
    private final long[] stackStart = new long[MAX_DEPTH];
    private final int[] stackMark = new int[MAX_DEPTH];
    private int depth;
    /** Parts opened past {@link #MAX_DEPTH}, which are not timed. */
    private int overflow;
    private boolean inFrame;

    private long windowStart;
    private int frames;
    private final float[] frameMillis = new float[MAX_FRAMES];
    private Map<String, Object> last;

    private DebugMode(Engine engine) {
        this.engine = engine;
        names[0] = "Frame";
        parents[0] = -1;
        java.util.Arrays.fill(firstChild, -1);
        java.util.Arrays.fill(nextSibling, -1);
        this.gpu = new GpuTimer();
        this.cpu = new CpuSampler(Thread.currentThread());
        Integer port = Integer.getInteger(PORT_PROPERTY);
        this.link = port == null ? null : new Link(port);
        String report = System.getProperty(REPORT_PROPERTY);
        this.reportFile = report == null || report.isBlank() ? null : Path.of(report);
        this.clockNanos = clockCost();
        this.windowStart = System.nanoTime();
    }

    /** Turns debug mode on if the game was started with it. With the window's OpenGL context current. */
    public static void start(Engine engine) {
        if (!Boolean.getBoolean(PROPERTY) || active != null) {
            return;
        }
        active = new DebugMode(engine);
        on = true;
        System.out.println("[Debug] Debug mode on: frame parts timed on the CPU and "
                + (active.gpu.working() ? "the GPU" : "not the GPU") + ", OpenGL calls "
                + (GlCalls.traced() ? "traced" : "not traced (start with the agent)") + ", CPU "
                + (active.cpu.working() ? "sampled" : "not sampled"));
    }

    public static boolean on() {
        return on;
    }

    /** Opens a part of the frame, inside whichever is open. */
    public static void begin(String name) {
        if (on) {
            active.open(name);
        }
    }

    /** Opens a part named for what does it: a render pass by its class. */
    public static void begin(Object owner) {
        if (on) {
            Class<?> type = owner.getClass();
            String name = type.getSimpleName();
            active.open(name.isEmpty() ? shortName(type.getName()) : name);
        }
    }

    /** Closes the part opened last. */
    public static void end() {
        if (on) {
            active.close();
        }
    }

    /** At the top of the engine's loop. */
    public static void frameStart() {
        if (on) {
            active.beginFrame();
        }
    }

    /** At the bottom of the engine's loop, after the swap and the frame cap. */
    public static void frameEnd() {
        if (on) {
            active.endFrame();
        }
    }

    /** As the engine closes: the report, if one was asked for. */
    public static void stop() {
        if (active == null) {
            return;
        }
        on = false;
        active.finish();
        active = null;
    }

    /** "org.zeronura.engine.render.Renderer" as "engine.render.Renderer": the engine's and games' own, shorter. */
    public static String shortName(String className) {
        return className.startsWith("org.zeronura.") ? className.substring("org.zeronura.".length()) : className;
    }

    // --- Frame parts --------------------------------------------------------------------------------------------

    private void beginFrame() {
        if (inFrame) {
            endFrame();
        }
        gpu.beginFrame(gpuNanos);
        inFrame = true;
        overflow = 0;
        stack[0] = 0;
        stackStart[0] = System.nanoTime();
        stackMark[0] = gpu.mark();
        depth = 1;
        GlCalls.scope = 0;
    }

    private void open(String name) {
        if (!inFrame) {
            return;
        }
        if (depth >= MAX_DEPTH) {
            overflow++;
            return;
        }
        int parent = stack[depth - 1];
        stack[depth] = parent < 0 ? -1 : child(parent, name);
        stackMark[depth] = stack[depth] < 0 ? -1 : gpu.mark();
        stackStart[depth] = System.nanoTime();
        if (stack[depth] >= 0) {
            GlCalls.scope = stack[depth];
        }
        depth++;
    }

    private void close() {
        if (!inFrame) {
            return;
        }
        if (overflow > 0) {
            overflow--;
            return;
        }
        if (depth <= 1) {
            return; // an end with no begin: the frame itself is closed by endFrame
        }
        depth--;
        int scope = stack[depth];
        GlCalls.scope = Math.max(0, stack[depth - 1]);
        if (scope < 0) {
            return; // past MAX_SCOPES
        }
        cpuNanos[scope] += System.nanoTime() - stackStart[depth];
        entered[scope]++;
        gpu.span(scope, stackMark[depth], gpu.mark());
    }

    private int child(int parent, String name) {
        int last = -1;
        for (int c = firstChild[parent]; c >= 0; c = nextSibling[c]) {
            if (names[c] == name || names[c].equals(name)) {
                return c;
            }
            last = c;
        }
        if (scopes >= MAX_SCOPES) {
            return -1;
        }
        int made = scopes++;
        names[made] = name;
        parents[made] = parent;
        if (last < 0) {
            firstChild[parent] = made;
        } else {
            nextSibling[last] = made;
        }
        return made;
    }

    private void endFrame() {
        if (!inFrame) {
            return;
        }
        // A part a throw left open is closed with the frame, so one bad frame doesn't skew the rest.
        overflow = 0;
        while (depth > 1) {
            close();
        }
        long now = System.nanoTime();
        long frame = now - stackStart[0];
        cpuNanos[0] += frame;
        entered[0]++;
        gpu.span(0, stackMark[0], gpu.mark());
        depth = 0;
        inFrame = false;
        if (frames < MAX_FRAMES) {
            frameMillis[frames] = frame / 1e6f;
        }
        frames++;
        if (now - windowStart >= WINDOW_NANOS) {
            publish(now);
        }
    }

    // --- Readings -----------------------------------------------------------------------------------------------

    private void publish(long now) {
        double seconds = (now - windowStart) / 1e9;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("info", info());
        out.put("frames", frames);
        out.put("seconds", round(seconds));
        out.put("fps", round(frames / seconds));
        double sum = 0;
        double worst = 0;
        List<Object> times = new ArrayList<>();
        for (int i = 0; i < Math.min(frames, MAX_FRAMES); i++) {
            sum += frameMillis[i];
            worst = Math.max(worst, frameMillis[i]);
            times.add(round(frameMillis[i]));
        }
        out.put("frameMs", round(sum / Math.max(1, Math.min(frames, MAX_FRAMES))));
        out.put("worstMs", round(worst));
        out.put("frameTimes", times);
        out.put("scopes", scopeRows());
        out.put("gl", glReadings());
        Map<String, Object> sampled = cpu.snapshot();
        out.put("cpu", sampled);
        long[] memory = cpu.memory();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("allocatedKbPerFrame", memory[0] < 0 ? -1 : round(memory[0] / 1024.0 / Math.max(1, frames)));
        mem.put("gcCount", memory[1]);
        mem.put("gcMs", memory[2]);
        mem.put("heapMb", round(memory[3] / 1048576.0));
        mem.put("heapMaxMb", round(memory[4] / 1048576.0));
        out.put("memory", mem);
        last = out;
        if (link != null) {
            link.send(Json.compact(out));
        }
        for (int i = 0; i < scopes; i++) {
            cpuNanos[i] = 0;
            gpuNanos[i] = 0;
            entered[i] = 0;
        }
        GlCalls.clear();
        frames = 0;
        windowStart = now;
    }

    private Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("game", engine.config().title());
        info.put("gpu", gpu.describe());
        info.put("size", engine.width() + "x" + engine.height());
        UserSettings s = engine.settings();
        info.put("settings", "vsync " + (s.vsync() ? "on" : "off")
                + ", frame cap " + (s.frameRateLimit() <= 0 ? "none" : s.frameRateLimit() + " fps")
                + ", anti-aliasing " + s.antiAliasing().label() + ", ambient occlusion " + s.ambientOcclusion().label()
                + ", shadows " + s.shadowQuality().label() + ", bloom " + s.bloom().label() + ", sun rays "
                + s.sunRays().label() + ", grass " + s.grass().label() + ", particles " + s.particles().label());
        info.put("vsync", s.vsync());
        info.put("gpuTimed", gpu.working());
        info.put("glTraced", GlCalls.traced());
        info.put("cpuSampled", cpu.working());
        info.put("clockNs", round(clockNanos));
        return info;
    }

    /**
     * The frame parts in order, each under its parent: name, depth, CPU and GPU ms a frame, times a frame, and with
     * the calls traced the OpenGL calls and draws made in it and the parts inside it, a frame.
     */
    private List<Object> scopeRows() {
        List<Object> rows = new ArrayList<>();
        addScope(rows, 0, 0);
        return rows;
    }

    /** Adds the part and those inside it; returns their calls and draws together. */
    private long[] addScope(List<Object> rows, int scope, int level) {
        long[] calls = {GlCalls.scopeCalls[scope], GlCalls.scopeDraws[scope]};
        if (entered[scope] == 0 && calls[0] == 0) {
            return calls;
        }
        double perFrame = Math.max(1, frames);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", names[scope]);
        row.put("depth", level);
        row.put("cpu", round(cpuNanos[scope] / 1e6 / perFrame));
        row.put("gpu", gpu.working() ? round(gpuNanos[scope] / 1e6 / perFrame) : -1);
        row.put("times", round(entered[scope] / perFrame));
        rows.add(row);
        for (int c = firstChild[scope]; c >= 0; c = nextSibling[c]) {
            long[] inside = addScope(rows, c, level + 1);
            calls[0] += inside[0];
            calls[1] += inside[1];
        }
        if (GlCalls.traced()) {
            row.put("glCalls", round(calls[0] / perFrame));
            row.put("draws", round(calls[1] / perFrame));
        }
        return calls;
    }

    private Map<String, Object> glReadings() {
        Map<String, Object> gl = new LinkedHashMap<>();
        gl.put("traced", GlCalls.traced());
        if (!GlCalls.traced()) {
            return gl;
        }
        double perFrame = Math.max(1, frames);
        long calls = 0;
        long nanos = 0;
        long draws = 0;
        List<Object> sites = new ArrayList<>();
        for (int i = 0, n = GlCalls.sites(); i < n; i++) {
            long count = GlCalls.counts[i];
            if (count == 0) {
                continue;
            }
            String function = GlCalls.function(i);
            calls += count;
            nanos += GlCalls.nanos[i];
            if (GlCalls.isDraw(function)) {
                draws += count;
            }
            sites.add(List.of(function, GlCalls.caller(i), round(count / perFrame), round(GlCalls.nanos[i] / 1e6 / perFrame)));
        }
        gl.put("calls", round(calls / perFrame));
        gl.put("ms", round(nanos / 1e6 / perFrame));
        gl.put("draws", round(draws / perFrame));
        gl.put("overheadMs", round(calls / perFrame * 2 * clockNanos / 1e6));
        gl.put("classes", GlCalls.rewrittenClasses.get());
        gl.put("skipped", GlCalls.skippedClasses.get());
        gl.put("sites", sites);
        return gl;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** What one clock read costs here, in ns: the call tracing makes two a call. */
    private static double clockCost() {
        long sink = 0;
        long began = System.nanoTime();
        for (int i = 0; i < 20000; i++) {
            sink += System.nanoTime();
        }
        long took = System.nanoTime() - began;
        return sink == 42 ? 0 : took / 20000.0;
    }

    private void finish() {
        cpu.stop();
        if (link != null) {
            link.close();
        }
        if (reportFile != null && last != null) {
            try {
                Path parent = reportFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(reportFile, DebugReport.text(last));
                System.out.println("[Debug] Report written to " + reportFile.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("[Debug] Could not write the report: " + e);
            }
        }
    }

    /** The way to the editor: a socket on this computer, written on its own thread so a frame never waits on it. */
    private static final class Link {
        private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(8);
        private final Thread thread;
        private volatile boolean closed;

        Link(int port) {
            thread = new Thread(() -> {
                try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                     Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    while (!closed) {
                        String line = lines.take();
                        out.write(line);
                        out.write('\n');
                        out.flush();
                    }
                } catch (IOException e) {
                    if (!closed) {
                        System.err.println("[Debug] Lost the editor: " + e.getMessage());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                closed = true;
            }, "engine-debug-link");
            thread.setDaemon(true);
            thread.start();
        }

        void send(String line) {
            if (!closed) {
                lines.offer(line);
            }
        }

        void close() {
            closed = true;
            thread.interrupt();
        }
    }
}
