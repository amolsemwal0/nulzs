package org.zeronura.engine.project;

import org.zeronura.engine.core.EngineConfig;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creates new blank projects: the project folder with its settings, its assets, its build ({@code pom.xml}, using the
 * engine as a library) and a starter {@code Game} and scene in its own {@code src/main/java}.
 *
 * <p>A blank project is deliberately not a 2D or a 3D template. Its scene starts empty and every engine
 * feature is one call away from it, so the same starting point grows into a platformer, a card game, a
 * shooter or an open world. Command line: {@code ./engine new MyGame}.
 */
public final class ProjectCreator {

    /** Project names become Java class names, so they follow class-name rules. */
    public static final Pattern VALID_NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,47}");

    public static final String BASE_PACKAGE = "org.zeronura.games";

    private final Path projectsRoot;
    private final Path sourceRoot;

    /**
     * @param sourceRoot where every project's code goes, one shared source tree; null to give each project its own
     *                   build and code in its folder
     */
    public ProjectCreator(Path projectsRoot, Path sourceRoot) {
        this.projectsRoot = projectsRoot;
        this.sourceRoot = sourceRoot;
    }

    /** A creator making self-contained projects in {@code projects/} under the working directory. */
    public static ProjectCreator forWorkingDirectory() {
        return new ProjectCreator(Projects.DEFAULT_ROOT, null);
    }

    /** The folder a project's package goes in. */
    private Path sourceDirectory(Path projectDirectory, String packageName) {
        Path root = sourceRoot != null ? sourceRoot : projectDirectory.resolve(Path.of("src", "main", "java"));
        return root.resolve(packageName.replace('.', '/'));
    }

    /** Null if {@code name} is usable, otherwise why not. */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "Enter a project name.";
        }
        if (!VALID_NAME.matcher(name).matches()) {
            return "Use letters and digits only, starting with a capital letter (e.g. SpaceRacer).";
        }
        return null;
    }

    /** The Java package a project's code goes in. */
    public static String packageFor(String name) {
        String segment = name.toLowerCase(Locale.ROOT);
        if (SourceVersion.isKeyword(segment)) {
            segment += "game";
        }
        return BASE_PACKAGE + "." + segment;
    }

    public ProjectInfo createBlank(String name, String description) throws IOException {
        String problem = validateName(name);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        if (Projects.find(projectsRoot, name).isPresent() || Files.exists(projectsRoot.resolve(name))) {
            throw new FileAlreadyExistsException(projectsRoot.resolve(name).toString(), null,
                    "a project called " + name + " already exists");
        }
        String packageName = packageFor(name);
        Path directory = projectsRoot.resolve(name);
        Path sourceDirectory = sourceDirectory(directory, packageName);
        Path gameFile = sourceDirectory.resolve(name + "Game.java");
        Path sceneFile = sourceDirectory.resolve("MainScene.java");
        if (Files.exists(gameFile) || Files.exists(sceneFile)) {
            throw new FileAlreadyExistsException(sourceDirectory.toString(), null, "source for " + name + " already exists");
        }

        ProjectInfo project = new ProjectInfo(name, directory, packageName + "." + name + "Game",
                description == null ? "" : description, EngineConfig.DEFAULT_WIDTH, EngineConfig.DEFAULT_HEIGHT,
                true, false);
        project.save();
        Files.createDirectories(project.assetsDirectory());
        Files.writeString(project.assetsDirectory().resolve("README.txt"), """
                Put this project's images, sounds, models and level files here.
                Load them from a scene with assets(), e.g. assets().texture("player.png").
                """);

        if (sourceRoot == null) {
            Files.writeString(directory.resolve("pom.xml"), pomSource(name));
            Files.writeString(directory.resolve(".gitignore"), "target/\n");
        }
        Files.createDirectories(sourceDirectory);
        Files.writeString(gameFile, gameSource(packageName, name));
        Files.writeString(sceneFile, sceneSource(packageName, name));
        return project;
    }

    /** The engine's version, as its jar records it; the version a new project's build asks for. */
    static String engineVersion() {
        try (var in = ProjectCreator.class.getResourceAsStream(
                "/META-INF/maven/org.zeronura/nulzs-game-engine/pom.properties")) {
            if (in != null) {
                java.util.Properties properties = new java.util.Properties();
                properties.load(in);
                return properties.getProperty("version", DEFAULT_ENGINE_VERSION);
            }
        } catch (IOException ignored) {
            // the default below
        }
        return DEFAULT_ENGINE_VERSION;
    }

    private static final String DEFAULT_ENGINE_VERSION = "1.0-SNAPSHOT";

    /** A project's own build: its code against the engine, as a library, and its tests run from the engine's folder. */
    static String pomSource(String name) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <!-- %1$s, made with Nulzs Game Engine. Everything of the project's own is in this folder: its code
                         (src/main/java), tests (src/test/java), project.properties and assets/. The engine is a library,
                         installed from the engine's folder two levels up with `mvn install`. -->
                    <groupId>org.zeronura.games</groupId>
                    <artifactId>%2$s</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <name>%1$s</name>

                    <properties>
                        <maven.compiler.source>26</maven.compiler.source>
                        <maven.compiler.target>26</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <nulzs.engine.version>%3$s</nulzs.engine.version>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.zeronura</groupId>
                            <artifactId>nulzs-game-engine</artifactId>
                            <version>${nulzs.engine.version}</version>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.4</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.5.2</version>
                                <configuration>
                                    <argLine>--enable-native-access=ALL-UNNAMED</argLine>
                                    <!-- The game runs from the engine's folder (its paths start projects/%1$s/...), and
                                         so do its tests. -->
                                    <workingDirectory>${project.basedir}/../..</workingDirectory>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(name, name.toLowerCase(Locale.ROOT), engineVersion());
    }

    /**
     * A blank project made to be built in the editor: its game starts the scene file {@code scenes/Main.scene}, which
     * is made with a camera, a floor and a cube, and its scripts go in a {@code scripts} package beside the game class.
     */
    public ProjectInfo createSceneProject(String name, String description) throws IOException {
        ProjectInfo project = createBlank(name, description);
        String packageName = packageFor(name);
        Path sourceDirectory = sourceDirectory(project.directory(), packageName);
        Files.writeString(sourceDirectory.resolve(name + "Game.java"), sceneGameSource(packageName, name));
        Files.deleteIfExists(sourceDirectory.resolve("MainScene.java"));
        Files.createDirectories(sourceDirectory.resolve("scripts"));
        Path scenes = project.assetsDirectory().resolve("scenes");
        Files.createDirectories(scenes);
        try (var in = ProjectCreator.class.getResourceAsStream("/templates/Main.scene")) {
            if (in != null) {
                Files.write(scenes.resolve("Main.scene"), in.readAllBytes());
            }
        }
        return project;
    }

    static String sceneGameSource(String packageName, String name) {
        return """
                package %1$s;

                import org.zeronura.engine.core.Engine;
                import org.zeronura.engine.core.Game;
                import org.zeronura.engine.scene.FileScene;

                /**
                 * Starts %2$s with the scene made in the editor. The engine creates this class by name from
                 * projects/%2$s/project.properties.
                 */
                public final class %2$sGame implements Game {

                    @Override
                    public void start(Engine engine) {
                        engine.scenes().set(new FileScene("scenes/Main.scene"));
                    }
                }
                """.formatted(packageName, name);
    }

    static String gameSource(String packageName, String name) {
        return """
                package %1$s;

                import org.zeronura.engine.core.Engine;
                import org.zeronura.engine.core.Game;

                /** Starts %2$s. The engine creates this class by name from projects/%2$s/project.properties. */
                public final class %2$sGame implements Game {

                    @Override
                    public void start(Engine engine) {
                        engine.scenes().set(new MainScene());
                    }
                }
                """.formatted(packageName, name);
    }

    static String sceneSource(String packageName, String name) {
        return """
                package %1$s;

                import org.zeronura.engine.graphics2d.Color;
                import org.zeronura.engine.gui.Gui;
                import org.zeronura.engine.input.Key;
                import org.zeronura.engine.scene.Scene;
                import org.zeronura.engine.ui.Rect;

                /**
                 * The first scene of %2$s. It starts empty: build any kind of game from here.
                 *
                 * <ul>
                 *   <li>2D: createEntity("player", 200, 200).add(new SpriteRenderer(Color.GREEN, 64, 64));
                 *       add Body2D for physics, TilemapRenderer for levels, or draw directly in onDraw(SpriteBatch).</li>
                 *   <li>3D: world3D().setGroundPlane(0f), then entities with
                 *       new MeshRenderer(world3D().own(Primitives.cube(1f, Color.RED))); add CharacterController3D with
                 *       FirstPersonController or ThirdPersonController for a player, or world3D().setTerrain(...)
                 *       for an open world.</li>
                 *   <li>UI: buttons, text and panels in onGui(Gui).</li>
                 * </ul>
                 *
                 * The engine's README.md walks through each kind of game.
                 */
                public class MainScene extends Scene {

                    @Override
                    protected void onStart() {
                        setBackgroundColor(Color.rgb(0x1B2330));
                    }

                    @Override
                    protected void onUpdate(float dt) {
                        if (input().isKeyPressed(Key.ESCAPE)) {
                            engine().exitToLauncher();
                        }
                    }

                    @Override
                    protected void onGui(Gui gui) {
                        gui.textCentered("%2$s", new Rect(0f, gui.height() * 0.42f, gui.width(), 60f), 7f, Color.WHITE);
                        gui.textCentered("Blank project - edit MainScene.java to start building",
                                new Rect(0f, gui.height() * 0.52f, gui.width(), 40f), 3f, gui.style().mutedText);
                    }
                }
                """.formatted(packageName, name);
    }

    /** {@code ProjectCreator <Name> [description...]} */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: ./engine new <ProjectName> [description]");
            System.exit(2);
        }
        String name = args[0];
        String description = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        try {
            ProjectInfo project = forWorkingDirectory().createBlank(name, description);
            Path source = project.sourceRoot().resolve(packageFor(name).replace('.', '/'));
            System.out.println("Created blank project " + project.name());
            System.out.println("  settings: " + project.directory().resolve(ProjectInfo.FILE_NAME));
            System.out.println("  assets:   " + project.assetsDirectory());
            System.out.println("  code:     " + source.resolve(name + "Game.java"));
            System.out.println("            " + source.resolve("MainScene.java"));
            System.out.println();
            System.out.println("Run it with: ./engine run " + project.name());
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Could not create project: " + e.getMessage());
            System.exit(1);
        }
    }
}
