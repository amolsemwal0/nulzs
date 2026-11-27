package org.zeronura.nulzs.nodes.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * What a geometry socket carries: at most one mesh, one set of curves, one point cloud and one set of
 * instances, each optional. Immutable; the {@code with...} methods return a changed copy.
 */
public final class GeometrySet {

    public static final GeometrySet EMPTY = new GeometrySet(null, null, null, null, "");

    public final MeshGeometry mesh;
    public final CurveGeometry curves;
    public final PointGeometry points;
    public final InstanceGeometry instances;
    public final String name;

    public GeometrySet(MeshGeometry mesh, CurveGeometry curves, PointGeometry points, InstanceGeometry instances,
                       String name) {
        this.mesh = mesh;
        this.curves = curves;
        this.points = points;
        this.instances = instances;
        this.name = name == null ? "" : name;
    }

    public static GeometrySet of(GeometryComponent component) {
        return EMPTY.with(component);
    }

    public GeometrySet with(GeometryComponent component) {
        return switch (component) {
            case MeshGeometry m -> new GeometrySet(m, curves, points, instances, name);
            case CurveGeometry c -> new GeometrySet(mesh, c, points, instances, name);
            case PointGeometry p -> new GeometrySet(mesh, curves, p, instances, name);
            case InstanceGeometry i -> new GeometrySet(mesh, curves, points, i, name);
            case null -> this;
        };
    }

    public GeometrySet withMesh(MeshGeometry m) {
        return new GeometrySet(m, curves, points, instances, name);
    }

    public GeometrySet withCurves(CurveGeometry c) {
        return new GeometrySet(mesh, c, points, instances, name);
    }

    public GeometrySet withPoints(PointGeometry p) {
        return new GeometrySet(mesh, curves, p, instances, name);
    }

    public GeometrySet withInstances(InstanceGeometry i) {
        return new GeometrySet(mesh, curves, points, i, name);
    }

    public GeometrySet withName(String newName) {
        return new GeometrySet(mesh, curves, points, instances, newName);
    }

    /** Every component present, in Blender's order: mesh, curves, points, instances. */
    public List<GeometryComponent> components() {
        List<GeometryComponent> out = new ArrayList<>(4);
        if (mesh != null) {
            out.add(mesh);
        }
        if (curves != null) {
            out.add(curves);
        }
        if (points != null) {
            out.add(points);
        }
        if (instances != null) {
            out.add(instances);
        }
        return out;
    }

    public boolean isEmpty() {
        return (mesh == null || mesh.vertCount() == 0) && (curves == null || curves.pointCount() == 0)
                && (points == null || points.count() == 0) && (instances == null || instances.count() == 0);
    }

    /** The same component kind replaced (or removed with null). */
    public GeometrySet replace(GeometryComponent old, GeometryComponent replacement) {
        return switch (old) {
            case MeshGeometry m -> withMesh((MeshGeometry) replacement);
            case CurveGeometry c -> withCurves((CurveGeometry) replacement);
            case PointGeometry p -> withPoints((PointGeometry) replacement);
            case InstanceGeometry i -> withInstances((InstanceGeometry) replacement);
        };
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("Geometry[");
        if (mesh != null) {
            out.append(" mesh v=").append(mesh.vertCount()).append(" f=").append(mesh.faceCount());
        }
        if (curves != null) {
            out.append(" curves n=").append(curves.curveCount()).append(" p=").append(curves.pointCount());
        }
        if (points != null) {
            out.append(" points ").append(points.count());
        }
        if (instances != null) {
            out.append(" instances ").append(instances.count());
        }
        return out.append(" ]").toString();
    }
}
