package org.zeronura.engine;

import org.zeronura.engine.core.Diagnostics;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.core.EngineConfig;
import org.zeronura.engine.core.Game;
import org.zeronura.engine.project.ProjectCreator;
import org.zeronura.engine.project.ProjectHub;
import org.zeronura.engine.project.ProjectInfo;
import org.zeronura.engine.project.Projects;
import org.zeronura.engine.scene.FileScene;
import org.zeronura.engine.studio.Studio;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The application entry point.
 *
 * <p>Launches, in order of preference: the project named on the command line, the default project from
 * {@code engine.properties}, or the project hub.
 *
 * <pre>
 * GameEngine                   default project, else the editor on the last project edited, else the editor on a
 *                              new blank project
 * GameEngine MyGame            a specific project
 * GameEngine MyGame --scene scenes/Level.scene
 *                              a project, starting in one of its scene files instead of its game's first scene
 * GameEngine --editor [MyGame] the editor, on a project or the one last edited
 * GameEngine --hub             the hub, ignoring the default
 * </pre>
 */
public final class GameEngine {

    private GameEngine() {
    }

    public static void main(String[] args) {
        // First, so whatever goes wrong from here is in a report's log.
        Diagnostics.captureLog();
        String projectName = null;
        String scene = null;
        boolean openHub = false;
        boolean openEditor = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--hub" -> openHub = true;
                case "--editor", "-e" -> openEditor = true;
                case "--scene" -> {
                    if (i + 1 >= args.length) {
                        usage("--scene needs a scene file, e.g. scenes/Main.scene");
                        return;
                    }
                    scene = args[++i];
                }
                case "--project", "-p" -> {
                    if (i + 1 >= args.length) {
                        usage("--project needs a name");
                        return;
                    }
                    projectName = args[++i];
                }
                case "--help", "-h" -> {
                    usage(null);
                    return;
                }
                default -> {
                    if (arg.startsWith("-") || projectName != null) {
                        usage("Unexpected argument: " + arg);
                        return;
                    }
                    projectName = arg;
                }
            }
        }

        Path root = Projects.DEFAULT_ROOT;
        EngineConfig hubConfig = EngineConfig.defaults().withTitle("Nulzs Game Engine - Project Hub");
        String problem = null;

        if (openEditor || (!openHub && projectName == null
                && Projects.activeProject(Projects.SETTINGS_FILE).isEmpty())) {
            String editing = projectName != null ? projectName : Studio.lastProject();
            Optional<ProjectInfo> project = editing == null ? Optional.empty() : Projects.find(root, editing);
            if (project.isEmpty() && projectName == null) {
                // Nothing to go back to: start the way an editor does, on a new blank project.
                project = blankProject(root);
            }
            if (project.isPresent()) {
                Engine engine = new Engine(Studio.configFor(project.get()));
                engine.setLauncher(new ProjectHub(root, hubConfig, null), hubConfig);
                engine.run(new Studio(project.get()));
                return;
            }
            if (openEditor && editing != null) {
                problem = "No project named '" + editing + "' to edit in " + root.toAbsolutePath();
            }
        }

        if (!openHub && !openEditor) {
            if (projectName == null) {
                projectName = Projects.activeProject(Projects.SETTINGS_FILE).orElse(null);
            }
            if (projectName != null) {
                Optional<ProjectInfo> project = Projects.find(root, projectName);
                if (project.isPresent()) {
                    Game game = null;
                    try {
                        game = scene != null ? sceneGame(scene) : project.get().instantiate();
                    } catch (ReflectiveOperationException | ClassCastException e) {
                        problem = "Could not start " + projectName + ": " + e;
                    }
                    if (game != null) {
                        new Engine(project.get().toConfig()).run(game);
                        return;
                    }
                } else {
                    problem = "No project named '" + projectName + "' in " + root.toAbsolutePath();
                }
                System.err.println("[GameEngine] " + problem);
            }
        }

        new Engine(hubConfig).run(new ProjectHub(root, hubConfig, problem));
    }

    /**
     * A new blank project for the editor to open when it has none to go back to: {@code MyGame}, or {@code MyGame2} and
     * so on if that name is taken. Empty if it could not be made.
     */
    private static Optional<ProjectInfo> blankProject(Path root) {
        ProjectCreator creator = ProjectCreator.forWorkingDirectory();
        for (int i = 1; i < 1000; i++) {
            String name = i == 1 ? "MyGame" : "MyGame" + i;
            if (Projects.find(root, name).isPresent() || java.nio.file.Files.exists(root.resolve(name))
                    || java.nio.file.Files.exists(Path.of("src", "main", "java",
                    ProjectCreator.packageFor(name).replace('.', '/')))) {
                continue;
            }
            try {
                ProjectInfo made = creator.createSceneProject(name, "A blank project");
                System.out.println("[GameEngine] Made a blank project, " + name + ", in " + made.directory());
                return Optional.of(made);
            } catch (java.io.IOException | IllegalArgumentException e) {
                System.err.println("[GameEngine] Could not make a blank project: " + e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** A game that is just one scene file, for trying a scene from the editor: Escape leaves it. */
    private static Game sceneGame(String path) {
        return engine -> engine.scenes().set(new FileScene(path) {
            @Override
            protected void onUpdate(float dt) {
                if (input().isKeyPressed(org.zeronura.engine.input.Key.ESCAPE) && !input().isCursorCaptured()) {
                    engine().exitToLauncher();
                }
            }
        });
    }

    private static void usage(String error) {
        if (error != null) {
            System.err.println(error);
        }
        System.err.println("Usage: GameEngine [ProjectName] [--scene scenes/Name.scene] [--editor] [--hub]");
        if (error != null) {
            System.exit(2);
        }
    }
}
