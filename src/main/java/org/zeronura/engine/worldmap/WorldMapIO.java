package org.zeronura.engine.worldmap;

import org.zeronura.engine.graphics2d.Color;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.BitSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saves and loads {@link WorldMap}s as compressed binary files.
 *
 * <p>Writes go to a temporary file that replaces the old map only once complete, so a crash mid-save never
 * leaves a half-written map behind.
 */
public final class WorldMapIO {

    private static final int MAGIC = 0x454D4150; // "EMAP"
    /**
     * 2 added the road network after the markers; version 1 files load with no roads. 3 marks each road a country
     * lane or not (version 2 roads all load as streets), and gives each object its tilt. 4 adds whether the map is
     * {@linkplain WorldMap#isTiled tiled}, at the very end; older maps are smooth. 5 adds the tiles'
     * {@linkplain WorldMap#tileOverlay overlays} after that; older maps have none. 6 adds the {@linkplain WorldMap#splines
     * splines} after those, each one's settings by name ({@link MapSpline#settings}) so later settings need no new
     * version. 7 gives each object the spline that laid it, after its tilt ({@link MapObject#owner}); older ones are
     * placed by hand. 8 gives each object whether splines clear it, after that ({@link MapObject#clearedBySpline}); in
     * older ones they all do. 9 adds the {@linkplain WorldMap#trees trees} drawn on it at the very end, each one's settings
     * by name ({@link MapTree#settings}); older maps have none.
     */
    private static final int VERSION = 9;

    private WorldMapIO() {
    }

