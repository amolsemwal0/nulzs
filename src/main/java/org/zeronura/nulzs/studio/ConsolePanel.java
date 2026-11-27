package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.ui.Rect;
import org.zeronura.nulzs.ui.Typeface;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Console: what the editor, the compiler and a game started from the editor have printed, coloured by how serious
 * it is, with filters for each level and a search. A line naming a place in a source file — a compile error, a line of
 * a stack trace — opens that file at that line when clicked.
 */
final class ConsolePanel {

    /** "path/To/File.java:12" as the compiler is reported here, or "(File.java:12)" as a stack trace has it. */
    private static final Pattern PATH_LINE = Pattern.compile("([\\w./\\\\-]+\\.(?:java|glsl|vert|frag|json|scene)):(\\d+)");
    private static final Pattern FRAME = Pattern.compile("at ([\\w.$]+)\\.[\\w$<>]+\\((\\w+\\.java):(\\d+)\\)");

    private final StudioScene studio;
    private boolean showInfo = true;
    private boolean showWarnings = true;
    private boolean showErrors = true;
    private boolean follow = true;
    private String search = "";
    private long lastSeen;

    ConsolePanel(StudioScene studio) {
        this.studio = studio;
    }

    void draw(Ui ui, Rect area) {
        ConsoleLog log = studio.console;
        Rect bar = new Rect(area.x(), area.y(), area.width(), 30f);
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 6f;
        if (ui.button("consoleClear", "Clear", new Rect(x, bar.y() + 4f, 58f, 22f))) {
            log.clear();
        }
        x += 66f;
        List<ConsoleLog.Line> lines = log.snapshot();
        int infos = 0;
        for (ConsoleLog.Line line : lines) {
            if (line.level() == ConsoleLog.Level.INFO) {
                infos++;
            }
        }
        x = filter(ui, "consoleInfo", Icon.INFO, Theme.TEXT_MUTED, infos, x, bar, showInfo, () -> showInfo = !showInfo);
        x = filter(ui, "consoleWarn", Icon.WARNING, Theme.WARNING, log.warnings(), x, bar, showWarnings,
                () -> showWarnings = !showWarnings);
        x = filter(ui, "consoleErr", Icon.ERROR, Theme.ERROR, log.errors(), x, bar, showErrors, () -> showErrors = !showErrors);
        x += 8f;
        boolean followNow = ui.checkbox("consoleFollow", new Rect(x, bar.y(), 18f, bar.height()), follow);
        follow = followNow;
        ui.textIn("Follow", bar, x + 24f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        float searchWidth = Math.min(240f, bar.width() * 0.3f);
        search = ui.searchField("consoleSearch", new Rect(bar.x() + bar.width() - searchWidth - 6f, bar.y() + 4f, searchWidth,
                22f), search, "Filter");

        Rect body = new Rect(area.x(), area.y() + bar.height(), area.width(), area.height() - bar.height());
        ui.fill(body, Theme.FIELD);
        if (ui.pressed(body) || ui.rightPressed(body)) {
            studio.setFocus(StudioScene.Focus.CONSOLE);
        }
        String needle = search.toLowerCase(Locale.ROOT);
        List<ConsoleLog.Line> shown = new ArrayList<>();
        for (ConsoleLog.Line line : lines) {
            boolean levelShown = switch (line.level()) {
                case INFO -> showInfo;
                case WARNING -> showWarnings;
                case ERROR -> showErrors;
            };
            if (levelShown && (needle.isEmpty() || line.text().toLowerCase(Locale.ROOT).contains(needle))) {
                shown.add(line);
            }
        }
        float rowHeight = 19f;
        float content = shown.size() * rowHeight + 8f;
        ConsoleLog.Line newest = lines.isEmpty() ? null : lines.getLast();
        if (follow && newest != null && newest.id() != lastSeen) {
            ui.setScroll("console", Math.max(0f, content - body.height()));
        }
        lastSeen = newest == null ? 0 : newest.id();
        float scroll = ui.beginScroll("console", body, content);
        int first = Math.max(0, (int) ((scroll - 4f) / rowHeight));
        int last = Math.min(shown.size() - 1, (int) ((scroll + body.height()) / rowHeight) + 1);
        for (int i = first; i <= last; i++) {
            ConsoleLog.Line line = shown.get(i);
            Rect row = new Rect(body.x(), body.y() + 4f + i * rowHeight - scroll, body.width(), rowHeight);
            Location location = locate(line.text());
            boolean hover = ui.hover(row);
            if (hover) {
                ui.fill(row, Theme.HOVER.withAlpha(0.6f));
            }
            Color color = switch (line.level()) {
                case INFO -> Theme.TEXT;
                case WARNING -> Theme.WARNING;
                case ERROR -> Theme.ERROR;
            };
            Icon icon = switch (line.level()) {
                case INFO -> null;
                case WARNING -> Icon.WARNING;
                case ERROR -> Icon.ERROR;
            };
            if (icon != null) {
                icon.draw(ui.gui, row.x() + 6f, row.centerY() - 6f, 12f, color);
            }
            String time = String.format(Locale.ROOT, "%6.1f", line.time());
            float textY = row.centerY() - ui.textHeight(1.38f) * 0.5f;
            ui.gui.text(time, row.x() + 24f, textY, 1.38f, Theme.TEXT_DISABLED, Typeface.MONO);
            ui.gui.text(line.source(), row.x() + 78f, textY, 1.38f, Theme.TEXT_MUTED, Typeface.MONO);
            float textX = row.x() + 78f + ui.gui.textWidth("Compiler", 1.38f, Typeface.MONO) + 14f;
            ui.gui.text(line.text(), textX, textY, 1.38f, color, Typeface.MONO);
            if (location != null && hover) {
                ui.hline(textX, row.y() + row.height() - 3f,
                        ui.gui.textWidth(line.text(), 1.38f, Typeface.MONO), color.withAlpha(0.6f));
                ui.tooltip("consoleLine:" + line.id(), row, "Click to open " + location.file().getFileName() + " at line " + location.line());
            }
            if (location != null && ui.clicked("consoleLine:" + line.id(), row)) {
                studio.openCode(location.file(), location.line());
            }
            if (ui.rightPressed(row)) {
                Menu.openAtPointer(ui, "consoleMenu", List.of(
                        new Menu.Command("Copy Line", () -> ui.input.setClipboard(line.text())),
                        new Menu.Command("Copy All", () -> {
                            StringBuilder all = new StringBuilder();
                            for (ConsoleLog.Line l : shown) {
                                all.append(l.text()).append('\n');
                            }
                            ui.input.setClipboard(all.toString());
                        }),
                        Menu.SEPARATOR,
                        new Menu.Command("Clear", log::clear)));
            }
        }
        if (shown.isEmpty()) {
            ui.text("Nothing here yet. Compiling, playing and running the game print here.", body.x() + 12f,
                    body.y() + 12f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        }
        ui.endScroll("console", body, content);
    }

    private float filter(Ui ui, String id, Icon icon, Color color, int count, float x, Rect bar, boolean on, Runnable flip) {
        String label = Integer.toString(count);
        float w = ui.width(label, Theme.SMALL_SCALE) + 34f;
        Rect r = new Rect(x, bar.y() + 4f, w, 22f);
        if (on) {
            ui.gui.rounded(r, Theme.RADIUS, Theme.SECTION);
        } else if (ui.hover(r)) {
            ui.gui.rounded(r, Theme.RADIUS, Theme.HOVER);
        }
        icon.draw(ui.gui, r.x() + 6f, r.centerY() - 7f, 14f, on ? color : Theme.TEXT_DISABLED);
        ui.textIn(label, r, r.x() + 26f, Theme.SMALL_SCALE, on ? Theme.TEXT : Theme.TEXT_DISABLED);
        if (ui.clicked(id, r)) {
            flip.run();
        }
        return x + w + 4f;
    }

    record Location(Path file, int line) {
    }

    /** The source file and line a console line names, if it names one that exists. */
    Location locate(String text) {
        Matcher frame = FRAME.matcher(text);
        if (frame.find()) {
            String className = frame.group(1);
            String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
            Path file = studio.catalog.sourceRoot().resolve(outer.replace('.', '/') + ".java");
            if (Files.isRegularFile(file)) {
                return new Location(file, Integer.parseInt(frame.group(3)));
            }
        }
        Matcher path = PATH_LINE.matcher(text);
        if (path.find()) {
            Path file = Path.of(path.group(1));
            if (!Files.isRegularFile(file)) {
                file = studio.catalog.sourceRoot().resolve(path.group(1));
            }
            if (Files.isRegularFile(file)) {
                return new Location(file, Integer.parseInt(path.group(2)));
            }
        }
        return null;
    }
}
