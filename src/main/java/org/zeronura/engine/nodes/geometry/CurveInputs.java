package org.zeronura.engine.nodes.geometry;

import java.util.Arrays;
import java.util.Map;

/** Input nodes that read curves: spline parameter, tangent, lengths, handles and curve topology. */
final class CurveInputs {

    private CurveInputs() {
    }

    interface CurveFill {
        void fill(CurveGeometry curves, Column out);
    }

    /** A field computed on one curve domain and adapted to the domain it is evaluated on. */
    static Field curveField(DataType type, Domain native_, CurveFill fill) {
        return new Field.Input(type) {
            @Override
            protected Column compute(FieldContext context) {
                CurveGeometry curves = context.curves();
                if (curves == null) {
                    return Column.create(type, context.size);
                }
                Column values = Column.create(type, curves.domainSize(native_));
                fill.fill(curves, values);
                return curves.adapt(values, native_, context.domain).convert(type);
            }
        };
    }

    /** The arc length at each control point. */
    static float[] controlLengths(CurveGeometry curves, int curve) {
        CurveGeometry.Evaluated e = curves.evaluated(curve);
        int n = curves.curveSize(curve);
        float[] out = new float[n];
        for (int k = 0; k < n; k++) {
            int ei = Math.min(e.controlIndex[k], e.count() - 1);
            out[k] = e.count() == 0 ? 0f : e.lengths[ei];
        }
        return out;
    }

    static void register(Map<String, NodeImpl> map) {
        map.put("GeometryNodeSplineParameter", CurveInputs::splineParameter);
        map.put("GeometryNodeInputTangent", call -> call.out("Tangent", new Field.Input(DataType.VECTOR) {
            @Override
            protected Column compute(FieldContext context) {
                CurveGeometry curves = context.curves();
                if (curves == null) {
                    return Column.create(DataType.VECTOR, context.size);
                }
                Column points = Column.ofFloats(DataType.VECTOR, curves.controlTangents());
                return curves.adapt(points, Domain.POINT, context.domain);
            }
        }));
        map.put("GeometryNodeSplineLength", call -> {
            call.out("Length", curveField(DataType.FLOAT, Domain.CURVE, (curves, out) -> {
                for (int c = 0; c < curves.curveCount(); c++) {
                    out.f[c] = curves.evaluated(c).totalLength();
                }
            }));
            call.out("Point Count", curveField(DataType.INT, Domain.CURVE, (curves, out) -> {
                for (int c = 0; c < curves.curveCount(); c++) {
                    out.i[c] = curves.curveSize(c);
                }
            }));
        });
        map.put("GeometryNodeInputCurveTilt", call -> call.out("Tilt", Inputs.named("tilt", DataType.FLOAT)));
        map.put("GeometryNodeInputSplineCyclic", call -> call.out("Cyclic", Inputs.named("cyclic", DataType.BOOL)));
        map.put("GeometryNodeInputSplineResolution", call -> call.out("Resolution", Inputs.named("resolution", DataType.INT)));
        map.put("GeometryNodeInputCurveHandlePositions", CurveInputs::handlePositions);
        map.put("GeometryNodeCurveEndpointSelection", CurveInputs::endpointSelection);
        map.put("GeometryNodeCurveOfPoint", call -> {
            Field point = call.field("Point Index", DataType.INT);
            call.out("Curve Index", curveLookup(point, (curves, p) -> curves.pointCurves()[p]));
            call.out("Index in Curve", curveLookup(point, (curves, p) -> p - curves.curveOffsets[curves.pointCurves()[p]]));
        });
        map.put("GeometryNodePointsOfCurve", CurveInputs::pointsOfCurve);
        map.put("GeometryNodeOffsetPointInCurve", CurveInputs::offsetPointInCurve);
    }

