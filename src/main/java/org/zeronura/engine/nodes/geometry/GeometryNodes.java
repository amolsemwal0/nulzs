package org.zeronura.engine.nodes.geometry;

import org.zeronura.engine.nodes.Ref;
import org.zeronura.engine.nodes.Socket;
import org.zeronura.engine.nodes.SocketType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Nodes that act on whole geometries: join, transform, set position, delete, attributes, materials, sampling. */
final class GeometryNodes {

    private GeometryNodes() {
    }

    static void register(Map<String, NodeImpl> map) {
        map.put("GeometryNodeJoinGeometry", call -> call.out("Geometry", GeometryOps.join(call.geometries("Geometry"))));
        map.put("GeometryNodeTransform", GeometryNodes::transform);
        map.put("GeometryNodeSetPosition", GeometryNodes::setPosition);
        map.put("GeometryNodeDeleteGeometry", call -> {
            GeometrySet[] parts = separate(call, call.geometry("Geometry"));
            call.out("Geometry", parts[1]);
        });
        map.put("GeometryNodeSeparateGeometry", call -> {
            GeometrySet[] parts = separate(call, call.geometry("Geometry"));
            call.out("Selection", parts[0]);
            call.out("Inverted", parts[1]);
        });
        map.put("GeometryNodeSeparateComponents", call -> {
            GeometrySet g = call.geometry("Geometry");
            call.out("Mesh", g.mesh == null ? GeometrySet.EMPTY : GeometrySet.of(g.mesh));
            call.out("Curve", g.curves == null ? GeometrySet.EMPTY : GeometrySet.of(g.curves));
            call.out("Point Cloud", g.points == null ? GeometrySet.EMPTY : GeometrySet.of(g.points));
            call.out("Instances", g.instances == null ? GeometrySet.EMPTY : GeometrySet.of(g.instances));
        });
        map.put("GeometryNodeBoundBox", GeometryNodes::boundBox);
        map.put("GeometryNodeAttributeDomainSize", GeometryNodes::domainSize);
        map.put("GeometryNodeAttributeStatistic", GeometryNodes::attributeStatistic);
        map.put("GeometryNodeStoreNamedAttribute", GeometryNodes::storeNamedAttribute);
        map.put("GeometryNodeRemoveAttribute", GeometryNodes::removeAttribute);
        map.put("GeometryNodeCaptureAttribute", GeometryNodes::captureAttribute);
        map.put("GeometryNodeSetMaterial", GeometryNodes::setMaterial);
        map.put("GeometryNodeSetMaterialIndex", call -> storeOn(call, call.geometry("Geometry"), "material_index", Domain.FACE,
                call.field("Material Index", DataType.INT), call.field("Selection", DataType.BOOL), "Geometry"));
        map.put("GeometryNodeReplaceMaterial", GeometryNodes::replaceMaterial);
        map.put("GeometryNodeMaterialSelection", GeometryNodes::materialSelection);
        map.put("GeometryNodeSetShadeSmooth", GeometryNodes::setShadeSmooth);
        map.put("GeometryNodeSetID", call -> storeOn(call, call.geometry("Geometry"), "id", null,
                call.field("ID", DataType.INT), call.field("Selection", DataType.BOOL), "Geometry"));
        map.put("GeometryNodeSetGeometryName", call -> call.out("Geometry", call.geometry("Geometry").withName(call.string("Name"))));
        map.put("GeometryNodeSampleIndex", GeometryNodes::sampleIndex);
        map.put("GeometryNodeFieldAtIndex", GeometryNodes::fieldAtIndex);
        map.put("GeometryNodeFieldOnDomain", call -> call.out("Value", Field.onDomain(call.field("Value"), call.domain(Domain.POINT))));
        map.put("GeometryNodeAccumulateField", GeometryNodes::accumulate);
        map.put("GeometryNodeSampleNearest", GeometryNodes::sampleNearest);
        map.put("GeometryNodeIndexOfNearest", GeometryNodes::indexOfNearest);
        map.put("GeometryNodeProximity", GeometryNodes::proximity);
        map.put("GeometryNodeSampleNearestSurface", GeometryNodes::sampleNearestSurface);
        map.put("GeometryNodeRaycast", GeometryNodes::raycast);
        map.put("GeometryNodeBlurAttribute", GeometryNodes::blur);
        map.put("GeometryNodeDuplicateElements", GeometryNodes::duplicateElements);
        map.put("GeometryNodeWarning", call -> call.out("Show", call.field("Show", DataType.BOOL)));
        map.put("GeometryNodeViewer", call -> {
        });
        for (String gizmo : new String[]{"GeometryNodeGizmoLinear", "GeometryNodeGizmoDial", "GeometryNodeGizmoTransform"}) {
            map.put(gizmo, call -> call.out("Transform", GeometrySet.EMPTY));
        }
    }

    // --- Transform and position ----------------------------------------------------------------------------

    private static void transform(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        float[] m;
        String mode = call.has("Mode") ? call.string("Mode") : "Components";
        if (mode.equalsIgnoreCase("Matrix")) {
            m = call.field("Transform", DataType.MATRIX).single().f.clone();
        } else {
            float[] t = call.v("Translation");
            float[] s = call.v("Scale");
            float[] q = call.field("Rotation", DataType.ROTATION).single().f;
            m = new float[16];
            Transforms.compose(t[0], t[1], t[2], q, 0, s[0], s[1], s[2], m, 0);
        }
        call.out("Geometry", GeometryOps.transform(geometry, m));
    }

