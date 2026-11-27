package org.zeronura.engine.graphics3d;

import org.joml.Vector3f;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.input.Gamepad;
import org.zeronura.engine.input.Input;
import org.zeronura.engine.input.InputMap;
import org.zeronura.engine.input.StandardActions;
import org.zeronura.engine.scene.Component;

/**
 * Moves a {@link CharacterController3D} one square at a time — up, down, left or right — the way a classic top-down
 * game steps its hero across a grid, over a 3D world.
 *
 * <p>The ground is a grid of square cells {@link #cellSize} across with their corners on multiples of it. The character
 * stands in the middle of a cell, or is walking straight to the middle of the next. Holding a direction walks on from
 * cell to cell without a pause; tapping a direction the character is not facing turns it on the spot, and holding it a
 * moment longer ({@link #turnDelay}) walks that way. When two directions are held the last one pressed wins. Sprint
 * runs. A controller's D-pad steps as the movement keys do, while there is nothing on screen for it to move between. Whether a step can be taken is the game's to say ({@link #steps}): walls, deep water, people in the way.
 *
 * <p>Up is up the screen: the directions are taken from the camera's heading, snapped to the nearer axis of the grid.
 *
 * <p>The body stays the controller's. Every physics step this sets its velocity so that it reaches exactly where the
 * step should have it by the next, and gravity, stepping up a ledge and free movement (riding over water, flying) work
 * as they always do. Speed is the controller's {@link CharacterController3D#moveSpeed} (times its sprint multiplier
 * when running), in world units a second. A step the body cannot make — something in the way the game did not foresee
 * — is given up after a moment, and the character goes back to the cell it left.
 *
 * <p>Teleporting the controller is fine: once still again, the walker settles into the cell it finds the body in.
 * Add this after the controller, so its physics step comes second and reads where the body has just got to.
 */
public class GridWalker extends Component {

    /** Whether a step can be taken. Asked with the same cell twice, whether that cell can be stood in at all. */
    @FunctionalInterface
    public interface Steps {
        boolean canStep(int fromX, int fromZ, int toX, int toZ);
    }

    /** The side of a cell, in world units. */
    public float cellSize = 1f;
    /** How long a direction has to be held, after turning to face it from standing, before the character walks. */
    public float turnDelay = 0.1f;
    /** Says which steps can be taken; every one, until the game says otherwise. */
    public Steps steps = (fromX, fromZ, toX, toZ) -> true;

    /** How far the body may trail where a step should have it before the step is given up, in cells. */
    private static final float LAG_LIMIT = 0.5f;
    /** How long it may trail that far before the step is given up, in seconds. */
    private static final float STUCK_TIME = 0.25f;

    // The four ways, in this order everywhere: up the screen, down it, left, right.
    private static final String[] MOVES = {StandardActions.MOVE_FORWARD, StandardActions.MOVE_BACK,
            StandardActions.MOVE_LEFT, StandardActions.MOVE_RIGHT};
    private static final int[] PAD = {Gamepad.DPAD_UP, Gamepad.DPAD_DOWN, Gamepad.DPAD_LEFT, Gamepad.DPAD_RIGHT};

    private CharacterController3D controller;
    private int cellX;
    private int cellZ;
    private int toX;
    private int toZ;
    private boolean moving;
    private boolean settled;
    /** How far along the current step, 0 to 1. */
    private float progress;
    private boolean running;
    private int facingX;
    private int facingZ = 1;
    /** The direction held on the keys, in grid terms; 0, 0 for none. */
    private int heldX;
    private int heldZ;
    private boolean runHeld;
    /** The way pressed most recently, while it is still held, as an index into {@link #MOVES}; -1 for none. */
    private int latest = -1;
    private float turnTimer;
    private float stuckTime;
    private int stepsTaken;
    private final Vector3f aimed = new Vector3f();

    @Override
    protected void start() {
        controller = entity().require(CharacterController3D.class);
    }

    // --- What the character is doing ----------------------------------------------------------------------

