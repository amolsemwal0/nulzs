package org.zeronura.nulzs.animation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.asset.AnimationClip;
import org.zeronura.nulzs.asset.Model;
import org.zeronura.nulzs.asset.NodeAnimChannel;
import org.zeronura.nulzs.asset.SkeletonNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A socket puts a thing on a bone, in the world, in the pose the bone is in.
 *
 * <p>GL-free, against a skeleton built by hand: {@link Model}'s constructor is plain field assignment, so
 * a model with a real skeleton and <b>no meshes at all</b> is a legal thing to make, and everything a
 * socket does is decided in that half. Which is fortunate, because none of what is asserted here can be
 * seen in a still frame — a sword in the wrong place is a perfectly convincing sword.
 *
 * <h2>The fixture is built so the obvious mistake fails it</h2>
 * The one bone is translated (1, 2, 3) from the root and its offset matrix is the inverse of that, which
 * is what an inverse bind pose <i>is</i>. So at bind pose the <b>skinning</b> matrix for that bone is
 * exactly the identity — meaning a socket that reached for {@code getFinalBoneMatrices}, which is the
 * array that looks like the answer, would place the attachment at the character's origin instead of on
 * the hand. Every assertion below separates those two by three units.
 */
class SocketTest {

    private static final Vector3f HAND_AT = new Vector3f(1f, 2f, 3f);
    private static final String HAND = "Hand";

    /** Root at the origin with one bone translated away from it, bound so its skinning matrix is identity. */
    private static Model skeleton() {
        SkeletonNode root = new SkeletonNode("Root", new Matrix4f(), null);
        SkeletonNode hand = new SkeletonNode(HAND, new Matrix4f().translation(HAND_AT), root);
        hand.boneIndex = 0;
        root.children.add(hand);

        return new Model(List.of(), List.of(), root,
                Map.of(HAND, 0),
                List.of(new Matrix4f().translation(new Vector3f(HAND_AT).negate())),
                new Matrix4f(), List.of(),
                new Vector3f(), new Vector3f(), 0f);
    }

    private static Vector3f placedBy(Socket socket, Animator animator, Matrix4f character) {
        return socket.transform(animator, character, new Matrix4f())
                .transformPosition(new Vector3f());
    }

    @Test
    void anAttachmentLandsOnItsBoneRatherThanAtTheCharactersOrigin() {
        Model model = skeleton();
        Animator animator = new Animator(model);
        Socket socket = Socket.on(model, HAND, new Matrix4f());

        assertTrue(socket.isBound());
        assertEquals(HAND_AT, placedBy(socket, animator, new Matrix4f()),
                "the attachment is not on the bone — reaching for the skinning matrix puts it at 0,0,0");

        // And the skinning matrix really is the identity here, which is what makes that assertion mean
        // something rather than passing by luck.
        assertEquals(new Matrix4f(), animator.getFinalBoneMatrices()[0],
                "the fixture no longer separates the two matrices, so this test proves nothing");
    }

    @Test
    void theAttachmentTravelsWithTheCharacter() {
        Model model = skeleton();
        Animator animator = new Animator(model);
        Socket socket = Socket.on(model, HAND, new Matrix4f());

        Matrix4f character = new Matrix4f().translation(10f, 0f, 0f);
        assertEquals(new Vector3f(11f, 2f, 3f), placedBy(socket, animator, character));
    }

    /** The character's own rotation carries the attachment round with it, rather than sliding it sideways. */
    @Test
    void theAttachmentTurnsWithTheCharacter() {
        Model model = skeleton();
        Animator animator = new Animator(model);
        Socket socket = Socket.on(model, HAND, new Matrix4f());

        Matrix4f turned = new Matrix4f().rotationY((float) Math.PI);
        Vector3f at = placedBy(socket, animator, turned);
        assertEquals(-HAND_AT.x, at.x, 1e-4f);
        assertEquals(HAND_AT.y, at.y, 1e-4f);
        assertEquals(-HAND_AT.z, at.z, 1e-4f);
    }

    /** The offset is applied on the bone, so it turns with the bone rather than pointing a fixed way. */
    @Test
    void theOffsetIsExpressedOnTheBone() {
        Model model = skeleton();
        Animator animator = new Animator(model);
        Socket socket = Socket.on(model, HAND, new Matrix4f().translation(0f, 0f, 5f));

        assertEquals(new Vector3f(1f, 2f, 8f), placedBy(socket, animator, new Matrix4f()));

        // Turn the whole character half round: an offset in bone space comes round with it. Applied in
        // world space instead it would stay at +Z and the sword would swap hands as the player turned.
        Vector3f turned = placedBy(socket, animator, new Matrix4f().rotationY((float) Math.PI));
        assertEquals(-8f, turned.z, 1e-4f);
    }

    /** And it follows the animation, which is the whole point of hanging it on a bone. */
    @Test
    void theAttachmentFollowsTheBoneAsItIsAnimated() {
        Model model = skeleton();
        Animator animator = new Animator(model);
        Socket socket = Socket.on(model, HAND, new Matrix4f());

        Vector3f atRest = placedBy(socket, animator, new Matrix4f());

        // One channel that lifts the hand to (1, 9, 3) over a second.
        animator.setClip(new AnimationClip("Raise", 1000.0, 1000.0, List.of(
                new NodeAnimChannel(HAND,
                        List.of(new NodeAnimChannel.PositionKey(0.0, new Vector3f(HAND_AT)),
                                new NodeAnimChannel.PositionKey(1000.0, new Vector3f(1f, 9f, 3f))),
                        List.of(new NodeAnimChannel.RotationKey(0.0, new Quaternionf())),
                        List.of(new NodeAnimChannel.ScaleKey(0.0, new Vector3f(1f, 1f, 1f)))))));
        animator.update(0.5);

        Vector3f raised = placedBy(socket, animator, new Matrix4f());
        assertEquals(atRest.x, raised.x, 1e-4f);
        assertEquals(atRest.z, raised.z, 1e-4f);
        assertTrue(raised.y > atRest.y + 2f,
                "the attachment stayed at the bind pose while the bone moved: " + raised);
    }

    /**
     * A socket the rig has no bone for reports itself rather than drawing somewhere arbitrary.
     *
     * <p>Not a hypothetical — the shipped base mesh has no bones at all — and the reason it must be
     * <i>reported</i> is that both plausible silent answers are bad: at the origin, a sword goes through
     * the character's feet; at an undefined index, it goes somewhere different on every model.
     */
    @Test
    void aSocketTheRigHasNoBoneForSaysSoInsteadOfGuessing() {
        Model model = skeleton();
        Socket socket = Socket.on(model, "NoSuchBone", new Matrix4f());

        assertFalse(socket.isBound());
        assertEquals(Socket.UNBOUND, socket.boneIndex());
    }

    /** The offset a socket is built with cannot be changed underneath it from outside. */
    @Test
    void aSocketKeepsItsOwnOffset() {
        Model model = skeleton();
        Matrix4f offset = new Matrix4f().translation(0f, 0f, 5f);
        Socket socket = Socket.on(model, HAND, offset);

        offset.translation(100f, 100f, 100f);
        socket.offset().translation(-100f, -100f, -100f);

        assertEquals(new Vector3f(1f, 2f, 8f),
                placedBy(socket, new Animator(model), new Matrix4f()));
    }
}
