package org.zeronura.nulzs.nodes.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Curve primitives and curve operations. */
final class CurveNodes {

    private CurveNodes() {
    }

    static void register(Map<String, NodeImpl> map) {
        map.put("GeometryNodeCurvePrimitiveLine", CurveNodes::line);
        map.put("GeometryNodeCurvePrimitiveCircle", CurveNodes::circle);
        map.put("GeometryNodeCurveArc", CurveNodes::arc);
        map.put("GeometryNodeCurveSpiral", CurveNodes::spiral);
        map.put("GeometryNodeCurveStar", CurveNodes::star);
        map.put("GeometryNodeCurvePrimitiveQuadrilateral", CurveNodes::quadrilateral);
        map.put("GeometryNodeCurvePrimitiveBezierSegment", CurveNodes::bezierSegment);
        map.put("GeometryNodeCurveQuadraticBezier", CurveNodes::quadraticBezier);
        map.put("GeometryNodeCurveToMesh", CurveNodes::curveToMesh);
        map.put("GeometryNodeResampleCurve", CurveNodes::resample);
        map.put("GeometryNodeCurveToPoints", CurveNodes::curveToPoints);
        map.put("GeometryNodeSetCurveRadius", call -> setPointAttribute(call, "Curve", "radius", "Radius", DataType.FLOAT));
        map.put("GeometryNodeSetCurveTilt", call -> setPointAttribute(call, "Curve", "tilt", "Tilt", DataType.FLOAT));
        map.put("GeometryNodeSetPointRadius", call -> setPointsRadius(call));
        map.put("GeometryNodeSetCurveNormal", CurveNodes::setNormal);
        map.put("GeometryNodeSetSplineCyclic", call -> setCurveAttribute(call, "cyclic", "Cyclic", DataType.BOOL));
        map.put("GeometryNodeSetSplineResolution", call -> setCurveAttribute(call, "resolution", "Resolution", DataType.INT));
        map.put("GeometryNodeCurveSplineType", CurveNodes::splineType);
        map.put("GeometryNodeReverseCurve", CurveNodes::reverse);
        map.put("GeometryNodeSubdivideCurve", CurveNodes::subdivide);
        map.put("GeometryNodeTrimCurve", CurveNodes::trim);
        map.put("GeometryNodeFillCurve", CurveNodes::fill);
        map.put("GeometryNodeCurveLength", call -> {
            CurveGeometry curves = call.geometry("Curve").curves;
            float total = 0f;
            if (curves != null) {
                for (int c = 0; c < curves.curveCount(); c++) {
                    total += curves.evaluated(c).totalLength();
                }
            }
            call.out("Length", Field.constant(DataType.FLOAT, total));
        });
        map.put("GeometryNodeSampleCurve", CurveNodes::sampleCurve);
        map.put("GeometryNodeCurveSetHandles", call -> call.out("Curve", call.geometry("Curve")));
        map.put("GeometryNodeSetCurveHandlePositions", CurveNodes::setHandlePositions);
        map.put("GeometryNodeMeshToCurve", CurveNodes::meshToCurve);
    }

    private static GeometrySet curveOut(float[] positions, boolean cyclic, int type) {
        return GeometrySet.of(CurveGeometry.build(positions, new int[]{positions.length / 3}, new boolean[]{cyclic}, type));
    }

    // --- Primitives ------------------------------------------------------------------------------------------

    private static void line(NodeCall call) {
        float[] start = call.v("Start");
        float[] end;
        if ("DIRECTION".equals(call.prop("mode", "POINTS"))) {
            float[] dir = normalized(call.v("Direction"));
            float length = call.f("Length");
            end = new float[]{start[0] + dir[0] * length, start[1] + dir[1] * length, start[2] + dir[2] * length};
        } else {
            end = call.v("End");
        }
        call.out("Curve", curveOut(new float[]{start[0], start[1], start[2], end[0], end[1], end[2]}, false,
                CurveGeometry.POLY));
    }

    private static void circle(NodeCall call) {
        int resolution = call.i("Resolution");
        if (resolution < 3) {
            call.out("Curve", GeometrySet.EMPTY);
            return;
        }
        float[] p = new float[resolution * 3];
        if ("POINTS".equals(call.prop("mode", "RADIUS"))) {
            float[] a = call.v("Point 1"), b = call.v("Point 2"), c = call.v("Point 3");
            float[][] circle = circleThrough(a, b, c);
            if (circle == null) {
                call.out("Curve", GeometrySet.EMPTY);
                return;
            }
            float[] center = circle[0], u = circle[1], v = circle[2];
            float r = circle[3][0];
            for (int i = 0; i < resolution; i++) {
                double t = 2 * Math.PI * i / resolution;
                for (int k = 0; k < 3; k++) {
                    p[3 * i + k] = (float) (center[k] + r * (Math.cos(t) * u[k] + Math.sin(t) * v[k]));
                }
            }
            call.out("Center", Field.constant(DataType.VECTOR, center));
        } else {
            float radius = call.f("Radius");
            float step = (float) (2.0 * Math.PI / resolution);
            for (int i = 0; i < resolution; i++) {
                p[3 * i] = radius * (float) Math.cos(step * i);
                p[3 * i + 1] = radius * (float) Math.sin(step * i);
            }
        }
        call.out("Curve", curveOut(p, true, CurveGeometry.POLY));
    }

    /** Centre, two in-plane axes (u towards the first point) and radius of the circle through three points. */
    private static float[][] circleThrough(float[] a, float[] b, float[] c) {
        float[] ab = sub(b, a), ac = sub(c, a);
        float[] n = cross(ab, ac);
        float n2 = dot(n, n);
        if (n2 < 1e-12f) {
            return null;
        }
        float[] t1 = cross(n, ab);
        float[] t2 = cross(ac, n);
        float ab2 = dot(ab, ab), ac2 = dot(ac, ac);
        float[] center = new float[3];
        for (int k = 0; k < 3; k++) {
            center[k] = a[k] + (ac2 * t1[k] + ab2 * t2[k]) / (2f * n2);
        }
        float[] u = normalized(sub(a, center));
        float[] v = normalized(cross(normalized(n), u));
        float r = (float) Math.sqrt(dot(sub(a, center), sub(a, center)));
        return new float[][]{center, u, v, {r}};
    }

