package org.zeronura.nulzs.nodes.geometry;

/**
 * One kind of geometry in a {@link GeometrySet}: a mesh, curves, a point cloud or instances.
 *
 * <p>Components are immutable once built: every node that changes one returns a new one, sharing whatever
 * columns it did not touch.
 */
public abstract sealed class GeometryComponent
        permits MeshGeometry, CurveGeometry, PointGeometry, InstanceGeometry {

    protected final Attributes attributes;

    protected GeometryComponent(Attributes attributes) {
        this.attributes = attributes;
    }

    public Attributes attributes() {
        return attributes;
    }

    public abstract int domainSize(Domain domain);

    /** The domain a field is evaluated on when a node does not say: points, or instances. */
    public abstract Domain defaultDomain();

    /** Whether this component has elements on a domain at all (a point cloud has no faces). */
    public abstract boolean hasDomain(Domain domain);

    /** A copy with a different attribute set: the same elements, other data. */
    public abstract GeometryComponent withAttributes(Attributes attributes);

    /**
     * A column on one domain moved onto another, as Blender interpolates attributes between domains
     * (averaging, mostly). Returns the column unchanged when the domains match.
     */
    public Column adapt(Column column, Domain from, Domain to) {
        if (from == to) {
            return column;
        }
        return Column.create(column.type, domainSize(to));
    }

    /** Reads an attribute onto a domain, converted to a type; null when the attribute does not exist. */
    public Column read(String name, Domain domain, DataType type) {
        Attributes.Attribute attribute = attributes.get(name);
        if (attribute == null) {
            return null;
        }
        return adapt(attribute.data(), attribute.domain(), domain).convert(type);
    }

    public int count(Domain domain) {
        return hasDomain(domain) ? domainSize(domain) : 0;
    }
}
