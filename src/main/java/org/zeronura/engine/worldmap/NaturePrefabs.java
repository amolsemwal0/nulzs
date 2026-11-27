package org.zeronura.engine.worldmap;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics3d.MeshBuilder3D;

/**
 * A starter set of low-poly props — trees, rocks, plants, fences, buildings — so a map can be dressed with
 * no art assets. Games add their own prefabs alongside these.
 */
public final class NaturePrefabs {

    private static final Color BARK = Color.rgb(0x6B4A2F);
    private static final Color WOOD = Color.rgb(0x9A6B40);
    private static final Color STONE = Color.rgb(0x8A857D);
    private static final Color DARK_STONE = Color.rgb(0x6C6862);

    private NaturePrefabs() {
    }

    public static void registerAll(PrefabCatalog catalog) {
        // Trees
        catalog.register("oak", "Oak tree", "Trees", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 0.32f, 2.8f, 8, BARK, false)
                .sphere(0f, 3.9f, 0f, 2.0f, 6, 10, Color.rgb(0x5E9142))
                .sphere(0.9f, 3.3f, 0.5f, 1.3f, 5, 9, Color.rgb(0x6AA04B))
                .sphere(-0.8f, 3.5f, -0.6f, 1.2f, 5, 9, Color.rgb(0x578A3D)), 0.8f, 3f, 0.8f);
        catalog.register("pine", "Pine tree", "Trees", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 0.28f, 2.2f, 8, BARK, false)
                .cone(0f, 1.4f, 0f, 2.0f, 3.0f, 9, Color.rgb(0x2F5D3A))
                .cone(0f, 3.0f, 0f, 1.55f, 2.6f, 9, Color.rgb(0x376B42))
                .cone(0f, 4.5f, 0f, 1.0f, 2.2f, 9, Color.rgb(0x40784A)), 0.8f, 3f, 0.8f);
        catalog.register("birch", "Birch tree", "Trees", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 0.22f, 3.4f, 8, Color.rgb(0xE8E4DA), false)
                .sphere(0f, 4.2f, 0f, 1.4f, 6, 10, Color.rgb(0x9CC95A))
                .sphere(0.5f, 3.6f, 0.3f, 1.0f, 5, 9, Color.rgb(0x8DBB4F)), 0.6f, 3f, 0.6f);
        catalog.register("palm", "Palm tree", "Trees", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 0.25f, 4.5f, 8, Color.rgb(0x8C6A45), false)
                .cone(0f, 4.2f, 0f, 2.4f, 0.9f, 7, Color.rgb(0x4E9A3C))
                .sphere(0f, 4.4f, 0f, 0.45f, 4, 6, Color.rgb(0x6B4A2F)), 0.6f, 3f, 0.6f);
        catalog.register("dead_tree", "Dead tree", "Trees", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 0.3f, 3.2f, 7, Color.rgb(0x5A4A3C), false)
                .cone(0f, 3.2f, 0f, 0.3f, 1.2f, 7, Color.rgb(0x5A4A3C)), 0.7f, 3f, 0.7f);

        // Plants
        catalog.register("bush", "Bush", "Plants", new MeshBuilder3D()
                .sphere(0f, 0.5f, 0f, 0.9f, 5, 8, Color.rgb(0x4F8A3A))
                .sphere(0.6f, 0.4f, 0.2f, 0.6f, 4, 7, Color.rgb(0x5A9842)), 0f, 0f, 0f);
        catalog.register("flowers", "Flowers", "Plants", new MeshBuilder3D()
                .sphere(0f, 0.25f, 0f, 0.18f, 3, 6, Color.rgb(0xE85D75))
                .sphere(0.4f, 0.22f, 0.3f, 0.16f, 3, 6, Color.rgb(0xF2C14E))
                .sphere(-0.35f, 0.24f, 0.2f, 0.17f, 3, 6, Color.rgb(0xB07CF2))
                .sphere(0.1f, 0.2f, -0.4f, 0.15f, 3, 6, Color.WHITE), 0f, 0f, 0f);
        catalog.register("grass_tuft", "Tall grass", "Plants", new MeshBuilder3D()
                .cone(0f, 0f, 0f, 0.18f, 0.9f, 4, Color.rgb(0x4F9B3A))
                .cone(0.25f, 0f, 0.1f, 0.15f, 0.7f, 4, Color.rgb(0x5DAA45))
                .cone(-0.2f, 0f, 0.2f, 0.16f, 0.8f, 4, Color.rgb(0x478F34))
                .cone(0.05f, 0f, -0.25f, 0.14f, 0.65f, 4, Color.rgb(0x58A241)), 0f, 0f, 0f);
        catalog.register("mushroom", "Giant mushroom", "Plants", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 0.25f, 1.1f, 8, Color.rgb(0xEDE3CF), false)
                .sphere(0f, 1.2f, 0f, 0.8f, 5, 10, Color.rgb(0xC8423B)), 0.5f, 1.2f, 0.5f);

        // Rocks
        catalog.register("rock", "Rock", "Rocks", new MeshBuilder3D()
                .sphere(0f, 0.2f, 0f, 0.7f, 4, 6, STONE), 1.1f, 0.8f, 1.1f);
        catalog.register("boulder", "Boulder", "Rocks", new MeshBuilder3D()
                .sphere(0f, 0.8f, 0f, 1.6f, 4, 7, DARK_STONE)
                .sphere(1.0f, 0.5f, 0.6f, 0.9f, 4, 6, STONE), 3f, 2.2f, 3f);
        catalog.register("cliff_rock", "Cliff rock", "Rocks", new MeshBuilder3D()
                .box(0f, 2f, 0f, 3.5f, 4f, 3f, DARK_STONE)
                .box(0.4f, 4.4f, -0.2f, 2.4f, 1f, 2.2f, STONE), 3.5f, 5f, 3f);
        catalog.register("crystal", "Crystal", "Rocks", new MeshBuilder3D()
                .cone(0f, 0f, 0f, 0.5f, 2.2f, 5, new Color(0.45f, 1.1f, 1.3f))
                .cone(0.5f, 0f, 0.2f, 0.3f, 1.3f, 5, new Color(0.55f, 0.9f, 1.4f)), 1f, 2f, 1f);

        // Built
        catalog.register("fence", "Fence", "Built", new MeshBuilder3D()
                .box(-1.9f, 0.55f, 0f, 0.18f, 1.1f, 0.18f, WOOD)
                .box(1.9f, 0.55f, 0f, 0.18f, 1.1f, 0.18f, WOOD)
                .box(0f, 0.8f, 0f, 4f, 0.14f, 0.1f, WOOD)
                .box(0f, 0.4f, 0f, 4f, 0.14f, 0.1f, WOOD), 4f, 1.2f, 0.3f);
        catalog.register("stump", "Stump", "Built", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 0.45f, 0.5f, 9, WOOD, true), 0.9f, 0.5f, 0.9f);
        catalog.register("crate", "Crate", "Built", new MeshBuilder3D()
                .box(0f, 0.5f, 0f, 1f, 1f, 1f, Color.rgb(0xB5834A)), 1f, 1f, 1f);
        catalog.register("house", "House", "Built", new MeshBuilder3D()
                .box(0f, 1.75f, 0f, 6f, 3.5f, 5f, Color.rgb(0xE8DCC2))
                .cone(0f, 3.5f, 0f, 4.6f, 2.6f, 4, Color.rgb(0xB24A3A))
                .box(0f, 1f, 2.52f, 1.1f, 2f, 0.05f, Color.rgb(0x6B4A2F)), 6f, 5f, 5f);
        catalog.register("tower", "Stone tower", "Built", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 2f, 9f, 12, STONE, true)
                .cone(0f, 9f, 0f, 2.6f, 3f, 12, Color.rgb(0x3E5A8A)), 4f, 9f, 4f);
        catalog.register("lamp", "Lamp post", "Built", new MeshBuilder3D()
                .cylinder(0f, 0f, 0f, 0.1f, 2.6f, 6, Color.rgb(0x3A3A40), false)
                .sphere(0f, 2.75f, 0f, 0.25f, 4, 6, new Color(2.2f, 1.9f, 1.1f)), 0.3f, 2.6f, 0.3f);
        catalog.register("bridge", "Bridge", "Built", new MeshBuilder3D()
                .box(0f, 0.15f, 0f, 3f, 0.3f, 10f, WOOD)
                .box(-1.45f, 0.7f, 0f, 0.12f, 0.8f, 10f, WOOD)
                .box(1.45f, 0.7f, 0f, 0.12f, 0.8f, 10f, WOOD), 0f, 0f, 0f);
    }
}
