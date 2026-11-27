package org.zeronura.engine.debug;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the CPU's time goes in the game's own code: Java Flight Recorder samples what every thread is running every
 * {@link #PERIOD_MS} ms, and the drawing thread's samples are added up by method — the method it was in ({@code self})
 * and every method on the way there ({@code total}). Kept for the last {@link #WINDOW_SECONDS} seconds, as the
 * recorder hands samples over about a second late.
 *
 * <p>Samples are taken only while a thread runs Java code: time inside the graphics driver is in {@link GlCalls}, and
 * time asleep or waiting for the screen in {@link DebugMode}'s frame parts.
 *
 * <p>With them, what the drawing thread allocates (garbage the collector must then clear) and the collector's pauses.
 * Desktop only: Android has its own of this class, which measures nothing.
 */
final class CpuSampler {

    static final int PERIOD_MS = 2;
    static final int WINDOW_SECONDS = 5;
    private static final int BUCKETS = WINDOW_SECONDS + 3;
    private static final int LISTED = 80;

    private static final class Bucket {
        long second = -1;
        int samples;
        final Map<String, int[]> self = new HashMap<>();
        final Map<String, int[]> total = new HashMap<>();
        final Map<String, int[]> threads = new HashMap<>();

        void reset(long at) {
            second = at;
            samples = 0;
            self.clear();
            total.clear();
            threads.clear();
        }
    }

    private final long mainThread;
    private final Bucket[] buckets = new Bucket[BUCKETS];
    private RecordingStream stream;
    private String problem = "";
    private long lastAllocated = -1;
    private long lastGcCount;
    private long lastGcMillis;

    CpuSampler(Thread main) {
        mainThread = main.threadId();
        for (int i = 0; i < BUCKETS; i++) {
            buckets[i] = new Bucket();
        }
        try {
            RecordingStream recording = new RecordingStream();
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(PERIOD_MS));
            recording.onEvent("jdk.ExecutionSample", this::sample);
            recording.startAsync();
            stream = recording;
        } catch (Throwable e) {
            problem = "Flight Recorder would not start: " + e.getMessage();
            System.err.println("[Debug] CPU sampling is off: " + e);
        }
        long[] gc = gcTotals();
        lastGcCount = gc[0];
        lastGcMillis = gc[1];
    }

    boolean working() {
        return stream != null;
    }

    private void sample(RecordedEvent event) {
        RecordedThread thread = event.getThread("sampledThread");
        long second = event.getStartTime().getEpochSecond();
        synchronized (this) {
            Bucket bucket = buckets[(int) Math.floorMod(second, (long) BUCKETS)];
            if (bucket.second != second) {
                bucket.reset(second);
            }
            String name = thread == null ? "(unknown)" : thread.getJavaName() == null ? thread.getOSName() : thread.getJavaName();
            count(bucket.threads, name);
            if (thread == null || thread.getJavaThreadId() != mainThread) {
                return;
            }
            RecordedStackTrace trace = event.getStackTrace();
            if (trace == null || trace.getFrames().isEmpty()) {
                return;
            }
            bucket.samples++;
            List<RecordedFrame> frames = trace.getFrames();
            count(bucket.self, method(frames.getFirst()));
            Set<String> seen = new HashSet<>();
            for (RecordedFrame frame : frames) {
                String method = method(frame);
                if (seen.add(method)) {
                    count(bucket.total, method);
                }
            }
        }
    }

    private static String method(RecordedFrame frame) {
        RecordedMethod method = frame.getMethod();
        return method == null ? "(unknown)" : DebugMode.shortName(method.getType().getName()) + "." + method.getName();
    }

    private static void count(Map<String, int[]> counts, String key) {
        counts.computeIfAbsent(key, k -> new int[1])[0]++;
    }

    /**
     * The last {@link #WINDOW_SECONDS} whole seconds: {@code samples} on the drawing thread, the methods most often
     * {@code self} and {@code total} as [method, samples], and {@code threads} as [name, samples].
     */
    synchronized Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodMs", PERIOD_MS);
        out.put("windowSeconds", WINDOW_SECONDS);
        out.put("working", stream != null);
        out.put("problem", problem);
        if (stream == null) {
            return out;
        }
        long now = System.currentTimeMillis() / 1000L;
        int samples = 0;
        Map<String, int[]> self = new HashMap<>();
        Map<String, int[]> total = new HashMap<>();
        Map<String, int[]> threads = new HashMap<>();
        for (Bucket bucket : buckets) {
            if (bucket.second >= now - WINDOW_SECONDS && bucket.second < now) {
                samples += bucket.samples;
                merge(self, bucket.self);
                merge(total, bucket.total);
                merge(threads, bucket.threads);
            }
        }
        out.put("samples", samples);
        out.put("self", top(self));
        out.put("total", top(total));
        out.put("threads", top(threads));
        return out;
    }

    private static void merge(Map<String, int[]> into, Map<String, int[]> from) {
        for (Map.Entry<String, int[]> entry : from.entrySet()) {
            into.computeIfAbsent(entry.getKey(), k -> new int[1])[0] += entry.getValue()[0];
        }
    }

    private static List<Object> top(Map<String, int[]> counts) {
        List<Map.Entry<String, int[]>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(LISTED, entries.size()); i++) {
            out.add(List.of(entries.get(i).getKey(), entries.get(i).getValue()[0]));
        }
        return out;
    }

    /**
     * Memory since the last call: bytes the drawing thread allocated, collections and their milliseconds, and the
     * heap now. {allocatedBytes, gcCount, gcMillis, heapUsedBytes, heapMaxBytes}; allocatedBytes is -1 where the JVM
     * can't say.
     */
    long[] memory() {
        long allocated = -1;
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean threads
                && threads.isThreadAllocatedMemorySupported()) {
            long now = threads.getThreadAllocatedBytes(mainThread);
            if (lastAllocated >= 0 && now >= lastAllocated) {
                allocated = now - lastAllocated;
            }
            lastAllocated = now;
        }
        long[] gc = gcTotals();
        long count = gc[0] - lastGcCount;
        long millis = gc[1] - lastGcMillis;
        lastGcCount = gc[0];
        lastGcMillis = gc[1];
        Runtime runtime = Runtime.getRuntime();
        return new long[] {allocated, count, millis, runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory()};
    }

    private static long[] gcTotals() {
        long count = 0;
        long millis = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, bean.getCollectionCount());
            millis += Math.max(0L, bean.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    void stop() {
        if (stream != null) {
            try {
                stream.close();
            } catch (RuntimeException ignored) {
                // already closing with the JVM
            }
            stream = null;
        }
    }
}
