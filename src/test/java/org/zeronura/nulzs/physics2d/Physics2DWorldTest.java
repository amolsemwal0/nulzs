package org.zeronura.nulzs.physics2d;

import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.graphics2d.Tilemap;
import org.zeronura.nulzs.scene.Component;
import org.zeronura.nulzs.scene.Entity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Physics2DWorldTest {

    private static final float DT = 1f / 60f;

    private final Physics2DWorld world = new Physics2DWorld();

    private Body2D spawn(Body2D body, float x, float y) {
        Entity entity = new Entity("body");
        entity.transform().setPosition(x, y);
        entity.add(body);
        world.add(body);
        return body;
    }

    private void simulate(float seconds) {
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) {
            world.step(DT);
        }
    }

    @Test
    void aFallingBoxComesToRestOnTheGround() {
        spawn(Body2D.box(BodyType.STATIC, 20f, 1f), 0f, 0f);
        Body2D box = spawn(Body2D.box(BodyType.DYNAMIC, 1f, 1f), 0f, 5f);

        simulate(2f);

        assertEquals(1f, box.centerY(), 1e-3f);
        assertTrue(box.isOnGround());
        assertEquals(0f, box.velocity.y, 1e-3f);
    }

    @Test
    void runningIntoAWallStopsFlushAgainstIt() {
        world.gravity.set(0f, 0f);
        spawn(Body2D.box(BodyType.STATIC, 1f, 10f), 5f, 0f);
        Body2D box = spawn(Body2D.box(BodyType.DYNAMIC, 1f, 1f), 0f, 0f);

        for (int i = 0; i < 60; i++) {
            box.velocity.x = 10f;
            world.step(DT);
        }

        assertEquals(4f, box.centerX(), 1e-3f);
        assertTrue(box.isTouchingRight());
    }

    @Test
    void slidingAlongATileFloorNeverCatchesOnTheSeams() {
        Tilemap floor = Tilemap.fromAscii(1f, Map.of('#', 1), "##############################");
        floor.setSolid(1);
        world.addTilemap(floor);
        Body2D runner = spawn(Body2D.box(BodyType.DYNAMIC, 0.8f, 0.8f), 1f, 1.4f);

        for (int i = 0; i < 60; i++) {
            runner.velocity.x = 5f;
            world.step(DT);
        }

        assertEquals(6f, runner.centerX(), 0.05f);
        assertEquals(1.4f, runner.centerY(), 1e-3f);
        assertTrue(runner.isOnGround());
    }

    @Test
    void triggersReportOneEnterAndOneExit() {
        world.gravity.set(0f, 0f);
        final class Counter extends Component implements CollisionListener2D {
            int enters;
            int exits;

            @Override
            public void onTriggerEnter(Body2D self, Body2D other) {
                enters++;
            }

            @Override
            public void onTriggerExit(Body2D self, Body2D other) {
                exits++;
            }
        }
        Entity zone = new Entity("zone");
        Counter counter = zone.add(new Counter());
        world.add(zone.add(Body2D.box(BodyType.STATIC, 2f, 2f).asTrigger()));
        Body2D mover = spawn(Body2D.box(BodyType.DYNAMIC, 0.5f, 0.5f), -5f, 0f);
        mover.velocity.x = 5f;

        simulate(2.2f);

        assertEquals(1, counter.enters);
        assertEquals(1, counter.exits);
    }

    @Test
    void oneWayPlatformsAreJumpedThroughAndStoodOn() {
        spawn(Body2D.box(BodyType.STATIC, 4f, 0.5f).asOneWay(), 0f, 3f);
        Body2D jumper = spawn(Body2D.box(BodyType.DYNAMIC, 1f, 1f), 0f, 0f);
        jumper.velocity.y = 18f;

        simulate(2f);

        assertEquals(3.75f, jumper.centerY(), 1e-3f);
        assertTrue(jumper.isOnGround());
    }

    @Test
    void aCircleRestsOnABox() {
        spawn(Body2D.box(BodyType.STATIC, 20f, 1f), 0f, 0f);
        Body2D ball = spawn(Body2D.circle(BodyType.DYNAMIC, 0.5f), 0f, 3f);

        simulate(2f);

        assertEquals(1f, ball.centerY(), 1e-2f);
        assertTrue(ball.isOnGround());
    }

    @Test
    void overlappingDynamicBodiesArePushedApart() {
        world.gravity.set(0f, 0f);
        Body2D a = spawn(Body2D.box(BodyType.DYNAMIC, 1f, 1f), 0f, 0f);
        Body2D b = spawn(Body2D.box(BodyType.DYNAMIC, 1f, 1f), 0.5f, 0f);

        world.step(DT);

        assertTrue(b.minX() >= a.maxX() - 1e-3f);
    }

    @Test
    void raycastsFindTheNearestBodyOrTile() {
        Tilemap map = Tilemap.fromAscii(1f, Map.of('#', 1), "    #");
        map.setSolid(1);
        world.addTilemap(map);
        Body2D crate = spawn(Body2D.box(BodyType.STATIC, 1f, 1f), 2.5f, 0.5f);

        RaycastHit2D hit = world.raycast(0f, 0.5f, 1f, 0f, 10f);
        assertSame(crate, hit.body());
        assertEquals(2f, hit.distance(), 1e-4f);
        assertEquals(-1f, hit.normalX());

        world.remove(crate);
        hit = world.raycast(0f, 0.5f, 1f, 0f, 10f);
        assertNull(hit.body());
        assertEquals(4f, hit.distance(), 1e-4f);
        assertEquals(-1f, hit.normalX());

        assertNull(world.raycast(0f, 0.5f, -1f, 0f, 10f));
    }

    @Test
    void kinematicPlatformsCarryWhatStandsOnThem() {
        Body2D platform = spawn(Body2D.box(BodyType.KINEMATIC, 4f, 0.5f), 0f, 0f);
        platform.velocity.x = 2f;
        Body2D rider = spawn(Body2D.box(BodyType.DYNAMIC, 1f, 1f), 0f, 0.75f);

        simulate(1f);

        assertEquals(platform.centerX(), rider.centerX(), 0.1f);
        assertTrue(rider.isOnGround());
        assertSame(platform, rider.groundBody());
    }

    @Test
    void collisionFiltersKeepGroupsApart() {
        spawn(Body2D.box(BodyType.STATIC, 20f, 1f).withCollisionFilter(0b01, -1), 0f, 0f);
        Body2D ghost = spawn(Body2D.box(BodyType.DYNAMIC, 1f, 1f).withCollisionFilter(0b10, 0b10), 0f, 2f);

        simulate(1f);

        assertTrue(ghost.centerY() < -1f, "a body that does not collide with the floor falls through it");
    }
}
