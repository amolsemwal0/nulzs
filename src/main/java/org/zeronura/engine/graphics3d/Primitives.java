package org.zeronura.engine.graphics3d;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.render.StaticMesh;

/**
 * One-line meshes for prototyping and blockouts. Each returns a new GPU mesh the caller owns — pass it to
 * {@link World3D#own} to have it deleted with the scene.
 *
 * <p>Boxes, spheres and planes are centred on the origin; cylinders, cones and capsules stand on it, so a
 * capsule placed at a character's feet position is exactly where the character is.
 */
public final class Primitives {

    private Primitives() {
    }

    public static StaticMesh box(float width, float height, float depth, Color color) {
        return new MeshBuilder3D().box(0f, 0f, 0f, width, height, depth, color).build();
    }

    public static StaticMesh cube(float size, Color color) {
        return box(size, size, size, color);
    }

    public static StaticMesh plane(float width, float depth, Color color) {
        return new MeshBuilder3D().plane(0f, 0f, 0f, width, depth, color).build();
    }

    public static StaticMesh sphere(float radius, Color color) {
        return new MeshBuilder3D().sphere(0f, 0f, 0f, radius, 16, 24, color).build();
    }

    public static StaticMesh cylinder(float radius, float height, Color color) {
        return new MeshBuilder3D().cylinder(0f, 0f, 0f, radius, height, 24, color, true).build();
    }

    public static StaticMesh cone(float radius, float height, Color color) {
        return new MeshBuilder3D().cone(0f, 0f, 0f, radius, height, 24, color).build();
    }

    public static StaticMesh capsule(float radius, float height, Color color) {
        return new MeshBuilder3D().capsule(0f, 0f, 0f, radius, height, 24, color).build();
    }
}
