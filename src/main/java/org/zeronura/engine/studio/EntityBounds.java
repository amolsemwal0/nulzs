package org.zeronura.engine.studio;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.asset.Model;
import org.zeronura.engine.graphics2d.CameraView2D;
import org.zeronura.engine.graphics2d.SpriteRenderer;
import org.zeronura.engine.graphics3d.BoxCollider3D;
import org.zeronura.engine.graphics3d.CameraView3D;
import org.zeronura.engine.graphics3d.Model3D;
import org.zeronura.engine.graphics3d.ModelRenderer;
import org.zeronura.engine.graphics3d.Shape3D;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.Entity;

/** The box an entity takes up in its own space, for picking it in the viewport, outlining it, and framing it. */
final class EntityBounds {

    private EntityBounds() {
    }

    /** Fills {@code min} and {@code max} with the entity's own box before its transform. False if it has no size. */
    static boolean local(Entity entity, Vector3f min, Vector3f max) {
        boolean any = false;
        Vector3f half = new Vector3f();
        for (Component component : entity.components()) {
            if (!component.isEnabled()) {
                continue;
            }
            if (component instanceof Shape3D shape) {
                shape.halfExtents(half);
                any = grow(min, max, -half.x, -half.y, -half.z, half.x, half.y, half.z, any);
            } else if (component instanceof ModelRenderer renderer && renderer.model != null) {
                Model model = renderer.model;
                Vector3f a = model.getBoundsMin();
                Vector3f b = model.getBoundsMax();
                any = grow(min, max, a.x, a.y, a.z, b.x, b.y, b.z, any);
            } else if (component instanceof SpriteRenderer sprite) {
                float x0 = -sprite.pivotX * sprite.width;
                float y0 = -sprite.pivotY * sprite.height;
                any = grow(min, max, x0, y0, -0.001f, x0 + sprite.width, y0 + sprite.height, 0.001f, any);
            } else if (component instanceof org.zeronura.engine.graphics3d.PathSpline path) {
                Vector3f a = new Vector3f();
                Vector3f b = new Vector3f();
                if (path.bounds(a, b)) {
                    any = grow(min, max, a.x, a.y, a.z, b.x, b.y, b.z, any);
                }
            } else if (component instanceof org.zeronura.engine.graphics3d.Terrain3D terrain) {
                // Its box in the world, brought into the entity's space (the terrain doesn't turn or scale with it).
                Vector3f a = new Vector3f();
                Vector3f b = new Vector3f();
                terrain.bounds(a, b);
                Vector3f at = entity.transform().worldPosition(new Vector3f());
                any = grow(min, max, a.x - at.x, a.y - at.y, a.z - at.z, b.x - at.x, b.y - at.y, b.z - at.z, any);
            } else if (component instanceof org.zeronura.engine.graphics3d.WorldMap3D shownMap) {
                // The same, for a map: it lies where its game puts it, whatever its entity does.
                Vector3f a = new Vector3f();
                Vector3f b = new Vector3f();
                if (shownMap.bounds(a, b)) {
                    Vector3f at = entity.transform().worldPosition(new Vector3f());
                    any = grow(min, max, a.x - at.x, a.y - at.y, a.z - at.z, b.x - at.x, b.y - at.y, b.z - at.z, any);
                }
            } else if (component instanceof org.zeronura.engine.graphics2d.TileMap2D tiles) {
                any = grow(min, max, 0f, 0f, -0.001f, tiles.columns * tiles.cellSize, tiles.rows * tiles.cellSize, 0.001f, any);
            } else if (component instanceof org.zeronura.engine.graphics3d.SplineTree tree) {
                Vector3f a = new Vector3f();
                Vector3f b = new Vector3f();
                if (tree.bounds(a, b)) {
                    any = grow(min, max, a.x, a.y, a.z, b.x, b.y, b.z, any);
                }
            } else if (component instanceof BoxCollider3D box) {
                Vector3f o = box.offset;
                Vector3f h = box.halfExtents;
                any = grow(min, max, o.x - h.x, o.y - h.y, o.z - h.z, o.x + h.x, o.y + h.y, o.z + h.z, any);
            }
        }
        return any;
    }

    private static boolean grow(Vector3f min, Vector3f max, float x0, float y0, float z0, float x1, float y1, float z1,
                                boolean any) {
        if (!any) {
            min.set(x0, y0, z0);
            max.set(x1, y1, z1);
        } else {
            min.min(new Vector3f(x0, y0, z0));
            max.max(new Vector3f(x1, y1, z1));
        }
        return true;
    }