    public static void save(WorldMap map, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(temp)), 1 << 16))) {
            write(map, out);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static WorldMap load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
            return read(in);
        }
    }

    private static void write(WorldMap map, DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeUTF(map.name());
        out.writeInt(map.width());
        out.writeInt(map.depth());
        out.writeFloat(map.cellSize());
        out.writeBoolean(map.hasWater());
        out.writeFloat(map.waterLevel());
        var spawn = map.spawn(new org.joml.Vector3f());
        out.writeFloat(spawn.x);
        out.writeFloat(spawn.z);
        out.writeFloat(map.spawnYaw());

        float[] heights = map.heights();
        ByteBuffer heightBytes = ByteBuffer.allocate(heights.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        heightBytes.asFloatBuffer().put(heights);
        out.write(heightBytes.array());
        out.write(map.materials());

        out.writeInt(map.objects().size());
        for (MapObject object : map.objects()) {
            out.writeUTF(object.prefab);
            out.writeFloat(object.x);
            out.writeFloat(object.z);
            out.writeFloat(object.offsetY);
            out.writeFloat(object.yaw);
            out.writeFloat(object.scale);
            out.writeFloat(object.pitch);
            out.writeFloat(object.roll);
            out.writeInt(object.owner);
            out.writeBoolean(object.clearedBySpline);
        }

        out.writeInt(map.zones().size());
        for (MapZone zone : map.zones()) {
            out.writeUTF(zone.name());
            out.writeUTF(zone.kind());
            writeColor(out, zone.color());
            long[] words = zone.cells().toLongArray();
            out.writeInt(words.length);
            for (long word : words) {
                out.writeLong(word);
            }
            out.writeInt(zone.properties().size());
            for (var entry : zone.properties().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
        }

        out.writeInt(map.markers().size());
        for (MapMarker marker : map.markers()) {
            out.writeUTF(marker.kind);
            out.writeUTF(marker.name);
            out.writeFloat(marker.x);
            out.writeFloat(marker.z);
            out.writeFloat(marker.yaw);
        }

        RoadNetwork roads = map.roads();
        out.writeBoolean(roads.sidewalks());
        out.writeInt(roads.nodes().size());
        for (RoadNetwork.Node node : roads.nodes()) {
            out.writeInt(node.id);
            out.writeFloat(node.x);
            out.writeFloat(node.z);
            out.writeBoolean(node.roundabout);
            out.writeBoolean(node.crosswalks);
            out.writeBoolean(node.capped);
        }
        out.writeInt(roads.edges().size());
        for (RoadNetwork.Edge edge : roads.edges()) {
            out.writeInt(edge.a());
            out.writeInt(edge.b());
            out.writeBoolean(edge.lane());
        }
        out.writeBoolean(map.isTiled());
        out.write(map.overlays());

        out.writeInt(map.splines().size());
        for (MapSpline spline : map.splines()) {
            var settings = spline.settings();
            out.writeInt(settings.size());
            for (var entry : settings.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
            out.writeInt(spline.points().size());
            for (MapSpline.Point point : spline.points()) {
                out.writeFloat(point.x);
                out.writeFloat(point.z);
                out.writeBoolean(point.sharp);
            }
        }

        out.writeInt(map.trees().size());
        for (MapTree tree : map.trees()) {
            var settings = tree.settings();
            out.writeInt(settings.size());
            for (var entry : settings.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
            out.writeInt(tree.points().size());
            for (MapTree.Point point : tree.points()) {
                out.writeFloat(point.x);
                out.writeFloat(point.y);
                out.writeFloat(point.z);
            }
        }
    }

    private static WorldMap read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not a map file");
        }
        int version = in.readInt();
        if (version < 1 || version > VERSION) {
            throw new IOException("unsupported map version " + version);
        }
        String name = in.readUTF();
        int width = in.readInt();
        int depth = in.readInt();
        float cellSize = in.readFloat();
        if (width < 1 || depth < 1 || width > 8192 || depth > 8192 || cellSize <= 0f) {
            throw new IOException("corrupt map header");
        }
        WorldMap map = new WorldMap(name, width, depth, cellSize);
        map.setWater(in.readBoolean());
        map.setWaterLevel(in.readFloat());
        float spawnX = in.readFloat();
        float spawnZ = in.readFloat();
        map.setSpawn(spawnX, spawnZ, in.readFloat());

        float[] heights = map.heights();
        byte[] heightBytes = new byte[heights.length * Float.BYTES];
        in.readFully(heightBytes);
        ByteBuffer.wrap(heightBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(heights);
        in.readFully(map.materials());

        int objectCount = in.readInt();
        for (int i = 0; i < objectCount; i++) {
            String prefab = in.readUTF();
            float x = in.readFloat();
            float z = in.readFloat();
            float offsetY = in.readFloat();
            MapObject object = new MapObject(prefab, x, z, in.readFloat(), in.readFloat());
            object.offsetY = offsetY;
            if (version >= 3) {
                object.pitch = in.readFloat();
                object.roll = in.readFloat();
            }
            if (version >= 7) {
                object.owner = in.readInt();
            }
            if (version >= 8) {
                object.clearedBySpline = in.readBoolean();
            }
            map.objects().add(object);
        }

        int zoneCount = in.readInt();
        for (int i = 0; i < zoneCount; i++) {
            String zoneName = in.readUTF();
            String kind = in.readUTF();
            Color color = readColor(in);
            long[] words = new long[in.readInt()];
            for (int w = 0; w < words.length; w++) {
                words[w] = in.readLong();
            }
            MapZone zone = new MapZone(zoneName, kind, color, width, depth, BitSet.valueOf(words));
            int propertyCount = in.readInt();
            for (int p = 0; p < propertyCount; p++) {
                zone.setProperty(in.readUTF(), in.readUTF());
            }
            map.zones().add(zone);
        }

        int markerCount = in.readInt();
        for (int i = 0; i < markerCount; i++) {
            map.markers().add(new MapMarker(in.readUTF(), in.readUTF(), in.readFloat(), in.readFloat(), in.readFloat()));
        }

        if (version >= 2) {
            RoadNetwork roads = map.roads();
            roads.setSidewalks(in.readBoolean());
            int nodeCount = in.readInt();
            for (int i = 0; i < nodeCount; i++) {
                RoadNetwork.Node node = roads.addNode(in.readInt(), in.readFloat(), in.readFloat());
                node.roundabout = in.readBoolean();
                node.crosswalks = in.readBoolean();
                node.capped = in.readBoolean();
            }
            int edgeCount = in.readInt();
            for (int i = 0; i < edgeCount; i++) {
                int a = in.readInt();
                int b = in.readInt();
                roads.connect(a, b, version >= 3 && in.readBoolean());
            }
        }
        if (version >= 4) {
            map.setTiled(in.readBoolean());
        }
        if (version >= 5) {
            in.readFully(map.overlays());
        }
        if (version >= 6) {
            int splineCount = in.readInt();
            for (int i = 0; i < splineCount; i++) {
                int settingCount = in.readInt();
                java.util.Map<String, String> settings = new java.util.HashMap<>();
                for (int k = 0; k < settingCount; k++) {
                    settings.put(in.readUTF(), in.readUTF());
                }
                MapSpline spline = MapSpline.fromSettings(settings);
                int pointCount = in.readInt();
                for (int k = 0; k < pointCount; k++) {
                    spline.addPoint(in.readFloat(), in.readFloat(), in.readBoolean());
                }
                map.splines().add(spline);
            }
            // Splines from before they had ids get theirs now.
            for (MapSpline spline : map.splines()) {
                if (spline.id == 0) {
                    spline.id = map.nextSplineId();
                }
            }
        }
        if (version >= 9) {
            int treeCount = in.readInt();
            for (int i = 0; i < treeCount; i++) {
                int settingCount = in.readInt();
                java.util.Map<String, String> settings = new java.util.HashMap<>();
                for (int k = 0; k < settingCount; k++) {
                    settings.put(in.readUTF(), in.readUTF());
                }
                MapTree tree = MapTree.fromSettings(settings);
                int pointCount = in.readInt();
                for (int k = 0; k < pointCount; k++) {
                    tree.addPoint(in.readFloat(), in.readFloat(), in.readFloat());
                }
                map.trees().add(tree);
            }
        }
        return map;
    }

    private static void writeColor(DataOutputStream out, Color color) throws IOException {
        out.writeFloat(color.r());
        out.writeFloat(color.g());
        out.writeFloat(color.b());
        out.writeFloat(color.a());
    }

    private static Color readColor(DataInputStream in) throws IOException {
        return new Color(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
    }
}
