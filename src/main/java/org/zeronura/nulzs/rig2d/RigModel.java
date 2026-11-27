package org.zeronura.nulzs.rig2d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A 2.5D rig: a layered painting cut into art meshes, hung on warp and rotation deformers, posed by parameters through
 * keyforms, given follow-through by physics and brought to life by motions — the Live2D way of animating a still
 * illustration. Everything is measured in the art's canvas pixels, y down.
 *
 * <p>A model is shared: draw it many times through a {@link RigInstance} each, which holds the pose. Load and save one
 * with {@link RigFiles}; make one from a layered file with {@link AutoRig}. After changing the lists, call
 * {@link #resolve} before posing.
 */
public final class RigModel {

    public int canvasWidth;
    public int canvasHeight;
    /** The canvas point that stands on the ground: between the feet, or under the belly. */
    public float pivotX;
    public float pivotY;
    /** Which way the art looks: -1 to the left of the canvas, 1 to the right. Motions that lunge go this way. */
    public int facing = -1;
    /** The layered file the art comes from, relative to the rig file's folder. */
    public String art = "";

    public final List<RigParameter> parameters = new ArrayList<>();
    public final List<Deformer> deformers = new ArrayList<>();
    public final List<ArtMesh> meshes = new ArrayList<>();
    public final List<RigPhysics> physics = new ArrayList<>();
    public final Map<String, RigMotion> motions = new LinkedHashMap<>();
    /** The art's layers, bottom first. Not saved in the rig file: read from {@link #art}. */
    public final List<RigLayer> layers = new ArrayList<>();

    /** Bumped whenever something changes that a cached drawing (the texture atlas) depends on. */
    public int revision;

    private final Map<String, Integer> parameterIndex = new HashMap<>();
    private final Map<String, Integer> deformerIndex = new HashMap<>();
    private int[] deformerOrder = new int[0];
    private float[] restBounds;

    public int parameterIndex(String id) {
        Integer index = parameterIndex.get(id);
        return index == null ? -1 : index;
    }

    public RigParameter parameter(String id) {
        int index = parameterIndex(id);
        return index < 0 ? null : parameters.get(index);
    }

    public int deformerIndex(String id) {
        Integer index = id == null ? null : deformerIndex.get(id);
        return index == null ? -1 : index;
    }

    public Deformer deformer(String id) {
        int index = deformerIndex(id);
        return index < 0 ? null : deformers.get(index);
    }

    public ArtMesh mesh(String id) {
        for (ArtMesh mesh : meshes) {
            if (mesh.id.equals(id)) {
                return mesh;
            }
        }
        return null;
    }

    public RigLayer layer(String path) {
        for (RigLayer layer : layers) {
            if (layer.path().equals(path)) {
                return layer;
            }
        }
        return null;
    }

    /** Adds a parameter, or replaces the one with its id. */
    public RigParameter addParameter(RigParameter parameter) {
        int index = parameterIndex(parameter.id());
        if (index >= 0) {
            parameters.set(index, parameter);
        } else {
            parameters.add(parameter);
        }
        resolve();
        return parameter;
    }

    /** Deformers in the order they are posed: every parent before its children. */
    int[] deformerOrder() {
        return deformerOrder;
    }

    /**
     * Looks up every name the parts give each other — parameters, parents, layers — and orders the deformers parents
     * first. A parent that does not exist, or that would make a loop, is dropped (the part hangs from the canvas).
     */
    public void resolve() {
        parameterIndex.clear();
        for (int i = 0; i < parameters.size(); i++) {
            parameterIndex.put(parameters.get(i).id(), i);
        }
        deformerIndex.clear();
        for (int i = 0; i < deformers.size(); i++) {
            deformerIndex.put(deformers.get(i).id, i);
        }
        for (Deformer deformer : deformers) {
            deformer.parentIndex = deformerIndex(deformer.parent);
            deformer.keyforms.resolve(this);
        }
        // Break loops: walk up from each deformer; if it comes back to itself, cut its parent.
        for (int i = 0; i < deformers.size(); i++) {
            int at = deformers.get(i).parentIndex;
            int steps = 0;
            while (at >= 0 && steps <= deformers.size()) {
                if (at == i) {
                    deformers.get(i).parentIndex = -1;
                    break;
                }
                at = deformers.get(at).parentIndex;
                steps++;
            }
        }
        List<Integer> order = new ArrayList<>();
        boolean[] placed = new boolean[deformers.size()];
        boolean progress = true;
        while (order.size() < deformers.size() && progress) {
            progress = false;
            for (int i = 0; i < deformers.size(); i++) {
                int parent = deformers.get(i).parentIndex;
                if (!placed[i] && (parent < 0 || placed[parent])) {
                    placed[i] = true;
                    order.add(i);
                    progress = true;
                }
            }
        }
        deformerOrder = order.stream().mapToInt(Integer::intValue).toArray();
        for (ArtMesh mesh : meshes) {
            mesh.parentIndex = deformerIndex(mesh.parent);
            mesh.layerIndex = -1;
            for (int i = 0; i < layers.size(); i++) {
                if (layers.get(i).path().equals(mesh.layer)) {
                    mesh.layerIndex = i;
                    break;
                }
            }
            mesh.keyforms.resolve(this);
        }
        for (RigPhysics p : physics) {
            p.inputIndices = new int[p.inputs.size()];
            for (int i = 0; i < p.inputs.size(); i++) {
                p.inputIndices[i] = parameterIndex(p.inputs.get(i).parameter());
            }
            p.outputIndex = parameterIndex(p.output);
        }
        for (RigMotion motion : motions.values()) {
            for (RigMotion.Curve curve : motion.curves) {
                curve.index = parameterIndex(curve.parameter);
            }
        }
        restBounds = null;
        revision++;
    }

    /**
     * The box round every visible pixel of the art at rest, {x, y, width, height} in canvas pixels: how big the
     * creature is, as opposed to its canvas.
     */
    public float[] restBounds() {
        if (restBounds == null) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (ArtMesh mesh : meshes) {
                RigLayer layer = mesh.layerIndex >= 0 ? layers.get(mesh.layerIndex) : null;
                if (layer == null) {
                    continue;
                }
                RigLayer trimmed = layer.trimmed();
                if (trimmed == null) {
                    continue;
                }
                minX = Math.min(minX, trimmed.left());
                minY = Math.min(minY, trimmed.top());
                maxX = Math.max(maxX, trimmed.left() + trimmed.width());
                maxY = Math.max(maxY, trimmed.top() + trimmed.height());
            }
            restBounds = maxX < minX
                    ? new float[]{0f, 0f, Math.max(1, canvasWidth), Math.max(1, canvasHeight)}
                    : new float[]{minX, minY, maxX - minX, maxY - minY};
        }
        return restBounds;
    }

    /** Names something new: {@code base}, or {@code base 2}, {@code base 3}... if that is taken among {@code taken}. */
    public static String unique(String base, java.util.Collection<String> taken) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String name = base + " " + n;
            if (!taken.contains(name)) {
                return name;
            }
        }
    }

    public List<String> deformerIds() {
        List<String> ids = new ArrayList<>();
        for (Deformer d : deformers) {
            ids.add(d.id);
        }
        return ids;
    }

    public List<String> meshIds() {
        List<String> ids = new ArrayList<>();
        for (ArtMesh m : meshes) {
            ids.add(m.id);
        }
        return ids;
    }
}
