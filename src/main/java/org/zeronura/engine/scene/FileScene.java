package org.zeronura.engine.scene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A scene built from a scene file made in the editor: its entities, their components and settings, and the signals
 * wired between them. The path is an asset path, so {@code new FileScene("scenes/Main.scene")} finds
 * {@code projects/MyGame/assets/scenes/Main.scene}.
 *
 * <pre>{@code
 * public void start(Engine engine) {
 *     engine.scenes().set(new FileScene("scenes/Main.scene"));
 * }
 * }</pre>
 *
 * <p>Subclass it to add a scene's own logic on top of what the file holds; call {@code super.onStart()} first, then
 * find what was loaded with {@link #find} or {@link #entityById}.
 */
public class FileScene extends Scene {

    private final String path;
    private SceneFile.Loaded loaded;

    public FileScene(String path) {
        this.path = path;
    }

    /** The asset path the scene is read from. */
    public String path() {
        return path;
    }

    @Override
    protected void onStart() {
        Path file = assets().resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("No scene file at " + file.toAbsolutePath());
        }
        Map<String, Object> json;
        try {
            json = SceneFile.read(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the scene " + file, e);
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        loaded = SceneFile.instantiate(json, this, loader != null ? loader : FileScene.class.getClassLoader());
        for (String problem : loaded.problems()) {
            System.err.println("[FileScene] " + path + ": " + problem);
        }
    }

    /** The entity saved with this id, or null. Ids are shown in the editor's Inspector. */
    public Entity entityById(String id) {
        return loaded == null ? null : loaded.byId().get(id);
    }

    /** The scene's settings as loaded. */
    public SceneSettings settings() {
        return loaded == null ? null : loaded.settings();
    }
}
