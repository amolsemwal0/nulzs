package org.zeronura.engine.physics2d;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.zeronura.engine.graphics2d.Tilemap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Simulates {@link Body2D}s: gravity, collisions against level geometry and each other, triggers and
 * raycasts.
 *
 * <h2>Two resolution strategies, on purpose</h2>
 * A dynamic <b>box</b> moving against static geometry is moved one axis at a time — X, then Y — and pushed
 * back out along the axis it moved on. That is what makes platformer movement feel right: running along a
 * floor built of many tiles never catches on the seams between them, walking into a wall keeps your
 * vertical speed, and "on ground" means exactly "was stopped moving down".
 *
 * <p>Everything else (circles, dynamic-vs-dynamic, bodies shoved by moving platforms) is resolved by the
 * minimum translation out of the overlap, with an impulse along the contact normal. That gives rolling
 * balls and stacked crates sensible behaviour without a full rigid-body solver.
 *
 * <h2>Substeps</h2>
 * A fast body could otherwise pass completely through thin geometry in one step, so each step is split so
 * no body moves more than about half its own size (or half a tile) at a time.
 */
public final class Physics2DWorld {

    private static final float EPSILON = 1e-4f;
    private static final float ONE_WAY_TOLERANCE = 0.05f;
    private static final int MAX_SUBSTEPS = 16;

    /** World units per second squared. Tuned for worlds measured in tiles or metres. */
    public final Vector2f gravity = new Vector2f(0f, -30f);

    private final List<Body2D> bodies = new ArrayList<>();
    private final List<Tilemap> tilemaps = new ArrayList<>();
    private final List<Body2D> dynamicScratch = new ArrayList<>();
    private final Manifold manifold = new Manifold();
    private final float[] rayScratch = new float[3];

    private Set<TriggerPair> triggerPairs = new HashSet<>();
    private Set<TriggerPair> currentPairs = new HashSet<>();

    private final List<CollisionEvent> collisionEvents = new ArrayList<>();
    private final Set<CollisionKey> collisionKeys = new HashSet<>();

    private float substepDt;

    private record TriggerPair(Body2D trigger, Body2D other) {
    }

    private record CollisionEvent(Body2D self, Body2D other, float normalX, float normalY) {
    }

    private record CollisionKey(Body2D self, Body2D other, int normalX, int normalY) {
    }

    static final class Manifold {
        float normalX;
        float normalY;
        float depth;
    }

    // --- Membership -------------------------------------------------------------------------------------

    public void add(Body2D body) {
        if (!bodies.contains(body)) {
            bodies.add(body);
        }
    }

    public void remove(Body2D body) {
        bodies.remove(body);
        triggerPairs.removeIf(pair -> pair.trigger == body || pair.other == body);
    }

    public void addTilemap(Tilemap tilemap) {
        if (!tilemaps.contains(tilemap)) {
            tilemaps.add(tilemap);
        }
    }

    public void removeTilemap(Tilemap tilemap) {
        tilemaps.remove(tilemap);
    }

    public List<Body2D> bodies() {
        return Collections.unmodifiableList(bodies);
    }

    // --- Simulation -------------------------------------------------------------------------------------

    public void step(float dt) {
        if (dt <= 0f) {
            return;
        }
        for (Body2D body : bodies) {
            // Remember what the body stood on last step, so a moving platform can carry it this step.
            body.carrier = body.onGround ? body.groundBody : null;
            body.clearContacts();
        }

        int substeps = substepsFor(dt);
        substepDt = dt / substeps;
        for (int i = 0; i < substeps; i++) {
            substep(substepDt);
        }

        List<Runnable> triggerEvents = updateTriggers();
        fireCollisionEvents();
        triggerEvents.forEach(Runnable::run);
    }

    private int substepsFor(float dt) {
        float minTile = Float.MAX_VALUE;
        for (Tilemap map : tilemaps) {
            minTile = Math.min(minTile, map.tileSize());
        }
        float worst = 0f;
        for (Body2D body : bodies) {
            if (body.type == BodyType.STATIC || !isActive(body)) {
                continue;
            }
            float speed = Math.max(Math.abs(body.velocity.x), Math.abs(body.velocity.y))
                    + Math.abs(gravity.y * body.gravityScale) * dt;
            float size = Math.min(Math.min(body.extentX(), body.extentY()), minTile * 0.5f);
            worst = Math.max(worst, speed * dt / Math.max(size, 1e-3f));
        }
        return Math.max(1, Math.min(MAX_SUBSTEPS, (int) Math.ceil(worst / 0.5f)));
    }

