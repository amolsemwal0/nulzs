package org.zeronura.nulzs.debug;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@link DebugMode}'s readings: what holds the frame back, and the readings as text. The editor's Debug panel
 * reads them the same way, from the JSON the game sends.
 */
public final class DebugReport {

    /** What limits the frame rate, in a line, and why. */
    public record Verdict(String headline, String detail, Limit limit) {
    }

    public enum Limit { GPU, CPU, VSYNC, CAP, FAST, UNKNOWN }

    /** Frames this short (over 200 a second) are fast whatever limits them: not a problem to look into. */
    static final double FAST_MS = 5.0;

    /** A frame part's own time, without the parts inside it; its path from the frame down, joined by " > ". */
    public record Part(String path, double ms) {
    }

    /** One OpenGL function, added up over every place it is called from. */
    public record Function(String name, double calls, double ms, int sites) {
    }

    private DebugReport() {
    }

    // --- Reading the readings -----------------------------------------------------------------------------------

    public static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> readings, String key) {
        Object value = readings == null ? null : readings.get(key);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> readings, String key) {
        Object value = readings == null ? null : readings.get(key);
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> scopes(Map<String, Object> readings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : list(readings, "scopes")) {
            if (row instanceof Map<?, ?> m) {
                rows.add((Map<String, Object>) m);
            }
        }
        return rows;
    }

    /** CPU ms a frame of a part straight under the frame, or 0. */
    public static double topLevel(Map<String, Object> readings, String name, String key) {
        for (Map<String, Object> row : scopes(readings)) {
            if (number(row.get("depth")) == 1 && name.equals(row.get("name"))) {
                return Math.max(0, number(row.get(key)));
            }
        }
        return 0;
    }

    /** The CPU's own work a frame: the frame less its waits, for the graphics card or the screen and for the cap. */
    public static double cpuWork(Map<String, Object> readings) {
        return Math.max(0, number(readings.get("frameMs")) - topLevel(readings, DebugMode.SWAP, "cpu")
                - topLevel(readings, DebugMode.CAP, "cpu"));
    }

    /** The graphics card's time a frame for the frame's work (the swap and the cap are not work), or -1 untimed. */
    public static double gpuWork(Map<String, Object> readings) {
        if (!Boolean.TRUE.equals(map(readings, "info").get("gpuTimed"))) {
            return -1;
        }
        double sum = 0;
        for (Map<String, Object> row : scopes(readings)) {
            String name = String.valueOf(row.get("name"));
            if (number(row.get("depth")) == 1 && !name.equals(DebugMode.SWAP) && !name.equals(DebugMode.CAP)) {
                sum += Math.max(0, number(row.get("gpu")));
            }
        }
        return sum;
    }

    public static Verdict verdict(Map<String, Object> readings) {
        double frame = number(readings.get("frameMs"));
        if (frame <= 0) {
            return new Verdict("No readings yet", "", Limit.UNKNOWN);
        }
        double swap = topLevel(readings, DebugMode.SWAP, "cpu");
        double cap = topLevel(readings, DebugMode.CAP, "cpu");
        double work = cpuWork(readings);
        double gpu = gpuWork(readings);
        boolean vsync = Boolean.TRUE.equals(map(readings, "info").get("vsync"));
        if (frame < FAST_MS) {
            return new Verdict(String.format(Locale.ROOT, "Fast: %.0f frames a second, nothing holds it back", 1000.0 / frame),
                    "The CPU's work is " + ms(work) + " a frame and the graphics card's " + (gpu < 0 ? "untimed" : ms(gpu))
                            + ". Biggest on the CPU: " + biggest(parts(readings, "cpu"), 3) + ".", Limit.FAST);
        }
        if (cap > 0.5 && cap > frame * 0.15) {
            return new Verdict(String.format(Locale.ROOT, "Held by the frame-rate cap: %.1f ms a frame is spent waiting for it", cap),
                    "The game could run faster: the CPU's work is " + ms(work) + " a frame and the graphics card's "
                            + (gpu < 0 ? "untimed" : ms(gpu)) + ". Raise or remove the cap to see.", Limit.CAP);
        }
        if (vsync && swap > frame * 0.2 && (gpu < 0 || gpu < frame * 0.85)) {
            return new Verdict("Held by vsync: " + ms(swap) + " a frame is spent waiting for the screen to refresh",
                    "The frame rate can't go past the screen's refresh rate with vsync on. The CPU's work is " + ms(work)
                            + " a frame and the graphics card's " + (gpu < 0 ? "untimed" : ms(gpu)) + ".", Limit.VSYNC);
        }
        if (gpu >= 0 && gpu > work * 1.15) {
            return new Verdict("GPU-bound: the graphics card needs " + ms(gpu) + " a frame, the CPU's own work "
                    + ms(work), "The CPU spends " + ms(swap) + " a frame waiting for the graphics card to finish (in "
                    + DebugMode.SWAP + "). Making the CPU's code faster won't raise the frame rate; drawing less, at a "
                    + "lower resolution or with cheaper effects will. Biggest on the graphics card: "
                    + biggest(parts(readings, "gpu"), 4) + ".", Limit.GPU);
        }
        return new Verdict("CPU-bound: the CPU's own work is " + ms(work) + " a frame, the graphics card's "
                + (gpu < 0 ? "untimed" : ms(gpu)), "The graphics card waits for the CPU. Biggest on the CPU: "
                + biggest(parts(readings, "cpu"), 4) + ". The CPU samples and the OpenGL calls say which code.", Limit.CPU);
    }

    private static String ms(double ms) {
        return String.format(Locale.ROOT, "%.1f ms", ms);
    }

    private static String biggest(List<Part> parts, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(count, parts.size()); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(shortPath(parts.get(i).path())).append(' ').append(ms(parts.get(i).ms()));
        }
        return out.isEmpty() ? "nothing measured" : out.toString();
    }

    /** A part's path cut to its last two steps, "World > Shadow map": enough to know it by in a sentence. */
    public static String shortPath(String path) {
        int last = path.lastIndexOf(" > ");
        if (last < 0) {
            return path;
        }
        int before = path.lastIndexOf(" > ", last - 1);
        return before < 0 ? path : path.substring(before + 3);
    }

    /**
     * Each frame part's own time ({@code key} "cpu" or "gpu"), less the parts inside it, largest first. The waits —
     * the swap and the cap — and the frame itself are left out: they are not work.
     */
    public static List<Part> parts(Map<String, Object> readings, String key) {
        List<Map<String, Object>> rows = scopes(readings);
        List<Part> out = new ArrayList<>();
        String[] path = new String[64];
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            int depth = (int) number(row.get("depth"));
            String name = String.valueOf(row.get("name"));
            if (depth < path.length) {
                path[depth] = name;
            }
            double own = number(row.get(key));
            if (own < 0) {
                continue;
            }
            for (int j = i + 1; j < rows.size() && number(rows.get(j).get("depth")) > depth; j++) {
                if (number(rows.get(j).get("depth")) == depth + 1) {
                    own -= Math.max(0, number(rows.get(j).get(key)));
                }
            }
            if (depth == 0 || (depth == 1 && (name.equals(DebugMode.SWAP) || name.equals(DebugMode.CAP)))) {
                continue;
            }
            StringBuilder joined = new StringBuilder();
            for (int d = 1; d <= Math.min(depth, path.length - 1); d++) {
                if (d > 1) {
                    joined.append(" > ");
                }
                joined.append(path[d]);
            }
            out.add(new Part(joined.toString(), Math.max(0, own)));
        }
        out.sort((a, b) -> Double.compare(b.ms(), a.ms()));
        return out;
    }

    /** The OpenGL calls added up by function, most time first. */
    public static List<Function> functions(Map<String, Object> readings) {
        Map<String, double[]> sums = new HashMap<>();
        for (Object site : list(map(readings, "gl"), "sites")) {
            if (site instanceof List<?> row && row.size() >= 4) {
                double[] sum = sums.computeIfAbsent(String.valueOf(row.get(0)), k -> new double[3]);
                sum[0] += number(row.get(2));
                sum[1] += number(row.get(3));
                sum[2]++;
            }
        }
        List<Function> out = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            out.add(new Function(entry.getKey(), entry.getValue()[0], entry.getValue()[1], (int) entry.getValue()[2]));
        }
        out.sort((a, b) -> Double.compare(b.ms(), a.ms()));
        return out;
    }

    /** CPU samples as ms a frame: each sample stands for its period of the drawing thread's time. */
    public static double sampleMs(Map<String, Object> readings, double samples) {
        Map<String, Object> cpu = map(readings, "cpu");
        double frames = number(cpu.get("windowSeconds")) * number(readings.get("fps"));
        return frames <= 0 ? 0 : samples * number(cpu.get("periodMs")) / frames;
    }

    // --- As text ------------------------------------------------------------------------------------------------

    /** The readings as a report to read or keep. */
    public static String text(Map<String, Object> readings) {
        StringBuilder out = new StringBuilder();
        Map<String, Object> info = map(readings, "info");
        out.append("Debug report: ").append(info.get("game")).append(", ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        out.append("Graphics: ").append(info.get("gpu")).append(", window ").append(info.get("size")).append('\n');
        out.append("Settings: ").append(info.get("settings")).append("\n\n");
        double gpu = gpuWork(readings);
        out.append(String.format(Locale.ROOT, "%.1f fps   frame %.2f ms (worst %.2f)   CPU work %.2f ms   GPU %s%n",
                number(readings.get("fps")), number(readings.get("frameMs")), number(readings.get("worstMs")),
                cpuWork(readings), gpu < 0 ? "untimed" : String.format(Locale.ROOT, "%.2f ms", gpu)));
        Verdict verdict = verdict(readings);
        out.append(verdict.headline()).append('\n').append(verdict.detail()).append("\n\n");

        out.append("Biggest on the CPU (own time a frame):\n");
        for (Part part : first(parts(readings, "cpu"), 12)) {
            out.append(String.format(Locale.ROOT, "  %7.3f ms  %s%n", part.ms(), part.path()));
        }
        if (gpu >= 0) {
            out.append("Biggest on the graphics card (own time a frame):\n");
            for (Part part : first(parts(readings, "gpu"), 12)) {
                out.append(String.format(Locale.ROOT, "  %7.3f ms  %s%n", part.ms(), part.path()));
            }
        }

        out.append("\nFrame parts (with the parts inside them)          CPU ms    GPU ms   GL calls     draws\n");
        for (Map<String, Object> row : scopes(readings)) {
            int depth = (int) number(row.get("depth"));
            StringBuilder name = new StringBuilder();
            for (int d = 0; d < depth; d++) {
                name.append("  ");
            }
            name.append(row.get("name"));
            double g = number(row.get("gpu"));
            out.append(String.format(Locale.ROOT, "%-48s %9.3f %9s %10s %9s%n", fit(name.toString(), 48), number(row.get("cpu")),
                    g < 0 ? "-" : String.format(Locale.ROOT, "%.3f", g),
                    row.containsKey("glCalls") ? String.format(Locale.ROOT, "%.0f", number(row.get("glCalls"))) : "-",
                    row.containsKey("draws") ? String.format(Locale.ROOT, "%.0f", number(row.get("draws"))) : "-"));
        }

        Map<String, Object> gl = map(readings, "gl");
        out.append('\n');
        if (!Boolean.TRUE.equals(gl.get("traced"))) {
            out.append("OpenGL calls: not traced (the game was started without the call tracer).\n");
        } else {
            out.append(String.format(Locale.ROOT,
                    "OpenGL calls: %.0f a frame, %.0f of them draws, %.2f ms a frame to return (tracing them adds about %.2f ms)%n",
                    number(gl.get("calls")), number(gl.get("draws")), number(gl.get("ms")), number(gl.get("overheadMs"))));
            out.append(String.format(Locale.ROOT, "  %d classes traced%s%n", (int) number(gl.get("classes")),
                    number(gl.get("skipped")) > 0 ? ", " + (int) number(gl.get("skipped")) + " could not be (their calls are missing)" : ""));
            out.append("By function                                calls a frame   ms a frame   call sites\n");
            for (Function function : first(functions(readings), 40)) {
                out.append(String.format(Locale.ROOT, "  %-40s %12.1f %12.3f %8d%n", fit(function.name(), 40), function.calls(),
                        function.ms(), function.sites()));
            }
            out.append("By call site                                                                calls a frame   ms a frame\n");
            List<Object> sites = new ArrayList<>(list(gl, "sites"));
            sites.sort((a, b) -> Double.compare(number(((List<?>) b).get(3)), number(((List<?>) a).get(3))));
            for (Object site : first(sites, 80)) {
                List<?> row = (List<?>) site;
                out.append(String.format(Locale.ROOT, "  %-30s %-44s %12.1f %12.3f%n", fit(String.valueOf(row.get(0)), 30),
                        fit(String.valueOf(row.get(1)), 44), number(row.get(2)), number(row.get(3))));
            }
        }

        Map<String, Object> cpu = map(readings, "cpu");
        out.append('\n');
        if (!Boolean.TRUE.equals(cpu.get("working"))) {
            out.append("CPU samples: none (").append(cpu.getOrDefault("problem", "the sampler is off")).append(")\n");
        } else {
            out.append(String.format(Locale.ROOT, "CPU samples: %.0f on the drawing thread in the last %.0f s, one every %.0f ms%n",
                    number(cpu.get("samples")), number(cpu.get("windowSeconds")), number(cpu.get("periodMs"))));
            sampled(out, readings, "Own time (the method itself)", list(cpu, "self"));
            sampled(out, readings, "With what it calls", list(cpu, "total"));
            out.append("Threads (samples, all threads)\n");
            for (Object row : first(list(cpu, "threads"), 12)) {
                List<?> pair = (List<?>) row;
                out.append(String.format(Locale.ROOT, "  %8.0f  %s%n", number(pair.get(1)), pair.get(0)));
            }
        }

        Map<String, Object> memory = map(readings, "memory");
        out.append(String.format(Locale.ROOT,
                "%nMemory: heap %.0f of %.0f MB; the drawing thread allocates %s a frame; %d collections (%d ms) in the last half second%n",
                number(memory.get("heapMb")), number(memory.get("heapMaxMb")),
                number(memory.get("allocatedKbPerFrame")) < 0 ? "an unknown amount"
                        : String.format(Locale.ROOT, "%.1f KB", number(memory.get("allocatedKbPerFrame"))),
                (long) number(memory.get("gcCount")), (long) number(memory.get("gcMs"))));
        return out.toString();
    }

    private static void sampled(StringBuilder out, Map<String, Object> readings, String title, List<Object> rows) {
        double samples = Math.max(1, number(map(readings, "cpu").get("samples")));
        out.append(title).append("                 share    ms a frame\n");
        for (Object row : first(rows, 30)) {
            List<?> pair = (List<?>) row;
            double count = number(pair.get(1));
            out.append(String.format(Locale.ROOT, "  %5.1f%%  %8.3f  %s%n", 100 * count / samples, sampleMs(readings, count), pair.get(0)));
        }
    }

    private static <T> List<T> first(List<T> list, int count) {
        return list.size() <= count ? list : list.subList(0, count);
    }

    private static String fit(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "~";
    }
}
