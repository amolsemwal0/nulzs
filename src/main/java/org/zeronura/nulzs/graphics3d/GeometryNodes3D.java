package org.zeronura.nulzs.graphics3d;

import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.nodes.NodeTree;
import org.zeronura.nulzs.nodes.render.BlenderMeshRenderer;
import org.zeronura.nulzs.nodes.render.BlenderModel;
import org.zeronura.nulzs.nodes.render.GeometryTrees;
import org.zeronura.nulzs.nodes.render.NodeRenderer;
import org.zeronura.nulzs.scene.Asset;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.scene.StoresData;
import org.zeronura.nulzs.scene.Tooltip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Geometry made by a geometry node tree (one built in the editor's Geometry Nodes Tool, saved in
 * {@code assets/geometry}), drawn at its entity and lit and shadowed with the rest of the world. The tree's inputs, the
 * sockets of its Group Input, are this component's settings: each entity sets its own, in the inspector or from code
 * with {@link #set}. The tree runs again whenever they or its file change, off the main thread, the last result shown
 * meanwhile. Set Material in the tree picks saved Shader Tool materials ({@link AssetMaterials}).
 *
 * <pre>{@code
 * entity.add(new GeometryNodes3D("geometry/Rock Pile.nodes.json").set("Count", 40).set("Seed", 7));
 * }</pre>
 */
public class GeometryNodes3D extends BlenderMeshRenderer implements StoresData {

    @Asset({"nodes.json"})
    @Tooltip("A geometry node tree from the Geometry Nodes Tool")
    public String nodes = "";

    /** The inputs set here, by the tree's interface identifier: the others keep the tree's defaults. */
    private final Map<String, Object> inputs = new LinkedHashMap<>();

    /** What the model was made from: the file, when it last changed, and the inputs. */
    private volatile String builtFrom = "";
    private volatile String running = "";
    private volatile GeometryTrees.Result finished;
    private GeometryTrees.Result shown;
    private volatile Thread worker;
    private long checkedAt;
    private long fileTime;

    public GeometryNodes3D() {
        super(null);
    }

    public GeometryNodes3D(String nodes) {
        super(null);
        this.nodes = nodes;
    }

    // --- Inputs ---------------------------------------------------------------------------------------------

    /** The tree's inputs, as its last run found them; empty before it has run. */
    public List<NodeTree.InterfaceSocket> treeInputs() {
        GeometryTrees.Result result = shown;
        if (result == null || result.tree() == null) {
            return List.of();
        }
        List<NodeTree.InterfaceSocket> out = new ArrayList<>();
        for (NodeTree.InterfaceSocket socket : result.tree().inputs()) {
            if (socket.type() != org.zeronura.nulzs.nodes.SocketType.GEOMETRY) {
                out.add(socket);
            }
        }
        return out;
    }

    /** An input's value: the one set here, or null for the tree's default. */
    public Object input(String id) {
        return inputs.get(id);
    }

    /** Sets an input by its identifier ({@code Socket_2}) or by its name as the tree shows it; null goes back to the default. */
    public GeometryNodes3D set(String idOrName, Object value) {
        String id = idOrName;
        for (NodeTree.InterfaceSocket socket : treeInputs()) {
            if (socket.name().equals(idOrName)) {
                id = socket.id();
            }
        }
        if (value == null) {
            inputs.remove(id);
        } else {
            inputs.put(id, value);
        }
        return this;
    }

    /** What went wrong the last time the tree ran, or an empty list. */
    public List<String> problems() {
        GeometryTrees.Result result = shown;
        return result == null ? List.of() : result.problems();
    }

    /** The last run's result: counts, bounds, timing; null before the first has finished. */
    public GeometryTrees.Result result() {
        return shown;
    }

    @Override
    public Object saveData() {
        return new LinkedHashMap<>(inputs);
    }

    @Override
    public void loadData(Object data) {
        inputs.clear();
        if (data instanceof Map<?, ?> map) {
            map.forEach((key, value) -> inputs.put(String.valueOf(key), value));
        }
    }

    // --- Running the tree -----------------------------------------------------------------------------------

    @Override
    protected void preRender(float alpha) {
        if (!scene().has3D()) {
            return;
        }
        World3D world = scene().world3D();
        if (world.pass(NodeRenderer.class) == null) {
            world.addPass(new NodeRenderer(scene()));
        }
        GeometryTrees.Result done = finished;
        if (done != null) {
            finished = null;
            showResult(done);
        }
        String wanted = wanted();
        if (wanted != null && !wanted.equals(builtFrom) && !wanted.equals(running) && worker == null) {
            start(wanted);
        }
    }

    /** What the model should be made from now, or null for nothing (no tree chosen). */
    private String wanted() {
        if (nodes == null || nodes.isBlank()) {
            if (model != null) {
                release();
                builtFrom = "";
            }
            return null;
        }
        long now = System.nanoTime();
        if (now - checkedAt > 1_000_000_000L) {
            checkedAt = now;
            try {
                fileTime = Files.getLastModifiedTime(scene().assets().resolve(nodes.trim())).toMillis();
            } catch (IOException missing) {
                fileTime = -1L;
            }
        }
        return nodes.trim() + "@" + fileTime + " " + Json.compact(inputs);
    }

    private void start(String wanted) {
        running = wanted;
        Path file = scene().assets().resolve(nodes.trim());
        Map<String, Object> given = new LinkedHashMap<>(inputs);
        Thread thread = new Thread(() -> {
            GeometryTrees.Result result = GeometryTrees.run(file, given);
            synchronized (this) {
                finished = result;
                builtFrom = wanted;
                running = "";
                worker = null;
            }
        }, "Geometry nodes: " + file.getFileName());
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void showResult(GeometryTrees.Result result) {
        release();
        shown = result;
        if (result.ok() && !result.geometry().isEmpty()) {
            model = BlenderModel.build(new AssetMaterials(scene().assets()), result.geometry(), null, null, null);
        }
        for (String problem : result.problems()) {
            System.err.println("[GeometryNodes3D] " + nodes + ": " + problem);
        }
    }

    /** Makes the tree run again, as soon as it can: after its file has been changed from outside, say. */
    public void rerun() {
        builtFrom = "";
        checkedAt = 0L;
    }

    private void release() {
        if (model != null) {
            model.delete();
            model = null;
        }
    }

    @Override
    protected void onExitScene(Scene scene) {
        release();
        builtFrom = "";
    }
}
