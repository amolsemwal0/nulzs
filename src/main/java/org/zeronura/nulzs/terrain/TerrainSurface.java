package org.zeronura.nulzs.terrain;

import org.joml.Vector3f;
import org.zeronura.nulzs.physics.AABB;
import org.zeronura.nulzs.physics.SolidField;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.StaticInstance;
import org.zeronura.nulzs.render.StaticMesh;
import org.zeronura.nulzs.render.TexturedInstance;

import java.util.List;
import java.util.Map;

/**
 * Ground that a 3D world draws and walks on. {@link Terrain} is the endless procedural kind;
 * {@link org.zeronura.nulzs.worldmap.MapTerrain} is a fixed, hand-edited map. {@code World3D} treats them
 * identically.
 */
public interface TerrainSurface extends SolidField, AutoCloseable {

    /** Brings meshes up to date around a point. Called every frame with the camera position. */
    void update(Vector3f center);

    List<StaticMesh> meshes();

    /** Props standing on the terrain: trees, rocks, buildings. */
    List<StaticInstance> instances();

    /**
     * Ground cover growing on the terrain: the tufts a field of tall grass is made of.
     *
     * <p>Apart from {@link #instances} because it is drawn apart from them — cover bends away from whoever
     * walks into it and a building does not, and that is a uniform, so it is a pass of its own. See
     * {@code Renderer.renderGroundCover}.
     */
    default List<StaticInstance> groundCover() {
        return List.of();
    }

    /**
     * Textured props standing on the terrain — buildings in brick and tile — grouped by the material each is drawn in,
     * since that is one pass apiece. See {@code Renderer.renderFoliage}.
     */
    default Map<FoliageMaterial, List<TexturedInstance>> texturedInstances() {
        return Map.of();
    }

    /**
     * Water of the terrain's own, drawn with the sea after everything solid and dressed as it is: rivers and lakes that
     * lie above the {@linkplain #waterLevel sea}, and their falls. Drawn whether or not there is a sea.
     */
    default List<StaticInstance> waterMeshes() {
        return List.of();
    }

    /**
     * Props far enough off to be drawn as pictures of themselves ({@link org.zeronura.nulzs.render.LitImpostor}): each
     * picture, and the model matrix of every prop drawn as it — its feet at the matrix's origin, at its scale. They
     * cast no shadow: they stand beyond the reach of the sun's shadow map.
     */
    default Map<org.zeronura.nulzs.render.LitImpostor.Sprite, List<org.joml.Matrix4f>> impostors() {
        return Map.of();
    }

    /**
     * A count that changes whenever anything this draws does — a chunk built, an object gathered or let go, one moved
     * where it stands — read after the lists above have been asked for this frame; or -1 for a terrain that keeps no
     * such count. While it is unchanged a world may keep the shadows the terrain's own casters threw and not draw them
     * again: see {@code ShadowMap.beginCached}.
     */
    default long drawnVersion() {
        return -1L;
    }

    /** Solid boxes characters collide with, besides the ground itself. */
    default List<AABB> colliders() {
        return List.of();
    }

    float heightAt(float x, float z);

    boolean hasWater();

    float waterLevel();

    @Override
    void close();
}
