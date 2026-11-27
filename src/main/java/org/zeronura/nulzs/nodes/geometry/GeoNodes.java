package org.zeronura.nulzs.nodes.geometry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Every geometry node the engine implements, by Blender type name. */
public final class GeoNodes {

    private static final Map<String, NodeImpl> REGISTRY;

    static {
        Map<String, NodeImpl> map = new HashMap<>();
        FunctionNodes.register(map);
        TextureNodes.register(map);
        InputNodes.register(map);
        MeshPrimitives.register(map);
        CurveNodes.register(map);
        InstanceNodes.register(map);
        GeometryNodes.register(map);
        MeshNodes.register(map);
        REGISTRY = Collections.unmodifiableMap(map);
    }

    private GeoNodes() {
    }

    public static Map<String, NodeImpl> registry() {
        return REGISTRY;
    }
}
