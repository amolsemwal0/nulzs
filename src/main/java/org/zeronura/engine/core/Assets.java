package org.zeronura.engine.core;

import org.zeronura.engine.asset.AssetLoader;
import org.zeronura.engine.asset.Model;
import org.zeronura.engine.audio.AudioClip;
import org.zeronura.engine.render.Texture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL33.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL33.GL_REPEAT;

/**
 * Loads and caches a game's files by relative path.
 *
 * <p>Paths are searched through the configured roots in order — the project's own {@code assets/} first,
 * then the engine's — so a project can override a shared asset by placing a file at the same path.
 * Everything loaded here is released by {@link #dispose}, which the engine calls when the game closes.
 */
public final class Assets {

    private final List<Path> roots;
    private final BooleanSupplier audioAvailable;
    private final Map<String, Texture> textures = new HashMap<>();
    private final Map<String, AudioClip> sounds = new HashMap<>();
    private final Map<String, Model> models = new HashMap<>();

    public Assets(List<Path> roots, BooleanSupplier audioAvailable) {
        this.roots = List.copyOf(roots);
        this.audioAvailable = audioAvailable;
    }

    /** The first existing file for {@code path} across the roots, or the path under the first root. */
    public Path resolve(String path) {
        Path direct = Path.of(path);
        if (direct.isAbsolute()) {
            return direct;
        }
        for (Path root : roots) {
            Path candidate = root.resolve(path);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return roots.isEmpty() ? direct : roots.getFirst().resolve(path);
    }

    public boolean exists(String path) {
        return Files.exists(resolve(path));
    }

    /** An image for sprites and UI: clamped at its edges, smoothly filtered. */
    public Texture texture(String path) {
        return textures.computeIfAbsent("sprite:" + path,
                key -> Texture.fromFile(resolve(path).toString(), GL_CLAMP_TO_EDGE, true));
    }

    /** An image for pixel art: sampled nearest-neighbour so pixels stay crisp when scaled up. */
    public Texture pixelTexture(String path) {
        return textures.computeIfAbsent("pixel:" + path,
                key -> Texture.fromFile(resolve(path).toString(), GL_CLAMP_TO_EDGE, true).setNearestFiltering(true));
    }

    /** An image that tiles across a surface. */
    public Texture tilingTexture(String path) {
        return textures.computeIfAbsent("tiling:" + path,
                key -> Texture.fromFile(resolve(path).toString(), GL_REPEAT, false));
    }

    /** {@link #tilingTexture(String)}, halved on loading until no side is over {@code maxSide}. */
    public Texture tilingTexture(String path, int maxSide) {
        return textures.computeIfAbsent("tiling:" + maxSide + ":" + path,
                key -> Texture.fromFile(resolve(path).toString(), GL_REPEAT, false, maxSide));
    }

    /**
     * A sound effect (.ogg). Returns null when no audio device is open, so games can load and play sounds
     * unconditionally: {@code AudioSystem} already treats a null-free silent device as a working state.
     */
    public AudioClip sound(String path) {
        if (!audioAvailable.getAsBoolean()) {
            return null;
        }
        return sounds.computeIfAbsent(path, key -> {
            try {
                return AudioClip.load(resolve(path));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load sound " + path, e);
            }
        });
    }

    /** A 3D model (glTF, FBX, OBJ — anything Assimp reads), with materials and animations. */
    public Model model(String path) {
        return models.computeIfAbsent(path, key -> AssetLoader.loadModel(resolve(path).toString()));
    }

    /** A text file's contents, e.g. a level layout. Not cached. */
    public String text(String path) {
        try {
            return Files.readString(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }

    /**
     * Drops the images already read from a file, so the next ask reads it again: for a tool that has just saved it, as
     * the editor's Sprite Editor does. What drew the old one asks again when it sees {@link #revision} change.
     */
    public void reloadTexture(String path) {
        String suffix = ":" + path;
        textures.entrySet().removeIf(entry -> {
            if (entry.getKey().endsWith(suffix)) {
                entry.getValue().delete();
                return true;
            }
            return false;
        });
        revisions.merge(path, 1, Integer::sum);
    }

    /** How many times a file's images have been read again since the game started. */
    public int revision(String path) {
        return revisions.getOrDefault(path, 0);
    }

    private final java.util.Map<String, Integer> revisions = new java.util.HashMap<>();

    public List<Path> roots() {
        return roots;
    }

    public void dispose() {
        textures.values().forEach(Texture::delete);
        sounds.values().forEach(AudioClip::delete);
        models.values().forEach(Model::delete);
        textures.clear();
        sounds.clear();
        models.clear();
    }
}
