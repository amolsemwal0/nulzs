package org.zeronura.engine.studio;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.render.Texture;
import org.zeronura.engine.graphics3d.WearsMaterial;
import org.zeronura.engine.scene.Asset;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.ComponentFields;
import org.zeronura.engine.scene.ComponentFields.Property;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.scene.MissingComponent;
import org.zeronura.engine.scene.Range;
import org.zeronura.engine.scene.Signal;
import org.zeronura.engine.ui.Rect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The Inspector: everything about what is selected. For an entity, its name, tags and transform, then each component
 * with its settings — numbers dragged or typed, switches, colours, asset paths, links to other entities and the signals
 * wired from it — and a button to add more. With nothing selected it shows the scene's own settings; with a file chosen
 * in the Project panel, a look at that file.
 */
final class InspectorPanel {

    private static final float LABEL_SHARE = 0.4f;

    private final StudioScene studio;
    private final Map<Entity, float[]> eulers = new WeakHashMap<>();
    private final Set<String> folded = new HashSet<>();
    private float contentHeight;
    private float y;
    private float left;
    private float width;
    private Rect clip;

    InspectorPanel(StudioScene studio) {
        this.studio = studio;
    }

    void draw(Ui ui, Rect area) {
        ui.panelHeader(new Rect(area.x(), area.y(), area.width(), 30f), "Inspector", Icon.INFO);
        Rect view = new Rect(area.x(), area.y() + 30f, area.width(), area.height() - 30f);
        if (ui.pressed(view)) {
            studio.setFocus(StudioScene.Focus.INSPECTOR);
        }
        SceneDocument doc = studio.doc();
        float scroll = ui.beginScroll("inspector", view, contentHeight);
        clip = view;
        left = view.x() + 10f;
        width = view.width() - 24f;
        y = view.y() + 8f - scroll;
        float top = y;
        if (doc == null) {
            ui.text("Open or create a scene to start.", left, y, Theme.TEXT_MUTED);
            y += 30f;
        } else if (studio.selectedAsset() != null && doc.selection.isEmpty()) {
            asset(ui, studio.selectedAsset());
        } else if (doc.mapPick() != null) {
            mapItem(ui, doc, doc.mapPick());
        } else if (doc.primary() != null) {
            entity(ui, doc, doc.primary());
        } else {
            scene(ui, doc);
        }
        contentHeight = y - top + 40f;
        ui.endScroll("inspector", view, contentHeight);
    }

    private boolean visible(float rowY, float rowHeight) {
        return rowY + rowHeight >= clip.y() && rowY <= clip.y() + clip.height();
    }

    // --- An entity ----------------------------------------------------------------------------------------------

