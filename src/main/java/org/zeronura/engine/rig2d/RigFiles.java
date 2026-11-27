package org.zeronura.engine.rig2d;

import org.zeronura.engine.asset.Picture;
import org.zeronura.engine.data.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes rigs: a {@code .rig.json} file holding everything but the pictures, beside the layered file it
 * names in {@code "art"} (a {@code .psd}, or a single {@code .png} for a rig of one layer).
 *
 * <pre>
 * { "rig2d": 1, "art": "front.psd", "canvas": [512, 512], "pivot": [256, 470], "facing": -1,
 *   "parameters": [ {"id": "Breath", "min": 0, "max": 1, "default": 0}, ... ],
 *   "deformers":  [ {"id": "Body", "type": "warp", "parent": null, "rect": [x, y, w, h], "grid": [4, 4],
 *                    "keyforms": [ {"parameters": ["Breath"], "keys": [[0, 1]], "forms": [[...], [...]]} ]},
 *                   {"id": "Jaw", "type": "rotation", "parent": "Body", "origin": [x, y], "keyforms": [...]} ],
 *   "meshes":     [ {"id": "Jaw", "layer": "Head/Jaw", "parent": "Jaw", "order": 30,
 *                    "vertices": [...], "triangles": [...], "keyforms": [...]} ],
 *   "physics":    [ {"id": "Tail", "output": "Sway Tail", "gain": 1.3, "stiffness": 60, "damping": 4.5,
 *                    "inputs": [ {"parameter": "BodyAngleX", "weight": 0.8} ]} ],
 *   "motions":    [ {"id": "idle", "duration": 3.2, "loop": true, "fadeIn": 0.15, "fadeOut": 0.25,
 *                    "curves": [ {"parameter": "Breath", "times": [0, 1.6], "values": [0, 1]} ]} ] }
 * </pre>
 * A keyform's numbers are what its owner says (see {@link Deformer}, {@link ArtMesh}), as offsets from rest.
 */
public final class RigFiles {

    public static final String EXTENSION = ".rig.json";

    private RigFiles() {
    }

    /** Reads a rig file and the art it names. */
    public static RigModel load(Path rigFile) throws IOException {
        String text = Files.readString(rigFile, StandardCharsets.UTF_8);
        Map<String, Object> root = Json.parseObject(text);
        String art = Json.string(root, "art", "");
        Art pictures = art.isEmpty() ? new Art(0, 0, List.of()) : readArt(rigFile.resolveSibling(art));
        return fromJson(root, pictures);
    }