    private static void splineParameter(NodeCall call) {
        DataType[] types = {DataType.FLOAT, DataType.FLOAT, DataType.INT};
        Field.Multi shared = new Field.Multi(types, null, new Field[0]) {
            @Override
            Column[] compute(FieldContext context) {
                Column[] out = {Column.create(DataType.FLOAT, context.size), Column.create(DataType.FLOAT, context.size),
                        Column.create(DataType.INT, context.size)};
                CurveGeometry curves = context.curves();
                if (curves == null) {
                    return out;
                }
                if (context.domain == Domain.CURVE) {
                    float total = 0f;
                    float[] lengths = new float[curves.curveCount()];
                    for (int c = 0; c < curves.curveCount(); c++) {
                        lengths[c] = total;
                        total += curves.evaluated(c).totalLength();
                    }
                    for (int c = 0; c < curves.curveCount(); c++) {
                        out[0].f[c] = total > 0f ? lengths[c] / total : 0f;
                        out[1].f[c] = lengths[c];
                        out[2].i[c] = c;
                    }
                    return out;
                }
                Column factor = Column.create(DataType.FLOAT, curves.pointCount());
                Column length = Column.create(DataType.FLOAT, curves.pointCount());
                Column index = Column.create(DataType.INT, curves.pointCount());
                for (int c = 0; c < curves.curveCount(); c++) {
                    float[] lengths = controlLengths(curves, c);
                    float total = curves.evaluated(c).totalLength();
                    int n = curves.curveSize(c);
                    for (int k = 0; k < n; k++) {
                        int p = curves.curveOffsets[c] + k;
                        length.f[p] = lengths[k];
                        factor.f[p] = total > 0f ? lengths[k] / total : (n > 1 ? k / (float) (n - 1) : 0f);
                        index.i[p] = k;
                    }
                }
                out[0] = curves.adapt(factor, Domain.POINT, context.domain);
                out[1] = curves.adapt(length, Domain.POINT, context.domain);
                out[2] = curves.adapt(index, Domain.POINT, context.domain);
                return out;
            }
        };
        call.out("Factor", new Field.MultiOutput(shared, 0));
        call.out("Length", new Field.MultiOutput(shared, 1));
        call.out("Index", new Field.MultiOutput(shared, 2));
    }

    private static void handlePositions(NodeCall call) {
        Field relative = call.field("Relative", DataType.BOOL);
        for (String side : new String[]{"Left", "Right"}) {
            String name = side.equals("Left") ? "handle_left" : "handle_right";
            call.out(side, new Field.Input(DataType.VECTOR) {
                @Override
                protected Column compute(FieldContext context) {
                    if (context.curves() == null) {
                        return Column.create(DataType.VECTOR, context.size);
                    }
                    Column handles = context.component.read(name, context.domain, DataType.VECTOR);
                    Column position = context.eval(Inputs.POSITION);
                    if (handles == null) {
                        handles = position;
                    }
                    Column rel = context.evalAs(relative, DataType.BOOL);
                    Column out = Column.create(DataType.VECTOR, context.size);
                    for (int k = 0; k < 3 * context.size; k++) {
                        out.f[k] = rel.i[k / 3] != 0 ? handles.f[k] - position.f[k] : handles.f[k];
                    }
                    return out;
                }
            });
        }
    }

    private static void endpointSelection(NodeCall call) {
        Field start = call.field("Start Size", DataType.INT);
        Field end = call.field("End Size", DataType.INT);
        call.out("Selection", new Field.Input(DataType.BOOL) {
            @Override
            protected Column compute(FieldContext context) {
                CurveGeometry curves = context.curves();
                if (curves == null) {
                    return Column.create(DataType.BOOL, context.size);
                }
                Column s = context.on(Domain.CURVE).evalAs(start, DataType.INT);
                Column e = context.on(Domain.CURVE).evalAs(end, DataType.INT);
                Column points = Column.create(DataType.BOOL, curves.pointCount());
                for (int c = 0; c < curves.curveCount(); c++) {
                    int n = curves.curveSize(c);
                    for (int k = 0; k < n; k++) {
                        points.i[curves.curveOffsets[c] + k] = k < s.i[c] || k >= n - e.i[c] ? 1 : 0;
                    }
                }
                return curves.adapt(points, Domain.POINT, context.domain);
            }
        });
    }

