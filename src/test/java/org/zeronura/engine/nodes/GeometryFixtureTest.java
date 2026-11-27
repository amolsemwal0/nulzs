package org.zeronura.engine.nodes;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.zeronura.engine.nodes.geometry.Column;
import org.zeronura.engine.nodes.geometry.GeometryComponent;
import org.zeronura.engine.nodes.geometry.GeometrySet;
import org.zeronura.engine.nodes.geometry.MeshGeometry;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole geometry trees run by the engine against Blender's own evaluation of them.
 *
 * <p>{@code tools/blender_nodes/make_fixtures.py} builds each tree in Blender, and the exporter writes it with its
 * bake (what Blender computed). Each object's geometry, evaluated here, must have the same elements in the same
 * places, and the same stored attribute values.
 */
class GeometryFixtureTest {

    private static final float TOLERANCE = 1e-3f;

    /** Trees whose element order legitimately differs from Blender's: compared as sets of positions. */
    private static final List<String> UNORDERED = List.of();

    private static NodePackage fixtures;

    @BeforeAll
    static void load() throws URISyntaxException {
        Path file = Path.of(GeometryFixtureTest.class.getResource("/nodes/fixtures/fixtures.nodes.json").toURI());
        fixtures = NodePackage.load(file);
    }

    private static float[] positions(GeometrySet set) {
        List<float[]> parts = new ArrayList<>();
        for (GeometryComponent component : set.components()) {
            Column p = component.attributes().column("position");
            if (p != null) {
                parts.add(p.f);
            }
        }
        int total = parts.stream().mapToInt(a -> a.length).sum();
        float[] all = new float[total];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        return all;
    }

    private static float maxDiff(float[] a, float[] b) {
        float worst = 0f;
        for (int k = 0; k < Math.min(a.length, b.length); k++) {
            worst = Math.max(worst, Math.abs(a[k] - b[k]));
        }
        return a.length == b.length ? worst : Float.POSITIVE_INFINITY;
    }

    private static float[] sorted(float[] p) {
        List<float[]> points = new ArrayList<>();
        for (int k = 0; k < p.length; k += 3) {
            points.add(new float[]{p[k], p[k + 1], p[k + 2]});
        }
        points.sort(Comparator.<float[]>comparingInt(v -> Math.round(v[0] * 100)).thenComparingInt(v -> Math.round(v[1] * 100))
                .thenComparingInt(v -> Math.round(v[2] * 100)));
        float[] out = new float[p.length];
        for (int k = 0; k < points.size(); k++) {
            System.arraycopy(points.get(k), 0, out, 3 * k, 3);
        }
        return out;
    }

    private static String compare(String name) {
        GeometrySet engine = NodePackage.realize(fixtures.evaluateStrict(name, Map.of(), null));
        GeometrySet blender = NodePackage.realize(fixtures.bake(name));
        float[] ep = positions(engine);
        float[] bp = positions(blender);
        StringBuilder report = new StringBuilder();
        if (ep.length != bp.length) {
            return name + ": " + ep.length / 3 + " points, Blender has " + bp.length / 3;
        }
        if (engine.mesh != null && blender.mesh != null && engine.mesh.faceCount() != blender.mesh.faceCount()) {
            report.append(name).append(": ").append(engine.mesh.faceCount()).append(" faces, Blender has ")
                    .append(blender.mesh.faceCount()).append("; ");
        }
        float ordered = maxDiff(ep, bp);
        if (ordered > TOLERANCE) {
            float unordered = maxDiff(sorted(ep), sorted(bp));
            if (unordered > TOLERANCE || !UNORDERED.contains(name)) {
                report.append(name).append(": positions off by ").append(ordered).append(" in order, ")
                        .append(unordered).append(" as a set; ");
            }
        }
        if (engine.mesh != null && blender.mesh != null) {
            for (String attribute : new String[]{"col", "ramp", "factor", "UVMap"}) {
                Column e = engine.mesh.attributes().column(attribute);
                Column b = blender.mesh.attributes().column(attribute);
                if (b != null && e == null) {
                    report.append(name).append(": attribute ").append(attribute).append(" missing; ");
                } else if (b != null && e.f != null && b.f != null) {
                    float d = maxDiff(e.f, b.f);
                    if (d > 2e-3f) {
                        report.append(name).append(": attribute ").append(attribute).append(" off by ").append(d).append("; ");
                    }
                }
            }
            if (engine.mesh.faceCount() == blender.mesh.faceCount() && ordered <= TOLERANCE) {
                float[] en = engine.mesh.withAttributes(withoutCornerNormals(engine.mesh)).cornerNormals();
                Column bn = blender.mesh.attributes().column(".corner_normals");
                if (bn != null && en.length == bn.f.length) {
                    float d = maxDiff(en, bn.f);
                    if (d > 2e-3f) {
                        report.append(name).append(": shading normals off by ").append(d).append("; ");
                    }
                }
            }
        }
        return report.toString();
    }

    private static org.zeronura.engine.nodes.geometry.Attributes withoutCornerNormals(MeshGeometry mesh) {
        var attributes = mesh.attributes().copy();
        attributes.remove(".corner_normals");
        return attributes;
    }

    @Test
    void everyTreeMatchesBlender() {
        List<String> failures = new ArrayList<>();
        for (String name : fixtures.roots()) {
            String result;
            try {
                result = compare(name);
            } catch (RuntimeException error) {
                result = name + ": " + error;
                error.printStackTrace();
            }
            if (!result.isEmpty()) {
                failures.add(result);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " of " + fixtures.roots().size() + " trees differ from Blender:\n"
                + String.join("\n", failures));
    }

    @Test
    void everyFixtureNodeIsSupported() {
        assertTrue(fixtures.unsupportedNodes().isEmpty(), "unsupported: " + fixtures.unsupportedNodes());
    }

    static String describe(float[] p, int count) {
        return Arrays.toString(Arrays.copyOf(p, Math.min(p.length, count * 3)));
    }
}
