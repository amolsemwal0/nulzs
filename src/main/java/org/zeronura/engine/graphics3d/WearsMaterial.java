package org.zeronura.engine.graphics3d;

import java.util.List;
import java.util.Map;

/**
 * Something drawn in a material the editor can choose: a {@link Model3D} or a {@link Shape3D}. Its {@code material}
 * setting is one of three things:
 *
 * <ul>
 *   <li>empty: its own look with no shader — a model's materials and textures as baked into its file, a shape's colour;
 *   <li>a saved shader, by asset path ({@code materials/Rock.nodes.json}), shared by everything that names it;
 *   <li>{@link #OWN}: a shader of its own, made for it in the Shader Tool and kept with it in the scene.
 * </ul>
 *
 * <p>A model's {@linkplain #parts parts} — the materials in its file — can each be set apart from the whole, in the same
 * three ways: a model with a wooden seat and iron legs can keep its baked iron and wear a shader on the wood.
 */
public interface WearsMaterial {

    /** The {@code material} setting that means the shader kept with this one. */
    String OWN = "own";

    /** Where a shader of its own finds its pictures from: as if it were a file in the materials folder. */
    String OWN_FILE = "materials/own.nodes.json";

    String material();

    void setMaterial(String material);

    /** Its own shader as a node package, or null if it has none. */
    Map<String, Object> ownShader();

    /** Sets its own shader; a new map each time, so what draws it knows to compile it again. */
    void setOwnShader(Map<String, Object> shader);

    /** What it looks like without a shader, as the editor names it: "Baked" for a model, "Plain colour" for a shape. */
    String unshadedLabel();

    /** Why the chosen shader isn't drawn (its own look is drawn instead), or null. */
    default String materialProblem() {
        return null;
    }

    // --- Parts: a model's materials, each of which can wear something else -------------------------------------------

    /** The parts that can each wear a material of their own: a model's materials, by name. None for a shape. */
    default List<String> parts() {
        return List.of();
    }

    /** What a part wears, like {@link #material}; null when it wears what the whole does. */
    default String partMaterial(String part) {
        return null;
    }

    /** Sets what a part wears; null for what the whole does. */
    default void setPartMaterial(String part, String material) {
    }

    /** A part's own shader, or null. */
    default Map<String, Object> partShader(String part) {
        return null;
    }

    default void setPartShader(String part, Map<String, Object> shader) {
    }

    /** Why a part's chosen shader isn't drawn, or null. */
    default String partProblem(String part) {
        return null;
    }
}
