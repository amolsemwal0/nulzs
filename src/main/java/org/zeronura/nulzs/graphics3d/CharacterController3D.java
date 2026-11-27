package org.zeronura.nulzs.graphics3d;

import org.joml.Vector3f;
import org.zeronura.nulzs.physics.AABB;
import org.zeronura.nulzs.physics.CharacterBody;
import org.zeronura.nulzs.scene.Component;
import org.zeronura.nulzs.scene.Scene;

import java.util.ArrayList;
import java.util.List;

/**
 * A walking, jumping body for 3D characters: collides with terrain, the ground plane and every
 * {@link BoxCollider3D}, steps up small ledges, and slides along walls.
 *
 * <p>The entity's position is the character's <b>feet</b>. Set the desired horizontal velocity with
 * {@link #move} every frame and call {@link #jump}; physics runs at the fixed rate. Pair it with
 * {@link FirstPersonController} or {@link ThirdPersonController} for player input, or drive it from AI.
 *
 * <p>The position is the physics step's after each step and, while a frame is drawn, the blend between the last
 * two steps. A step moves a running character a whole 60th of a second at once; drawn at that position on a
 * faster display, the model holds still for a frame or two and then jumps, while the camera — which follows the
 * blended position — glides on smoothly. The character shakes back and forth against the camera by up to a step's
 * distance, 12 cm at 7 m/s. So what is drawn is the blended position too, set in {@link #preRender}.
 */
public class CharacterController3D extends Component {

    public float moveSpeed = 6f;
    public float sprintMultiplier = 1.7f;
    public float jumpSpeed = 9f;
    public float eyeHeight = 1.62f;

    private final float halfWidth;
    private final float height;
    private CharacterBody body;
    private float desiredX;
    private float desiredZ;
    private boolean jumpRequested;
    private boolean free;
    private float desiredY;

    private static final float NEARBY_REACH = 6f;

    private final Vector3f previous = new Vector3f();
    private final Vector3f current = new Vector3f();
    private final List<AABB> nearby = new ArrayList<>();

    public CharacterController3D() {
        this(0.35f, 1.8f);
    }

    public CharacterController3D(float halfWidth, float height) {
        this.halfWidth = halfWidth;
        this.height = height;
    }

    @Override
    protected void onEnterScene(Scene scene) {
        Vector3f feet = transform().position;
        body = new CharacterBody(feet.x, feet.y, feet.z, halfWidth, height);
        previous.set(feet);
        current.set(feet);
    }

    /** Desired horizontal velocity in world units per second. Kept until changed. */
    public void move(float velocityX, float velocityZ) {
        desiredX = velocityX;
        desiredZ = velocityZ;
    }

    /** Jumps at the next physics step if standing on something. */
    public void jump() {
        jumpRequested = true;
    }

    /**
     * Free movement: no gravity and no collision, the body going wherever {@link #move} and {@link #moveVertical}
     * send it — for riding over water or flying, where the game keeps it where it belongs. Jumps are ignored. Turning
     * it off leaves the body where it is, to fall from there.
     */
    public void setFreeMovement(boolean free) {
        this.free = free;
        desiredY = 0f;
        jumpRequested = false;
        if (body != null) {
            body.velocity().y = 0f;
        }
    }

    public boolean isFreeMovement() {
        return free;
    }

    /** Desired vertical velocity in world units per second, in free movement only. Kept until changed. */
    public void moveVertical(float velocityY) {
        desiredY = velocityY;
    }

    public boolean isOnGround() {
        return body != null && !free && body.isOnGround();
    }

    public CharacterBody body() {
        return body;
    }

    public float halfWidth() {
        return halfWidth;
    }

    public float height() {
        return height;
    }

    public void teleport(float x, float feetY, float z) {
        if (body != null) {
            body.setPosition(x, feetY, z);
        }
        previous.set(x, feetY, z);
        current.set(x, feetY, z);
        transform().position.set(x, feetY, z);
    }

    @Override
    protected void fixedUpdate(float dt) {
        if (body == null) {
            return;
        }
        if (free) {
            jumpRequested = false;
            previous.set(current);
            Vector3f feet = body.position();
            body.setPosition(feet.x + desiredX * dt, feet.y + desiredY * dt, feet.z + desiredZ * dt);
            current.set(body.position());
            transform().position.set(current);
            return;
        }
        World3D world = scene().world3D();
        body.velocity().x = desiredX;
        body.velocity().z = desiredZ;
        if (jumpRequested) {
            body.jump(jumpSpeed);
            jumpRequested = false;
        }
        previous.set(current);
        // Only boxes within reach: a map can hold thousands of colliders, and physics tests each one
        // many times per step.
        nearby.clear();
        Vector3f feet = body.position();
        for (AABB box : world.obstacles()) {
            if (box.maxX() > feet.x - NEARBY_REACH && box.minX() < feet.x + NEARBY_REACH
                    && box.maxZ() > feet.z - NEARBY_REACH && box.minZ() < feet.z + NEARBY_REACH) {
                nearby.add(box);
            }
        }
        world.physics().step(body, world.solidField(), nearby, dt);
        current.set(body.position());
        transform().position.set(current);
    }

    @Override
    protected void preRender(float alpha) {
        if (body != null) {
            interpolatedPosition(alpha, transform().position);
        }
    }

    /** Feet position blended between the last two physics steps, for smooth cameras. */
    public Vector3f interpolatedPosition(float alpha, Vector3f dest) {
        return previous.lerp(current, alpha, dest);
    }
}
