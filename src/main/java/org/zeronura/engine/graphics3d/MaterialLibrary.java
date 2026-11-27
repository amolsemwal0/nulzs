package org.zeronura.engine.graphics3d;

import org.zeronura.engine.core.Assets;
import org.zeronura.engine.nodes.NodePackage;
import org.zeronura.engine.nodes.render.NodeMaterial;
import org.zeronura.engine.nodes.render.NodeMaterials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Materials made of shader nodes — in the editor's Shader Tool, or exported from Blender — by asset path, compiled once
 * and shared. A file changed on disk is read again the next time it is asked for (checked at most once a second), so a
 * material saved in the Shader Tool shows on every shape wearing it straight away.
 */
public final class MaterialLibrary {

    private record Loaded(NodeMaterials materials, NodeMaterial material, FileTime modified, long checked, String problem) {
    }

    private static final Map<Assets, Map<String, Loaded>> LOADED = new WeakHashMap<>();

    private MaterialLibrary() {
    }

    /**
     * The material in a node package file, by asset path: the one named {@code name}, or its first when the name is
     * empty. Null if it can't be had; {@link #problem} says why.
     */
    public static synchronized NodeMaterial get(Assets assets, String path, String name) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Map<String, Loaded> byPath = LOADED.computeIfAbsent(assets, a -> new HashMap<>());
        String key = path.trim() + "#" + (name == null ? "" : name);
        Loaded loaded = byPath.get(key);
        long now = System.nanoTime();
        if (loaded != null && now - loaded.checked() < 1_000_000_000L) {
            return loaded.material();
        }
        Path file = assets.resolve(path.trim());
        FileTime modified = null;
        try {
            modified = Files.getLastModifiedTime(file);
        } catch (IOException missing) {
            // reported below
        }
        if (loaded != null && modified != null && modified.equals(loaded.modified())) {
            byPath.put(key, new Loaded(loaded.materials(), loaded.material(), modified, now, loaded.problem()));
            return loaded.material();
        }
        if (loaded != null && loaded.materials() != null) {
            loaded.materials().delete();
        }
        if (modified == null) {
            byPath.put(key, new Loaded(null, null, null, now, "No material file at " + file));
            return null;
        }
        try {
            NodePackage pkg = NodePackage.load(file);
            NodeMaterials materials = new NodeMaterials(pkg);
            String which = name == null || name.isBlank()
                    ? pkg.materials().keySet().stream().findFirst().orElse("") : name;
            NodeMaterial material = materials.get(which);
            byPath.put(key, new Loaded(materials, material, modified, now, null));
            return material;
        } catch (RuntimeException e) {
            System.err.println("[MaterialLibrary] Could not load " + path + ": " + e.getMessage());
            byPath.put(key, new Loaded(null, null, modified, now, e.getMessage()));
            return null;
        }
    }

    /** Why the last {@link #get} of a path gave nothing, or null. */
    public static synchronized String problem(Assets assets, String path, String name) {
        Map<String, Loaded> byPath = LOADED.get(assets);
        Loaded loaded = byPath == null ? null : byPath.get(path.trim() + "#" + (name == null ? "" : name));
        return loaded == null ? null : loaded.problem();
    }

    /** Makes the next {@link #get} of a path read it again at once, as a tool that has just saved it wants. */
    public static synchronized void changed(String path) {
        for (Map<String, Loaded> byPath : LOADED.values()) {
            byPath.replaceAll((key, loaded) -> key.startsWith(path.trim() + "#")
                    ? new Loaded(loaded.materials(), loaded.material(), null, 0L, loaded.problem()) : loaded);
        }
    }
}