    private static void arc(NodeCall call) {
        int resolution = Math.max(2, call.i("Resolution"));
        boolean connect = call.b("Connect Center");
        boolean invert = call.b("Invert Arc");
        List<Float> pts = new ArrayList<>();
        float[] center;
        float[] normal = {0f, 0f, 1f};
        float radius;
        if ("POINTS".equals(call.prop("mode", "RADIUS"))) {
            float[] a = call.v("Start"), m = call.v("Middle"), b = call.v("End");
            float[][] circle = circleThrough(a, m, b);
            if (circle == null) {
                float[] p = {a[0], a[1], a[2], b[0], b[1], b[2]};
                call.out("Curve", curveOut(p, false, CurveGeometry.POLY));
                return;
            }
            center = circle[0];
            float[] u = circle[1], v = circle[2];
            radius = circle[3][0];
            normal = normalized(cross(u, v));
            float angleB = angleIn(sub(b, center), u, v);
            float angleM = angleIn(sub(m, center), u, v);
            float sweep = angleM <= angleB ? angleB : angleB - (float) (2 * Math.PI);
            if (invert) {
                sweep = sweep > 0 ? sweep - (float) (2 * Math.PI) : sweep + (float) (2 * Math.PI);
            }
            for (int i = 0; i < resolution; i++) {
                double t = sweep * i / (resolution - 1);
                for (int k = 0; k < 3; k++) {
                    pts.add((float) (center[k] + radius * (Math.cos(t) * u[k] + Math.sin(t) * v[k])));
                }
            }
        } else {
            radius = call.f("Radius");
            float start = call.f("Start Angle");
            float sweep = call.f("Sweep Angle");
            if (invert) {
                start += sweep;
                sweep = (float) (2 * Math.PI) - sweep;
            }
            center = new float[3];
            for (int i = 0; i < resolution; i++) {
                double t = start + sweep * i / (resolution - 1);
                pts.add((float) (radius * Math.cos(t)));
                pts.add((float) (radius * Math.sin(t)));
                pts.add(0f);
            }
        }
        if (connect) {
            pts.add(center[0]);
            pts.add(center[1]);
            pts.add(center[2]);
        }
        float[] p = new float[pts.size()];
        for (int k = 0; k < p.length; k++) {
            p[k] = pts.get(k);
        }
        call.out("Curve", curveOut(p, connect, CurveGeometry.POLY));
        call.out("Center", Field.constant(DataType.VECTOR, center));
        call.out("Normal", Field.constant(DataType.VECTOR, normal));
        call.out("Radius", Field.constant(DataType.FLOAT, radius));
    }

    private static float angleIn(float[] d, float[] u, float[] v) {
        float a = (float) Math.atan2(dot(d, v), dot(d, u));
        return a < 0 ? a + (float) (2 * Math.PI) : a;
    }

    private static void spiral(NodeCall call) {
        int resolution = call.i("Resolution");
        float rotations = call.f("Rotations");
        float startRadius = call.f("Start Radius"), endRadius = call.f("End Radius");
        float height = call.f("Height");
        // Blender passes the Reverse flag straight through as the winding: unticked turns clockwise.
        boolean direction = call.b("Reverse");
        int total = Math.max((int) (resolution * rotations), 1);
        float deltaRadius = (endRadius - startRadius) / total;
        float deltaHeight = height / total;
        float deltaTheta = (float) (Math.PI * 2 * rotations) / total * (direction ? 1f : -1f);
        float[] p = new float[(total + 1) * 3];
        for (int i = 0; i <= total; i++) {
            float theta = i * deltaTheta;
            float r = startRadius + i * deltaRadius;
            p[3 * i] = r * (float) Math.cos(theta);
            p[3 * i + 1] = r * (float) Math.sin(theta);
            p[3 * i + 2] = deltaHeight * i;
        }
        call.out("Curve", curveOut(p, false, CurveGeometry.POLY));
    }

    private static void star(NodeCall call) {
        int points = Math.max(3, call.i("Points"));
        float inner = call.f("Inner Radius"), outer = call.f("Outer Radius"), twist = call.f("Twist");
        float step = (float) (2.0 * Math.PI / points);
        float[] p = new float[points * 6];
        int[] outerFlags = new int[points * 2];
        for (int i = 0; i < points; i++) {
            p[6 * i] = outer * (float) Math.cos(i * step);
            p[6 * i + 1] = outer * (float) Math.sin(i * step);
            float innerTheta = i * step + step * 0.5f + twist;
            p[6 * i + 3] = inner * (float) Math.cos(innerTheta);
            p[6 * i + 4] = inner * (float) Math.sin(innerTheta);
            outerFlags[2 * i] = 1;
        }
        CurveGeometry curve = CurveGeometry.build(p, new int[]{points * 2}, new boolean[]{true}, CurveGeometry.POLY);
        String name = MeshPrimitives.anonymous("outer");
        curve = (CurveGeometry) GeometryOps.withAttribute(curve, name, Domain.POINT, Column.ofInts(DataType.BOOL, outerFlags));
        call.out("Curve", GeometrySet.of(curve));
        call.out("Outer Points", Inputs.named(name, DataType.BOOL));
    }

    private static void quadrilateral(NodeCall call) {
        float[] p = new float[12];
        switch (call.prop("mode", "RECTANGLE")) {
            case "PARALLELOGRAM" -> {
                float w = call.f("Width"), h = call.f("Height"), o = call.f("Offset");
                set(p, 0, w / 2 + o / 2, h / 2);
                set(p, 1, -w / 2 + o / 2, h / 2);
                set(p, 2, -w / 2 - o / 2, -h / 2);
                set(p, 3, w / 2 - o / 2, -h / 2);
            }
            case "TRAPEZOID" -> {
                float b = call.f("Bottom Width"), t = call.f("Top Width"), o = call.f("Offset"), h = call.f("Height");
                set(p, 0, t / 2 + o, h / 2);
                set(p, 1, -t / 2 + o, h / 2);
                set(p, 2, -b / 2, -h / 2);
                set(p, 3, b / 2, -h / 2);
            }
            case "KITE" -> {
                float w = call.f("Width"), bh = call.f("Bottom Height"), th = call.f("Top Height");
                set(p, 0, 0, -bh);
                set(p, 1, w / 2, 0);
                set(p, 2, 0, th);
                set(p, 3, -w / 2, 0);
            }
            case "POINTS" -> {
                for (int k = 0; k < 4; k++) {
                    float[] v = call.v("Point " + (k + 1));
                    System.arraycopy(v, 0, p, 3 * k, 3);
                }
            }
            default -> {
                float w = call.f("Width"), h = call.f("Height");
                set(p, 0, w / 2, h / 2);
                set(p, 1, -w / 2, h / 2);
                set(p, 2, -w / 2, -h / 2);
                set(p, 3, w / 2, -h / 2);
            }
        }
        call.out("Curve", curveOut(p, true, CurveGeometry.POLY));
    }

    private static void set(float[] p, int i, float x, float y) {
        p[3 * i] = x;
        p[3 * i + 1] = y;
        p[3 * i + 2] = 0f;
    }

