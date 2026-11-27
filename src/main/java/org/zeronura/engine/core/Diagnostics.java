package org.zeronura.engine.core;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.lwjgl.opengl.GL33.GL_RENDERER;
import static org.lwjgl.opengl.GL33.GL_VENDOR;
import static org.lwjgl.opengl.GL33.GL_VERSION;
import static org.lwjgl.opengl.GL33.glGetString;

/**
 * What a bug report is made of: the end of everything the game has printed, a description of the computer, and the
 * zip that carries them. A crash writes one by itself ({@link #writeCrashReport}); a game can offer the player a way to
 * write one too ({@link #writeArchive}).
 *
 * <p>Nothing here leaves the computer. A report is a file in {@link #FOLDER} for the player to send.
 */
public final class Diagnostics {

    /** Where reports go. Absolute because a Windows launcher can start the game from another folder. */
    public static final Path FOLDER = Path.of("bug-reports").toAbsolutePath();

    /** The most of the log a report keeps: its newest part. */
    private static final int LOG_BYTES = 512 * 1024;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.ROOT);
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    /** Names the last crash's report, for the next run to tell the player about. */
    private static final Path CRASH_NOTE = FOLDER.resolve("last-crash.txt");

    private static final byte[] log = new byte[LOG_BYTES];
    private static int logEnd;
    private static boolean logFull;
    private static Charset logCharset = StandardCharsets.UTF_8;
    private static String graphics = "not known: the window never opened";
    private static final Map<String, Path> crashFiles = new LinkedHashMap<>();

    private Diagnostics() {
    }

    // --- The log ------------------------------------------------------------------------------------------------

    /** From here on, what the game prints is also kept for reports. It still goes where it went; call it once, first. */
    public static void captureLog() {
        logCharset = System.out.charset();
        System.setOut(new PrintStream(new Tee(System.out), true, System.out.charset()));
        System.setErr(new PrintStream(new Tee(System.err), true, System.err.charset()));
    }

    /** Passes bytes on to where they were going, keeping a copy. */
    private static final class Tee extends OutputStream {
        private final PrintStream original;

        Tee(PrintStream original) {
            this.original = original;
        }

        @Override
        public void write(int b) {
            original.write(b);
            remember(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            original.write(bytes, offset, length);
            remember(bytes, offset, length);
        }

        @Override
        public void flush() {
            original.flush();
        }
    }

    static synchronized void remember(byte[] bytes, int offset, int length) {
        if (length >= log.length) {
            System.arraycopy(bytes, offset + length - log.length, log, 0, log.length);
            logEnd = 0;
            logFull = true;
            return;
        }
        int first = Math.min(length, log.length - logEnd);
        System.arraycopy(bytes, offset, log, logEnd, first);
        System.arraycopy(bytes, offset + first, log, 0, length - first);
        logFull |= logEnd + length >= log.length;
        logEnd = (logEnd + length) % log.length;
    }

    /** The newest of what the game has printed, oldest first, starting on a whole line. */
    public static synchronized String recentLog() {
        if (!logFull) {
            return new String(log, 0, logEnd, logCharset);
        }
        byte[] ordered = new byte[log.length];
        System.arraycopy(log, logEnd, ordered, 0, log.length - logEnd);
        System.arraycopy(log, 0, ordered, log.length - logEnd, logEnd);
        String text = new String(ordered, logCharset);
        return text.substring(text.indexOf('\n') + 1);
    }

    // --- The computer -----------------------------------------------------------------------------------------

    /** Notes the graphics card. Call with the window's OpenGL context current; a crash may come when it no longer is. */
    static void readGraphics() {
        graphics = glGetString(GL_RENDERER) + " (" + glGetString(GL_VENDOR) + "), OpenGL " + glGetString(GL_VERSION);
    }

    /** The build this is: what the packaging script wrote beside the game, or "development" when run from the project. */
    public static String build() {
        try {
            return Files.readString(Path.of("build-info.txt").toAbsolutePath()).trim();
        } catch (IOException e) {
            return "development";
        }
    }

    /** A description of the computer, a line for each thing. */
    public static String computer() {
        Runtime runtime = Runtime.getRuntime();
        long usedMegabytes = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        return "System: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")\n"
                + "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n"
                + "Graphics: " + graphics + "\n"
                + "Processors: " + runtime.availableProcessors() + "\n"
                + "Memory: " + usedMegabytes + " MB of " + (runtime.maxMemory() >> 20) + " MB in use";
    }

    /** A headed part of a report's text. */
    public static String section(String title, String body) {
        return title + "\n" + "-".repeat(title.length()) + "\n" + body + "\n\n";
    }

    /** The line that opens a report: when and on which build it was made. */
    public static String stamp() {
        return "Saved: " + LocalDateTime.now().format(REPORT_TIME) + "\nBuild: " + build() + "\n\n";
    }

    // --- Writing reports ----------------------------------------------------------------------------------------

    /**
     * Writes a report as one zip: {@code report.txt} from {@code report}, {@code log.txt} from {@link #recentLog}, and
     * each attachment that exists, under its name there. A folder goes in with everything inside it.
     *
     * @param prefix what the file's name starts with
     * @return the zip
     */
    public static Path writeArchive(Path folder, String prefix, String report, Map<String, Path> attachments) throws IOException {
        Files.createDirectories(folder);
        String stamp = LocalDateTime.now().format(FILE_TIME);
        Path archive = folder.resolve(prefix + "-" + stamp + ".zip");
        for (int n = 2; Files.exists(archive); n++) {
            archive = folder.resolve(prefix + "-" + stamp + "-" + n + ".zip");
        }
        // Under another name until it is whole, so a report that stops part way is never taken for a finished one.
        Path partial = archive.resolveSibling(archive.getFileName() + ".part");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
                putText(zip, "report.txt", report);
                putText(zip, "log.txt", recentLog());
                for (Map.Entry<String, Path> attachment : attachments.entrySet()) {
                    put(zip, attachment.getKey(), attachment.getValue());
                }
            }
            Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
        return archive;
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void put(ZipOutputStream zip, String name, Path path) throws IOException {
        if (Files.isDirectory(path)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(path)) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            for (Path file : files) {
                putFile(zip, name + "/" + path.relativize(file).toString().replace('\\', '/'), file);
            }
        } else if (Files.isRegularFile(path)) {
            putFile(zip, name, path);
        }
    }

    private static void putFile(ZipOutputStream zip, String name, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    /** Adds a file or folder to every crash report, under {@code name} there, if it exists at the time. */
    public static void includeInCrashReports(String name, Path path) {
        crashFiles.put(name, path.toAbsolutePath());
    }

    /**
     * Writes a report of a crash, and leaves a note for the next run. Never throws: the game is already going down, and
     * this must not hide why.
     *
     * @param screens what was on screen, as far as is known
     */
    static void writeCrashReport(Throwable crash, String screens) {
        try {
            StringWriter trace = new StringWriter();
            crash.printStackTrace(new PrintWriter(trace));
            String report = "Crash report\n" + stamp()
                    + section("What happened", trace.toString().stripTrailing())
                    + section("Screens, bottom to top", screens)
                    + section("Computer", computer());
            Path archive = writeArchive(FOLDER, "crash", report, crashFiles);
            Files.writeString(CRASH_NOTE, archive.getFileName().toString());
            System.err.println("[Engine] Crash report saved to " + archive);
        } catch (IOException | RuntimeException e) {
            System.err.println("[Engine] Could not write a crash report: " + e);
        }
    }

    /** The name of the report the last run's crash left, once: the note is gone afterwards. Null if it didn't crash. */
    public static String takeCrashNotice() {
        try {
            if (!Files.isRegularFile(CRASH_NOTE)) {
                return null;
            }
            String name = Files.readString(CRASH_NOTE).trim();
            Files.delete(CRASH_NOTE);
            return name;
        } catch (IOException e) {
            return null;
        }
    }
}
