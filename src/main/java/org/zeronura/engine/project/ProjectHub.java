package org.zeronura.engine.project;

import org.zeronura.engine.core.Engine;
import org.zeronura.engine.core.EngineConfig;
import org.zeronura.engine.core.Game;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.gui.Gui;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.ui.Rect;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The screen shown when the engine starts without a project: lists projects to play, creates new blank
 * projects, and sets which project launches by default. Games started from here return to it through
 * {@link Engine#exitToLauncher()}.
 */
public final class ProjectHub implements Game {

    private final Path projectsRoot;
    private final EngineConfig hubConfig;
    private final String initialMessage;

    public ProjectHub(Path projectsRoot, EngineConfig hubConfig, String initialMessage) {
        this.projectsRoot = projectsRoot;
        this.hubConfig = hubConfig;
        this.initialMessage = initialMessage;
    }

    @Override
    public void start(Engine engine) {
        engine.setLauncher(this, hubConfig);
        engine.scenes().set(new HubScene());
    }

    private final class HubScene extends Scene {

        private static final Color ERROR = Color.rgb(0xF07178);
        private static final Color SUCCESS = Color.rgb(0xA3D977);

        private List<ProjectInfo> projects = List.of();
        private String defaultProject;
        private String newName = "";
        private String status;
        private Color statusColor = ERROR;
        private float scroll;

        @Override
        protected void onStart() {
            setBackgroundColor(Color.rgb(0x12161D));
            status = initialMessage;
            refresh();
        }

        private void refresh() {
            projects = Projects.list(projectsRoot);
            defaultProject = Projects.activeProject(Projects.SETTINGS_FILE).orElse(null);
        }

        @Override
        protected void onUpdate(float dt) {
            if (input().isKeyPressed(Key.ESCAPE) && !engine().gui().hasKeyboardFocus()) {
                engine().quit();
            }
        }

        @Override
        protected void onGui(Gui gui) {
            float width = gui.width();
            float height = gui.height();
            float margin = Math.max(40f, width * 0.04f);
            Gui.Style style = gui.style();

            gui.text("Nulzs Game Engine", margin, margin, 8f, Color.WHITE);
            gui.text("Project Hub", margin, margin + gui.textHeight(8f) + 20f, 3f, style.mutedText);

            float top = margin + 170f;
            float listWidth = width * 0.58f - margin;
            Rect listPanel = new Rect(margin, top, listWidth, height - top - margin);
            gui.panel(listPanel);
            drawProjectList(gui, listPanel);

            float sideX = margin + listWidth + 32f;
            float sideWidth = width - sideX - margin;
            Rect createPanel = new Rect(sideX, top, sideWidth, 340f);
            gui.panel(createPanel);
            drawCreatePanel(gui, createPanel);

            Rect helpPanel = new Rect(sideX, top + 372f, sideWidth, height - top - 372f - margin);
            gui.panel(helpPanel);
            drawHelp(gui, helpPanel);
        }

        private void drawProjectList(Gui gui, Rect panel) {
            Gui.Style style = gui.style();
            float pad = style.padding * 1.5f;
            gui.text("Projects", panel.x() + pad, panel.y() + pad, 4f, Color.WHITE);
            if (gui.button("Refresh", new Rect(panel.x() + panel.width() - pad - 180f, panel.y() + pad - 6f, 180f, 52f))) {
                refresh();
                status = null;
            }

            if (projects.isEmpty()) {
                gui.text("No projects yet. Create one on the right.", panel.x() + pad, panel.y() + 110f, 3f, style.mutedText);
                return;
            }

            float rowHeight = 112f;
            float listTop = panel.y() + 100f;
            float visibleHeight = panel.height() - 110f;
            int visibleRows = Math.max(1, (int) (visibleHeight / rowHeight));
            if (gui.hoveredButton(panel)) {
                scroll -= input().scroll();
            }
            scroll = Math.max(0f, Math.min(Math.max(0, projects.size() - visibleRows), scroll));
            int first = (int) scroll;

            for (int i = first; i < Math.min(projects.size(), first + visibleRows); i++) {
                ProjectInfo project = projects.get(i);
                Rect row = new Rect(panel.x() + pad, listTop + (i - first) * rowHeight, panel.width() - 2f * pad, rowHeight - 12f);
                gui.rect(row, Color.rgb(0x1C222C));
                boolean isDefault = project.name().equalsIgnoreCase(defaultProject);
                gui.text(project.name() + (isDefault ? "  (default)" : ""), row.x() + 20f, row.y() + 18f, 3.5f, Color.WHITE);
                String description = project.description().isEmpty() ? project.gameClass() : project.description();
                gui.text(fit(gui, description, row.width() - 670f, 2f), row.x() + 20f, row.y() + 62f, 2f, style.mutedText);

                float buttonY = row.y() + (row.height() - 56f) * 0.5f;
                if (gui.button("play:" + project.name(), "Play", new Rect(row.x() + row.width() - 200f, buttonY, 180f, 56f))) {
                    play(project);
                }
                if (gui.button("edit:" + project.name(), "Edit", new Rect(row.x() + row.width() - 600f, buttonY, 180f, 56f))) {
                    engine().switchGame(new org.zeronura.engine.studio.Studio(project),
                            org.zeronura.engine.studio.Studio.configFor(project));
                }
                String defaultLabel = isDefault ? "Unset" : "Default";
                if (gui.button("default:" + project.name(), defaultLabel, new Rect(row.x() + row.width() - 400f, buttonY, 180f, 56f))) {
                    toggleDefault(project, isDefault);
                }
            }
        }

        private void drawCreatePanel(Gui gui, Rect panel) {
            Gui.Style style = gui.style();
            float pad = style.padding * 1.5f;
            gui.text("New blank project", panel.x() + pad, panel.y() + pad, 4f, Color.WHITE);
            gui.text("A blank project can grow into any 2D or 3D game.", panel.x() + pad, panel.y() + pad + 56f, 2f, style.mutedText);

            Rect field = new Rect(panel.x() + pad, panel.y() + 130f, panel.width() - 2f * pad, 64f);
            newName = gui.textField("new-project", field, newName, 48);
            if (newName.isEmpty() && !gui.hasKeyboardFocus()) {
                gui.text("ProjectName", field.x() + style.padding, field.centerY() - gui.textHeight(3f) * 0.5f, 3f,
                        style.mutedText.withAlpha(0.5f));
            }
            if (gui.button("Create project", new Rect(panel.x() + pad, panel.y() + 214f, panel.width() - 2f * pad, 64f))) {
                create();
            }
            if (status != null) {
                gui.text(fit(gui, status, panel.width() - 2f * pad, 2f), panel.x() + pad, panel.y() + 306f, 2f, statusColor);
            }
        }

        private void drawHelp(Gui gui, Rect panel) {
            Gui.Style style = gui.style();
            float pad = style.padding * 1.5f;
            String[] lines = {
                    "From a terminal in the games' folder:",
                    "",
                    "./engine editor MyGame   open it in the editor",
                    "./engine new MyGame      create a blank project",
                    "./engine run MyGame      compile and play it",
                    "./engine list            list projects",
                    "./engine default MyGame  launch it by default",
                    "./engine hub             open this hub",
                    "",
                    "In any game: F3 stats, F11 fullscreen,",
                    "F12 screenshot, Esc to leave.",
            };
            gui.text("Command line", panel.x() + pad, panel.y() + pad, 4f, Color.WHITE);
            float y = panel.y() + pad + 70f;
            for (String line : lines) {
                gui.text(fit(gui, line, panel.width() - 2f * pad, 2f), panel.x() + pad, y, 2f, style.mutedText);
                y += 28f;
            }
        }

        private void play(ProjectInfo project) {
            try {
                engine().switchGame(project.instantiate(), project.toConfig());
            } catch (ClassNotFoundException e) {
                report("'" + project.name() + "' is not compiled yet. Run: ./engine run " + project.name(), false);
            } catch (ReflectiveOperationException | ClassCastException e) {
                report("Could not start " + project.name() + ": " + e.getMessage(), false);
            }
        }

        private void toggleDefault(ProjectInfo project, boolean isDefault) {
            try {
                Projects.setActiveProject(Projects.SETTINGS_FILE, isDefault ? null : project.name());
                refresh();
                report(isDefault ? "The hub will open on launch." : project.name() + " will launch by default.", true);
            } catch (IOException e) {
                report("Could not save the default project: " + e.getMessage(), false);
            }
        }

        private void create() {
            String problem = ProjectCreator.validateName(newName);
            if (problem != null) {
                report(problem, false);
                return;
            }
            try {
                ProjectInfo project = ProjectCreator.forWorkingDirectory().createBlank(newName, "");
                report("Created " + project.name() + ". Compile and open it with: ./engine run " + project.name(), true);
                newName = "";
                refresh();
            } catch (IOException | IllegalArgumentException e) {
                report("Could not create project: " + e.getMessage(), false);
            }
        }

        private void report(String message, boolean success) {
            status = message;
            statusColor = success ? SUCCESS : ERROR;
        }

        /** Truncates text with an ellipsis to fit a width. */
        private static String fit(Gui gui, String text, float maxWidth, float scale) {
            if (gui.textWidth(text, scale) <= maxWidth) {
                return text;
            }
            String trimmed = text;
            while (!trimmed.isEmpty() && gui.textWidth(trimmed + "...", scale) > maxWidth) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed + "...";
        }
    }
}
