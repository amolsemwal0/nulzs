package org.zeronura.nulzs.render;

import org.joml.Vector3f;

import java.util.List;

/**
 * Where a character's head is and which way it faces — what face shading is measured against.
 *
 * <p>A face material (see {@code Material.setFace}) is not lit by its own normals, so the shader needs the
 * head itself: its centre for spherical face normals, and its forward and up for the face shadow map. Both
 * come from the head bone, which is found by name, and the head's turn is read as a delta from the bind
 * pose ({@code Animator.nodeFromBind}) so that no rig's bone-axis convention has to be known.
 *
 * @param headNodes    candidate names for the head bone, tried in order; the first the rig has wins. The
 *                     defaults cover VRoid, Blender/Rigify, Mixamo and 3ds Max Biped rigs.
 * @param forward      which way the character faces <b>in its bind pose, in model space</b>. glTF's own
 *                     convention is +Z, and so is VRM 1.0; VRM 0.x faces −Z.
 * @param up           up in the bind pose, in model space.
 * @param centerOffset from the head bone to the middle of the skull, in the bind pose and in model units.
 *                     A head bone usually sits at the top of the neck, so the centre is a little above it
 *                     and a little forward. Only spherical face normals read it.
 */
public record FaceRig(List<String> headNodes, Vector3f forward, Vector3f up, Vector3f centerOffset) {

    public static final FaceRig DEFAULT = new FaceRig(
            List.of("J_Bip_C_Head", "head", "Head", "DEF-head", "mixamorig:Head", "mixamorig_Head",
                    "Bip001 Head", "Bip01 Head"),
            new Vector3f(0f, 0f, 1f),
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0.08f, 0.015f));

    /** The same rig facing the other way — for a VRM 0.x model, which faces −Z. */
    public FaceRig facingNegativeZ() {
        return new FaceRig(headNodes, new Vector3f(forward).negate(), up, new Vector3f(centerOffset.x,
                centerOffset.y, -centerOffset.z));
    }
}
