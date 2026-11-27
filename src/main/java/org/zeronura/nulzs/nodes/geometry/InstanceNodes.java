package org.zeronura.nulzs.nodes.geometry;

import org.zeronura.nulzs.nodes.Ref;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Instancing: Instance on Points, Realize Instances, instance transforms, and Object and Collection Info. */
final class InstanceNodes {

    private InstanceNodes() {
    }

    /** The instance transform as a matrix field (Instance Transform node, and group "Default Input"). */
    static final Field INSTANCE_TRANSFORM = new Field.Input(DataType.MATRIX) {
        @Override
        protected Column compute(FieldContext context) {
            if (context.component instanceof InstanceGeometry instances) {
                return Column.ofFloats(DataType.MATRIX, instances.transforms().clone());
            }
            Column out = Column.create(DataType.MATRIX, context.size);
            for (int k = 0; k < context.size; k++) {
                Transforms.identity(out.f, 16 * k);
            }
            return out;
        }
    };

    static void register(Map<String, NodeImpl> map) {
        map.put("GeometryNodeInstanceOnPoints", InstanceNodes::instanceOnPoints);
        map.put("GeometryNodeRealizeInstances", call -> call.out("Geometry", GeometryOps.realize(call.geometry("Geometry"))));
        map.put("GeometryNodeRotateInstances", InstanceNodes::rotateInstances);
        map.put("GeometryNodeScaleInstances", InstanceNodes::scaleInstances);
        map.put("GeometryNodeTranslateInstances", InstanceNodes::translateInstances);
        map.put("GeometryNodeSetInstanceTransform", InstanceNodes::setInstanceTransform);
        map.put("GeometryNodeInstanceTransform", call -> call.out("Transform", INSTANCE_TRANSFORM));
        map.put("GeometryNodeInputInstanceRotation", call -> call.out("Rotation", INSTANCE_TRANSFORM.as(DataType.ROTATION)));
        map.put("GeometryNodeInputInstanceScale", call -> call.out("Scale", Field.of(DataType.VECTOR, (in, out, n) -> {
            float[] d = new float[10];
            for (int k = 0; k < n; k++) {
                Transforms.decompose(in[0].f, 16 * k, d);
                out.setVector(k, d[7], d[8], d[9]);
            }
        }, INSTANCE_TRANSFORM)));
        map.put("GeometryNodeGeometryToInstance", call -> {
            List<GeometrySet> parts = call.geometries("Geometry");
            float[] transforms = new float[parts.size() * 16];
            int[] handles = new int[parts.size()];
            for (int k = 0; k < parts.size(); k++) {
                Transforms.identity(transforms, 16 * k);
                handles[k] = k;
            }
            call.out("Instances", GeometrySet.of(InstanceGeometry.of(parts, handles, transforms)));
        });
        map.put("GeometryNodeInstancesToPoints", InstanceNodes::instancesToPoints);
        map.put("GeometryNodeObjectInfo", InstanceNodes::objectInfo);
        map.put("GeometryNodeCollectionInfo", InstanceNodes::collectionInfo);
    }

    // --- Instance on Points --------------------------------------------------------------------------------

    private static void instanceOnPoints(NodeCall call) {
        GeometrySet points = call.geometry("Points");
        GeometrySet instance = call.geometry("Instance");
        boolean pick = call.b("Pick Instance");
        List<InstanceGeometry> results = new ArrayList<>();
        for (GeometryComponent component : points.components()) {
            if (component instanceof InstanceGeometry) {
                continue;
            }
            InstanceGeometry made = instanceOn(call, component, instance, pick);
            if (made != null && made.count() > 0) {
                results.add(made);
            }
        }
        GeometrySet out = GeometrySet.EMPTY;
        if (points.instances != null && points.instances.count() > 0) {
            results.add(0, points.instances);
        }
        if (!results.isEmpty()) {
            out = out.withInstances(GeometryOps.joinInstances(results));
        }
        call.out("Instances", out.withName(points.name));
    }

