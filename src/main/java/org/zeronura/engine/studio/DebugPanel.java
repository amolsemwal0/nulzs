package org.zeronura.engine.studio;

import org.zeronura.engine.debug.DebugReport;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.ui.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Debug tab: a game started in debug mode (Debug > Run Game in Debug Mode) measured while it runs, from the
 * readings it sends ({@link DebugLink}).
 *
 * <ul>
 *   <li><b>Overview</b> — frame rate, the CPU's and the graphics card's time a frame, what limits the frame rate, a
 *   graph of the last frames, and every part of the frame with its CPU and GPU time and the OpenGL calls and draws
 *   made in it.</li>
 *   <li><b>OpenGL Calls</b> — every call the game makes, by function or by the method calling it.</li>
 *   <li><b>CPU</b> — which of the game's methods the drawing thread runs, sampled.</li>
 *   <li><b>Memory</b> — the heap, what a frame allocates, the garbage collector.</li>
 * </ul>
 */
final class DebugPanel {

    private enum View {
        OVERVIEW("Overview"), CALLS("OpenGL Calls"), CPU("CPU"), MEMORY("Memory");

        final String label;

        View(String label) {
            this.label = label;
        }
    }

    private static final float BAR = 36f;
    private static final float PAD = 10f;
    private static final float NUMBER_COLUMN = 92f;
    private static final float INDENT = 16f;
    private static final Color GPU_BAR = Theme.WARNING.lerp(Theme.PANEL, 0.62f);
    private static final Color CPU_BAR = Theme.ACCENT.lerp(Theme.PANEL, 0.55f);

    private final StudioScene studio;
    private final DebugLink link;
    private View view = View.OVERVIEW;
    private boolean frozen;
    private Map<String, Object> frozenReadings;
    private float[] frozenHistory;
    private boolean byFunction = true;
    private boolean sortByCalls;
    private boolean ownTime = true;
    private String callSearch = "";
    private String cpuSearch = "";

    DebugPanel(StudioScene studio, DebugLink link) {
        this.studio = studio;
        this.link = link;
        // For a script's screenshot: -Dstudio.debugView=calls|cpu|memory opens on that view.
        for (View each : View.values()) {
            if (each.name().equalsIgnoreCase(System.getProperty("studio.debugView", ""))) {
                view = each;
            }
        }
    }

    /** The readings on show: the latest, or those kept when Freeze was pressed. */
    Map<String, Object> readings() {
        return frozen ? frozenReadings : link.readings();
    }

    boolean frozen() {
        return frozen;
    }

    void setFrozen(boolean freeze) {
        frozen = freeze && link.readings() != null;
        frozenReadings = frozen ? link.readings() : null;
        frozenHistory = frozen ? link.history() : null;
    }

    void draw(Ui ui, Rect area) {
        ui.fill(area, Theme.PANEL);
        Rect bar = new Rect(area.x(), area.y(), area.width(), BAR);
        drawBar(ui, bar);
        Rect body = new Rect(area.x(), area.y() + BAR, area.width(), area.height() - BAR);
        Map<String, Object> readings = readings();
        if (readings == null) {
            drawEmpty(ui, body);
            return;
        }
        switch (view) {
            case OVERVIEW -> drawOverview(ui, body, readings);
            case CALLS -> drawCalls(ui, body, readings);
            case CPU -> drawCpu(ui, body, readings);
            case MEMORY -> drawMemory(ui, body, readings);
        }
    }

    // --- The bar along the top ----------------------------------------------------------------------------------

