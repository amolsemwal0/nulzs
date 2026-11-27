package org.zeronura.engine.nodes.geometry;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Where a field is being evaluated: a component of a geometry and one of its domains (or nowhere, for a single
 * value). Results are cached per field for the life of the context, so a field shared by many others in a
 * graph is computed once.
 */
public final class FieldContext {

    public final GeometryComponent component;
    public final Domain domain;
    public final int size;
    /** Scene time, the object being evaluated and so on, for inputs like Scene Time and Self Object. */
    public final EvalEnv env;

    private final Map<Object, Object> cache = new IdentityHashMap<>();
    private final Map<Domain, FieldContext> siblings;

    public FieldContext(GeometryComponent component, Domain domain, EvalEnv env) {
        this(component, domain, component == null ? 1 : component.count(domain), env, new java.util.EnumMap<>(Domain.class));
    }

    private FieldContext(GeometryComponent component, Domain domain, int size, EvalEnv env,
                         Map<Domain, FieldContext> siblings) {
        this.component = component;
        this.domain = domain;
        this.size = size;
        this.env = env;
        this.siblings = siblings;
        siblings.putIfAbsent(domain, this);
    }

    /** A context with no geometry and one element, where inputs read as their defaults. */
    public static FieldContext single() {
        return new FieldContext(null, Domain.POINT, 1, EvalEnv.CURRENT.get(), new java.util.EnumMap<>(Domain.class));
    }

    /** The same geometry on another domain, sharing nothing but the geometry. */
    public FieldContext on(Domain other) {
        if (other == domain) {
            return this;
        }
        FieldContext existing = siblings.get(other);
        if (existing != null) {
            return existing;
        }
        return new FieldContext(component, other, component == null ? 1 : component.count(other), env, siblings);
    }

    public Column eval(Field field) {
        Object cached = cache.get(field);
        if (cached != null) {
            return (Column) cached;
        }
        Column result = field.compute(this);
        if (result.type != field.type) {
            result = result.convert(field.type);
        }
        if (result.size != size) {
            throw new IllegalStateException(field.getClass().getSimpleName() + " gave " + result.size
                    + " values for " + size + " elements");
        }
        cache.put(field, result);
        return result;
    }

    public Column evalAs(Field field, DataType type) {
        return eval(field).convert(type);
    }

    Column[] evalMulti(Field.Multi shared) {
        Object cached = cache.get(shared);
        if (cached != null) {
            return (Column[]) cached;
        }
        Column[] result = shared.compute(this);
        cache.put(shared, result);
        return result;
    }

    public MeshGeometry mesh() {
        return component instanceof MeshGeometry m ? m : null;
    }

    public CurveGeometry curves() {
        return component instanceof CurveGeometry c ? c : null;
    }
}
