package org.zeronura.engine.input;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/** Keyboard key codes, so games do not have to import GLFW. Any GLFW key code also works directly. */
public final class Key {

    private Key() {
    }

    public static final int SPACE = GLFW_KEY_SPACE;
    public static final int APOSTROPHE = GLFW_KEY_APOSTROPHE;
    public static final int COMMA = GLFW_KEY_COMMA;
    public static final int MINUS = GLFW_KEY_MINUS;
    public static final int PERIOD = GLFW_KEY_PERIOD;
    public static final int SLASH = GLFW_KEY_SLASH;
    public static final int NUM_0 = GLFW_KEY_0;
    public static final int NUM_1 = GLFW_KEY_1;
    public static final int NUM_2 = GLFW_KEY_2;
    public static final int NUM_3 = GLFW_KEY_3;
    public static final int NUM_4 = GLFW_KEY_4;
    public static final int NUM_5 = GLFW_KEY_5;
    public static final int NUM_6 = GLFW_KEY_6;
    public static final int NUM_7 = GLFW_KEY_7;
    public static final int NUM_8 = GLFW_KEY_8;
    public static final int NUM_9 = GLFW_KEY_9;
    public static final int A = GLFW_KEY_A;
    public static final int B = GLFW_KEY_B;
    public static final int C = GLFW_KEY_C;
    public static final int D = GLFW_KEY_D;
    public static final int E = GLFW_KEY_E;
    public static final int F = GLFW_KEY_F;
    public static final int G = GLFW_KEY_G;
    public static final int H = GLFW_KEY_H;
    public static final int I = GLFW_KEY_I;
    public static final int J = GLFW_KEY_J;
    public static final int K = GLFW_KEY_K;
    public static final int L = GLFW_KEY_L;
    public static final int M = GLFW_KEY_M;
    public static final int N = GLFW_KEY_N;
    public static final int O = GLFW_KEY_O;
    public static final int P = GLFW_KEY_P;
    public static final int Q = GLFW_KEY_Q;
    public static final int R = GLFW_KEY_R;
    public static final int S = GLFW_KEY_S;
    public static final int T = GLFW_KEY_T;
    public static final int U = GLFW_KEY_U;
    public static final int V = GLFW_KEY_V;
    public static final int W = GLFW_KEY_W;
    public static final int X = GLFW_KEY_X;
    public static final int Y = GLFW_KEY_Y;
    public static final int Z = GLFW_KEY_Z;
    public static final int ESCAPE = GLFW_KEY_ESCAPE;
    public static final int ENTER = GLFW_KEY_ENTER;
    public static final int TAB = GLFW_KEY_TAB;
    public static final int BACKSPACE = GLFW_KEY_BACKSPACE;
    public static final int DELETE = GLFW_KEY_DELETE;
    public static final int RIGHT = GLFW_KEY_RIGHT;
    public static final int LEFT = GLFW_KEY_LEFT;
    public static final int DOWN = GLFW_KEY_DOWN;
    public static final int UP = GLFW_KEY_UP;
    public static final int HOME = GLFW_KEY_HOME;
    public static final int END = GLFW_KEY_END;
    public static final int PAGE_UP = GLFW_KEY_PAGE_UP;
    public static final int PAGE_DOWN = GLFW_KEY_PAGE_DOWN;
    public static final int LEFT_BRACKET = GLFW_KEY_LEFT_BRACKET;
    public static final int RIGHT_BRACKET = GLFW_KEY_RIGHT_BRACKET;
    public static final int EQUAL = GLFW_KEY_EQUAL;
    public static final int GRAVE_ACCENT = GLFW_KEY_GRAVE_ACCENT;
    public static final int F1 = GLFW_KEY_F1;
    public static final int F2 = GLFW_KEY_F2;
    public static final int F3 = GLFW_KEY_F3;
    public static final int F4 = GLFW_KEY_F4;
    public static final int F5 = GLFW_KEY_F5;
    public static final int F6 = GLFW_KEY_F6;
    public static final int F7 = GLFW_KEY_F7;
    public static final int F8 = GLFW_KEY_F8;
    public static final int F9 = GLFW_KEY_F9;
    public static final int F10 = GLFW_KEY_F10;
    public static final int F11 = GLFW_KEY_F11;
    public static final int F12 = GLFW_KEY_F12;
    public static final int LEFT_SHIFT = GLFW_KEY_LEFT_SHIFT;
    public static final int LEFT_CONTROL = GLFW_KEY_LEFT_CONTROL;
    public static final int LEFT_ALT = GLFW_KEY_LEFT_ALT;
    public static final int RIGHT_SHIFT = GLFW_KEY_RIGHT_SHIFT;
    public static final int RIGHT_CONTROL = GLFW_KEY_RIGHT_CONTROL;
    public static final int RIGHT_ALT = GLFW_KEY_RIGHT_ALT;

    /** Every key above by its constant's name ("LEFT_SHIFT"), built the first time a name is asked for. */
    private static Map<String, Integer> byName;
    private static Map<Integer, String> names;

    private static synchronized void index() {
        if (byName != null) {
            return;
        }
        Map<String, Integer> found = new LinkedHashMap<>();
        Map<Integer, String> reverse = new LinkedHashMap<>();
        for (Field field : Key.class.getFields()) {
            if (field.getType() == int.class && Modifier.isStatic(field.getModifiers())) {
                try {
                    int code = field.getInt(null);
                    found.put(field.getName(), code);
                    reverse.putIfAbsent(code, field.getName());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        byName = found;
        names = reverse;
    }

    /** The key's constant name, {@code "LEFT_SHIFT"}, for saving it; {@code "KEY_<code>"} for one without a constant. */
    public static String name(int key) {
        index();
        String name = names.get(key);
        return name != null ? name : "KEY_" + key;
    }

    /** The key a {@link #name} stands for, or -1 for a name that is no key. */
    public static int code(String name) {
        index();
        String upper = name.trim().toUpperCase(Locale.ROOT);
        Integer code = byName.get(upper);
        if (code != null) {
            return code;
        }
        if (upper.startsWith("KEY_")) {
            try {
                return Integer.parseInt(upper.substring(4));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** The key as a player reads it: {@code "W"}, {@code "Left Shift"}, {@code "1"}, {@code "Up"}. */
    public static String label(int key) {
        String name = name(key);
        if (name.startsWith("NUM_")) {
            return name.substring(4);
        }
        if (name.startsWith("KEY_")) {
            return "Key " + key;
        }
        StringBuilder label = new StringBuilder();
        for (String word : name.split("_")) {
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return switch (name) {
            case "LEFT_CONTROL" -> "Left Ctrl";
            case "RIGHT_CONTROL" -> "Right Ctrl";
            case "ESCAPE" -> "Esc";
            case "GRAVE_ACCENT" -> "`";
            default -> label.toString();
        };
    }
}