    interface PointMap {
        int map(CurveGeometry curves, int point);
    }

    private static Field curveLookup(Field point, PointMap map) {
        return new Field.Input(DataType.INT) {
            @Override
            protected Column compute(FieldContext context) {
                Column in = context.evalAs(point, DataType.INT);
                Column out = Column.create(DataType.INT, context.size);
                CurveGeometry curves = context.curves();
                if (curves == null) {
                    return out;
                }
                for (int k = 0; k < context.size; k++) {
                    int p = in.i[k];
                    out.i[k] = p >= 0 && p < curves.pointCount() ? map.map(curves, p) : 0;
                }
                return out;
            }
        };
    }

    private static void pointsOfCurve(NodeCall call) {
        Field curve = call.field("Curve Index", DataType.INT);
        Field weights = call.field("Weights", DataType.FLOAT);
        Field sortIndex = call.field("Sort Index", DataType.INT);
        DataType[] types = {DataType.INT, DataType.INT};
        Field.Multi shared = new Field.Multi(types, null, new Field[0]) {
            @Override
            Column[] compute(FieldContext context) {
                Column[] out = {Column.create(DataType.INT, context.size), Column.create(DataType.INT, context.size)};
                CurveGeometry curves = context.curves();
                if (curves == null) {
                    return out;
                }
                Column c = context.evalAs(curve, DataType.INT);
                Column sort = context.evalAs(sortIndex, DataType.INT);
                Column w = weights.isConstant() ? null : context.on(Domain.POINT).evalAs(weights, DataType.FLOAT);
                for (int k = 0; k < context.size; k++) {
                    int ci = c.i[k];
                    if (ci < 0 || ci >= curves.curveCount()) {
                        continue;
                    }
                    int n = curves.curveSize(ci);
                    out[1].i[k] = n;
                    Integer[] order = new Integer[n];
                    for (int j = 0; j < n; j++) {
                        order[j] = curves.curveOffsets[ci] + j;
                    }
                    if (w != null) {
                        Arrays.sort(order, (a, b) -> Float.compare(w.f[a], w.f[b]));
                    }
                    out[0].i[k] = n == 0 ? 0 : order[Math.floorMod(sort.i[k], n)];
                }
                return out;
            }
        };
        call.out("Point Index", new Field.MultiOutput(shared, 0));
        call.out("Total", new Field.MultiOutput(shared, 1));
    }

    private static void offsetPointInCurve(NodeCall call) {
        Field point = call.field("Point Index", DataType.INT);
        Field offset = call.field("Offset", DataType.INT);
        DataType[] types = {DataType.BOOL, DataType.INT};
        Field.Multi shared = new Field.Multi(types, null, new Field[0]) {
            @Override
            Column[] compute(FieldContext context) {
                Column[] out = {Column.create(DataType.BOOL, context.size), Column.create(DataType.INT, context.size)};
                CurveGeometry curves = context.curves();
                if (curves == null) {
                    return out;
                }
                Column p = context.evalAs(point, DataType.INT);
                Column o = context.evalAs(offset, DataType.INT);
                int[] pointCurves = curves.pointCurves();
                for (int k = 0; k < context.size; k++) {
                    int pi = p.i[k];
                    if (pi < 0 || pi >= curves.pointCount()) {
                        continue;
                    }
                    int c = pointCurves[pi];
                    int start = curves.curveOffsets[c];
                    int n = curves.curveSize(c);
                    int local = pi - start + o.i[k];
                    if (curves.cyclic(c)) {
                        out[0].i[k] = 1;
                        out[1].i[k] = start + Math.floorMod(local, n);
                    } else {
                        boolean valid = local >= 0 && local < n;
                        out[0].i[k] = valid ? 1 : 0;
                        out[1].i[k] = valid ? start + local : pi;
                    }
                }
                return out;
            }
        };
        call.out("Is Valid Offset", new Field.MultiOutput(shared, 0));
        call.out("Point Index", new Field.MultiOutput(shared, 1));
    }
}
