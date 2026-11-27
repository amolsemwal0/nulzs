package org.zeronura.nulzs.animation;

import org.joml.Matrix4f;
import org.zeronura.nulzs.asset.Model;

/**
 * A place on a posed skeleton to hang something: a bone, plus where on that bone it sits.
 *
 * <p>The engine half of the modular character. It knows nothing about hands, hats or swords — a socket is
 * a bone name and an offset, and which bone means "the right hand" is a fact about the content, which is
 * why the naming lives in the game layer ({@code game.character.CharacterSocket}) and not here.
 *
 * <h2>What it is not</h2>
 * A socket carries a <b>rigid</b> attachment: the thing follows one bone, whole. That covers a weapon in a
 * hand, a tool, a ring, a hat, a quiver on a back. It does <b>not</b> cover a garment, which has to bend
 * with the body — a shirt is not attached to the spine, it is skinned to the same skeleton as the body
 * under it, and that is a different mechanism (bind the garment's own bones to the character's by name and
 * draw it with the character's pose). Hair is either, depending on how it was authored: a rigid cap is a
 * socket, and hair with its own bones is not.
 *
 * <h2>Resolution</h2>
 * The bone is looked up <b>once</b>, when the socket is bound to a model, rather than by name per frame —
 * and a socket whose bone the rig does not have is not an error. Models here differ: the shipped player
 * rig has 135 bones including a named ring finger, and the base mesh has <b>none at all</b>, so "this
 * character cannot wear that" is an ordinary answer. It is reported by {@link #isBound()} rather than
 * thrown, so the caller can say so out loud; an unbound socket that silently drew at the origin would put
 * a sword through the character's feet.
 */
public record Socket(String boneName, int boneIndex, Matrix4f offset) {

    /** The bone index of a socket whose bone the model does not have. */
    public static final int UNBOUND = -1;

    public Socket {
        offset = new Matrix4f(offset);
    }

    /**
     * Binds a socket to a model's skeleton, resolving the bone once.
     *
     * @param offset where on the bone the attachment sits, in <b>bone space</b> — identity puts the
     *               attachment's own origin on the bone's, which is what a model authored about its grip
     *               wants
     */
    public static Socket on(Model model, String boneName, Matrix4f offset) {
        return new Socket(boneName, model.boneIndexOf(boneName), offset);
    }

    /** Whether the model this was bound to actually has the bone. */
    public boolean isBound() {
        return boneIndex != UNBOUND;
    }

    /**
     * Where an attachment on this socket should be drawn.
     *
     * <p>{@code characterTransform · boneInModelSpace · offset}, in that order and for that reason: the
     * offset is expressed on the bone, the bone is expressed in the model, and the model is placed in the
     * world. Reading it right to left is how an attachment travels — out from its own origin, onto the
     * hand, and then wherever the character is standing.
     *
     * @return {@code dest}
     */
    public Matrix4f transform(Animator animator, Matrix4f characterTransform, Matrix4f dest) {
        animator.boneModelTransform(boneIndex, dest);
        return dest.mulLocal(characterTransform).mul(offset);
    }

    @Override
    public Matrix4f offset() {
        return new Matrix4f(offset);
    }
}