    /** Rigs a layered file (or a flat picture) from scratch with {@link AutoRig}; {@code facing} -1 looks left. */
    public static RigModel autoRig(Path artFile, int facing) throws IOException {
        Art art = readArt(artFile);
        Map<String, AutoRig.Hint> hints = new java.util.HashMap<>();
        Path hintFile = partsFile(artFile);
        if (Files.isRegularFile(hintFile)) {
            Map<String, Object> root = Json.parseObject(Files.readString(hintFile, StandardCharsets.UTF_8));
            if (root.get("facing") instanceof Number n) {
                facing = n.intValue() > 0 ? 1 : -1;
            }
            if (root.get("parts") instanceof Map<?, ?> parts) {
                for (Map.Entry<?, ?> e : parts.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> h) {
                        float[] pivot = h.get("pivot") == null ? null : floats(h.get("pivot"));
                        String kind = h.get("kind") instanceof String k ? k : null;
                        Float degrees = h.get("degrees") instanceof Number d ? d.floatValue() : null;
                        hints.put(String.valueOf(e.getKey()), new AutoRig.Hint(pivot, kind, degrees));
                    }
                }
            }
        }
        RigModel model = AutoRig.rig(art.layers(), art.width(), art.height(), facing, hints);
        model.art = artFile.getFileName().toString();
        return model;
    }

    /** The hints beside a layered file: {@code front.psd} has {@code front.parts.json}. */
    public static Path partsFile(Path artFile) {
        String name = artFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return artFile.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".parts.json");
    }

    /** The rig file for a layered file: {@code front.psd} has {@code front.rig.json}. */
    public static Path rigFile(Path artFile) {
        String name = artFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return artFile.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + EXTENSION);
    }

    /** The pictures of a rig: its canvas size and its layers, bottom first. */
    public record Art(int width, int height, List<RigLayer> layers) {
    }

    /** Reads a layered file's visible pixel layers, or a flat picture as one layer called Body. */
    public static Art readArt(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".psd")) {
            PsdFile psd = PsdFile.read(file);
            List<RigLayer> layers = new ArrayList<>();
            for (PsdFile.Layer layer : psd.layers()) {
                if (!layer.visible()) {
                    continue;
                }
                int[] argb = layer.argb();
                if (layer.opacity() < 0.999f) {
                    argb = argb.clone();
                    for (int i = 0; i < argb.length; i++) {
                        int a = Math.round((argb[i] >>> 24) * layer.opacity());
                        argb[i] = a << 24 | argb[i] & 0xFFFFFF;
                    }
                }
                boolean additive = layer.blend().equals("lddg") || layer.blend().equals("scrn");
                layers.add(new RigLayer(layer.path(), layer.left(), layer.top(), layer.width(), layer.height(), argb,
                        additive));
            }
            return new Art(psd.width(), psd.height(), layers);
        }
        Picture picture = Picture.read(file);
        int[] argb = picture.getRGB(0, 0, picture.getWidth(), picture.getHeight(), null, 0, picture.getWidth());
        RigLayer body = new RigLayer("Body", 0, 0, picture.getWidth(), picture.getHeight(), argb, false);
        return new Art(picture.getWidth(), picture.getHeight(), List.of(body));
    }

    // --- Reading --------------------------------------------------------------------------------------------------

    /** A rig from its file's text, drawn with art already read (an editor's undo keeps the pictures it has). */
    public static RigModel parse(String json, Art art) {
        return fromJson(Json.parseObject(json), art);
    }

    static RigModel fromJson(Map<String, Object> root, Art art) {
        RigModel model = new RigModel();
        model.art = Json.string(root, "art", "");
        float[] canvas = floats(root.get("canvas"));
        model.canvasWidth = canvas.length >= 2 ? Math.round(canvas[0]) : art.width();
        model.canvasHeight = canvas.length >= 2 ? Math.round(canvas[1]) : art.height();
        float[] pivot = floats(root.get("pivot"));
        model.pivotX = pivot.length >= 2 ? pivot[0] : model.canvasWidth * 0.5f;
        model.pivotY = pivot.length >= 2 ? pivot[1] : model.canvasHeight;
        model.facing = root.get("facing") instanceof Number n && n.intValue() > 0 ? 1 : -1;
        for (RigLayer layer : art.layers()) {
            RigLayer trimmed = layer.trimmed();
            if (trimmed != null) {
                model.layers.add(trimmed);
            }
        }
        for (Map<String, Object> p : objects(root, "parameters")) {
            model.parameters.add(new RigParameter(Json.string(p, "id"), num(p.get("min"), 0f), num(p.get("max"), 1f),
                    num(p.get("default"), 0f)));
        }
        for (Map<String, Object> d : objects(root, "deformers")) {
            String id = Json.string(d, "id");
            String parent = d.get("parent") instanceof String s && !s.isEmpty() ? s : null;
            Deformer deformer;
            if ("rotation".equals(Json.string(d, "type", "warp"))) {
                float[] origin = floats(d.get("origin"));
                deformer = new Deformer.Rotation(id, parent, origin.length > 0 ? origin[0] : 0f,
                        origin.length > 1 ? origin[1] : 0f);
            } else {
                float[] rect = floats(d.get("rect"));
                float[] grid = floats(d.get("grid"));
                deformer = new Deformer.Warp(id, parent, rect[0], rect[1], rect[2], rect[3],
                        grid.length > 0 ? Math.round(grid[0]) : 4, grid.length > 1 ? Math.round(grid[1]) : 4);
            }
            readKeyforms(d, deformer.keyforms);
            model.deformers.add(deformer);
        }
        for (Map<String, Object> m : objects(root, "meshes")) {
            float[] vertices = floats(m.get("vertices"));
            float[] tris = floats(m.get("triangles"));
            int[] triangles = new int[tris.length];
            for (int i = 0; i < tris.length; i++) {
                triangles[i] = Math.round(tris[i]);
            }
            String parent = m.get("parent") instanceof String s && !s.isEmpty() ? s : null;
            ArtMesh mesh = new ArtMesh(Json.string(m, "id"), Json.string(m, "layer", ""), parent, vertices, triangles,
                    Math.round(num(m.get("order"), 0f)));
            readKeyforms(m, mesh.keyforms);
            model.meshes.add(mesh);
        }
        for (Map<String, Object> p : objects(root, "physics")) {
            RigPhysics physics = new RigPhysics(Json.string(p, "id", "Physics"), Json.string(p, "output", ""));
            physics.gain = num(p.get("gain"), physics.gain);
            physics.stiffness = num(p.get("stiffness"), physics.stiffness);
            physics.damping = num(p.get("damping"), physics.damping);
            for (Map<String, Object> in : objects(p, "inputs")) {
                physics.inputs.add(new RigPhysics.Input(Json.string(in, "parameter"), num(in.get("weight"), 1f)));
            }
            model.physics.add(physics);
        }
        for (Map<String, Object> m : objects(root, "motions")) {
            RigMotion motion = new RigMotion(Json.string(m, "id"), num(m.get("duration"), 1f),
                    Json.bool(m, "loop", false));
            motion.fadeIn = num(m.get("fadeIn"), motion.fadeIn);
            motion.fadeOut = num(m.get("fadeOut"), motion.fadeOut);
            for (Map<String, Object> c : objects(m, "curves")) {
                float[] times = floats(c.get("times"));
                float[] values = floats(c.get("values"));
                if (times.length == 0 || times.length != values.length) {
                    continue;
                }
                RigMotion.Curve curve = new RigMotion.Curve(Json.string(c, "parameter"), times, values);
                curve.linear = Json.bool(c, "linear", false);
                motion.curves.add(curve);
            }
            model.motions.put(motion.id, motion);
        }
        model.resolve();
        return model;
    }

    private static void readKeyforms(Map<String, Object> owner, Keyforms keyforms) {
        for (Map<String, Object> t : objects(owner, "keyforms")) {
            Keyforms.Track track = new Keyforms.Track();
            for (Object p : list(t.get("parameters"))) {
                track.parameters.add(String.valueOf(p));
            }
            for (Object k : list(t.get("keys"))) {
                track.keys.add(floats(k));
            }
            for (Object f : list(t.get("forms"))) {
                float[] form = floats(f);
                track.forms.add(form.length == keyforms.size() ? form : java.util.Arrays.copyOf(form, keyforms.size()));
            }
            if (track.parameters.size() == track.keys.size() && track.forms.size() == track.combinations()) {
                keyforms.tracks.add(track);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> owner, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list(owner.get(key))) {
            if (o instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static float[] floats(Object value) {
        List<?> l = list(value);
        float[] out = new float[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = num(l.get(i), 0f);
        }
        return out;
    }

    private static float num(Object value, float fallback) {
        return value instanceof Number n ? n.floatValue() : fallback;
    }

    // --- Writing --------------------------------------------------------------------------------------------------

    public static void save(RigModel model, Path rigFile) throws IOException {
        Files.createDirectories(rigFile.toAbsolutePath().getParent());
        Path temp = rigFile.resolveSibling(rigFile.getFileName() + ".tmp");
        Files.writeString(temp, toJson(model), StandardCharsets.UTF_8);
        Files.move(temp, rigFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public static String toJson(RigModel model) {
        StringBuilder out = new StringBuilder(1 << 16);
        out.append("{\n  \"rig2d\": 1,\n  \"art\": ").append(quote(model.art))
                .append(",\n  \"canvas\": [").append(model.canvasWidth).append(", ").append(model.canvasHeight)
                .append("],\n  \"pivot\": [").append(n(model.pivotX)).append(", ").append(n(model.pivotY))
                .append("],\n  \"facing\": ").append(model.facing)
                .append(",\n  \"parameters\": [");
        for (int i = 0; i < model.parameters.size(); i++) {
            RigParameter p = model.parameters.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(quote(p.id())).append(", \"min\": ")
                    .append(n(p.min())).append(", \"max\": ").append(n(p.max())).append(", \"default\": ")
                    .append(n(p.defaultValue())).append('}');
        }
        out.append("\n  ],\n  \"deformers\": [");
        for (int i = 0; i < model.deformers.size(); i++) {
            Deformer d = model.deformers.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(quote(d.id)).append(", \"type\": ");
            if (d instanceof Deformer.Warp w) {
                out.append("\"warp\", \"parent\": ").append(quote(d.parent)).append(", \"rect\": [").append(n(w.x))
                        .append(", ").append(n(w.y)).append(", ").append(n(w.width)).append(", ").append(n(w.height))
                        .append("], \"grid\": [").append(w.columns).append(", ").append(w.rows).append(']');
            } else if (d instanceof Deformer.Rotation r) {
                out.append("\"rotation\", \"parent\": ").append(quote(d.parent)).append(", \"origin\": [")
                        .append(n(r.originX)).append(", ").append(n(r.originY)).append(']');
            }
            keyforms(out, d.keyforms);
            out.append('}');
        }
        out.append("\n  ],\n  \"meshes\": [");
        for (int i = 0; i < model.meshes.size(); i++) {
            ArtMesh m = model.meshes.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(quote(m.id)).append(", \"layer\": ")
                    .append(quote(m.layer)).append(", \"parent\": ").append(quote(m.parent)).append(", \"order\": ")
                    .append(m.drawOrder).append(",\n      \"vertices\": ");
            array(out, m.vertices);
            out.append(",\n      \"triangles\": [");
            for (int t = 0; t < m.triangles.length; t++) {
                out.append(t == 0 ? "" : ", ").append(m.triangles[t]);
            }
            out.append(']');
            keyforms(out, m.keyforms);
            out.append('}');
        }
        out.append("\n  ],\n  \"physics\": [");
        for (int i = 0; i < model.physics.size(); i++) {
            RigPhysics p = model.physics.get(i);
            out.append(i == 0 ? "\n" : ",\n").append("    {\"id\": ").append(quote(p.id)).append(", \"output\": ")
                    .append(quote(p.output)).append(", \"gain\": ").append(n(p.gain)).append(", \"stiffness\": ")
                    .append(n(p.stiffness)).append(", \"damping\": ").append(n(p.damping)).append(", \"inputs\": [");
            for (int k = 0; k < p.inputs.size(); k++) {
                RigPhysics.Input in = p.inputs.get(k);
                out.append(k == 0 ? "" : ", ").append("{\"parameter\": ").append(quote(in.parameter()))
                        .append(", \"weight\": ").append(n(in.weight())).append('}');
            }
            out.append("]}");
        }
        out.append("\n  ],\n  \"motions\": [");
        int i = 0;
        for (RigMotion m : model.motions.values()) {
            out.append(i++ == 0 ? "\n" : ",\n").append("    {\"id\": ").append(quote(m.id)).append(", \"duration\": ")
                    .append(n(m.duration)).append(", \"loop\": ").append(m.loop).append(", \"fadeIn\": ")
                    .append(n(m.fadeIn)).append(", \"fadeOut\": ").append(n(m.fadeOut)).append(", \"curves\": [");
            for (int c = 0; c < m.curves.size(); c++) {
                RigMotion.Curve curve = m.curves.get(c);
                out.append(c == 0 ? "\n" : ",\n").append("      {\"parameter\": ").append(quote(curve.parameter))
                        .append(", \"times\": ");
                array(out, curve.times);
                out.append(", \"values\": ");
                array(out, curve.values);
                if (curve.linear) {
                    out.append(", \"linear\": true");
                }
                out.append('}');
            }
            out.append("\n    ]}");
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static void keyforms(StringBuilder out, Keyforms keyforms) {
        out.append(",\n      \"keyforms\": [");
        for (int t = 0; t < keyforms.tracks.size(); t++) {
            Keyforms.Track track = keyforms.tracks.get(t);
            out.append(t == 0 ? "\n" : ",\n").append("        {\"parameters\": [");
            for (int p = 0; p < track.parameters.size(); p++) {
                out.append(p == 0 ? "" : ", ").append(quote(track.parameters.get(p)));
            }
            out.append("], \"keys\": [");
            for (int k = 0; k < track.keys.size(); k++) {
                out.append(k == 0 ? "" : ", ");
                array(out, track.keys.get(k));
            }
            out.append("], \"forms\": [");
            for (int f = 0; f < track.forms.size(); f++) {
                out.append(f == 0 ? "\n          " : ",\n          ");
                array(out, track.forms.get(f));
            }
            out.append("]}");
        }
        out.append(keyforms.tracks.isEmpty() ? "]" : "\n      ]");
    }

    private static void array(StringBuilder out, float[] values) {
        out.append('[');
        for (int i = 0; i < values.length; i++) {
            out.append(i == 0 ? "" : ", ").append(n(values[i]));
        }
        out.append(']');
    }

    /** A number with at most two decimals, and none when it is whole. */
    static String n(float value) {
        if (!Float.isFinite(value)) {
            return "0";
        }
        long hundredths = Math.round(value * 100.0);
        if (hundredths % 100 == 0) {
            return Long.toString(hundredths / 100);
        }
        String s = String.format(Locale.ROOT, "%.2f", hundredths / 100.0);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    private static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
