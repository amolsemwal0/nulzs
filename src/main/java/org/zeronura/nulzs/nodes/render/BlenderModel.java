package org.zeronura.nulzs.nodes.render;

import org.joml.Matrix4f;
import org.zeronura.nulzs.nodes.geometry.GeometryOps;
import org.zeronura.nulzs.nodes.geometry.GeometrySet;
import org.zeronura.nulzs.nodes.geometry.InstanceGeometry;
import org.zeronura.nulzs.nodes.geometry.MeshGeometry;
import org.zeronura.nulzs.nodes.tex.Hash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Geometry from a node package ready to draw: meshes on the GPU, each with its compiled material, and where to put
 * them. Top-level instances (a scatter of rocks, the pebbles along a path) stay instances: each distinct piece is
 * uploaded once and drawn with one instanced call per material, however many times it is placed.
 *
 * <p>Build one with {@link #build}, draw it with a {@link BlenderMeshRenderer}. Several renderers can share one
 * model. It owns its meshes (not its materials, which belong to their {@link NodeMaterials}).
 */
public final class BlenderModel {

    /** Where one placement goes, relative to the model, and the random value Object Info gives it. */
    public record Placement(Matrix4f matrix, float random) {
    }

    /** One mesh in one material, at one or more placements. */
    public record Part(NodeMesh mesh, NodeMaterial material, List<Placement> placements, float[] texspaceLocation,
                       float[] texspaceSize) {
    }

    public final List<Part> parts;

    private BlenderModel(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    /**
     * Uploads a geometry for drawing. {@code uvName} and {@code colorName} choose the UV map and colour attribute
     * the materials' default UV and Vertex Color read (null for UVMap and the first colour attribute).
     * {@code texspace} is the source mesh's texture space from Blender, or null to use the bounds.
     */
    public static BlenderModel build(NodeMaterialSource materials, GeometrySet geometry, String uvName, String colorName,
                                     float[][] texspace) {
        List<Part> parts = new ArrayList<>();
        GeometrySet real = geometry.withInstances(null);
        if (real.mesh != null && real.mesh.faceCount() > 0) {
            addMesh(materials, real.mesh, List.of(new Placement(new Matrix4f(), 0f)), uvName, colorName, texspace, parts);
        }
        InstanceGeometry instances = geometry.instances;
        if (instances != null && instances.count() > 0) {
            Map<Integer, List<Placement>> byReference = new HashMap<>();
            int[] handles = instances.handles();
            float[] transforms = instances.transforms();
            for (int i = 0; i < instances.count(); i++) {
                float random = Hash.hashToFloat(i, handles[i]);
                byReference.computeIfAbsent(handles[i], h -> new ArrayList<>())
                        .add(new Placement(BlenderSpace.matrix(transforms, 16 * i), random));
            }
            for (Map.Entry<Integer, List<Placement>> entry : byReference.entrySet()) {
                GeometrySet reference = GeometryOps.realize(instances.references.get(entry.getKey()));
                if (reference.mesh != null && reference.mesh.faceCount() > 0) {
                    addMesh(materials, reference.mesh, entry.getValue(), uvName, colorName, null, parts);
                }
            }
        }
        return new BlenderModel(parts);
    }

    private static void addMesh(NodeMaterialSource materials, MeshGeometry mesh, List<Placement> placements, String uvName,
                                String colorName, float[][] texspace, List<Part> parts) {
        Map<Integer, NodeMaterial> bySlot = new HashMap<>();
        Map<Integer, List<String>> attributes = new HashMap<>();
        int slots = Math.max(1, mesh.materials.size());
        for (int slot = 0; slot < slots; slot++) {
            String name = slot < mesh.materials.size() ? mesh.materials.get(slot) : null;
            NodeMaterial material = materials.get(name);
            bySlot.put(slot, material);
            attributes.put(slot, material.attributes);
        }
        for (RenderMeshes.Data data : RenderMeshes.build(mesh, attributes, uvName, colorName, texspace)) {
            NodeMaterial material = bySlot.getOrDefault(data.slot(), materials.fallback());
            NodeMesh gpu = new NodeMesh(data.vertices(), data.indices(), data.attributeCount(), data.centre());
            parts.add(new Part(gpu, material, placements, data.texspaceLocation(), data.texspaceSize()));
        }
    }

    public int triangleCount() {
        int total = 0;
        for (Part part : parts) {
            total += part.mesh().indexCount() / 3 * part.placements().size();
        }
        return total;
    }

    public void delete() {
        for (Part part : parts) {
            part.mesh().delete();
        }
    }
}
