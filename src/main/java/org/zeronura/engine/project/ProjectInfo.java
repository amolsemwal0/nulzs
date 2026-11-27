package org.zeronura.engine.project;

import org.zeronura.engine.core.EngineConfig;
import org.zeronura.engine.core.Game;

import java.io.IOException;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * A game project: a folder under {@code projects/} holding everything of the project's own — {@code project.properties},
 * its {@code assets/}, and its code, a Maven project of its own that uses the engine as a library — plus the Java class
 * that starts the game.
 *
 * <pre>
 * projects/MyGame/
 *   project.properties   name, gameClass, window settings
 *   pom.xml              the project's build, depending on org.zeronura:nulzs-game-engine
 *   src/main/java/       its code (the editor compiles its scripts here, into target/classes)
 *   src/test/java/       its tests
 *   assets/              images, sounds, models, levels
 * </pre>
 *
 * <p>A project with no {@code pom.xml} of its own keeps its code in the working folder's {@code src/main/java}, as
 * projects did before they had their own builds.
 *
 * @param gameClass fully-qualified name of the project's {@link Game} implementation
 */
public record ProjectInfo(
        String name,
        Path directory,
        String gameClass,
        String description,
        int width,
        int height,
        boolean vsync,
        boolean fullscreen
) {

    public static final String FILE_NAME = "project.properties";

    public static ProjectInfo load(Path directory) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(directory.resolve(FILE_NAME))) {
            properties.load(reader);
        }
        String gameClass = properties.getProperty("gameClass", "").trim();
        if (gameClass.isEmpty()) {
            throw new IOException(directory.resolve(FILE_NAME) + " does not name a gameClass");
        }
        return new ProjectInfo(
                properties.getProperty("name", directory.getFileName().toString()).trim(),
                directory,
                gameClass,
                properties.getProperty("description", "").trim(),
                intProperty(properties, "width", EngineConfig.DEFAULT_WIDTH),
                intProperty(properties, "height", EngineConfig.DEFAULT_HEIGHT),
                Boolean.parseBoolean(properties.getProperty("vsync", "true").trim()),
                Boolean.parseBoolean(properties.getProperty("fullscreen", "false").trim()));
    }

    private static int intProperty(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Writes {@code project.properties}, creating the project folder if needed. */
    public void save() throws IOException {
        Files.createDirectories(directory);
        String contents = """
                # Nulzs Game Engine project
                name=%s
                description=%s
                # The class that starts the game. It must implement org.zeronura.engine.core.Game.
                gameClass=%s
                # Window
                width=%d
                height=%d
                vsync=%b
                fullscreen=%b
                """.formatted(escape(name), escape(description), gameClass, width, height, vsync, fullscreen);
        Files.writeString(directory.resolve(FILE_NAME), contents);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n");
    }

    public Path assetsDirectory() {
        return directory.resolve("assets");
    }

    /** Whether the project is built on its own, from a {@code pom.xml} in its folder. */
    public boolean hasOwnBuild() {
        return Files.isRegularFile(directory.resolve("pom.xml"));
    }

    /** Where the project's Java code is: its own {@code src/main/java}, else the working folder's. */
    public Path sourceRoot() {
        return hasOwnBuild() ? directory.resolve(Path.of("src", "main", "java")) : Path.of("src", "main", "java");
    }

    /** Where the project's code is compiled to: its own {@code target/classes}, else the working folder's. */
    public Path classesDirectory() {
        return hasOwnBuild() ? directory.resolve(Path.of("target", "classes")) : Path.of("target", "classes");
    }

    /**
     * Engine settings for running this project: its window, and its assets searched before the engine's. The asset
     * folders are absolute, so the image and model loaders — native code, which opens files from the process's own
     * working folder — find them however the game was started (a Windows launcher started from a shortcut elsewhere,
     * with {@code -Duser.dir} pointing at the game).
     */
    public EngineConfig toConfig() {
        return EngineConfig.defaults()
                .withTitle(name)
                .withSize(width, height)
                .withVsync(vsync)
                .withFullscreen(fullscreen)
                .withAssetRoots(List.of(assetsDirectory().toAbsolutePath(), Path.of("assets").toAbsolutePath()));
    }

    /**
     * Creates the project's game through its public no-argument constructor. The class is looked for on the classpath
     * first, then in the project's compiled classes, so a project the launcher was not started with can still be played
     * (from the hub, say) once it is compiled.
     */
    public Game instantiate() throws ReflectiveOperationException {
        Class<?> type;
        try {
            type = Class.forName(gameClass);
        } catch (ClassNotFoundException notOnClasspath) {
            Path classes = classesDirectory().toAbsolutePath();
            if (!Files.isDirectory(classes)) {
                throw new ClassNotFoundException(gameClass + " is not compiled: no " + classes, notOnClasspath);
            }
            URL url;
            try {
                url = classes.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new ClassNotFoundException(gameClass, e);
            }
            ClassLoader loader = new URLClassLoader(new URL[] {url}, ProjectInfo.class.getClassLoader());
            Thread.currentThread().setContextClassLoader(loader);
            type = Class.forName(gameClass, true, loader);
        }
        if (!Game.class.isAssignableFrom(type)) {
            throw new ClassCastException(gameClass + " does not implement " + Game.class.getName());
        }
        return (Game) type.getDeclaredConstructor().newInstance();
    }
}
