package org.zeronura.engine.studio;

import org.zeronura.engine.core.Engine;
import org.zeronura.engine.core.EngineConfig;
import org.zeronura.engine.core.Game;
import org.zeronura.engine.gui.Gui;
import org.zeronura.engine.project.ProjectInfo;

import java.util.List;

/**
 * The engine's editor, as a game the engine runs: {@code ./engine editor [Project]}, or the editor button on the
 * Project Hub. It opens one project — its scenes, assets and scripts — and edits it; see {@link StudioScene}.
 *
 * <p>The GUI is drawn at a set size rather than fitted to a 1080p layout as games are, so a bigger window gives the
 * panels more room instead of bigger letters; Options > Interface Size changes it.
 */
public final class Studio implements Game {

    private final ProjectInfo project;
    private float previousScale = Float.NaN;

    public Studio(ProjectInfo project) {
        this.project = project;
    }

    /** The window the editor opens in for a project: its own title and settings, the project's assets to load from. */
    public static EngineConfig configFor(ProjectInfo project) {
        return EngineConfig.defaults()
                .withTitle("Nulzs Game Engine Editor")
                .withSize(1920, 1080)
                .withFullscreen(false)
                .withVsync(true)
                .withAssetRoots(project.toConfig().assetRoots());
    }

    /** The project the editor last had open, or null. */
    public static String lastProject() {
        return StudioPrefs.load().lastProject;
    }

    @Override
    public void start(Engine engine) {
        StudioPrefs prefs = StudioPrefs.load();
        previousScale = Gui.fixedScale();
        Gui.setFixedScale(prefs.uiScale);
        Gui.Style style = engine.gui().style();
        style.textScale = Theme.TEXT_SCALE;
        style.padding = 6f;
        style.radius = Theme.RADIUS;
        if (engine.window().isFullscreen() && !Boolean.getBoolean("engine.fullscreen")) {
            engine.settings().setFullscreen(false);
            engine.applySettings();
        }
        engine.window().maximize();
        engine.scenes().set(new StudioScene(project, prefs));
    }

    @Override
    public void stop(Engine engine) {
        Gui.setFixedScale(previousScale);
        Gui.Style fresh = new Gui.Style();
        Gui.Style style = engine.gui().style();
        style.textScale = fresh.textScale;
        style.padding = fresh.padding;
        style.radius = fresh.radius;
    }

    /** The asset folders the editor's engine loads from, for a project: the project's own, then the engine's. */
    static List<java.nio.file.Path> assetRoots(ProjectInfo project) {
        return project.toConfig().assetRoots();
    }
}
