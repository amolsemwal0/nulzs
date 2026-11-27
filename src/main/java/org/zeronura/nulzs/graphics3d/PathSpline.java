package org.zeronura.nulzs.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.physics.AABB;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.scene.Asset;
import org.zeronura.nulzs.scene.Component;
import org.zeronura.nulzs.scene.Range;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.scene.StoresData;
import org.zeronura.nulzs.scene.Tooltip;
import org.zeronura.nulzs.worldmap.MapSpline;
import org.zeronura.nulzs.worldmap.SplineMaterials;
import org.zeronura.nulzs.worldmap.SplineMeshes;
import org.zeronura.nulzs.worldmap.WorldMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A path laid along a line of points: a road, a dirt path, a raised footpath, a wall or a railing, built by the same
 * code as the map editor's splines — curving through its points or turning sharp at them, its picture laid across its
 * width and repeating along it. The editor's Path Tool draws one by clicking points on the ground; the points can then
 * be dragged, and everything else is set in the Inspector.
 *
 * <p>The points lie flat in the entity's own space, on its ground (y = 0), so moving, turning or raising the entity
 * moves the whole path. Walls, railings and footpaths are solid to characters.
 */
public class PathSpline extends Component implements StoresData, Collides3D {

    /** What the path is laid as. */
    public enum Kind {
        ROAD(MapSpline.Kind.ROAD, Color.rgb(0x4A4D53)),
        PATH(MapSpline.Kind.PATH, Color.rgb(0x9A7A56)),
        FOOTPATH(MapSpline.Kind.FOOTPATH, Color.rgb(0xB8BABD)),
        WALL(MapSpline.Kind.WALL, Color.rgb(0xA8775E)),
        RAILING(MapSpline.Kind.RAILING, Color.rgb(0x86684B));

        final MapSpline.Kind map;
        final Color color;

        Kind(MapSpline.Kind map, Color color) {
            this.map = map;
            this.color = color;
        }

        @Override
        public String toString() {
            return map.label;
        }
    }

    /** What runs along a road's or path's outer edges. */
    public enum Edge {
        RAILING, WALL
    }

    public Kind kind = Kind.PATH;
    @Range(min = 0.05, max = 60)
    @Tooltip("Width in metres; a wall's is how thick it is")
    public float width = MapSpline.Kind.PATH.width;
    @Range(min = 0, max = 12)
    @Tooltip("How tall a wall or railing stands, or how high a footpath is raised")
    public float height = MapSpline.Kind.PATH.height;
    @Tooltip("Curve smoothly through the points; off turns sharply at every one")
    public boolean curved = true;
    @Tooltip("Join the last point back to the first")
    public boolean closed;
    @Asset({"png", "jpg", "jpeg", "tga", "bmp"})
    @Tooltip("The picture laid along it, across its whole width; none for a plain colour")
    public String texture = "";
    @Tooltip("The colour it is when it has no picture")
    public Color color = Kind.PATH.color;
    @Range(min = 0, max = 30)
    @Tooltip("How long one repeat of the picture is, in metres; 0 fits it to the width")
    public float textureSize = 0f;
    @Tooltip("The picture runs top to bottom, so turn it to run along the path")
    public boolean turned;
    @Tooltip("Footpaths along a road's or path's sides")
    public MapSpline.Sides footpaths = MapSpline.Sides.NONE;
    @Range(min = 0.3, max = 8)
    public float footpathWidth = MapSpline.Kind.FOOTPATH.width;
    @Tooltip("Railings or walls along a road's or path's outer sides")
    public MapSpline.Sides edges = MapSpline.Sides.NONE;
    public Edge edgeKind = Edge.RAILING;
    @Range(min = 0.2, max = 6)
    public float edgeHeight = MapSpline.Kind.RAILING.height;
    @Tooltip("Characters bump into its walls, railings and footpaths")
    public boolean solid = true;
    public boolean castShadows = true;

    private final List<MapSpline.Point> points = new ArrayList<>();
    private final LaidParts parts = new LaidParts();
    private final List<AABB> localColliders = new ArrayList<>();
    private final Matrix4f world = new Matrix4f();
    private String built;
    private Kind settled = Kind.PATH;
    /** A flat ground for the spline code to lay the path on: one cell, every height 0, so every point reads 0. */
    private static final WorldMap FLAT = new WorldMap("path", 1, 1, 1f);

    // --- Points -------------------------------------------------------------------------------------------------

    /** The points, in the entity's own space (x and z; y is 0). */
    public List<MapSpline.Point> points() {
        return points;
    }

    public MapSpline.Point addPoint(float x, float z, boolean sharp) {
        MapSpline.Point point = new MapSpline.Point(x, z, sharp);
        points.add(point);
        return point;
    }

    public MapSpline.Point insertPoint(int index, float x, float z, boolean sharp) {
        MapSpline.Point point = new MapSpline.Point(x, z, sharp);
        points.add(Math.clamp(index, 0, points.size()), point);
        return point;
    }

    @Override
    public Object saveData() {
        List<Object> out = new ArrayList<>();
        for (MapSpline.Point point : points) {
            out.add(List.of(round(point.x), round(point.z), point.sharp));
        }
        return out;
    }

