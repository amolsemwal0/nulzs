package org.zeronura.nulzs.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GamepadInputTest {

    private final Input input = new Input();
    private final InputMap actions = input.actions();

    @Test
    void aControllerButtonBoundToAnActionPressesIt() {
        actions.bindKeys("confirm", Key.ENTER).bindPad("confirm", Gamepad.A);
        input.beginFrame();
        input.gamepad().set(Gamepad.A, true);
        assertTrue(actions.isPressed("confirm"));
        assertTrue(actions.isDown("confirm"));

        input.beginFrame();
        assertFalse(actions.isPressed("confirm"), "held is not a new press");

        input.beginFrame();
        input.gamepad().set(Gamepad.A, false);
        assertTrue(actions.isReleased("confirm"));
    }

    @Test
    void aStickMakesMovementAnalogue() {
        actions.bindKeys("left", Key.A).bindKeys("right", Key.D);
        actions.bindStick("left", Gamepad.LEFT_X, -1f).bindStick("right", Gamepad.LEFT_X, 1f);
        input.beginFrame();
        input.gamepad().setAxis(Gamepad.LEFT_X, 0.4f);
        assertEquals(0.4f, actions.axis("left", "right"), 1e-5f);
        assertFalse(actions.isDown("right"), "a light lean is not a press");
        input.gamepad().setAxis(Gamepad.LEFT_X, -0.9f);
        assertEquals(-0.9f, actions.axis("left", "right"), 1e-5f);
        assertTrue(actions.isDown("left"));
        input.setKey(Key.D, true);
        assertEquals(0.1f, actions.axis("left", "right"), 1e-5f, "a key counts as full");
    }

    @Test
    void aButtonTheGuiClaimsIsNotSeenByActions() {
        actions.bindPad("interact", Gamepad.A);
        input.beginFrame();
        input.gamepad().set(Gamepad.A, true);
        input.claimPad(Gamepad.A);
        assertFalse(actions.isPressed("interact"));
        assertFalse(actions.isDown("interact"));
    }

    @Test
    void bindingTheSameButtonTwiceKeepsOne() {
        actions.bindPad("menu", Gamepad.START).bindPad("menu", Gamepad.START);
        assertEquals(1, actions.padBindingsOf("menu").size());
    }

    @Test
    void buttonsHaveSavedNamesThatReadBack() {
        for (int button = 0; button < Gamepad.BUTTON_COUNT; button++) {
            assertEquals(button, Gamepad.code(Gamepad.saveName(button)), Gamepad.saveName(button));
        }
    }

    @Test
    void theVirtualPointerClicksWhereTheControllerPoints() {
        input.beginFrame();
        input.setVirtualPointer(300f, 200f, true);
        assertEquals(300f, input.mouseX());
        assertTrue(input.isMousePressed(MouseButton.LEFT));
        assertTrue(input.isPointerVirtual());
        input.beginFrame();
        assertFalse(input.isPointerVirtual(), "only for the frame it is set");
    }
}
