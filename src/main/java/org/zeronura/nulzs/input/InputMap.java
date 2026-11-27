package org.zeronura.nulzs.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Named actions ("jump", "fire") bound to one or more keys or mouse buttons.
 *
 * <p>Gameplay asks about actions, never keys, so rebinding is a change to this map and nothing else — and
 * a keyboard key and a mouse button can mean the same thing without every call site checking both.
 *
 * <p>Controller buttons and stick directions are bound beside the keys ({@link #bindPad}, {@link #bindStick}): a fixed
 * layout the game sets, not rebound by the player. A stick bound to an action makes it analogue — {@link #strength}
 * and {@link #axis} read how far it leans — and down once it leans past half way.
 *
 * <p>An action {@linkplain #define defined} with a label and its default bindings is one the player can rebind: a
 * settings screen lists it from {@link #definitions()}, and what the player changed is {@link #saved()} as text and
 * {@link #restore}d next time.
 */
public final class InputMap {

    /** One physical input an action listens to. */
    public record Binding(int code, boolean mouse) {
        public static Binding key(int key) {
            return new Binding(key, false);
        }

        public static Binding mouse(int button) {
            return new Binding(button, true);
        }

        /** How it is saved: a key's name ({@code "LEFT_SHIFT"}), or {@code "MOUSE_LEFT"} for a button. */
        public String save() {
            if (!mouse) {
                return Key.name(code);
            }
            return switch (code) {
                case MouseButton.LEFT -> "MOUSE_LEFT";
                case MouseButton.RIGHT -> "MOUSE_RIGHT";
                case MouseButton.MIDDLE -> "MOUSE_MIDDLE";
                default -> "MOUSE_" + code;
            };
        }

        /** The binding a {@link #save()}d name stands for, or null for one that names nothing. */
        public static Binding parse(String text) {
            String name = text.trim().toUpperCase(Locale.ROOT);
            if (name.startsWith("MOUSE_")) {
                return switch (name) {
                    case "MOUSE_LEFT" -> mouse(MouseButton.LEFT);
                    case "MOUSE_RIGHT" -> mouse(MouseButton.RIGHT);
                    case "MOUSE_MIDDLE" -> mouse(MouseButton.MIDDLE);
                    default -> {
                        try {
                            yield mouse(Integer.parseInt(name.substring(6)));
                        } catch (NumberFormatException e) {
                            yield null;
                        }
                    }
                };
            }
            int key = Key.code(name);
            return key < 0 ? null : key(key);
        }

        /** As a player reads it: {@code "Left Shift"}, {@code "Right click"}. */
        public String label() {
            if (!mouse) {
                return Key.label(code);
            }
            return switch (code) {
                case MouseButton.LEFT -> "Left click";
                case MouseButton.RIGHT -> "Right click";
                case MouseButton.MIDDLE -> "Middle click";
                default -> "Mouse " + (code + 1);
            };
        }
    }

    /**
     * An action the player can rebind.
     *
     * @param group    the heading it is listed under, such as "Movement"
     * @param defaults what it is bound to until the player changes it
     */
    public record Definition(String action, String label, String group, List<Binding> defaults) {
        public Definition {
            defaults = List.copyOf(defaults);
        }
    }

    /** How many bindings a rebindable action can have: a main one and a spare. */
    public static final int SLOTS = 2;

    private final Input input;
    private final Map<String, List<Binding>> bindings = new LinkedHashMap<>();
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    /** What the player saved, by action, kept for actions a game has not defined yet. */
    private final Map<String, List<Binding>> saved = new HashMap<>();

    /** A controller button, or a stick direction: {@code axis} leant toward {@code sign}. */
    public record PadBinding(int button, int axis, float sign) {
        public static PadBinding button(int button) {
            return new PadBinding(button, -1, 0f);
        }

        public static PadBinding stick(int axis, float sign) {
            return new PadBinding(-1, axis, Math.signum(sign));
        }

        public boolean isStick() {
            return axis >= 0;
        }

        /** As a player reads it: "A", "Left stick". */
        public String label() {
            if (!isStick()) {
                return Gamepad.label(button);
            }
            return axis == Gamepad.LEFT_X || axis == Gamepad.LEFT_Y ? "Left stick"
                    : axis == Gamepad.RIGHT_X || axis == Gamepad.RIGHT_Y ? "Right stick" : "Trigger";
        }
    }

    private static final float STICK_DOWN = 0.5f;

    private final Map<String, List<PadBinding>> padBindings = new LinkedHashMap<>();
    /** Whether each action's controller bindings were down last frame, for presses made with a stick. */
    private final Map<String, Boolean> padWasDown = new HashMap<>();

    InputMap(Input input) {
        this.input = input;
    }

    /** Adds controller buttons to an action. */
    public InputMap bindPad(String action, int... buttons) {
        List<PadBinding> list = padBindings.computeIfAbsent(action, a -> new ArrayList<>());
        for (int button : buttons) {
            if (!list.contains(PadBinding.button(button))) {
                list.add(PadBinding.button(button));
            }
        }
        return this;
    }

    /** Adds a stick direction to an action: {@code axis} leant toward the sign of {@code sign}. */
    public InputMap bindStick(String action, int axis, float sign) {
        List<PadBinding> list = padBindings.computeIfAbsent(action, a -> new ArrayList<>());
        if (!list.contains(PadBinding.stick(axis, sign))) {
            list.add(PadBinding.stick(axis, sign));
        }
        return this;
    }

    /** Whether prompts should name controller buttons: the player is on a controller or the touch screen's buttons. */
    public boolean promptsForPad() {
        return input.device() != Input.Device.KEYBOARD_MOUSE;
    }

    public List<PadBinding> padBindingsOf(String action) {
        List<PadBinding> list = padBindings.get(action);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** How strongly the controller holds an action, 0 to 1: 1 for a held button, how far a bound stick leans. */
    public float padStrength(String action) {
        float strength = 0f;
        Gamepad pad = input.gamepad();
        for (PadBinding binding : padBindingsOf(action)) {
            if (binding.isStick()) {
                strength = Math.max(strength, pad.axis(binding.axis()) * binding.sign());
            } else if (pad.isDown(binding.button()) && !input.isPadClaimed(binding.button())) {
                strength = 1f;
            }
        }
        return Math.max(0f, Math.min(1f, strength));
    }

    private boolean padDown(String action) {
        return padStrength(action) >= STICK_DOWN;
    }

    /** How strongly an action is held, 0 to 1: 1 for a key, how far the stick leans for a stick. */
    public float strength(String action) {
        return keysDown(action) ? 1f : padStrength(action);
    }

    /** Called by {@link Input} as a frame begins, to remember which actions the controller held. */
    void endFrame() {
        padWasDown.clear();
        for (String action : padBindings.keySet()) {
            padWasDown.put(action, padDown(action));
        }
    }

    /** Adds keys to an action, keeping any bindings it already has. */
    public InputMap bindKeys(String action, int... keys) {
        List<Binding> list = bindings.computeIfAbsent(action, a -> new ArrayList<>());
        for (int key : keys) {
            list.add(Binding.key(key));
        }
        return this;
    }

    /** Adds mouse buttons to an action, keeping any bindings it already has. */
    public InputMap bindMouse(String action, int... mouseButtons) {
        List<Binding> list = bindings.computeIfAbsent(action, a -> new ArrayList<>());
        for (int button : mouseButtons) {
            list.add(Binding.mouse(button));
        }
        return this;
    }

    /**
     * Makes an action rebindable: gives it a label, a group and its default bindings, and binds it to what the player
     * saved for it if they did, otherwise to the defaults. Defining it again replaces it.
     */
    public InputMap define(String action, String label, String group, Binding... defaults) {
        Definition definition = new Definition(action, label, group, List.of(defaults));
        definitions.put(action, definition);
        List<Binding> chosen = saved.get(action);
        bindings.put(action, new ArrayList<>(chosen != null ? chosen : definition.defaults()));
        return this;
    }

    /** The rebindable actions, in the order they were defined. */
    public List<Definition> definitions() {
        return List.copyOf(definitions.values());
    }

    /**
     * Binds one of a rebindable action's {@link #SLOTS} to {@code binding}, or empties it for null. Filling a slot
     * past the last one in use adds a binding; emptying one moves any after it up.
     */
    public void rebind(String action, int slot, Binding binding) {
        List<Binding> list = bindings.computeIfAbsent(action, a -> new ArrayList<>());
        if (binding == null) {
            if (slot < list.size()) {
                list.remove(slot);
            }
        } else if (slot < list.size()) {
            list.set(slot, binding);
        } else if (!list.contains(binding)) {
            list.add(binding);
        }
        // The same input twice in one action is one binding.
        List<Binding> distinct = new ArrayList<>(list.stream().distinct().toList());
        list.clear();
        list.addAll(distinct);
        saved.put(action, List.copyOf(list));
    }

    /** Puts every rebindable action back on its defaults. */
    public void resetToDefaults() {
        saved.clear();
        for (Definition definition : definitions.values()) {
            bindings.put(definition.action(), new ArrayList<>(definition.defaults()));
        }
    }

    /** Whether a rebindable action is bound as it is by default. */
    public boolean isDefault(String action) {
        Definition definition = definitions.get(action);
        return definition == null || definition.defaults().equals(bindingsOf(action));
    }

    /** The other rebindable actions also bound to {@code binding}, for a settings screen to warn about. */
    public List<Definition> sharing(String action, Binding binding) {
        List<Definition> others = new ArrayList<>();
        for (Definition definition : definitions.values()) {
            if (!definition.action().equals(action) && bindingsOf(definition.action()).contains(binding)) {
                others.add(definition);
            }
        }
        return others;
    }

    /**
     * The rebindable actions the player has changed, each as its bindings' saved names joined by commas (empty when
     * it is unbound), for a settings file.
     */
    public Map<String, String> saved() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Definition definition : definitions.values()) {
            if (!isDefault(definition.action())) {
                out.put(definition.action(), String.join(",", bindingsOf(definition.action()).stream().map(Binding::save).toList()));
            }
        }
        // Changes to actions this game has not defined (another game's, in the same file) are kept as they were.
        for (Map.Entry<String, List<Binding>> entry : saved.entrySet()) {
            if (!definitions.containsKey(entry.getKey())) {
                out.put(entry.getKey(), String.join(",", entry.getValue().stream().map(Binding::save).toList()));
            }
        }
        return out;
    }

    /**
     * Takes the player's bindings as {@link #saved()} wrote them: those for defined actions apply now, the rest when
     * their action is defined. Names that stand for nothing are skipped.
     */
    public void restore(Map<String, String> savedBindings) {
        for (Map.Entry<String, String> entry : savedBindings.entrySet()) {
            List<Binding> list = new ArrayList<>();
            for (String name : entry.getValue().split(",")) {
                Binding binding = name.isBlank() ? null : Binding.parse(name);
                if (binding != null && !list.contains(binding) && list.size() < SLOTS) {
                    list.add(binding);
                }
            }
            saved.put(entry.getKey(), List.copyOf(list));
            if (definitions.containsKey(entry.getKey())) {
                bindings.put(entry.getKey(), new ArrayList<>(list));
            }
        }
    }

    /** Removes every binding for an action. */
    public void clear(String action) {
        bindings.remove(action);
    }

    public boolean isDown(String action) {
        return keysDown(action) || padDown(action);
    }

    private boolean keysDown(String action) {
        for (Binding binding : bindingsOf(action)) {
            if (binding.mouse() ? input.isMouseDown(binding.code()) : input.isKeyDown(binding.code())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True on the frame the action became active. With two keys bound, pressing the second while the
     * first is held is not a new press — the action was already down.
     */
    public boolean isPressed(String action) {
        boolean padNow = padDown(action);
        boolean padBefore = padWasDown.getOrDefault(action, false);
        if (padNow && padBefore) {
            return false;
        }
        boolean anyPressed = padNow;
        for (Binding binding : bindingsOf(action)) {
            boolean down = binding.mouse() ? input.isMouseDown(binding.code()) : input.isKeyDown(binding.code());
            boolean pressed = binding.mouse()
                    ? input.isMousePressed(binding.code())
                    : input.isKeyPressed(binding.code());
            if (down && !pressed) {
                return false;
            }
            anyPressed |= pressed;
        }
        return anyPressed;
    }

    /** True on the frame the last held binding of the action came up. */
    public boolean isReleased(String action) {
        if (padDown(action)) {
            return false;
        }
        boolean anyReleased = padWasDown.getOrDefault(action, false);
        for (Binding binding : bindingsOf(action)) {
            boolean down = binding.mouse() ? input.isMouseDown(binding.code()) : input.isKeyDown(binding.code());
            if (down) {
                return false;
            }
            anyReleased |= binding.mouse()
                    ? input.isMouseReleased(binding.code())
                    : input.isKeyReleased(binding.code());
        }
        return anyReleased;
    }

    /**
     * -1 while only {@code negative} is held, +1 while only {@code positive} is, 0 for both or neither — and in between
     * for a stick, by how far it leans.
     */
    public float axis(String negative, String positive) {
        return Math.max(-1f, Math.min(1f, strength(positive) - strength(negative)));
    }

    public List<Binding> bindingsOf(String action) {
        List<Binding> list = bindings.get(action);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** Every action and its bindings, in the order they were first bound — for a rebinding screen. */
    public Map<String, List<Binding>> all() {
        return Collections.unmodifiableMap(bindings);
    }
}
