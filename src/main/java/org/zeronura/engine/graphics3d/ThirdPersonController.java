package org.zeronura.engine.graphics3d;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.input.Gamepad;
import org.zeronura.engine.input.Input;
import org.zeronura.engine.input.StandardActions;
import org.zeronura.engine.physics.AABB;
import org.zeronura.engine.physics.UprightBox;
import org.zeronura.engine.render.Camera;
import org.zeronura.engine.scene.Component;

import java.util.List;

/**
 * Third-person player input: an orbit camera behind the character, movement relative to where the camera
 * looks, and the character turning to face the way it moves. Mouse to orbit, wheel to zoom, WASD to move,
 * Shift to sprint, Space to jump. Requires a {@link CharacterController3D} on the same entity.
 */
public class ThirdPersonController extends Component {

    public float sensitivity = 0.0022f;
    public boolean captureOnClick = true;
    /**
     * Whether Escape lets go of a captured mouse. Off, Escape is left to the scene in one press — a pause menu opening
     * straight away rather than on a second press — and the scene lets go of the mouse itself.
     */
    public boolean escapeReleases = true;
    public float distance = 6f;
    public float minDistance = 2f;
    public float maxDistance = 18f;
    /** Height above the feet the camera orbits around. */
    public float pivotHeight = 1.5f;
    /** How quickly the character turns toward its movement direction. */
    public float turnSpeed = 12f;
    /** Added to the facing angle, for models whose front is not +Z. */
    public float modelYawOffset;
    /**
     * A box the camera is kept inside, or null for none — the inside of a room, so the camera comes in closer
     * rather than looking through a wall. The character must stay inside it too.
     */
    public AABB cameraBounds;
    /**
     * Boxes the camera does not look through, coming in in front of the nearest between it and the character instead:
     * the walls dividing a room, which a box round the whole of it lets the camera stand behind.
     */
    public List<AABB> cameraObstacles = List.of();
    /**
     * Boxes turned about the vertical that the camera does not look through either, as {@link #cameraObstacles}:
     * buildings standing at an angle to the world's axes. One the pivot is inside is looked out of.
     */
    public List<UprightBox> cameraTurnedObstacles = List.of();
    /**
     * How quickly the camera goes back out to its distance once nothing stands in its way: the share of the way left
     * gone in 1/rate s. It comes in at once, to stay out of what it would otherwise look through.
     */
    public float releaseRate = 5f;
    /**
     * A fixed view: the mouse does not turn the camera, which keeps whatever yaw and pitch it was given, the way a
     * classic top-down game's camera hangs over the player. Movement is still relative to the camera, so up on the
     * stick is up the screen.
     */
    public boolean lockedView;
    /** The field of view while {@link #lockedView}, in degrees; 0 to use the player's setting. */
    public float lockedFovDegrees;
    /**
     * Whether this moves the character from the keys. Off when something else steers it — a {@link GridWalker} — and
     * this only keeps the camera on it.
     */
    public boolean movesCharacter = true;
    /** How quickly {@link #swingView} turns the camera: the share of what is left of the turn gone in 1/rate s. */
    public float swingRate = 14f;
    /** How quickly a {@linkplain #frame framed shot} eases back to following once the character moves, likewise. */
    public float settleRate = 3f;

    private final CursorLook look = new CursorLook();
    private final Vector3f pivot = new Vector3f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f eye = new Vector3f();
    private CharacterController3D controller;
    private float facing;
    /** The heading the camera is swinging to, or NaN while it is not swinging. */
    private float swingTarget = Float.NaN;
    /** A framed shot: how far the aim is raised above the pivot, and how much further back the camera stands. */
    private float shotLift;
    private float shotPullBack;
    /** Where the character stood when the shot was framed, so it can tell when they move; NaN for no shot. */
    private float shotX = Float.NaN;
    private float shotZ;
    /** How far back the camera stood last frame, coming back out after something stood in its way; NaN for afresh. */
    private float shownDistance = Float.NaN;
    private final Vector3f behind = new Vector3f();

    @Override
    protected void start() {
        controller = entity().require(CharacterController3D.class);
        if (!lockedView) {
            Camera camera = scene().world3D().camera();
            camera.setOrientation(camera.getYaw(), -0.35f);
        }
    }

