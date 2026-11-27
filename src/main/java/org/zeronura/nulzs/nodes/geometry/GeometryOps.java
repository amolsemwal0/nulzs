package org.zeronura.nulzs.nodes.geometry;

import org.zeronura.nulzs.nodes.tex.Hash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The geometry operations many nodes share: transforming, joining (attributes and material slots merged as
 * Blender merges them), realizing instances, and keeping a subset of a mesh's elements.
 */
public final class GeometryOps {

    private GeometryOps() {
    }

    // --- Transform -----------------------------------------------------------------------------------------

    public static GeometrySet transform(GeometrySet geometry, float[] m) {
        GeometrySet out = geometry;
        if (geometry.mesh != null) {
            out = out.withMesh(transform(geometry.mesh, m));
        }
        if (geometry.curves != null) {
            out = out.withCurves(transform(geometry.curves, m));
        }
        if (geometry.points != null) {
            out = out.withPoints(transform(geometry.points, m));
        }
        if (geometry.instances != null) {
            out = out.withInstances(transform(geometry.instances, m));
        }
        return out;
    }

    private static float[] transformPoints(float[] p, float[] m) {
        float[] out = new float[p.length];
        for (int k = 0; k < p.length; k += 3) {
            Transforms.transformPoint(m, 0, p, k, out, k);
        }
        return out;
    }

    private static float[] transformNormals(float[] n, float[] m) {
        float[] nm = new float[9];
        Transforms.normalMatrix(m, 0, nm);
        float[] out = new float[n.length];
        for (int k = 0; k < n.length; k += 3) {
            float x = n[k], y = n[k + 1], z = n[k + 2];
            float ox = nm[0] * x + nm[3] * y + nm[6] * z;
            float oy = nm[1] * x + nm[4] * y + nm[7] * z;
            float oz = nm[2] * x + nm[5] * y + nm[8] * z;
            float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (len > 0f) {
                ox /= len;
                oy /= len;
                oz /= len;
            }
            out[k] = ox;
            out[k + 1] = oy;
            out[k + 2] = oz;
        }
        return out;
    }