    /** Whether the character is walking between two cells. */
    public boolean isMoving() {
        return moving;
    }

    /** Whether the step being walked is a run. */
    public boolean isRunning() {
        return moving && running;
    }

    /** How far along the step being walked, 0 to 1; 0 when standing. */
    public float stepProgress() {
        return moving ? progress : 0f;
    }

    /** Every step finished so far: which foot a step is on, for a walk cycle that alternates them. */
    public int stepsTaken() {
        return stepsTaken;
    }

    /** The cell stood in, or the one being walked away from. */
    public int cellX() {
        return cellX;
    }

    public int cellZ() {
        return cellZ;
    }

    /** The way the character faces along x: -1, 0 or 1. */
    public int facingX() {
        return facingX;
    }

    /** The way the character faces along z: -1, 0 or 1. */
    public int facingZ() {
        return facingZ;
    }

    /** The way the character faces as an angle whose sine is x and cosine is z, as {@link ThirdPersonController#facing}. */
    public float facing() {
        return (float) Math.atan2(facingX, facingZ);
    }

    /** Turns the character to the grid direction nearest an angle measured as {@link #facing} is. */
    public void setFacing(float angle) {
        float x = (float) Math.sin(angle);
        float z = (float) Math.cos(angle);
        if (Math.abs(x) > Math.abs(z)) {
            face((int) Math.signum(x), 0);
        } else {
            face(0, z < 0f ? -1 : 1);
        }
    }

    /**
     * Stops at once, in whichever of the two cells the character is nearer, as a conversation or a battle begins. The
     * body is drawn into that cell's middle as soon as the scene runs again.
     */
    public void halt() {
        if (moving && progress >= 0.5f) {
            cellX = toX;
            cellZ = toZ;
        }
        moving = false;
        progress = 0f;
        turnTimer = 0f;
        heldX = 0;
        heldZ = 0;
        latest = -1;
    }

    // --- Stepping ------------------------------------------------------------------------------------------

    @Override
    protected void update(float dt) {
        Engine engine = engine();
        if (engine == null) {
            return;
        }
        if (engine.gui().hasKeyboardFocus()) {
            heldX = 0;
            heldZ = 0;
            latest = -1;
            return;
        }
        Input input = engine.input();
        InputMap actions = input.actions();
        boolean pad = engine.gui().nothingToPress();
        for (int i = 0; i < MOVES.length; i++) {
            if (actions.isPressed(MOVES[i]) || (pad && input.gamepad().isPressed(PAD[i]))) {
                latest = i;
            }
        }
        if (latest >= 0 && !isDown(input, pad, latest)) {
            latest = -1;
        }
        int held = latest;
        for (int i = 0; held < 0 && i < MOVES.length; i++) {
            if (isDown(input, pad, i)) {
                held = i;
            }
        }
        runHeld = actions.isDown(StandardActions.SPRINT);
        heldX = 0;
        heldZ = 0;
        if (held < 0) {
            return;
        }
        // The camera's heading, snapped to the grid: its forward runs along (cos yaw, sin yaw), its right along
        // (-sin yaw, cos yaw), which with forward snapped to (ax, az) is (-az, ax).
        float yaw = scene().world3D().camera().getYaw();
        float forwardX = (float) Math.cos(yaw);
        float forwardZ = (float) Math.sin(yaw);
        int ax = Math.abs(forwardX) >= Math.abs(forwardZ) ? (int) Math.signum(forwardX) : 0;
        int az = ax == 0 ? (int) Math.signum(forwardZ) : 0;
        switch (held) {
            case 0 -> { heldX = ax; heldZ = az; }
            case 1 -> { heldX = -ax; heldZ = -az; }
            case 2 -> { heldX = az; heldZ = -ax; }
            default -> { heldX = -az; heldZ = ax; }
        }
    }

    private static boolean isDown(Input input, boolean pad, int way) {
        return input.actions().isDown(MOVES[way]) || (pad && input.gamepad().isDown(PAD[way]));
    }

