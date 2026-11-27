package org.zeronura.nulzs.physics2d;

import org.joml.Vector2f;
import org.zeronura.nulzs.scene.Component;
import org.zeronura.nulzs.scene.Scene;

/**
 * A box or circle that takes part in 2D physics, positioned by its entity's transform.
 *
 * <p>Bodies are axis-aligned and do not rotate — the model platformers, top-down games and most arcade
 * games actually want, since a player that tips over on a slope is a bug, not a feature. The shape is
 * centred on the transform position plus {@link #offsetX}/{@link #offsetY}.
 *
 * <pre>{@code
 * Body2D body = player.add(Body2D.box(BodyType.DYNAMIC, 0.8f, 1.6f));
 * body.velocity.x = input * speed;             // you own horizontal movement
 * if (jumpPressed && body.isOnGround()) body.velocity.y = 12f;
 * }</pre>
 *
 * <p>Bodies should be on root entities (no parent), since physics writes the local position.
 */
public class Body2D extends Component {

    public enum Shape { BOX, CIRCLE }

    public BodyType type;
    public final Shape shape;
    public float halfWidth;
    public float halfHeight;
    public float radius;
    public float offsetX;
    public float offsetY;

    /** World units per second. */
    public final Vector2f velocity = new Vector2f();
    /** Multiplies world gravity; 0 floats. */
    public float gravityScale = 1f;
    /** Only matters between dynamic bodies: heavier bodies push lighter ones. */
    public float mass = 1f;
    /** Bounciness, 0 (none) to 1 (perfectly elastic). */
    public float restitution;
    /** How quickly sliding on a surface stops. 0 is ice. */
    public float friction;
    /** Air drag, per second. */
    public float linearDamping;
    /** Detects overlaps without colliding. Coins, checkpoints, damage zones. */
    public boolean trigger;
    /** A static or kinematic platform that can be jumped up through and stood on. */
    public boolean oneWay;
    /** Which collision group this body is in. */
    public int category = 1;
    /** Which groups this body collides with. */
    public int mask = -1;

    boolean onGround;
    boolean touchingLeft;
    boolean touchingRight;
    boolean touchingCeiling;
    Body2D groundBody;
    Body2D carrier;
    float previousMinY;

    private Body2D(BodyType type, Shape shape) {
        this.type = type;
        this.shape = shape;
    }

    public static Body2D box(BodyType type, float width, float height) {
        Body2D body = new Body2D(type, Shape.BOX);
        body.halfWidth = width * 0.5f;
        body.halfHeight = height * 0.5f;
        return body;
    }

    public static Body2D circle(BodyType type, float radius) {
        Body2D body = new Body2D(type, Shape.CIRCLE);
        body.radius = radius;
        return body;
    }

    // --- Fluent setup -----------------------------------------------------------------------------------

    public Body2D asTrigger() {
        trigger = true;
        return this;
    }

    public Body2D asOneWay() {
        oneWay = true;
        return this;
    }

    public Body2D withRestitution(float value) {
        restitution = value;
        return this;
    }

    public Body2D withFriction(float value) {
        friction = value;
        return this;
    }

    public Body2D withGravityScale(float value) {
        gravityScale = value;
        return this;
    }

    public Body2D withMass(float value) {
        mass = value;
        return this;
    }

    public Body2D withDamping(float value) {
        linearDamping = value;
        return this;
    }

    public Body2D withOffset(float x, float y) {
        offsetX = x;
        offsetY = y;
        return this;
    }

    public Body2D withCollisionFilter(int newCategory, int newMask) {
        category = newCategory;
        mask = newMask;
        return this;
    }

    // --- Contact state (valid after a physics step) -----------------------------------------------------

    public boolean isOnGround() {
        return onGround;
    }

    public boolean isTouchingLeft() {
        return touchingLeft;
    }

    public boolean isTouchingRight() {
        return touchingRight;
    }

    public boolean isTouchingCeiling() {
        return touchingCeiling;
    }

    /** The body stood on, or null when standing on tiles or in the air. */
    public Body2D groundBody() {
        return groundBody;
    }

    void clearContacts() {
        onGround = false;
        touchingLeft = false;
        touchingRight = false;
        touchingCeiling = false;
        groundBody = null;
    }

    // --- Geometry ---------------------------------------------------------------------------------------

    public float centerX() {
        return transform().position.x + offsetX;
    }

    public float centerY() {
        return transform().position.y + offsetY;
    }

    public float extentX() {
        return shape == Shape.BOX ? halfWidth : radius;
    }

    public float extentY() {
        return shape == Shape.BOX ? halfHeight : radius;
    }

    public float minX() {
        return centerX() - extentX();
    }

    public float maxX() {
        return centerX() + extentX();
    }

    public float minY() {
        return centerY() - extentY();
    }

    public float maxY() {
        return centerY() + extentY();
    }

    public boolean containsPoint(float x, float y) {
        if (shape == Shape.BOX) {
            return x >= minX() && x <= maxX() && y >= minY() && y <= maxY();
        }
        float dx = x - centerX();
        float dy = y - centerY();
        return dx * dx + dy * dy <= radius * radius;
    }

    public float inverseMass() {
        return type == BodyType.DYNAMIC && mass > 0f ? 1f / mass : 0f;
    }

    /** Whether the collision filters of both bodies let them interact. */
    public boolean filters(Body2D other) {
        return (category & other.mask) != 0 && (other.category & mask) != 0;
    }

    public void setPosition(float x, float y) {
        transform().setPosition(x, y);
    }

    @Override
    protected void onEnterScene(Scene scene) {
        scene.physics2D().add(this);
    }

    @Override
    protected void onExitScene(Scene scene) {
        scene.physics2D().remove(this);
    }
}
