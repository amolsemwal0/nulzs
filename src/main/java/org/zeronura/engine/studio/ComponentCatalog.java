package org.zeronura.engine.studio;

import org.zeronura.engine.scene.ComponentFields;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every component the editor can add to an entity: the engine's own and the project's scripts, found by looking through
 * the compiled classes for public components with a constructor taking nothing. The engine's are read from wherever its
 * classes were loaded from, its jar when a game uses it as a library; the project's from its compiled classes. The
 * search runs on a background thread when the editor opens and again after each compile.
 */
final class ComponentCatalog {

    /** A component type, the group it is listed under in Add Component, and whether it is one of the project's scripts. */
    record Entry(Class<?> type, String name, String category, boolean script) {
    }

    /** The engine's own package, as a folder in its jar or classes folder. */
    private static final String ENGINE_PACKAGE_FOLDER = "org/zeronura/engine";

    private final Path classes;
    private final Path sourceRoot;
    private final String projectPackage;
    private volatile ScriptClassLoader loader;
    private volatile List<Entry> entries = List.of();
    private volatile boolean scanning;
    private volatile int generation;

    ComponentCatalog(Path classes, Path sourceRoot, String projectPackage) {
        this.classes = classes;
        this.sourceRoot = sourceRoot;
        this.projectPackage = projectPackage;
        this.loader = new ScriptClassLoader(classes, projectPackage, ComponentCatalog.class.getClassLoader());
    }

    ClassLoader loader() {
        return loader;
    }

    List<Entry> entries() {
        return entries;
    }

    boolean scanning() {
        return scanning;
    }

    /** Goes up by one each time the scripts are reloaded, so panels can tell their classes are stale. */
    int generation() {
        return generation;
    }

    String projectPackage() {
        return projectPackage;
    }

    Path sourceRoot() {
        return sourceRoot;
    }

    Path classes() {
        return classes;
    }

    /** The project's scripts folder: its package's folder under the sources. */
    Path scriptsFolder() {
        return sourceRoot.resolve(projectPackage.replace('.', '/'));
    }

    /** Throws away the loaded scripts and loads them afresh, as they now are on disk; then searches again. */
    void reload() {
        ScriptClassLoader old = loader;
        loader = new ScriptClassLoader(classes, projectPackage, ComponentCatalog.class.getClassLoader());
        generation++;
        ComponentFields.clearCache();
        try {
            old.close();
        } catch (IOException ignored) {
            // nothing to do
        }
        scanAsync();
    }

    void scanAsync() {
        scanning = true;
        ScriptClassLoader using = loader;
        Thread thread = new Thread(() -> {
            try {
                entries = scan(using);
            } finally {
                scanning = false;
            }
        }, "editor-component-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private List<Entry> scan(ScriptClassLoader using) {
        // Names first, from both places, so the engine's are listed once when it and the project share one folder.
        Set<String> names = new LinkedHashSet<>();
        Path engine = engineClasses();
        if (engine != null && Files.isRegularFile(engine)) {
            try (FileSystem jar = FileSystems.newFileSystem(engine)) {
                collect(jar.getPath("/"), ENGINE_PACKAGE_FOLDER, names);
            } catch (IOException | RuntimeException e) {
                System.err.println("[Editor] Could not search " + engine + " for components: " + e.getMessage());
            }
        } else if (engine != null) {
            collect(engine, ENGINE_PACKAGE_FOLDER, names);
        }
        if (!projectPackage.isEmpty()) {
            collect(classes, projectPackage.replace('.', '/'), names);
        }
        List<Entry> found = new ArrayList<>();
        for (String name : names) {
            if (name.matches(".*\\$\\d.*") || name.startsWith("org.zeronura.engine.studio.")) {
                continue;
            }
            try {
                Class<?> type = Class.forName(name, false, using);
                if (ComponentFields.isCreatable(type)) {
                    boolean script = using.isScript(name);
                    found.add(new Entry(type, type.getSimpleName(), category(type, script), script));
                }
            } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
                // a class that can't be loaded is no component anyone can add
            }
        }
        found.sort(Comparator.comparing((Entry e) -> !e.script())
                .thenComparing(Entry::category).thenComparing(Entry::name));
        return List.copyOf(found);
    }

    /** The folder or jar the engine's own classes were loaded from, or null if the JVM won't say. */
    private static Path engineClasses() {
        try {
            CodeSource source = ComponentCatalog.class.getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? null : Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return null;
        }
    }

    /** Adds the name of every class under {@code base/folder}, a package's folder in a classes folder or a jar. */
    private static void collect(Path base, String folder, Set<String> names) {
        Path start = base.resolve(folder);
        if (!Files.isDirectory(start)) {
            return;
        }
        try (Stream<Path> files = Files.walk(start)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String relative = base.relativize(file).toString().replace('\\', '/');
                names.add(relative.substring(0, relative.length() - ".class".length()).replace('/', '.'));
            }
        } catch (IOException e) {
            System.err.println("[Editor] Could not search " + start + " for components: " + e.getMessage());
        }
    }

    private String category(Class<?> type, boolean script) {
        String pkg = type.getPackageName();
        if (script) {
            String rest = pkg.length() > projectPackage.length() ? pkg.substring(projectPackage.length() + 1) : "";
            return rest.isEmpty() || rest.equals("scripts") ? "Scripts" : "Scripts/" + rest.replace('.', '/');
        }
        String engine = pkg.startsWith("org.zeronura.engine.") ? pkg.substring("org.zeronura.engine.".length()) : pkg;
        String top = engine.contains(".") ? engine.substring(0, engine.indexOf('.')) : engine;
        return switch (top) {
            case "graphics3d" -> "3D";
            case "graphics2d" -> "2D";
            case "physics2d" -> "Physics 2D";
            case "physics" -> "Physics";
            case "audio" -> "Audio";
            case "animation" -> "Animation";
            case "scene" -> "Scene";
            case "gui", "ui" -> "UI";
            case "terrain", "worldmap" -> "World";
            case "nodes" -> "Nodes";
            default -> "Engine";
        };
    }

    /** A class by name, from the scripts if it is one of them. */
    Class<?> find(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** The source file a class was compiled from, if it is under the sources; else null. */
    Path sourceOf(Class<?> type) {
        Class<?> top = type;
        while (top.getEnclosingClass() != null) {
            top = top.getEnclosingClass();
        }
        Path file = sourceRoot.resolve(top.getName().replace('.', '/') + ".java");
        return Files.isRegularFile(file) ? file : null;
    }
}
