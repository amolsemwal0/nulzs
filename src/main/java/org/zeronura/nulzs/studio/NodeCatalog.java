package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.data.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The nodes a node tool can add, as Blender makes them: the Shader Tool's from {@code nodes/shader_nodes.json} and the
 * Geometry Nodes Tool's from {@code nodes/geometry_nodes.json}, which {@code tools/blender_nodes/make_shader_catalog.py}
 * and {@code make_geometry_catalog.py} write from Blender itself, with every socket's id, name, type and default and
 * every setting — and, for each dropdown setting, which sockets each choice shows.
 *
 * <p>A category's name may be a path ({@code Mesh/Primitives}), shown as a menu inside a menu. A zone (a Repeat Zone,
 * say) is one entry that adds two nodes, paired: {@link Entry#zoneOutput} is the second.
 */
final class NodeCatalog {

    /** A dropdown setting: its choices as id and label, and the socket ids each choice shows. */
    record Setting(String name, List<String[]> items, Map<String, Set<String>> inputs, Map<String, Set<String>> outputs) {
    }

    /**
     * A node that can be added: its Blender type, its name, its group in the Add menu, and the node as Blender exports
     * it. A zone's entry adds its input node and, paired with it, {@code zoneOutput}'s.
     */
    record Entry(String id, String type, String label, String category, Map<String, Object> template,
                 Map<String, Setting> settings, Set<String> fixed, Entry zoneOutput) {

        boolean isZone() {
            return zoneOutput != null;
        }
    }

    private static NodeCatalog shader;
    private static NodeCatalog geometry;

    private final String resource;
    private final List<String> categories = new ArrayList<>();
    private final Map<String, List<Entry>> byCategory = new LinkedHashMap<>();
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    /** Every entry by node type, zones' outputs too, for nodes read from a file. */
    private final Map<String, Entry> byType = new LinkedHashMap<>();

    private NodeCatalog(String resource) {
        this.resource = resource;
    }

    /** The Shader Tool's nodes. */
    static synchronized NodeCatalog shader() {
        if (shader == null) {
            shader = load("/nodes/shader_nodes.json");
        }
        return shader;
    }

    /** The Geometry Nodes Tool's nodes: the ones the engine's geometry evaluator runs. */
    static synchronized NodeCatalog geometry() {
        if (geometry == null) {
            geometry = load("/nodes/geometry_nodes.json");
        }
        return geometry;
    }

    /** Where the catalog comes from, for a message when it is missing. */
    String resource() {
        return resource;
    }

    @SuppressWarnings("unchecked")
    private static NodeCatalog load(String resource) {
        NodeCatalog catalog = new NodeCatalog(resource);
        try (InputStream in = NodeCatalog.class.getResourceAsStream(resource)) {
            if (in == null) {
                return catalog;
            }
            Map<String, Object> json = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (Map<String, Object> category : Json.objects(json, "categories")) {
                String name = Json.string(category, "name", "Other");
                List<Entry> entries = new ArrayList<>();
                for (Map<String, Object> node : Json.objects(category, "nodes")) {
                    Map<String, Object> template = (Map<String, Object>) node.get("node");
                    Set<String> fixed = new HashSet<>();
                    for (Object f : Json.list(node, "fixed")) {
                        fixed.add(String.valueOf(f));
                    }
                    Entry zoneOutput = null;
                    if (node.get("zone") instanceof Map<?, ?> output) {
                        Map<String, Object> outputTemplate = (Map<String, Object>) output;
                        String type = Json.string(outputTemplate, "type", "");
                        zoneOutput = new Entry(Json.string(node, "id", "") + "#output", type,
                                Json.string(node, "label", "").replace(" Zone", "") + " Output", name, outputTemplate,
                                settings(Json.object(node, "zone_settings")), Set.of(), null);
                        catalog.byType.putIfAbsent(type, zoneOutput);
                    }
                    String label = Json.string(node, "label", "");
                    Entry entry = new Entry(Json.string(node, "id", ""), Json.string(template, "type", ""), label, name,
                            template, settings(Json.object(node, "settings")), fixed, zoneOutput);
                    entries.add(entry);
                    catalog.byId.put(entry.id(), entry);
                    if (zoneOutput != null) {
                        // A zone's input node, read from a file, is that node, not the pair the menu adds.
                        Entry input = new Entry(entry.id() + "#input", entry.type(), label.replace(" Zone", "") + " Input",
                                name, template, entry.settings(), fixed, null);
                        catalog.byType.putIfAbsent(entry.type(), input);
                    } else {
                        catalog.byType.putIfAbsent(entry.type(), entry);
                    }
                }
                catalog.categories.add(name);
                catalog.byCategory.put(name, entries);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[Editor] Could not read the node catalog " + resource + ": " + e.getMessage());
        }
        return catalog;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Setting> settings(Map<String, Object> raw) {
        Map<String, Setting> settings = new LinkedHashMap<>();
        if (raw == null) {
            return settings;
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Map<String, Object> s = (Map<String, Object>) entry.getValue();
            List<String[]> items = new ArrayList<>();
            for (Object item : Json.list(s, "items")) {
                List<Object> pair = (List<Object>) item;
                items.add(new String[]{String.valueOf(pair.get(0)), String.valueOf(pair.get(1))});
            }
            settings.put(entry.getKey(), new Setting(entry.getKey(), items, sets(Json.object(s, "inputs")),
                    sets(Json.object(s, "outputs"))));
        }
        return settings;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> sets(Map<String, Object> json) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (json != null) {
            json.forEach((key, value) -> {
                Set<String> ids = new HashSet<>();
                for (Object id : (List<Object>) value) {
                    ids.add(String.valueOf(id));
                }
                out.put(key, ids);
            });
        }
        return out;
    }

    List<String> categories() {
        return categories;
    }

    List<Entry> in(String category) {
        return byCategory.getOrDefault(category, List.of());
    }

    Entry byId(String id) {
        return byId.get(id);
    }

    /** The entry a node of this type and settings came from: a Mix node by its data type, a zone's node by its half. */
    Entry entryFor(String type, Map<String, Object> props) {
        Object dataType = props == null ? null : props.get("data_type");
        Entry exact = dataType == null ? null : byId.get(type + ":" + dataType);
        if (exact != null) {
            return exact;
        }
        Entry plain = byId.get(type);
        return plain != null ? plain : byType.get(type);
    }

    boolean isEmpty() {
        return byId.isEmpty();
    }
}
