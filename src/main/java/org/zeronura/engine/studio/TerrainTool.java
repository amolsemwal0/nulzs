package org.zeronura.engine.studio;

import org.joml.Vector3f;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics3d.EditableGround;
import org.zeronura.engine.graphics3d.Shape3D;
import org.zeronura.engine.graphics3d.Terrain3D;
import org.zeronura.engine.graphics3d.WorldMap3D;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.terrain.Noise;
import org.zeronura.engine.ui.Rect;
import org.zeronura.engine.worldmap.Brushes;
import org.zeronura.engine.worldmap.TerrainRay;
import org.zeronura.engine.worldmap.WorldMap;

import java.util.ArrayList;
import java.util.List;

/**
 * The Terrain Tool: sculpts and paints a {@link Terrain3D} in the 3D Scene view, with the brushes a map editor has —
 * raise, lower, smooth, flatten, roughen — and paint in the terrain's ground layers; or a map a {@link WorldMap3D}
 * shows, painted in its game's grounds (its heights too, unless they are laid from its areas). Started with none
 * selected, it works on the scene's terrain or map, or makes a terrain. Drag over the ground to brush it; Shift turns
 * raising into lowering and back; [ and ] size the brush; Ctrl+click picks the height Flatten levels to. Each stroke is
 * one undo step.
 */
final class TerrainTool implements SceneTool {

    enum Brush {
        RAISE("Raise"), LOWER("Lower"), SMOOTH("Smooth"), FLATTEN("Flatten"), ROUGHEN("Roughen"), PAINT("Paint");

        final String label;

        Brush(String label) {
            this.label = label;
        }
    }

    private static final float PANEL_WIDTH = 236f;

    private final StudioScene studio;
    private boolean drawing;
    private String terrainId;
    private Brush brush = Brush.RAISE;
    private float radius = 6f;
    private float strength = 0.5f;
    private float flattenHeight;
    private int paintLayer;
    private boolean stroking;
    private final Noise noise = new Noise(7L);
    private Rect panel = new Rect(0, 0, 0, 0);

    TerrainTool(StudioScene studio) {
        this.studio = studio;
    }

    @Override
    public String name() {
        return "Terrain Tool";
    }

    @Override
    public String hint() {
        String what = switch (brush) {
            case RAISE -> "raise it  -  Shift lowers";
            case LOWER -> "lower it  -  Shift raises";
            case SMOOTH -> "smooth it";
            case FLATTEN -> "level it to " + Ui.format(flattenHeight, false) + " m  -  Ctrl+click picks the height";
            case ROUGHEN -> "roughen it";
            case PAINT -> "paint it";
        };
        return "Terrain Tool: drag over the ground to " + what + "  -  [ ] brush size  -  Esc to stop";
    }

    @Override
    public boolean drawing() {
        return drawing;
    }

