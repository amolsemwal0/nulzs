package org.zeronura.engine.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.render.Camera;
import org.zeronura.engine.render.GroundMaterial;
import org.zeronura.engine.render.GroundTextures;
import org.zeronura.engine.render.Renderer;
import org.zeronura.engine.render.SceneLighting;
import org.zeronura.engine.render.ShadowMap;
import org.zeronura.engine.render.Texture;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.Range;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.scene.StoresData;
import org.zeronura.engine.scene.Tooltip;
import org.zeronura.engine.worldmap.Brushes;
import org.zeronura.engine.worldmap.MapMaterial;
import org.zeronura.engine.worldmap.MapTerrain;
import org.zeronura.engine.worldmap.PrefabCatalog;
import org.zeronura.engine.worldmap.WorldMap;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Ground to sculpt and paint: a square heightfield centred on its entity, drawn in chunks like a hand-made map's
 * terrain ({@link MapTerrain}, which it is built on), lit and shadowed, and walked on — characters stand on it and
 * can't go into it ({@link Ground3D}). The editor's Terrain Tool raises, lowers, smooths, flattens and roughens it with
 * the same {@link Brushes} a map editor uses, and paints it with up to {@value #MAX_LAYERS} ground layers, each a
 * colour or a tiling picture laid over the ground from above.
 *
 * <p>Its heights and paint are kept in the scene with it. The entity's position places it; turning or scaling the
 * entity doesn't turn or scale it.
 */
public class Terrain3D extends Component implements Ground3D, StoresData, EditableGround {

    public static final int MAX_LAYERS = 8;

    /** How many squares across it is: finer hills, or a lighter scene. */
    public enum Detail {
        COARSE(32, "32 x 32"), MEDIUM(64, "64 x 64"), FINE(128, "128 x 128"), FINEST(256, "256 x 256");

        public final int cells;
        public final String label;

        Detail(int cells, String label) {
            this.cells = cells;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A ground that can be painted on: a colour, and a picture laid over it if it has one. */
    public static final class Layer {
        public String name;
        public Color color;
        /** A picture by asset path, tiled over the ground from above; empty for the colour alone. */
        public String texture = "";
        /** How many metres one picture covers. */
        public float tileSize = 4f;

        public Layer(String name, Color color) {
            this.name = name;
            this.color = color;
        }

        Layer copy() {
            Layer copy = new Layer(name, color);
            copy.texture = texture;
            copy.tileSize = tileSize;
            return copy;
        }
    }

    @Range(min = 4, max = 4000)
    @Tooltip("Width and depth in metres; it is centred on its entity")
    public float size = 100f;
    @Tooltip("How many squares across: more for finer hills, fewer for a lighter scene")
    public Detail detail = Detail.FINE;
    public boolean castShadows = true;
    @Tooltip("Whether characters stand on it")
    public boolean solid = true;

    private WorldMap map;
    private final List<Layer> layers = new ArrayList<>(defaultLayers());
    /** Goes up with every change to the layers, so their colours and pictures are made again. */
    private int layersRevision;

    // Drawing, built on the GL thread when first drawn.
    private MapTerrain terrain;
    private int builtLayers = -1;
    private GroundTextures textures = GroundTextures.NONE;
    private World3D passWorld;
    private final Matrix4f placed = new Matrix4f();
    private final Vector3f origin = new Vector3f();
    private final World3D.RenderPass pass = new World3D.RenderPass() {
        @Override
        public void castShadows(ShadowMap shadowMap) {
            if (terrain != null && castShadows && drawn()) {
                shadowMap.drawStatic(terrain.meshes(), placed);
            }
        }

        @Override
        public void renderOpaque(Renderer renderer, Camera camera, SceneLighting lighting) {
            if (terrain != null && drawn()) {
                renderer.renderTerrain(camera, lighting, terrain.meshes(), placed, textures);
            }
        }

        @Override
        public void renderTransparent(Renderer renderer, Camera camera, SceneLighting lighting) {
        }
    };

    public static List<Layer> defaultLayers() {
        return List.of(new Layer("Grass", Color.rgb(0x6E9A4C)), new Layer("Dirt", Color.rgb(0x8C6A48)),
                new Layer("Rock", Color.rgb(0x8A8A86)), new Layer("Sand", Color.rgb(0xD8C690)));
    }

    private boolean drawn() {
        return isEnabled() && entity() != null && entity().isActive();
    }

    // --- The ground --------------------------------------------------------------------------------------------------

    /** The heightfield, made (or remade, for a new size or detail) as the settings say. Its x and z run from 0. */
    @Override
    public WorldMap map() {
        int cells = (detail == null ? Detail.FINE : detail).cells;
        float cellSize = Math.max(4f, size) / cells;
        if (map == null) {
            map = new WorldMap("Terrain", cells, cells, cellSize);
            map.setWater(false);
        } else if (map.width() != cells || Math.abs(map.cellSize() - cellSize) > 1e-5f) {
            map = resampled(map, cells, cellSize);
            releaseTerrain();
        }
        return map;
    }

    /** The map at a new detail or size: its shape kept, stretched to the new size, its paint kept where it lay. */
    private static WorldMap resampled(WorldMap old, int cells, float cellSize) {
        WorldMap made = new WorldMap("Terrain", cells, cells, cellSize);
        made.setWater(false);
        float scale = old.worldWidth() / (cells * cellSize);
        for (int j = 0; j <= cells; j++) {
            for (int i = 0; i <= cells; i++) {
                float x = i * cellSize * scale;
                float z = j * cellSize * scale;
                made.setVertexHeight(i, j, old.heightAt(x, z));
                int oi = Math.round(x / old.cellSize());
                int oj = Math.round(z / old.cellSize());
                made.setVertexMaterial(i, j, old.vertexMaterial(oi, oj));
            }
        }
        return made;
    }

    /** The corner of the map (its x = 0, z = 0) in the world: the entity's position less half the size. */
    @Override
    public Vector3f origin(Vector3f dest) {
        WorldMap m = map();
        Vector3f at = entity() == null ? new Vector3f() : entity().transform().worldPosition(new Vector3f());
        return dest.set(at.x - m.worldWidth() * 0.5f, at.y, at.z - m.worldDepth() * 0.5f);
    }

    /** A world point in the map's own space, where its x and z run from 0. */
    public Vector3f toMap(Vector3f world, Vector3f dest) {
        return dest.set(world).sub(origin(new Vector3f()));
    }

    /** Asks for the ground a brush touched to be built again. */
    @Override
    public void changed(Brushes.Area area) {
        if (terrain != null) {
            terrain.markDirty(area);
        }
    }

    /** Asks for all of it to be built again: after an undo, or a fill. */
    @Override
    public void changedAll() {
        if (terrain != null) {
            terrain.markAllDirty();
        }
    }

    @Override
    public float groundHeight(float x, float z) {
        if (!solid || !drawn()) {
            return Float.NEGATIVE_INFINITY;
        }
        WorldMap m = map();
        origin(origin);
        float lx = x - origin.x;
        float lz = z - origin.z;
        if (lx < 0f || lz < 0f || lx > m.worldWidth() || lz > m.worldDepth()) {
            return Float.NEGATIVE_INFINITY;
        }
        return m.heightAt(lx, lz) + origin.y;
    }

    // --- Layers -------------------------------------------------------------------------------------------------------

    /** The ground layers, in paint order; a vertex's paint is an index into these. Changed through {@link #layersChanged}. */
    public List<Layer> layers() {
        return layers;
    }

    @Override
    public List<Paint> paints() {
        List<Paint> paints = new ArrayList<>();
        for (Layer layer : layers) {
            paints.add(new Paint(layer.name, layer.color == null ? Color.WHITE : layer.color));
        }
        return paints;
    }

    /** Says the layers were changed (added, recoloured, given a picture), so they are made again. */
    public void layersChanged() {
        layersRevision++;
    }

    // --- Drawing ------------------------------------------------------------------------------------------------------

    @Override
    protected void preRender(float alpha) {
        if (scene() == null || scene().engine() == null || !scene().has3D()) {
            return;
        }
        WorldMap m = map();
        if (layers.isEmpty()) {
            layers.add(new Layer("Ground", Color.rgb(0x7D9070)));
        }
        if (terrain == null || builtLayers != layersRevision) {
            buildTerrain(m);
        }
        if (passWorld == null) {
            passWorld = scene().world3D();
            passWorld.addPass(pass);
        }
        origin(origin);
        placed.translation(origin);
        Camera camera = passWorld.camera();
        Vector3f eye = camera == null ? new Vector3f(m.worldWidth() * 0.5f, 0f, m.worldDepth() * 0.5f)
                : new Vector3f(camera.getPosition()).sub(origin);
        terrain.update(eye);
    }

    private void buildTerrain(WorldMap m) {
        releaseTerrain();
        builtLayers = layersRevision;
        List<MapMaterial> palette = new ArrayList<>();
        for (Layer layer : layers) {
            palette.add(new MapMaterial(layer.name, layer.color == null ? Color.WHITE : layer.color));
        }
        terrain = new MapTerrain(m, palette, new PrefabCatalog());
        terrain.setViewDistance(Math.max(600f, m.worldWidth() * 1.5f));
        textures = groundTextures();
        if (textures.isPresent()) {
            int[] slots = new int[layers.size()];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = i;
            }
            terrain.setGroundLayers(slots, -1);
        }
    }

    /**
     * The layers' pictures as one array texture, each brought to one size; a layer with none gets plain grey that
     * leaves its colour as it is. None at all if no layer has a picture.
     */
    private GroundTextures groundTextures() {
        boolean any = false;
        for (Layer layer : layers) {
            any |= layer.texture != null && !layer.texture.isBlank();
        }
        if (!any) {
            return GroundTextures.NONE;
        }
        int side = 512;
        List<ByteBuffer> pixels = new ArrayList<>();
        List<GroundMaterial> materials = new ArrayList<>();
        try {
            for (Layer layer : layers.subList(0, Math.min(layers.size(), MAX_LAYERS))) {
                ByteBuffer picture = layer.texture == null || layer.texture.isBlank() ? null
                        : load(scene().assets().resolve(layer.texture).toString(), side);
                if (picture == null) {
                    ByteBuffer grey = MemoryUtil.memAlloc(side * side * 4);
                    for (int i = 0; i < side * side; i++) {
                        grey.put((byte) 128).put((byte) 128).put((byte) 128).put((byte) 255);
                    }
                    grey.flip();
                    pixels.add(grey);
                    materials.add(new GroundMaterial(new Vector3f(128f / 255f), 4f, 0f, 0f, 0f));
                } else {
                    pixels.add(picture);
                    materials.add(new GroundMaterial(mean(picture), Math.max(0.1f, layer.tileSize), 0f, 1f, 1f));
                }
            }
            Texture albedo = Texture.arrayFromPixels(pixels, side, side);
            return new GroundTextures(albedo, null, materials, 120f, 400f);
        } catch (RuntimeException e) {
            System.err.println("[Terrain3D] The ground pictures couldn't be made: " + e.getMessage());
            return GroundTextures.NONE;
        } finally {
            for (ByteBuffer buffer : pixels) {
                MemoryUtil.memFree(buffer);
            }
        }
    }

    /** A picture file, brought to {@code side} by {@code side} pixels; null if it won't load. */
    private static ByteBuffer load(String file, int side) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer data = STBImage.stbi_load(file, w, h, channels, 4);
            if (data == null) {
                System.err.println("[Terrain3D] Couldn't read the ground picture " + file + ": " + STBImage.stbi_failure_reason());
                return null;
            }
            try {
                return resized(data, w.get(0), h.get(0), side);
            } finally {
                STBImage.stbi_image_free(data);
            }
        }
    }

    /** Bilinear, wrapping at the edges, as a tiling picture is seen. */
    static ByteBuffer resized(ByteBuffer source, int width, int height, int side) {
        ByteBuffer out = MemoryUtil.memAlloc(side * side * 4);
        for (int y = 0; y < side; y++) {
            float sy = (y + 0.5f) * height / side - 0.5f;
            int y0 = (int) Math.floor(sy);
            float fy = sy - y0;
            for (int x = 0; x < side; x++) {
                float sx = (x + 0.5f) * width / side - 0.5f;
                int x0 = (int) Math.floor(sx);
                float fx = sx - x0;
                for (int c = 0; c < 4; c++) {
                    float a = texel(source, width, height, x0, y0, c);
                    float b = texel(source, width, height, x0 + 1, y0, c);
                    float d = texel(source, width, height, x0, y0 + 1, c);
                    float e = texel(source, width, height, x0 + 1, y0 + 1, c);
                    float v = (a * (1 - fx) + b * fx) * (1 - fy) + (d * (1 - fx) + e * fx) * fy;
                    out.put((byte) Math.round(Math.clamp(v, 0f, 255f)));
                }
            }
        }
        out.flip();
        return out;
    }

    private static float texel(ByteBuffer data, int width, int height, int x, int y, int c) {
        int wx = Math.floorMod(x, width);
        int wy = Math.floorMod(y, height);
        return data.get((wy * width + wx) * 4 + c) & 0xFF;
    }

    /** A picture's average colour, as the shader measures its grain against. */
    static Vector3f mean(ByteBuffer rgba) {
        double r = 0;
        double g = 0;
        double b = 0;
        int count = rgba.remaining() / 4;
        for (int i = 0; i < count; i++) {
            r += rgba.get(i * 4) & 0xFF;
            g += rgba.get(i * 4 + 1) & 0xFF;
            b += rgba.get(i * 4 + 2) & 0xFF;
        }
        return new Vector3f((float) (r / count / 255.0), (float) (g / count / 255.0), (float) (b / count / 255.0));
    }

    /** A layer's picture's average colour, for its swatch: what the ground shows from far off. Null if it won't load. */
    public Color pictureColor(String texture) {
        if (texture == null || texture.isBlank() || scene() == null || scene().engine() == null) {
            return null;
        }
        ByteBuffer picture = load(scene().assets().resolve(texture).toString(), 64);
        if (picture == null) {
            return null;
        }
        try {
            Vector3f m = mean(picture);
            return new Color(m.x, m.y, m.z, 1f);
        } finally {
            MemoryUtil.memFree(picture);
        }
    }

    private void releaseTerrain() {
        if (terrain != null) {
            terrain.close();
            terrain = null;
        }
        if (textures.albedo() != null) {
            textures.albedo().delete();
        }
        textures = GroundTextures.NONE;
        builtLayers = -1;
    }

    @Override
    protected void onExitScene(Scene scene) {
        releaseTerrain();
        if (passWorld != null) {
            passWorld.removePass(pass);
            passWorld = null;
        }
    }

    /** The world box it covers, for the editor to outline and pick it by. */
    public void bounds(Vector3f min, Vector3f max) {
        WorldMap m = map();
        origin(origin);
        min.set(origin.x, origin.y + m.minHeight(), origin.z);
        max.set(origin.x + m.worldWidth(), origin.y + Math.max(m.maxHeight(), m.minHeight() + 0.05f), origin.z + m.worldDepth());
    }

    // --- Saving -------------------------------------------------------------------------------------------------------

    @Override
    public Object saveData() {
        WorldMap m = map();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cells", m.width());
        ByteBuffer heights = ByteBuffer.allocate(m.heights().length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float h : m.heights()) {
            heights.putFloat(h);
        }
        data.put("heights", pack(heights.array()));
        data.put("paint", pack(m.materials()));
        List<Object> saved = new ArrayList<>();
        for (Layer layer : layers) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("name", layer.name);
            l.put("color", layer.color == null ? "#FFFFFFFF" : org.zeronura.engine.scene.ComponentFields.colorToHex(layer.color));
            l.put("texture", layer.texture == null ? "" : layer.texture);
            l.put("tileSize", (double) layer.tileSize);
            saved.add(l);
        }
        data.put("layers", saved);
        return data;
    }

    @Override
    public void loadData(Object data) {
        if (!(data instanceof Map<?, ?> json)) {
            return;
        }
        int cells = json.get("cells") instanceof Number n ? n.intValue() : -1;
        if (json.get("layers") instanceof List<?> saved) {
            layers.clear();
            for (Object entry : saved) {
                if (entry instanceof Map<?, ?> l) {
                    Layer layer = new Layer(String.valueOf(l.get("name")), org.zeronura.engine.scene.ComponentFields.colorFromHex(String.valueOf(l.get("color"))));
                    layer.texture = l.get("texture") == null ? "" : String.valueOf(l.get("texture"));
                    layer.tileSize = l.get("tileSize") instanceof Number t ? t.floatValue() : 4f;
                    layers.add(layer);
                }
            }
            layersChanged();
        }
        if (cells <= 0) {
            return;
        }
        WorldMap made = new WorldMap("Terrain", cells, cells, Math.max(4f, size) / cells);
        made.setWater(false);
        byte[] heights = unpack(String.valueOf(json.get("heights")));
        ByteBuffer read = ByteBuffer.wrap(heights).order(ByteOrder.LITTLE_ENDIAN);
        float[] into = made.heights();
        for (int i = 0; i < into.length && read.remaining() >= 4; i++) {
            into[i] = read.getFloat();
        }
        byte[] paint = unpack(String.valueOf(json.get("paint")));
        System.arraycopy(paint, 0, made.materials(), 0, Math.min(paint.length, made.materials().length));
        map = made;
        releaseTerrain();
    }

    /** Deflated and in base 64: a flat terrain's heights come to almost nothing. */
    private static String pack(byte[] bytes) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(bytes);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 8));
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static byte[] unpack(String text) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(Base64.getDecoder().decode(text));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IllegalArgumentException | DataFormatException e) {
            return new byte[0];
        } finally {
            inflater.end();
        }
    }

    /** A copy of the layers, for an editor that changes them and keeps the old ones to put back. */
    public List<Layer> copyLayers() {
        List<Layer> copy = new ArrayList<>();
        for (Layer layer : layers) {
            copy.add(layer.copy());
        }
        return copy;
    }
}
