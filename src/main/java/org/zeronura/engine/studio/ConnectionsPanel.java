package org.zeronura.engine.studio;

import org.joml.Vector2f;
import org.zeronura.engine.Window;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.scene.Component;
import org.zeronura.engine.scene.ComponentFields;
import org.zeronura.engine.scene.ComponentFields.Property;
import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.scene.MissingComponent;
import org.zeronura.engine.scene.Signal;
import org.zeronura.engine.ui.Rect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Connections view: the scene as a graph of how its parts are linked. Each entity is a node listing its components
 * — its scripts — and the fields on them that point at other entities; wires run from those fields to what they point
 * at, orange ones from signals to the methods they call, and grey ones from parents to children.
 *
 * <p>Drag from a field's socket onto a node to link it (a signal asks which method to call), right-click a socket or a
 * wire's end to unlink it, drag nodes about by their titles, double-click a script to open it. Middle or right drag pans
 * and the wheel zooms. Where the nodes sit is saved with the scene.
 */
final class ConnectionsPanel {

    private static final float NODE_WIDTH = 232f;
    private static final float HEADER = 28f;
    private static final float COMPONENT_ROW = 23f;
    private static final float FIELD_ROW = 21f;

    private final StudioScene studio;
    private final Vector2f pan = new Vector2f(40f, 40f);
    private float zoom = 1f;
    boolean showParents = true;
    boolean showReferences = true;
    boolean showSignals = true;
    boolean onlyLinked;

    private Entity draggedNode;
    private float grabX;
    private float grabY;
    private boolean moved;
    private int panButton = -1;
    private Socket wireFrom;
    /** Set when the nodes have just been laid out afresh, to frame them all once drawn. */
    private boolean fitNext;

    /** A field's socket on a node, where its wires leave from. */
    private record Socket(Entity entity, Component component, Property property, float x, float y) {
    }

    private record Node(Entity entity, float x, float y, float height, List<Socket> sockets,
                        Map<Component, Float> componentRows) {
        float inputX() {
            return x;
        }

        float inputY() {
            return y + HEADER * 0.5f;
        }
    }

    ConnectionsPanel(StudioScene studio) {
        this.studio = studio;
    }

    void draw(Ui ui, Rect area) {
        SceneDocument doc = studio.doc();
        Rect bar = new Rect(area.x(), area.y(), area.width(), 30f);
        Rect canvas = new Rect(area.x(), area.y() + bar.height(), area.width(), area.height() - bar.height());
        toolbar(ui, bar, doc, canvas);
        if (doc == null) {
            return;
        }
        if (ui.pressed(canvas) || ui.rightPressed(canvas)) {
            studio.setFocus(StudioScene.Focus.GRAPH);
        }
        ui.fill(canvas, Theme.FIELD);
        ui.gui.pushClip(canvas);
        dots(ui, canvas);

        Map<String, Object> positions = positions(doc);
        List<Entity> entities = shown(doc);
        layoutMissing(doc, entities, positions);
        if (fitNext) {
            fitNext = false;
            fit(doc, canvas);
        }
        Map<Entity, Node> nodes = new LinkedHashMap<>();
        for (Entity entity : entities) {
            nodes.put(entity, node(doc, entity, positions));
        }

        float mx = ui.mouseX();
        float my = ui.mouseY();
        Vector2f graphMouse = toGraph(canvas, mx, my);
        input(ui, canvas, graphMouse);

        // Everything in graph units from here, scaled by the zoom about the canvas's corner.
        ui.gui.transform(0f, zoom, canvas.x(), canvas.y());
        float ox = canvas.x() + pan.x;
        float oy = canvas.y() + pan.y;
        wires(ui, doc, nodes, ox, oy);
        Entity clickedNode = null;
        for (Node node : nodes.values()) {
            if (drawNode(ui, doc, node, ox, oy)) {
                clickedNode = node.entity();
            }
        }
        if (wireFrom != null) {
            float sx = ox + wireFrom.x();
            float sy = oy + wireFrom.y();
            Color color = wireFrom.property().kind() == ComponentFields.Kind.SIGNAL ? Theme.SIGNAL_WIRE : Theme.REFERENCE_WIRE;
            curve(ui, sx, sy, ox + graphMouse.x, oy + graphMouse.y, color, 2.5f);
        }
        ui.gui.resetRotation();

        if (wireFrom != null && !ui.input.isMouseDown(MouseButton.LEFT)) {
            Node target = nodeAt(nodes, graphMouse);
            if (target != null && target.entity() != null) {
                connect(ui, doc, wireFrom, target.entity());
            }
            wireFrom = null;
        }
        if (clickedNode != null && wireFrom == null) {
            if (!doc.isSelected(clickedNode)) {
                doc.select(clickedNode);
                studio.selectionChanged();
            }
        }
        if (draggedNode == null && wireFrom == null && ui.pressed(canvas) && nodeAt(nodes, graphMouse) == null) {
            doc.select(null);
            studio.selectionChanged();
        }
        ui.gui.popClip();
        if (entities.isEmpty()) {
            ui.textCentered(onlyLinked ? "Nothing in this scene is linked yet" : "This scene is empty", canvas, Theme.TEXT_MUTED);
        }
        legend(ui, canvas);
    }