    private void drawBar(Ui ui, Rect bar) {
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + PAD;
        DebugLink.State state = link.state();
        Color dot = switch (state) {
            case LIVE -> Theme.PLAY;
            case WAITING -> Theme.WARNING;
            case CLOSED -> Theme.TEXT_MUTED;
            case IDLE -> Theme.TEXT_DISABLED;
        };
        ui.gui.circle(x + 5f, bar.centerY(), 5f, dot);
        x += 16f;
        String name = link.gameName();
        String said = switch (state) {
            case IDLE -> "No game running in debug mode";
            case WAITING -> "Starting " + name + " in debug mode...";
            case LIVE -> name + ": live" + (frozen ? ", frozen" : "");
            case CLOSED -> name + " closed: its last readings";
        };
        x = ui.textIn(said, bar, x, Theme.SMALL_SCALE, Theme.TEXT) + 16f;
        Rect run = new Rect(x, bar.y() + 6f, 160f, bar.height() - 12f);
        if (studio.gameRunning()) {
            if (ui.button("debug:stop", "Stop Game", run)) {
                studio.stopGame();
            }
        } else if (ui.primaryButton("debug:run", "Run in Debug Mode", run)) {
            studio.runGameInDebugMode(null);
        }
        ui.tooltip("debug:run", run, "Compile and run the game with its frame time measured (F6)");
        x += run.width() + 8f;
        Rect freeze = new Rect(x, run.y(), 76f, run.height());
        if (link.readings() != null && ui.flatButton("debug:freeze", frozen ? "Resume" : "Freeze", freeze, frozen)) {
            setFrozen(!frozen);
        }
        ui.tooltip("debug:freeze", freeze, "Keep the readings on show still, to read them");
        x += freeze.width() + 6f;
        Rect save = new Rect(x, run.y(), ui.width("Save Report") + 24f, run.height());
        if (readings() != null && ui.button("debug:save", "Save Report", save)) {
            studio.saveDebugReport();
        }
        ui.tooltip("debug:save", save, "Write the readings on show to a text file in the project's debug-reports folder");

        float right = bar.x() + bar.width() - PAD;
        View[] views = View.values();
        for (int i = views.length - 1; i >= 0; i--) {
            float w = ui.width(views[i].label) + 22f;
            Rect r = new Rect(right - w, run.y(), w, run.height());
            if (ui.flatButton("debug:view:" + views[i].name(), views[i].label, r, view == views[i])) {
                view = views[i];
            }
            right -= w + 4f;
        }
    }

    private void drawEmpty(Ui ui, Rect body) {
        float y = body.y() + body.height() * 0.32f;
        String[] lines = link.state() == DebugLink.State.WAITING
                ? new String[] {"Waiting for the game's first readings...",
                "They come every half second once it has opened its window."}
                : new String[] {"Debug mode runs the game in its own window and measures where its frame time goes:",
                "every part of the frame on the CPU and on the graphics card, every OpenGL call the game makes,",
                "which of its methods the CPU is busy in, and what it allocates.",
                "",
                "Debug > Run Game in Debug Mode (F6), or the button above."};
        for (String line : lines) {
            ui.textCentered(line, new Rect(body.x(), y, body.width(), Theme.ROW), Theme.TEXT_MUTED);
            y += Theme.ROW;
        }
    }

    // --- Overview -----------------------------------------------------------------------------------------------

