package org.zeronura.engine.nodes.geometry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A geometry's named attributes. Names beginning with a dot are internal to the node system (anonymous
 * attributes made by Capture Attribute and the like) and are never shown as output attributes.
 */
public final class Attributes {

    public record Attribute(String name, Domain domain, Column data) {
    }

    private final LinkedHashMap<String, Attribute> map = new LinkedHashMap<>();

    public Attributes copy() {
        Attributes out = new Attributes();
        out.map.putAll(map);
        return out;
    }

    public Attribute get(String name) {
        return map.get(name);
    }

    public Column column(String name) {
        Attribute attribute = map.get(name);
        return attribute == null ? null : attribute.data;
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public void put(String name, Domain domain, Column data) {
        map.put(name, new Attribute(name, domain, data));
    }

    public void remove(String name) {
        map.remove(name);
    }

    public Collection<Attribute> all() {
        return map.values();
    }

    public Map<String, Attribute> asMap() {
        return map;
    }

    /** Attributes on one domain, for operations that carry them along with that domain's elements. */
    public java.util.List<Attribute> on(Domain domain) {
        java.util.List<Attribute> out = new java.util.ArrayList<>();
        for (Attribute attribute : map.values()) {
            if (attribute.domain == domain) {
                out.add(attribute);
            }
        }
        return out;
    }
}
