package org.zeronura.engine.nodes.geometry;

/**
 * The fields input nodes produce: Position, Normal, Index, ID, named attributes and the rest. Each reads the
 * geometry it is evaluated on; with no geometry it reads as zero, as in Blender.
 */
public final class Inputs {

    private Inputs() {
    }

    public static final Field POSITION = new Field.Input(DataType.VECTOR) {
        @Override
        protected Column compute(FieldContext context) {
            if (context.component == null) {
                return Column.create(DataType.VECTOR, context.size);
            }
            Column c = context.component.read("position", context.domain, DataType.VECTOR);
            return c != null ? c : Column.create(DataType.VECTOR, context.size);
        }
    };

    public static final Field INDEX = new Field.Input(DataType.INT) {
        @Override
        protected Column compute(FieldContext context) {
            int[] out = new int[context.size];
            for (int k = 0; k < out.length; k++) {
                out[k] = k;
            }
            return Column.ofInts(DataType.INT, out);
        }
    };

    /** The {@code id} attribute where there is one, otherwise the index: what Random Value is keyed by. */
    public static final Field ID_OR_INDEX = new Field.Input(DataType.INT) {
        @Override
        protected Column compute(FieldContext context) {
            if (context.component != null) {
                Attributes.Attribute id = context.component.attributes().get("id");
                if (id != null) {
                    return context.component.adapt(id.data(), id.domain(), context.domain).convert(DataType.INT);
                }
            }
            return context.eval(INDEX);
        }
    };

    /** The ID input node: the {@code id} attribute, or the index where there is none. */
    public static final Field ID = ID_OR_INDEX;

    public static final Field NORMAL = new Field.Input(DataType.VECTOR) {
        @Override
        protected Column compute(FieldContext context) {
            GeometryComponent component = context.component;
            if (component instanceof MeshGeometry mesh) {
                float[] values = switch (context.domain) {
                    case POINT -> mesh.vertNormals();
                    case FACE -> mesh.faceNormals();
                    case CORNER -> mesh.cornerNormals();
                    case EDGE -> mesh.edgeNormals();
                    default -> null;
                };
                if (values != null) {
                    return Column.ofFloats(DataType.VECTOR, values.clone());
                }
            }
            if (component instanceof CurveGeometry curves && curves.pointCount() > 0) {
                Column points = Column.ofFloats(DataType.VECTOR, curves.controlNormals());
                return curves.adapt(points, Domain.POINT, context.domain);
            }
            Column out = Column.create(DataType.VECTOR, context.size);
            for (int k = 0; k < context.size; k++) {
                out.f[3 * k + 2] = 1f;
            }
            return out;
        }
    };

    /** A named attribute (or nothing, read as zero) of a type, moved onto the evaluating domain. */
    public static Field named(String name, DataType type) {
        return new NamedAttribute(name, type);
    }

    public static final class NamedAttribute extends Field.Input {
        public final String name;

        NamedAttribute(String name, DataType type) {
            super(type);
            this.name = name;
        }

        @Override
        protected Column compute(FieldContext context) {
            if (context.component == null) {
                return Column.create(type, context.size);
            }
            Column c = context.component.read(name, context.domain, type);
            if (c == null) {
                c = builtInDefault(context, name, type);
            }
            return c;
        }
    }

    /** Built-in attributes that read as a default when absent: curve radius 1, resolution 12. */
    static Column builtInDefault(FieldContext context, String name, DataType type) {
        Object value = switch (name) {
            case "radius" -> context.component instanceof CurveGeometry ? 1f
                    : context.component instanceof PointGeometry ? 0.1f : 0f;
            case "resolution" -> 12;
            case "id" -> null;
            default -> 0f;
        };
        if ("id".equals(name)) {
            return context.eval(INDEX).convert(type);
        }
        return Column.constant(type, value, context.size);
    }

    /** Whether a named attribute exists on the geometry being evaluated. */
    public static Field exists(String name) {
        return new Field.Input(DataType.BOOL) {
            @Override
            protected Column compute(FieldContext context) {
                boolean has = context.component != null && (context.component.attributes().has(name)
                        || ("position".equals(name)) || (context.component instanceof InstanceGeometry && "position".equals(name)));
                return Column.constant(DataType.BOOL, has, context.size);
            }
        };
    }

    public static final Field RADIUS = named("radius", DataType.FLOAT);

    public static final Field SHADE_SMOOTH = new Field.Input(DataType.BOOL) {
        @Override
        protected Column compute(FieldContext context) {
            Column sharp = context.component == null ? null : context.component.read("sharp_face", context.domain, DataType.BOOL);
            Column out = Column.create(DataType.BOOL, context.size);
            for (int k = 0; k < context.size; k++) {
                out.i[k] = sharp != null && sharp.i[k] != 0 ? 0 : 1;
            }
            return out;
        }
    };

    public static final Field EDGE_SMOOTH = new Field.Input(DataType.BOOL) {
        @Override
        protected Column compute(FieldContext context) {
            Column sharp = context.component == null ? null : context.component.read("sharp_edge", context.domain, DataType.BOOL);
            Column out = Column.create(DataType.BOOL, context.size);
            for (int k = 0; k < context.size; k++) {
                out.i[k] = sharp != null && sharp.i[k] != 0 ? 0 : 1;
            }
            return out;
        }
    };

    public static final Field MATERIAL_INDEX = named("material_index", DataType.INT);
}
