package org.zeronura.engine.nodes.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Instances: placements of other geometry, each a reference and a transform, as Instance on Points makes them.
 *
 * <p>The transforms are the {@code instance_transform} attribute (4x4, column-major) and the reference of each
 * instance the {@code .reference_index} attribute into {@link #references}. Reading {@code position} gives each
 * instance's translation, as in Blender.
 */
public final class InstanceGeometry extends GeometryComponent {

    public static final String TRANSFORM = "instance_transform";
    public static final String REFERENCE = ".reference_index";

    public final List<GeometrySet> references;
    private final int count;

    public InstanceGeometry(int count, List<GeometrySet> references, Attributes attributes) {
        super(attributes);
        this.count = count;
        this.references = List.copyOf(references);
    }

    /** Instances of {@code references.get(handles[i])} at {@code transforms[16 i..]}. */
    public static InstanceGeometry of(List<GeometrySet> references, int[] handles, float[] transforms) {
        Attributes attributes = new Attributes();
        attributes.put(REFERENCE, Domain.INSTANCE, Column.ofInts(DataType.INT, handles));
        attributes.put(TRANSFORM, Domain.INSTANCE, Column.ofFloats(DataType.MATRIX, transforms));
        return new InstanceGeometry(handles.length, references, attributes);
    }

    public int count() {
        return count;
    }

    public float[] transforms() {
        return attributes.column(TRANSFORM).f;
    }

    public int[] handles() {
        return attributes.column(REFERENCE).i;
    }

    public GeometrySet reference(int instance) {
        return references.get(handles()[instance]);
    }

    public InstanceGeometry withTransforms(float[] transforms) {
        Attributes out = attributes.copy();
        out.put(TRANSFORM, Domain.INSTANCE, Column.ofFloats(DataType.MATRIX, transforms));
        return new InstanceGeometry(count, references, out);
    }

    @Override
    public int domainSize(Domain domain) {
        return domain == Domain.INSTANCE ? count : 0;
    }

    @Override
    public Domain defaultDomain() {
        return Domain.INSTANCE;
    }

    @Override
    public boolean hasDomain(Domain domain) {
        return domain == Domain.INSTANCE;
    }

    @Override
    public InstanceGeometry withAttributes(Attributes attributes) {
        return new InstanceGeometry(count, references, attributes);
    }

    @Override
    public Column read(String name, Domain domain, DataType type) {
        if ("position".equals(name)) {
            float[] t = transforms();
            float[] out = new float[count * 3];
            for (int i = 0; i < count; i++) {
                out[3 * i] = t[16 * i + 12];
                out[3 * i + 1] = t[16 * i + 13];
                out[3 * i + 2] = t[16 * i + 14];
            }
            return Column.ofFloats(DataType.VECTOR, out).convert(type);
        }
        return super.read(name, domain, type);
    }

    /** The references that are actually used, renumbered, so joined instances do not drag dead geometry along. */
    public InstanceGeometry compact() {
        int[] handles = handles();
        int[] remap = new int[references.size()];
        java.util.Arrays.fill(remap, -1);
        List<GeometrySet> used = new ArrayList<>();
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int h = handles[i];
            if (remap[h] < 0) {
                remap[h] = used.size();
                used.add(references.get(h));
            }
            out[i] = remap[h];
        }
        Attributes attrs = attributes.copy();
        attrs.put(REFERENCE, Domain.INSTANCE, Column.ofInts(DataType.INT, out));
        return new InstanceGeometry(count, used, attrs);
    }
}