    @Override
    protected void update(float dt) {
        Engine engine = engine();
        if (engine == null) {
            return;
        }
        Input input = engine.input();
        Camera camera = scene().world3D().camera();

        if (!Float.isNaN(swingTarget)) {
            float left = swingTarget - camera.getYaw();
            if (Math.abs(left) < SWING_DONE) {
                camera.setOrientation(swingTarget, camera.getPitch());
                swingTarget = Float.NaN;
            } else {
                camera.rotate(left * (1f - (float) Math.exp(-swingRate * dt)), 0f);
            }
        }
        settleShot(dt);
        if (!lockedView) {
            Vector2f turn = look.update(engine, sensitivity, captureOnClick, escapeReleases);
            if (turn.x != 0f || turn.y != 0f) {
                camera.rotate(turn.x, turn.y);
            }
        }
        if (input.scroll() != 0f && !engine.guiWantsPointer()) {
            distance = Math.max(minDistance, Math.min(maxDistance, distance - input.scroll() * 0.8f));
        }
        // A controller zooms with the D-pad while there are no buttons on screen for it to move between — unless
        // something else moves the character, which may step it with the D-pad instead.
        if (movesCharacter && engine.gui().nothingToPress()) {
            float zoom = (input.gamepad().isDown(Gamepad.DPAD_DOWN) ? 1f : 0f) - (input.gamepad().isDown(Gamepad.DPAD_UP) ? 1f : 0f);
            if (zoom != 0f) {
                distance = Math.max(minDistance, Math.min(maxDistance, distance + zoom * 8f * dt));
            }
        }
        if (!movesCharacter) {
            return;
        }
        if (engine.gui().hasKeyboardFocus()) {
            controller.move(0f, 0f);
            return;
        }

        float forwardInput = input.actions().axis(StandardActions.MOVE_BACK, StandardActions.MOVE_FORWARD);
        float strafeInput = input.actions().axis(StandardActions.MOVE_LEFT, StandardActions.MOVE_RIGHT);
        float yaw = camera.getYaw();
        float moveX = (float) Math.cos(yaw) * forwardInput - (float) Math.sin(yaw) * strafeInput;
        float moveZ = (float) Math.sin(yaw) * forwardInput + (float) Math.cos(yaw) * strafeInput;
        float length = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (length > 1e-4f) {
            if (length > 1f) {
                moveX /= length;
                moveZ /= length;
            }
            float target = (float) Math.atan2(moveX, moveZ);
            facing = approachAngle(facing, target, 1f - (float) Math.exp(-turnSpeed * dt));
            transform().setYaw(facing + modelYawOffset);
        }
        float speed = controller.moveSpeed * (input.actions().isDown(StandardActions.SPRINT) ? controller.sprintMultiplier : 1f);
        controller.move(moveX * speed, moveZ * speed);
        if (input.actions().isPressed(StandardActions.JUMP)) {
            controller.jump();
        }
    }

    @Override
    protected void preRender(float alpha) {
        if (controller == null) {
            return;
        }
        World3D world = scene().world3D();
        Camera camera = world.camera();
        // The player's field of view: this is the camera they look through while they play.
        if (lockedView && lockedFovDegrees > 0f) {
            camera.setFovDegrees(lockedFovDegrees);
        } else if (scene().engine() != null) {
            camera.setFovDegrees(scene().engine().settings().fieldOfView());
        }
        controller.interpolatedPosition(alpha, pivot).add(0f, pivotHeight + shotLift, 0f);
        camera.getForward(forward);
        float wanted = boundedDistance(forward);
        if (Float.isNaN(shownDistance) || wanted <= shownDistance || scene().engine() == null) {
            shownDistance = wanted;
        } else {
            float dt = scene().engine().time().delta();
            shownDistance += (wanted - shownDistance) * (1f - (float) Math.exp(-releaseRate * dt));
        }
        pivot.sub(forward.mul(shownDistance), eye);
        // Keep the camera above the ground so it never looks up from inside a hill.
        float ground = world.groundHeightAt(eye.x, eye.z);
        if (eye.y < ground + 0.4f) {
            eye.y = ground + 0.4f;
        }
        camera.setPosition(eye);
    }

    /**
     * The way the character faces on the ground, as an angle whose sine is x and cosine is z — the way it last
     * moved. Without {@link #modelYawOffset}, so it is the same for any model.
     */
    public float facing() {
        return facing;
    }

    /** Turns the character to face an angle at once, as {@link #facing} measures it. */
    public void setFacing(float angle) {
        facing = angle;
        transform().setYaw(facing + modelYawOffset);
    }

    /** How near its heading a swing has to come to be finished, in radians. */
    private static final float SWING_DONE = 0.002f;

    /**
     * Frames a shot that holds until the character moves: the camera aims {@code lift} metres above its usual pivot,
     * from {@code distance} away when that is further than its own — a building the player has come out of, standing up
     * behind them. Once they move it eases back to following them as before, at their own distance.
     */
    public void frame(float lift, float distance) {
        shotLift = Math.max(0f, lift);
        shotPullBack = Math.max(0f, distance - this.distance);
        // Straight there, not eased out to as after something in the way.
        shownDistance = Float.NaN;
        Vector3f position = transform().position;
        shotX = position.x;
        shotZ = position.z;
    }

