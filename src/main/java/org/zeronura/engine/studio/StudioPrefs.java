package org.zeronura.engine.studio;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The editor's own settings, kept between runs: the layout, the last project and scene, tools and options. */
final class StudioPrefs {

    static final Path FILE = Path.of("saves", "settings", "editor-preferences.properties");

    float uiScale = 1f;
    String lastProject;
    float leftWidth = 270f;
    float rightWidth = 360f;
    float bottomHeight = 270f;
    boolean hierarchyVisible = true;
    boolean inspectorVisible = true;
    boolean bottomVisible = true;
    float cameraSpeed = 8f;
    boolean compileOnSave = true;
    boolean consoleOnErrors = true;
    boolean localSpace;
    boolean snap;
    float moveSnap = 0.5f;
    float rotateSnap = 15f;
    float scaleSnap = 0.1f;
    private final Properties extra = new Properties();

    static StudioPrefs load() {
        StudioPrefs prefs = new StudioPrefs();
        if (!Files.isRegularFile(FILE)) {
            return prefs;
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            p.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            return prefs;
        }
        prefs.uiScale = number(p, "uiScale", prefs.uiScale);
        prefs.lastProject = p.getProperty("lastProject");
        prefs.leftWidth = number(p, "leftWidth", prefs.leftWidth);
        prefs.rightWidth = number(p, "rightWidth", prefs.rightWidth);
        prefs.bottomHeight = number(p, "bottomHeight", prefs.bottomHeight);
        prefs.hierarchyVisible = flag(p, "hierarchyVisible", true);
        prefs.inspectorVisible = flag(p, "inspectorVisible", true);
        prefs.bottomVisible = flag(p, "bottomVisible", true);
        prefs.cameraSpeed = number(p, "cameraSpeed", prefs.cameraSpeed);
        prefs.compileOnSave = flag(p, "compileOnSave", true);
        prefs.consoleOnErrors = flag(p, "consoleOnErrors", true);
        prefs.localSpace = flag(p, "localSpace", false);
        prefs.snap = flag(p, "snap", false);
        prefs.moveSnap = number(p, "moveSnap", prefs.moveSnap);
        prefs.rotateSnap = number(p, "rotateSnap", prefs.rotateSnap);
        prefs.scaleSnap = number(p, "scaleSnap", prefs.scaleSnap);
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("scene.")) {
                prefs.extra.setProperty(key, p.getProperty(key));
            }
        }
        return prefs;
    }

    void save() {
        Properties p = new Properties();
        p.putAll(extra);
        p.setProperty("uiScale", Float.toString(uiScale));
        if (lastProject != null) {
            p.setProperty("lastProject", lastProject);
        }
        p.setProperty("leftWidth", Float.toString(leftWidth));
        p.setProperty("rightWidth", Float.toString(rightWidth));
        p.setProperty("bottomHeight", Float.toString(bottomHeight));
        p.setProperty("hierarchyVisible", Boolean.toString(hierarchyVisible));
        p.setProperty("inspectorVisible", Boolean.toString(inspectorVisible));
        p.setProperty("bottomVisible", Boolean.toString(bottomVisible));
        p.setProperty("cameraSpeed", Float.toString(cameraSpeed));
        p.setProperty("compileOnSave", Boolean.toString(compileOnSave));
        p.setProperty("consoleOnErrors", Boolean.toString(consoleOnErrors));
        p.setProperty("localSpace", Boolean.toString(localSpace));
        p.setProperty("snap", Boolean.toString(snap));
        p.setProperty("moveSnap", Float.toString(moveSnap));
        p.setProperty("rotateSnap", Float.toString(rotateSnap));
        p.setProperty("scaleSnap", Float.toString(scaleSnap));
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                p.store(writer, "Nulzs Game Engine editor");
            }
        } catch (IOException e) {
            System.err.println("[Editor] Could not save the editor's settings: " + e.getMessage());
        }
    }

    /** The scene last open in a project, as a path relative to the engine folder. */
    String lastScene(String project) {
        return extra.getProperty("scene." + project);
    }

    void setLastScene(String project, String scene) {
        if (scene == null) {
            extra.remove("scene." + project);
        } else {
            extra.setProperty("scene." + project, scene);
        }
    }

    private static float number(Properties p, String key, float fallback) {
        try {
            return Float.parseFloat(p.getProperty(key, Float.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean flag(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
