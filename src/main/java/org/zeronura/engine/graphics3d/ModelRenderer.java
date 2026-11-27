package org.zeronura.engine.graphics3d;

import org.joml.Matrix4f;
import org.zeronura.engine.animation.Animator;
import org.zeronura.engine.asset.AnimationClip;
import org.zeronura.engine.asset.Model;
import org.zeronura.engine.scene.Component;

/**
 * Draws a loaded 3D model (glTF, FBX, …) with its materials, optionally playing a skeletal animation.
 *
 * <pre>{@code
 * Model knight = scene.assets().model("models/knight.glb");
 * hero.add(new ModelRenderer(knight)).animate(0);   // play the first clip
 * }</pre>
 */
public class ModelRenderer extends Component {

    public Model model;
    public Animator animator;
    public boolean castShadows = true;
    /** Animation playback rate. */
    public float playbackSpeed = 1f;

    /** The meshes a subclass draws itself (parts in a shader): the world draws only their shadows. */
    final java.util.Set<org.zeronura.engine.render.Mesh> drawnElsewhere = new java.util.HashSet<>();

    private final Matrix4f matrix = new Matrix4f();

    public ModelRenderer(Model model) {
        this.model = model;
    }

    /** Plays a clip, creating the animator on first use. */
    public ModelRenderer animate(AnimationClip clip) {
        if (animator == null) {
            animator = new Animator(model);
        }
        animator.setClip(clip);
        return this;
    }

    /** Plays the model's clip at this index, in the order the file lists them. */
    public ModelRenderer animate(int clipIndex) {
        return animate(model.getAnimationClips().get(clipIndex));
    }

    @Override
    protected void update(float dt) {
        if (animator != null && animator.getClip() != null) {
            animator.update(dt * playbackSpeed);
        }
    }

    Matrix4f worldMatrix() {
        return transform().worldMatrix(matrix);
    }
}