    private void entity(Ui ui, SceneDocument doc, Entity entity) {
        String key = doc.idOf(entity);
        if (doc.selection.size() > 1) {
            ui.text(doc.selection.size() + " entities selected; showing the last one picked", left, y,
                    Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            y += 22f;
        }
        Rect row = new Rect(left, y, width, Theme.FIELD_HEIGHT + 4f);
        boolean active = ui.checkbox("active:" + key, new Rect(left, y, 20f, row.height()), entity.isActiveSelf());
        if (active != entity.isActiveSelf()) {
            entity.setActive(active);
            doc.changed();
        }
        EntityBounds.iconOf(entity).draw(ui.gui, left + 26f, row.centerY() - 9f, 18f, Theme.TEXT_MUTED);
        ui.textField("name:" + key, new Rect(left + 50f, y, width - 50f, row.height()), entity.name(), "Name", name -> {
            if (!name.isBlank() && !name.equals(entity.name())) {
                entity.setName(name.trim());
                doc.changed();
            }
        });
        y += row.height() + 6f;

        Rect tagsRow = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        label(ui, "Tags", tagsRow, "Words to find entities by from code: scene.findWithTag(\"enemy\")");
        String tags = String.join(", ", new java.util.TreeSet<>(entity.tags()));
        ui.textField("tags:" + key, valueRect(tagsRow), tags, "none", text -> {
            Set<String> wanted = new LinkedHashSet<>();
            for (String part : text.split(",")) {
                if (!part.isBlank()) {
                    wanted.add(part.trim());
                }
            }
            if (!wanted.equals(new HashSet<>(entity.tags()))) {
                setTags(entity, wanted);
                doc.changed();
            }
        });
        y += Theme.FIELD_HEIGHT + 4f;
        ui.text("id " + key, left, y, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        y += 20f;

        transform(ui, doc, entity, key);

        List<Component> components = List.copyOf(entity.components());
        for (int i = 0; i < components.size(); i++) {
            component(ui, doc, entity, components.get(i), i, components.size(), key);
        }
        y += 10f;
        Rect add = new Rect(left + width * 0.15f, y, width * 0.7f, 28f);
        if (ui.primaryButton("addComponent:" + key, "Add Component", add)) {
            AddComponentPopup.open(ui, studio, entity, add.x(), add.y() + add.height() + 2f, add.width());
        }
        ScriptDrop.accept(ui, studio, entity, new Rect(left, y - 6f, width, 40f));
        y += 40f;
    }

    // --- An object or a tree on a map -----------------------------------------------------------------------------

    /**
     * What is picked on a map: which prop it is, where it stands, how it is turned and sized, and for a tree the settings
     * it grows by. Changes go into the map, and undo with the scene's.
     */
    private void mapItem(Ui ui, SceneDocument doc, MapPick pick) {
        org.zeronura.engine.graphics3d.WorldMap3D shown = pick.shown(doc);
        Entity entity = pick.entity(doc);
        // Whatever is changed here is recorded from before it: the map keeps the edit as a step when the scene is recorded.
        shown.beginEdit();
        String key = "mapItem:" + pick.entityId + ":" + (pick.tree ? "tree" : "object") + ":" + pick.index();

        Rect row = new Rect(left, y, width, Theme.FIELD_HEIGHT + 4f);
        (pick.tree ? Icon.TREE : Icon.MODEL).draw(ui.gui, left + 2f, row.centerY() - 9f, 18f, Theme.TEXT_MUTED);
        float backWidth = ui.width("Select Map") + 24f;
        Rect back = new Rect(left + width - backWidth, y + 1f, backWidth, row.height() - 2f);
        ui.textIn(ui.fit(pick.name(doc), back.x() - left - 34f), row, left + 28f, Theme.TEXT_BRIGHT);
        if (ui.button(key + ":selectMap", "Select Map", back)) {
            doc.select(entity);
            studio.selectionChanged();
            return;
        }
        ui.tooltip(key + ":selectMapTip", back, "Show the map's own settings (Esc in the view does too)");
        y += row.height() + 4f;
        int count = pick.tree ? shown.map().trees().size() : shown.map().objects().size();
        ui.text((pick.tree ? "Tree " : "Object ") + (pick.index() + 1) + " of " + count + " on " + entity.name(), left, y,
                Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        y += 20f;

        if (pick.tree) {
            mapTree(ui, doc, shown, pick.tree(doc), key);
        } else {
            mapObject(ui, doc, shown, pick.object(doc), key);
        }

        y += 8f;
        float half = (width - 8f) * 0.5f;
        if (ui.button(key + ":duplicate", "Duplicate", new Rect(left, y, half, 26f))) {
            studio.duplicateMapItem();
        }
        if (ui.button(key + ":delete", "Delete", new Rect(left + half + 8f, y, half, 26f))) {
            studio.deleteMapItem();
        }
        y += 34f;
        ui.text("Saving the scene (Ctrl+S) saves the map file.", left, y, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        y += 22f;
    }

    private void mapObject(Ui ui, SceneDocument doc, org.zeronura.engine.graphics3d.WorldMap3D shown,
                           org.zeronura.engine.worldmap.MapObject object, String key) {
        if (object.owner != 0) {
            note(ui, "Laid by a spline: it is laid again, as the spline has it, whenever the spline changes", Theme.WARNING);
        }
        if (shown.hidden(object)) {
            note(ui, "A road or wall laid over it hides it, in the game too", Theme.WARNING);
        }
        String presetName = org.zeronura.engine.worldmap.Preset.presetOfObject(object.prefab);
        if (presetName != null) {
            boolean known = org.zeronura.engine.worldmap.PresetLibrary.of(studio.engine().assets()).get(presetName) != null;
            note(ui, known ? "A copy of the foliage preset " + presetName + ": saving the preset changes it"
                    : "A copy of the foliage preset " + presetName + ", which isn't there: it isn't drawn", known
                    ? Theme.TEXT_MUTED : Theme.WARNING);
            if (known) {
                Rect edit = new Rect(left, y, width, 24f);
                y += 30f;
                if (visible(edit.y(), edit.height()) && ui.button(key + ":editPreset", "Edit Preset", edit)) {
                    studio.openPreset(presetName);
                }
            }
        }
        Runnable moved = () -> {
            shown.objectChanged(object);
            doc.changed();
        };
        if (!section(ui, key + ":object", "Map Object", Icon.MODEL, null, null, () -> List.of(
                new Menu.Command("Stand on the Ground", () -> {
                    object.offsetY = 0f;
                    object.pitch = 0f;
                    object.roll = 0f;
                    moved.run();
                }),
                new Menu.Command("Reset Rotation", () -> {
                    object.yaw = 0f;
                    object.pitch = 0f;
                    object.roll = 0f;
                    moved.run();
                }),
                new Menu.Command("Reset Scale", () -> {
                    object.scale = 1f;
                    moved.run();
                })))) {
            return;
        }
        Rect prefabRow = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 4f;
        if (visible(prefabRow.y(), prefabRow.height())) {
            label(ui, "Prefab", prefabRow, "Which prop it is: click to choose another");
            Rect field = valueRect(prefabRow);
            org.zeronura.engine.worldmap.PrefabCatalog.Prefab prefab = shown.prefabOf(object);
            ui.gui.rounded(field, Theme.RADIUS, ui.hover(field) ? Theme.HOVER : Theme.FIELD);
            ui.gui.roundedOutline(field, Theme.RADIUS, 1f, Theme.FIELD_BORDER);
            Icon.MODEL.draw(ui.gui, field.x() + 5f, field.centerY() - 7f, 14f, Theme.TEXT_MUTED);
            ui.textIn(ui.fit(prefab == null ? object.prefab : PrefabPicker.label(prefab), field.width() - 30f), field,
                    field.x() + 24f, Theme.TEXT);
            if (ui.clicked(key + ":prefab", field) && shown.prefabs() != null) {
                PrefabPicker.open(ui, shown.prefabs(), field.x(), field.y() + field.height() + 2f,
                        Math.max(field.width(), 280f), id -> {
                            if (!id.equals(object.prefab)) {
                                object.prefab = id;
                                moved.run();
                            }
                        });
            }
        }
        float ground = shown.groundUnder(object);
        vectorRow(ui, key + ":pos", "Position", new float[]{object.x, ground + object.offsetY, object.z}, 0.05, v -> {
            object.x = Math.clamp(v[0], 0f, shown.map().worldWidth());
            object.z = Math.clamp(v[2], 0f, shown.map().worldDepth());
            if (v[1] != ground + object.offsetY) {
                object.offsetY = v[1] - shown.groundUnder(object);
            }
            moved.run();
        });
        numberRow(ui, key + ":offset", "Above Ground", object.offsetY, 0.02,
                "How far it stands above the ground under it (below it, if less than 0): it rides the ground when that is "
                        + "reshaped", v -> {
                    object.offsetY = v;
                    moved.run();
                });
        vectorRow(ui, key + ":rot", "Rotation", new float[]{(float) Math.toDegrees(-object.pitch),
                (float) Math.toDegrees(object.yaw), (float) Math.toDegrees(object.roll)}, 0.5, v -> {
            object.pitch = (float) Math.toRadians(-v[0]);
            object.yaw = (float) Math.toRadians(v[1]);
            object.roll = (float) Math.toRadians(v[2]);
            moved.run();
        });
        numberRow(ui, key + ":scale", "Scale", object.scale, 0.01, "How big it is drawn: 1 as its model is", v -> {
            object.scale = Math.max(0.01f, v);
            moved.run();
        });
        Rect clearedRow = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 4f;
        if (visible(clearedRow.y(), clearedRow.height())) {
            label(ui, "Cleared by Splines", clearedRow, "Whether a road, path or wall laid over it hides it. Off for what "
                    + "must stay whatever is drawn over it: a quest's barrier, a landmark");
            boolean next = ui.checkbox(key + ":cleared", valueRect(clearedRow), object.clearedBySpline);
            if (next != object.clearedBySpline) {
                object.clearedBySpline = next;
                shown.objectsChanged();
                doc.changed();
            }
        }
        y += 4f;
    }

    private void mapTree(Ui ui, SceneDocument doc, org.zeronura.engine.graphics3d.WorldMap3D shown,
                         org.zeronura.engine.worldmap.MapTree tree, String key) {
        if (shown.hidden(tree)) {
            note(ui, "A road or wall laid over its root hides it, in the game too", Theme.WARNING);
        }
        Runnable moved = () -> {
            shown.treeChanged(tree, false);
            doc.changed();
        };
        if (section(ui, key + ":tree", "Map Tree", Icon.TREE, null, null, () -> List.of(
                new Menu.Command("Reset Turn", () -> {
                    tree.yaw = 0f;
                    moved.run();
                }),
                new Menu.Command("Reset Scale", () -> {
                    tree.scale = 1f;
                    moved.run();
                })))) {
            vectorRow(ui, key + ":pos", "Position", new float[]{tree.x, tree.z}, 0.05, v -> {
                tree.x = Math.clamp(v[0], 0f, shown.map().worldWidth());
                tree.z = Math.clamp(v[1], 0f, shown.map().worldDepth());
                moved.run();
            }, new String[]{"X", "Z"}, new Color[]{Theme.AXIS_X, Theme.AXIS_Z});
            numberRow(ui, key + ":yaw", "Turn", (float) Math.toDegrees(tree.yaw), 0.5,
                    "How far it is turned about its trunk, in degrees", v -> {
                        tree.yaw = (float) Math.toRadians(v);
                        moved.run();
                    });
            numberRow(ui, key + ":scale", "Scale", tree.scale, 0.01, "How big it is drawn: 1 as it grows", v -> {
                tree.scale = Math.max(0.05f, v);
                moved.run();
            });
            y += 4f;
        }
        if (presetOfTree(ui, doc, shown, tree, key)) {
            return;
        }
        if (!section(ui, key + ":shape", "Growth", Icon.TREE, null, null, null)) {
            return;
        }
        java.util.Set<String> placement = java.util.Set.of("x", "z", "yaw", "scale", "preset");
        for (Property property : ComponentFields.of(org.zeronura.engine.worldmap.MapTree.class)) {
            if (placement.contains(property.name())) {
                continue;
            }
            boolean grows = !property.name().equals("clearedBySpline");
            property(ui, doc, key, tree, property, () -> {
                shown.treeChanged(tree, grows);
                doc.changed();
            });
        }
        y += 6f;
    }

    /**
     * The preset a tree on a map is a copy of, if it is one: its name, Edit Preset and Unlink. Its shape comes from the
     * preset, so its growth settings aren't shown. For a tree of its own, Save as Preset... makes one of it. Whether
     * the tree is a preset's (and nothing more is to be shown).
     */
    private boolean presetOfTree(Ui ui, SceneDocument doc, org.zeronura.engine.graphics3d.WorldMap3D shown,
                                 org.zeronura.engine.worldmap.MapTree tree, String key) {
        org.zeronura.engine.worldmap.PresetLibrary library = org.zeronura.engine.worldmap.PresetLibrary.of(studio.engine().assets());
        if (tree.preset == null) {
            Rect button = new Rect(left, y, width, 24f);
            y += 30f;
            if (visible(button.y(), button.height()) && ui.button(key + ":toPreset", "Save as Preset...", button)) {
                saveTreeAsPreset(doc, shown, tree, library);
            }
            ui.tooltip(key + ":toPresetTip", button, "Keeps this tree's shape as a preset the Place Tool can place; this tree, and "
                    + "every other on the map shaped just like it, become copies of it");
            return false;
        }
        org.zeronura.engine.worldmap.Preset preset = library.get(tree.preset);
        if (!section(ui, key + ":preset", "Preset", Icon.TREE, null, null, null)) {
            return true;
        }
        Rect row = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 4f;
        if (visible(row.y(), row.height())) {
            label(ui, "Copy of", row, "Its shape comes from this preset: saving the preset changes it");
            ui.textIn(ui.fit(tree.preset + (preset == null ? "  (missing)" : ""), valueRect(row).width()), valueRect(row),
                    valueRect(row).x() + 4f, preset == null ? Theme.WARNING : Theme.TEXT_BRIGHT);
        }
        if (preset == null) {
            note(ui, "There is no preset of that name: it keeps the shape it last had", Theme.WARNING);
        }
        float half = (width - 8f) * 0.5f;
        Rect edit = new Rect(left, y, half, 24f);
        Rect unlink = new Rect(left + half + 8f, y, half, 24f);
        y += 30f;
        if (visible(edit.y(), edit.height())) {
            if (preset != null && ui.button(key + ":editPreset", "Edit Preset", edit)) {
                studio.openPreset(preset.name);
            }
            if (ui.button(key + ":unlinkPreset", "Unlink", unlink)) {
                shown.beginEdit();
                tree.preset = null;
                doc.changed();
                studio.status("The tree has a shape of its own now: its preset no longer changes it", false);
            }
            ui.tooltip(key + ":unlinkTip", unlink, "Gives it a shape of its own, set here, which its preset no longer changes");
        }
        note(ui, "Its shape is the preset's. Where it stands, its turn and its size are its own.", Theme.TEXT_DISABLED);
        return true;
    }

    /** Makes a preset of a tree on a map, and makes it and every tree there shaped like it copies of the preset. */
    private void saveTreeAsPreset(SceneDocument doc, org.zeronura.engine.graphics3d.WorldMap3D shown,
                                  org.zeronura.engine.worldmap.MapTree tree, org.zeronura.engine.worldmap.PresetLibrary library) {
        String suggested = studio.presetPanel.freeName("Map Tree");
        studio.dialog(Dialog.prompt("Save Tree as Preset", "Preset name, in " + studio.displayPath(library.folder()), suggested,
                name -> {
                    String problem = org.zeronura.engine.worldmap.Preset.nameProblem(name);
                    return problem == null && library.get(name) != null ? "There is a preset called " + name + " already" : problem;
                }, name -> {
                    org.zeronura.engine.worldmap.Preset preset = new org.zeronura.engine.worldmap.Preset(
                            org.zeronura.engine.worldmap.Preset.Kind.TREE, name);
                    org.zeronura.engine.worldmap.MapTree shape = new org.zeronura.engine.worldmap.MapTree(0f, 0f);
                    shape.takeShape(tree);
                    preset.tree = shape;
                    preset.clearedBySpline = tree.clearedBySpline;
                    try {
                        library.save(preset);
                    } catch (java.io.IOException e) {
                        studio.status("Couldn't save the preset: " + e.getMessage(), true);
                        return;
                    }
                    shown.beginEdit();
                    int linked = 0;
                    for (org.zeronura.engine.worldmap.MapTree other : shown.map().trees()) {
                        if (other.preset == null && other.sameShape(shape)) {
                            other.preset = name;
                            linked++;
                        }
                    }
                    doc.changed();
                    studio.status("Saved the preset " + name + ": " + (linked == 1 ? "this tree is a copy of it"
                            : "this tree and " + (linked - 1) + " more shaped like it are copies of it"), false);
                }));
    }

    /** A line of small text in a colour, wrapped to the panel. */
    private void note(Ui ui, String text, Color color) {
        for (String line : ui.wrap(text, width, Theme.SMALL_SCALE)) {
            ui.text(line, left, y, Theme.SMALL_SCALE, color);
            y += 18f;
        }
        y += 4f;
    }

    /** A labelled number dragged or typed. */
    private void numberRow(Ui ui, String id, String label, float value, double speed, String tooltip,
                           java.util.function.Consumer<Float> set) {
        Rect row = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 4f;
        if (!visible(row.y(), row.height())) {
            return;
        }
        label(ui, label, row, tooltip);
        ui.number(id, valueRect(row), value, speed, -Double.MAX_VALUE, Double.MAX_VALUE, false, v -> set.accept((float) v));
    }

    private static void setTags(Entity entity, Set<String> wanted) {
        entity.clearTags();
        entity.tag(wanted.toArray(String[]::new));
    }

    private void transform(Ui ui, SceneDocument doc, Entity entity, String key) {
        if (!section(ui, "transform", "Transform", Icon.MOVE, null, null, () -> List.of(
                new Menu.Command("Reset", () -> {
                    entity.transform().position.zero();
                    entity.transform().rotation.identity();
                    entity.transform().scale.set(1f);
                    doc.changed();
                }),
                new Menu.Command("Copy Position", () -> ui.input.setClipboard(vectorText(entity.transform().position))),
                new Menu.Command("Paste Position", () -> {
                    float[] v = parseVector(ui.input.clipboard(), 3);
                    if (v != null) {
                        entity.transform().position.set(v[0], v[1], v[2]);
                        doc.changed();
                    }
                })))) {
            return;
        }
        Vector3f position = entity.transform().position;
        vectorRow(ui, "pos:" + key, "Position", new float[]{position.x, position.y, position.z}, 0.05, v -> {
            position.set(v[0], v[1], v[2]);
            doc.changed();
        });

        // Euler angles are kept per entity while it is edited, so typing 190 doesn't come back as -170.
        Quaternionf rotation = entity.transform().rotation;
        float[] euler = eulers.get(entity);
        Quaternionf fromEuler = euler == null ? null : fromEuler(euler);
        if (euler == null || !approximately(fromEuler, rotation)) {
            Vector3f angles = rotation.getEulerAnglesYXZ(new Vector3f());
            euler = new float[]{(float) Math.toDegrees(angles.x), (float) Math.toDegrees(angles.y), (float) Math.toDegrees(angles.z)};
            for (int i = 0; i < 3; i++) {
                euler[i] = Math.round(euler[i] * 1000f) / 1000f;
            }
            eulers.put(entity, euler);
        }
        float[] shown = euler;
        vectorRow(ui, "rot:" + key, "Rotation", shown.clone(), 0.5, v -> {
            eulers.put(entity, v.clone());
            rotation.set(fromEuler(v));
            doc.changed();
        });
        Vector3f scale = entity.transform().scale;
        vectorRow(ui, "scale:" + key, "Scale", new float[]{scale.x, scale.y, scale.z}, 0.02, v -> {
            scale.set(v[0], v[1], v[2]);
            doc.changed();
        });
        y += 4f;
    }

    private static Quaternionf fromEuler(float[] degrees) {
        return new Quaternionf().rotationYXZ((float) Math.toRadians(degrees[1]), (float) Math.toRadians(degrees[0]),
                (float) Math.toRadians(degrees[2]));
    }

    private static boolean approximately(Quaternionf a, Quaternionf b) {
        return Math.abs(Math.abs(a.dot(b)) - 1f) < 1e-5f;
    }

    private void component(Ui ui, SceneDocument doc, Entity entity, Component component, int index, int count, String key) {
        Class<?> type = component.getClass();
        String foldKey = key + ":" + index + ":" + type.getName();
        if (component instanceof MissingComponent missing) {
            boolean open = section(ui, foldKey, "Missing script " + simpleName(missing.type()), Icon.WARNING, Theme.ERROR, null,
                    () -> List.of(new Menu.Command("Remove Component", () -> {
                        entity.remove(component);
                        doc.changed();
                    })));
            if (open) {
                ui.text("Its class can't be found: compile the scripts, or remove it.", left + 8f, y, Theme.SMALL_SCALE,
                        Theme.TEXT_MUTED);
                y += 24f;
            }
            return;
        }
        boolean[] enabled = {component.isEnabled()};
        boolean script = studio.catalog.sourceOf(type) != null && type.getName().startsWith(studio.catalog.projectPackage());
        boolean open = section(ui, foldKey, ComponentFields.label(type.getSimpleName()),
                script ? Icon.SCRIPT : iconFor(type), null, enabled, () -> componentMenu(ui, doc, entity, component, index, count));
        if (enabled[0] != component.isEnabled()) {
            component.setEnabled(enabled[0]);
            doc.changed();
        }
        if (!open) {
            return;
        }
        List<Property> properties = ComponentFields.of(type);
        if (properties.isEmpty()) {
            ui.text("No settings", left + 8f, y, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
            y += 22f;
        }
        for (Property property : properties) {
            property(ui, doc, key + ":" + index, component, property, doc::changed);
        }
        y += 6f;
    }

    private List<Menu.Item> componentMenu(Ui ui, SceneDocument doc, Entity entity, Component component, int index, int count) {
        List<Menu.Item> items = new ArrayList<>();
        Path source = studio.catalog.sourceOf(component.getClass());
        if (source != null) {
            items.add(new Menu.Command("Edit Script", () -> studio.openCode(source, 1)));
            items.add(Menu.SEPARATOR);
        }
        items.add(new Menu.Command("Move Up", () -> studio.moveComponent(entity, index, -1)).enabledWhen(() -> index > 0));
        items.add(new Menu.Command("Move Down", () -> studio.moveComponent(entity, index, 1)).enabledWhen(() -> index < count - 1));
        items.add(new Menu.Command("Reset", () -> studio.resetComponent(entity, index)));
        items.add(Menu.SEPARATOR);
        items.add(new Menu.Command("Remove Component", () -> {
            entity.remove(component);
            doc.changed();
        }));
        return items;
    }

    private static Icon iconFor(Class<?> type) {
        String name = type.getSimpleName();
        if (name.contains("Camera")) {
            return Icon.CAMERA;
        }
        if (name.contains("Sprite")) {
            return Icon.SPRITE;
        }
        if (name.contains("Model")) {
            return Icon.MODEL;
        }
        if (name.contains("Shape") || name.contains("Mesh")) {
            return Icon.SHAPE;
        }
        if (name.contains("Light")) {
            return Icon.LIGHT;
        }
        if (name.contains("Audio") || name.contains("Sound")) {
            return Icon.AUDIO;
        }
        return Icon.GEAR;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /**
     * A section's header: fold arrow, optional switch, icon, title and a menu. Returns whether it is open.
     *
     * @param enabled one switch, read and written; null for none
     */
    private boolean section(Ui ui, String key, String title, Icon icon, Color titleColor, boolean[] enabled,
                            java.util.function.Supplier<List<Menu.Item>> menu) {
        Rect header = new Rect(left - 6f, y, width + 12f, 26f);
        boolean open = !folded.contains(key);
        if (visible(header.y(), header.height())) {
            ui.gui.rounded(header, 3f, ui.hover(header) ? Theme.HOVER : Theme.SECTION);
            (open ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT).draw(ui.gui, header.x() + 4f, header.centerY() - 7f, 14f,
                    Theme.TEXT_MUTED);
            float x = header.x() + 22f;
            if (enabled != null) {
                enabled[0] = ui.checkbox("enabled:" + key, new Rect(x, header.y(), 18f, header.height()), enabled[0]);
                x += 24f;
            }
            icon.draw(ui.gui, x, header.centerY() - 8f, 16f, titleColor != null ? titleColor : Theme.TEXT_MUTED);
            x += 22f;
            ui.textIn(ui.fit(title, header.x() + header.width() - x - 30f), header, x,
                    titleColor != null ? titleColor : Theme.TEXT_BRIGHT);
            Rect dots = new Rect(header.x() + header.width() - 26f, header.y() + 2f, 22f, 22f);
            if (menu != null && ui.iconButton("menu:" + key, Icon.DOTS, dots, null, false)) {
                Menu.open(ui, "componentMenu", dots.x(), dots.y() + dots.height(), menu);
            }
            Rect foldArea = new Rect(header.x(), header.y(), (enabled != null ? 22f : x - header.x() + 200f), header.height());
            if (ui.clicked("fold:" + key, enabled != null ? foldArea : new Rect(header.x(), header.y(), header.width() - 30f,
                    header.height()))) {
                if (open) {
                    folded.add(key);
                } else {
                    folded.remove(key);
                }
            }
        }
        y += header.height() + 6f;
        return open;
    }

    // --- Properties -------------------------------------------------------------------------------------------------

    private Rect valueRect(Rect row) {
        float labelWidth = row.width() * LABEL_SHARE;
        return new Rect(row.x() + labelWidth, row.y(), row.width() - labelWidth, row.height());
    }

    private void label(Ui ui, String text, Rect row, String tooltip) {
        float labelWidth = row.width() * LABEL_SHARE - 6f;
        ui.textIn(ui.fit(text, labelWidth), row, row.x() + 4f, Theme.TEXT_MUTED);
        if (tooltip != null) {
            ui.tooltip("label:" + text + row.y(), new Rect(row.x(), row.y(), labelWidth, row.height()), tooltip);
        }
    }

    /** One setting of a component, or of the scene: its label, and the editor for its kind. */
    void property(Ui ui, SceneDocument doc, String key, Object owner, Property property, Runnable changed) {
        String id = key + ":" + property.name();
        if (owner instanceof WearsMaterial wearer && owner instanceof Component component && property.name().equals("material")) {
            materialRows(ui, id, component, wearer, property, changed);
            return;
        }
        if (owner instanceof org.zeronura.engine.graphics3d.GeometryNodes3D geometry && property.name().equals("nodes")
                && !drawingTreeField) {
            drawingTreeField = true;
            try {
                property(ui, doc, key, owner, property, changed);
            } finally {
                drawingTreeField = false;
            }
            treeInputs(ui, id, geometry, changed);
            return;
        }
        Object value = property.get(owner);
        float rowHeight = Theme.FIELD_HEIGHT;
        switch (property.kind()) {
            case VECTOR2, VECTOR3, VECTOR4 -> {
                float[] values = switch (property.kind()) {
                    case VECTOR2 -> new float[]{((Vector2f) value).x, ((Vector2f) value).y};
                    case VECTOR3 -> new float[]{((Vector3f) value).x, ((Vector3f) value).y, ((Vector3f) value).z};
                    default -> new float[]{((Vector4f) value).x, ((Vector4f) value).y, ((Vector4f) value).z, ((Vector4f) value).w};
                };
                vectorRow(ui, id, property.label(), values, 0.02, v -> {
                    switch (property.kind()) {
                        case VECTOR2 -> ((Vector2f) value).set(v[0], v[1]);
                        case VECTOR3 -> ((Vector3f) value).set(v[0], v[1], v[2]);
                        default -> ((Vector4f) value).set(v[0], v[1], v[2], v[3]);
                    }
                    changed.run();
                });
                return;
            }
            case SIGNAL -> {
                signal(ui, doc, id, owner, property, (Signal) value, changed);
                return;
            }
            default -> {
            }
        }
        Rect row = new Rect(left, y, width, rowHeight);
        y += rowHeight + 4f;
        if (!visible(row.y(), row.height())) {
            return;
        }
        label(ui, property.label(), row, property.tooltip());
        Rect field = valueRect(row);
        Range range = property.range();
        switch (property.kind()) {
            case BOOLEAN -> {
                boolean next = ui.checkbox(id, field, (Boolean) value);
                if (next != (Boolean) value) {
                    property.set(owner, next);
                    changed.run();
                }
            }
            case INT, LONG, FLOAT, DOUBLE -> {
                boolean integer = property.kind() == ComponentFields.Kind.INT || property.kind() == ComponentFields.Kind.LONG;
                double number = ((Number) value).doubleValue();
                java.util.function.DoubleConsumer set = v -> {
                    property.set(owner, integer ? (Number) Math.round(v) : (Number) v);
                    changed.run();
                };
                if (range != null) {
                    ui.slider(id, field, number, range.min(), range.max(), integer, set);
                } else {
                    double speed = integer ? 0.15 : Math.max(0.005, Math.min(1.0, Math.abs(number) * 0.01 + 0.01));
                    ui.number(id, field, number, speed, -Double.MAX_VALUE, Double.MAX_VALUE, integer, set);
                }
            }
            case STRING -> {
                Asset asset = property.asset();
                if (asset != null) {
                    assetField(ui, id, field, (String) value, asset.value(), text -> {
                        property.set(owner, text);
                        changed.run();
                    });
                } else {
                    ui.textField(id, field, (String) value, null, text -> {
                        property.set(owner, text);
                        changed.run();
                    });
                }
            }
            case ENUM -> {
                Object[] constants = property.type().getEnumConstants();
                List<String> labels = new ArrayList<>();
                for (Object constant : constants) {
                    labels.add(ComponentFields.enumLabel((Enum<?>) constant));
                }
                String current = value == null ? "" : ComponentFields.enumLabel((Enum<?>) value);
                ui.dropdown(id, field, current, labels, i -> {
                    property.set(owner, constants[i]);
                    changed.run();
                });
            }
            case COLOR -> ColorPicker.field(ui, id, field, (Color) value, color -> {
                property.set(owner, color);
                changed.run();
            });
            case ENTITY -> referenceField(ui, doc, id, field, (Entity) value, null, picked -> {
                property.set(owner, picked);
                changed.run();
            });
            case COMPONENT -> {
                Component current = (Component) value;
                referenceField(ui, doc, id, field, current == null ? null : current.entity(), property.type(), picked -> {
                    @SuppressWarnings("unchecked")
                    Class<Object> wanted = (Class<Object>) property.type();
                    property.set(owner, picked == null ? null : picked.get(wanted));
                    changed.run();
                });
            }
            default -> {
            }
        }
    }

    /** A row of two to four numbers with coloured X, Y, Z and W tags. */
    private void vectorRow(Ui ui, String id, String label, float[] values, double speed,
                           java.util.function.Consumer<float[]> set) {
        vectorRow(ui, id, label, values, speed, set, new String[]{"X", "Y", "Z", "W"},
                new Color[]{Theme.AXIS_X, Theme.AXIS_Y, Theme.AXIS_Z, Theme.TEXT_MUTED});
    }

    /** A row of numbers with the tags and colours given. */
    private void vectorRow(Ui ui, String id, String label, float[] values, double speed,
                           java.util.function.Consumer<float[]> set, String[] names, Color[] colors) {
        Rect row = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 4f;
        if (!visible(row.y(), row.height())) {
            return;
        }
        label(ui, label, row, null);
        Rect field = valueRect(row);
        float gap = 4f;
        float each = (field.width() - gap * (values.length - 1)) / values.length;
        for (int i = 0; i < values.length; i++) {
            Rect cell = new Rect(field.x() + i * (each + gap), field.y(), each, field.height());
            Rect tag = new Rect(cell.x(), cell.y(), 14f, cell.height());
            ui.gui.rounded(tag, 3f, colors[i].scaled(0.75f));
            ui.textCentered(names[i], tag, 1.25f, Theme.TEXT_BRIGHT);
            int axis = i;
            ui.number(id + ":" + i, new Rect(cell.x() + 15f, cell.y(), cell.width() - 15f, cell.height()), values[i], speed,
                    -Double.MAX_VALUE, Double.MAX_VALUE, false, v -> {
                        float[] next = values.clone();
                        next[axis] = (float) v;
                        set.accept(next);
                    });
        }
    }

    // --- Assets, references and signals -----------------------------------------------------------------------------

    /**
     * What a model or shape is drawn in — its own look (Baked, or Plain colour), its own shader, or one of the project's
     * saved shaders — and, for a model of several materials, a row for each part, which can wear what the whole does or
     * something of its own. New Shader... makes one in the Shader Tool from how it looks, Save as Shader... keeps an own
     * one to use again, and the button beside each edits what it wears.
     */
    private void materialRows(Ui ui, String id, Component component, WearsMaterial wearer, Property property,
                              Runnable changed) {
        Rect row = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 4f;
        if (visible(row.y(), row.height())) {
            label(ui, property.label(), row, property.tooltip());
            materialChoice(ui, id, valueRect(row), component, wearer, null, changed);
        }
        problemLine(ui, wearer.materialProblem(), wearer);
        List<String> parts = wearer.parts();
        if (parts.size() < 2) {
            return;
        }
        for (String part : parts) {
            Rect partRow = new Rect(left, y, width, Theme.FIELD_HEIGHT);
            y += Theme.FIELD_HEIGHT + 4f;
            if (visible(partRow.y(), partRow.height())) {
                float labelWidth = partRow.width() * LABEL_SHARE - 6f;
                Icon.CHEVRON_RIGHT.draw(ui.gui, partRow.x() + 2f, partRow.centerY() - 5f, 10f, Theme.TEXT_DISABLED);
                ui.textIn(ui.fit(part, labelWidth - 14f, Theme.SMALL_SCALE), partRow, partRow.x() + 14f, Theme.SMALL_SCALE,
                        Theme.TEXT_MUTED);
                ui.tooltip("partLabel:" + id + part, new Rect(partRow.x(), partRow.y(), labelWidth, partRow.height()),
                        "Part of the model: what its material " + part + " covers. As Material wears what the whole does");
                materialChoice(ui, id + ":part:" + part, valueRect(partRow), component, wearer, part, changed);
            }
            problemLine(ui, wearer.partProblem(part), wearer);
        }
    }

    /** Set while a geometry tree's own file field is drawn, before its inputs: the plain field, not this again. */
    private boolean drawingTreeField;

    /**
     * A geometry node tree's inputs, under the tree's field: one row each, its editor by the input's type, showing the
     * tree's default until it is set here (a set one has a button to go back to the default); then what its last run
     * made, or what went wrong, and a button to open the tree in the Geometry Nodes Tool.
     */
    private void treeInputs(Ui ui, String id, org.zeronura.engine.graphics3d.GeometryNodes3D geometry, Runnable changed) {
        for (org.zeronura.engine.nodes.NodeTree.InterfaceSocket socket : geometry.treeInputs()) {
            String inputId = id + ":input:" + socket.id();
            Object set = geometry.input(socket.id());
            Object value = set != null ? set : socket.defaultValue();
            java.util.function.Consumer<Object> put = v -> {
                geometry.set(socket.id(), v);
                changed.run();
            };
            switch (socket.type()) {
                case VECTOR, ROTATION -> {
                    float[] v = ComponentFields.floats(value instanceof float[] f ? toList(f) : value, 3);
                    vectorRow(ui, inputId, socket.name(), v, 0.02, next -> put.accept(List.of((double) next[0],
                            (double) next[1], (double) next[2])));
                    continue;
                }
                default -> {
                }
            }
            Rect row = new Rect(left, y, width, Theme.FIELD_HEIGHT);
            y += Theme.FIELD_HEIGHT + 4f;
            if (!visible(row.y(), row.height())) {
                continue;
            }
            label(ui, socket.name(), row, "An input of the tree" + (set == null ? ", at its default" : ""));
            Rect field = valueRect(row);
            if (set != null) {
                Rect reset = new Rect(field.x() + field.width() - 24f, field.y(), 24f, field.height());
                field = new Rect(field.x(), field.y(), field.width() - 28f, field.height());
                if (ui.iconButton(inputId + ":reset", Icon.REFRESH, reset, "Back to the tree's default", false)) {
                    geometry.set(socket.id(), null);
                    changed.run();
                }
            }
            switch (socket.type()) {
                case VALUE, INT -> {
                    boolean integer = socket.type() == org.zeronura.engine.nodes.SocketType.INT;
                    double number = value instanceof Number n ? n.doubleValue() : 0.0;
                    java.util.function.DoubleConsumer to = v -> put.accept(integer ? (Object) Math.round(v) : (Object) v);
                    if (socket.min() != null && socket.max() != null && socket.max() - socket.min() < 1e5f) {
                        ui.slider(inputId, field, number, socket.min(), socket.max(), integer, to);
                    } else {
                        double speed = integer ? 0.15 : Math.max(0.005, Math.min(1.0, Math.abs(number) * 0.01 + 0.01));
                        ui.number(inputId, field, number, speed, socket.min() == null ? -Double.MAX_VALUE : socket.min(),
                                socket.max() == null ? Double.MAX_VALUE : socket.max(), integer, to);
                    }
                }
                case BOOLEAN -> {
                    boolean on = Boolean.TRUE.equals(value);
                    boolean next = ui.checkbox(inputId, field, on);
                    if (next != on) {
                        put.accept(next);
                    }
                }
                case RGBA -> {
                    float[] c = ComponentFields.floats(value instanceof float[] f ? toList(f) : value, 4);
                    ColorPicker.swatch(ui, inputId, field, NodeEditorPanel.shown(c), picked -> put.accept(NodeEditorPanel.stored(picked)));
                }
                case STRING -> ui.textField(inputId, field, value == null ? "" : String.valueOf(value), null, put::accept);
                case MATERIAL -> {
                    String current = value instanceof org.zeronura.engine.nodes.Ref r ? r.name() : value instanceof String t ? t : "";
                    List<String> labels = new ArrayList<>(List.of("None"));
                    List<String> choices = new ArrayList<>(List.of(""));
                    for (Path file : studio.shaderPanel.savedShaders()) {
                        String asset = studio.assetPath(file);
                        if (asset != null) {
                            labels.add(ShaderPanel.shaderName(file));
                            choices.add(asset);
                        }
                    }
                    ui.dropdown(inputId, field, current.isBlank() ? "None" : ShaderPanel.shaderName(current), labels,
                            i -> put.accept(choices.get(i)));
                }
                default -> ui.textIn(ComponentFields.label(socket.type().name()), field, field.x() + 4f, Theme.TEXT_DISABLED);
            }
        }
        org.zeronura.engine.nodes.render.GeometryTrees.Result result = geometry.result();
        if (result != null) {
            for (String problem : result.problems()) {
                if (visible(y - 2f, 16f)) {
                    ui.text(ui.fit(problem, width - 8f, Theme.SMALL_SCALE), left + 4f, y - 2f, Theme.SMALL_SCALE, Theme.WARNING);
                }
                y += 18f;
            }
            if (result.ok() && visible(y - 2f, 16f)) {
                ui.text(ui.fit(GeometryPanel.summary(result), width - 8f, Theme.SMALL_SCALE), left + 4f, y - 2f, Theme.SMALL_SCALE,
                        Theme.TEXT_MUTED);
            }
            y += 18f;
        }
        Rect button = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 6f;
        if (visible(button.y(), button.height())) {
            boolean chosen = geometry.nodes != null && !geometry.nodes.isBlank();
            if (ui.button(id + ":editTree", chosen ? "Edit in Geometry Nodes Tool" : "New Geometry Nodes Tree...", button)) {
                studio.editGeometryOf(geometry);
            }
        }
    }

    private static List<Object> toList(float[] values) {
        List<Object> out = new ArrayList<>();
        for (float v : values) {
            out.add((double) v);
        }
        return out;
    }

    private void problemLine(Ui ui, String problem, WearsMaterial wearer) {
        if (problem == null || problem.isBlank()) {
            return;
        }
        if (visible(y - 2f, 16f)) {
            ui.text(ui.fit("Drawn as " + wearer.unshadedLabel().toLowerCase(java.util.Locale.ROOT) + ": " + problem, width - 8f,
                    Theme.SMALL_SCALE), left + 4f, y - 2f, Theme.SMALL_SCALE, Theme.WARNING);
        }
        y += 18f;
    }

    /** One Material dropdown and its edit button: for the whole, or for a part (null for the whole). */
    private void materialChoice(Ui ui, String id, Rect field, Component component, WearsMaterial wearer, String part,
                                Runnable changed) {
        String chosen = ShaderPanel.choiceOf(wearer, part);
        boolean asWhole = part != null && chosen == null;
        String value = chosen == null ? "" : chosen.trim();
        boolean own = !asWhole && WearsMaterial.OWN.equals(value);
        boolean hasOwn = ShaderPanel.ownOf(wearer, part) != null;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        java.util.function.Consumer<String> choose = choice -> {
            ShaderPanel.setChoice(wearer, part, choice);
            changed.run();
        };
        if (part != null) {
            labels.add("As Material");
            actions.add(() -> choose.accept(null));
        }
        labels.add(wearer.unshadedLabel());
        actions.add(() -> choose.accept(""));
        if (hasOwn) {
            labels.add("Own shader");
            actions.add(() -> choose.accept(WearsMaterial.OWN));
        }
        for (Path file : studio.shaderPanel.savedShaders()) {
            String asset = studio.assetPath(file);
            if (asset != null) {
                labels.add(ShaderPanel.shaderName(file));
                actions.add(() -> choose.accept(asset));
            }
        }
        labels.add(hasOwn ? "Edit Own Shader..." : "New Shader...");
        actions.add(() -> studio.editOwnShader(component, part));
        if (own) {
            labels.add("Save as Shader...");
            actions.add(() -> studio.saveOwnShader(component, part));
        }
        String current = asWhole ? "As Material" : value.isEmpty() ? wearer.unshadedLabel() : own ? "Own shader"
                : ShaderPanel.shaderName(value);
        Rect pick = new Rect(field.x(), field.y(), field.width() - 28f, field.height());
        Rect edit = new Rect(field.x() + field.width() - 26f, field.y(), 26f, field.height());
        ui.dropdown(id, pick, current, labels, i -> actions.get(i).run());
        String tip = asWhole || value.isEmpty()
                ? "Make " + (part == null ? "it" : "this part") + " a shader in the Shader Tool, starting from how it looks now"
                : own ? "Edit its own shader in the Shader Tool"
                : "Edit " + current + " in the Shader Tool (everything wearing it changes)";
        if (ui.iconButton(id + ":edit", Icon.SHADER, edit, tip, false)) {
            studio.editMaterialOf(component, part);
        }
        if (ui.dragOver(field, AssetBrowser.AssetDrag.class)) {
            ui.gui.roundedOutline(field, Theme.RADIUS, 2f, Theme.ACCENT);
        }
        AssetBrowser.AssetDrag dropped = ui.dropped(field, AssetBrowser.AssetDrag.class);
        if (dropped != null) {
            String relative = studio.assetPath(dropped.path());
            if (relative != null && AssetKind.of(dropped.path()) == AssetKind.MATERIAL) {
                choose.accept(relative);
            } else {
                studio.status("Only a shader (a .nodes.json file) can go in Material", true);
            }
        }
        String problem = part == null ? wearer.materialProblem() : wearer.partProblem(part);
        if (problem != null && !problem.isBlank()) {
            ui.gui.roundedOutline(pick, Theme.RADIUS, 1f, Theme.WARNING);
        }
    }

    private void assetField(Ui ui, String id, Rect field, String value, String[] extensions,
                            java.util.function.Consumer<String> set) {
        Rect text = new Rect(field.x(), field.y(), field.width() - 26f, field.height());
        Rect pick = new Rect(field.x() + field.width() - 24f, field.y(), 24f, field.height());
        ui.monoField(id, text, value, "none", set);
        if (ui.iconButton(id + ":pick", Icon.FOLDER, pick, "Choose a file from the project", false)) {
            AssetPicker.open(ui, studio, id, field.x(), field.y() + field.height() + 2f, Math.max(field.width(), 320f),
                    extensions, set);
        }
        if (ui.dragOver(field, AssetBrowser.AssetDrag.class)) {
            ui.gui.roundedOutline(field, Theme.RADIUS, 2f, Theme.ACCENT);
        }
        AssetBrowser.AssetDrag dropped = ui.dropped(field, AssetBrowser.AssetDrag.class);
        if (dropped != null) {
            String relative = studio.assetPath(dropped.path());
            if (relative != null && AssetPicker.accepts(dropped.path(), extensions)) {
                set.accept(relative);
            } else {
                studio.status("That file can't go in this field", true);
            }
        }
    }

    /**
     * A link to another entity: shows its name, opens a list of entities when clicked, and takes an entity dragged from
     * the Hierarchy.
     *
     * @param needs a component the entity must have, or null for any entity
     */
    private void referenceField(Ui ui, SceneDocument doc, String id, Rect field, Entity value, Class<?> needs,
                                java.util.function.Consumer<Entity> set) {
        boolean hover = ui.hover(field);
        boolean dragOver = ui.dragOver(field, HierarchyPanel.EntityDrag.class);
        ui.gui.rounded(field, Theme.RADIUS, hover ? Theme.HOVER : Theme.FIELD);
        ui.gui.roundedOutline(field, Theme.RADIUS, dragOver ? 2f : 1f, dragOver ? Theme.ACCENT : Theme.FIELD_BORDER);
        String typeName = needs == null ? "Entity" : needs.getSimpleName();
        if (value != null && !value.isDestroyed()) {
            EntityBounds.iconOf(value).draw(ui.gui, field.x() + 5f, field.centerY() - 7f, 14f, Theme.REFERENCE_WIRE);
            ui.textIn(ui.fit(value.name() + (needs == null ? "" : " (" + typeName + ")"), field.width() - 48f), field,
                    field.x() + 24f, Theme.TEXT);
        } else {
            ui.textIn(ui.fit("None (" + typeName + ")", field.width() - 30f), field, field.x() + 8f, Theme.TEXT_DISABLED);
        }
        Rect clear = new Rect(field.x() + field.width() - 20f, field.y() + 2f, 18f, field.height() - 4f);
        if (value != null && ui.iconButton(id + ":clear", Icon.CLOSE, clear, "Clear", false)) {
            set.accept(null);
            return;
        }
        if (ui.clicked(id + ":open", new Rect(field.x(), field.y(), field.width() - 22f, field.height()))) {
            EntityPicker.open(ui, studio, id, field.x(), field.y() + field.height() + 2f, Math.max(field.width(), 240f),
                    needs, set);
        }
        HierarchyPanel.EntityDrag dropped = ui.dropped(field, HierarchyPanel.EntityDrag.class);
        if (dropped != null && !dropped.entities().isEmpty()) {
            Entity entity = dropped.entities().getFirst();
            if (needs == null || hasComponent(entity, needs)) {
                set.accept(entity);
            } else {
                studio.status(entity.name() + " has no " + typeName, true);
            }
        }
    }

    static boolean hasComponent(Entity entity, Class<?> type) {
        for (Component component : entity.components()) {
            if (type.isInstance(component)) {
                return true;
            }
        }
        return false;
    }

    /** A signal's wires: each a target entity and one of its components' methods, with a button to add another. */
    private void signal(Ui ui, SceneDocument doc, String id, Object owner, Property property, Signal signal,
                        Runnable changed) {
        Rect title = new Rect(left, y, width, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 2f;
        if (visible(title.y(), title.height())) {
            Icon.SIGNAL.draw(ui.gui, left, title.centerY() - 7f, 14f, Theme.SIGNAL_WIRE);
            ui.textIn(property.label() + "  (" + signal.connections().size() + ")", title, left + 20f, Theme.TEXT);
            ui.tooltip("signal:" + id, title, property.tooltip() != null ? property.tooltip()
                    : "Called when the component emits it: wire it to a method on another entity");
        }
        List<Signal.Connection> wires = List.copyOf(signal.connections());
        for (int i = 0; i < wires.size(); i++) {
            Signal.Connection wire = wires.get(i);
            Rect row = new Rect(left + 12f, y, width - 12f, Theme.FIELD_HEIGHT);
            y += Theme.FIELD_HEIGHT + 3f;
            if (!visible(row.y(), row.height())) {
                continue;
            }
            float half = (row.width() - 28f) * 0.45f;
            String wireId = id + ":" + i;
            referenceField(ui, doc, wireId + ":target", new Rect(row.x(), row.y(), half, row.height()), wire.target(), null,
                    picked -> {
                        signal.replace(wire, new Signal.Connection(picked, wire.component(), wire.method()));
                        changed.run();
                    });
            List<String[]> methods = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            if (wire.target() != null) {
                for (Component component : wire.target().components()) {
                    for (String method : Signal.callableMethods(component.getClass())) {
                        methods.add(new String[]{component.getClass().getName(), method});
                        labels.add(component.getClass().getSimpleName() + "." + method + "()");
                    }
                }
            }
            String current = wire.method() == null || wire.method().isEmpty() ? "Choose a method"
                    : simpleName(wire.component()) + "." + wire.method() + "()";
            Rect methodRect = new Rect(row.x() + half + 4f, row.y(), row.width() - half - 30f, row.height());
            if (labels.isEmpty()) {
                ui.gui.rounded(methodRect, Theme.RADIUS, Theme.FIELD);
                ui.textIn(wire.target() == null ? "Pick an entity first" : "No methods to call", methodRect,
                        methodRect.x() + 6f, Theme.TEXT_DISABLED);
            } else {
                ui.dropdown(wireId + ":method", methodRect, current, labels, k -> {
                    signal.replace(wire, new Signal.Connection(wire.target(), methods.get(k)[0], methods.get(k)[1]));
                    changed.run();
                });
            }
            if (ui.iconButton(wireId + ":remove", Icon.CLOSE, new Rect(row.x() + row.width() - 22f, row.y(), 22f,
                    row.height()), "Remove this wire", false)) {
                signal.disconnect(wire);
                changed.run();
            }
        }
        Rect add = new Rect(left + 12f, y, 120f, Theme.FIELD_HEIGHT);
        y += Theme.FIELD_HEIGHT + 6f;
        if (visible(add.y(), add.height()) && ui.button(id + ":add", "+ Add wire", add)) {
            Entity self = owner instanceof Component component ? component.entity() : null;
            signal.connect(self, "", "");
            changed.run();
        }
    }

    // --- The scene ------------------------------------------------------------------------------------------------

    private void scene(Ui ui, SceneDocument doc) {
        ui.heading(doc.name(), left, y, Theme.TEXT_BRIGHT);
        y += 26f;
        String where = doc.file == null ? "Not saved yet" : studio.displayPath(doc.file);
        ui.text(ui.fit(where, width), left, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 26f;
        if (section(ui, "sceneSettings", "Scene Settings", Icon.SCENE, null, null, null)) {
            for (Property property : ComponentFields.of(doc.settings.getClass())) {
                property(ui, doc, "scene", doc.settings, property, doc::applySettings);
            }
        }
        y += 8f;
        if (!doc.problems.isEmpty() && section(ui, "sceneProblems", "Problems loading", Icon.WARNING, Theme.WARNING, null, null)) {
            for (String problem : doc.problems) {
                ui.text(ui.fit(problem, width), left, y, Theme.SMALL_SCALE, Theme.WARNING);
                y += 20f;
            }
        }
        y += 8f;
        ui.text("Select an entity to see its components.", left, y, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
        y += 22f;
    }

    // --- A file -------------------------------------------------------------------------------------------------

    private void asset(Ui ui, Path file) {
        String name = file.getFileName().toString();
        AssetKind kind = AssetKind.of(file);
        kind.icon().draw(ui.gui, left, y, 22f, Theme.TEXT_MUTED);
        ui.heading(ui.fit(name, width - 30f), left + 30f, y + 3f, Theme.TEXT_BRIGHT);
        y += 32f;
        ui.text(ui.fit(studio.displayPath(file), width), left, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 22f;
        try {
            if (Files.isRegularFile(file)) {
                long bytes = Files.size(file);
                ui.text(kind.label() + "  -  " + size(bytes), left, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                y += 26f;
            }
        } catch (IOException ignored) {
            // shown without a size
        }
        Rect button = new Rect(left, y, Math.min(200f, width), 28f);
        switch (kind) {
            case SCENE -> {
                if (ui.primaryButton("assetOpenScene", "Open Scene", button)) {
                    studio.openScene(file);
                }
                y += 36f;
            }
            case MATERIAL -> {
                if (ui.primaryButton("assetOpenMaterial", "Open in Shader Tool", button)) {
                    studio.openShaderFile(file);
                }
                y += 36f;
            }
            case SCRIPT, TEXT, SHADER, DATA -> {
                if (ui.primaryButton("assetEdit", "Edit", button)) {
                    studio.openCode(file, 1);
                }
                y += 36f;
            }
            case MODEL -> {
                if (ui.primaryButton("assetAddModel", "Add to Scene", button)) {
                    studio.dropAsset(file, studio.viewport.spawnPoint(), null);
                }
                y += 36f;
            }
            case IMAGE -> {
                Texture preview = studio.assets.preview(file);
                if (preview != null) {
                    float w = width;
                    float h = w * preview.getHeight() / (float) Math.max(1, preview.getWidth());
                    if (h > 420f) {
                        w *= 420f / h;
                        h = 420f;
                    }
                    Rect image = new Rect(left + (width - w) * 0.5f, y, w, h);
                    Checker.draw(ui.gui, image);
                    ui.gui.image(preview, image, Color.WHITE);
                    y += h + 8f;
                    int[] dims = studio.assets.dimensions(file);
                    if (dims != null) {
                        ui.text(dims[0] + " x " + dims[1] + " pixels", left, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                        y += 24f;
                    }
                } else {
                    ui.text("Loading preview...", left, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
                    y += 24f;
                }
                if (ui.button("assetAddSprite", "Add as Sprite", new Rect(left, y, Math.min(200f, width), 28f))) {
                    studio.dropAsset(file, studio.viewport.spawnPoint(), null);
                }
                y += 36f;
                if (file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                        && ui.button("assetEditSprite", "Edit in Sprite Editor", new Rect(left, y, Math.min(200f, width), 28f))) {
                    studio.openSpriteFile(file);
                }
                y += 36f;
            }
            default -> {
            }
        }
        if (kind == AssetKind.SCRIPT || kind == AssetKind.TEXT || kind == AssetKind.DATA || kind == AssetKind.SHADER) {
            List<String> lines = studio.assets.head(file, 30);
            Rect box = new Rect(left, y, width, Math.min(30, lines.size()) * 18f + 12f);
            ui.gui.rounded(box, Theme.RADIUS, Theme.FIELD);
            ui.gui.pushClip(box);
            float ly = y + 6f;
            for (String line : lines) {
                ui.gui.text(line, left + 6f, ly, 1.35f, Theme.TEXT_MUTED, org.zeronura.engine.ui.Typeface.MONO);
                ly += 18f;
            }
            ui.gui.popClip();
            y += box.height() + 8f;
        }
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String vectorText(Vector3f v) {
        return Ui.format(v.x, false) + ", " + Ui.format(v.y, false) + ", " + Ui.format(v.z, false);
    }

    static float[] parseVector(String text, int count) {
        String[] parts = text.replace("(", "").replace(")", "").split("[,\\s]+");
        List<String> numbers = new ArrayList<>(Arrays.stream(parts).filter(p -> !p.isBlank()).toList());
        if (numbers.size() < count) {
            return null;
        }
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            Double value = Ui.parse(numbers.get(i));
            if (value == null) {
                return null;
            }
            out[i] = value.floatValue();
        }
        return out;
    }
}
