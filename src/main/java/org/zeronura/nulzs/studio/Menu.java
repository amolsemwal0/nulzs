package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.ui.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Menus: the editor's menu bar dropdowns, and the context menus opened with a right click. A menu is a list of items —
 * commands, separators, and submenus — drawn as a popup.
 */
final class Menu {

    private Menu() {
    }

    sealed interface Item permits Command, Separator, Sub {
    }

    /** A line between groups of items. */
    record Separator() implements Item {
    }

    static final Separator SEPARATOR = new Separator();

    /** An item that opens another menu beside it. */
    record Sub(String label, Supplier<List<Item>> items) implements Item {
    }

    /**
     * Something the editor can do, with how it shows in a menu and the key that does it from anywhere. The same command
     * sits in the menu and answers the shortcut, so the two can't disagree.
     */
    static final class Command implements Item {
        final String label;
        final int key;
        final boolean ctrl;
        final boolean shift;
        final Runnable run;
        BooleanSupplier enabled = () -> true;
        BooleanSupplier checked;
        /** Whether the shortcut works while a field is being typed in; only Ctrl ones should. */
        boolean whileTyping;

        Command(String label, Runnable run) {
            this(label, -1, false, false, run);
        }

        Command(String label, int key, boolean ctrl, boolean shift, Runnable run) {
            this.label = label;
            this.key = key;
            this.ctrl = ctrl;
            this.shift = shift;
            this.run = run;
        }

        Command enabledWhen(BooleanSupplier condition) {
            this.enabled = condition;
            return this;
        }

        Command checkedWhen(BooleanSupplier condition) {
            this.checked = condition;
            return this;
        }

        Command whileTyping() {
            this.whileTyping = true;
            return this;
        }

        /** The shortcut as shown beside the item: "Ctrl+Shift+S". */
        String keys() {
            if (key < 0) {
                return "";
            }
            String name = keyName(key);
            return (ctrl ? "Ctrl+" : "") + (shift ? "Shift+" : "") + name;
        }
    }

    static String keyName(int key) {
        return switch (key) {
            case org.zeronura.nulzs.input.Key.DELETE -> "Del";
            case org.zeronura.nulzs.input.Key.ESCAPE -> "Esc";
            case org.zeronura.nulzs.input.Key.ENTER -> "Enter";
            case org.zeronura.nulzs.input.Key.SPACE -> "Space";
            case org.zeronura.nulzs.input.Key.TAB -> "Tab";
            case org.zeronura.nulzs.input.Key.SLASH -> "/";
            case org.zeronura.nulzs.input.Key.EQUAL -> "=";
            case org.zeronura.nulzs.input.Key.MINUS -> "-";
            default -> {
                if (key >= org.zeronura.nulzs.input.Key.F1 && key <= org.zeronura.nulzs.input.Key.F12) {
                    yield "F" + (key - org.zeronura.nulzs.input.Key.F1 + 1);
                }
                String name = org.lwjgl.glfw.GLFW.glfwGetKeyName(key, 0);
                yield name == null ? "?" : name.toUpperCase(java.util.Locale.ROOT);
            }
        };
    }

    // --- Drawing ----------------------------------------------------------------------------------------------

    private static final float ITEM_HEIGHT = 26f;
    private static final float SEPARATOR_HEIGHT = 9f;

    /** Opens a menu as a popup at (x, y). */
    static void open(Ui ui, String id, float x, float y, Supplier<List<Item>> items) {
        ui.openPopup(id, x, y, (u, px, py) -> draw(u, id, px, py, items.get()));
    }

    /** Opens a context menu at the pointer. */
    static void openAtPointer(Ui ui, String id, List<Item> items) {
        List<Item> fixed = List.copyOf(items);
        open(ui, id, ui.mouseX() + 2f, ui.mouseY() + 2f, () -> fixed);
    }

    private static Rect draw(Ui ui, String id, float x, float y, List<Item> items) {
        float labelWidth = 120f;
        float keysWidth = 0f;
        for (Item item : items) {
            if (item instanceof Command command) {
                labelWidth = Math.max(labelWidth, ui.width(command.label));
                keysWidth = Math.max(keysWidth, ui.width(command.keys(), Theme.SMALL_SCALE));
            } else if (item instanceof Sub sub) {
                labelWidth = Math.max(labelWidth, ui.width(sub.label()));
            }
        }
        float width = 30f + labelWidth + (keysWidth > 0f ? keysWidth + 40f : 30f) + 8f;
        float height = 8f;
        for (Item item : items) {
            height += item instanceof Separator ? SEPARATOR_HEIGHT : ITEM_HEIGHT;
        }
        Rect box = ui.popupBox(x, y, width, height);
        float rowY = box.y() + 4f;
        for (Item item : items) {
            if (item instanceof Separator) {
                ui.hline(box.x() + 8f, rowY + SEPARATOR_HEIGHT * 0.5f, box.width() - 16f, Theme.DIVIDER);
                rowY += SEPARATOR_HEIGHT;
                continue;
            }
            Rect row = new Rect(box.x() + 4f, rowY, box.width() - 8f, ITEM_HEIGHT);
            rowY += ITEM_HEIGHT;
            boolean hover = ui.hover(row);
            if (item instanceof Command command) {
                boolean enabled = command.enabled.getAsBoolean();
                if (hover && enabled) {
                    ui.gui.rounded(row, 3f, Theme.ACCENT_DIM);
                    ui.closePopupsUnder(id);
                }
                if (command.checked != null && command.checked.getAsBoolean()) {
                    Icon.CHECK.draw(ui.gui, row.x() + 6f, row.centerY() - 7f, 14f, enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
                }
                ui.textIn(command.label, row, row.x() + 26f, enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
                if (!command.keys().isEmpty()) {
                    ui.textRight(command.keys(), row, row.x() + row.width() - 10f, Theme.SMALL_SCALE,
                            enabled ? Theme.TEXT_MUTED : Theme.TEXT_DISABLED);
                }
                if (enabled && ui.clicked(id + ":" + command.label, row)) {
                    ui.closePopups();
                    command.run.run();
                    return null;
                }
            } else if (item instanceof Sub sub) {
                String childId = id + "/" + sub.label();
                boolean open = ui.popupOpen(childId);
                if (hover || open) {
                    ui.gui.rounded(row, 3f, hover ? Theme.ACCENT_DIM : Theme.HOVER);
                }
                ui.textIn(sub.label(), row, row.x() + 26f, Theme.TEXT);
                Icon.CHEVRON_RIGHT.draw(ui.gui, row.x() + row.width() - 18f, row.centerY() - 6f, 12f, Theme.TEXT_MUTED);
                if (hover && !open) {
                    ui.closePopupsUnder(id);
                    open(ui, childId, box.x() + box.width() - 2f, row.y() - 4f, sub.items());
                }
            }
        }
        return box;
    }

    /** The commands in some menu items and their submenus, for answering shortcuts. */
    static List<Command> commands(List<Item> items) {
        List<Command> out = new ArrayList<>();
        for (Item item : items) {
            if (item instanceof Command command) {
                out.add(command);
            } else if (item instanceof Sub sub) {
                out.addAll(commands(sub.items().get()));
            }
        }
        return out;
    }
}