    @Override
    public void start() {
        drawing = true;
        stroking = false;
        SceneDocument doc = studio.doc();
        if (doc == null) {
            return;
        }
        Entity chosen = null;
        Entity primary = doc.primary();
        if (primary != null && groundOf(primary) != null) {
            chosen = primary;
        }
        if (chosen == null) {
            for (Entity entity : doc.entitiesInOrder()) {
                if (entity.isActive() && groundOf(entity) != null) {
                    chosen = entity;
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = makeTerrain(doc);
        }
        terrainId = doc.idOf(chosen);
        doc.select(chosen);
        studio.selectionChanged();
        EditableGround ground = groundOf(chosen);
        if (ground != null && ground.map() != null && ground.heightsLocked() != null && brush != Brush.PAINT) {
            // Only paint is kept on this ground: the tool starts painting.
            brush = Brush.PAINT;
        }
    }

    /** The ground on an entity the tool can brush: an enabled terrain or map, or null. */
    static EditableGround groundOf(Entity entity) {
        for (Component component : entity.components()) {
            if (component instanceof EditableGround ground && component.isEnabled()) {
                return ground;
            }
        }
        return null;
    }

    /**
     * A new terrain at the middle of the world. The flat Ground plane a new scene starts with is switched off under it,
     * and the scene's flat ground collision too, so the ground can be dug below where that plane was.
     */
    private Entity makeTerrain(SceneDocument doc) {
        Entity entity = doc.createEntity("Terrain", null);
        entity.transform().position.set(0f, 0f, 0f);
        doc.addComponent(entity, new Terrain3D());
        StringBuilder also = new StringBuilder();
        for (Entity other : doc.entitiesInOrder()) {
            Shape3D shape = other.get(Shape3D.class);
            if (other != entity && other.isActiveSelf() && shape != null && shape.shape == Shape3D.Kind.PLANE
                    && other.name().equalsIgnoreCase("Ground")) {
                other.setActive(false);
                also.append("  -  switched off the Ground plane under it");
            }
        }
        if (doc.settings.groundCollision) {
            doc.settings.groundCollision = false;
            also.append("  -  turned off the scene's flat Ground Collision, the terrain is the ground now");
        }
        doc.changed();
        studio.status("Made a terrain " + Ui.format(100f, false) + " m across" + also, false);
        return entity;
    }

    @Override
    public void finish() {
        if (stroking) {
            endStroke();
        }
        drawing = false;
    }

    @Override
    public void back() {
    }

    private EditableGround terrain(SceneDocument doc) {
        if (terrainId == null) {
            return null;
        }
        Entity entity = doc.byId.get(terrainId);
        return entity == null || entity.isDestroyed() ? null : groundOf(entity);
    }

    @Override
    public boolean viewport(Ui ui, ViewContext view, Rect content, boolean free) {
        SceneDocument doc = studio.doc();
        if (!drawing || doc == null || view.flat) {
            return false;
        }
        EditableGround terrain = terrain(doc);
        if (terrain == null) {
            if (stroking) {
                stroking = false;
            }
            SceneTool.banner(ui, content, "The terrain has gone: Esc, then start the Terrain Tool again");
            return false;
        }
        WorldMap map = terrain.map();
        if (map == null) {
            stroking = false;
            String problem = terrain instanceof WorldMap3D shown ? shown.problem() : null;
            SceneTool.banner(ui, content, problem != null ? "The map couldn't load: " + problem : "The map is loading...");
            return false;
        }
        boolean overPanel = panel(ui, content, terrain, doc);
        keys(ui);

        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        view.ray(ui.mouseX(), ui.mouseY(), origin, direction);
        Vector3f corner = terrain.origin(new Vector3f());
        Vector3f localOrigin = new Vector3f(origin).sub(corner);
        Vector3f hit = new Vector3f();
        boolean onGround = !overPanel && TerrainRay.intersect(map, localOrigin, direction, 4000f, hit);
        if (onGround) {
            ring(ui, view, map, corner, hit, radius, Theme.ACCENT);
            ring(ui, view, map, corner, hit, radius * 0.5f, Theme.ACCENT.withAlpha(0.45f));
        }

        if (stroking) {
            if (!ui.input.isMouseDown(MouseButton.LEFT)) {
                endStroke();
            } else if (onGround) {
                apply(terrain, map, hit, ui);
            }
            return true;
        }
        if (!free || overPanel) {
            return overPanel;
        }
        if (onGround && ui.input.isMousePressed(MouseButton.LEFT)) {
            String locked = brush == Brush.PAINT ? null : terrain.heightsLocked();
            if (ui.input.ctrlDown() && brush == Brush.FLATTEN) {
                flattenHeight = map.heightAt(hit.x, hit.z);
                studio.status("Flatten levels to " + Ui.format(flattenHeight, false) + " m", false);
            } else if (locked != null) {
                studio.status(locked, true);
            } else {
                if (brush == Brush.FLATTEN && ui.input.altDown()) {
                    flattenHeight = map.heightAt(hit.x, hit.z);
                }
                stroking = true;
                terrain.beginStroke();
                apply(terrain, map, hit, ui);
            }
        }
        // While sculpting, a click is the tool's, on the ground or off it: it doesn't pick or deselect.
        return onGround || ui.input.isMousePressed(MouseButton.LEFT);
    }

    private void apply(EditableGround terrain, WorldMap map, Vector3f at, Ui ui) {
        float dt = Math.min(0.05f, (float) studio.engine().time().unscaledDelta());
        boolean flip = ui.input.shiftDown();
        float rate = strength * dt;
        Brushes.Area area = switch (brush) {
            case RAISE, LOWER -> {
                boolean up = (brush == Brush.RAISE) != flip;
                yield Brushes.raise(map, at.x, at.z, radius, (up ? 1f : -1f) * rate * Math.max(2f, radius * 0.8f));
            }
            case SMOOTH -> Brushes.smooth(map, at.x, at.z, radius, Math.min(1f, rate * 6f));
            case FLATTEN -> Brushes.flatten(map, at.x, at.z, radius, flattenHeight, Math.min(1f, rate * 6f));
            case ROUGHEN -> Brushes.roughen(map, at.x, at.z, radius, rate * Math.max(1f, radius * 0.3f), noise);
            case PAINT -> Brushes.paintMaterial(map, at.x, at.z, radius,
                    Math.clamp(paintLayer, 0, Math.max(0, terrain.paints().size() - 1)));
        };
        terrain.changed(area);
    }

    private void endStroke() {
        stroking = false;
        SceneDocument doc = studio.doc();
        if (doc != null) {
            EditableGround terrain = terrain(doc);
            if (terrain != null) {
                terrain.endStroke();
            }
            doc.changed();
        }
    }

    private void keys(Ui ui) {
        if (ui.typing()) {
            return;
        }
        if (ui.input.isKeyTyped(Key.LEFT_BRACKET)) {
            radius = Math.max(0.5f, radius / 1.2f);
        }
        if (ui.input.isKeyTyped(Key.RIGHT_BRACKET)) {
            radius = Math.min(80f, radius * 1.2f);
        }
    }

    /** The brush's edge laid on the ground. */
    private static void ring(Ui ui, ViewContext view, WorldMap map, Vector3f corner, Vector3f centre, float r, Color color) {
        Vector3f previous = null;
        Vector3f screen = new Vector3f();
        for (int k = 0; k <= 48; k++) {
            double angle = k / 48.0 * Math.PI * 2.0;
            float x = centre.x + (float) Math.cos(angle) * r;
            float z = centre.z + (float) Math.sin(angle) * r;
            float y = map.heightAt(Math.clamp(x, 0f, map.worldWidth()), Math.clamp(z, 0f, map.worldDepth())) + 0.05f;
            Vector3f world = new Vector3f(x + corner.x, y + corner.y, z + corner.z);
            if (view.project(world, screen)) {
                Vector3f now = new Vector3f(screen);
                if (previous != null) {
                    ui.gui.line(previous.x, previous.y, now.x, now.y, 2f, color);
                }
                previous = now;
            } else {
                previous = null;
            }
        }
    }

    // --- The brush panel ------------------------------------------------------------------------------------------

    /** The brushes and their settings, over the view's left side. Whether the pointer is on it. */
    private boolean panel(Ui ui, Rect content, EditableGround terrain, SceneDocument doc) {
        Terrain3D own = terrain instanceof Terrain3D t ? t : null;
        String locked = terrain.heightsLocked();
        List<String> note = locked == null ? List.of() : wrap(ui, locked, PANEL_WIDTH - 20f, Theme.SMALL_SCALE);
        int paintRows = own != null ? own.layers().size() : (terrain.paints().size() + 1) / 2;
        float paintHeight = own != null ? 30f + paintRows * 28f + 30f : 30f + paintRows * 24f + 6f;
        float rows = 168f + (brush == Brush.FLATTEN ? 30f : 0f) + (brush == Brush.PAINT ? paintHeight : 0f)
                + note.size() * 16f + (note.isEmpty() ? 0f : 6f);
        panel = new Rect(content.x() + 10f, content.y() + 44f, PANEL_WIDTH, rows);
        ui.gui.rounded(panel, 8f, new Color(0.11f, 0.12f, 0.14f, 0.94f));
        ui.gui.roundedOutline(panel, 8f, 1f, Theme.BORDER);
        float x = panel.x() + 10f;
        float y = panel.y() + 8f;
        float w = panel.width() - 20f;
        Icon.TERRAIN.draw(ui.gui, x, y + 2f, 16f, Theme.ACCENT);
        ui.text(own != null ? "Terrain" : "Map", x + 22f, y + 1f, Theme.TEXT_SCALE, Theme.TEXT_BRIGHT);
        y += 26f;
        Brush[] brushes = Brush.values();
        float each = (w - 8f) / 3f;
        for (int i = 0; i < brushes.length; i++) {
            Rect button = new Rect(x + (i % 3) * (each + 4f), y + (i / 3) * 28f, each, 24f);
            boolean off = locked != null && brushes[i] != Brush.PAINT;
            if (off) {
                ui.textCentered(brushes[i].label, button, Theme.TEXT_DISABLED);
                ui.tooltip("terrainBrushOff:" + brushes[i], button, locked);
            } else if (ui.flatButton("terrainBrush:" + brushes[i], brushes[i].label, button, brush == brushes[i])) {
                brush = brushes[i];
                studio.status(hint(), false);
            }
        }
        y += 60f;
        if (!note.isEmpty()) {
            for (String line : note) {
                ui.text(line, x, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                y += 16f;
            }
            y += 6f;
        }
        y = sliderRow(ui, "Size", "terrainSize", x, y, w, radius, 0.5, 80, v -> radius = (float) v);
        y = sliderRow(ui, "Strength", "terrainStrength", x, y, w, strength, 0.02, 1, v -> strength = (float) v);
        if (brush == Brush.FLATTEN) {
            ui.text("Height", x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            ui.number("terrainFlatten", new Rect(x + 70f, y, w - 70f, 22f), flattenHeight, 0.05, -1e5, 1e5, false,
                    v -> flattenHeight = (float) v);
            y += 30f;
        }
        if (brush == Brush.PAINT) {
            y = own != null ? layerRows(ui, own, doc, x, y, w) : paintRows(ui, terrain, x, y, w);
        }
        ui.text(ui.fit("Ctrl+Z undoes a stroke", w, Theme.SMALL_SCALE), x, panel.y() + panel.height() - 20f,
                Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        boolean over = ui.hover(panel);
        if (over && ui.input.isMousePressed(MouseButton.LEFT)) {
            studio.setFocus(StudioScene.Focus.VIEWPORT);
        }
        return over;
    }

    private static float sliderRow(Ui ui, String label, String id, float x, float y, float w, double value, double min,
                                   double max, java.util.function.DoubleConsumer set) {
        ui.text(label, x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        ui.slider(id, new Rect(x + 70f, y, w - 70f, 22f), value, min, max, false, set);
        return y + 28f;
    }

    /** A map's grounds to paint with, two to a row: its game's, so they can be picked but not changed here. */
    private float paintRows(Ui ui, EditableGround ground, float x, float y, float w) {
        List<EditableGround.Paint> paints = ground.paints();
        ui.text("Grounds", x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 26f;
        paintLayer = Math.clamp(paintLayer, 0, Math.max(0, paints.size() - 1));
        float each = (w - 4f) / 2f;
        for (int i = 0; i < paints.size(); i++) {
            EditableGround.Paint paint = paints.get(i);
            Rect cell = new Rect(x + (i % 2) * (each + 4f), y + (i / 2) * 24f, each, 22f);
            if (i == paintLayer) {
                ui.gui.rounded(cell, 4f, Theme.ACCENT_DIM);
            } else if (ui.hover(cell)) {
                ui.gui.rounded(cell, 4f, Theme.HOVER);
            }
            Rect swatch = new Rect(cell.x() + 4f, cell.y() + 4f, 14f, 14f);
            ui.gui.rounded(swatch, 3f, paint.color().withAlpha(1f));
            ui.gui.roundedOutline(swatch, 3f, 1f, Theme.BORDER);
            ui.textIn(ui.fit(paint.name(), each - 28f, Theme.SMALL_SCALE), cell, cell.x() + 24f, Theme.SMALL_SCALE,
                    i == paintLayer ? Theme.TEXT_BRIGHT : Theme.TEXT);
            if (ui.clicked("terrainPaint:" + i, cell)) {
                paintLayer = i;
            }
        }
        return y + (paints.size() + 1) / 2 * 24f + 6f;
    }

    /** Text broken into lines no wider than {@code width}, at word breaks. */
    private static List<String> wrap(Ui ui, String text, float width, float scale) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tried = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && ui.width(tried, scale) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tried);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** The ground layers to paint with: pick one, recolour it, give it a picture, add or take one away. */
    private float layerRows(Ui ui, Terrain3D terrain, SceneDocument doc, float x, float y, float w) {
        List<Terrain3D.Layer> layers = terrain.layers();
        ui.text("Ground layers", x, y + 4f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 26f;
        paintLayer = Math.clamp(paintLayer, 0, Math.max(0, layers.size() - 1));
        for (int i = 0; i < layers.size(); i++) {
            Terrain3D.Layer layer = layers.get(i);
            Rect row = new Rect(x, y, w, 24f);
            if (i == paintLayer) {
                ui.gui.rounded(row, 4f, Theme.ACCENT_DIM);
            } else if (ui.hover(row)) {
                ui.gui.rounded(row, 4f, Theme.HOVER);
            }
            int index = i;
            ColorPicker.swatch(ui, "terrainLayerColor:" + i, new Rect(x + 4f, y + 3f, 26f, 18f), layer.color, picked -> {
                layer.color = picked;
                terrain.layersChanged();
                doc.changed();
            });
            String label = layer.name + (layer.texture == null || layer.texture.isBlank() ? "" : "  (picture)");
            ui.textIn(ui.fit(label, w - 92f, Theme.SMALL_SCALE), row, x + 36f, Theme.SMALL_SCALE, Theme.TEXT);
            Rect pick = new Rect(x + w - 50f, y + 1f, 22f, 22f);
            if (ui.iconButton("terrainLayerPicture:" + i, Icon.IMAGE, pick,
                    "A picture to tile over this ground (" + Ui.format(layer.tileSize, false) + " m to a picture)", false)) {
                AssetPicker.open(ui, studio, "terrainLayerPicker:" + i, pick.x(), pick.y() + 24f, 320f,
                        new String[]{"png", "jpg", "jpeg"}, path -> {
                            layer.texture = path == null ? "" : path;
                            Color seen = terrain.pictureColor(layer.texture);
                            if (seen != null) {
                                layer.color = seen;
                            }
                            terrain.layersChanged();
                            doc.changed();
                        });
            }
            Rect remove = new Rect(x + w - 24f, y + 1f, 22f, 22f);
            if (layers.size() > 1 && ui.iconButton("terrainLayerRemove:" + i, Icon.CLOSE, remove,
                    "Take this layer away (ground painted with it takes the first layer)", false)) {
                removeLayer(terrain, doc, index);
                break;
            }
            if (ui.clicked("terrainLayer:" + i, new Rect(x + 34f, y, w - 88f, 24f))) {
                paintLayer = index;
            }
            y += 28f;
        }
        if (layers.size() < Terrain3D.MAX_LAYERS
                && ui.button("terrainLayerAdd", "Add Layer", new Rect(x, y, 100f, 22f))) {
            layers.add(new Terrain3D.Layer("Layer " + (layers.size() + 1), Color.rgb(0x9A9A70)));
            paintLayer = layers.size() - 1;
            terrain.layersChanged();
            doc.changed();
        }
        return y + 30f;
    }

    private void removeLayer(Terrain3D terrain, SceneDocument doc, int index) {
        WorldMap map = terrain.map();
        byte[] paint = map.materials();
        for (int i = 0; i < paint.length; i++) {
            int layer = paint[i] & 0xFF;
            if (layer == index) {
                paint[i] = 0;
            } else if (layer > index) {
                paint[i] = (byte) (layer - 1);
            }
        }
        terrain.layers().remove(index);
        paintLayer = Math.max(0, Math.min(paintLayer, terrain.layers().size() - 1));
        terrain.layersChanged();
        terrain.changedAll();
        doc.changed();
    }
}