    private static void bezierSegment(NodeCall call) {
        float[] start = call.v("Start"), end = call.v("End");
        float[] sh = call.v("Start Handle"), eh = call.v("End Handle");
        if ("OFFSET".equals(call.prop("mode", "POSITION"))) {
            sh = new float[]{start[0] + sh[0], start[1] + sh[1], start[2] + sh[2]};
            eh = new float[]{end[0] + eh[0], end[1] + eh[1], end[2] + eh[2]};
        }
        CurveGeometry curve = CurveGeometry.build(new float[]{start[0], start[1], start[2], end[0], end[1], end[2]},
                new int[]{2}, new boolean[]{false}, CurveGeometry.BEZIER);
        Attributes attributes = curve.attributes().copy();
        attributes.put("handle_right", Domain.POINT, Column.ofFloats(DataType.VECTOR, new float[]{sh[0], sh[1], sh[2],
                2 * end[0] - eh[0], 2 * end[1] - eh[1], 2 * end[2] - eh[2]}));
        attributes.put("handle_left", Domain.POINT, Column.ofFloats(DataType.VECTOR, new float[]{2 * start[0] - sh[0],
                2 * start[1] - sh[1], 2 * start[2] - sh[2], eh[0], eh[1], eh[2]}));
        attributes.put("resolution", Domain.CURVE, Column.ofInts(DataType.INT, new int[]{Math.max(1, call.i("Resolution"))}));
        call.out("Curve", GeometrySet.of(curve.withAttributes(attributes)));
    }

    private static void quadraticBezier(NodeCall call) {
        int resolution = Math.max(1, call.i("Resolution"));
        float[] a = call.v("Start"), b = call.v("Middle"), c = call.v("End");
        float[] p = new float[(resolution + 1) * 3];
        for (int i = 0; i <= resolution; i++) {
            float t = i / (float) resolution, u = 1f - t;
            for (int k = 0; k < 3; k++) {
                p[3 * i + k] = u * u * a[k] + 2f * u * t * b[k] + t * t * c[k];
            }
        }
        call.out("Curve", curveOut(p, false, CurveGeometry.POLY));
    }

    // --- Curve to mesh -----------------------------------------------------------------------------------------

    private static void curveToMesh(NodeCall call) {
        GeometrySet input = call.geometry("Curve");
        GeometrySet profileSet = call.geometry("Profile Curve");
        boolean fillCaps = call.b("Fill Caps");
        Field scaleField = call.has("Scale") ? call.field("Scale", DataType.FLOAT) : Field.constant(DataType.FLOAT, 1f);
        GeometrySet out = input.withCurves(null);
        if (input.curves != null && input.curves.curveCount() > 0) {
            Column scale = call.context(input.curves, Domain.POINT).evalAs(scaleField, DataType.FLOAT);
            MeshGeometry mesh = sweep(input.curves, profileSet.curves, scale, fillCaps);
            out = out.withMesh(out.mesh == null ? mesh : GeometryOps.joinMeshes(List.of(out.mesh, mesh)));
        }
        call.out("Mesh", out);
    }

    /** A control-point column interpolated onto a curve's evaluated points. */
    static float[] toEvaluated(CurveGeometry curves, int curve, Column column) {
        CurveGeometry.Evaluated e = curves.evaluated(curve);
        int n = curves.curveSize(curve);
        int start = curves.curveOffsets[curve];
        int count = e.count();
        float[] out = new float[count];
        for (int k = 0; k < n; k++) {
            int from = e.controlIndex[k];
            int to = k + 1 < n ? e.controlIndex[k + 1] : (e.cyclic ? count : from + 1);
            float a = column.getFloat(start + k);
            float b = column.getFloat(start + (k + 1) % n);
            for (int i = from; i < Math.min(to, count); i++) {
                float t = to - from > 1 ? (i - from) / (float) (to - from) : 0f;
                out[i] = a + (b - a) * t;
            }
        }
        return out;
    }

    static MeshGeometry sweep(CurveGeometry main, CurveGeometry profile, Column scale, boolean fillCaps) {
        List<float[]> positions = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        List<Integer> capFaces = new ArrayList<>();
        List<int[]> looseEdges = new ArrayList<>();
        int vertBase = 0;
        List<Attributes.Attribute> mainAttrs = new ArrayList<>();
        for (Attributes.Attribute a : main.attributes().all()) {
            if (a.domain() == Domain.POINT && !isBuiltInCurveAttribute(a.name())) {
                mainAttrs.add(a);
            }
        }
        List<List<Float>> attrValues = new ArrayList<>();
        for (Attributes.Attribute a : mainAttrs) {
            attrValues.add(new ArrayList<>());
        }
        int profileCount = profile == null || profile.curveCount() == 0 ? 1 : profile.curveCount();
        for (int c = 0; c < main.curveCount(); c++) {
            CurveGeometry.Evaluated e = main.evaluated(c);
            // Blender 5 scales the profile by the Scale input alone; the curve's radius attribute is not applied
            // (files from older versions are updated to wire Radius into Scale when Blender opens them).
            float[] radius = toEvaluated(main, c, scale);
            for (int pc = 0; pc < profileCount; pc++) {
                float[] prof;
                boolean profCyclic;
                if (profile == null || profile.curveCount() == 0) {
                    prof = new float[3];
                    profCyclic = false;
                } else {
                    CurveGeometry.Evaluated pe = profile.evaluated(pc);
                    prof = pe.positions;
                    profCyclic = pe.cyclic;
                }
                int mainN = e.count();
                int profN = prof.length / 3;
                for (int i = 0; i < mainN; i++) {
                    float[] nrm = {e.normals[3 * i], e.normals[3 * i + 1], e.normals[3 * i + 2]};
                    float[] tan = {e.tangents[3 * i], e.tangents[3 * i + 1], e.tangents[3 * i + 2]};
                    float[] bin = cross(tan, nrm);
                    float r = radius[i];
                    for (int j = 0; j < profN; j++) {
                        float px = prof[3 * j], py = prof[3 * j + 1], pz = prof[3 * j + 2];
                        float[] v = new float[3];
                        for (int k = 0; k < 3; k++) {
                            v[k] = e.positions[3 * i + k] + (nrm[k] * px + bin[k] * py + tan[k] * pz) * r;
                        }
                        positions.add(v);
                    }
                    for (int a = 0; a < mainAttrs.size(); a++) {
                        float value = 0f;
                        Column col = mainAttrs.get(a).data();
                        if (col.type == DataType.FLOAT || col.type == DataType.INT || col.type == DataType.BOOL) {
                            value = toEvaluatedCached(main, c, col)[i];
                        }
                        for (int j = 0; j < profN; j++) {
                            attrValues.get(a).add(value);
                        }
                    }
                }
                if (profN == 1) {
                    for (int i = 0; i + 1 < mainN; i++) {
                        looseEdges.add(new int[]{vertBase + i, vertBase + i + 1});
                    }
                    if (e.cyclic && mainN > 2) {
                        looseEdges.add(new int[]{vertBase + mainN - 1, vertBase});
                    }
                } else {
                    int mainSegments = e.cyclic ? mainN : mainN - 1;
                    int profSegments = profCyclic ? profN : profN - 1;
                    for (int i = 0; i < mainSegments; i++) {
                        int nextRing = (i == mainN - 1) ? 0 : i + 1;
                        int ring = vertBase + profN * i;
                        int next = vertBase + profN * nextRing;
                        for (int j = 0; j < profSegments; j++) {
                            int nj = j == profN - 1 ? 0 : j + 1;
                            faces.add(new int[]{ring + j, ring + nj, next + nj, next + j});
                        }
                    }
                    if (fillCaps && profCyclic && !e.cyclic && profN > 2) {
                        int[] startCap = new int[profN];
                        int[] endCap = new int[profN];
                        int last = vertBase + profN * (mainN - 1);
                        for (int j = 0; j < profN; j++) {
                            startCap[j] = vertBase + profN - 1 - j;
                            endCap[j] = last + j;
                        }
                        capFaces.add(faces.size());
                        faces.add(startCap);
                        capFaces.add(faces.size());
                        faces.add(endCap);
                    }
                }
                vertBase += mainN * profN;
            }
        }
        float[] p = new float[positions.size() * 3];
        for (int k = 0; k < positions.size(); k++) {
            System.arraycopy(positions.get(k), 0, p, 3 * k, 3);
        }
        int[] edges = new int[looseEdges.size() * 2];
        for (int k = 0; k < looseEdges.size(); k++) {
            edges[2 * k] = looseEdges.get(k)[0];
            edges[2 * k + 1] = looseEdges.get(k)[1];
        }
        int corners = 0;
        for (int[] f : faces) {
            corners += f.length;
        }
        int[] offsets = new int[faces.size() + 1];
        int[] cv = new int[corners];
        int at = 0;
        for (int k = 0; k < faces.size(); k++) {
            offsets[k] = at;
            for (int v : faces.get(k)) {
                cv[at++] = v;
            }
        }
        offsets[faces.size()] = corners;
        MeshGeometry mesh = MeshGeometry.build(p, offsets, cv, edges);
        Attributes attributes = mesh.attributes().copy();
        if (!capFaces.isEmpty()) {
            // Caps are flat, and the rings they close are hard edges, as Blender marks them.
            int[] sharpFace = new int[mesh.faceCount()];
            int[] sharpEdge = new int[mesh.edgeCount()];
            for (int f : capFaces) {
                sharpFace[f] = 1;
                for (int c = mesh.topology.faceOffsets[f]; c < mesh.topology.faceOffsets[f + 1]; c++) {
                    sharpEdge[mesh.topology.cornerEdges[c]] = 1;
                }
            }
            attributes.put("sharp_face", Domain.FACE, Column.ofInts(DataType.BOOL, sharpFace));
            attributes.put("sharp_edge", Domain.EDGE, Column.ofInts(DataType.BOOL, sharpEdge));
        }
        for (int a = 0; a < mainAttrs.size(); a++) {
            List<Float> values = attrValues.get(a);
            float[] data = new float[values.size()];
            for (int k = 0; k < data.length; k++) {
                data[k] = values.get(k);
            }
            attributes.put(mainAttrs.get(a).name(), Domain.POINT,
                    Column.ofFloats(DataType.FLOAT, data).convert(mainAttrs.get(a).data().type));
        }
        if (!main.materials.isEmpty()) {
            return new MeshGeometry(mesh.topology, attributes, main.materials);
        }
        return mesh.withAttributes(attributes);
    }

