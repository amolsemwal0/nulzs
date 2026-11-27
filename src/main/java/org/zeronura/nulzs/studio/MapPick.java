package org.zeronura.nulzs.studio;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.zeronura.nulzs.graphics3d.WorldMap3D;
import org.zeronura.nulzs.scene.Entity;
import org.zeronura.nulzs.worldmap.MapObject;
import org.zeronura.nulzs.worldmap.MapTree;
import org.zeronura.nulzs.worldmap.PrefabCatalog;

import java.util.List;

/**
 * An object or a tree standing on a map, picked in the Scene view. The map's entity is what is selected — the hierarchy
 * shows it so — and this says which of the things on its map. It holds on to it by its place in the map's list as well as
 * by itself: an undo puts back copies of what the map held, and the copy in its place is the same thing.
 */
final class MapPick {

    /** The id of the map's entity in the scene: entities are made again with every undo, their ids stay. */
    final String entityId;
    final boolean tree;
    private int index;
    private Object item;

    private MapPick(String entityId, boolean tree, int index, Object item) {
        this.entityId = entityId;
        this.tree = tree;
        this.index = index;
        this.item = item;
    }

    /** A pick of {@code item}, an object or a tree on the map {@code entity} shows; null if it isn't on it. */
    static MapPick of(SceneDocument doc, Entity entity, Object item) {
        WorldMap3D shown = entity == null ? null : entity.get(WorldMap3D.class);
        if (shown == null || shown.map() == null || !(item instanceof MapObject || item instanceof MapTree)) {
            return null;
        }
        boolean isTree = item instanceof MapTree;
        int at = indexOf(isTree ? shown.map().trees() : shown.map().objects(), item);
        return at < 0 ? null : new MapPick(doc.idOf(entity), isTree, at, item);
    }

