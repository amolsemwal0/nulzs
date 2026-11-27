package org.zeronura.nulzs.studio;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Compiles a project's scripts inside the editor with the JDK's own compiler: only the source files changed since they
 * were last compiled (and whatever they need that is out of date), into the same classes folder Maven uses, so a script
 * is ready a second or two after it is saved.
 */
final class ScriptCompiler {

    record Problem(Path file, long line, long column, String message, boolean error) {
    }

    record Result(boolean ok, List<Problem> problems, int files, long millis, String note) {
    }

    private ScriptCompiler() {
    }

    static boolean available() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    /** The source files under {@code folder} newer than their compiled classes, or never compiled. */
    static List<Path> stale(Path folder, Path sourceRoot, Path classes) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path source : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Path relative = sourceRoot.relativize(source);
                String name = relative.toString();
                Path compiled = classes.resolve(name.substring(0, name.length() - ".java".length()) + ".class");
                FileTime sourceTime = Files.getLastModifiedTime(source);
                if (!Files.exists(compiled) || Files.getLastModifiedTime(compiled).compareTo(sourceTime) < 0) {
                    out.add(source);
                }
            }
        } catch (IOException e) {
            System.err.println("[Editor] Could not look for changed scripts: " + e.getMessage());
        }
        return out;
    }

    static Result compile(List<Path> files, Path sourceRoot, Path classes, String classpath) {
        long start = System.nanoTime();
        if (files.isEmpty()) {
            return new Result(true, List.of(), 0, 0, "Scripts are up to date.");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new Result(false, List.of(), 0, 0,
                    "No Java compiler in this runtime: run the editor on a JDK, or use Build > Full Build (Maven).");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<Problem> problems = new ArrayList<>();
        boolean ok;
        StringWriter output = new StringWriter();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            Files.createDirectories(classes);
            List<String> options = List.of(
                    "-d", classes.toString(),
                    // The project's classes compiled before, which need not be on the editor's own classpath.
                    "-cp", classes + java.io.File.pathSeparator + classpath,
                    "-sourcepath", sourceRoot.toString(),
                    "--release", Integer.toString(Runtime.version().feature()),
                    "-encoding", "UTF-8",
                    "-g",
                    "-proc:none",
                    "-implicit:class");
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(files);
            ok = compiler.getTask(output, manager, diagnostics, options, null, units).call();
        } catch (IOException | RuntimeException e) {
            return new Result(false, List.of(), files.size(), 0, "The compiler failed: " + e);
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.NOTE) {
                continue;
            }
            Path file = null;
            if (d.getSource() != null) {
                try {
                    file = Path.of(d.getSource().toUri());
                } catch (RuntimeException ignored) {
                    // not a file
                }
            }
            problems.add(new Problem(file, d.getLineNumber(), d.getColumnNumber(), d.getMessage(Locale.ROOT),
                    d.getKind() == Diagnostic.Kind.ERROR));
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        String extra = output.toString().trim();
        return new Result(ok, problems, files.size(), millis, extra.isEmpty() ? null : extra);
    }
}
