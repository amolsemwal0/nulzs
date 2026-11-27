package org.zeronura.engine.studio;

import org.zeronura.engine.graphics2d.Color;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** What sort of file something in the Project panel is, by its extension: how it is pictured and what opening it does. */
enum AssetKind {
    FOLDER(Icon.FOLDER, "Folder", Color.rgb(0xD9B25F)),
    SCENE(Icon.SCENE, "Scene", Color.rgb(0x7FB7FF)),
    SCRIPT(Icon.SCRIPT, "Java script", Color.rgb(0xF0A15C)),
    MATERIAL(Icon.SHADER, "Material", Color.rgb(0xE07FD0)),
    GEOMETRY(Icon.GEOMETRY, "Geometry nodes", Color.rgb(0x3FD6A6)),
    PRESET(Icon.TREE, "Preset", Color.rgb(0x9BD36A)),
    IMAGE(Icon.IMAGE, "Image", Color.rgb(0x8FD18A)),
    MODEL(Icon.MODEL, "3D model", Color.rgb(0x6FD3D8)),
    AUDIO(Icon.AUDIO, "Sound", Color.rgb(0xE58AD6)),
    FONT(Icon.FONT, "Font", Color.rgb(0xC6C9D1)),
    SHADER(Icon.SCRIPT, "Shader", Color.rgb(0xB99CF2)),
    DATA(Icon.TEXT, "Data", Color.rgb(0xB8C0CC)),
    TEXT(Icon.TEXT, "Text", Color.rgb(0xB8C0CC)),
    OTHER(Icon.FILE, "File", Color.rgb(0x8E929A));

    private final Icon icon;
    private final String label;
    private final Color tint;

    AssetKind(Icon icon, String label, Color tint) {
        this.icon = icon;
        this.label = label;
        this.tint = tint;
    }

    Icon icon() {
        return icon;
    }

    String label() {
        return label;
    }

    Color tint() {
        return tint;
    }

    static AssetKind of(Path path) {
        if (Files.isDirectory(path)) {
            return FOLDER;
        }
        if (path.getFileName() != null && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".preset.json")) {
            return PRESET;
        }
        if (path.getFileName() != null && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nodes.json")) {
            return holdsGeometry(path) ? GEOMETRY : MATERIAL;
        }
        String extension = extension(path);
        return switch (extension) {
            case "scene" -> SCENE;
            case "java" -> SCRIPT;
            case "png", "jpg", "jpeg", "bmp", "tga", "gif", "hdr", "psd" -> IMAGE;
            case "glb", "gltf", "fbx", "obj", "dae", "blend", "3ds", "ply", "stl" -> MODEL;
            case "ogg", "wav", "mp3", "flac" -> AUDIO;
            case "ttf", "otf" -> FONT;
            case "glsl", "vert", "frag", "geom", "comp" -> SHADER;
            case "json", "properties", "xml", "csv", "yaml", "yml", "toml", "ini" -> DATA;
            case "txt", "md", "sh", "py", "gradle", "kts", "jsx" -> TEXT;
            default -> OTHER;
        };
    }

    /** What a node package was last found to hold, by file and when it last changed, so it is read once. */
    private static final java.util.Map<Path, Object[]> PACKAGES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether a node package is a Geometry Nodes Tool tree rather than a material: it says so near its top (the Tool
     * writes a {@code "geometry"} entry before its trees), so only the first few kilobytes are read.
     */
    static boolean holdsGeometry(Path path) {
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            Object[] known = PACKAGES.get(path);
            if (known != null && (long) known[0] == modified) {
                return (boolean) known[1];
            }
            byte[] head;
            try (java.io.InputStream in = Files.newInputStream(path)) {
                head = in.readNBytes(16384);
            }
            String text = new String(head, java.nio.charset.StandardCharsets.UTF_8);
            boolean geometry = text.contains("\"geometry\": {") || text.contains("\"geometry\":{")
                    || text.contains("\"Nulzs Game Engine Geometry Nodes Tool\"")
                    || text.contains("\"GameEngine Geometry Nodes Tool\""); // written before the rename
            PACKAGES.put(path, new Object[]{modified, geometry});
            return geometry;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    static String extension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Whether the code editor can open it. */
    boolean editable() {
        return this == SCRIPT || this == SHADER || this == DATA || this == TEXT || this == SCENE;
    }
}