    /** Eases a framed shot away once the character has moved off the spot it was framed on. */
    private void settleShot(float dt) {
        if (Float.isNaN(shotX)) {
            return;
        }
        Vector3f position = transform().position;
        if (Math.abs(position.x - shotX) + Math.abs(position.z - shotZ) < SHOT_STILL) {
            return;
        }
        float keep = (float) Math.exp(-settleRate * dt);
        shotLift *= keep;
        shotPullBack *= keep;
        if (shotLift < 0.01f && shotPullBack < 0.01f) {
            shotLift = 0f;
            shotPullBack = 0f;
            shotX = Float.NaN;
        }
    }

    /** How far the character can drift, settling onto the ground, and still be standing where a shot was framed. */
    private static final float SHOT_STILL = 0.05f;

    /**
     * Turns the camera to a heading, smoothly at {@link #swingRate} — for a {@linkplain #lockedView fixed view} the
     * player turns a quarter at a time. It turns through the difference as given, not the short way round, so two
     * quarter turns to the left asked for in quick succession swing on round to the left. Movement follows the camera
     * as it goes.
     */
    public void swingView(float yaw) {
        swingTarget = yaw;
    }

    /**
     * The orbit distance, shortened where going back along {@code forward} from the pivot would leave
     * {@link #cameraBounds} or meet one of the {@link #cameraObstacles} or {@link #cameraTurnedObstacles}.
     */
    private float boundedDistance(Vector3f forward) {
        float reach = distance + shotPullBack;
        if (cameraBounds != null) {
            reach = Math.min(reach, reachAlong(-forward.x, pivot.x, cameraBounds.minX(), cameraBounds.maxX()));
            reach = Math.min(reach, reachAlong(-forward.y, pivot.y, cameraBounds.minY(), cameraBounds.maxY()));
            reach = Math.min(reach, reachAlong(-forward.z, pivot.z, cameraBounds.minZ(), cameraBounds.maxZ()));
        }
        for (AABB box : cameraObstacles) {
            reach = Math.min(reach, entering(box, pivot, -forward.x, -forward.y, -forward.z) - OBSTACLE_CLEARANCE);
        }
        if (!cameraTurnedObstacles.isEmpty()) {
            behind.set(forward).negate().normalize();
            float within = reach + OBSTACLE_CLEARANCE;
            for (UprightBox box : cameraTurnedObstacles) {
                float across = within + box.radius();
                if (Math.abs(box.centerX() - pivot.x) > across || Math.abs(box.centerZ() - pivot.z) > across
                        || box.contains(pivot.x, pivot.y, pivot.z)) {
                    continue;
                }
                float hit = box.rayDistance(pivot, behind, within);
                if (hit != AABB.NO_HIT) {
                    reach = Math.min(reach, hit - OBSTACLE_CLEARANCE);
                }
            }
        }
        return Math.max(0.1f, reach);
    }

    /** How near in front of an obstacle the camera comes, so the near plane does not cut into it. */
    private static final float OBSTACLE_CLEARANCE = 0.25f;

    /**
     * How far a ray from {@code from} going (dx, dy, dz) per unit goes before it enters a box; infinite when it never
     * does, or starts inside it.
     */
    static float entering(AABB box, Vector3f from, float dx, float dy, float dz) {
        float near = 0f;
        float far = Float.MAX_VALUE;
        float[][] slabs = {{dx, from.x, box.minX(), box.maxX()}, {dy, from.y, box.minY(), box.maxY()},
                {dz, from.z, box.minZ(), box.maxZ()}};
        boolean inside = true;
        for (float[] slab : slabs) {
            float d = slab[0];
            float o = slab[1];
            inside &= o > slab[2] && o < slab[3];
            if (Math.abs(d) < 1e-6f) {
                if (o < slab[2] || o > slab[3]) {
                    return Float.MAX_VALUE;
                }
                continue;
            }
            float t1 = (slab[2] - o) / d;
            float t2 = (slab[3] - o) / d;
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (near > far) {
                return Float.MAX_VALUE;
            }
        }
        return inside ? Float.MAX_VALUE : near;
    }

    /** How far a ray from {@code from} going {@code direction} per unit stays within min..max along one axis. */
    private static float reachAlong(float direction, float from, float min, float max) {
        if (direction > 1e-5f) {
            return (max - from) / direction;
        }
        if (direction < -1e-5f) {
            return (min - from) / direction;
        }
        return Float.MAX_VALUE;
    }

    private static float approachAngle(float current, float target, float fraction) {
        float difference = target - current;
        while (difference > Math.PI) {
            difference -= (float) (2.0 * Math.PI);
        }
        while (difference < -Math.PI) {
            difference += (float) (2.0 * Math.PI);
        }
        return current + difference * fraction;
    }
}