    private void substep(float h) {
        for (Body2D body : bodies) {
            if (body.type == BodyType.KINEMATIC && isActive(body)) {
                Vector3f p = body.transform().position;
                p.x += body.velocity.x * h;
                p.y += body.velocity.y * h;
            }
        }

        for (Body2D body : bodies) {
            if (body.type != BodyType.DYNAMIC || !isActive(body)) {
                continue;
            }
            Vector3f p = body.transform().position;
            body.velocity.x += gravity.x * body.gravityScale * h;
            body.velocity.y += gravity.y * body.gravityScale * h;
            if (body.linearDamping > 0f) {
                body.velocity.mul(1f / (1f + body.linearDamping * h));
            }
            if (body.carrier != null && body.carrier.type == BodyType.KINEMATIC) {
                p.x += body.carrier.velocity.x * h;
                p.y += body.carrier.velocity.y * h;
            }
            if (body.trigger) {
                p.x += body.velocity.x * h;
                p.y += body.velocity.y * h;
                continue;
            }

            body.previousMinY = body.minY();
            if (body.shape == Body2D.Shape.BOX) {
                moveAxis(body, true, body.velocity.x * h);
                moveAxis(body, false, body.velocity.y * h);
            } else {
                p.x += body.velocity.x * h;
                p.y += body.velocity.y * h;
            }
            resolveOverlaps(body);
        }

        resolveDynamicPairs();
    }