    private static InstanceGeometry instanceOn(NodeCall call, GeometryComponent component, GeometrySet instance, boolean pick) {
        FieldContext context = call.context(component, Domain.POINT);
        int n = context.size;
        if (n == 0) {
            return null;
        }
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), component, Domain.POINT);
        Column positions = context.eval(Inputs.POSITION);
        Column rotation = context.evalAs(call.field("Rotation"), DataType.ROTATION);
        Column scale = context.evalAs(call.field("Scale"), DataType.VECTOR);
        Column index = pick ? context.evalAs(call.field("Instance Index"), DataType.INT) : null;
        List<GeometrySet> references = new ArrayList<>();
        float[] pickedTransforms = null;
        if (pick && instance.instances != null && instance.instances.count() > 0) {
            InstanceGeometry source = instance.instances;
            for (int k = 0; k < source.count(); k++) {
                references.add(source.reference(k));
            }
            pickedTransforms = source.transforms();
        } else if (!instance.isEmpty() || instance.instances != null) {
            references.add(instance);
        }
        if (references.isEmpty()) {
            return null;
        }
        int[] kept = GeometryOps.indices(selection);
        float[] transforms = new float[kept.length * 16];
        int[] handles = new int[kept.length];
        float[] m = new float[16];
        for (int j = 0; j < kept.length; j++) {
            int k = kept[j];
            Transforms.compose(positions.f[3 * k], positions.f[3 * k + 1], positions.f[3 * k + 2], rotation.f, 4 * k,
                    scale.f[3 * k], scale.f[3 * k + 1], scale.f[3 * k + 2], m, 0);
            int handle = 0;
            if (pickedTransforms != null) {
                handle = Math.floorMod(index.i[k], references.size());
                Transforms.mul(m, 0, pickedTransforms, 16 * handle, transforms, 16 * j);
            } else {
                System.arraycopy(m, 0, transforms, 16 * j, 16);
            }
            handles[j] = handle;
        }
        InstanceGeometry made = InstanceGeometry.of(references, handles, transforms);
        Attributes attributes = made.attributes().copy();
        for (Attributes.Attribute a : component.attributes().all()) {
            if (a.name().equals("position") || a.name().startsWith(".") && !a.name().startsWith(".anon.")) {
                continue;
            }
            Column values = component.adapt(a.data(), a.domain(), Domain.POINT);
            if (values.size != n) {
                continue;
            }
            attributes.put(a.name(), Domain.INSTANCE, values.gather(kept));
        }
        return made.withAttributes(attributes);
    }

    // --- Instance transforms -------------------------------------------------------------------------------

    private interface TransformEdit {
        void apply(float[] matrix, int k, Column[] inputs, float[] out);
    }

    private static void editInstances(NodeCall call, TransformEdit edit, Field... fields) {
        GeometrySet geometry = call.geometry("Instances");
        InstanceGeometry instances = geometry.instances;
        if (instances == null || instances.count() == 0) {
            call.out("Instances", geometry);
            return;
        }
        FieldContext context = call.context(instances, Domain.INSTANCE);
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), instances, Domain.INSTANCE);
        Column[] inputs = new Column[fields.length];
        for (int k = 0; k < fields.length; k++) {
            inputs[k] = context.eval(fields[k]);
        }
        float[] t = instances.transforms().clone();
        float[] m = new float[16];
        float[] out = new float[16];
        for (int k = 0; k < instances.count(); k++) {
            if (!selection[k]) {
                continue;
            }
            System.arraycopy(t, 16 * k, m, 0, 16);
            edit.apply(m, k, inputs, out);
            System.arraycopy(out, 0, t, 16 * k, 16);
        }
        call.out("Instances", geometry.withInstances(instances.withTransforms(t)));
    }

    private static float[] translate(float x, float y, float z) {
        return MeshPrimitives.translation(x, y, z);
    }

    private static void rotateInstances(NodeCall call) {
        editInstances(call, (m, k, in, out) -> {
            float[] r = new float[16];
            Transforms.quatToMatrix(in[0].f, 4 * k, r, 0);
            float px = in[1].f[3 * k], py = in[1].f[3 * k + 1], pz = in[1].f[3 * k + 2];
            boolean local = in[2].i[k] != 0;
            float[] around;
            if (local) {
                around = Transforms.mul(Transforms.mul(translate(px, py, pz), r), translate(-px, -py, -pz));
                Transforms.mul(m, 0, around, 0, out, 0);
            } else {
                float cx = m[12] + px, cy = m[13] + py, cz = m[14] + pz;
                around = Transforms.mul(Transforms.mul(translate(cx, cy, cz), r), translate(-cx, -cy, -cz));
                Transforms.mul(around, 0, m, 0, out, 0);
            }
        }, call.field("Rotation", DataType.ROTATION), call.field("Pivot Point", DataType.VECTOR),
                call.field("Local Space", DataType.BOOL));
    }

    private static void scaleInstances(NodeCall call) {
        editInstances(call, (m, k, in, out) -> {
            float[] s = Transforms.identity();
            s[0] = in[0].f[3 * k];
            s[5] = in[0].f[3 * k + 1];
            s[10] = in[0].f[3 * k + 2];
            float cx = in[1].f[3 * k], cy = in[1].f[3 * k + 1], cz = in[1].f[3 * k + 2];
            boolean local = in[2].i[k] != 0;
            if (local) {
                float[] around = Transforms.mul(Transforms.mul(translate(cx, cy, cz), s), translate(-cx, -cy, -cz));
                Transforms.mul(m, 0, around, 0, out, 0);
            } else {
                float wx = m[12] + cx, wy = m[13] + cy, wz = m[14] + cz;
                float[] around = Transforms.mul(Transforms.mul(translate(wx, wy, wz), s), translate(-wx, -wy, -wz));
                Transforms.mul(around, 0, m, 0, out, 0);
            }
        }, call.field("Scale", DataType.VECTOR), call.field("Center", DataType.VECTOR), call.field("Local Space", DataType.BOOL));
    }

    private static void translateInstances(NodeCall call) {
        editInstances(call, (m, k, in, out) -> {
            float[] t = translate(in[0].f[3 * k], in[0].f[3 * k + 1], in[0].f[3 * k + 2]);
            if (in[1].i[k] != 0) {
                Transforms.mul(m, 0, t, 0, out, 0);
            } else {
                Transforms.mul(t, 0, m, 0, out, 0);
            }
        }, call.field("Translation", DataType.VECTOR), call.field("Local Space", DataType.BOOL));
    }

    private static void setInstanceTransform(NodeCall call) {
        editInstances(call, (m, k, in, out) -> System.arraycopy(in[0].f, 16 * k, out, 0, 16),
                call.field("Transform", DataType.MATRIX));
    }

    private static void instancesToPoints(NodeCall call) {
        GeometrySet geometry = call.geometry("Instances");
        InstanceGeometry instances = geometry.instances;
        if (instances == null) {
            call.out("Points", GeometrySet.EMPTY);
            return;
        }
        FieldContext context = call.context(instances, Domain.INSTANCE);
        boolean[] selection = call.selection(call.field("Selection", DataType.BOOL), instances, Domain.INSTANCE);
        int[] kept = GeometryOps.indices(selection);
        Column positions = context.evalAs(call.field("Position"), DataType.VECTOR).gather(kept);
        Column radius = context.evalAs(call.field("Radius"), DataType.FLOAT).gather(kept);
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, positions);
        attributes.put("radius", Domain.POINT, radius);
        for (Attributes.Attribute a : instances.attributes().all()) {
            if (!a.name().equals(InstanceGeometry.TRANSFORM) && !a.name().equals(InstanceGeometry.REFERENCE)) {
                attributes.put(a.name(), Domain.POINT, a.data().gather(kept));
            }
        }
        call.out("Points", GeometrySet.of(new PointGeometry(kept.length, attributes)));
    }

    // --- Objects and collections ---------------------------------------------------------------------------

    private static void objectInfo(NodeCall call) {
        Ref object = call.ref("Object");
        if (object == null) {
            return;
        }
        boolean relative = "RELATIVE".equals(call.prop("transform_space", "ORIGINAL"));
        float[] world = call.resolver().objectMatrix(object.name());
        float[] transform = relative ? Transforms.mul(Transforms.invert(call.env().selfMatrix()), world) : world;
        float[] decomposed = new float[10];
        Transforms.decompose(transform, 0, decomposed);
        call.out("Transform", Field.constant(DataType.MATRIX, transform));
        call.out("Location", Field.constant(DataType.VECTOR, new float[]{decomposed[0], decomposed[1], decomposed[2]}));
        call.out("Rotation", Field.constant(DataType.ROTATION, new float[]{decomposed[3], decomposed[4], decomposed[5], decomposed[6]}));
        call.out("Scale", Field.constant(DataType.VECTOR, new float[]{decomposed[7], decomposed[8], decomposed[9]}));
        GeometrySet geometry = call.resolver().objectGeometry(object.name(), call.env());
        float[] placement = relative ? transform : Transforms.identity();
        if (call.b("As Instance")) {
            geometry = GeometrySet.of(InstanceGeometry.of(List.of(geometry), new int[]{0}, placement));
        } else if (relative) {
            geometry = GeometryOps.transform(geometry, placement);
        }
        call.out("Geometry", geometry);
    }

    private static void collectionInfo(NodeCall call) {
        Ref collection = call.ref("Collection");
        if (collection == null) {
            call.out("Instances", GeometrySet.EMPTY);
            return;
        }
        boolean separate = call.b("Separate Children");
        boolean reset = call.b("Reset Children");
        boolean relative = "RELATIVE".equals(call.prop("transform_space", "ORIGINAL"));
        InstanceGeometry children = collectionInstances(call, collection.name(), reset, 0);
        if (children == null) {
            call.out("Instances", GeometrySet.EMPTY);
            return;
        }
        float[] base = relative ? Transforms.invert(call.env().selfMatrix()) : Transforms.identity();
        InstanceGeometry out;
        if (separate) {
            out = GeometryOps.transform(children, base);
        } else {
            out = InstanceGeometry.of(List.of(GeometrySet.of(children)), new int[]{0}, base);
        }
        call.out("Instances", GeometrySet.of(out));
    }

    /** A collection's objects and child collections as instances, each placed relative to the collection's offset. */
    static InstanceGeometry collectionInstances(NodeCall call, String name, boolean reset, int depth) {
        NodeResolver.CollectionContents contents = call.resolver().collection(name);
        if (contents == null || depth > 16) {
            return null;
        }
        float[] offset = MeshPrimitives.translation(-contents.offset()[0], -contents.offset()[1], -contents.offset()[2]);
        List<GeometrySet> references = new ArrayList<>();
        List<float[]> transforms = new ArrayList<>();
        for (String object : contents.objects()) {
            references.add(call.resolver().objectGeometry(object, call.env()));
            transforms.add(reset ? Transforms.identity() : Transforms.mul(offset, call.resolver().objectMatrix(object)));
        }
        for (String child : contents.children()) {
            InstanceGeometry nested = collectionInstances(call, child, reset, depth + 1);
            if (nested != null) {
                references.add(GeometrySet.of(nested));
                transforms.add(reset ? Transforms.identity() : offset);
            }
        }
        int[] handles = new int[references.size()];
        float[] packed = new float[references.size() * 16];
        for (int k = 0; k < handles.length; k++) {
            handles[k] = k;
            System.arraycopy(transforms.get(k), 0, packed, 16 * k, 16);
        }
        return InstanceGeometry.of(references, handles, packed);
    }
}
