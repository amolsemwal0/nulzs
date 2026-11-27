package org.zeronura.nulzs.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InputMapTest {

    private final Input input = new Input();
    private final InputMap actions = input.actions();

    @Test
    void anActionIsPressedOnTheFrameAnyBindingGoesDown() {
        actions.bindKeys("jump", Key.SPACE, Key.W);

        input.beginFrame();
        input.setKey(Key.SPACE, true);
        assertTrue(actions.isPressed("jump"));
        assertTrue(actions.isDown("jump"));

        input.beginFrame();
        assertFalse(actions.isPressed("jump"), "still held is not a new press");
        assertTrue(actions.isDown("jump"));
    }

    @Test
    void pressingASecondBindingWhileTheFirstIsHeldIsNotANewPress() {
        actions.bindKeys("jump", Key.SPACE, Key.W);
        input.beginFrame();
        input.setKey(Key.SPACE, true);

        input.beginFrame();
        input.setKey(Key.W, true);
        assertFalse(actions.isPressed("jump"));

        input.beginFrame();
        input.setKey(Key.SPACE, false);
        assertFalse(actions.isReleased("jump"), "W is still holding it");

        input.beginFrame();
        input.setKey(Key.W, false);
        assertTrue(actions.isReleased("jump"));
    }

    @Test
    void keysAndMouseButtonsCanShareAnAction() {
        actions.bindKeys("fire", Key.F).bindMouse("fire", MouseButton.LEFT);
        input.beginFrame();
        input.setMouseButton(MouseButton.LEFT, true);
        assertTrue(actions.isPressed("fire"));
    }

    @Test
    void axesCombineTwoActions() {
        actions.bindKeys("left", Key.A).bindKeys("right", Key.D);
        input.beginFrame();
        assertEquals(0f, actions.axis("left", "right"));
        input.setKey(Key.D, true);
        assertEquals(1f, actions.axis("left", "right"));
        input.setKey(Key.A, true);
        assertEquals(0f, actions.axis("left", "right"));
    }

    @Test
    void consumingAKeyHidesThisFramesPress() {
        input.beginFrame();
        input.setKey(Key.ESCAPE, true);
        assertTrue(input.isKeyPressed(Key.ESCAPE));
        input.consumeKey(Key.ESCAPE);
        assertFalse(input.isKeyPressed(Key.ESCAPE));
        assertTrue(input.isKeyDown(Key.ESCAPE));
    }

    @Test
    void unboundActionsAreSimplyInactive() {
        assertFalse(actions.isDown("nothing"));
        assertFalse(actions.isPressed("nothing"));
    }

    // --- Rebinding ------------------------------------------------------------------------------------------

    @Test
    void aDefinedActionStartsOnItsDefaultsAndCanBeRebound() {
        actions.define("interact", "Interact", "Actions", InputMap.Binding.key(Key.E));
        assertEquals(java.util.List.of(InputMap.Binding.key(Key.E)), actions.bindingsOf("interact"));
        assertTrue(actions.isDefault("interact"));

        actions.rebind("interact", 0, InputMap.Binding.key(Key.F));
        actions.rebind("interact", 1, InputMap.Binding.mouse(MouseButton.MIDDLE));
        assertEquals(java.util.List.of(InputMap.Binding.key(Key.F), InputMap.Binding.mouse(MouseButton.MIDDLE)),
                actions.bindingsOf("interact"));
        assertFalse(actions.isDefault("interact"));

        input.beginFrame();
        input.setKey(Key.F, true);
        assertTrue(actions.isPressed("interact"));

        actions.rebind("interact", 0, null);
        assertEquals(java.util.List.of(InputMap.Binding.mouse(MouseButton.MIDDLE)), actions.bindingsOf("interact"),
                "emptying a slot moves the next one up");
        actions.resetToDefaults();
        assertTrue(actions.isDefault("interact"));
    }

    @Test
    void savedBindingsComeBackAndWaitForTheirActionToBeDefined() {
        actions.define("menu", "Menu", "Menus", InputMap.Binding.key(Key.TAB));
        actions.rebind("menu", 0, InputMap.Binding.key(Key.M));
        java.util.Map<String, String> saved = actions.saved();
        assertEquals(java.util.Map.of("menu", "M"), saved);

        InputMap fresh = new Input().actions();
        fresh.restore(java.util.Map.of("menu", "M,MOUSE_RIGHT", "jump", "", "nonsense", "NOT_A_KEY"));
        fresh.define("menu", "Menu", "Menus", InputMap.Binding.key(Key.TAB));
        fresh.define("jump", "Jump", "Movement", InputMap.Binding.key(Key.SPACE));
        assertEquals(java.util.List.of(InputMap.Binding.key(Key.M), InputMap.Binding.mouse(MouseButton.RIGHT)),
                fresh.bindingsOf("menu"));
        assertTrue(fresh.bindingsOf("jump").isEmpty(), "an action saved with nothing stays unbound");
    }

    @Test
    void keysHaveNamesToSaveAndLabelsToShow() {
        assertEquals("LEFT_SHIFT", Key.name(Key.LEFT_SHIFT));
        assertEquals(Key.LEFT_SHIFT, Key.code("left_shift"));
        assertEquals(-1, Key.code("NOT_A_KEY"));
        assertEquals("Left Shift", Key.label(Key.LEFT_SHIFT));
        assertEquals("1", Key.label(Key.NUM_1));
        assertEquals("Left Ctrl", Key.label(Key.LEFT_CONTROL));
        assertEquals("W", InputMap.Binding.key(Key.W).label());
        assertEquals("Middle click", InputMap.Binding.mouse(MouseButton.MIDDLE).label());
        assertEquals(InputMap.Binding.mouse(MouseButton.MIDDLE), InputMap.Binding.parse("MOUSE_MIDDLE"));
    }

    @Test
    void theStandardMovementActionsDriveTheAxes() {
        StandardActions.define(actions);
        input.beginFrame();
        input.setKey(Key.W, true);
        assertEquals(1f, actions.axis(StandardActions.MOVE_BACK, StandardActions.MOVE_FORWARD));
        actions.rebind(StandardActions.MOVE_FORWARD, 0, InputMap.Binding.key(Key.Z));
        assertEquals(0f, actions.axis(StandardActions.MOVE_BACK, StandardActions.MOVE_FORWARD), "W no longer walks");
    }
}