    private static void setPosition(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        Field selection = call.field("Selection", DataType.BOOL);
        Field position = call.field("Position", DataType.VECTOR);
        Field offset = call.field("Offset", DataType.VECTOR);
        GeometrySet out = geometry;
        for (GeometryComponent component : geometry.components()) {
            Domain domain = component.defaultDomain();
            FieldContext context = call.context(component, domain);
            int n = context.size;
            if (n == 0) {
                continue;
            }
            boolean[] sel = call.selection(selection, component, domain);
            Column p = context.evalAs(position, DataType.VECTOR);
            Column o = context.evalAs(offset, DataType.VECTOR);
            if (component instanceof InstanceGeometry instances) {
                float[] t = instances.transforms().clone();
                for (int k = 0; k < n; k++) {
                    if (sel[k]) {
                        t[16 * k + 12] = p.f[3 * k] + o.f[3 * k];
                        t[16 * k + 13] = p.f[3 * k + 1] + o.f[3 * k + 1];
                        t[16 * k + 14] = p.f[3 * k + 2] + o.f[3 * k + 2];
                    }
                }
                out = out.withInstances(instances.withTransforms(t));
                continue;
            }
            float[] old = component.attributes().column("position").f;
            float[] next = old.clone();
            for (int k = 0; k < n; k++) {
                if (sel[k]) {
                    for (int a = 0; a < 3; a++) {
                        next[3 * k + a] = p.f[3 * k + a] + o.f[3 * k + a];
                    }
                }
            }
            Attributes attributes = component.attributes().copy();
            attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, next));
            if (component instanceof CurveGeometry) {
                for (String handle : new String[]{"handle_left", "handle_right"}) {
                    Column h = attributes.column(handle);
                    if (h != null) {
                        float[] moved = h.f.clone();
                        for (int k = 0; k < moved.length; k++) {
                            moved[k] += next[k] - old[k];
                        }
                        attributes.put(handle, Domain.POINT, Column.ofFloats(DataType.VECTOR, moved));
                    }
                }
            }
            attributes.remove(".corner_normals");
            out = out.replace(component, component.withAttributes(attributes));
        }
        call.out("Geometry", out);
    }

    // --- Delete and separate -------------------------------------------------------------------------------

    /** The selected part and the rest, as Separate Geometry; Delete Geometry keeps the rest. */
    private static GeometrySet[] separate(NodeCall call, GeometrySet geometry) {
        Domain domain = call.domain(Domain.POINT);
        String mode = call.prop("mode", "ALL");
        Field selection = call.field("Selection", DataType.BOOL);
        GeometrySet selected = geometry;
        GeometrySet rest = geometry;
        for (GeometryComponent component : geometry.components()) {
            Domain d = domain;
            if (component instanceof PointGeometry || (component instanceof CurveGeometry && d != Domain.CURVE)) {
                d = Domain.POINT;
            }
            if (component instanceof InstanceGeometry) {
                d = Domain.INSTANCE;
            }
            if (!component.hasDomain(d)) {
                continue;
            }
            boolean[] sel = call.selection(selection, component, d);
            boolean[] inv = new boolean[sel.length];
            for (int k = 0; k < sel.length; k++) {
                inv[k] = !sel[k];
            }
            selected = selected.replace(component, keep(component, d, sel, mode));
            rest = rest.replace(component, keep(component, d, inv, mode));
        }
        return new GeometrySet[]{selected, rest};
    }

    /** The component keeping the flagged elements of a domain (and what depends on them, as the mode says). */
    static GeometryComponent keep(GeometryComponent component, Domain domain, boolean[] keep, String mode) {
        return switch (component) {
            case PointGeometry points -> GeometryOps.pointsSubset(points, keep);
            case InstanceGeometry instances -> GeometryOps.instancesSubset(instances, keep);
            case CurveGeometry curves -> domain == Domain.CURVE ? GeometryOps.curvesSubset(curves, null, keep)
                    : GeometryOps.curvesSubset(curves, keep, null);
            case MeshGeometry mesh -> keepMesh(mesh, domain, keep, mode);
        };
    }

    private static MeshGeometry keepMesh(MeshGeometry mesh, Domain domain, boolean[] keep, String mode) {
        MeshGeometry.Topology t = mesh.topology;
        boolean[] verts = new boolean[mesh.vertCount()];
        boolean[] edges = new boolean[mesh.edgeCount()];
        boolean[] faces = new boolean[mesh.faceCount()];
        switch (domain) {
            case POINT -> {
                System.arraycopy(keep, 0, verts, 0, verts.length);
                Arrays.fill(edges, true);
                Arrays.fill(faces, true);
            }
            case EDGE -> {
                System.arraycopy(keep, 0, edges, 0, edges.length);
                Arrays.fill(faces, true);
                for (int f = 0; f < faces.length; f++) {
                    for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                        if (!keep[t.cornerEdges[c]]) {
                            faces[f] = false;
                        }
                    }
                }
                if ("ONLY_FACE".equals(mode)) {
                    Arrays.fill(edges, true);
                }
                if ("ALL".equals(mode)) {
                    markUsedVerts(mesh, edges, faces, verts);
                } else {
                    Arrays.fill(verts, true);
                }
            }
            default -> {
                System.arraycopy(keep, 0, faces, 0, faces.length);
                if ("ONLY_FACE".equals(mode)) {
                    Arrays.fill(edges, true);
                    Arrays.fill(verts, true);
                } else {
                    int[][] edgeFaces = t.edgeFaces();
                    for (int e = 0; e < edges.length; e++) {
                        boolean used = edgeFaces[e].length == 0;
                        for (int f : edgeFaces[e]) {
                            used |= faces[f];
                        }
                        edges[e] = used;
                    }
                    if ("ALL".equals(mode)) {
                        markUsedVerts(mesh, edges, faces, verts);
                        int[][] vertEdges = t.vertEdges();
                        for (int v = 0; v < verts.length; v++) {
                            if (vertEdges[v].length == 0) {
                                verts[v] = true;
                            }
                        }
                    } else {
                        Arrays.fill(verts, true);
                    }
                }
            }
        }
        return GeometryOps.meshSubset(mesh, verts, edges, faces);
    }

    private static void markUsedVerts(MeshGeometry mesh, boolean[] edges, boolean[] faces, boolean[] verts) {
        int[] ev = mesh.topology.edgeVerts;
        for (int e = 0; e < edges.length; e++) {
            if (edges[e]) {
                verts[ev[2 * e]] = true;
                verts[ev[2 * e + 1]] = true;
            }
        }
    }

    // --- Measurement ---------------------------------------------------------------------------------------

    private static void boundBox(NodeCall call) {
        GeometrySet geometry = GeometryOps.realize(call.geometry("Geometry"));
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean any = false;
        for (GeometryComponent component : geometry.components()) {
            Column p = component.attributes().column("position");
            if (p == null) {
                continue;
            }
            for (int k = 0; k < p.f.length; k += 3) {
                any = true;
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], p.f[k + a]);
                    max[a] = Math.max(max[a], p.f[k + a]);
                }
            }
        }
        if (!any) {
            min = new float[3];
            max = new float[3];
        }
        float[] size = {max[0] - min[0], max[1] - min[1], max[2] - min[2]};
        MeshGeometry box = MeshPrimitives.cube(size, 2, 2, 2);
        box = GeometryOps.transform(box, MeshPrimitives.translation((min[0] + max[0]) / 2f, (min[1] + max[1]) / 2f,
                (min[2] + max[2]) / 2f));
        call.out("Bounding Box", any ? GeometrySet.of(box) : GeometrySet.EMPTY);
        call.out("Min", Field.constant(DataType.VECTOR, min));
        call.out("Max", Field.constant(DataType.VECTOR, max));
    }

    private static void domainSize(NodeCall call) {
        GeometrySet g = call.geometry("Geometry");
        String component = call.prop("component", "MESH");
        int points = 0, edges = 0, faces = 0, corners = 0, splines = 0, instances = 0;
        switch (component) {
            case "MESH" -> {
                if (g.mesh != null) {
                    points = g.mesh.vertCount();
                    edges = g.mesh.edgeCount();
                    faces = g.mesh.faceCount();
                    corners = g.mesh.cornerCount();
                }
            }
            case "CURVE" -> {
                if (g.curves != null) {
                    points = g.curves.pointCount();
                    splines = g.curves.curveCount();
                }
            }
            case "POINTCLOUD" -> points = g.points == null ? 0 : g.points.count();
            case "INSTANCES" -> instances = g.instances == null ? 0 : g.instances.count();
            default -> {
            }
        }
        call.out("Point Count", Field.constant(DataType.INT, points));
        call.out("Edge Count", Field.constant(DataType.INT, edges));
        call.out("Face Count", Field.constant(DataType.INT, faces));
        call.out("Face Corner Count", Field.constant(DataType.INT, corners));
        call.out("Spline Count", Field.constant(DataType.INT, splines));
        call.out("Instance Count", Field.constant(DataType.INT, instances));
        call.out("Layer Count", Field.constant(DataType.INT, 0));
    }

    private static void attributeStatistic(NodeCall call) {
        GeometrySet g = call.geometry("Geometry");
        Domain domain = call.domain(Domain.POINT);
        boolean vector = "FLOAT_VECTOR".equals(call.prop("data_type", "FLOAT"));
        DataType type = vector ? DataType.VECTOR : DataType.FLOAT;
        Field attribute = call.field("Attribute", type);
        Field selection = call.field("Selection", DataType.BOOL);
        int width = type.width;
        List<float[]> values = new ArrayList<>();
        for (GeometryComponent component : g.components()) {
            if (!component.hasDomain(domain)) {
                continue;
            }
            FieldContext context = call.context(component, domain);
            Column c = context.evalAs(attribute, type);
            boolean[] sel = call.selection(selection, component, domain);
            for (int k = 0; k < context.size; k++) {
                if (sel[k]) {
                    values.add(Arrays.copyOfRange(c.f, k * width, k * width + width));
                }
            }
        }
        float[][] stats = new float[8][width];
        int n = values.size();
        if (n > 0) {
            for (int a = 0; a < width; a++) {
                float[] column = new float[n];
                double sum = 0;
                for (int k = 0; k < n; k++) {
                    column[k] = values.get(k)[a];
                    sum += column[k];
                }
                Arrays.sort(column);
                double mean = sum / n;
                double var = 0;
                for (float v : column) {
                    var += (v - mean) * (v - mean);
                }
                var = n > 1 ? var / n : 0;
                stats[0][a] = (float) mean;
                stats[1][a] = n % 2 == 1 ? column[n / 2] : (column[n / 2 - 1] + column[n / 2]) / 2f;
                stats[2][a] = (float) sum;
                stats[3][a] = column[0];
                stats[4][a] = column[n - 1];
                stats[5][a] = column[n - 1] - column[0];
                stats[6][a] = (float) Math.sqrt(var);
                stats[7][a] = (float) var;
            }
        }
        String[] names = {"Mean", "Median", "Sum", "Min", "Max", "Range", "Standard Deviation", "Variance"};
        for (int s = 0; s < 8; s++) {
            call.out(names[s], vector ? Field.constant(DataType.VECTOR, stats[s]) : Field.constant(DataType.FLOAT, stats[s][0]));
        }
    }

    // --- Attributes ----------------------------------------------------------------------------------------

    /**
     * Stores a field as a named attribute on every component that has the domain (each component's own domain
     * when {@code domain} is null), keeping existing values where the selection is false.
     */
    static GeometrySet storeOn(NodeCall call, GeometrySet geometry, String name, Domain domain, Field value, Field selection,
                               String output) {
        GeometrySet out = storeAttribute(call, geometry, name, domain, value, selection);
        if (output != null) {
            call.out(output, out);
        }
        return out;
    }

    static GeometrySet storeAttribute(NodeCall call, GeometrySet geometry, String name, Domain domain, Field value,
                                      Field selection) {
        GeometrySet out = geometry;
        for (GeometryComponent component : geometry.components()) {
            Domain d = domain != null ? domain : component.defaultDomain();
            if (component instanceof InstanceGeometry && domain != null && domain != Domain.INSTANCE) {
                continue;
            }
            if (!component.hasDomain(d)) {
                continue;
            }
            FieldContext context = call.context(component, d);
            Column values = context.evalAs(value, value.type);
            boolean[] sel = call.selection(selection, component, d);
            boolean all = true;
            for (boolean s : sel) {
                all &= s;
            }
            Column stored = values;
            if (!all) {
                Column existing = component.read(name, d, value.type);
                stored = existing == null ? Column.create(value.type, context.size) : existing.copy();
                for (int k = 0; k < context.size; k++) {
                    if (sel[k]) {
                        stored.copyElement(values, k, k);
                    }
                }
            } else {
                stored = values.copy();
            }
            out = out.replace(component, GeometryOps.withAttribute(component, name, d, stored));
        }
        return out;
    }

    private static void storeNamedAttribute(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        String name = call.string("Name");
        if (name.isEmpty()) {
            call.out("Geometry", geometry);
            return;
        }
        DataType type = DataType.ofAttribute(call.prop("data_type", "FLOAT"));
        Domain domain = call.domain(Domain.POINT);
        if ("BYTE_COLOR".equals(call.prop("data_type", "FLOAT")) || "FLOAT_COLOR".equals(call.prop("data_type", "FLOAT"))) {
            type = DataType.COLOR;
        }
        if ("FLOAT2".equals(call.prop("data_type", "FLOAT"))) {
            type = DataType.FLOAT2;
        }
        storeOn(call, geometry, name, domain, call.field("Value").as(type), call.field("Selection", DataType.BOOL), "Geometry");
    }

    private static void removeAttribute(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        String pattern = call.string("Name");
        boolean wildcard = call.has("Pattern Mode") && call.string("Pattern Mode").toLowerCase().startsWith("wild");
        GeometrySet out = geometry;
        for (GeometryComponent component : geometry.components()) {
            Attributes attributes = component.attributes().copy();
            for (Attributes.Attribute a : component.attributes().all()) {
                boolean match = wildcard ? a.name().matches(pattern.replace(".", "\\.").replace("*", ".*")) : a.name().equals(pattern);
                if (match && !a.name().equals("position")) {
                    attributes.remove(a.name());
                }
            }
            out = out.replace(component, component.withAttributes(attributes));
        }
        call.out("Geometry", out);
    }

    /**
     * Capture Attribute: each item's field evaluated on the geometry now and kept as an attribute, so it can be read
     * back after the geometry changes. Items pair up by position: Blender names the input and output sides of one
     * item differently ("Value" and "Attribute").
     */
    private static void captureAttribute(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        Domain domain = call.domain(Domain.POINT);
        Field selection = call.has("Selection") ? call.field("Selection", DataType.BOOL) : Field.constant(DataType.BOOL, true);
        List<Socket> inputs = new ArrayList<>();
        List<Socket> outputs = new ArrayList<>();
        for (Socket socket : call.node.inputs()) {
            if (isCaptureItem(socket)) {
                inputs.add(socket);
            }
        }
        for (Socket socket : call.node.outputs()) {
            if (isCaptureItem(socket)) {
                outputs.add(socket);
            }
        }
        GeometrySet out = geometry;
        for (int k = 0; k < Math.min(inputs.size(), outputs.size()); k++) {
            Field value = call.field(inputs.get(k).id);
            String outputId = outputs.get(k).id;
            if (value.isConstant()) {
                call.out(outputId, value);
                continue;
            }
            String name = MeshPrimitives.anonymous("capture");
            out = storeAttribute(call, out, name, domain, value, selection);
            call.out(outputId, Inputs.named(name, value.type));
        }
        if (call.node.output("Selection") != null) {
            call.out("Selection", selection);
        }
        call.out("Geometry", out);
    }

    private static boolean isCaptureItem(Socket socket) {
        return !socket.id.equals("Geometry") && !socket.id.equals("Selection") && socket.type != SocketType.CUSTOM
                && socket.type.isFieldType();
    }

    // --- Materials -----------------------------------------------------------------------------------------

    private static String materialName(Object value) {
        return value instanceof Ref ref ? ref.name() : value instanceof String s ? s : null;
    }

    private static void setMaterial(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        String material = materialName(call.raw("Material"));
        Field selection = call.field("Selection", DataType.BOOL);
        GeometrySet out = geometry;
        if (geometry.mesh != null && geometry.mesh.faceCount() > 0) {
            MeshGeometry mesh = geometry.mesh;
            boolean[] sel = call.selection(selection, mesh, Domain.FACE);
            boolean all = true;
            for (boolean s : sel) {
                all &= s;
            }
            List<String> slots = new ArrayList<>(mesh.materials);
            Column existing = mesh.read("material_index", Domain.FACE, DataType.INT);
            String key = material == null ? "" : material;
            int slot;
            Column indices;
            if (all) {
                slots = new ArrayList<>(List.of(key));
                slot = 0;
                indices = Column.create(DataType.INT, mesh.faceCount());
            } else {
                slot = slots.indexOf(key);
                if (slot < 0) {
                    if (slots.isEmpty()) {
                        slots.add("");
                    }
                    slot = slots.size();
                    slots.add(key);
                }
                indices = existing == null ? Column.create(DataType.INT, mesh.faceCount()) : existing.copy();
                for (int f = 0; f < sel.length; f++) {
                    if (sel[f]) {
                        indices.i[f] = slot;
                    }
                }
            }
            MeshGeometry updated = (MeshGeometry) GeometryOps.withAttribute(mesh, "material_index", Domain.FACE, indices);
            out = out.withMesh(updated.withMaterials(slots));
        }
        if (geometry.curves != null) {
            out = out.withCurves(geometry.curves.withMaterials(List.of(material == null ? "" : material)));
        }
        call.out("Geometry", out);
    }

    private static void replaceMaterial(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        String old = materialName(call.raw("Old"));
        String replacement = materialName(call.raw("New"));
        GeometrySet out = geometry;
        if (geometry.mesh != null) {
            List<String> slots = new ArrayList<>(geometry.mesh.materials);
            for (int k = 0; k < slots.size(); k++) {
                if (slots.get(k).equals(old == null ? "" : old)) {
                    slots.set(k, replacement == null ? "" : replacement);
                }
            }
            out = out.withMesh(geometry.mesh.withMaterials(slots));
        }
        call.out("Geometry", out);
    }

    private static void materialSelection(NodeCall call) {
        String material = materialName(call.raw("Material"));
        call.out("Selection", new Field.Input(DataType.BOOL) {
            @Override
            protected Column compute(FieldContext context) {
                MeshGeometry mesh = context.mesh();
                if (mesh == null) {
                    return Column.create(DataType.BOOL, context.size);
                }
                Column indices = mesh.read("material_index", Domain.FACE, DataType.INT);
                Column faces = Column.create(DataType.BOOL, mesh.faceCount());
                for (int f = 0; f < mesh.faceCount(); f++) {
                    int idx = indices == null ? 0 : indices.i[f];
                    String slot = idx >= 0 && idx < mesh.materials.size() ? mesh.materials.get(idx) : "";
                    faces.i[f] = slot.equals(material == null ? "" : material) ? 1 : 0;
                }
                return mesh.adapt(faces, Domain.FACE, context.domain);
            }
        });
    }

    private static void setShadeSmooth(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        boolean edges = "EDGE".equals(call.prop("domain", "FACE"));
        Field smooth = call.field("Shade Smooth", DataType.BOOL);
        Field sharp = Field.of(DataType.BOOL, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                out.i[k] = in[0].i[k] != 0 ? 0 : 1;
            }
        }, smooth);
        if (geometry.mesh == null) {
            call.out("Geometry", geometry);
            return;
        }
        GeometrySet meshOnly = GeometrySet.of(geometry.mesh);
        GeometrySet stored = storeAttribute(call, meshOnly, edges ? "sharp_edge" : "sharp_face", edges ? Domain.EDGE : Domain.FACE,
                sharp, call.field("Selection", DataType.BOOL));
        MeshGeometry mesh = stored.mesh;
        Attributes attributes = mesh.attributes().copy();
        attributes.remove(".corner_normals");
        call.out("Geometry", geometry.withMesh(mesh.withAttributes(attributes)));
    }

    // --- Sampling ------------------------------------------------------------------------------------------

    /** The component of a geometry that has a domain, preferring the mesh, as Blender's sampling nodes pick. */
    static GeometryComponent componentFor(GeometrySet geometry, Domain domain) {
        for (GeometryComponent component : geometry.components()) {
            if (component.hasDomain(domain)) {
                return component;
            }
        }
        return null;
    }

    private static void sampleIndex(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        Domain domain = call.domain(Domain.POINT);
        boolean clamp = call.propBool("clamp", false);
        Field value = call.field("Value");
        Field index = call.field("Index", DataType.INT);
        GeometryComponent source = componentFor(geometry, domain);
        Column sourceValues = source == null ? null : call.context(source, domain).eval(value);
        call.out("Value", new Field.Input(value.type) {
            @Override
            protected Column compute(FieldContext context) {
                Column idx = context.evalAs(index, DataType.INT);
                Column out = Column.create(value.type, context.size);
                if (sourceValues == null || sourceValues.size == 0) {
                    return out;
                }
                for (int k = 0; k < context.size; k++) {
                    int i = idx.i[k];
                    if (clamp) {
                        i = Math.clamp(i, 0, sourceValues.size - 1);
                    } else if (i < 0 || i >= sourceValues.size) {
                        continue;
                    }
                    out.copyElement(sourceValues, i, k);
                }
                return out;
            }
        });
    }

    private static void fieldAtIndex(NodeCall call) {
        Domain domain = call.domain(Domain.POINT);
        Field value = call.field("Value");
        Field index = call.field("Index", DataType.INT);
        call.out("Value", new Field.Input(value.type) {
            @Override
            protected Column compute(FieldContext context) {
                Column idx = context.evalAs(index, DataType.INT);
                Column out = Column.create(value.type, context.size);
                if (context.component == null || !context.component.hasDomain(domain)) {
                    return out;
                }
                Column values = context.on(domain).eval(value);
                for (int k = 0; k < context.size; k++) {
                    int i = idx.i[k];
                    if (i >= 0 && i < values.size) {
                        out.copyElement(values, i, k);
                    }
                }
                return out;
            }
        });
    }

    private static void accumulate(NodeCall call) {
        Domain domain = call.domain(Domain.POINT);
        Field value = call.field("Value");
        DataType type = value.type == DataType.INT ? DataType.INT : value.type == DataType.MATRIX ? DataType.MATRIX
                : value.type == DataType.VECTOR ? DataType.VECTOR : DataType.FLOAT;
        Field group = call.field("Group Index", DataType.INT);
        DataType[] types = {type, type, type};
        Field.Multi shared = new Field.Multi(types, null, new Field[0]) {
            @Override
            Column[] compute(FieldContext context) {
                Column[] out = {Column.create(type, context.size), Column.create(type, context.size), Column.create(type, context.size)};
                if (context.component == null || !context.component.hasDomain(domain)) {
                    return out;
                }
                FieldContext on = context.on(domain);
                Column values = on.evalAs(value, type);
                Column groups = on.evalAs(group, DataType.INT);
                int n = on.size;
                int w = type.width;
                Column leading = Column.create(type, n), trailing = Column.create(type, n), total = Column.create(type, n);
                Map<Integer, double[]> sums = new java.util.HashMap<>();
                for (int k = 0; k < n; k++) {
                    double[] sum = sums.computeIfAbsent(groups.i[k], g -> new double[w]);
                    for (int a = 0; a < w; a++) {
                        double v = type.integer ? values.i[k] : values.f[k * w + a];
                        if (type.integer) {
                            trailing.i[k] = (int) sum[a];
                        } else {
                            trailing.f[k * w + a] = (float) sum[a];
                        }
                        sum[a] += v;
                        if (type.integer) {
                            leading.i[k] = (int) sum[a];
                        } else {
                            leading.f[k * w + a] = (float) sum[a];
                        }
                    }
                }
                for (int k = 0; k < n; k++) {
                    double[] sum = sums.get(groups.i[k]);
                    for (int a = 0; a < w; a++) {
                        if (type.integer) {
                            total.i[k] = (int) sum[a];
                        } else {
                            total.f[k * w + a] = (float) sum[a];
                        }
                    }
                }
                GeometryComponent c = context.component;
                out[0] = c.adapt(leading, domain, context.domain);
                out[1] = c.adapt(trailing, domain, context.domain);
                out[2] = c.adapt(total, domain, context.domain);
                return out;
            }
        };
        call.out("Leading", new Field.MultiOutput(shared, 0));
        call.out("Trailing", new Field.MultiOutput(shared, 1));
        call.out("Total", new Field.MultiOutput(shared, 2));
    }

    /** Positions of a domain's elements: points as they are, edges and faces at their centres. */
    static float[] elementPositions(GeometryComponent component, Domain domain) {
        Column p = component.attributes().column("position");
        if (component instanceof InstanceGeometry instances) {
            return instances.read("position", Domain.INSTANCE, DataType.VECTOR).f;
        }
        if (p == null) {
            return new float[0];
        }
        return component.adapt(p, Domain.POINT, domain).f;
    }

    private static void sampleNearest(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        Domain domain = call.domain(Domain.POINT);
        GeometryComponent source = componentFor(geometry, domain);
        float[] targets = source == null ? new float[0] : elementPositions(source, domain);
        KdTree tree = new KdTree(targets);
        Field position = call.field("Sample Position", DataType.VECTOR);
        call.out("Index", Field.of(DataType.INT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                out.i[k] = tree.nearest(in[0].f[3 * k], in[0].f[3 * k + 1], in[0].f[3 * k + 2]);
            }
        }, position));
    }

    private static void indexOfNearest(NodeCall call) {
        Field position = call.field("Position", DataType.VECTOR);
        Field group = call.field("Group ID", DataType.INT);
        DataType[] types = {DataType.INT, DataType.BOOL};
        Field.Multi shared = new Field.Multi(types, null, new Field[0]) {
            @Override
            Column[] compute(FieldContext context) {
                Column p = context.evalAs(position, DataType.VECTOR);
                Column g = context.evalAs(group, DataType.INT);
                Column[] out = {Column.create(DataType.INT, context.size), Column.create(DataType.BOOL, context.size)};
                for (int k = 0; k < context.size; k++) {
                    float best = Float.MAX_VALUE;
                    int bestIndex = -1;
                    for (int j = 0; j < context.size; j++) {
                        if (j == k || g.i[j] != g.i[k]) {
                            continue;
                        }
                        float dx = p.f[3 * j] - p.f[3 * k], dy = p.f[3 * j + 1] - p.f[3 * k + 1], dz = p.f[3 * j + 2] - p.f[3 * k + 2];
                        float d = dx * dx + dy * dy + dz * dz;
                        if (d < best) {
                            best = d;
                            bestIndex = j;
                        }
                    }
                    out[0].i[k] = bestIndex < 0 ? k : bestIndex;
                    out[1].i[k] = bestIndex < 0 ? 0 : 1;
                }
                return out;
            }
        };
        call.out("Index", new Field.MultiOutput(shared, 0));
        call.out("Has Neighbor", new Field.MultiOutput(shared, 1));
    }

    private static void proximity(NodeCall call) {
        GeometrySet target = call.geometry("Target");
        String element = call.prop("target_element", "FACES");
        Field position = call.field("Source Position", DataType.VECTOR);
        Surface surface = Surface.of(target, element);
        Field[] parts = Field.multi(new DataType[]{DataType.VECTOR, DataType.FLOAT, DataType.BOOL}, (in, out, n) -> {
            float[] hit = new float[3];
            for (int k = 0; k < n; k++) {
                if (surface == null) {
                    continue;
                }
                float d = surface.closest(in[0].f[3 * k], in[0].f[3 * k + 1], in[0].f[3 * k + 2], hit, null);
                out[0].setVector(k, hit[0], hit[1], hit[2]);
                out[1].f[k] = d;
                out[2].i[k] = 1;
            }
        }, position);
        call.out("Position", parts[0]);
        call.out("Distance", parts[1]);
        call.out("Is Valid", parts[2]);
    }

    private static void sampleNearestSurface(NodeCall call) {
        GeometrySet geometry = call.geometry("Mesh");
        Field value = call.field("Value");
        Field position = call.field("Sample Position", DataType.VECTOR);
        Surface surface = Surface.of(geometry, "FACES");
        MeshGeometry mesh = geometry.mesh;
        Column cornerValues = mesh == null ? null : call.context(mesh, Domain.CORNER).eval(value);
        DataType type = value.type;
        Field[] parts = Field.multi(new DataType[]{type, DataType.BOOL}, (in, out, n) -> {
            float[] hit = new float[3];
            float[] bary = new float[3];
            int[] tri = new int[4];
            for (int k = 0; k < n; k++) {
                if (surface == null || cornerValues == null) {
                    continue;
                }
                surface.closest(in[0].f[3 * k], in[0].f[3 * k + 1], in[0].f[3 * k + 2], hit, tri);
                Surface.barycentric(mesh, tri, hit, bary);
                if (type.integer) {
                    int best = bary[0] >= bary[1] && bary[0] >= bary[2] ? 0 : bary[1] >= bary[2] ? 1 : 2;
                    out[0].i[k] = cornerValues.i[tri[best]];
                } else {
                    int w = type.width;
                    for (int a = 0; a < w; a++) {
                        out[0].f[k * w + a] = cornerValues.f[tri[0] * w + a] * bary[0] + cornerValues.f[tri[1] * w + a] * bary[1]
                                + cornerValues.f[tri[2] * w + a] * bary[2];
                    }
                }
                out[1].i[k] = 1;
            }
        }, position);
        call.out("Value", parts[0]);
        call.out("Is Valid", parts[1]);
    }

    private static void raycast(NodeCall call) {
        GeometrySet target = call.geometry("Target Geometry");
        Surface surface = Surface.of(target, "FACES");
        Field source = call.field("Source Position", DataType.VECTOR);
        Field direction = call.field("Ray Direction", DataType.VECTOR);
        Field length = call.field("Ray Length", DataType.FLOAT);
        Field value = call.field("Attribute");
        MeshGeometry mesh = target.mesh;
        Column cornerValues = mesh == null ? null : call.context(mesh, Domain.CORNER).eval(value);
        DataType type = value.type;
        Field[] parts = Field.multi(new DataType[]{DataType.BOOL, DataType.VECTOR, DataType.VECTOR, DataType.FLOAT, type},
                (in, out, n) -> {
                    float[] hit = new float[7];
                    int[] tri = new int[4];
                    float[] bary = new float[3];
                    for (int k = 0; k < n; k++) {
                        if (surface == null) {
                            continue;
                        }
                        boolean found = surface.raycast(in[0].f[3 * k], in[0].f[3 * k + 1], in[0].f[3 * k + 2], in[1].f[3 * k],
                                in[1].f[3 * k + 1], in[1].f[3 * k + 2], in[2].f[k], hit, tri);
                        if (!found) {
                            continue;
                        }
                        out[0].i[k] = 1;
                        out[1].setVector(k, hit[0], hit[1], hit[2]);
                        out[2].setVector(k, hit[3], hit[4], hit[5]);
                        out[3].f[k] = hit[6];
                        if (cornerValues != null && !type.integer) {
                            Surface.barycentric(mesh, tri, hit, bary);
                            int w = type.width;
                            for (int a = 0; a < w; a++) {
                                out[4].f[k * w + a] = cornerValues.f[tri[0] * w + a] * bary[0]
                                        + cornerValues.f[tri[1] * w + a] * bary[1] + cornerValues.f[tri[2] * w + a] * bary[2];
                            }
                        }
                    }
                }, source, direction, length);
        call.out("Is Hit", parts[0]);
        call.out("Hit Position", parts[1]);
        call.out("Hit Normal", parts[2]);
        call.out("Hit Distance", parts[3]);
        call.out("Attribute", parts[4]);
    }

    private static void blur(NodeCall call) {
        Field value = call.field("Value");
        Field iterations = call.field("Iterations", DataType.INT);
        Field weight = call.field("Weight", DataType.FLOAT);
        call.out("Value", new Field.Input(value.type) {
            @Override
            protected Column compute(FieldContext context) {
                Column current = context.eval(value).copy();
                if (current.type.integer || context.component == null) {
                    return current;
                }
                int[][] neighbours = neighbours(context.component, context.domain);
                if (neighbours == null) {
                    return current;
                }
                Column w = context.evalAs(weight, DataType.FLOAT);
                int steps = iterations.singleInt();
                int width = current.type.width;
                for (int s = 0; s < steps; s++) {
                    float[] next = current.f.clone();
                    for (int k = 0; k < context.size; k++) {
                        float total = w.f[k];
                        for (int a = 0; a < width; a++) {
                            next[k * width + a] = current.f[k * width + a] * w.f[k];
                        }
                        for (int j : neighbours[k]) {
                            total += w.f[j];
                            for (int a = 0; a < width; a++) {
                                next[k * width + a] += current.f[j * width + a] * w.f[j];
                            }
                        }
                        if (total > 0f) {
                            for (int a = 0; a < width; a++) {
                                next[k * width + a] /= total;
                            }
                        } else {
                            System.arraycopy(current.f, k * width, next, k * width, width);
                        }
                    }
                    current = Column.ofFloats(current.type, next);
                }
                return current;
            }
        });
    }

    private static int[][] neighbours(GeometryComponent component, Domain domain) {
        if (component instanceof MeshGeometry mesh) {
            MeshGeometry.Topology t = mesh.topology;
            if (domain == Domain.POINT) {
                int[][] vertEdges = t.vertEdges();
                int[][] out = new int[mesh.vertCount()][];
                for (int v = 0; v < out.length; v++) {
                    out[v] = new int[vertEdges[v].length];
                    for (int k = 0; k < vertEdges[v].length; k++) {
                        int e = vertEdges[v][k];
                        out[v][k] = t.edgeVerts[2 * e] == v ? t.edgeVerts[2 * e + 1] : t.edgeVerts[2 * e];
                    }
                }
                return out;
            }
            if (domain == Domain.FACE) {
                int[][] edgeFaces = t.edgeFaces();
                int[][] out = new int[mesh.faceCount()][];
                for (int f = 0; f < out.length; f++) {
                    java.util.Set<Integer> set = new java.util.LinkedHashSet<>();
                    for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                        for (int g : edgeFaces[t.cornerEdges[c]]) {
                            if (g != f) {
                                set.add(g);
                            }
                        }
                    }
                    out[f] = set.stream().mapToInt(Integer::intValue).toArray();
                }
                return out;
            }
        }
        if (component instanceof CurveGeometry curves && domain == Domain.POINT) {
            int[][] out = new int[curves.pointCount()][];
            for (int c = 0; c < curves.curveCount(); c++) {
                int start = curves.curveOffsets[c], n = curves.curveSize(c);
                boolean cyclic = curves.cyclic(c);
                for (int k = 0; k < n; k++) {
                    List<Integer> list = new ArrayList<>();
                    if (k > 0 || cyclic) {
                        list.add(start + (k + n - 1) % n);
                    }
                    if (k < n - 1 || cyclic) {
                        list.add(start + (k + 1) % n);
                    }
                    out[start + k] = list.stream().mapToInt(Integer::intValue).toArray();
                }
            }
            return out;
        }
        return null;
    }

    private static void duplicateElements(NodeCall call) {
        GeometrySet geometry = call.geometry("Geometry");
        String domain = call.prop("domain", "POINT");
        Field amount = call.field("Amount", DataType.INT);
        Field selection = call.field("Selection", DataType.BOOL);
        String indexName = MeshPrimitives.anonymous("duplicate");
        GeometrySet out = GeometrySet.EMPTY;
        if ("INSTANCE".equals(domain) && geometry.instances != null) {
            InstanceGeometry instances = geometry.instances;
            int[][] plan = plan(call, instances, Domain.INSTANCE, amount, selection);
            Attributes attributes = new Attributes();
            for (Attributes.Attribute a : instances.attributes().all()) {
                attributes.put(a.name(), Domain.INSTANCE, a.data().gather(plan[0]));
            }
            attributes.put(indexName, Domain.INSTANCE, Column.ofInts(DataType.INT, plan[1]));
            out = GeometrySet.of(new InstanceGeometry(plan[0].length, instances.references, attributes));
        } else if ("POINT".equals(domain)) {
            List<GeometryComponent> parts = new ArrayList<>();
            for (GeometryComponent component : geometry.components()) {
                if (component instanceof InstanceGeometry || !component.hasDomain(Domain.POINT)) {
                    continue;
                }
                int[][] plan = plan(call, component, Domain.POINT, amount, selection);
                Attributes attributes = new Attributes();
                for (Attributes.Attribute a : component.attributes().all()) {
                    Column values = component.adapt(a.data(), a.domain(), Domain.POINT);
                    attributes.put(a.name(), Domain.POINT, values.gather(plan[0]));
                }
                attributes.put(indexName, Domain.POINT, Column.ofInts(DataType.INT, plan[1]));
                parts.add(new PointGeometry(plan[0].length, attributes));
            }
            List<PointGeometry> clouds = new ArrayList<>();
            for (GeometryComponent part : parts) {
                clouds.add((PointGeometry) part);
            }
            if (!clouds.isEmpty()) {
                out = GeometrySet.of(GeometryOps.joinPoints(clouds));
            }
        } else {
            throw call.unsupported("duplicating " + domain + " elements");
        }
        call.out("Geometry", out);
        call.out("Duplicate Index", Inputs.named(indexName, DataType.INT));
    }

    /** For Duplicate Elements: the source of every copy, and which copy it is. */
    private static int[][] plan(NodeCall call, GeometryComponent component, Domain domain, Field amount, Field selection) {
        FieldContext context = call.context(component, domain);
        Column counts = context.evalAs(amount, DataType.INT);
        boolean[] sel = call.selection(selection, component, domain);
        List<Integer> source = new ArrayList<>();
        List<Integer> copy = new ArrayList<>();
        for (int k = 0; k < context.size; k++) {
            if (!sel[k]) {
                continue;
            }
            for (int c = 0; c < Math.max(0, counts.i[k]); c++) {
                source.add(k);
                copy.add(c);
            }
        }
        return new int[][]{source.stream().mapToInt(Integer::intValue).toArray(), copy.stream().mapToInt(Integer::intValue).toArray()};
    }
}
