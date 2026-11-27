package org.zeronura.engine.studio;

import org.joml.Vector2f;
import org.zeronura.engine.Window;
import org.zeronura.engine.data.Json;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.scene.ComponentFields;
import org.zeronura.engine.ui.Rect;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the editor's node tools share: a canvas of Blender's nodes, joined by dragging from socket to socket, panned
 * with the middle (or right) button and zoomed with the wheel, nodes added from an Add menu (Blender's own groups) or a
 * right click, values set on the nodes themselves, dropdown settings showing and hiding sockets as Blender does, colour
 * ramps edited in place, and undo as snapshots of the saved form. The Shader Tool ({@link ShaderPanel}) and the Geometry
 * Nodes Tool ({@link GeometryPanel}) are this with their own toolbar, side panel (preview), files and catalog.
 */
abstract class NodeEditorPanel {

    static final float HEADER = 24f;

    static final float ROW = 22f;

    static final float SETTING_ROW = 26f;

    static final float RAMP_ROW = 58f;

    final StudioScene studio;

    final NodeCatalog catalog;

    NodeGraph graph;

    // The canvas
    final Vector2f pan = new Vector2f(520f, 140f);

    float zoom = 1f;

    NodeGraph.Node selected;

    NodeGraph.Node dragging;

    float grabX;

    float grabY;

    NodeGraph.Node wireNode;

    String wireSocket;

    boolean wireFromOutput;

    int panButton = -1;

    float panTravel;

    int selectedStop;

    // Undo, as snapshots of the graph
    final ArrayDeque<String> undo = new ArrayDeque<>();

    final ArrayDeque<String> redo = new ArrayDeque<>();

    String current;

    int recordedRevision;

    int seenRevision = -1;

    double changedAt;

    boolean visible;

    /** The part of each node drawn as one line: an output, an input, a setting, the ramp, the picture. */
    record Row(String kind, NodeGraph.Socket socket, String setting, float y, float height) {
    }

    void resetHistory() {
        undo.clear();
        redo.clear();
        current = Json.compact(graph.toPackage(previewFile(), studio.assetsRoot()));
        recordedRevision = graph.revision;
    }

    /** Records a finished change as an undo step. */
    void settle(Ui ui) {
        if (graph == null || graph.revision == recordedRevision || ui.busy() || ui.input.isMouseDown(MouseButton.LEFT)
                || dragging != null || wireNode != null) {
            return;
        }
        String now = Json.compact(graph.toPackage(previewFile(), studio.assetsRoot()));
        recordedRevision = graph.revision;
        if (!now.equals(current)) {
            undo.push(current);
            if (undo.size() > 100) {
                undo.removeLast();
            }
            redo.clear();
            current = now;
        }
    }

    void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(current);
        current = undo.pop();
        restore(current);
    }

    void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(current);
        current = redo.pop();
        restore(current);
    }

    void restore(String json) {
        int revision = graph.revision;
        graph = read(Json.parseObject(json));
        graph.revision = revision + 1;
        recordedRevision = graph.revision;
        selected = null;
    }

    void draw(Ui ui, Rect area) {
        visible = true;
        if (graph == null) {
            startFresh();
        }
        if (graph.revision != seenRevision) {
            seenRevision = graph.revision;
            changedAt = ui.time();
        }
        Rect bar = new Rect(area.x(), area.y(), area.width(), 32f);
        toolbar(ui, bar);
        float sideWidth = Math.min(320f, area.width() * 0.32f);
        Rect canvas = new Rect(area.x(), bar.y() + bar.height(), area.width() - sideWidth, area.height() - bar.height());
        Rect side = new Rect(canvas.x() + canvas.width(), canvas.y(), sideWidth, canvas.height());
        if (ui.pressed(canvas) || ui.rightPressed(canvas)) {
            studio.setFocus(focusKind());
        }
        canvas(ui, canvas);
        side(ui, side);
        if (studio.focus() == focusKind() && !ui.typing() && !ui.modal()) {
            keys(ui);
        }
        settle(ui);
    }

    Rect canvasRect = new Rect(0, 0, 1, 1);

    void canvas(Ui ui, Rect canvas) {
        canvasRect = canvas;
        ui.fill(canvas, Theme.FIELD);
        ui.gui.pushClip(canvas);
        float step = 24f * zoom;
        float startX = canvas.x() + ((pan.x * zoom) % step + step) % step;
        float startY = canvas.y() + ((pan.y * zoom) % step + step) % step;
        Color dot = Color.WHITE.withAlpha(0.055f);
        for (float gx = startX; gx < canvas.x() + canvas.width(); gx += step) {
            for (float gy = startY; gy < canvas.y() + canvas.height(); gy += step) {
                ui.gui.rect(gx, gy, 1.5f, 1.5f, dot);
            }
        }
        pointer(ui, canvas);

        // Wires, under the nodes.
        for (NodeGraph.Link link : graph.links) {
            Vector2f from = socketPosition(canvas, link.from(), link.fromSocket(), true);
            Vector2f to = socketPosition(canvas, link.to(), link.toSocket(), false);
            if (from != null && to != null) {
                NodeGraph.Socket out = link.from().output(link.fromSocket());
                wire(ui, from, to, socketColor(out == null ? "VALUE" : out.type), 2.4f * Math.max(0.7f, zoom));
            }
        }
        ui.widgetScale(zoom);
        NodeGraph.Node pressedNode = null;
        for (NodeGraph.Node node : List.copyOf(graph.nodes)) {
            if (drawNode(ui, canvas, node)) {
                pressedNode = node;
            }
        }
        ui.widgetScale(1f);
        if (wireNode != null) {
            Vector2f from = socketPosition(canvas, wireNode, wireSocket, wireFromOutput);
            if (from != null) {
                Vector2f mouse = new Vector2f(ui.mouseX(), ui.mouseY());
                NodeGraph.Socket socket = wireFromOutput ? wireNode.output(wireSocket) : wireNode.input(wireSocket);
                Color color = socketColor(socket == null ? "VALUE" : socket.type);
                if (wireFromOutput) {
                    wire(ui, from, mouse, color, 2.4f);
                } else {
                    wire(ui, mouse, from, color, 2.4f);
                }
            }
            if (!ui.input.isMouseDown(MouseButton.LEFT)) {
                dropWire(ui, canvas);
            }
        }
        if (pressedNode != null) {
            selected = pressedNode;
        } else if (ui.pressed(canvas) && wireNode == null && dragging == null && !ui.outsideClick()) {
            selected = null;
        }
        ui.gui.popClip();
        String hint = "Right-click or Add Node to add  -  drag between sockets to join  -  middle-drag to pan, wheel to zoom"
                + "  -  Delete removes, Ctrl+D copies";
        if (catalog.isEmpty()) {
            hint = "No nodes to offer: " + catalog.resource().substring(1) + " is missing from the build"
                    + "  -  make it with tools/blender_nodes (see " + catalog.resource().substring(1) + "'s maker)";
            ui.text(hint, canvas.x() + 10f, canvas.y() + canvas.height() - 20f, Theme.SMALL_SCALE, Theme.ERROR);
            return;
        }
        ui.text(ui.fit(hint, canvas.width() - 20f, Theme.SMALL_SCALE), canvas.x() + 10f, canvas.y() + canvas.height() - 20f,
                Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
    }

    void pointer(Ui ui, Rect canvas) {
        boolean hover = ui.hover(canvas);
        boolean pressedNow = false;
        if (panButton < 0 && hover && !ui.outsideClick()) {
            if (ui.input.isMousePressed(MouseButton.MIDDLE)) {
                panButton = MouseButton.MIDDLE;
                panTravel = 0f;
                pressedNow = true;
            } else if (ui.input.isMousePressed(MouseButton.RIGHT)) {
                panButton = MouseButton.RIGHT;
                panTravel = 0f;
                pressedNow = true;
            }
        }
        // The pointer's move into this frame happened before the press, so the drag starts from here.
        if (panButton >= 0 && !pressedNow) {
            float dx = ui.input.mouseDeltaX() / ui.gui.scale();
            float dy = ui.input.mouseDeltaY() / ui.gui.scale();
            panTravel += Math.abs(dx) + Math.abs(dy);
            if (ui.input.isMouseDown(panButton)) {
                pan.add(dx / zoom, dy / zoom);
                if (panTravel > 4f) {
                    ui.cursor(Window.CursorShape.HAND);
                }
            } else {
                if (panButton == MouseButton.RIGHT && panTravel <= 4f && hover) {
                    Vector2f at = toGraph(canvas, ui.mouseX(), ui.mouseY());
                    Menu.openAtPointer(ui, idPrefix() + "AddMenu", addMenu(at, null));
                }
                panButton = -1;
            }
        }
        if (hover && ui.takeScroll(canvas)) {
            Vector2f before = toGraph(canvas, ui.mouseX(), ui.mouseY());
            zoom = Math.clamp(zoom * (float) Math.pow(1.1, ui.input.scroll()), 0.45f, 1.6f);
            pan.x = (ui.mouseX() - canvas.x()) / zoom - before.x;
            pan.y = (ui.mouseY() - canvas.y()) / zoom - before.y;
        }
        if (dragging != null) {
            if (ui.input.isMouseDown(MouseButton.LEFT)) {
                float dx = (ui.mouseX() - grabX) / zoom;
                float dy = (ui.mouseY() - grabY) / zoom;
                if (dx != 0f || dy != 0f) {
                    dragging.x += dx;
                    dragging.y += dy;
                    grabX = ui.mouseX();
                    grabY = ui.mouseY();
                    graph.changed();
                }
            } else {
                dragging = null;
            }
        }
    }

    Vector2f toGraph(Rect canvas, float x, float y) {
        return new Vector2f((x - canvas.x()) / zoom - pan.x, (y - canvas.y()) / zoom - pan.y);
    }

    float sx(Rect canvas, float x) {
        return canvas.x() + (x + pan.x) * zoom;
    }

    float sy(Rect canvas, float y) {
        return canvas.y() + (y + pan.y) * zoom;
    }

    /** How wide a node is drawn, in graph units. */
    float width(NodeGraph.Node node) {
        return node.ramp != null ? 240f : 210f;
    }

    /** The node's lines from the top, in graph units. */
    List<Row> rows(NodeGraph.Node node) {
        List<Row> rows = new ArrayList<>();
        float y = HEADER + 4f;
        for (NodeGraph.Socket socket : node.outputs) {
            if (socket.enabled) {
                rows.add(new Row("out", socket, null, y, ROW));
                y += ROW;
            }
        }
        if (node.entry != null) {
            for (NodeCatalog.Setting setting : node.entry.settings().values()) {
                if (!node.entry.fixed().contains(setting.name()) && node.props.containsKey(setting.name())) {
                    rows.add(new Row("setting", null, setting.name(), y, SETTING_ROW));
                    y += SETTING_ROW;
                }
            }
        }
        for (Map.Entry<String, Object> prop : node.props.entrySet()) {
            if (prop.getValue() instanceof Boolean && !prop.getKey().equals("is_active_output")) {
                rows.add(new Row("flag", null, prop.getKey(), y, ROW));
                y += ROW;
            }
        }
        if (node.ramp != null) {
            rows.add(new Row("ramp", null, null, y, RAMP_ROW));
            y += RAMP_ROW;
        }
        y = extraRows(node, rows, y);
        for (NodeGraph.Socket socket : node.inputs) {
            if (socket.enabled) {
                rows.add(new Row("in", socket, null, y, ROW));
                y += ROW;
            }
        }
        rows.add(new Row("end", null, null, y + 6f, 0f));
        return rows;
    }

    float height(NodeGraph.Node node) {
        return rows(node).getLast().y();
    }

    Vector2f socketPosition(Rect canvas, NodeGraph.Node node, String socketId, boolean output) {
        for (Row row : rows(node)) {
            if (row.socket() != null && row.socket().output == output && row.socket().id.equals(socketId)) {
                float x = output ? node.x + width(node) : node.x;
                return new Vector2f(sx(canvas, x), sy(canvas, node.y + row.y() + row.height() * 0.5f));
            }
        }
        return null;
    }

    Color headerColor(NodeGraph.Node node) {
        return categoryColor(node.entry == null ? (node.isOutput() ? "Output" : node.isGroupInput() ? "Input" : "")
                : node.entry.category());
    }

    static Color socketColor(String type) {
        return switch (type) {
            case "RGBA" -> Color.rgb(0xC7C729);
            case "VECTOR" -> Color.rgb(0x6363C7);
            case "SHADER" -> Color.rgb(0x63C763);
            case "BOOLEAN" -> Color.rgb(0xCCA6D6);
            case "INT" -> Color.rgb(0x598C5C);
            case "STRING" -> Color.rgb(0x70B2FF);
            case "GEOMETRY" -> Color.rgb(0x00D6A3);
            case "ROTATION" -> Color.rgb(0xA663C7);
            case "MATRIX" -> Color.rgb(0x9E9EFF);
            case "MATERIAL" -> Color.rgb(0xEB7582);
            case "MENU" -> Color.rgb(0x6B6B6B);
            case "OBJECT" -> Color.rgb(0xED9E5C);
            case "COLLECTION" -> Color.rgb(0xF5F5F5);
            case "IMAGE" -> Color.rgb(0x633863);
            default -> Color.rgb(0xA1A1A1);
        };
    }

    /** Draws a node and handles presses on it; returns whether it was pressed. */
    boolean drawNode(Ui ui, Rect canvas, NodeGraph.Node node) {
        float w = width(node) * zoom;
        List<Row> rows = rows(node);
        float h = rows.getLast().y() * zoom;
        Rect box = new Rect(sx(canvas, node.x), sy(canvas, node.y), w, h);
        if (box.x() > canvas.x() + canvas.width() || box.y() > canvas.y() + canvas.height() || box.x() + w < canvas.x()
                || box.y() + h < canvas.y()) {
            return false;
        }
        boolean isSelected = node == selected;
        ui.gui.glow(box, 6f, 10f, new Color(0f, 0f, 0f, 0.4f));
        ui.gui.rounded(box, 6f * zoom, Theme.PANEL);
        Rect header = new Rect(box.x(), box.y(), w, HEADER * zoom);
        Color hc = headerColor(node);
        ui.gui.rounded(header, 6f * zoom, hc.lerp(Color.WHITE, 0.08f), hc);
        ui.gui.rect(header.x(), header.y() + header.height() - 6f * zoom, w, 6f * zoom, hc);
        ui.gui.roundedOutline(box, 6f * zoom, isSelected ? 2f : 1f, isSelected ? Theme.HIGHLIGHT : Theme.POPUP_BORDER);
        float textScale = Theme.SMALL_SCALE * zoom;
        ui.textIn(ui.fit(node.title(), w - 16f * zoom, textScale), header, header.x() + 8f * zoom, textScale, Theme.TEXT_BRIGHT);
        boolean pressed = false;
        if (ui.pressed(header) && wireNode == null) {
            dragging = node;
            grabX = ui.mouseX();
            grabY = ui.mouseY();
            pressed = true;
        }
        String key = node.name;
        for (Row row : rows) {
            Rect r = new Rect(box.x() + 10f * zoom, box.y() + row.y() * zoom, w - 20f * zoom, row.height() * zoom);
            switch (row.kind()) {
                case "out" -> {
                    ui.textRight(row.socket().name, r, r.x() + r.width(), textScale, Theme.TEXT);
                    pressed |= socket(ui, canvas, node, row.socket(), box.x() + w, r.centerY());
                }
                case "in" -> {
                    pressed |= socket(ui, canvas, node, row.socket(), box.x(), r.centerY());
                    inputEditor(ui, node, row.socket(), r, key + ":" + row.socket().id, textScale);
                }
                case "setting" -> settingRow(ui, node, row.setting(), new Rect(r.x(), r.y() + 2f * zoom, r.width(), r.height() - 4f * zoom), key);
                case "flag" -> {
                    boolean value = Boolean.TRUE.equals(node.props.get(row.setting()));
                    boolean next = ui.checkbox(key + ":flag:" + row.setting(), new Rect(r.x(), r.y(), 16f * zoom, r.height()), value);
                    ui.textIn(ComponentFields.label(row.setting().replace("use_", "")), r, r.x() + 22f * zoom, textScale, Theme.TEXT);
                    if (next != value) {
                        node.props.put(row.setting(), next);
                        graph.changed();
                    }
                }
                case "ramp" -> rampEditor(ui, node, r, key);
                default -> drawExtraRow(ui, node, row, r, key, textScale);
            }
        }
        if (ui.pressed(box)) {
            pressed = true;
        }
        if (ui.rightClicked(header) && !node.isOutput()) {
            Menu.openAtPointer(ui, idPrefix() + "NodeMenu", List.of(
                    new Menu.Command("Duplicate", () -> {
                        selected = graph.duplicate(node);
                    }),
                    new Menu.Command("Delete", () -> deleteNode(node))));
        }
        return pressed;
    }

    /** A socket's dot; a press on it starts a wire. Returns whether it was pressed. */
    boolean socket(Ui ui, Rect canvas, NodeGraph.Node node, NodeGraph.Socket socket, float x, float y) {
        float r = 5f * Math.max(0.7f, zoom);
        Rect hit = new Rect(x - r - 4f, y - r - 4f, 2f * r + 8f, 2f * r + 8f);
        boolean hover = ui.hover(hit);
        if (socket.multiInput) {
            // Takes many links: drawn long, as Blender draws it.
            float half = (hover ? r + 1f : r);
            ui.gui.rounded(new Rect(x - half - 1.5f, y - half * 1.8f - 1.5f, 2f * half + 3f, half * 3.6f + 3f), half + 1.5f, Theme.WINDOW);
            ui.gui.rounded(new Rect(x - half, y - half * 1.8f, 2f * half, half * 3.6f), half, socketColor(socket.type));
        } else {
            ui.gui.circle(x, y, r + 1.5f, Theme.WINDOW);
            ui.gui.circle(x, y, hover ? r + 1f : r, socketColor(socket.type));
        }
        if (hover) {
            ui.cursor(Window.CursorShape.CROSSHAIR);
        }
        if (ui.pressed(hit) && wireNode == null) {
            if (socket.output) {
                wireNode = node;
                wireSocket = socket.id;
                wireFromOutput = true;
            } else {
                NodeGraph.Link link = graph.linkInto(node, socket.id);
                if (link != null) {
                    // Picks the link up off the input (a multi-input's last), to drop it on another or let it go.
                    graph.disconnect(link);
                    wireNode = link.from();
                    wireSocket = link.fromSocket();
                    wireFromOutput = true;
                } else {
                    wireNode = node;
                    wireSocket = socket.id;
                    wireFromOutput = false;
                }
            }
            return true;
        }
        return false;
    }

    /** Lets go of a wire: onto a socket of the other side it joins them; on empty canvas it offers a node to join to. */
    void dropWire(Ui ui, Rect canvas) {
        NodeGraph.Node fromNode = wireNode;
        String fromSocket = wireSocket;
        boolean fromOutput = wireFromOutput;
        wireNode = null;
        float mx = ui.mouseX();
        float my = ui.mouseY();
        for (NodeGraph.Node node : graph.nodes) {
            for (NodeGraph.Socket socket : fromOutput ? node.inputs : node.outputs) {
                if (!socket.enabled) {
                    continue;
                }
                Vector2f at = socketPosition(canvas, node, socket.id, !fromOutput);
                if (at != null && Math.hypot(at.x - mx, at.y - my) < 12f) {
                    boolean joined = fromOutput ? graph.connect(fromNode, fromSocket, node, socket.id)
                            : graph.connect(node, socket.id, fromNode, fromSocket);
                    if (!joined) {
                        studio.status("That would make a loop", true);
                    }
                    return;
                }
            }
        }
        if (canvas.contains(mx, my) && nodeAt(canvas, mx, my) == null) {
            Vector2f at = toGraph(canvas, mx, my);
            Menu.openAtPointer(ui, idPrefix() + "AddMenu", addMenu(at, new Object[]{fromNode, fromSocket, fromOutput}));
        }
    }

    NodeGraph.Node nodeAt(Rect canvas, float x, float y) {
        NodeGraph.Node hit = null;
        for (NodeGraph.Node node : graph.nodes) {
            Rect box = new Rect(sx(canvas, node.x), sy(canvas, node.y), width(node) * zoom, height(node) * zoom);
            if (box.contains(x, y)) {
                hit = node;
            }
        }
        return hit;
    }

    /** The Add menu: every node by group; one added from a dropped wire is joined to it. */
    List<Menu.Item> addMenu(Vector2f at, Object[] wire) {
        return menuLevel("", at, wire);
    }

    /**
     * One level of the Add menu: the categories directly under {@code path} (a category {@code Mesh/Primitives} is
     * the menu Primitives inside Mesh), each with its nodes and then its own sub-menus.
     */
    private List<Menu.Item> menuLevel(String path, Vector2f at, Object[] wire) {
        List<Menu.Item> items = new ArrayList<>();
        java.util.Set<String> shown = new java.util.LinkedHashSet<>();
        for (String category : catalog.categories()) {
            if (path.isEmpty() ? true : category.startsWith(path + "/")) {
                String rest = path.isEmpty() ? category : category.substring(path.length() + 1);
                shown.add(path.isEmpty() ? rest.split("/")[0] : path + "/" + rest.split("/")[0]);
            }
        }
        if (!path.isEmpty()) {
            for (NodeCatalog.Entry entry : catalog.in(path)) {
                items.add(addCommand(entry, at, wire));
            }
        }
        for (String child : shown) {
            if (child.equals(path)) {
                continue;
            }
            String label = child.substring(child.lastIndexOf('/') + 1);
            items.add(new Menu.Sub(label, () -> menuLevel(child, at, wire)));
        }
        return items;
    }

    private Menu.Item addCommand(NodeCatalog.Entry entry, Vector2f at, Object[] wire) {
        return new Menu.Command(entry.label(), () -> {
            NodeGraph.Node made = graph.add(entry, at.x, at.y - 12f);
            selected = made;
            if (wire != null) {
                join(made, (NodeGraph.Node) wire[0], (String) wire[1], (Boolean) wire[2]);
            }
        });
    }

    /** Joins a new node to the socket a wire was dragged from: its first socket of a like kind, or its first. */
    void join(NodeGraph.Node made, NodeGraph.Node other, String socketId, boolean otherIsOutput) {
        NodeGraph.Socket from = otherIsOutput ? other.output(socketId) : other.input(socketId);
        if (from == null) {
            return;
        }
        List<NodeGraph.Socket> candidates = otherIsOutput ? made.inputs : made.outputs;
        NodeGraph.Socket best = null;
        for (NodeGraph.Socket socket : candidates) {
            if (socket.enabled && best == null && socket.type.equals(from.type)) {
                best = socket;
            }
        }
        for (NodeGraph.Socket socket : candidates) {
            if (socket.enabled && best == null) {
                best = socket;
            }
        }
        if (best == null) {
            return;
        }
        if (otherIsOutput) {
            graph.connect(other, socketId, made, best.id);
        } else {
            made.x = other.x - width(made) - 60f;
            graph.connect(made, best.id, other, socketId);
        }
    }

    void deleteNode(NodeGraph.Node node) {
        if (node.isOutput() && graph.nodes.stream().filter(NodeGraph.Node::isOutput).count() <= 1) {
            studio.status(outputNeeded(), true);
            return;
        }
        graph.remove(node);
        if (selected == node) {
            selected = null;
        }
    }

    void inputEditor(Ui ui, NodeGraph.Node node, NodeGraph.Socket socket, Rect r, String id, float textScale) {
        boolean linked = graph.linkInto(node, socket.id) != null;
        boolean valueless = switch (socket.type) {
            case "SHADER", "GEOMETRY", "OBJECT", "COLLECTION", "IMAGE", "MATRIX" -> true;
            default -> false;
        };
        if (linked || valueless || socket.hideValue || (socket.value == null && !socket.type.equals("MATERIAL"))) {
            ui.textIn(socket.name, r, r.x(), textScale, linked ? Theme.TEXT : Theme.TEXT_MUTED);
            return;
        }
        float labelWidth = r.width() * 0.44f;
        ui.textIn(ui.fit(socket.name, labelWidth - 4f, textScale), r, r.x(), textScale, Theme.TEXT_MUTED);
        Rect field = new Rect(r.x() + labelWidth, r.y() + 1f, r.width() - labelWidth, r.height() - 2f);
        if (customInput(ui, node, socket, field, id, textScale)) {
            return;
        }
        switch (socket.type) {
            case "VALUE", "INT" -> {
                double value = socket.value instanceof Number n ? n.doubleValue() : 0.0;
                boolean integer = socket.type.equals("INT");
                ui.number(id, field, value, integer ? 0.1 : Math.max(0.005, Math.abs(value) * 0.01 + 0.005),
                        -1e9, 1e9, integer, v -> {
                            socket.value = integer ? (Object) Math.round(v) : (Object) v;
                            graph.changed();
                        });
            }
            case "RGBA" -> {
                float[] c = ComponentFields.floats(socket.value, 4);
                ColorPicker.swatch(ui, id, field, shown(c), picked -> {
                    socket.value = stored(picked);
                    graph.changed();
                });
            }
            case "VECTOR", "ROTATION" -> {
                float[] v = ComponentFields.floats(socket.value, 3);
                float each = (field.width() - 4f) / 3f;
                for (int k = 0; k < 3; k++) {
                    int axis = k;
                    ui.number(id + ":" + k, new Rect(field.x() + k * (each + 2f), field.y(), each, field.height()), v[k], 0.01,
                            -1e9, 1e9, false, value -> {
                                float[] now = ComponentFields.floats(socket.value, 3);
                                now[axis] = (float) value;
                                socket.value = List.of((double) now[0], (double) now[1], (double) now[2]);
                                graph.changed();
                            });
                }
            }
            case "BOOLEAN" -> {
                boolean value = Boolean.TRUE.equals(socket.value);
                boolean next = ui.checkbox(id, new Rect(field.x(), field.y(), 16f * zoom, field.height()), value);
                if (next != value) {
                    socket.value = next;
                    graph.changed();
                }
            }
            case "STRING" -> ui.textField(id, field, String.valueOf(socket.value), "", text -> {
                socket.value = text;
                graph.changed();
            });
            default -> ui.textIn(String.valueOf(socket.value), field, field.x(), textScale, Theme.TEXT_DISABLED);
        }
    }

    void settingRow(Ui ui, NodeGraph.Node node, String name, Rect r, String key) {
        NodeCatalog.Setting setting = node.entry.settings().get(name);
        String current = String.valueOf(node.props.get(name));
        String label = current;
        List<String> labels = new ArrayList<>();
        for (String[] item : setting.items()) {
            labels.add(item[1]);
            if (item[0].equals(current)) {
                label = item[1];
            }
        }
        ui.dropdown(key + ":setting:" + name, r, label, labels, i -> {
            node.props.put(name, setting.items().get(i)[0]);
            graph.applySettings(node);
            graph.changed();
        });
    }

    @SuppressWarnings("unchecked")
    void rampEditor(Ui ui, NodeGraph.Node node, Rect r, String key) {
        List<Object> stops = (List<Object>) node.ramp.computeIfAbsent("stops", k -> new ArrayList<>());
        if (stops.isEmpty()) {
            stops.add(new java.util.LinkedHashMap<>(Map.of("position", 0.0, "color", List.of(0.0, 0.0, 0.0, 1.0))));
            stops.add(new java.util.LinkedHashMap<>(Map.of("position", 1.0, "color", List.of(1.0, 1.0, 1.0, 1.0))));
        }
        stops.sort((a, b) -> Float.compare(position(a), position(b)));
        Rect bar = new Rect(r.x(), r.y() + 4f * zoom, r.width(), 18f * zoom);
        Checker.draw(ui.gui, bar);
        // The ramp, stop to stop.
        float previous = 0f;
        Color previousColor = color(stops.getFirst());
        for (Object stop : stops) {
            float at = Math.clamp(position(stop), 0f, 1f);
            Color c = color(stop);
            float x0 = bar.x() + previous * bar.width();
            float x1 = bar.x() + at * bar.width();
            if (x1 > x0 + 0.5f) {
                boolean constant = "CONSTANT".equals(node.ramp.get("interpolation"));
                ui.gui.polygonAcross(new float[]{x0, bar.y(), x1, bar.y(), x1, bar.y() + bar.height(), x0, bar.y() + bar.height()},
                        previousColor, constant ? previousColor : c);
            }
            previous = at;
            previousColor = c;
        }
        if (previous < 1f) {
            float x0 = bar.x() + previous * bar.width();
            ui.gui.rect(x0, bar.y(), bar.x() + bar.width() - x0, bar.height(), previousColor);
        }
        ui.gui.outline(bar, 1f, Theme.FIELD_BORDER);
        selectedStop = Math.clamp(selectedStop, 0, stops.size() - 1);
        // The stops: pick one, drag it along.
        for (int i = 0; i < stops.size(); i++) {
            float x = bar.x() + Math.clamp(position(stops.get(i)), 0f, 1f) * bar.width();
            float top = bar.y() + bar.height();
            ui.gui.triangle(x, top - 2f, x - 5f * zoom, top + 7f * zoom, x + 5f * zoom, top + 7f * zoom,
                    i == selectedStop ? Theme.HIGHLIGHT : Theme.TEXT_BRIGHT);
            Rect grip = new Rect(x - 7f, bar.y(), 14f, bar.height() + 9f * zoom);
            if (ui.pressed(grip)) {
                selectedStop = i;
                rampDrag = key;
            }
        }
        if (key.equals(rampDrag)) {
            if (ui.input.isMouseDown(MouseButton.LEFT)) {
                float t = Math.clamp((ui.mouseX() - bar.x()) / Math.max(1f, bar.width()), 0f, 1f);
                Map<String, Object> stop = (Map<String, Object>) stops.get(selectedStop);
                if (Math.abs(position(stop) - t) > 1e-4f) {
                    stop.put("position", (double) t);
                    graph.changed();
                }
            } else {
                rampDrag = null;
            }
        }
        float rowY = bar.y() + bar.height() + 10f * zoom;
        float h = 20f * zoom;
        Map<String, Object> stop = (Map<String, Object>) stops.get(selectedStop);
        ColorPicker.swatch(ui, key + ":stopColor:" + selectedStop, new Rect(r.x(), rowY, 46f * zoom, h), color(stop), picked -> {
            stop.put("color", stored(picked));
            graph.changed();
        });
        float bx = r.x() + 52f * zoom;
        float bw = 26f * zoom;
        if (ui.button(key + ":addStop", "+", new Rect(bx, rowY, bw, h))) {
            float t = stops.size() > 1 ? (position(stops.get(selectedStop)) + position(stops.get(Math.min(stops.size() - 1, selectedStop + 1)))) * 0.5f : 0.5f;
            Map<String, Object> added = new java.util.LinkedHashMap<>();
            added.put("position", (double) t);
            added.put("color", ((List<Object>) stop.get("color")).stream().toList());
            stops.add(added);
            selectedStop = stops.indexOf(added);
            graph.changed();
        }
        if (ui.button(key + ":removeStop", "-", new Rect(bx + bw + 4f, rowY, bw, h)) && stops.size() > 1) {
            stops.remove(selectedStop);
            selectedStop = Math.max(0, selectedStop - 1);
            graph.changed();
        }
        String interpolation = String.valueOf(node.ramp.getOrDefault("interpolation", "LINEAR"));
        List<String> modes = List.of("LINEAR", "EASE", "CONSTANT", "B_SPLINE", "CARDINAL");
        ui.dropdown(key + ":interpolation", new Rect(bx + 2f * bw + 10f, rowY, r.x() + r.width() - (bx + 2f * bw + 10f), h),
                ComponentFields.label(interpolation), modes.stream().map(ComponentFields::label).toList(), i -> {
                    node.ramp.put("interpolation", modes.get(i));
                    graph.changed();
                });
    }

    String rampDrag;

    static float position(Object stop) {
        return stop instanceof Map<?, ?> m && m.get("position") instanceof Number n ? n.floatValue() : 0f;
    }

    static Color color(Object stop) {
        return shown(ComponentFields.floats(stop instanceof Map<?, ?> m ? m.get("color") : null, 4));
    }

    /** A linear colour value as it looks on screen. */
    static Color shown(float[] linear) {
        return new Color(toScreen(linear[0]), toScreen(linear[1]), toScreen(linear[2]), linear[3]);
    }

    /** A colour as picked on screen, as the linear value a shader keeps. */
    static List<Object> stored(Color picked) {
        return List.of((double) toLinear(picked.r()), (double) toLinear(picked.g()), (double) toLinear(picked.b()),
                (double) picked.a());
    }

    static float toLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    static float toScreen(float c) {
        float v = Math.clamp(c, 0f, 1f);
        return v <= 0.0031308f ? v * 12.92f : (float) (1.055 * Math.pow(v, 1.0 / 2.4) - 0.055);
    }

    void keys(Ui ui) {
        if (selected != null && (ui.input.isKeyPressed(Key.DELETE) || ui.input.isKeyPressed(Key.BACKSPACE))) {
            deleteNode(selected);
        }
        if (ui.input.isKeyPressed(Key.HOME)) {
            frame();
        }
    }

    void duplicateSelected() {
        if (selected != null && !selected.isOutput()) {
            selected = graph.duplicate(selected);
        }
    }

    /** Fits every node in view. */
    void frame() {
        if (graph == null || graph.nodes.isEmpty()) {
            return;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (NodeGraph.Node node : graph.nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.x + width(node));
            maxY = Math.max(maxY, node.y + height(node));
        }
        Rect canvas = canvasRect.width() > 10f ? canvasRect : new Rect(0, 0, 1000, 600);
        zoom = Math.clamp(Math.min((canvas.width() - 80f) / (maxX - minX), (canvas.height() - 80f) / (maxY - minY)), 0.45f, 1f);
        pan.set(40f / zoom - minX, 40f / zoom - minY);
    }

    static void wire(Ui ui, Vector2f a, Vector2f b, Color color, float thickness) {
        float dx = Math.max(40f, Math.abs(b.x - a.x) * 0.5f);
        float px = a.x;
        float py = a.y;
        for (int i = 1; i <= 24; i++) {
            float t = i / 24f;
            float u = 1f - t;
            float x = u * u * u * a.x + 3f * u * u * t * (a.x + dx) + 3f * u * t * t * (b.x - dx) + t * t * t * b.x;
            float y = u * u * u * a.y + 3f * u * u * t * a.y + 3f * u * t * t * b.y + t * t * t * b.y;
            ui.gui.line(px, py, x, y, thickness, color);
            px = x;
            py = y;
        }
    }

    // --- What each tool brings ----------------------------------------------------------------------------------

    NodeEditorPanel(StudioScene studio, NodeCatalog catalog) {
        this.studio = studio;
        this.catalog = catalog;
    }

    /** The bar over the canvas: New, Open, Save and the tool's own. */
    abstract void toolbar(Ui ui, Rect bar);

    /** The panel beside the canvas: the preview, and what went wrong. */
    abstract void side(Ui ui, Rect side);

    /** Where the graph is saved, or would be: pictures' paths are measured from it. */
    abstract Path previewFile();

    /** The graph a saved form (an undo step) holds. */
    abstract NodeGraph read(Map<String, Object> json);

    /** Starts a new graph, as New does. */
    abstract void startFresh();

    /** Which part of the editor has the keyboard while this tool does. */
    abstract StudioScene.Focus focusKind();

    /** A node's header colour, by its Add-menu category. */
    abstract Color categoryColor(String category);

    /** What to say when the last output node is to be deleted. */
    abstract String outputNeeded();

    /** What the tool's menus and widgets are called, to keep them apart from another tool's. */
    abstract String idPrefix();

    /** Lines a tool adds to a node, after its settings: the Shader Tool's picture rows. Returns the y below them. */
    float extraRows(NodeGraph.Node node, List<Row> rows, float y) {
        return y;
    }

    /** Draws one of the lines {@link #extraRows} added. */
    void drawExtraRow(Ui ui, NodeGraph.Node node, Row row, Rect r, String key, float textScale) {
    }

    /** Edits an input's value in a way of the tool's own (a material picker, say); false to leave it to the canvas. */
    boolean customInput(Ui ui, NodeGraph.Node node, NodeGraph.Socket socket, Rect field, String id, float textScale) {
        return false;
    }
}