    private static double round(float value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    @Override
    public void loadData(Object data) {
        points.clear();
        if (data instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof List<?> p && p.size() >= 2 && p.get(0) instanceof Number x && p.get(1) instanceof Number z) {
                    boolean sharp = p.size() > 2 && Boolean.TRUE.equals(p.get(2));
                    addPoint(x.floatValue(), z.floatValue(), sharp);
                }
            }
        }
    }

    // --- Building -----------------------------------------------------------------------------------------------

    @Override
    protected void onEnterScene(Scene scene) {
        settled = kind;
        parts.attach(scene);
    }

    @Override
    protected void onExitScene(Scene scene) {
        parts.detach();
        built = null;
    }

    @Override
    protected void preRender(float alpha) {
        followKind();
        parts.attach(scene());
        parts.castShadows = castShadows;
        transform().worldMatrix(world);
        parts.place(world);
        String key = key();
        if (!key.equals(built)) {
            rebuild();
            built = key;
        }
    }

    /** A kind newly chosen starts at that kind's size and colour, as one drawn in the map editor does. */
    private void followKind() {
        if (kind == settled) {
            return;
        }
        if (color == null || color.equals(settled.color)) {
            color = kind.color;
        }
        width = kind.map.width;
        height = kind.map.height;
        settled = kind;
    }

    private String key() {
        StringBuilder key = new StringBuilder(String.format(Locale.ROOT, "%s %s %s %s %s %s %s %s %s %s %s %s %s %s|",
                kind, width, height, curved, closed, texture, color, textureSize, turned, footpaths, footpathWidth, edges,
                edgeKind, edgeHeight));
        for (MapSpline.Point point : points) {
            key.append(point.x).append(',').append(point.z).append(point.sharp ? 's' : 'c').append(';');
        }
        return key.toString();
    }

    /** The path as the map's spline code knows it. */
    public MapSpline spline() {
        MapSpline spline = new MapSpline(kind.map);
        spline.width = Math.clamp(width, MapSpline.MIN_WIDTH, MapSpline.MAX_WIDTH);
        spline.height = height;
        spline.closed = closed;
        spline.texture = texture == null || texture.isBlank() ? null : texture.trim();
        spline.textureSize = textureSize;
        spline.turned = turned;
        spline.footpaths = footpaths;
        spline.footpathWidth = footpathWidth;
        spline.edges = edges;
        spline.edgeKind = edgeKind == Edge.WALL ? MapSpline.Kind.WALL : MapSpline.Kind.RAILING;
        spline.edgeHeight = edgeHeight;
        for (MapSpline.Point point : points) {
            spline.addPoint(point.x, point.z, point.sharp || !curved);
        }
        return spline;
    }

    private void rebuild() {
        parts.clear();
        localColliders.clear();
        Scene scene = scene();
        if (scene == null || scene.engine() == null || points.size() < 2) {
            return;
        }
        SplineMaterials looks = SplineLooks.of(scene.assets());
        MapSpline spline = spline();
        try {
            SplineMeshes.Built laid = SplineMeshes.build(FLAT, spline, SplineMeshes.LIFT, k -> SplineMeshes.PLAIN);
            for (var entry : laid.meshes().entrySet()) {
                parts.add(entry.getValue(), material(looks, entry.getKey()));
            }
            localColliders.addAll(laid.colliders());
        } catch (RuntimeException e) {
            System.err.println("[PathSpline] Could not lay " + entity().name() + ": " + e);
        }
    }

    private FoliageMaterial material(SplineMaterials looks, String texture) {
        if (texture == null || texture.isEmpty()) {
            return SplineLooks.plain(color);
        }
        FoliageMaterial material = looks.material(texture);
        return material != null ? material : SplineLooks.plain(color);
    }

    // --- Colliding ----------------------------------------------------------------------------------------------

    @Override
    public void addColliders(List<AABB> into) {
        if (!solid || localColliders.isEmpty() || entity() == null || !entity().isActive()) {
            return;
        }
        Matrix4f matrix = transform().worldMatrix(new Matrix4f());
        Vector3f corner = new Vector3f();
        for (AABB box : localColliders) {
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                matrix.transformPosition(corner.set((i & 1) == 0 ? box.minX() : box.maxX(), (i & 2) == 0 ? box.minY() : box.maxY(),
                        (i & 4) == 0 ? box.minZ() : box.maxZ()));
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
                maxZ = Math.max(maxZ, corner.z);
            }
            into.add(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
        }
    }

    /** The box round the path's points, in its own space, for picking and framing it. */
    public boolean bounds(Vector3f min, Vector3f max) {
        if (points.isEmpty()) {
            return false;
        }
        float half = Math.max(width, footpaths != MapSpline.Sides.NONE ? width + 2f * footpathWidth : width) * 0.5f;
        min.set(Float.MAX_VALUE, 0f, Float.MAX_VALUE);
        max.set(-Float.MAX_VALUE, Math.max(0.05f, height), -Float.MAX_VALUE);
        for (MapSpline.Point point : points) {
            min.x = Math.min(min.x, point.x - half);
            min.z = Math.min(min.z, point.z - half);
            max.x = Math.max(max.x, point.x + half);
            max.z = Math.max(max.z, point.z + half);
        }
        min.y = -0.05f;
        return true;
    }
}