    private static int indexOf(List<?> list, Object item) {
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k) == item) {
                return k;
            }
        }
        return -1;
    }

    /** The map's entity, while it is the one thing selected; null otherwise. */
    Entity entity(SceneDocument doc) {
        Entity entity = doc.byId.get(entityId);
        if (entity == null || entity.isDestroyed() || doc.selection.size() != 1 || doc.selection.getFirst() != entity) {
            return null;
        }
        return entity;
    }

    /** The map component it stands on, while its entity is the one selected and the map is loaded; null otherwise. */
    WorldMap3D shown(SceneDocument doc) {
        Entity entity = entity(doc);
        WorldMap3D shown = entity == null ? null : entity.get(WorldMap3D.class);
        return shown != null && shown.isEnabled() && shown.ready() ? shown : null;
    }

    /** The object or the tree as the map holds it now — found again after an undo — or null if it is gone. */
    Object item(SceneDocument doc) {
        WorldMap3D shown = shown(doc);
        if (shown == null) {
            return null;
        }
        List<?> list = tree ? shown.map().trees() : shown.map().objects();
        if (index < list.size() && list.get(index) == item) {
            return item;
        }
        int at = indexOf(list, item);
        if (at >= 0) {
            index = at;
            return item;
        }
        if (index < list.size()) {
            // The map's list was put back by an undo or a redo: what is in its place now is the copy of it.
            item = list.get(index);
            return item;
        }
        return null;
    }

    MapObject object(SceneDocument doc) {
        return item(doc) instanceof MapObject object ? object : null;
    }

    MapTree tree(SceneDocument doc) {
        return item(doc) instanceof MapTree mapTree ? mapTree : null;
    }

    /** Whether this picks that: the same thing on the same map. */
    boolean is(SceneDocument doc, Entity entity, Object other) {
        return other != null && entityId.equals(doc.idOf(entity)) && item(doc) == other;
    }

    int index() {
        return index;
    }

    /** What it is called: its prefab's name, or "Tree". */
    String name(SceneDocument doc) {
        if (tree) {
            MapTree picked = tree(doc);
            return picked != null && picked.preset != null ? "Tree: " + picked.preset : "Tree";
        }
        MapObject object = object(doc);
        WorldMap3D shown = shown(doc);
        if (object == null || shown == null) {
            return "Map object";
        }
        PrefabCatalog.Prefab prefab = shown.prefabOf(object);
        return prefab != null && prefab.name() != null && !prefab.name().isBlank() ? prefab.name() : object.prefab;
    }

    /** Where it is in the world and the box it fills in its own space; false if it is gone. */
    boolean box(SceneDocument doc, Matrix4f world, Vector3f min, Vector3f max) {
        WorldMap3D shown = shown(doc);
        Object now = item(doc);
        if (shown == null || now == null) {
            return false;
        }
        return now instanceof MapTree mapTree ? shown.treeBox(mapTree, world, min, max)
                : shown.objectBox((MapObject) now, world, min, max);
    }

    /** The eight corners of its box in the world, numbered as bits x, y, z; null if it is gone. */
    Vector3f[] corners(SceneDocument doc) {
        Matrix4f world = new Matrix4f();
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        return box(doc, world, min, max) ? corners(world, min, max) : null;
    }

    static Vector3f[] corners(Matrix4f world, Vector3f min, Vector3f max) {
        Vector3f[] corners = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = world.transformPosition(new Vector3f((i & 1) == 0 ? min.x : max.x, (i & 2) == 0 ? min.y : max.y,
                    (i & 4) == 0 ? min.z : max.z));
        }
        return corners;
    }

    /** What the gizmo moves for it; null if it is gone. */
    TransformGizmo.Target target(SceneDocument doc) {
        WorldMap3D shown = shown(doc);
        Object now = item(doc);
        if (shown == null || now == null) {
            return null;
        }
        return now instanceof MapTree mapTree ? treeTarget(shown, mapTree) : objectTarget(shown, (MapObject) now);
    }

    /**
     * An object as the gizmo moves it. Its height is kept as how far above the ground it stands, so moving it along the
     * ground keeps it on the ground, and only moving it up or down raises or sinks it.
     */
    private static TransformGizmo.Target objectTarget(WorldMap3D shown, MapObject object) {
        return new TransformGizmo.Target() {
            private float startOffset;
            private float startY;

            @Override
            public Matrix4f world(Matrix4f dest) {
                shown.objectBox(object, dest, new Vector3f(), new Vector3f());
                return dest;
            }

            @Override
            public Vector3f scale(Vector3f dest) {
                return dest.set(object.scale);
            }

            @Override
            public void begin() {
                shown.beginEdit();
                startOffset = object.offsetY;
                startY = shown.groundUnder(object) + object.offsetY;
            }

            @Override
            public void moveTo(Vector3f worldPosition) {
                object.x = Math.clamp(worldPosition.x, 0f, shown.map().worldWidth());
                object.z = Math.clamp(worldPosition.z, 0f, shown.map().worldDepth());
                object.offsetY = startOffset + (worldPosition.y - startY);
                shown.objectChanged(object);
            }

            @Override
            public void turnTo(Quaternionf worldRotation) {
                // The map turns an object about y, then x the other way (its pitch), then z (its roll).
                Vector3f angles = worldRotation.getEulerAnglesYXZ(new Vector3f());
                object.yaw = angles.y;
                object.pitch = -angles.x;
                object.roll = angles.z;
                shown.objectChanged(object);
            }

            @Override
            public void scaleTo(Vector3f scale) {
                object.scale = Math.max(0.01f, scale.x);
                shown.objectChanged(object);
            }

            @Override
            public boolean oneScale() {
                return true;
            }
        };
    }

    /** A tree as the gizmo moves it: along the ground, which it always stands on, and turned about its trunk. */
    private static TransformGizmo.Target treeTarget(WorldMap3D shown, MapTree mapTree) {
        return new TransformGizmo.Target() {
            @Override
            public Matrix4f world(Matrix4f dest) {
                shown.treeBox(mapTree, dest, new Vector3f(), new Vector3f());
                return dest;
            }

            @Override
            public Vector3f scale(Vector3f dest) {
                return dest.set(mapTree.scale);
            }

            @Override
            public void begin() {
                shown.beginEdit();
            }

            @Override
            public void moveTo(Vector3f worldPosition) {
                mapTree.x = Math.clamp(worldPosition.x, 0f, shown.map().worldWidth());
                mapTree.z = Math.clamp(worldPosition.z, 0f, shown.map().worldDepth());
                shown.treeChanged(mapTree, false);
            }

            @Override
            public void turnTo(Quaternionf worldRotation) {
                mapTree.yaw = worldRotation.getEulerAnglesYXZ(new Vector3f()).y;
                shown.treeChanged(mapTree, false);
            }

            @Override
            public void scaleTo(Vector3f scale) {
                mapTree.scale = Math.max(0.05f, scale.x);
                shown.treeChanged(mapTree, false);
            }

            @Override
            public boolean upright() {
                return true;
            }

            @Override
            public boolean oneScale() {
                return true;
            }
        };
    }
}
