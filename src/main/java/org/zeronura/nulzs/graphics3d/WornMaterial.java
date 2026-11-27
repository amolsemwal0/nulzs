package org.zeronura.nulzs.graphics3d;

import org.zeronura.nulzs.core.Assets;
import org.zeronura.nulzs.nodes.NodePackage;
import org.zeronura.nulzs.nodes.render.NodeMaterial;
import org.zeronura.nulzs.nodes.render.NodeMaterials;
import org.zeronura.nulzs.scene.Scene;

import java.util.Map;

/**
 * The shader a {@link WearsMaterial} is drawn in this frame, from its settings: a saved one through
 * {@link MaterialLibrary}, shared; its own compiled here, once for each version of it.
 */
final class WornMaterial {

    private Map<String, Object> ownFrom;
    private boolean ownTried;
    private NodeMaterials ownCompiled;
    private NodeMaterial own;
    private String problem;

    /** The shader to draw in, or null for the thing's own look (none chosen, or the chosen one can't be had). */
    NodeMaterial resolve(Scene scene, String material, Map<String, Object> ownShader) {
        if (material == null || material.isBlank() || scene == null || scene.engine() == null) {
            return null;
        }
        if (WearsMaterial.OWN.equals(material.trim())) {
            if (!ownTried || ownShader != ownFrom) {
                compileOwn(scene.assets(), ownShader);
            }
            return own;
        }
        NodeMaterial saved = MaterialLibrary.get(scene.assets(), material, null);
        problem = saved == null ? MaterialLibrary.problem(scene.assets(), material, null) : null;
        return saved;
    }

    private void compileOwn(Assets assets, Map<String, Object> json) {
        release();
        ownTried = true;
        ownFrom = json;
        if (json == null) {
            problem = "It has no shader of its own yet";
            return;
        }
        try {
            NodePackage pkg = NodePackage.fromJson(assets.resolve(WearsMaterial.OWN_FILE), json);
            ownCompiled = new NodeMaterials(pkg);
            String which = pkg.materials().keySet().stream().findFirst().orElse("");
            own = ownCompiled.get(which);
            problem = null;
        } catch (RuntimeException e) {
            problem = e.getMessage();
            System.err.println("[Material] Could not compile a shader of its own: " + e.getMessage());
        }
    }

    /** Why the chosen shader isn't drawn, or null. */
    String problem() {
        return problem;
    }

    void release() {
        if (ownCompiled != null) {
            ownCompiled.delete();
        }
        ownCompiled = null;
        own = null;
        ownFrom = null;
        ownTried = false;
    }
}