    private void drawOverview(Ui ui, Rect body, Map<String, Object> readings) {
        float x = body.x() + PAD;
        float width = body.width() - PAD * 2f;
        float y = body.y() + PAD;
        Map<String, Object> gl = DebugReport.map(readings, "gl");
        boolean traced = Boolean.TRUE.equals(gl.get("traced"));
        double gpu = DebugReport.gpuWork(readings);
        String[][] tiles = {
                {"Frames a second", f(readings.get("fps"), 0), ""},
                {"Frame time", ms(readings.get("frameMs")), "worst " + ms(readings.get("worstMs"))},
                {"CPU's own work", ms(DebugReport.cpuWork(readings)), "less its waits"},
                {"Graphics card", gpu < 0 ? "untimed" : ms(gpu), "for the frame's work"},
                {"Draw calls", traced ? f(gl.get("draws"), 0) : "-", "a frame"},
                {"OpenGL calls", traced ? f(gl.get("calls"), 0) : "-", traced ? ms(gl.get("ms")) + " to return" : "not traced"},
        };
        float tileWidth = (width - 8f * (tiles.length - 1)) / tiles.length;
        for (int i = 0; i < tiles.length; i++) {
            Rect tile = new Rect(x + i * (tileWidth + 8f), y, tileWidth, 62f);
            ui.gui.rounded(tile, 6f, Theme.SECTION);
            ui.text(ui.fit(tiles[i][0], tileWidth - 16f, Theme.SMALL_SCALE), tile.x() + 10f, tile.y() + 7f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            ui.text(ui.fit(tiles[i][1], tileWidth - 16f, 2.3f), tile.x() + 10f, tile.y() + 22f, 2.3f, Theme.TEXT_BRIGHT);
            ui.text(ui.fit(tiles[i][2], tileWidth - 16f, 1.2f), tile.x() + 10f, tile.y() + 47f, 1.2f, Theme.TEXT_MUTED);
        }
        y += 62f + 8f;

        DebugReport.Verdict verdict = DebugReport.verdict(readings);
        Color tone = switch (verdict.limit()) {
            case GPU -> Theme.WARNING;
            case CPU -> Theme.ACCENT;
            case VSYNC, CAP, FAST -> Theme.SUCCESS;
            case UNKNOWN -> Theme.TEXT_MUTED;
        };
        List<String> detail = wrap(ui, verdict.detail(), width - 28f, Theme.SMALL_SCALE);
        float lineHeight = ui.textHeight(Theme.SMALL_SCALE) + 5f;
        float boxHeight = 34f + detail.size() * lineHeight;
        Rect box = new Rect(x, y, width, boxHeight);
        ui.gui.rounded(box, 6f, Theme.PANEL_ALT);
        ui.gui.rounded(new Rect(box.x(), box.y(), 4f, box.height()), 2f, tone);
        ui.text(ui.fit(verdict.headline(), width - 28f), box.x() + 14f, box.y() + 8f, Theme.TEXT_SCALE, tone);
        float ly = box.y() + 30f;
        for (String line : detail) {
            ui.text(line, box.x() + 14f, ly, Theme.SMALL_SCALE, Theme.TEXT);
            ly += lineHeight;
        }
        y += boxHeight + 8f;

        Rect graph = new Rect(x, y, width, 96f);
        drawGraph(ui, graph, frozen ? frozenHistory : link.history());
        y += graph.height() + 8f;

        Map<String, Object> info = DebugReport.map(readings, "info");
        String about = info.get("gpu") + ", window " + info.get("size") + ". " + info.get("settings")
                + (traced ? String.format(Locale.ROOT, ". Tracing the OpenGL calls adds about %.1f ms a frame.",
                DebugReport.number(gl.get("overheadMs"))) : ".");
        List<String> aboutLines = wrap(ui, about, width, 1.2f);
        float aboutHeight = aboutLines.size() * (ui.textHeight(1.2f) + 4f) + 6f;

        Rect table = new Rect(x, y, width, body.y() + body.height() - y - aboutHeight - PAD);
        drawParts(ui, table, readings);
        float ay = table.y() + table.height() + 6f;
        for (String line : aboutLines) {
            ui.text(line, x, ay, 1.2f, Theme.TEXT_MUTED);
            ay += ui.textHeight(1.2f) + 4f;
        }
    }

    /** The frame times kept, newest on the right, against 60 and 30 frames a second. */
    private void drawGraph(Ui ui, Rect graph, float[] times) {
        ui.gui.rounded(graph, 6f, Theme.FIELD);
        if (times == null || times.length == 0) {
            return;
        }
        float top = 34f;
        for (float t : times) {
            top = Math.max(top, Math.min(t * 1.1f, 120f));
        }
        float inner = graph.height() - 12f;
        float base = graph.y() + graph.height() - 6f;
        int shown = Math.min(times.length, (int) ((graph.width() - 70f) / 2f));
        float step = (graph.width() - 70f) / Math.max(1, shown);
        for (int i = 0; i < shown; i++) {
            float t = times[times.length - shown + i];
            float h = Math.min(inner, t / top * inner);
            Color c = t <= 17.5f ? Theme.SUCCESS : t <= 34f ? Theme.WARNING : Theme.ERROR;
            ui.gui.rect(graph.x() + 6f + i * step, base - h, Math.max(1f, step - 0.5f), h, c);
        }
        for (float[] line : new float[][] {{16.67f, 60f}, {33.33f, 30f}}) {
            float ly = base - line[0] / top * inner;
            ui.gui.rect(graph.x() + 6f, ly, graph.width() - 70f, 1f, Theme.DIVIDER);
            ui.text(String.format(Locale.ROOT, "%.0f fps", line[1]), graph.x() + graph.width() - 58f,
                    ly - ui.textHeight(1.2f) * 0.5f, 1.2f, Theme.TEXT_MUTED);
        }
    }

    /** Every frame part under its parent: CPU and GPU ms with a bar against the frame, GL calls and draws. */
    private void drawParts(Ui ui, Rect area, Map<String, Object> readings) {
        List<Map<String, Object>> rows = DebugReport.scopes(readings);
        double frame = Math.max(0.001, DebugReport.number(readings.get("frameMs")));
        String[] headings = {"CPU ms", "GPU ms", "GL calls", "Draws"};
        Rect header = new Rect(area.x(), area.y(), area.width(), Theme.ROW);
        ui.fill(header, Theme.PANEL_HEADER);
        ui.textIn("Part of the frame", header, header.x() + 8f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        float numbers = area.x() + area.width() - headings.length * NUMBER_COLUMN;
        for (int i = 0; i < headings.length; i++) {
            ui.textRight(headings[i], header, numbers + (i + 1) * NUMBER_COLUMN - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        }
        ui.tooltip("debug:partsHeader", header, "Each part's time includes the parts inside it. The CPU's time is how "
                + "long the code took; the graphics card's how long it then spent on what the code asked of it.");
        Rect view = new Rect(area.x(), area.y() + Theme.ROW, area.width(), area.height() - Theme.ROW);
        ui.fill(view, Theme.FIELD);
        float content = rows.size() * Theme.ROW;
        float scroll = ui.beginScroll("debug:parts", view, content);
        for (int i = 0; i < rows.size(); i++) {
            float y = view.y() - scroll + i * Theme.ROW;
            if (y + Theme.ROW < view.y() || y > view.y() + view.height()) {
                continue;
            }
            Map<String, Object> row = rows.get(i);
            Rect r = new Rect(view.x(), y, view.width(), Theme.ROW);
            if (i % 2 == 1) {
                ui.fill(r, Theme.PANEL_ALT.lerp(Theme.FIELD, 0.6f));
            }
            if (ui.hover(r)) {
                ui.fill(r, Theme.HOVER);
            }
            int depth = (int) DebugReport.number(row.get("depth"));
            double cpu = DebugReport.number(row.get("cpu"));
            double gpu = DebugReport.number(row.get("gpu"));
            String name = String.valueOf(row.get("name"));
            ui.textIn(ui.fit(name, numbers - r.x() - 12f - depth * INDENT, Theme.SMALL_SCALE), r, r.x() + 8f + depth * INDENT,
                    Theme.SMALL_SCALE, depth <= 1 ? Theme.TEXT_BRIGHT : Theme.TEXT);
            drawNumber(ui, r, numbers, 0, cpu, frame, CPU_BAR, "%.2f");
            drawNumber(ui, r, numbers, 1, gpu, frame, GPU_BAR, "%.2f");
            drawNumber(ui, r, numbers, 2, row.containsKey("glCalls") ? DebugReport.number(row.get("glCalls")) : -1, 0, null, "%.0f");
            drawNumber(ui, r, numbers, 3, row.containsKey("draws") ? DebugReport.number(row.get("draws")) : -1, 0, null, "%.0f");
        }
        ui.endScroll("debug:parts", view, content);
    }

    private static void drawNumber(Ui ui, Rect row, float numbers, int column, double value, double whole, Color bar,
                                   String format) {
        float left = numbers + column * NUMBER_COLUMN;
        if (bar != null && value > 0 && whole > 0) {
            float w = (float) Math.min(1.0, value / whole) * (NUMBER_COLUMN - 8f);
            ui.gui.rect(left + 4f, row.y() + 4f, w, row.height() - 8f, bar);
        }
        ui.textRight(value < 0 ? "-" : String.format(Locale.ROOT, format, value), row, left + NUMBER_COLUMN - 10f,
                Theme.SMALL_SCALE, Theme.TEXT);
    }

    // --- OpenGL calls -------------------------------------------------------------------------------------------

    private void drawCalls(Ui ui, Rect body, Map<String, Object> readings) {
        Map<String, Object> gl = DebugReport.map(readings, "gl");
        float x = body.x() + PAD;
        float width = body.width() - PAD * 2f;
        float y = body.y() + PAD;
        if (!Boolean.TRUE.equals(gl.get("traced"))) {
            ui.text("The OpenGL calls weren't traced: Debug > Trace Every OpenGL Call was off when the game started.",
                    x, y, Theme.TEXT_MUTED);
            return;
        }
        if (ui.flatButton("debug:byFunction", "By function", new Rect(x, y, 110f, 26f), byFunction)) {
            byFunction = true;
        }
        if (ui.flatButton("debug:bySite", "By call site", new Rect(x + 114f, y, 110f, 26f), !byFunction)) {
            byFunction = false;
        }
        float searchWidth = Math.min(260f, width * 0.3f);
        callSearch = ui.searchField("debug:callSearch", new Rect(x + width - searchWidth, y + 2f, searchWidth, 22f),
                callSearch, "Filter");
        String totals = String.format(Locale.ROOT, "%.0f calls a frame, %.0f of them draws, %.2f ms a frame to return",
                DebugReport.number(gl.get("calls")), DebugReport.number(gl.get("draws")), DebugReport.number(gl.get("ms")));
        ui.text(totals, x + 240f, y + 5f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 26f + 6f;
        if (DebugReport.number(gl.get("skipped")) > 0) {
            ui.text((int) DebugReport.number(gl.get("skipped")) + " classes couldn't be traced: their calls are missing (see the Console)",
                    x, y, Theme.SMALL_SCALE, Theme.WARNING);
            y += Theme.ROW;
        }

        List<String[]> rows = new ArrayList<>();
        List<double[]> values = new ArrayList<>();
        String needle = callSearch.toLowerCase(Locale.ROOT);
        if (byFunction) {
            for (DebugReport.Function function : DebugReport.functions(readings)) {
                if (needle.isEmpty() || function.name().toLowerCase(Locale.ROOT).contains(needle)) {
                    rows.add(new String[] {function.name(), function.sites() + (function.sites() == 1 ? " place" : " places")});
                    values.add(new double[] {function.calls(), function.ms()});
                }
            }
        } else {
            for (Object site : DebugReport.list(gl, "sites")) {
                List<?> row = (List<?>) site;
                String function = String.valueOf(row.get(0));
                String caller = String.valueOf(row.get(1));
                if (needle.isEmpty() || function.toLowerCase(Locale.ROOT).contains(needle)
                        || caller.toLowerCase(Locale.ROOT).contains(needle)) {
                    rows.add(new String[] {function, caller});
                    values.add(new double[] {DebugReport.number(row.get(2)), DebugReport.number(row.get(3))});
                }
            }
        }
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        int key = sortByCalls ? 0 : 1;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values.get(b)[key], values.get(a)[key]));

        Rect header = new Rect(x, y, width, Theme.ROW);
        ui.fill(header, Theme.PANEL_HEADER);
        float numbers = x + width - 2 * NUMBER_COLUMN - 20f;
        float second = x + Math.min(320f, (numbers - x) * 0.42f);
        ui.textIn("Function", header, x + 8f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        ui.textIn(byFunction ? "Called from" : "Called from (the method)", header, second, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        Rect callsHead = new Rect(numbers, header.y(), NUMBER_COLUMN + 10f, header.height());
        Rect msHead = new Rect(numbers + NUMBER_COLUMN + 10f, header.y(), NUMBER_COLUMN + 10f, header.height());
        ui.textRight((sortByCalls ? "v " : "") + "Calls a frame", callsHead, callsHead.x() + callsHead.width() - 10f,
                Theme.SMALL_SCALE, sortByCalls ? Theme.TEXT_BRIGHT : Theme.TEXT_MUTED);
        ui.textRight((sortByCalls ? "" : "v ") + "ms a frame", msHead, msHead.x() + msHead.width() - 10f,
                Theme.SMALL_SCALE, sortByCalls ? Theme.TEXT_MUTED : Theme.TEXT_BRIGHT);
        if (ui.clicked("debug:sortCalls", callsHead)) {
            sortByCalls = true;
        }
        if (ui.clicked("debug:sortMs", msHead)) {
            sortByCalls = false;
        }
        ui.tooltip("debug:sortMs", msHead, "How long the calls took to return, on the CPU: the driver taking the "
                + "commands, not the graphics card carrying them out. Click to sort by it.");
        y += Theme.ROW;
        Rect view = new Rect(x, y, width, body.y() + body.height() - y - PAD);
        ui.fill(view, Theme.FIELD);
        float content = order.length * Theme.ROW;
        float scroll = ui.beginScroll("debug:calls", view, content);
        for (int n = 0; n < order.length; n++) {
            float ry = view.y() - scroll + n * Theme.ROW;
            if (ry + Theme.ROW < view.y() || ry > view.y() + view.height()) {
                continue;
            }
            int i = order[n];
            Rect r = new Rect(view.x(), ry, view.width(), Theme.ROW);
            if (n % 2 == 1) {
                ui.fill(r, Theme.PANEL_ALT.lerp(Theme.FIELD, 0.6f));
            }
            if (ui.hover(r)) {
                ui.fill(r, Theme.HOVER);
            }
            ui.textIn(ui.fit(rows.get(i)[0], second - r.x() - 16f, Theme.SMALL_SCALE), r, r.x() + 8f, Theme.SMALL_SCALE, Theme.TEXT_BRIGHT);
            ui.textIn(ui.fit(rows.get(i)[1], numbers - second - 10f, Theme.SMALL_SCALE), r, second, Theme.SMALL_SCALE, Theme.TEXT);
            ui.textRight(String.format(Locale.ROOT, "%.1f", values.get(i)[0]), r, numbers + NUMBER_COLUMN, Theme.SMALL_SCALE, Theme.TEXT);
            ui.textRight(String.format(Locale.ROOT, "%.3f", values.get(i)[1]), r, numbers + 2 * NUMBER_COLUMN + 10f,
                    Theme.SMALL_SCALE, Theme.TEXT);
        }
        ui.endScroll("debug:calls", view, content);
    }

    // --- CPU samples --------------------------------------------------------------------------------------------

    private void drawCpu(Ui ui, Rect body, Map<String, Object> readings) {
        Map<String, Object> cpu = DebugReport.map(readings, "cpu");
        float x = body.x() + PAD;
        float width = body.width() - PAD * 2f;
        float y = body.y() + PAD;
        if (!Boolean.TRUE.equals(cpu.get("working"))) {
            ui.text("No CPU samples: " + cpu.getOrDefault("problem", "the sampler is off"), x, y, Theme.TEXT_MUTED);
            return;
        }
        if (ui.flatButton("debug:own", "Own time", new Rect(x, y, 100f, 26f), ownTime)) {
            ownTime = true;
        }
        if (ui.flatButton("debug:with", "With what it calls", new Rect(x + 104f, y, 150f, 26f), !ownTime)) {
            ownTime = false;
        }
        float searchWidth = Math.min(260f, width * 0.3f);
        cpuSearch = ui.searchField("debug:cpuSearch", new Rect(x + width - searchWidth, y + 2f, searchWidth, 22f), cpuSearch,
                "Filter");
        double samples = Math.max(1, DebugReport.number(cpu.get("samples")));
        ui.text(String.format(Locale.ROOT, "%.0f samples of the drawing thread in the last %.0f s, one every %.0f ms",
                        DebugReport.number(cpu.get("samples")), DebugReport.number(cpu.get("windowSeconds")),
                        DebugReport.number(cpu.get("periodMs"))), x + 270f, y + 5f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 26f + 6f;
        ui.text(ownTime ? "Where the drawing thread was when sampled: the method itself, not what it called."
                        : "Every method on the way to where it was: a method's own time and everything it called.",
                x, y, 1.25f, Theme.TEXT_MUTED);
        y += 20f;

        List<Object[]> rows = new ArrayList<>();
        String needle = cpuSearch.toLowerCase(Locale.ROOT);
        for (Object entry : DebugReport.list(cpu, ownTime ? "self" : "total")) {
            List<?> pair = (List<?>) entry;
            String method = String.valueOf(pair.get(0));
            if (needle.isEmpty() || method.toLowerCase(Locale.ROOT).contains(needle)) {
                rows.add(new Object[] {method, DebugReport.number(pair.get(1))});
            }
        }
        List<Object> threads = DebugReport.list(cpu, "threads");

        Rect header = new Rect(x, y, width, Theme.ROW);
        ui.fill(header, Theme.PANEL_HEADER);
        ui.textRight("Share", header, x + 70f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        ui.textRight("ms a frame", header, x + 70f + NUMBER_COLUMN, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        ui.textIn("Method", header, x + 70f + NUMBER_COLUMN + 16f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += Theme.ROW;
        Rect view = new Rect(x, y, width, body.y() + body.height() - y - PAD);
        ui.fill(view, Theme.FIELD);
        float content = (rows.size() + 2 + threads.size()) * Theme.ROW;
        float scroll = ui.beginScroll("debug:cpu", view, content);
        for (int i = 0; i < rows.size(); i++) {
            float ry = view.y() - scroll + i * Theme.ROW;
            if (ry + Theme.ROW < view.y() || ry > view.y() + view.height()) {
                continue;
            }
            Rect r = new Rect(view.x(), ry, view.width(), Theme.ROW);
            double count = (double) rows.get(i)[1];
            float share = (float) (count / samples);
            ui.gui.rect(r.x() + 4f, r.y() + 4f, 66f * share, r.height() - 8f, CPU_BAR);
            if (ui.hover(r)) {
                ui.fill(r, Theme.HOVER);
            }
            ui.textRight(String.format(Locale.ROOT, "%.1f%%", 100 * share), r, r.x() + 70f, Theme.SMALL_SCALE, Theme.TEXT);
            ui.textRight(String.format(Locale.ROOT, "%.3f", DebugReport.sampleMs(readings, count)), r,
                    r.x() + 70f + NUMBER_COLUMN, Theme.SMALL_SCALE, Theme.TEXT);
            String method = (String) rows.get(i)[0];
            ui.textIn(ui.fit(method, r.width() - 70f - NUMBER_COLUMN - 24f, Theme.SMALL_SCALE), r, r.x() + 70f + NUMBER_COLUMN + 16f,
                    Theme.SMALL_SCALE, method.startsWith("engine.") || method.startsWith("games.") ? Theme.TEXT_BRIGHT : Theme.TEXT);
        }
        float ty = view.y() - scroll + (rows.size() + 1) * Theme.ROW;
        ui.text("Threads (samples of every thread)", view.x() + 8f, ty + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        for (int i = 0; i < threads.size(); i++) {
            List<?> pair = (List<?>) threads.get(i);
            Rect r = new Rect(view.x(), ty + (i + 1) * Theme.ROW, view.width(), Theme.ROW);
            ui.textRight(String.format(Locale.ROOT, "%.0f", DebugReport.number(pair.get(1))), r, r.x() + 70f, Theme.SMALL_SCALE, Theme.TEXT);
            ui.textIn(String.valueOf(pair.get(0)), r, r.x() + 70f + NUMBER_COLUMN + 16f, Theme.SMALL_SCALE, Theme.TEXT);
        }
        ui.endScroll("debug:cpu", view, content);
    }

    // --- Memory -------------------------------------------------------------------------------------------------

    private void drawMemory(Ui ui, Rect body, Map<String, Object> readings) {
        Map<String, Object> memory = DebugReport.map(readings, "memory");
        float x = body.x() + PAD;
        float width = Math.min(body.width() - PAD * 2f, 720f);
        float y = body.y() + PAD;
        double heap = DebugReport.number(memory.get("heapMb"));
        double max = Math.max(1, DebugReport.number(memory.get("heapMaxMb")));
        float values = x + ui.width("Time collecting, last half second") + 28f;
        ui.text("Heap in use", x, y, Theme.TEXT_MUTED);
        ui.text(String.format(Locale.ROOT, "%.0f of %.0f MB", heap, max), values, y, Theme.TEXT_BRIGHT);
        y += Theme.ROW;
        Rect bar = new Rect(x, y, width, 12f);
        ui.gui.rounded(bar, 4f, Theme.FIELD);
        ui.gui.rounded(new Rect(x, y, (float) (width * Math.min(1.0, heap / max)), 12f), 4f, CPU_BAR);
        y += 24f;
        double allocated = DebugReport.number(memory.get("allocatedKbPerFrame"));
        String[][] lines = {
                {"Allocated a frame", allocated < 0 ? "not known" : String.format(Locale.ROOT, "%.1f KB", allocated),
                        "By the drawing thread. All of it is garbage the collector clears later; the less a frame allocates, the rarer its pauses."},
                {"Collections, last half second", String.format(Locale.ROOT, "%.0f", DebugReport.number(memory.get("gcCount"))), ""},
                {"Time collecting, last half second", String.format(Locale.ROOT, "%.0f ms", DebugReport.number(memory.get("gcMs"))),
                        "A collection mostly runs beside the game; a long one shows as a tall bar in the Overview's graph."},
        };
        for (String[] line : lines) {
            ui.text(line[0], x, y, Theme.TEXT_MUTED);
            ui.text(line[1], values, y, Theme.TEXT_BRIGHT);
            y += Theme.ROW;
            if (!line[2].isEmpty()) {
                for (String part : wrap(ui, line[2], width, 1.25f)) {
                    ui.text(part, x, y, 1.25f, Theme.TEXT_MUTED);
                    y += 18f;
                }
                y += 6f;
            }
        }
    }

    // --- Little helpers -----------------------------------------------------------------------------------------

    private static String f(Object value, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", DebugReport.number(value));
    }

    private static String ms(Object value) {
        return String.format(Locale.ROOT, "%.1f ms", value instanceof Number n ? n.doubleValue() : DebugReport.number(value));
    }

    private static String ms(double value) {
        return String.format(Locale.ROOT, "%.1f ms", value);
    }

    private static List<String> wrap(Ui ui, String text, float width, float scale) {
        return ui.wrap(text, width, scale);
    }
}
