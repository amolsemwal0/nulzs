package org.zeronura.engine.studio;

import org.joml.Vector4f;
import org.zeronura.engine.asset.Material;
import org.zeronura.engine.asset.Model;
import org.zeronura.engine.graphics3d.Model3D;
import org.zeronura.engine.graphics3d.Shape3D;
import org.zeronura.engine.graphics3d.WearsMaterial;
import org.zeronura.engine.render.Mesh;
import org.zeronura.engine.render.Texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A shader to start from that looks like the thing does now: for a model, the material it is mostly drawn in — its
 * picture (written out of the model into {@code assets/materials/pictures} so an Image Texture node can read it) into a
 * Principled BSDF's Base Color, its colour too if it has one; for a shape, its colour.
 */
final class BakedLook {

    private BakedLook() {
    }

    /** The look to start from: the whole thing's, or one part's of a model (null for the whole). */
    static NodeGraph graph(StudioScene studio, NodeCatalog catalog, String name, WearsMaterial wearer, String part) {
        NodeGraph graph = NodeGraph.starter(catalog, name);
        NodeGraph.Node bsdf = null;
        for (NodeGraph.Node node : graph.nodes) {
            if (node.type.equals("ShaderNodeBsdfPrincipled")) {
                bsdf = node;
            }
        }
        if (bsdf == null) {
            return graph;
        }
        if (wearer instanceof Shape3D shape && shape.color != null) {
            setColor(bsdf, linear(shape.color.r()), linear(shape.color.g()), linear(shape.color.b()), 1f);
        } else if (wearer instanceof Model3D model3D && model3D.loaded() != null) {
            fromModel(studio, catalog, graph, bsdf, model3D, part);
        }
        graph.revision = 0;
        return graph;
    }

    private static void fromModel(StudioScene studio, NodeCatalog catalog, NodeGraph graph, NodeGraph.Node bsdf,
                                  Model3D model3D, String part) {
        Model model = model3D.loaded();
        Material material = part == null ? mostUsed(model) : named(model, part);
        if (material == null) {
            return;
        }
        Vector4f base = material.getBaseColor() == null ? new Vector4f(1f) : material.getBaseColor();
        Texture texture = material.getBaseColorTexture();
        boolean pictured = texture != null && texture.getWidth() > 1 && texture.getHeight() > 1;
        String picture = pictured ? writePicture(studio, model3D.path, material, texture) : null;
        NodeCatalog.Entry imageEntry = catalog.byId("ShaderNodeTexImage");
        if (picture == null || imageEntry == null) {
            setColor(bsdf, base.x, base.y, base.z, 1f);
            return;
        }
        NodeGraph.Node image = graph.add(imageEntry, bsdf.x - 340f, bsdf.y);
        image.image = picture;
        boolean white = base.x > 0.99f && base.y > 0.99f && base.z > 0.99f;
        NodeCatalog.Entry mixEntry = catalog.byId("ShaderNodeMix:RGBA");
        if (!white && mixEntry != null) {
            // The picture times the material's colour, as the model is drawn.
            image.x -= 260f;
            NodeGraph.Node mix = graph.add(mixEntry, bsdf.x - 260f, bsdf.y);
            mix.props.put("blend_type", "MULTIPLY");
            graph.applySettings(mix);
            NodeGraph.Socket colour = mix.input("B_Color");
            if (colour != null) {
                colour.value = List.of((double) base.x, (double) base.y, (double) base.z, 1.0);
            }
            graph.connect(image, "Color", mix, "A_Color");
            graph.connect(mix, "Result_Color", bsdf, "Base Color");
        } else {
            graph.connect(image, "Color", bsdf, "Base Color");
        }
        if (hasSeeThrough(texture)) {
            graph.connect(image, "Alpha", bsdf, "Alpha");
        }
    }

    /** The part's material. */
    private static Material named(Model model, String part) {
        for (int i = 0; i < model.getMaterials().size(); i++) {
            if (Model3D.partName(model, i).equals(part)) {
                return model.getMaterials().get(i);
            }
        }
        return mostUsed(model);
    }

