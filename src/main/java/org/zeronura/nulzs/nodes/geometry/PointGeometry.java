package org.zeronura.nulzs.nodes.geometry;

/** A point cloud: points with attributes ({@code position}, {@code radius}) and nothing joining them. */
public final class PointGeometry extends GeometryComponent {

    private final int count;

    public PointGeometry(int count, Attributes attributes) {
        super(attributes);
        this.count = count;
    }

    public static PointGeometry of(float[] positions) {
        Attributes attributes = new Attributes();
        attributes.put("position", Domain.POINT, Column.ofFloats(DataType.VECTOR, positions));
        return new PointGeometry(positions.length / 3, attributes);
    }

    public int count() {
        return count;
    }

    public float[] positions() {
        return attributes.column("position").f;
    }

    @Override
    public int domainSize(Domain domain) {
        return domain == Domain.POINT ? count : 0;
    }

    @Override
    public Domain defaultDomain() {
        return Domain.POINT;
    }

    @Override
    public boolean hasDomain(Domain domain) {
        return domain == Domain.POINT;
    }

    @Override
    public PointGeometry withAttributes(Attributes attributes) {
        return new PointGeometry(count, attributes);
    }
}