    private static final Map<Column, Map<Integer, float[]>> EVAL_CACHE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static float[] toEvaluatedCached(CurveGeometry curves, int curve, Column column) {
        Map<Integer, float[]> perCurve = EVAL_CACHE.computeIfAbsent(column, c -> new java.util.concurrent.ConcurrentHashMap<>());
        return perCurve.computeIfAbsent(curve, k -> toEvaluated(curves, curve, column));
    }

    static boolean isBuiltInCurveAttribute(String name) {
        return switch (name) {
            case "position", "radius", "tilt", "handle_left", "handle_right", "handle_type_left", "handle_type_right",
                 "nurbs_weight", "custom_normal" -> true;
            default -> false;
        };
    }

    // --- Resample and sampling -------------------------------------------------------------------------------

    /** Where along a curve's evaluated polyline a length falls: segment index and fraction. */
    static float[] locate(CurveGeometry.Evaluated e, float length) {
        int n = e.count();
        if (n == 1) {
            return new float[]{0, 0};
        }
        float total = e.totalLength();
        length = Math.clamp(length, 0f, total);
        int segments = e.cyclic ? n : n - 1;
        for (int s = 0; s < segments; s++) {
            float l0 = e.lengths[s];
            float l1 = s + 1 < n ? e.lengths[s + 1] : total;
            if (length <= l1 || s == segments - 1) {
                float t = l1 > l0 ? (length - l0) / (l1 - l0) : 0f;
                return new float[]{s, Math.clamp(t, 0f, 1f)};
            }
        }
        return new float[]{segments - 1, 1f};
    }

    static void interpolate(float[] values, int width, CurveGeometry.Evaluated e, float[] at, float[] out, int oo) {
        int n = e.count();
        int a = (int) at[0];
        int b = (a + 1) % n;
        float t = at[1];
        for (int k = 0; k < width; k++) {
            out[oo + k] = values[width * a + k] + (values[width * b + k] - values[width * a + k]) * t;
        }
    }