    /** The material most of the model's triangles are drawn in, preferring one with a picture. */
    private static Material mostUsed(Model model) {
        List<Material> materials = model.getMaterials();
        if (materials.isEmpty()) {
            return null;
        }
        long[] used = new long[materials.size()];
        for (Mesh mesh : model.getMeshes()) {
            int index = mesh.getMaterialIndex();
            if (index >= 0 && index < used.length) {
                used[index] += mesh.getIndexCount();
            }
        }
        int best = -1;
        for (int i = 0; i < materials.size(); i++) {
            Texture texture = materials.get(i).getBaseColorTexture();
            boolean pictured = texture != null && texture.getWidth() > 1;
            if (pictured && (best < 0 || used[i] > used[best])) {
                best = i;
            }
        }
        if (best < 0) {
            best = 0;
            for (int i = 1; i < materials.size(); i++) {
                if (used[i] > used[best]) {
                    best = i;
                }
            }
        }
        return materials.get(best);
    }

    /**
     * Writes the material's picture out of the model as a PNG beside the project's shaders, and gives its asset path.
     * A picture written before is used again as it is, so painting over it in another program is kept.
     */
    private static String writePicture(StudioScene studio, String modelPath, Material material, Texture texture) {
        String model = modelPath == null ? "model" : modelPath.replace('\\', '/');
        model = model.substring(model.lastIndexOf('/') + 1);
        int dot = model.lastIndexOf('.');
        model = dot > 0 ? model.substring(0, dot) : model;
        String suffix = material.getName().isBlank() ? "" : "_" + material.getName();
        String fileName = (model + suffix).replaceAll("[^A-Za-z0-9_\\-]", "_") + ".png";
        Path target = studio.assetsRoot().resolve("materials").resolve("pictures").resolve(fileName);
        if (!Files.exists(target)) {
            Texture.Pixels pixels = texture.readLevel(0);
            try {
                BufferedImage image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
                ByteBuffer rgba = pixels.rgba();
                for (int y = 0; y < pixels.height(); y++) {
                    for (int x = 0; x < pixels.width(); x++) {
                        int i = (y * pixels.width() + x) * 4;
                        int r = rgba.get(i) & 0xFF;
                        int g = rgba.get(i + 1) & 0xFF;
                        int b = rgba.get(i + 2) & 0xFF;
                        int a = rgba.get(i + 3) & 0xFF;
                        image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                Files.createDirectories(target.getParent());
                ImageIO.write(image, "png", target.toFile());
                studio.assets.refresh();
            } catch (IOException | RuntimeException e) {
                studio.status("Couldn't write the model's picture out for its shader: " + e.getMessage(), true);
                return null;
            } finally {
                pixels.free();
            }
        }
        return studio.assetPath(target);
    }

    /** Whether any of a picture's pixels are see-through, read from a small mip level. */
    private static boolean hasSeeThrough(Texture texture) {
        int level = 0;
        int side = Math.max(texture.getWidth(), texture.getHeight());
        while (side > 64) {
            side /= 2;
            level++;
        }
        Texture.Pixels pixels;
        try {
            pixels = texture.readLevel(level);
        } catch (RuntimeException e) {
            return false;
        }
        try {
            ByteBuffer rgba = pixels.rgba();
            for (int i = 3; i < pixels.width() * pixels.height() * 4; i += 4) {
                if ((rgba.get(i) & 0xFF) < 250) {
                    return true;
                }
            }
            return false;
        } finally {
            pixels.free();
        }
    }

    private static void setColor(NodeGraph.Node bsdf, float r, float g, float b, float a) {
        NodeGraph.Socket socket = bsdf.input("Base Color");
        if (socket != null) {
            socket.value = List.of((double) r, (double) g, (double) b, (double) a);
        }
    }

    /** An sRGB channel as the linear value a shader's colour socket holds. */
    private static float linear(float c) {
        return ShaderPanel.toLinear(c);
    }
}