    public static MeshGeometry transform(MeshGeometry mesh, float[] m) {
        Attributes attributes = mesh.attributes().copy();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, transformPoints(mesh.positions(), m)));
        Column custom = attributes.column(".corner_normals");
        if (custom != null) {
            attributes.put(".corner_normals", Domain.CORNER, Column.ofFloats(DataType.VECTOR, transformNormals(custom.f, m)));
        }
        MeshGeometry out = new MeshGeometry(mesh.topology, attributes, mesh.materials);
        if (Transforms.determinant3(m, 0) < 0f) {
            out = flipFaces(out, null);
        }
        return out;
    }

    public static CurveGeometry transform(CurveGeometry curves, float[] m) {
        Attributes attributes = curves.attributes().copy();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, transformPoints(curves.positions(), m)));
        for (String handle : new String[]{"handle_left", "handle_right"}) {
            Column c = attributes.column(handle);
            if (c != null) {
                attributes.put(handle, Domain.POINT, Column.ofFloats(DataType.VECTOR, transformPoints(c.f, m)));
            }
        }
        Column normal = attributes.column("custom_normal");
        if (normal != null) {
            attributes.put("custom_normal", Domain.POINT, Column.ofFloats(DataType.VECTOR, transformNormals(normal.f, m)));
        }
        return new CurveGeometry(curves.curveOffsets, attributes, curves.materials);
    }

    public static PointGeometry transform(PointGeometry points, float[] m) {
        Attributes attributes = points.attributes().copy();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, transformPoints(points.positions(), m)));
        return new PointGeometry(points.count(), attributes);
    }

    public static InstanceGeometry transform(InstanceGeometry instances, float[] m) {
        float[] t = instances.transforms();
        float[] out = new float[t.length];
        for (int i = 0; i < instances.count(); i++) {
            Transforms.mul(m, 0, t, 16 * i, out, 16 * i);
        }
        return instances.withTransforms(out);
    }

    /**
     * Reverses the winding of the selected faces (all when null), as Blender does: the first corner stays and the
     * rest run backwards. Corner attributes move with their corners.
     */
    public static MeshGeometry flipFaces(MeshGeometry mesh, boolean[] selection) {
        MeshGeometry.Topology t = mesh.topology;
        int corners = t.cornerCount();
        int[] map = new int[corners];
        int[] cornerVerts = new int[corners];
        int[] cornerEdges = new int[corners];
        for (int f = 0; f < t.faceCount(); f++) {
            int start = t.faceOffsets[f];
            int n = t.faceOffsets[f + 1] - start;
            boolean flip = selection == null || selection[f];
            for (int k = 0; k < n; k++) {
                int c = start + k;
                if (!flip) {
                    map[c] = c;
                    cornerVerts[c] = t.cornerVerts[c];
                    cornerEdges[c] = t.cornerEdges[c];
                    continue;
                }
                int old = k == 0 ? 0 : n - k;
                map[c] = start + old;
                cornerVerts[c] = t.cornerVerts[start + old];
                cornerEdges[c] = t.cornerEdges[start + Math.floorMod(old - 1, n)];
            }
        }
        MeshGeometry.Topology topology = new MeshGeometry.Topology(t.vertCount, t.edgeVerts, t.faceOffsets,
                cornerVerts, cornerEdges);
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : mesh.attributes().all()) {
            Column data = a.domain() == Domain.CORNER ? a.data().gather(map) : a.data();
            if (a.name().equals(".corner_normals")) {
                float[] values = data.f.clone();
                for (int c = 0; c < corners; c++) {
                    int f = t.cornerFaces()[c];
                    if (selection == null || selection[f]) {
                        values[3 * c] = -values[3 * c];
                        values[3 * c + 1] = -values[3 * c + 1];
                        values[3 * c + 2] = -values[3 * c + 2];
                    }
                }
                data = Column.ofFloats(DataType.VECTOR, values);
            }
            attributes.put(a.name(), a.domain(), data);
        }
        return new MeshGeometry(topology, attributes, mesh.materials);
    }

    // --- Join ----------------------------------------------------------------------------------------------

    /** Merges attribute sets: the union of names, each taking the first part's domain and type. */
    private static Map<String, Attributes.Attribute> attributeLayout(List<? extends GeometryComponent> parts) {
        Map<String, Attributes.Attribute> layout = new LinkedHashMap<>();
        for (GeometryComponent part : parts) {
            for (Attributes.Attribute a : part.attributes().all()) {
                Attributes.Attribute existing = layout.get(a.name());
                if (existing == null) {
                    layout.put(a.name(), a);
                } else if (rank(a.data().type) > rank(existing.data().type)) {
                    layout.put(a.name(), new Attributes.Attribute(a.name(), existing.domain(), a.data()));
                }
            }
        }
        return layout;
    }

    private static int rank(DataType type) {
        return switch (type) {
            case BOOL -> 0;
            case INT -> 1;
            case FLOAT -> 2;
            case FLOAT2 -> 3;
            case VECTOR -> 4;
            case COLOR -> 5;
            case ROTATION -> 6;
            case MATRIX -> 7;
        };
    }

    private static Column attributeOf(GeometryComponent part, String name, Domain domain, DataType type) {
        Column c = part.read(name, domain, type);
        if (c == null) {
            int n = part.count(domain);
            c = Column.create(type, n);
            if (type == DataType.ROTATION) {
                for (int k = 0; k < n; k++) {
                    c.f[4 * k] = 1f;
                }
            } else if (type == DataType.MATRIX) {
                for (int k = 0; k < n; k++) {
                    Transforms.identity(c.f, 16 * k);
                }
            } else if (type == DataType.COLOR) {
                for (int k = 0; k < n; k++) {
                    c.f[4 * k + 3] = 1f;
                }
            } else if (name.equals("radius") && part instanceof CurveGeometry) {
                java.util.Arrays.fill(c.f, 1f);
            }
        }
        return c;
    }

    /** Joined material slots, in order of first appearance, and each part's remapping into them. */
    private static List<String> mergeMaterials(List<List<String>> lists, List<int[]> remaps) {
        List<String> merged = new ArrayList<>();
        for (List<String> list : lists) {
            int[] remap = new int[Math.max(1, list.size())];
            for (int k = 0; k < list.size(); k++) {
                int at = merged.indexOf(list.get(k));
                if (at < 0) {
                    at = merged.size();
                    merged.add(list.get(k));
                }
                remap[k] = at;
            }
            if (list.isEmpty()) {
                remap[0] = 0;
            }
            remaps.add(remap);
        }
        return merged;
    }

    private static Column remapMaterialIndex(Column materialIndex, int[] remap) {
        Column out = Column.create(DataType.INT, materialIndex.size);
        for (int k = 0; k < materialIndex.size; k++) {
            int idx = materialIndex.getInt(k);
            out.i[k] = idx >= 0 && idx < remap.length ? remap[idx] : remap.length > 0 ? remap[0] : 0;
        }
        return out;
    }

    public static MeshGeometry joinMeshes(List<MeshGeometry> meshes) {
        if (meshes.size() == 1) {
            return meshes.get(0);
        }
        int verts = 0, edges = 0, faces = 0, corners = 0;
        for (MeshGeometry m : meshes) {
            verts += m.vertCount();
            edges += m.edgeCount();
            faces += m.faceCount();
            corners += m.cornerCount();
        }
        int[] edgeVerts = new int[edges * 2];
        int[] faceOffsets = new int[faces + 1];
        int[] cornerVerts = new int[corners];
        int[] cornerEdges = new int[corners];
        int v0 = 0, e0 = 0, f0 = 0, c0 = 0;
        for (MeshGeometry m : meshes) {
            MeshGeometry.Topology t = m.topology;
            for (int k = 0; k < t.edgeVerts.length; k++) {
                edgeVerts[2 * e0 + k] = t.edgeVerts[k] + v0;
            }
            for (int f = 0; f < t.faceCount(); f++) {
                faceOffsets[f0 + f] = t.faceOffsets[f] + c0;
            }
            for (int c = 0; c < t.cornerCount(); c++) {
                cornerVerts[c0 + c] = t.cornerVerts[c] + v0;
                cornerEdges[c0 + c] = t.cornerEdges[c] + e0;
            }
            v0 += t.vertCount;
            e0 += t.edgeCount();
            f0 += t.faceCount();
            c0 += t.cornerCount();
        }
        faceOffsets[faces] = corners;
        MeshGeometry.Topology topology = new MeshGeometry.Topology(verts, edgeVerts, faceOffsets, cornerVerts, cornerEdges);

        List<List<String>> materialLists = new ArrayList<>();
        for (MeshGeometry m : meshes) {
            materialLists.add(m.materials);
        }
        List<int[]> remaps = new ArrayList<>();
        List<String> materials = mergeMaterials(materialLists, remaps);
        boolean anyMaterials = !materials.isEmpty();

        Attributes attributes = new Attributes();
        Map<String, Attributes.Attribute> layout = attributeLayout(meshes);
        if (anyMaterials && !layout.containsKey("material_index")) {
            layout.put("material_index", new Attributes.Attribute("material_index", Domain.FACE,
                    Column.create(DataType.INT, 0)));
        }
        for (Attributes.Attribute a : layout.values()) {
            List<Column> parts = new ArrayList<>();
            for (int k = 0; k < meshes.size(); k++) {
                Column c = attributeOf(meshes.get(k), a.name(), a.domain(), a.data().type);
                if (a.name().equals("material_index") && anyMaterials) {
                    c = remapMaterialIndex(c, remaps.get(k));
                }
                if (a.name().equals(".corner_normals")) {
                    c = meshes.get(k).attributes().has(".corner_normals") ? c
                            : Column.ofFloats(DataType.VECTOR, meshes.get(k).cornerNormals().clone());
                }
                parts.add(c);
            }
            attributes.put(a.name(), a.domain(), Column.concat(a.data().type, parts));
        }
        return new MeshGeometry(topology, attributes, materials);
    }

    public static CurveGeometry joinCurves(List<CurveGeometry> curves) {
        if (curves.size() == 1) {
            return curves.get(0);
        }
        int total = 0;
        int count = 0;
        for (CurveGeometry c : curves) {
            total += c.pointCount();
            count += c.curveCount();
        }
        int[] offsets = new int[count + 1];
        int at = 0, p0 = 0;
        for (CurveGeometry c : curves) {
            for (int k = 0; k < c.curveCount(); k++) {
                offsets[at++] = c.curveOffsets[k] + p0;
            }
            p0 += c.pointCount();
        }
        offsets[count] = total;
        List<List<String>> lists = new ArrayList<>();
        for (CurveGeometry c : curves) {
            lists.add(c.materials);
        }
        List<int[]> remaps = new ArrayList<>();
        List<String> materials = mergeMaterials(lists, remaps);
        Attributes attributes = new Attributes();
        Map<String, Attributes.Attribute> layout = attributeLayout(curves);
        for (String builtIn : new String[]{"curve_type", "cyclic", "resolution"}) {
            if (!layout.containsKey(builtIn)) {
                continue;
            }
            Attributes.Attribute a = layout.get(builtIn);
            List<Column> parts = new ArrayList<>();
            for (CurveGeometry c : curves) {
                Column col = c.attributes().column(builtIn);
                if (col == null) {
                    Object def = builtIn.equals("resolution") ? (Object) 12 : builtIn.equals("curve_type") ? 0 : 0;
                    col = Column.constant(a.data().type, def, c.curveCount());
                }
                parts.add(col);
            }
            attributes.put(builtIn, Domain.CURVE, Column.concat(a.data().type, parts));
            layout.remove(builtIn);
        }
        for (Attributes.Attribute a : layout.values()) {
            List<Column> parts = new ArrayList<>();
            for (int k = 0; k < curves.size(); k++) {
                Column c = attributeOf(curves.get(k), a.name(), a.domain(), a.data().type);
                if (a.name().equals("material_index") && !materials.isEmpty()) {
                    c = remapMaterialIndex(c, remaps.get(k));
                }
                parts.add(c);
            }
            attributes.put(a.name(), a.domain(), Column.concat(a.data().type, parts));
        }
        return new CurveGeometry(offsets, attributes, materials);
    }

    public static PointGeometry joinPoints(List<PointGeometry> points) {
        if (points.size() == 1) {
            return points.get(0);
        }
        int total = 0;
        for (PointGeometry p : points) {
            total += p.count();
        }
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : attributeLayout(points).values()) {
            List<Column> parts = new ArrayList<>();
            for (PointGeometry p : points) {
                parts.add(attributeOf(p, a.name(), Domain.POINT, a.data().type));
            }
            attributes.put(a.name(), Domain.POINT, Column.concat(a.data().type, parts));
        }
        return new PointGeometry(total, attributes);
    }

    public static InstanceGeometry joinInstances(List<InstanceGeometry> instances) {
        if (instances.size() == 1) {
            return instances.get(0);
        }
        List<GeometrySet> references = new ArrayList<>();
        int total = 0;
        for (InstanceGeometry i : instances) {
            total += i.count();
        }
        int[] handles = new int[total];
        int at = 0;
        for (InstanceGeometry i : instances) {
            int base = references.size();
            references.addAll(i.references);
            int[] h = i.handles();
            for (int k = 0; k < i.count(); k++) {
                handles[at++] = h[k] + base;
            }
        }
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : attributeLayout(instances).values()) {
            if (a.name().equals(InstanceGeometry.REFERENCE)) {
                continue;
            }
            List<Column> parts = new ArrayList<>();
            for (InstanceGeometry i : instances) {
                parts.add(attributeOf(i, a.name(), Domain.INSTANCE, a.data().type));
            }
            attributes.put(a.name(), Domain.INSTANCE, Column.concat(a.data().type, parts));
        }
        attributes.put(InstanceGeometry.REFERENCE, Domain.INSTANCE, Column.ofInts(DataType.INT, handles));
        return new InstanceGeometry(total, references, attributes);
    }

    /** Join Geometry: each component kind joined across the inputs. */
    public static GeometrySet join(List<GeometrySet> sets) {
        List<MeshGeometry> meshes = new ArrayList<>();
        List<CurveGeometry> curves = new ArrayList<>();
        List<PointGeometry> points = new ArrayList<>();
        List<InstanceGeometry> instances = new ArrayList<>();
        String name = "";
        for (GeometrySet set : sets) {
            if (set.mesh != null && set.mesh.vertCount() > 0) {
                meshes.add(set.mesh);
            }
            if (set.curves != null && set.curves.pointCount() > 0) {
                curves.add(set.curves);
            }
            if (set.points != null && set.points.count() > 0) {
                points.add(set.points);
            }
            if (set.instances != null && set.instances.count() > 0) {
                instances.add(set.instances);
            }
            if (name.isEmpty()) {
                name = set.name;
            }
        }
        return new GeometrySet(meshes.isEmpty() ? null : joinMeshes(meshes), curves.isEmpty() ? null : joinCurves(curves),
                points.isEmpty() ? null : joinPoints(points), instances.isEmpty() ? null : joinInstances(instances), name);
    }

    // --- Realize -------------------------------------------------------------------------------------------

    /** Every instance (to any depth) turned into real geometry and joined with what was already real. */
    public static GeometrySet realize(GeometrySet geometry) {
        if (geometry.instances == null || geometry.instances.count() == 0) {
            return geometry.withInstances(null);
        }
        List<GeometrySet> parts = new ArrayList<>();
        parts.add(geometry.withInstances(null));
        InstanceGeometry instances = geometry.instances;
        float[] transforms = instances.transforms();
        Column instanceIds = instances.attributes().column("id");
        List<Attributes.Attribute> carried = new ArrayList<>();
        for (Attributes.Attribute a : instances.attributes().all()) {
            if (!a.name().equals(InstanceGeometry.TRANSFORM) && !a.name().equals(InstanceGeometry.REFERENCE)
                    && !a.name().equals("id")) {
                carried.add(a);
            }
        }
        Map<Integer, GeometrySet> realizedReferences = new java.util.HashMap<>();
        for (int i = 0; i < instances.count(); i++) {
            int handle = instances.handles()[i];
            GeometrySet reference = realizedReferences.computeIfAbsent(handle, h -> realize(instances.references.get(h)));
            float[] m = java.util.Arrays.copyOfRange(transforms, 16 * i, 16 * i + 16);
            GeometrySet placed = transform(reference, m);
            if (!carried.isEmpty() || instanceIds != null) {
                placed = stampInstanceAttributes(placed, carried, i, instanceIds == null ? -1 : instanceIds.getInt(i),
                        instanceIds != null);
            }
            parts.add(placed);
        }
        return join(parts);
    }

    /** Instance attributes copied onto every point of the realized geometry, and ids combined with the instance's. */
    private static GeometrySet stampInstanceAttributes(GeometrySet geometry, List<Attributes.Attribute> carried,
                                                       int instance, int instanceId, boolean hasId) {
        GeometrySet out = geometry;
        for (GeometryComponent component : geometry.components()) {
            if (component instanceof InstanceGeometry) {
                continue;
            }
            Attributes attributes = component.attributes().copy();
            int n = component.count(Domain.POINT);
            for (Attributes.Attribute a : carried) {
                if (attributes.has(a.name())) {
                    continue;
                }
                Column single = a.data().slice(instance, 1);
                attributes.put(a.name(), Domain.POINT, Column.constant(a.data().type, single.firstValue() instanceof float[] arr
                        ? arr : single.firstValue(), n));
            }
            if (hasId) {
                Column ids = attributes.column("id");
                int[] combined = new int[n];
                for (int k = 0; k < n; k++) {
                    combined[k] = Hash.hash(instanceId, ids != null ? ids.getInt(k) : k);
                }
                attributes.put("id", Domain.POINT, Column.ofInts(DataType.INT, combined));
            }
            out = out.replace(component, component.withAttributes(attributes));
        }
        return out;
    }

    // --- Subsets -------------------------------------------------------------------------------------------

    /**
     * The mesh keeping only the flagged vertices, edges and faces (an edge needs both its vertices, a face all of
     * its corners' vertices and edges), with every attribute carried along.
     */
    public static MeshGeometry meshSubset(MeshGeometry mesh, boolean[] keepVerts, boolean[] keepEdges, boolean[] keepFaces) {
        MeshGeometry.Topology t = mesh.topology;
        int[] vertMap = new int[t.vertCount];
        int verts = 0;
        for (int v = 0; v < t.vertCount; v++) {
            vertMap[v] = keepVerts[v] ? verts++ : -1;
        }
        int[] edgeMap = new int[t.edgeCount()];
        int edges = 0;
        for (int e = 0; e < t.edgeCount(); e++) {
            boolean keep = keepEdges[e] && vertMap[t.edgeVerts[2 * e]] >= 0 && vertMap[t.edgeVerts[2 * e + 1]] >= 0;
            edgeMap[e] = keep ? edges++ : -1;
        }
        List<Integer> keptFaces = new ArrayList<>();
        int corners = 0;
        for (int f = 0; f < t.faceCount(); f++) {
            if (!keepFaces[f]) {
                continue;
            }
            boolean ok = true;
            for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1] && ok; c++) {
                ok = vertMap[t.cornerVerts[c]] >= 0 && edgeMap[t.cornerEdges[c]] >= 0;
            }
            if (ok) {
                keptFaces.add(f);
                corners += t.faceSize(f);
            }
        }
        int[] faceIndex = new int[keptFaces.size()];
        int[] cornerIndex = new int[corners];
        int[] faceOffsets = new int[keptFaces.size() + 1];
        int[] cornerVerts = new int[corners];
        int[] cornerEdges = new int[corners];
        int at = 0;
        for (int k = 0; k < keptFaces.size(); k++) {
            int f = keptFaces.get(k);
            faceIndex[k] = f;
            faceOffsets[k] = at;
            for (int c = t.faceOffsets[f]; c < t.faceOffsets[f + 1]; c++) {
                cornerIndex[at] = c;
                cornerVerts[at] = vertMap[t.cornerVerts[c]];
                cornerEdges[at] = edgeMap[t.cornerEdges[c]];
                at++;
            }
        }
        faceOffsets[keptFaces.size()] = corners;
        int[] edgeVerts = new int[edges * 2];
        int[] edgeIndex = new int[edges];
        for (int e = 0; e < t.edgeCount(); e++) {
            if (edgeMap[e] >= 0) {
                edgeIndex[edgeMap[e]] = e;
                edgeVerts[2 * edgeMap[e]] = vertMap[t.edgeVerts[2 * e]];
                edgeVerts[2 * edgeMap[e] + 1] = vertMap[t.edgeVerts[2 * e + 1]];
            }
        }
        int[] vertIndex = new int[verts];
        for (int v = 0; v < t.vertCount; v++) {
            if (vertMap[v] >= 0) {
                vertIndex[vertMap[v]] = v;
            }
        }
        MeshGeometry.Topology topology = new MeshGeometry.Topology(verts, edgeVerts, faceOffsets, cornerVerts, cornerEdges);
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : mesh.attributes().all()) {
            int[] map = switch (a.domain()) {
                case POINT -> vertIndex;
                case EDGE -> edgeIndex;
                case FACE -> faceIndex;
                case CORNER -> cornerIndex;
                default -> null;
            };
            if (map != null) {
                attributes.put(a.name(), a.domain(), a.data().gather(map));
            }
        }
        return new MeshGeometry(topology, attributes, mesh.materials);
    }

    /** Points, curves' points or instances kept by flag, attributes carried along. */
    public static PointGeometry pointsSubset(PointGeometry points, boolean[] keep) {
        int[] map = indices(keep);
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : points.attributes().all()) {
            attributes.put(a.name(), a.domain(), a.data().gather(map));
        }
        return new PointGeometry(map.length, attributes);
    }

    public static InstanceGeometry instancesSubset(InstanceGeometry instances, boolean[] keep) {
        int[] map = indices(keep);
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : instances.attributes().all()) {
            attributes.put(a.name(), a.domain(), a.data().gather(map));
        }
        return new InstanceGeometry(map.length, instances.references, attributes).compact();
    }

    /** Curves keeping only the flagged points; a curve left with no points goes. */
    public static CurveGeometry curvesSubset(CurveGeometry curves, boolean[] keepPoints, boolean[] keepCurves) {
        List<Integer> pointList = new ArrayList<>();
        List<Integer> curveList = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (int c = 0; c < curves.curveCount(); c++) {
            if (keepCurves != null && !keepCurves[c]) {
                continue;
            }
            int size = 0;
            for (int p = curves.curveOffsets[c]; p < curves.curveOffsets[c + 1]; p++) {
                if (keepPoints == null || keepPoints[p]) {
                    pointList.add(p);
                    size++;
                }
            }
            if (size > 0) {
                curveList.add(c);
                sizes.add(size);
            }
        }
        int[] offsets = new int[sizes.size() + 1];
        for (int k = 0; k < sizes.size(); k++) {
            offsets[k + 1] = offsets[k] + sizes.get(k);
        }
        int[] pointMap = pointList.stream().mapToInt(Integer::intValue).toArray();
        int[] curveMap = curveList.stream().mapToInt(Integer::intValue).toArray();
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : curves.attributes().all()) {
            int[] map = a.domain() == Domain.POINT ? pointMap : curveMap;
            attributes.put(a.name(), a.domain(), a.data().gather(map));
        }
        return new CurveGeometry(offsets, attributes, curves.materials);
    }

    public static int[] indices(boolean[] keep) {
        int n = 0;
        for (boolean k : keep) {
            if (k) {
                n++;
            }
        }
        int[] out = new int[n];
        int at = 0;
        for (int k = 0; k < keep.length; k++) {
            if (keep[k]) {
                out[at++] = k;
            }
        }
        return out;
    }

    /** A component with one attribute set (replacing any of that name). */
    public static GeometryComponent withAttribute(GeometryComponent component, String name, Domain domain, Column data) {
        Attributes attributes = component.attributes().copy();
        attributes.put(name, domain, data);
        return component.withAttributes(attributes);
    }
}