    @Override
    protected void fixedUpdate(float dt) {
        if (controller == null || controller.body() == null || dt <= 0f) {
            return;
        }
        Vector3f body = controller.body().position();
        turnTimer = Math.max(0f, turnTimer - dt);
        if (!settled) {
            settle(body);
        }
        if (!moving) {
            // Teleported away from the cell, or never in one: take whichever the body is in now.
            if (Math.abs(body.x - middle(cellX)) > cellSize * 0.75f || Math.abs(body.z - middle(cellZ)) > cellSize * 0.75f) {
                settle(body);
            }
            startStep(true);
        } else {
            float lag = (float) Math.hypot(body.x - aimed.x, body.z - aimed.z);
            stuckTime = lag > cellSize * LAG_LIMIT ? stuckTime + dt : 0f;
            if (stuckTime > STUCK_TIME) {
                // Something in the way: back to the cell the step left.
                moving = false;
                progress = 0f;
                stuckTime = 0f;
            } else {
                progress += speed() * dt / cellSize;
                if (progress >= 1f) {
                    float over = progress - 1f;
                    cellX = toX;
                    cellZ = toZ;
                    stepsTaken++;
                    moving = false;
                    progress = 0f;
                    if (startStep(false)) {
                        progress = Math.min(over, 1f);
                    }
                }
            }
        }
        if (moving) {
            aimed.set(middle(cellX) + (middle(toX) - middle(cellX)) * progress, 0f,
                    middle(cellZ) + (middle(toZ) - middle(cellZ)) * progress);
        } else {
            aimed.set(middle(cellX), 0f, middle(cellZ));
        }
        // Whatever reaches the aimed point by the next physics step — capped, so a body knocked far off it is drawn
        // back at a run rather than flung.
        float vx = (aimed.x - body.x) / dt;
        float vz = (aimed.z - body.z) / dt;
        float length = (float) Math.hypot(vx, vz);
        float cap = Math.max(speed(), controller.moveSpeed) * 3f;
        if (length > cap) {
            vx *= cap / length;
            vz *= cap / length;
        }
        controller.move(vx, vz);
    }

    /**
     * Sets off toward the cell in the held direction, if a direction is held and the step can be taken. From standing,
     * a new direction is faced first and walked only once it has been held for {@link #turnDelay}.
     *
     * @return whether a step began
     */
    private boolean startStep(boolean fromStanding) {
        if (heldX == 0 && heldZ == 0) {
            return false;
        }
        if (heldX != facingX || heldZ != facingZ) {
            face(heldX, heldZ);
            if (fromStanding) {
                turnTimer = turnDelay;
                return false;
            }
        }
        if (turnTimer > 0f) {
            return false;
        }
        int nextX = cellX + heldX;
        int nextZ = cellZ + heldZ;
        if (!steps.canStep(cellX, cellZ, nextX, nextZ)) {
            return false;
        }
        toX = nextX;
        toZ = nextZ;
        moving = true;
        progress = 0f;
        running = runHeld;
        stuckTime = 0f;
        return true;
    }

    private void face(int x, int z) {
        facingX = x;
        facingZ = z;
        transform().setYaw(facing());
    }

    /** The cell the body is in, or the nearest beside it that can be stood in when that one cannot. */
    private void settle(Vector3f body) {
        settled = true;
        int x = (int) Math.floor(body.x / cellSize);
        int z = (int) Math.floor(body.z / cellSize);
        cellX = x;
        cellZ = z;
        if (steps.canStep(x, z, x, z)) {
            return;
        }
        float best = Float.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && steps.canStep(x + dx, z + dz, x + dx, z + dz)) {
                    float distance = (float) Math.hypot(middle(x + dx) - body.x, middle(z + dz) - body.z);
                    if (distance < best) {
                        best = distance;
                        cellX = x + dx;
                        cellZ = z + dz;
                    }
                }
            }
        }
    }

    private float middle(int cell) {
        return (cell + 0.5f) * cellSize;
    }

    private float speed() {
        return controller.moveSpeed * (running ? controller.sprintMultiplier : 1f);
    }
}
