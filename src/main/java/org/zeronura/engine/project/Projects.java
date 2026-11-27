package org.zeronura.engine.project;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/** Finds projects on disk and remembers which one launches by default. */
public final class Projects {

    /** Where projects live, relative to the engine's working directory. */
    public static final Path DEFAULT_ROOT = Path.of("projects");

    /** Engine-wide settings, currently just the default project. */
    public static final Path SETTINGS_FILE = Path.of("engine.properties");

    private static final String ACTIVE_PROJECT = "activeProject";

    private Projects() {
    }

    /** Every readable project under {@code root}, sorted by name. Unreadable folders are reported and skipped. */
    public static List<ProjectInfo> list(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<ProjectInfo> projects = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path directory : children.sorted().toList()) {
                if (!Files.isRegularFile(directory.resolve(ProjectInfo.FILE_NAME))) {
                    continue;
                }
                try {
                    projects.add(ProjectInfo.load(directory));
                } catch (IOException e) {
                    System.err.println("[Projects] Skipping " + directory + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list projects in " + root, e);
        }
        projects.sort(Comparator.comparing(ProjectInfo::name, String.CASE_INSENSITIVE_ORDER));
        return projects;
    }

    /** A project by name or folder name, ignoring case. */
    public static Optional<ProjectInfo> find(Path root, String name) {
        return list(root).stream()
                .filter(project -> project.name().equalsIgnoreCase(name)
                        || project.directory().getFileName().toString().equalsIgnoreCase(name))
                .findFirst();
    }

    public static Optional<String> activeProject(Path settingsFile) {
        if (!Files.isRegularFile(settingsFile)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(settingsFile)) {
            properties.load(reader);
        } catch (IOException e) {
            System.err.println("[Projects] Could not read " + settingsFile + ": " + e.getMessage());
            return Optional.empty();
        }
        String value = properties.getProperty(ACTIVE_PROJECT, "").trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** Makes a project launch by default; null clears it so the hub opens instead. */
    public static void setActiveProject(Path settingsFile, String name) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(settingsFile)) {
            try (Reader reader = Files.newBufferedReader(settingsFile)) {
                properties.load(reader);
            }
        }
        if (name == null) {
            properties.remove(ACTIVE_PROJECT);
        } else {
            properties.setProperty(ACTIVE_PROJECT, name);
        }
        try (Writer writer = Files.newBufferedWriter(settingsFile)) {
            properties.store(writer, "Nulzs Game Engine settings");
        }
    }
}
