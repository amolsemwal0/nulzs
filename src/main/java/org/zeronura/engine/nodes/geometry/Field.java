package org.zeronura.engine.nodes.geometry;

/**
 * A value that can vary per element of a geometry: Blender's fields. A field is a recipe ("position times
 * two", "a random value per ID"); it only produces numbers when a node evaluates it on a geometry's domain, as
 * a {@link Column} of one value per element.
 *
 * <p>Constant values are fields too ({@link #constant}), and a field computed purely from constants is folded
 * into a constant when it is built, so a node can tell whether it got one value or many.
 */
public abstract class Field {

    public final DataType type;

    protected Field(DataType type) {
        this.type = type;
    }

    /** One value per element of the context's domain, of this field's type. Called through {@link FieldContext#eval}. */
    protected abstract Column compute(FieldContext context);

    public boolean isConstant() {
        return false;
    }

    /** Whether evaluating this field reads the geometry it is evaluated on (an input like Position or Index). */
    public boolean dependsOnGeometry() {
        return !isConstant();
    }

    // --- Constants -----------------------------------------------------------------------------------------

    public static Field constant(DataType type, Object value) {
        return new Constant(Column.single(type, value));
    }

    public static Field constant(Column single) {
        return new Constant(single);
    }

    public static final class Constant extends Field {
        public final Column value;

        Constant(Column value) {
            super(value.type);
            this.value = value;
        }

        @Override
        protected Column compute(FieldContext context) {
            return Column.constant(type, value.firstValue(), context.size);
        }

        @Override
        public boolean isConstant() {
            return true;
        }
    }

    /** The value of a constant field, or of any field evaluated with no geometry. */
    public Column single() {
        if (this instanceof Constant c) {
            return c.value;
        }
        return FieldContext.single().eval(this);
    }

    public float singleFloat() {
        return single().getFloat(0);
    }

    public int singleInt() {
        return single().getInt(0);
    }

    public boolean singleBool() {
        return single().getBool(0);
    }

    public float[] singleVector() {
        return single().getVector(0, new float[type == DataType.COLOR || type == DataType.ROTATION ? 4 : 3]);
    }

    // --- Conversion ----------------------------------------------------------------------------------------

    /** This field as another type, converting values as Blender's implicit conversions do. */
    public Field as(DataType target) {
        if (target == type) {
            return this;
        }
        if (this instanceof Constant c) {
            return new Constant(c.value.convert(target));
        }
        Field source = this;
        return new Field(target) {
            @Override
            protected Column compute(FieldContext context) {
                return context.eval(source).convert(target);
            }
        };
    }

    // --- Per-element functions -----------------------------------------------------------------------------

    /** Fills {@code out} (already sized) from the input columns. */
    @FunctionalInterface
    public interface Kernel {
        void run(Column[] inputs, Column out, int count);
    }

    /**
     * A field computed element-wise from other fields. When every input is constant the result is computed once
     * now and returned as a constant.
     */
    public static Field of(DataType type, Kernel kernel, Field... inputs) {
        boolean constant = true;
        for (Field input : inputs) {
            constant &= input.isConstant();
        }
        Field field = new KernelField(type, kernel, inputs);
        if (constant) {
            return new Constant(FieldContext.single().eval(field));
        }
        return field;
    }

    static final class KernelField extends Field {
        private final Kernel kernel;
        private final Field[] inputs;

        KernelField(DataType type, Kernel kernel, Field[] inputs) {
            super(type);
            this.kernel = kernel;
            this.inputs = inputs;
        }

        @Override
        protected Column compute(FieldContext context) {
            Column[] columns = new Column[inputs.length];
            for (int k = 0; k < inputs.length; k++) {
                columns[k] = context.eval(inputs[k]);
            }
            Column out = Column.create(type, context.size);
            kernel.run(columns, out, context.size);
            return out;
        }
    }

    /** Several outputs computed together (a texture's value and colour), so each is computed once per context. */
    @FunctionalInterface
    public interface MultiKernel {
        void run(Column[] inputs, Column[] outputs, int count);
    }

    /** The outputs of one multi-output computation, one field per output type. */
    public static Field[] multi(DataType[] types, MultiKernel kernel, Field... inputs) {
        boolean constant = true;
        for (Field input : inputs) {
            constant &= input.isConstant();
        }
        Multi shared = new Multi(types, kernel, inputs);
        Field[] out = new Field[types.length];
        for (int k = 0; k < types.length; k++) {
            Field field = new MultiOutput(shared, k);
            out[k] = constant ? new Constant(FieldContext.single().eval(field)) : field;
        }
        return out;
    }

    static class Multi {
        final DataType[] types;
        final MultiKernel kernel;
        final Field[] inputs;

        Multi(DataType[] types, MultiKernel kernel, Field[] inputs) {
            this.types = types;
            this.kernel = kernel;
            this.inputs = inputs;
        }

        Column[] compute(FieldContext context) {
            Column[] in = new Column[inputs.length];
            for (int k = 0; k < inputs.length; k++) {
                in[k] = context.eval(inputs[k]);
            }
            Column[] out = new Column[types.length];
            for (int k = 0; k < types.length; k++) {
                out[k] = Column.create(types[k], context.size);
            }
            kernel.run(in, out, context.size);
            return out;
        }
    }

    static final class MultiOutput extends Field {
        final Multi shared;
        final int index;

        MultiOutput(Multi shared, int index) {
            super(shared.types[index]);
            this.shared = shared;
            this.index = index;
        }

        @Override
        protected Column compute(FieldContext context) {
            return context.evalMulti(shared)[index];
        }
    }

    // --- Context-reading fields ----------------------------------------------------------------------------

    /** A field that reads the geometry it is evaluated on. */
    public abstract static class Input extends Field {
        protected Input(DataType type) {
            super(type);
        }
    }

    /** A field over another domain of the same geometry, moved onto the evaluating domain afterwards. */
    public static Field onDomain(Field inner, Domain domain) {
        if (inner.isConstant()) {
            return inner;
        }
        return new Field(inner.type) {
            @Override
            protected Column compute(FieldContext context) {
                GeometryComponent component = context.component;
                if (component == null || !component.hasDomain(domain) || domain == context.domain) {
                    return context.eval(inner);
                }
                Column values = context.on(domain).eval(inner);
                return component.adapt(values, domain, context.domain);
            }
        };
    }
}