    private void toolbar(Ui ui, Rect bar, SceneDocument doc, Rect canvas) {
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 6f;
        if (ui.button("graphArrange", "Arrange", new Rect(x, bar.y() + 4f, 80f, 22f)) && doc != null) {
            arrange(doc);
        }
        x += 86f;
        if (ui.button("graphFit", "Fit", new Rect(x, bar.y() + 4f, 50f, 22f)) && doc != null) {
            fit(doc, canvas);
        }
        x += 64f;
        x = toggle(ui, "graphParents", "Parents", x, bar, showParents, Theme.PARENT_WIRE, () -> showParents = !showParents);
        x = toggle(ui, "graphRefs", "References", x, bar, showReferences, Theme.REFERENCE_WIRE, () -> showReferences = !showReferences);
        x = toggle(ui, "graphSignals", "Signals", x, bar, showSignals, Theme.SIGNAL_WIRE, () -> showSignals = !showSignals);
        x += 10f;
        boolean only = ui.checkbox("graphOnlyLinked", new Rect(x, bar.y(), 18f, bar.height()), onlyLinked);
        if (only != onlyLinked) {
            onlyLinked = only;
        }
        ui.textIn("Only linked", bar, x + 24f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        ui.textRight(Math.round(zoom * 100f) + "%", bar, bar.x() + bar.width() - 10f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
    }

    private float toggle(Ui ui, String id, String label, float x, Rect bar, boolean on, Color color, Runnable flip) {
        float w = ui.width(label, Theme.SMALL_SCALE) + 30f;
        Rect r = new Rect(x, bar.y() + 4f, w, 22f);
        if (on) {
            ui.gui.rounded(r, Theme.RADIUS, Theme.SECTION);
        } else if (ui.hover(r)) {
            ui.gui.rounded(r, Theme.RADIUS, Theme.HOVER);
        }
        ui.gui.rect(r.x() + 8f, r.centerY() - 1.5f, 12f, 3f, on ? color : Theme.TEXT_DISABLED);
        ui.textIn(label, r, r.x() + 24f, Theme.SMALL_SCALE, on ? Theme.TEXT : Theme.TEXT_DISABLED);
        if (ui.clicked(id, r)) {
            flip.run();
        }
        return x + w + 4f;
    }

    private void legend(Ui ui, Rect canvas) {
        String text = "Drag from a socket onto a node to link it. Right-click a socket to unlink. Middle-drag to pan, wheel to zoom.";
        ui.text(ui.fit(text, canvas.width() - 20f, Theme.SMALL_SCALE), canvas.x() + 10f,
                canvas.y() + canvas.height() - 20f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
    }

    private void dots(Ui ui, Rect canvas) {
        float step = 24f * zoom;
        if (step < 8f) {
            return;
        }
        float startX = canvas.x() + ((pan.x * zoom) % step + step) % step;
        float startY = canvas.y() + ((pan.y * zoom) % step + step) % step;
        Color dot = Color.WHITE.withAlpha(0.06f);
        for (float x = startX; x < canvas.x() + canvas.width(); x += step) {
            for (float y = startY; y < canvas.y() + canvas.height(); y += step) {
                ui.gui.rect(x, y, 1.5f, 1.5f, dot);
            }
        }
    }

    // --- Nodes ------------------------------------------------------------------------------------------------

    private List<Entity> shown(SceneDocument doc) {
        List<Entity> all = doc.entitiesInOrder();
        if (!onlyLinked) {
            return all;
        }
        java.util.Set<Entity> linked = new java.util.HashSet<>();
        for (Entity entity : all) {
            for (Component component : entity.components()) {
                if (component instanceof MissingComponent) {
                    continue;
                }
                for (Property property : ComponentFields.of(component.getClass())) {
                    Object value = property.get(component);
                    switch (property.kind()) {
                        case ENTITY -> {
                            if (value instanceof Entity target) {
                                linked.add(entity);
                                linked.add(target);
                            }
                        }
                        case COMPONENT -> {
                            if (value instanceof Component target && target.entity() != null) {
                                linked.add(entity);
                                linked.add(target.entity());
                            }
                        }
                        case SIGNAL -> {
                            for (Signal.Connection wire : ((Signal) value).connections()) {
                                if (wire.target() != null) {
                                    linked.add(entity);
                                    linked.add(wire.target());
                                }
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        return all.stream().filter(linked::contains).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> positions(SceneDocument doc) {
        Object graph = doc.editorData.get("graph");
        if (!(graph instanceof Map<?, ?>)) {
            graph = new LinkedHashMap<String, Object>();
            doc.editorData.put("graph", graph);
        }
        return (Map<String, Object>) graph;
    }

    private Vector2f position(SceneDocument doc, Entity entity, Map<String, Object> positions) {
        float[] p = ComponentFields.floats(positions.get(doc.idOf(entity)), 2);
        return new Vector2f(p[0], p[1]);
    }

    private void setPosition(SceneDocument doc, Entity entity, Map<String, Object> positions, float x, float y) {
        positions.put(doc.idOf(entity), List.of((double) Math.round(x), (double) Math.round(y)));
    }

    /**
     * Gives nodes that have never been placed a place: all of them laid out as a tree when none has one yet, otherwise
     * each new one beside its parent, or under everything if it has none.
     */
    private void layoutMissing(SceneDocument doc, List<Entity> entities, Map<String, Object> positions) {
        boolean anyPlaced = false;
        boolean anyMissing = false;
        for (Entity entity : entities) {
            if (positions.containsKey(doc.idOf(entity))) {
                anyPlaced = true;
            } else {
                anyMissing = true;
            }
        }
        if (!anyMissing) {
            return;
        }
        if (!anyPlaced) {
            tree(doc, entities, positions);
            fitNext = true;
            return;
        }
        for (Entity entity : entities) {
            if (positions.containsKey(doc.idOf(entity))) {
                continue;
            }
            Entity parent = entity.parent();
            if (parent != null && positions.containsKey(doc.idOf(parent))) {
                Vector2f p = position(doc, parent, positions);
                float y = p.y + nodeHeight(parent) + 24f;
                for (Entity sibling : parent.children()) {
                    if (sibling != entity && positions.containsKey(doc.idOf(sibling))) {
                        y = Math.max(y, position(doc, sibling, positions).y + nodeHeight(sibling) + 24f);
                    }
                }
                setPosition(doc, entity, positions, p.x + 270f, y);
            } else {
                float minX = Float.MAX_VALUE;
                float maxY = 0f;
                for (Entity other : entities) {
                    if (other != entity && positions.containsKey(doc.idOf(other))) {
                        Vector2f o = position(doc, other, positions);
                        minX = Math.min(minX, o.x);
                        maxY = Math.max(maxY, o.y + nodeHeight(other));
                    }
                }
                setPosition(doc, entity, positions, minX == Float.MAX_VALUE ? 0f : minX, maxY + 30f);
            }
        }
    }

    /**
     * Lays every node out as a tree: down the page in hierarchy order, each child a step right of its parent, starting
     * a new column of trees beside the last when one gets tall.
     */
    private void tree(SceneDocument doc, List<Entity> entities, Map<String, Object> positions) {
        java.util.Set<Entity> shown = new java.util.HashSet<>(entities);
        float columnX = 0f;
        float y = 0f;
        int deepest = 0;
        for (Entity entity : entities) {
            int depth = 0;
            for (Entity up = entity.parent(); up != null; up = up.parent()) {
                if (shown.contains(up)) {
                    depth++;
                }
            }
            boolean root = depth == 0;
            if (root && y > 760f) {
                columnX += (deepest + 1) * 270f + 60f;
                y = 0f;
                deepest = 0;
            }
            deepest = Math.max(deepest, depth);
            setPosition(doc, entity, positions, columnX + depth * 270f, y);
            y += nodeHeight(entity) + 24f;
        }
    }

    private void arrange(SceneDocument doc) {
        Map<String, Object> positions = positions(doc);
        positions.clear();
        tree(doc, shown(doc), positions);
        fitNext = true;
        doc.changed();
    }

    private void fit(SceneDocument doc, Rect canvas) {
        Map<String, Object> positions = positions(doc);
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Entity entity : shown(doc)) {
            Vector2f p = position(doc, entity, positions);
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x + NODE_WIDTH);
            maxY = Math.max(maxY, p.y + nodeHeight(entity));
        }
        if (minX == Float.MAX_VALUE) {
            return;
        }
        zoom = Math.clamp(Math.min((canvas.width() - 60f) / (maxX - minX), (canvas.height() - 60f) / (maxY - minY)), 0.25f, 1.5f);
        pan.set(30f / zoom - minX, 30f / zoom - minY);
    }

    private static float nodeHeight(Entity entity) {
        float height = HEADER + 6f;
        for (Component component : entity.components()) {
            height += COMPONENT_ROW;
            if (!(component instanceof MissingComponent)) {
                for (Property property : ComponentFields.of(component.getClass())) {
                    if (property.kind().isReference()) {
                        height += FIELD_ROW;
                    }
                }
            }
        }
        return height + 4f;
    }

    private Node node(SceneDocument doc, Entity entity, Map<String, Object> positions) {
        Vector2f p = position(doc, entity, positions);
        List<Socket> sockets = new ArrayList<>();
        Map<Component, Float> rows = new LinkedHashMap<>();
        float y = p.y + HEADER + 6f;
        for (Component component : entity.components()) {
            rows.put(component, y);
            y += COMPONENT_ROW;
            if (component instanceof MissingComponent) {
                continue;
            }
            for (Property property : ComponentFields.of(component.getClass())) {
                if (property.kind().isReference()) {
                    sockets.add(new Socket(entity, component, property, p.x + NODE_WIDTH, y + FIELD_ROW * 0.5f));
                    y += FIELD_ROW;
                }
            }
        }
        return new Node(entity, p.x, p.y, y - p.y + 4f, sockets, rows);
    }

    /** Draws a node; returns whether it was clicked. */
    private boolean drawNode(Ui ui, SceneDocument doc, Node node, float ox, float oy) {
        Entity entity = node.entity();
        Rect box = new Rect(ox + node.x(), oy + node.y(), NODE_WIDTH, node.height());
        boolean selected = doc.isSelected(entity);
        ui.gui.glow(box, 6f, 10f, new Color(0f, 0f, 0f, 0.35f));
        ui.gui.rounded(box, 6f, Theme.PANEL);
        Rect header = new Rect(box.x(), box.y(), box.width(), HEADER);
        Color headerColor = switch (EntityBounds.iconOf(entity)) {
            case CAMERA -> Color.rgb(0x3D4F6B);
            case SHAPE, MODEL -> Color.rgb(0x3F5A4A);
            case SPRITE -> Color.rgb(0x5A4A6B);
            default -> Color.rgb(0x41444B);
        };
        ui.gui.rounded(header, 6f, headerColor.lerp(Color.WHITE, 0.06f), headerColor);
        ui.gui.rect(header.x(), header.y() + header.height() - 6f, header.width(), 6f, headerColor);
        ui.gui.roundedOutline(box, 6f, selected ? 2f : 1f, selected ? Theme.HIGHLIGHT : Theme.POPUP_BORDER);
        EntityBounds.iconOf(entity).draw(ui.gui, header.x() + 12f, header.centerY() - 8f, 16f, Theme.TEXT_BRIGHT);
        ui.textIn(ui.fit(entity.name(), header.width() - 50f), header, header.x() + 34f,
                entity.isActive() ? Theme.TEXT_BRIGHT : Theme.TEXT_DISABLED);
        // Where links to this entity come in.
        ui.gui.circle(box.x(), header.centerY(), 6f, Theme.FIELD);
        ui.gui.circle(box.x(), header.centerY(), 4f, Theme.TEXT_MUTED);

        boolean clicked = false;
        if (ui.pressed(header)) {
            draggedNode = entity;
            Vector2f mouse = new Vector2f(ui.mouseX(), ui.mouseY());
            grabX = mouse.x;
            grabY = mouse.y;
            moved = false;
            clicked = true;
        } else if (ui.pressed(box)) {
            clicked = true;
        }

        for (Map.Entry<Component, Float> entry : node.componentRows().entrySet()) {
            Component component = entry.getKey();
            Rect row = new Rect(box.x() + 6f, oy + entry.getValue(), box.width() - 12f, COMPONENT_ROW - 2f);
            boolean missing = component instanceof MissingComponent;
            String name = missing ? "Missing " + ((MissingComponent) component).type().replaceAll(".*\\.", "")
                    : component.getClass().getSimpleName();
            Path source = missing ? null : studio.catalog.sourceOf(component.getClass());
            boolean script = source != null && component.getClass().getName().startsWith(studio.catalog.projectPackage());
            if (ui.hover(row)) {
                ui.gui.rounded(row, 3f, Theme.HOVER);
            }
            (missing ? Icon.WARNING : script ? Icon.SCRIPT : Icon.GEAR).draw(ui.gui, row.x() + 4f, row.centerY() - 7f, 14f,
                    missing ? Theme.ERROR : script ? AssetKind.SCRIPT.tint() : Theme.TEXT_MUTED);
            ui.textIn(ui.fit(name, row.width() - 30f), row, row.x() + 24f, Theme.SMALL_SCALE,
                    component.isEnabled() ? Theme.TEXT : Theme.TEXT_DISABLED);
            if (source != null) {
                ui.tooltip("graphScript:" + System.identityHashCode(component), row, "Double-click to open " + source.getFileName());
                if (ui.doublePressed("graphScriptOpen:" + System.identityHashCode(component), row)) {
                    studio.openCode(source, 1);
                }
            }
        }
        for (Socket socket : node.sockets()) {
            float sx = ox + socket.x();
            float sy = oy + socket.y();
            Rect row = new Rect(box.x() + 22f, sy - FIELD_ROW * 0.5f, box.width() - 30f, FIELD_ROW);
            boolean signal = socket.property().kind() == ComponentFields.Kind.SIGNAL;
            Color color = signal ? Theme.SIGNAL_WIRE : Theme.REFERENCE_WIRE;
            String value = describe(socket);
            ui.textIn(ui.fit(socket.property().label(), row.width() * 0.55f), row, row.x() + 8f, 1.32f, color);
            ui.textRight(ui.fit(value, row.width() * 0.42f, 1.25f), row, row.x() + row.width() - 8f, 1.25f, Theme.TEXT_MUTED);
            Rect socketRect = new Rect(sx - 8f, sy - 8f, 16f, 16f);
            boolean hover = ui.hover(socketRect);
            ui.gui.circle(sx, sy, hover ? 7f : 6f, Theme.FIELD);
            ui.gui.circle(sx, sy, hover ? 5f : 4f, color);
            if (hover) {
                ui.cursor(Window.CursorShape.CROSSHAIR);
                ui.tooltip("socket:" + System.identityHashCode(socket.component()) + socket.property().name(), socketRect,
                        signal ? "Drag onto a node to call one of its methods when this signal is raised"
                                : "Drag onto a node to point this field at it");
            }
            if (ui.pressed(socketRect)) {
                wireFrom = socket;
                draggedNode = null;
                clicked = false;
            }
            if (ui.rightPressed(socketRect)) {
                unlinkMenu(ui, doc, socket);
            }
        }
        return clicked;
    }

    private static String describe(Socket socket) {
        Object value = socket.property().get(socket.component());
        return switch (socket.property().kind()) {
            case ENTITY -> value instanceof Entity e ? e.name() : "None";
            case COMPONENT -> value instanceof Component c && c.entity() != null ? c.entity().name() : "None";
            case SIGNAL -> ((Signal) value).connections().size() + " wired";
            default -> "";
        };
    }

    // --- Wires ------------------------------------------------------------------------------------------------

    private void wires(Ui ui, SceneDocument doc, Map<Entity, Node> nodes, float ox, float oy) {
        if (showParents) {
            for (Node node : nodes.values()) {
                Entity parent = node.entity().parent();
                Node from = parent == null ? null : nodes.get(parent);
                if (from != null) {
                    float x1 = ox + from.x() + 24f;
                    float y1 = oy + from.y() + from.height();
                    float x2 = ox + node.inputX();
                    float y2 = oy + node.inputY();
                    elbow(ui, x1, y1, x2, y2, Theme.PARENT_WIRE.withAlpha(0.6f), 1.6f);
                }
            }
        }
        for (Node node : nodes.values()) {
            for (Socket socket : node.sockets()) {
                float sx = ox + socket.x();
                float sy = oy + socket.y();
                Object value = socket.property().get(socket.component());
                switch (socket.property().kind()) {
                    case ENTITY, COMPONENT -> {
                        if (!showReferences) {
                            continue;
                        }
                        Entity target = value instanceof Entity e ? e : value instanceof Component c ? c.entity() : null;
                        Node to = target == null ? null : nodes.get(target);
                        if (to != null) {
                            curve(ui, sx, sy, ox + to.inputX(), oy + to.inputY(), Theme.REFERENCE_WIRE, 2.4f);
                        }
                    }
                    case SIGNAL -> {
                        if (!showSignals) {
                            continue;
                        }
                        for (Signal.Connection wire : ((Signal) value).connections()) {
                            Node to = wire.target() == null ? null : nodes.get(wire.target());
                            if (to == null) {
                                continue;
                            }
                            float tx = ox + to.inputX();
                            float ty = oy + to.inputY();
                            curve(ui, sx, sy, tx, ty, Theme.SIGNAL_WIRE, 2.4f);
                            if (wire.method() != null && !wire.method().isEmpty()) {
                                String label = wire.method() + "()";
                                float lx = (sx + tx) * 0.5f;
                                float ly = (sy + ty) * 0.5f;
                                float w = ui.width(label, 1.3f) + 12f;
                                Rect tag = new Rect(lx - w * 0.5f, ly - 10f, w, 20f);
                                ui.gui.rounded(tag, 10f, Theme.FIELD);
                                ui.gui.roundedOutline(tag, 10f, 1f, Theme.SIGNAL_WIRE);
                                ui.textCentered(label, tag, 1.3f, Theme.SIGNAL_WIRE);
                                if (ui.rightPressed(tag)) {
                                    Signal signal = (Signal) value;
                                    Menu.openAtPointer(ui, "wireMenu", List.of(new Menu.Command("Disconnect " + label, () -> {
                                        signal.disconnect(wire);
                                        doc.changed();
                                    })));
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private static void curve(Ui ui, float x1, float y1, float x2, float y2, Color color, float thickness) {
        float dx = Math.max(50f, Math.abs(x2 - x1) * 0.5f);
        float px = x1;
        float py = y1;
        int steps = 28;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float u = 1f - t;
            float x = u * u * u * x1 + 3f * u * u * t * (x1 + dx) + 3f * u * t * t * (x2 - dx) + t * t * t * x2;
            float y = u * u * u * y1 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y2;
            ui.gui.line(px, py, x, y, thickness, color);
            px = x;
            py = y;
        }
        ui.gui.circle(x2, y2, 4f, color);
    }

    /** A parent's link to a child: down from under the parent, then round into the child's side. */
    private static void elbow(Ui ui, float x1, float y1, float x2, float y2, Color color, float thickness) {
        float px = x1;
        float py = y1;
        int steps = 20;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float u = 1f - t;
            float x = u * u * u * x1 + 3f * u * u * t * x1 + 3f * u * t * t * x1 + t * t * t * x2;
            float y = u * u * u * y1 + 3f * u * u * t * y2 + 3f * u * t * t * y2 + t * t * t * y2;
            ui.gui.line(px, py, x, y, thickness, color);
            px = x;
            py = y;
        }
    }

    // --- Linking ----------------------------------------------------------------------------------------------

    private void connect(Ui ui, SceneDocument doc, Socket from, Entity target) {
        Property property = from.property();
        switch (property.kind()) {
            case ENTITY -> {
                property.set(from.component(), target);
                doc.changed();
            }
            case COMPONENT -> {
                @SuppressWarnings("unchecked")
                Class<Object> wanted = (Class<Object>) property.type();
                Object found = target.get(wanted);
                if (found == null) {
                    studio.status(target.name() + " has no " + property.type().getSimpleName(), true);
                    return;
                }
                property.set(from.component(), found);
                doc.changed();
            }
            case SIGNAL -> {
                Signal signal = (Signal) property.get(from.component());
                List<Menu.Item> items = new ArrayList<>();
                for (Component component : target.components()) {
                    for (String method : Signal.callableMethods(component.getClass())) {
                        String type = component.getClass().getName();
                        items.add(new Menu.Command(component.getClass().getSimpleName() + "." + method + "()", () -> {
                            signal.connect(target, type, method);
                            doc.changed();
                        }));
                    }
                }
                if (items.isEmpty()) {
                    studio.status(target.name() + " has no methods a signal can call", true);
                } else {
                    Menu.openAtPointer(ui, "signalMethods", items);
                }
            }
            default -> {
            }
        }
    }

    private void unlinkMenu(Ui ui, SceneDocument doc, Socket socket) {
        Property property = socket.property();
        List<Menu.Item> items = new ArrayList<>();
        if (property.kind() == ComponentFields.Kind.SIGNAL) {
            Signal signal = (Signal) property.get(socket.component());
            for (Signal.Connection wire : signal.connections()) {
                String to = wire.target() == null ? "nothing" : wire.target().name();
                items.add(new Menu.Command("Disconnect " + to + "." + wire.method() + "()", () -> {
                    signal.disconnect(wire);
                    doc.changed();
                }));
            }
        } else if (property.get(socket.component()) != null) {
            items.add(new Menu.Command("Clear " + property.label(), () -> {
                property.set(socket.component(), null);
                doc.changed();
            }));
        }
        if (!items.isEmpty()) {
            Menu.openAtPointer(ui, "socketMenu", items);
        }
    }

    // --- Pointer ----------------------------------------------------------------------------------------------

    private void input(Ui ui, Rect canvas, Vector2f graphMouse) {
        SceneDocument doc = studio.doc();
        boolean hover = ui.hover(canvas);
        if (panButton < 0 && hover) {
            if (ui.input.isMousePressed(MouseButton.MIDDLE)) {
                panButton = MouseButton.MIDDLE;
            } else if (ui.input.isMousePressed(MouseButton.RIGHT)) {
                panButton = MouseButton.RIGHT;
            }
        }
        if (panButton >= 0) {
            if (ui.input.isMouseDown(panButton)) {
                pan.add(ui.input.mouseDeltaX() / ui.gui.scale() / zoom, ui.input.mouseDeltaY() / ui.gui.scale() / zoom);
                ui.cursor(Window.CursorShape.HAND);
            } else {
                panButton = -1;
            }
        }
        if (hover && ui.takeScroll(canvas)) {
            zoom = Math.clamp(zoom * (float) Math.pow(1.12, ui.input.scroll()), 0.25f, 2f);
            // Zoom about the pointer: the point under it stays under it.
            float mx = ui.mouseX() - canvas.x();
            float my = ui.mouseY() - canvas.y();
            pan.x = mx / zoom - graphMouse.x;
            pan.y = my / zoom - graphMouse.y;
        }
        if (draggedNode != null) {
            if (ui.input.isMouseDown(MouseButton.LEFT) && doc != null) {
                float dx = (ui.mouseX() - grabX) / zoom;
                float dy = (ui.mouseY() - grabY) / zoom;
                if (dx != 0f || dy != 0f) {
                    Map<String, Object> positions = positions(doc);
                    Vector2f p = position(doc, draggedNode, positions);
                    setPosition(doc, draggedNode, positions, p.x + dx, p.y + dy);
                    grabX = ui.mouseX();
                    grabY = ui.mouseY();
                    moved = true;
                    // The node moves by whole units; what is left over is carried to the next frame.
                    Vector2f now = position(doc, draggedNode, positions);
                    grabX -= (p.x + dx - now.x) * zoom;
                    grabY -= (p.y + dy - now.y) * zoom;
                }
            } else {
                if (moved && doc != null) {
                    doc.changed();
                }
                draggedNode = null;
            }
        }
    }

    private Vector2f toGraph(Rect canvas, float x, float y) {
        return new Vector2f((x - canvas.x()) / zoom - pan.x, (y - canvas.y()) / zoom - pan.y);
    }

    private static Node nodeAt(Map<Entity, Node> nodes, Vector2f graph) {
        Node hit = null;
        for (Node node : nodes.values()) {
            if (graph.x >= node.x() - 8f && graph.x <= node.x() + NODE_WIDTH && graph.y >= node.y()
                    && graph.y <= node.y() + node.height()) {
                hit = node;
            }
        }
        return hit;
    }
}