    /** Moves a box along one axis and pushes it back out of any static geometry it entered. */
    private void moveAxis(Body2D body, boolean xAxis, float delta) {
        if (delta == 0f) {
            return;
        }
        Vector3f p = body.transform().position;
        if (xAxis) {
            p.x += delta;
        } else {
            p.y += delta;
        }

        for (Body2D other : bodies) {
            if (other == body || !isObstacle(other) || other.shape != Body2D.Shape.BOX || !body.filters(other)) {
                continue;
            }
            if (!overlaps(body, other.minX(), other.minY(), other.maxX(), other.maxY())) {
                continue;
            }
            if (other.oneWay && !(!xAxis && delta < 0f && body.previousMinY >= other.maxY() - ONE_WAY_TOLERANCE)) {
                continue;
            }
            resolveAxis(body, other, xAxis, delta, other.minX(), other.minY(), other.maxX(), other.maxY());
        }

        for (Tilemap map : tilemaps) {
            int x0 = map.worldToTileX(body.minX() + EPSILON);
            int x1 = map.worldToTileX(body.maxX() - EPSILON);
            int y0 = map.worldToTileY(body.minY() + EPSILON);
            int y1 = map.worldToTileY(body.maxY() - EPSILON);
            float size = map.tileSize();
            for (int ty = y0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++) {
                    if (!map.isSolid(tx, ty)) {
                        continue;
                    }
                    float minX = map.tileToWorldX(tx);
                    float minY = map.tileToWorldY(ty);
                    if (overlaps(body, minX, minY, minX + size, minY + size)) {
                        resolveAxis(body, null, xAxis, delta, minX, minY, minX + size, minY + size);
                    }
                }
            }
        }
    }

    private void resolveAxis(Body2D body, Body2D other, boolean xAxis, float delta,
                             float minX, float minY, float maxX, float maxY) {
        Vector3f p = body.transform().position;
        float normalX = 0f;
        float normalY = 0f;
        if (xAxis) {
            if (delta > 0f) {
                p.x -= body.maxX() - minX;
                body.touchingRight = true;
                normalX = -1f;
            } else {
                p.x += maxX - body.minX();
                body.touchingLeft = true;
                normalX = 1f;
            }
            if (body.velocity.x * delta > 0f) {
                body.velocity.x = bounce(body.velocity.x, restitution(body, other));
            }
        } else {
            if (delta > 0f) {
                p.y -= body.maxY() - minY;
                body.touchingCeiling = true;
                normalY = -1f;
            } else {
                p.y += maxY - body.minY();
                body.onGround = true;
                body.groundBody = other;
                normalY = 1f;
                applyFriction(body, other);
            }
            if (body.velocity.y * delta > 0f) {
                body.velocity.y = bounce(body.velocity.y, restitution(body, other));
            }
        }
        recordCollision(body, other, normalX, normalY);
    }

    /** Minimum-translation resolution against static geometry, for circles and anything left overlapping. */
    private void resolveOverlaps(Body2D body) {
        for (Body2D other : bodies) {
            if (other == body || !isObstacle(other) || !body.filters(other)) {
                continue;
            }
            if (other.oneWay && !(body.velocity.y <= 0f && body.previousMinY >= other.maxY() - ONE_WAY_TOLERANCE)) {
                continue;
            }
            if (!collide(body, other, manifold)) {
                continue;
            }
            if (other.oneWay && manifold.normalY < 0.5f) {
                continue;
            }
            applyContact(body, other, manifold);
        }

        for (Tilemap map : tilemaps) {
            int x0 = map.worldToTileX(body.minX());
            int x1 = map.worldToTileX(body.maxX());
            int y0 = map.worldToTileY(body.minY());
            int y1 = map.worldToTileY(body.maxY());
            float size = map.tileSize();
            for (int ty = y0; ty <= y1; ty++) {
                for (int tx = x0; tx <= x1; tx++) {
                    if (!map.isSolid(tx, ty)) {
                        continue;
                    }
                    float minX = map.tileToWorldX(tx);
                    float minY = map.tileToWorldY(ty);
                    if (collideWithBox(body, minX, minY, minX + size, minY + size, manifold)) {
                        applyContact(body, null, manifold);
                    }
                }
            }
        }
    }

    private void applyContact(Body2D body, Body2D other, Manifold contact) {
        Vector3f p = body.transform().position;
        float nx = contact.normalX;
        float ny = contact.normalY;
        p.x += nx * contact.depth;
        p.y += ny * contact.depth;

        float normalSpeed = body.velocity.x * nx + body.velocity.y * ny;
        if (normalSpeed < 0f) {
            float e = restitution(body, other);
            float change = -(1f + e) * normalSpeed;
            if (-normalSpeed * e < restingSpeed()) {
                change = -normalSpeed;
            }
            body.velocity.x += nx * change;
            body.velocity.y += ny * change;
        }

        if (ny > 0.7f) {
            body.onGround = true;
            body.groundBody = other;
            applyFriction(body, other);
        } else if (ny < -0.7f) {
            body.touchingCeiling = true;
        } else if (nx > 0f) {
            body.touchingLeft = true;
        } else if (nx < 0f) {
            body.touchingRight = true;
        }
        recordCollision(body, other, nx, ny);
    }

    private void resolveDynamicPairs() {
        dynamicScratch.clear();
        for (Body2D body : bodies) {
            if (body.type == BodyType.DYNAMIC && !body.trigger && isActive(body)) {
                dynamicScratch.add(body);
            }
        }
        if (dynamicScratch.size() < 2) {
            return;
        }
        // Sort and sweep on x: only bodies whose x ranges overlap are tested against each other.
        dynamicScratch.sort(Comparator.comparingDouble(Body2D::minX));
        for (int i = 0; i < dynamicScratch.size(); i++) {
            Body2D a = dynamicScratch.get(i);
            for (int j = i + 1; j < dynamicScratch.size(); j++) {
                Body2D b = dynamicScratch.get(j);
                if (b.minX() > a.maxX()) {
                    break;
                }
                if (!a.filters(b) || !collide(a, b, manifold)) {
                    continue;
                }
                float inverseA = a.inverseMass();
                float inverseB = b.inverseMass();
                float total = inverseA + inverseB;
                if (total == 0f) {
                    continue;
                }
                float nx = manifold.normalX;
                float ny = manifold.normalY;
                Vector3f pa = a.transform().position;
                Vector3f pb = b.transform().position;
                pa.x += nx * manifold.depth * inverseA / total;
                pa.y += ny * manifold.depth * inverseA / total;
                pb.x -= nx * manifold.depth * inverseB / total;
                pb.y -= ny * manifold.depth * inverseB / total;

                float relativeNormal = (a.velocity.x - b.velocity.x) * nx + (a.velocity.y - b.velocity.y) * ny;
                if (relativeNormal < 0f) {
                    float e = Math.max(a.restitution, b.restitution);
                    float impulse = -(1f + e) * relativeNormal / total;
                    a.velocity.x += nx * impulse * inverseA;
                    a.velocity.y += ny * impulse * inverseA;
                    b.velocity.x -= nx * impulse * inverseB;
                    b.velocity.y -= ny * impulse * inverseB;
                }
                if (ny > 0.7f) {
                    a.onGround = true;
                    a.groundBody = b;
                } else if (ny < -0.7f) {
                    b.onGround = true;
                    b.groundBody = a;
                }
                recordCollision(a, b, nx, ny);
                recordCollision(b, a, -nx, -ny);
            }
        }
    }

    // --- Events -----------------------------------------------------------------------------------------

    private void recordCollision(Body2D self, Body2D other, float nx, float ny) {
        if (collisionKeys.add(new CollisionKey(self, other, Math.round(nx), Math.round(ny)))) {
            collisionEvents.add(new CollisionEvent(self, other, nx, ny));
        }
    }

    private void fireCollisionEvents() {
        List<CollisionEvent> events = List.copyOf(collisionEvents);
        collisionEvents.clear();
        collisionKeys.clear();
        for (CollisionEvent event : events) {
            if (event.self.entity() == null) {
                continue;
            }
            for (CollisionListener2D listener : event.self.entity().getAll(CollisionListener2D.class)) {
                listener.onCollision(event.self, event.other, event.normalX, event.normalY);
            }
        }
    }

    private List<Runnable> updateTriggers() {
        currentPairs.clear();
        for (int i = 0; i < bodies.size(); i++) {
            Body2D trigger = bodies.get(i);
            if (!trigger.trigger || !isActive(trigger)) {
                continue;
            }
            for (int j = 0; j < bodies.size(); j++) {
                Body2D other = bodies.get(j);
                // Two triggers overlapping form one pair, not two.
                if (other == trigger || (other.trigger && j < i) || !isActive(other) || !trigger.filters(other)) {
                    continue;
                }
                if (collide(trigger, other, manifold)) {
                    currentPairs.add(new TriggerPair(trigger, other));
                }
            }
        }

        List<Runnable> events = new ArrayList<>();
        for (TriggerPair pair : currentPairs) {
            if (!triggerPairs.contains(pair)) {
                events.add(() -> notifyTrigger(pair, true));
            }
        }
        for (TriggerPair pair : triggerPairs) {
            if (!currentPairs.contains(pair)) {
                events.add(() -> notifyTrigger(pair, false));
            }
        }
        Set<TriggerPair> swap = triggerPairs;
        triggerPairs = currentPairs;
        currentPairs = swap;
        return events;
    }

    private static void notifyTrigger(TriggerPair pair, boolean enter) {
        notifyTrigger(pair.trigger, pair.other, enter);
        notifyTrigger(pair.other, pair.trigger, enter);
    }

    private static void notifyTrigger(Body2D self, Body2D other, boolean enter) {
        if (self.entity() == null) {
            return;
        }
        for (CollisionListener2D listener : self.entity().getAll(CollisionListener2D.class)) {
            if (enter) {
                listener.onTriggerEnter(self, other);
            } else {
                listener.onTriggerExit(self, other);
            }
        }
    }

    // --- Queries ----------------------------------------------------------------------------------------

    /** The first non-trigger body containing a point, or null. */
    public Body2D overlapPoint(float x, float y) {
        for (Body2D body : bodies) {
            if (!body.trigger && isActive(body) && body.containsPoint(x, y)) {
                return body;
            }
        }
        return null;
    }

    /** Every active body whose bounds overlap a rectangle, triggers included. */
    public List<Body2D> overlapBox(float minX, float minY, float maxX, float maxY) {
        List<Body2D> found = new ArrayList<>();
        for (Body2D body : bodies) {
            if (isActive(body) && body.maxX() > minX && body.minX() < maxX && body.maxY() > minY && body.minY() < maxY) {
                found.add(body);
            }
        }
        return found;
    }

    /** Whether a solid tile or a non-trigger static/kinematic body occupies a point. */
    public boolean isSolidAt(float x, float y) {
        for (Tilemap map : tilemaps) {
            if (map.isSolid(map.worldToTileX(x), map.worldToTileY(y))) {
                return true;
            }
        }
        for (Body2D body : bodies) {
            if (isObstacle(body) && body.containsPoint(x, y)) {
                return true;
            }
        }
        return false;
    }

    public RaycastHit2D raycast(float originX, float originY, float directionX, float directionY, float maxDistance) {
        return raycast(originX, originY, directionX, directionY, maxDistance, null, -1);
    }

    /**
     * The nearest non-trigger body or solid tile along a ray.
     *
     * @param ignore a body to skip — usually the one casting
     * @param mask   only bodies whose category overlaps this are hit; tiles are always hit
     */
    public RaycastHit2D raycast(float originX, float originY, float directionX, float directionY, float maxDistance,
                                Body2D ignore, int mask) {
        float length = (float) Math.sqrt(directionX * directionX + directionY * directionY);
        if (length == 0f || maxDistance <= 0f) {
            return null;
        }
        float dx = directionX / length;
        float dy = directionY / length;

        RaycastHit2D best = null;
        float bestDistance = maxDistance;
        for (Body2D body : bodies) {
            if (body == ignore || body.trigger || !isActive(body) || (body.category & mask) == 0) {
                continue;
            }
            boolean hit = body.shape == Body2D.Shape.BOX
                    ? rayBox(originX, originY, dx, dy, body.minX(), body.minY(), body.maxX(), body.maxY(), rayScratch)
                    : rayCircle(originX, originY, dx, dy, body.centerX(), body.centerY(), body.radius, rayScratch);
            if (hit && rayScratch[0] <= bestDistance) {
                bestDistance = rayScratch[0];
                best = new RaycastHit2D(body, originX + dx * bestDistance, originY + dy * bestDistance,
                        rayScratch[1], rayScratch[2], bestDistance);
            }
        }
        for (Tilemap map : tilemaps) {
            RaycastHit2D hit = rayTilemap(map, originX, originY, dx, dy, bestDistance);
            if (hit != null && hit.distance() <= bestDistance) {
                bestDistance = hit.distance();
                best = hit;
            }
        }
        return best;
    }

    /** Slab test. Writes distance and normal into {@code out}. */
    private static boolean rayBox(float ox, float oy, float dx, float dy,
                                  float minX, float minY, float maxX, float maxY, float[] out) {
        float tNear = Float.NEGATIVE_INFINITY;
        float tFar = Float.POSITIVE_INFINITY;
        float nx = 0f;
        float ny = 0f;
        if (Math.abs(dx) < 1e-9f) {
            if (ox < minX || ox > maxX) {
                return false;
            }
        } else {
            float t1 = (minX - ox) / dx;
            float t2 = (maxX - ox) / dx;
            if (t1 > t2) {
                float swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tNear) {
                tNear = t1;
                nx = -Math.signum(dx);
                ny = 0f;
            }
            tFar = Math.min(tFar, t2);
        }
        if (Math.abs(dy) < 1e-9f) {
            if (oy < minY || oy > maxY) {
                return false;
            }
        } else {
            float t1 = (minY - oy) / dy;
            float t2 = (maxY - oy) / dy;
            if (t1 > t2) {
                float swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tNear) {
                tNear = t1;
                nx = 0f;
                ny = -Math.signum(dy);
            }
            tFar = Math.min(tFar, t2);
        }
        if (tFar < Math.max(tNear, 0f)) {
            return false;
        }
        if (tNear < 0f) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 0f;
        } else {
            out[0] = tNear;
            out[1] = nx;
            out[2] = ny;
        }
        return true;
    }

    private static boolean rayCircle(float ox, float oy, float dx, float dy, float cx, float cy, float r, float[] out) {
        float mx = ox - cx;
        float my = oy - cy;
        float b = mx * dx + my * dy;
        float c = mx * mx + my * my - r * r;
        if (c > 0f && b > 0f) {
            return false;
        }
        float discriminant = b * b - c;
        if (discriminant < 0f) {
            return false;
        }
        float t = -b - (float) Math.sqrt(discriminant);
        if (t < 0f) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 0f;
            return true;
        }
        out[0] = t;
        out[1] = (ox + dx * t - cx) / r;
        out[2] = (oy + dy * t - cy) / r;
        return true;
    }

    /** Grid traversal (Amanatides–Woo): visits exactly the tiles the ray crosses, in order. */
    private static RaycastHit2D rayTilemap(Tilemap map, float ox, float oy, float dx, float dy, float maxDistance) {
        int tx = map.worldToTileX(ox);
        int ty = map.worldToTileY(oy);
        if (map.isSolid(tx, ty)) {
            return new RaycastHit2D(null, ox, oy, 0f, 0f, 0f);
        }
        int stepX = dx > 0f ? 1 : (dx < 0f ? -1 : 0);
        int stepY = dy > 0f ? 1 : (dy < 0f ? -1 : 0);
        float size = map.tileSize();
        float tMaxX = stepX == 0 ? Float.POSITIVE_INFINITY : (map.tileToWorldX(tx + (stepX > 0 ? 1 : 0)) - ox) / dx;
        float tMaxY = stepY == 0 ? Float.POSITIVE_INFINITY : (map.tileToWorldY(ty + (stepY > 0 ? 1 : 0)) - oy) / dy;
        float tDeltaX = stepX == 0 ? Float.POSITIVE_INFINITY : size / Math.abs(dx);
        float tDeltaY = stepY == 0 ? Float.POSITIVE_INFINITY : size / Math.abs(dy);

        while (true) {
            float t;
            float nx = 0f;
            float ny = 0f;
            if (tMaxX < tMaxY) {
                t = tMaxX;
                tMaxX += tDeltaX;
                tx += stepX;
                nx = -stepX;
            } else {
                t = tMaxY;
                tMaxY += tDeltaY;
                ty += stepY;
                ny = -stepY;
            }
            if (t > maxDistance) {
                return null;
            }
            if (map.isSolid(tx, ty)) {
                return new RaycastHit2D(null, ox + dx * t, oy + dy * t, nx, ny, t);
            }
            boolean leftForGood = (tx < 0 && stepX <= 0) || (tx >= map.width() && stepX >= 0)
                    || (ty < 0 && stepY <= 0) || (ty >= map.height() && stepY >= 0);
            if (leftForGood) {
                return null;
            }
        }
    }

    // --- Geometry helpers -------------------------------------------------------------------------------

    private static boolean isActive(Body2D body) {
        return body.entity() != null && body.isEnabled() && body.entity().isActive();
    }

    private static boolean isObstacle(Body2D body) {
        return body.type != BodyType.DYNAMIC && !body.trigger && isActive(body);
    }

    private static boolean overlaps(Body2D body, float minX, float minY, float maxX, float maxY) {
        return body.maxX() > minX + EPSILON && body.minX() < maxX - EPSILON
                && body.maxY() > minY + EPSILON && body.minY() < maxY - EPSILON;
    }

    private static float restitution(Body2D body, Body2D other) {
        return other == null ? body.restitution : Math.max(body.restitution, other.restitution);
    }

    private float restingSpeed() {
        return Math.abs(gravity.y) * substepDt * 2f + EPSILON;
    }

    private float bounce(float speed, float restitution) {
        float bounced = -speed * restitution;
        return Math.abs(bounced) < restingSpeed() ? 0f : bounced;
    }

    private void applyFriction(Body2D body, Body2D other) {
        float friction = other == null ? body.friction : Math.max(body.friction, other.friction);
        if (friction <= 0f) {
            return;
        }
        // Relative to what is underfoot, so standing on a moving platform is not sliding.
        float surfaceSpeed = other != null && other.type == BodyType.KINEMATIC ? other.velocity.x : 0f;
        float relative = body.velocity.x - surfaceSpeed;
        float reduction = friction * Math.abs(gravity.y) * substepDt;
        if (Math.abs(relative) <= reduction) {
            body.velocity.x = surfaceSpeed;
        } else {
            body.velocity.x -= Math.signum(relative) * reduction;
        }
    }

    /** Overlap between two bodies; the normal points the way {@code a} must move to separate. */
    static boolean collide(Body2D a, Body2D b, Manifold out) {
        if (a.shape == Body2D.Shape.BOX && b.shape == Body2D.Shape.BOX) {
            return boxBox(a.minX(), a.minY(), a.maxX(), a.maxY(), b.minX(), b.minY(), b.maxX(), b.maxY(), out);
        }
        if (a.shape == Body2D.Shape.CIRCLE && b.shape == Body2D.Shape.CIRCLE) {
            return circleCircle(a.centerX(), a.centerY(), a.radius, b.centerX(), b.centerY(), b.radius, out);
        }
        if (a.shape == Body2D.Shape.CIRCLE) {
            return circleBox(a.centerX(), a.centerY(), a.radius, b.minX(), b.minY(), b.maxX(), b.maxY(), out);
        }
        if (!circleBox(b.centerX(), b.centerY(), b.radius, a.minX(), a.minY(), a.maxX(), a.maxY(), out)) {
            return false;
        }
        out.normalX = -out.normalX;
        out.normalY = -out.normalY;
        return true;
    }

    private static boolean collideWithBox(Body2D body, float minX, float minY, float maxX, float maxY, Manifold out) {
        return body.shape == Body2D.Shape.BOX
                ? boxBox(body.minX(), body.minY(), body.maxX(), body.maxY(), minX, minY, maxX, maxY, out)
                : circleBox(body.centerX(), body.centerY(), body.radius, minX, minY, maxX, maxY, out);
    }

    static boolean boxBox(float aMinX, float aMinY, float aMaxX, float aMaxY,
                          float bMinX, float bMinY, float bMaxX, float bMaxY, Manifold out) {
        float overlapX = Math.min(aMaxX - bMinX, bMaxX - aMinX);
        float overlapY = Math.min(aMaxY - bMinY, bMaxY - aMinY);
        if (overlapX <= EPSILON || overlapY <= EPSILON) {
            return false;
        }
        if (overlapX < overlapY) {
            out.normalX = (aMinX + aMaxX) < (bMinX + bMaxX) ? -1f : 1f;
            out.normalY = 0f;
            out.depth = overlapX;
        } else {
            out.normalX = 0f;
            out.normalY = (aMinY + aMaxY) < (bMinY + bMaxY) ? -1f : 1f;
            out.depth = overlapY;
        }
        return true;
    }

    static boolean circleCircle(float ax, float ay, float ar, float bx, float by, float br, Manifold out) {
        float dx = ax - bx;
        float dy = ay - by;
        float radii = ar + br;
        float distanceSquared = dx * dx + dy * dy;
        if (distanceSquared >= radii * radii) {
            return false;
        }
        float distance = (float) Math.sqrt(distanceSquared);
        if (distance < 1e-6f) {
            out.normalX = 0f;
            out.normalY = 1f;
            out.depth = radii;
        } else {
            out.normalX = dx / distance;
            out.normalY = dy / distance;
            out.depth = radii - distance;
        }
        return out.depth > EPSILON;
    }

    static boolean circleBox(float cx, float cy, float r, float minX, float minY, float maxX, float maxY, Manifold out) {
        float closestX = Math.max(minX, Math.min(maxX, cx));
        float closestY = Math.max(minY, Math.min(maxY, cy));
        float dx = cx - closestX;
        float dy = cy - closestY;
        float distanceSquared = dx * dx + dy * dy;
        if (distanceSquared > 0f) {
            if (distanceSquared >= r * r) {
                return false;
            }
            float distance = (float) Math.sqrt(distanceSquared);
            out.normalX = dx / distance;
            out.normalY = dy / distance;
            out.depth = r - distance;
            return out.depth > EPSILON;
        }
        // The centre is inside the box: leave through the nearest face.
        float left = cx - minX;
        float right = maxX - cx;
        float bottom = cy - minY;
        float top = maxY - cy;
        float nearest = Math.min(Math.min(left, right), Math.min(bottom, top));
        if (nearest == top) {
            out.normalX = 0f;
            out.normalY = 1f;
        } else if (nearest == bottom) {
            out.normalX = 0f;
            out.normalY = -1f;
        } else if (nearest == left) {
            out.normalX = -1f;
            out.normalY = 0f;
        } else {
            out.normalX = 1f;
            out.normalY = 0f;
        }
        out.depth = nearest + r;
        return true;
    }
}