    /** The eight corners of the entity's box in the world, numbered as bits x, y, z; null if it has no size. */
    static Vector3f[] worldCorners(Entity entity) {
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        if (!local(entity, min, max)) {
            return null;
        }
        Matrix4f world = entity.transform().worldMatrix(new Matrix4f());
        Vector3f[] corners = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = world.transformPosition(new Vector3f((i & 1) == 0 ? min.x : max.x, (i & 2) == 0 ? min.y : max.y,
                    (i & 4) == 0 ? min.z : max.z));
        }
        return corners;
    }

    /**
     * Where a ray first meets the entity, as a distance along it, or NaN for a miss. An entity with no size is a small
     * ball round its position so it can still be clicked.
     */
    static float hit(Entity entity, Vector3f origin, Vector3f direction, float emptyRadius) {
        org.zeronura.engine.graphics3d.WorldMap3D shownMap = entity.get(org.zeronura.engine.graphics3d.WorldMap3D.class);
        if (shownMap != null && shownMap.isEnabled() && shownMap.ready()) {
            // A map is met where the ray meets its ground, not its box, which would stand in front of all on it.
            Vector3f at = new Vector3f();
            return org.zeronura.engine.worldmap.TerrainRay.intersect(shownMap.map(), origin, direction, 20000f, at)
                    ? at.distance(origin) : Float.NaN;
        }
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        Matrix4f world = entity.transform().worldMatrix(new Matrix4f());
        if (!local(entity, min, max)) {
            Vector3f centre = world.getTranslation(new Vector3f());
            Vector3f to = new Vector3f(centre).sub(origin);
            float along = to.dot(direction);
            if (along < 0f) {
                return Float.NaN;
            }
            float off = to.sub(new Vector3f(direction).mul(along)).length();
            return off <= emptyRadius ? along : Float.NaN;
        }
        // Into the entity's own space, where its box is square to the axes.
        Matrix4f inverse = new Matrix4f(world).invert();
        Vector3f o = inverse.transformPosition(new Vector3f(origin));
        Vector3f d = inverse.transformDirection(new Vector3f(direction));
        float tMin = -Float.MAX_VALUE;
        float tMax = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            float oa = o.get(axis);
            float da = d.get(axis);
            float lo = min.get(axis) - 1e-3f;
            float hi = max.get(axis) + 1e-3f;
            if (Math.abs(da) < 1e-9f) {
                if (oa < lo || oa > hi) {
                    return Float.NaN;
                }
                continue;
            }
            float t1 = (lo - oa) / da;
            float t2 = (hi - oa) / da;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (tMax < Math.max(0f, tMin)) {
            return Float.NaN;
        }
        float t = Math.max(0f, tMin);
        Vector3f hitPoint = world.transformPosition(new Vector3f(d).mul(t).add(o));
        return hitPoint.distance(origin);
    }

    /** The centre and radius of everything in some entities, for framing them; false if there is nothing. */
    static boolean sphere(Iterable<Entity> entities, Vector3f centre, float[] radius) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        boolean any = false;
        for (Entity entity : entities) {
            Vector3f[] corners = worldCorners(entity);
            if (corners == null) {
                Vector3f p = entity.transform().worldPosition(new Vector3f());
                min.min(new Vector3f(p).sub(0.5f, 0.5f, 0.5f));
                max.max(new Vector3f(p).add(0.5f, 0.5f, 0.5f));
            } else {
                for (Vector3f corner : corners) {
                    min.min(corner);
                    max.max(corner);
                }
            }
            any = true;
        }
        if (!any) {
            return false;
        }
        centre.set(min).add(max).mul(0.5f);
        radius[0] = Math.max(0.25f, new Vector3f(max).sub(min).length() * 0.5f);
        return true;
    }

    /** The icon the hierarchy shows for an entity, from what it carries. */
    static Icon iconOf(Entity entity) {
        for (Component component : entity.components()) {
            if (component instanceof CameraView3D || component instanceof CameraView2D) {
                return Icon.CAMERA;
            }
        }
        for (Component component : entity.components()) {
            if (component instanceof org.zeronura.engine.graphics3d.PathSpline) {
                return Icon.PATH;
            }
            if (component instanceof org.zeronura.engine.graphics3d.SplineTree) {
                return Icon.TREE;
            }
            if (component instanceof org.zeronura.engine.graphics3d.Terrain3D
                    || component instanceof org.zeronura.engine.graphics3d.WorldMap3D) {
                return Icon.TERRAIN;
            }
            if (component instanceof org.zeronura.engine.graphics2d.TileMap2D) {
                return Icon.TILEMAP;
            }
            if (component instanceof Model3D || component instanceof ModelRenderer) {
                return Icon.MODEL;
            }
            if (component instanceof Shape3D) {
                return Icon.SHAPE;
            }
            if (component instanceof SpriteRenderer) {
                return Icon.SPRITE;
            }
        }
        return Icon.ENTITY;
    }
}
