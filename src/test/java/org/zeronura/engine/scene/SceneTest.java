package org.zeronura.engine.scene;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics2d.SpriteRenderer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Entity and component lifecycle, hierarchy and picking — the contracts every game is built on. */
class SceneTest {

    private static final class Recorder extends Component {
        final List<String> log = new ArrayList<>();

        @Override
        protected void onAttach() {
            log.add("attach");
        }

        @Override
        protected void onEnterScene(Scene scene) {
            log.add("enter");
        }

        @Override
        protected void start() {
            log.add("start");
        }

        @Override
        protected void fixedUpdate(float dt) {
            log.add("fixed");
        }

        @Override
        protected void update(float dt) {
            log.add("update");
        }

        @Override
        protected void onExitScene(Scene scene) {
            log.add("exit");
        }
    }

    private static final class Clickable extends Component implements PointerHandler {
    }

    @Test
    void componentsReceiveTheirLifecycleInOrder() {
        Scene scene = new Scene();
        Entity entity = new Entity("thing");
        Recorder recorder = entity.add(new Recorder());
        scene.add(entity);

        scene.fixedUpdate(0.016f);
        scene.update(0.016f);
        scene.update(0.016f);
        entity.destroy();
        scene.update(0.016f);

        assertEquals(List.of("attach", "enter", "start", "fixed", "update", "update", "exit"), recorder.log);
        assertTrue(scene.entities().isEmpty());
    }

    @Test
    void aComponentAddedToAnEntityAlreadyInASceneEntersImmediately() {
        Scene scene = new Scene();
        Entity entity = scene.createEntity("thing");
        Recorder recorder = entity.add(new Recorder());

        assertEquals(List.of("attach", "enter"), recorder.log);
    }

    @Test
    void disabledComponentsAndInactiveEntitiesDoNotUpdate() {
        Scene scene = new Scene();
        Entity parent = scene.createEntity("parent");
        Entity child = parent.addChild(new Entity("child"));
        Recorder onChild = child.add(new Recorder());
        Recorder disabled = scene.createEntity("other").add(new Recorder());
        disabled.setEnabled(false);

        parent.setActive(false);
        scene.update(0.016f);

        assertFalse(child.isActive());
        assertFalse(onChild.log.contains("update"));
        assertFalse(disabled.log.contains("update"));
    }

    @Test
    void aChildMovesWithItsParent() {
        Entity parent = new Entity("parent");
        Entity child = parent.addChild(new Entity("child"));
        parent.transform().setPosition(10f, 0f, 0f);
        parent.transform().setRotation2D((float) (Math.PI / 2));
        child.transform().setPosition(1f, 0f, 0f);

        Vector3f world = child.transform().worldPosition(new Vector3f());

        assertEquals(10f, world.x, 1e-4f);
        assertEquals(1f, world.y, 1e-4f);
    }

    @Test
    void destroyingAParentRemovesItsChildren() {
        Scene scene = new Scene();
        Entity parent = new Entity("parent");
        parent.addChild(new Entity("child"));
        scene.add(parent);
        assertEquals(2, scene.entities().size());

        parent.destroy();
        scene.update(0.016f);

        assertTrue(scene.entities().isEmpty());
    }

    @Test
    void anEntityCannotBecomeItsOwnAncestor() {
        Entity a = new Entity("a");
        Entity b = a.addChild(new Entity("b"));
        assertThrows(IllegalArgumentException.class, () -> b.addChild(a));
    }

    @Test
    void entitiesCanBeFoundByNameAndTag() {
        Scene scene = new Scene();
        scene.createEntity("player").tag("hero");
        scene.createEntity("coin").tag("pickup");
        scene.createEntity("coin").tag("pickup");

        assertEquals("player", scene.find("player").name());
        assertEquals(2, scene.findWithTag("pickup").size());
        assertNull(scene.find("dragon"));
    }

    @Test
    void pickReturnsTheTopmostClickableSprite() {
        Scene scene = new Scene();
        Entity low = scene.createEntity("low", 0f, 0f);
        low.add(new SpriteRenderer(Color.RED, 10f, 10f));
        low.add(new Clickable());
        Entity high = scene.createEntity("high", 2f, 2f);
        high.add(new SpriteRenderer(Color.RED, 10f, 10f)).setLayer(5);
        high.add(new Clickable());
        scene.createEntity("decoration", 0f, 0f).add(new SpriteRenderer(Color.RED, 100f, 100f)).setLayer(10);

        assertSame(high, scene.pick(1f, 1f), "overlap: the higher layer wins");
        assertSame(low, scene.pick(-4f, -4f));
        assertNull(scene.pick(40f, 40f), "sprites without a pointer handler are not clickable");
    }

    @Test
    void spriteHitTestsFollowRotationAndScale() {
        Entity entity = new Entity("bar");
        SpriteRenderer sprite = entity.add(new SpriteRenderer(Color.RED, 10f, 2f));
        entity.transform().setRotation2D((float) (Math.PI / 2));

        assertTrue(sprite.contains(0f, 4f));
        assertFalse(sprite.contains(4f, 0f));

        entity.transform().scale.x = 0f;
        assertFalse(sprite.contains(0f, 0f), "a sprite squashed to nothing cannot be hit");
    }

    @Test
    void sceneTimersAndTweensAdvanceWithUpdates() {
        Scene scene = new Scene();
        int[] fired = {0};
        float[] value = {0f};
        scene.scheduler().after(0.5f, () -> fired[0]++);
        scene.tweens().to(0f, 1f, 1f, org.zeronura.engine.gui.Ease.LINEAR, v -> value[0] = v);

        for (int i = 0; i < 30; i++) {
            scene.update(1f / 60f);
        }

        assertEquals(1, fired[0]);
        assertEquals(0.5f, value[0], 1e-3f);
    }
}