    private static void resample(NodeCall call) {
        GeometrySet input = call.geometry("Curve");
        if (input.curves == null) {
            call.out("Curve", input);
            return;
        }
        CurveGeometry curves = input.curves;
        String mode = call.has("Mode") ? call.string("Mode") : call.prop("mode", "COUNT");
        FieldContext curveContext = call.context(curves, Domain.CURVE);
        Column count = curveContext.evalAs(call.field("Count"), DataType.INT);
        Column length = curveContext.evalAs(call.field("Length"), DataType.FLOAT);
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), curves, Domain.CURVE);
        List<float[]> newPositions = new ArrayList<>();
        int[] sizes = new int[curves.curveCount()];
        List<Integer> sourceCurves = new ArrayList<>();
        List<float[]> radii = new ArrayList<>();
        for (int c = 0; c < curves.curveCount(); c++) {
            CurveGeometry.Evaluated e = curves.evaluated(c);
            float total = e.totalLength();
            int samples;
            if (!selection[c]) {
                samples = -1;
            } else if (mode.equalsIgnoreCase("Evaluated") || mode.equals("EVALUATED")) {
                samples = 0;
            } else if (mode.equalsIgnoreCase("Length")) {
                float sl = Math.max(length.f[c], 0.0001f);
                samples = e.cyclic ? Math.max((int) (total / sl), 1) : Math.max((int) (total / sl) + 1, 2);
            } else {
                samples = Math.max(count.i[c], 1);
            }
            float[] p;
            float[] r;
            if (samples == 0) {
                p = e.positions.clone();
                r = e.radii.clone();
            } else if (samples < 0) {
                int start = curves.curveOffsets[c];
                p = java.util.Arrays.copyOfRange(curves.positions(), 3 * start, 3 * (start + curves.curveSize(c)));
                r = new float[curves.curveSize(c)];
                for (int k = 0; k < r.length; k++) {
                    r[k] = curves.radius(start + k);
                }
            } else {
                p = new float[samples * 3];
                r = new float[samples];
                int divisions = e.cyclic ? samples : Math.max(samples - 1, 1);
                for (int i = 0; i < samples; i++) {
                    float l = samples == 1 ? 0f : total * i / divisions;
                    float[] at = locate(e, l);
                    interpolate(e.positions, 3, e, at, p, 3 * i);
                    interpolate(e.radii, 1, e, at, r, i);
                }
            }
            newPositions.add(p);
            radii.add(r);
            sizes[c] = p.length / 3;
            sourceCurves.add(c);
        }
        int total = 0;
        for (int s : sizes) {
            total += s;
        }
        float[] p = new float[total * 3];
        float[] r = new float[total];
        int at = 0;
        for (int c = 0; c < newPositions.size(); c++) {
            System.arraycopy(newPositions.get(c), 0, p, 3 * at, newPositions.get(c).length);
            System.arraycopy(radii.get(c), 0, r, at, radii.get(c).length);
            at += sizes[c];
        }
        CurveGeometry out = CurveGeometry.build(p, sizes, null, CurveGeometry.POLY);
        Attributes attributes = out.attributes().copy();
        attributes.put("radius", Domain.POINT, Column.ofFloats(DataType.FLOAT, r));
        for (Attributes.Attribute a : curves.attributes().all()) {
            if (a.domain() == Domain.CURVE && !a.name().equals("curve_type")) {
                attributes.put(a.name(), Domain.CURVE, a.data());
            }
        }
        call.out("Curve", input.withCurves(new CurveGeometry(out.curveOffsets, attributes, curves.materials)));
    }

    private static void curveToPoints(NodeCall call) {
        GeometrySet input = call.geometry("Curve");
        CurveGeometry curves = input.curves;
        if (curves == null) {
            call.out("Points", GeometrySet.EMPTY);
            return;
        }
        String mode = call.prop("mode", "COUNT");
        FieldContext curveContext = call.context(curves, Domain.CURVE);
        Column count = curveContext.evalAs(call.field("Count"), DataType.INT);
        Column length = curveContext.evalAs(call.field("Length"), DataType.FLOAT);
        List<Float> pos = new ArrayList<>(), tan = new ArrayList<>(), nrm = new ArrayList<>(), rad = new ArrayList<>();
        for (int c = 0; c < curves.curveCount(); c++) {
            CurveGeometry.Evaluated e = curves.evaluated(c);
            float total = e.totalLength();
            int samples;
            if ("EVALUATED".equals(mode)) {
                samples = e.count();
            } else if ("LENGTH".equals(mode)) {
                float sl = Math.max(length.f[c], 0.0001f);
                samples = e.cyclic ? Math.max((int) (total / sl), 1) : Math.max((int) (total / sl) + 1, 1);
            } else {
                samples = Math.max(count.i[c], 1);
            }
            float[] v = new float[3], t = new float[3], n = new float[3], r = new float[1];
            int divisions = e.cyclic ? samples : Math.max(samples - 1, 1);
            for (int i = 0; i < samples; i++) {
                if ("EVALUATED".equals(mode)) {
                    System.arraycopy(e.positions, 3 * i, v, 0, 3);
                    System.arraycopy(e.tangents, 3 * i, t, 0, 3);
                    System.arraycopy(e.normals, 3 * i, n, 0, 3);
                    r[0] = e.radii[i];
                } else {
                    float[] at = locate(e, samples == 1 ? 0f : total * i / divisions);
                    interpolate(e.positions, 3, e, at, v, 0);
                    interpolate(e.tangents, 3, e, at, t, 0);
                    interpolate(e.normals, 3, e, at, n, 0);
                    interpolate(e.radii, 1, e, at, r, 0);
                    t = normalized(t);
                    n = normalized(n);
                }
                for (int k = 0; k < 3; k++) {
                    pos.add(v[k]);
                    tan.add(t[k]);
                    nrm.add(n[k]);
                }
                rad.add(r[0]);
            }
        }
        int n = rad.size();
        float[] p = toArray(pos), t = toArray(tan), nr = toArray(nrm), r = toArray(rad);
        float[] rotation = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float[] tz = {t[3 * i], t[3 * i + 1], t[3 * i + 2]};
            float[] nx = {nr[3 * i], nr[3 * i + 1], nr[3 * i + 2]};
            float[] y = cross(tz, nx);
            double[] m = {nx[0], nx[1], nx[2], y[0], y[1], y[2], tz[0], tz[1], tz[2]};
            Transforms.mat3ToQuat(m, rotation, 4 * i);
        }
        PointGeometry points = PointGeometry.of(p);
        Attributes attributes = points.attributes().copy();
        attributes.put("radius", Domain.POINT, Column.ofFloats(DataType.FLOAT, r));
        String tangentName = MeshPrimitives.anonymous("tangent");
        String normalName = MeshPrimitives.anonymous("normal");
        String rotationName = MeshPrimitives.anonymous("rotation");
        attributes.put(tangentName, Domain.POINT, Column.ofFloats(DataType.VECTOR, t));
        attributes.put(normalName, Domain.POINT, Column.ofFloats(DataType.VECTOR, nr));
        attributes.put(rotationName, Domain.POINT, Column.ofFloats(DataType.ROTATION, rotation));
        call.out("Points", GeometrySet.of(new PointGeometry(n, attributes)));
        call.out("Tangent", Inputs.named(tangentName, DataType.VECTOR));
        call.out("Normal", Inputs.named(normalName, DataType.VECTOR));
        call.out("Rotation", Inputs.named(rotationName, DataType.ROTATION));
    }

    private static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = list.get(k);
        }
        return out;
    }

    private static void sampleCurve(NodeCall call) {
        GeometrySet source = call.geometry("Curves");
        CurveGeometry curves = source.curves;
        boolean byLength = "LENGTH".equals(call.prop("mode", "FACTOR"));
        boolean allCurves = call.propBool("use_all_curves", false);
        Field where = call.field(byLength ? "Length" : "Factor", DataType.FLOAT);
        Field curveIndex = call.field("Curve Index", DataType.INT);
        Field valueField = call.field("Value");
        DataType valueType = valueField.type;
        DataType[] types = {valueType, DataType.VECTOR, DataType.VECTOR, DataType.VECTOR};
        Field.Multi shared = new Field.Multi(types, null, new Field[0]) {
            @Override
            Column[] compute(FieldContext context) {
                Column[] out = new Column[4];
                for (int k = 0; k < 4; k++) {
                    out[k] = Column.create(types[k], context.size);
                }
                if (curves == null || curves.curveCount() == 0) {
                    return out;
                }
                Column w = context.evalAs(where, DataType.FLOAT);
                Column ci = context.evalAs(curveIndex, DataType.INT);
                Column values = new FieldContext(curves, Domain.POINT, context.env).eval(valueField);
                float total = 0f;
                float[] starts = new float[curves.curveCount()];
                for (int c = 0; c < curves.curveCount(); c++) {
                    starts[c] = total;
                    total += curves.evaluated(c).totalLength();
                }
                float[] tmp = new float[3];
                for (int k = 0; k < context.size; k++) {
                    int c;
                    float l;
                    if (allCurves) {
                        float g = byLength ? w.f[k] : w.f[k] * total;
                        c = 0;
                        while (c + 1 < curves.curveCount() && g > starts[c + 1]) {
                            c++;
                        }
                        l = g - starts[c];
                    } else {
                        c = Math.clamp(ci.i[k], 0, curves.curveCount() - 1);
                        l = byLength ? w.f[k] : w.f[k] * curves.evaluated(c).totalLength();
                    }
                    CurveGeometry.Evaluated e = curves.evaluated(c);
                    if (e.count() == 0) {
                        continue;
                    }
                    float[] at = locate(e, l);
                    interpolate(e.positions, 3, e, at, out[1].f, 3 * k);
                    interpolate(e.tangents, 3, e, at, tmp, 0);
                    float[] tn = normalized(tmp);
                    out[2].setVector(k, tn[0], tn[1], tn[2]);
                    interpolate(e.normals, 3, e, at, tmp, 0);
                    float[] nn = normalized(tmp);
                    out[3].setVector(k, nn[0], nn[1], nn[2]);
                    float param = controlParameter(curves, c, e, at);
                    int start = curves.curveOffsets[c];
                    int size = curves.curveSize(c);
                    int a = (int) Math.floor(param);
                    float t = param - a;
                    int ia = start + Math.min(a, size - 1);
                    int ib = start + (e.cyclic ? (a + 1) % size : Math.min(a + 1, size - 1));
                    mixInto(values, ia, ib, t, out[0], k);
                }
                return out;
            }
        };
        call.out("Value", new Field.MultiOutput(shared, 0));
        call.out("Position", new Field.MultiOutput(shared, 1));
        call.out("Tangent", new Field.MultiOutput(shared, 2));
        call.out("Normal", new Field.MultiOutput(shared, 3));
    }

    /** A location on the evaluated polyline as a parameter along the control points (index plus fraction). */
    static float controlParameter(CurveGeometry curves, int curve, CurveGeometry.Evaluated e, float[] at) {
        float evaluated = at[0] + at[1];
        int n = curves.curveSize(curve);
        for (int k = 0; k < n; k++) {
            int from = e.controlIndex[k];
            int to = k + 1 < n ? e.controlIndex[k + 1] : (e.cyclic ? e.count() : from);
            if (evaluated >= from && (evaluated < to || k == n - 1)) {
                return to > from ? k + (evaluated - from) / (to - from) : k;
            }
        }
        return n - 1;
    }

    static void mixInto(Column values, int a, int b, float t, Column out, int k) {
        if (values.type.integer) {
            out.i[k] = t < 0.5f ? values.i[a] : values.i[b];
            return;
        }
        int w = values.type.width;
        for (int c = 0; c < w; c++) {
            out.f[w * k + c] = values.f[w * a + c] + (values.f[w * b + c] - values.f[w * a + c]) * t;
        }
    }

    // --- Setters -----------------------------------------------------------------------------------------------

    private static void setPointAttribute(NodeCall call, String input, String name, String valueName, DataType type) {
        GeometrySet geometry = call.geometry(input);
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out(input, geometry);
            return;
        }
        FieldContext context = call.context(curves, Domain.POINT);
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), curves, Domain.POINT);
        Column values = context.evalAs(call.field(valueName), type);
        Column existing = curves.read(name, Domain.POINT, type);
        if (existing == null) {
            existing = Column.constant(type, "radius".equals(name) ? 1f : 0f, curves.pointCount());
        }
        Column out = existing.copy();
        for (int k = 0; k < curves.pointCount(); k++) {
            if (selection[k]) {
                out.copyElement(values, k, k);
            }
        }
        call.out(input, geometry.withCurves((CurveGeometry) GeometryOps.withAttribute(curves, name, Domain.POINT, out)));
    }

    private static void setPointsRadius(NodeCall call) {
        GeometrySet geometry = call.geometry("Points");
        PointGeometry points = geometry.points;
        if (points == null) {
            call.out("Points", geometry);
            return;
        }
        FieldContext context = call.context(points, Domain.POINT);
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), points, Domain.POINT);
        Column values = context.evalAs(call.field("Radius"), DataType.FLOAT);
        Column existing = points.read("radius", Domain.POINT, DataType.FLOAT);
        Column out = existing == null ? Column.constant(DataType.FLOAT, 0.1f, points.count()) : existing.copy();
        for (int k = 0; k < points.count(); k++) {
            if (selection[k]) {
                out.f[k] = values.f[k];
            }
        }
        call.out("Points", geometry.withPoints((PointGeometry) GeometryOps.withAttribute(points, "radius", Domain.POINT, out)));
    }

    private static void setCurveAttribute(NodeCall call, String name, String valueName, DataType type) {
        GeometrySet geometry = call.geometry("Geometry");
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out("Geometry", geometry);
            return;
        }
        FieldContext context = call.context(curves, Domain.CURVE);
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), curves, Domain.CURVE);
        Column values = context.evalAs(call.field(valueName), type);
        Column existing = curves.read(name, Domain.CURVE, type);
        Column out = existing == null ? Column.constant(type, "resolution".equals(name) ? 12 : 0, curves.curveCount())
                : existing.copy();
        for (int k = 0; k < curves.curveCount(); k++) {
            if (selection[k]) {
                out.copyElement(values, k, k);
            }
        }
        call.out("Geometry", geometry.withCurves((CurveGeometry) GeometryOps.withAttribute(curves, name, Domain.CURVE, out)));
    }

    private static void setNormal(NodeCall call) {
        GeometrySet geometry = call.geometry("Curve");
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out("Curve", geometry);
            return;
        }
        String mode = call.has("Mode") ? call.string("Mode") : call.prop("mode", "MINIMUM_TWIST");
        int value = switch (mode) {
            case "Z Up", "Z_UP" -> CurveGeometry.NORMAL_Z_UP;
            case "Free", "FREE" -> CurveGeometry.NORMAL_FREE;
            default -> CurveGeometry.NORMAL_MINIMUM_TWIST;
        };
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), curves, Domain.CURVE);
        Column existing = curves.read("normal_mode", Domain.CURVE, DataType.INT);
        Column out = existing == null ? Column.create(DataType.INT, curves.curveCount()) : existing.copy();
        for (int c = 0; c < curves.curveCount(); c++) {
            if (selection[c]) {
                out.i[c] = value == CurveGeometry.NORMAL_FREE ? CurveGeometry.NORMAL_MINIMUM_TWIST : value;
            }
        }
        call.out("Curve", geometry.withCurves((CurveGeometry) GeometryOps.withAttribute(curves, "normal_mode", Domain.CURVE, out)));
    }

    private static void splineType(NodeCall call) {
        GeometrySet geometry = call.geometry("Curve");
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out("Curve", geometry);
            return;
        }
        int type = switch (call.prop("spline_type", "POLY")) {
            case "CATMULL_ROM" -> CurveGeometry.CATMULL_ROM;
            case "BEZIER" -> CurveGeometry.BEZIER;
            case "NURBS" -> CurveGeometry.NURBS;
            default -> CurveGeometry.POLY;
        };
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), curves, Domain.CURVE);
        Column types = Column.create(DataType.INT, curves.curveCount());
        for (int c = 0; c < curves.curveCount(); c++) {
            types.i[c] = selection[c] ? type : curves.curveType(c);
        }
        Attributes attributes = curves.attributes().copy();
        attributes.put("curve_type", Domain.CURVE, types);
        if (type == CurveGeometry.BEZIER) {
            float[] p = curves.positions();
            float[] left = new float[p.length], right = new float[p.length];
            for (int c = 0; c < curves.curveCount(); c++) {
                int start = curves.curveOffsets[c], n = curves.curveSize(c);
                boolean cyclic = curves.cyclic(c);
                for (int k = 0; k < n; k++) {
                    int prev = k > 0 ? k - 1 : (cyclic ? n - 1 : k);
                    int next = k < n - 1 ? k + 1 : (cyclic ? 0 : k);
                    for (int a = 0; a < 3; a++) {
                        float d = (p[3 * (start + next) + a] - p[3 * (start + prev) + a]) / 6f;
                        left[3 * (start + k) + a] = p[3 * (start + k) + a] - d;
                        right[3 * (start + k) + a] = p[3 * (start + k) + a] + d;
                    }
                }
            }
            attributes.put("handle_left", Domain.POINT, Column.ofFloats(DataType.VECTOR, left));
            attributes.put("handle_right", Domain.POINT, Column.ofFloats(DataType.VECTOR, right));
        }
        call.out("Curve", geometry.withCurves(curves.withAttributes(attributes)));
    }

    private static void reverse(NodeCall call) {
        GeometrySet geometry = call.geometry("Curve");
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out("Curve", geometry);
            return;
        }
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), curves, Domain.CURVE);
        int[] map = new int[curves.pointCount()];
        for (int c = 0; c < curves.curveCount(); c++) {
            int start = curves.curveOffsets[c], n = curves.curveSize(c);
            for (int k = 0; k < n; k++) {
                map[start + k] = selection[c] ? start + n - 1 - k : start + k;
            }
        }
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : curves.attributes().all()) {
            String name = a.name();
            if (a.domain() == Domain.POINT) {
                if (name.equals("handle_left")) {
                    name = "handle_right";
                } else if (name.equals("handle_right")) {
                    name = "handle_left";
                }
                attributes.put(name, Domain.POINT, a.data().gather(map));
            } else {
                attributes.put(name, a.domain(), a.data());
            }
        }
        call.out("Curve", geometry.withCurves(curves.withAttributes(attributes)));
    }

    private static void subdivide(NodeCall call) {
        GeometrySet geometry = call.geometry("Curve");
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out("Curve", geometry);
            return;
        }
        Column cuts = call.context(curves, Domain.POINT).evalAs(call.field("Cuts"), DataType.INT);
        List<Integer> sources = new ArrayList<>();
        List<Float> params = new ArrayList<>();
        int[] sizes = new int[curves.curveCount()];
        for (int c = 0; c < curves.curveCount(); c++) {
            int start = curves.curveOffsets[c], n = curves.curveSize(c);
            boolean cyclic = curves.cyclic(c);
            int size = 0;
            for (int k = 0; k < n; k++) {
                int segmentCuts = (k < n - 1 || cyclic) ? Math.max(0, cuts.i[start + k]) : 0;
                for (int s = 0; s <= segmentCuts; s++) {
                    sources.add(start + k);
                    params.add(s / (float) (segmentCuts + 1));
                    size++;
                }
            }
            sizes[c] = size;
        }
        int total = sources.size();
        int[] curveOfPoint = curves.pointCurves();
        Attributes attributes = new Attributes();
        for (Attributes.Attribute a : curves.attributes().all()) {
            if (a.domain() != Domain.POINT) {
                attributes.put(a.name(), a.domain(), a.data());
                continue;
            }
            Column out = Column.create(a.data().type, total);
            for (int i = 0; i < total; i++) {
                int src = sources.get(i);
                int c = curveOfPoint[src];
                int start = curves.curveOffsets[c], n = curves.curveSize(c);
                int next = start + (src - start + 1) % n;
                mixInto(a.data(), src, next, params.get(i), out, i);
            }
            attributes.put(a.name(), Domain.POINT, out);
        }
        int[] offsets = new int[sizes.length + 1];
        for (int c = 0; c < sizes.length; c++) {
            offsets[c + 1] = offsets[c] + sizes[c];
        }
        call.out("Curve", geometry.withCurves(new CurveGeometry(offsets, attributes, curves.materials)));
    }

    private static void trim(NodeCall call) {
        GeometrySet geometry = call.geometry("Curve");
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out("Curve", geometry);
            return;
        }
        boolean byLength = "LENGTH".equals(call.prop("mode", "FACTOR"));
        FieldContext context = call.context(curves, Domain.CURVE);
        Column start = context.evalAs(call.field(byLength ? "Start_001" : "Start"), DataType.FLOAT);
        Column end = context.evalAs(call.field(byLength ? "End_001" : "End"), DataType.FLOAT);
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), curves, Domain.CURVE);
        List<float[]> parts = new ArrayList<>();
        int[] sizes = new int[curves.curveCount()];
        for (int c = 0; c < curves.curveCount(); c++) {
            CurveGeometry.Evaluated e = curves.evaluated(c);
            float total = e.totalLength();
            float l0 = byLength ? start.f[c] : start.f[c] * total;
            float l1 = byLength ? end.f[c] : end.f[c] * total;
            if (!selection[c]) {
                l0 = 0f;
                l1 = total;
            }
            l0 = Math.clamp(l0, 0f, total);
            l1 = Math.clamp(l1, l0, total);
            List<Float> pts = new ArrayList<>();
            float[] tmp = new float[3];
            interpolate(e.positions, 3, e, locate(e, l0), tmp, 0);
            for (float v : tmp) {
                pts.add(v);
            }
            for (int i = 0; i < e.count(); i++) {
                if (e.lengths[i] > l0 && e.lengths[i] < l1) {
                    for (int k = 0; k < 3; k++) {
                        pts.add(e.positions[3 * i + k]);
                    }
                }
            }
            interpolate(e.positions, 3, e, locate(e, l1), tmp, 0);
            for (float v : tmp) {
                pts.add(v);
            }
            float[] p = toArray(pts);
            parts.add(p);
            sizes[c] = p.length / 3;
        }
        float[] all = new float[java.util.Arrays.stream(sizes).sum() * 3];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        CurveGeometry out = CurveGeometry.build(all, sizes, null, CurveGeometry.POLY);
        call.out("Curve", geometry.withCurves(out.withMaterials(curves.materials)));
    }

    private static void fill(NodeCall call) {
        GeometrySet geometry = call.geometry("Curve");
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out("Mesh", GeometrySet.EMPTY);
            return;
        }
        String mode = call.has("Mode") ? call.string("Mode") : call.prop("mode", "TRIANGLES");
        boolean ngons = mode.toUpperCase().startsWith("N");
        List<float[]> points = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        for (int c = 0; c < curves.curveCount(); c++) {
            CurveGeometry.Evaluated e = curves.evaluated(c);
            int base = points.size();
            int n = e.count();
            if (n < 3) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                points.add(new float[]{e.positions[3 * i], e.positions[3 * i + 1], 0f});
            }
            int[] polygon = new int[n];
            for (int i = 0; i < n; i++) {
                polygon[i] = base + i;
            }
            if (ngons) {
                faces.add(orientedUp(polygon, points));
            } else {
                faces.addAll(Triangulation.earClip(orientedUp(polygon, points), points));
            }
        }
        float[] p = new float[points.size() * 3];
        for (int k = 0; k < points.size(); k++) {
            System.arraycopy(points.get(k), 0, p, 3 * k, 3);
        }
        call.out("Mesh", GeometrySet.of(MeshPrimitives.fromFaces(p, faces).withMaterials(curves.materials)));
    }

    private static int[] orientedUp(int[] polygon, List<float[]> points) {
        double area = 0;
        for (int i = 0; i < polygon.length; i++) {
            float[] a = points.get(polygon[i]), b = points.get(polygon[(i + 1) % polygon.length]);
            area += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        if (area >= 0) {
            return polygon;
        }
        int[] reversed = new int[polygon.length];
        for (int i = 0; i < polygon.length; i++) {
            reversed[i] = polygon[polygon.length - 1 - i];
        }
        return reversed;
    }

    private static void setHandlePositions(NodeCall call) {
        GeometrySet geometry = call.geometry("Curve");
        CurveGeometry curves = geometry.curves;
        if (curves == null) {
            call.out("Curve", geometry);
            return;
        }
        String name = "LEFT".equals(call.prop("mode", "LEFT")) ? "handle_left" : "handle_right";
        FieldContext context = call.context(curves, Domain.POINT);
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), curves, Domain.POINT);
        Column position = context.evalAs(call.field("Position"), DataType.VECTOR);
        Column offset = context.evalAs(call.field("Offset"), DataType.VECTOR);
        Column existing = curves.read(name, Domain.POINT, DataType.VECTOR);
        Column out = existing == null ? Column.ofFloats(DataType.VECTOR, curves.positions().clone()) : existing.copy();
        for (int k = 0; k < curves.pointCount(); k++) {
            if (selection[k]) {
                for (int a = 0; a < 3; a++) {
                    out.f[3 * k + a] = position.f[3 * k + a] + offset.f[3 * k + a];
                }
            }
        }
        call.out("Curve", geometry.withCurves((CurveGeometry) GeometryOps.withAttribute(curves, name, Domain.POINT, out)));
    }

    /** Mesh to Curve: chains of selected edges become poly curves (closed loops become cyclic curves). */
    private static void meshToCurve(NodeCall call) {
        GeometrySet geometry = call.geometry("Mesh");
        MeshGeometry mesh = geometry.mesh;
        if (mesh == null) {
            call.out("Curve", GeometrySet.EMPTY);
            return;
        }
        boolean[] selected = call.selection(call.field("Selection", DataType.BOOL), mesh, Domain.EDGE);
        int verts = mesh.vertCount();
        List<List<Integer>> adjacency = new ArrayList<>();
        for (int v = 0; v < verts; v++) {
            adjacency.add(new ArrayList<>());
        }
        int[] ev = mesh.topology.edgeVerts;
        for (int e = 0; e < mesh.edgeCount(); e++) {
            if (selected[e]) {
                adjacency.get(ev[2 * e]).add(ev[2 * e + 1]);
                adjacency.get(ev[2 * e + 1]).add(ev[2 * e]);
            }
        }
        boolean[] usedEdge = new boolean[mesh.edgeCount()];
        java.util.Map<Long, Integer> edgeIndex = new java.util.HashMap<>();
        for (int e = 0; e < mesh.edgeCount(); e++) {
            if (selected[e]) {
                edgeIndex.put(key(ev[2 * e], ev[2 * e + 1]), e);
            }
        }
        List<int[]> chains = new ArrayList<>();
        List<Boolean> cyclic = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int v = 0; v < verts; v++) {
                int degree = adjacency.get(v).size();
                if (pass == 0 && (degree == 0 || degree == 2)) {
                    continue;
                }
                for (int next : adjacency.get(v)) {
                    int e = edgeIndex.get(key(v, next));
                    if (usedEdge[e]) {
                        continue;
                    }
                    List<Integer> chain = new ArrayList<>();
                    chain.add(v);
                    int prev = v, cur = next;
                    usedEdge[e] = true;
                    boolean closed = false;
                    while (true) {
                        if (cur == v) {
                            closed = true;
                            break;
                        }
                        chain.add(cur);
                        if (adjacency.get(cur).size() != 2) {
                            break;
                        }
                        int nxt = adjacency.get(cur).get(0) == prev ? adjacency.get(cur).get(1) : adjacency.get(cur).get(0);
                        int ne = edgeIndex.get(key(cur, nxt));
                        if (usedEdge[ne]) {
                            break;
                        }
                        usedEdge[ne] = true;
                        prev = cur;
                        cur = nxt;
                    }
                    chains.add(chain.stream().mapToInt(Integer::intValue).toArray());
                    cyclic.add(closed);
                }
            }
        }
        int total = 0;
        for (int[] chain : chains) {
            total += chain.length;
        }
        int[] map = new int[total];
        int[] sizes = new int[chains.size()];
        boolean[] cyc = new boolean[chains.size()];
        int at = 0;
        for (int c = 0; c < chains.size(); c++) {
            for (int v : chains.get(c)) {
                map[at++] = v;
            }
            sizes[c] = chains.get(c).length;
            cyc[c] = cyclic.get(c);
        }
        float[] positions = mesh.attributes().column("position").gather(map).f;
        CurveGeometry curves = CurveGeometry.build(positions, sizes, cyc, CurveGeometry.POLY);
        Attributes attributes = curves.attributes().copy();
        for (Attributes.Attribute a : mesh.attributes().all()) {
            if (a.domain() == Domain.POINT && !a.name().equals("position")) {
                attributes.put(a.name(), Domain.POINT, a.data().gather(map));
            }
        }
        call.out("Curve", GeometrySet.of(curves.withAttributes(attributes)));
    }

    private static long key(int a, int b) {
        return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    }

    // --- Vector helpers ----------------------------------------------------------------------------------------

    static float[] sub(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static float[] normalized(float[] a) {
        float len = (float) Math.sqrt(dot(a, a));
        return len > 0f ? new float[]{a[0] / len, a[1] / len, a[2] / len} : new float[]{0f, 0f, 0f};
    }
}
