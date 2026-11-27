package org.zeronura.engine.studio;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.input.Key;
import org.zeronura.engine.input.MouseButton;
import org.zeronura.engine.render.Texture;
import org.zeronura.engine.ui.Rect;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.system.MemoryUtil;

/**
 * The Sprite Editor: paints pictures pixel by pixel — sprites, sprite sheets, tilesets — in a tab of its own. Pencil,
 * eraser, fill, line, rectangle and colour picker, in brush sizes; a palette and the colours last used; a pixel grid
 * and a grid of frames or tiles laid over a sheet. Middle-drag pans, the wheel zooms, a right click picks a colour up.
 * It saves PNG files in the project's assets, and what shows a saved picture shows the new one straight away.
 */
final class SpritePanel {

    enum Tool {
        PENCIL("Pencil"), ERASER("Eraser"), FILL("Fill"), LINE("Line"), RECTANGLE("Rectangle"), PICKER("Picker");

        final String label;

        Tool(String label) {
            this.label = label;
        }
    }

    /** A palette to start from: greys, then warm to cool, each in a dark, a middle and a light. */
    private static final int[] PALETTE = {
            0x000000, 0x1D1D24, 0x45444F, 0x7A7887, 0xB5B3C2, 0xFFFFFF,
            0x5A1E22, 0xA8323A, 0xEF5B5B, 0x7A3F17, 0xD97A2B, 0xFFB063,
            0x7A6416, 0xD9B532, 0xFFE985, 0x264D1E, 0x4B9B3A, 0x9ADB6A,
            0x163F4D, 0x2A8AA8, 0x7FD3E6, 0x22265A, 0x4752C4, 0x98A3F5,
            0x45204F, 0x8A3FA3, 0xD38BE6, 0x6B4A36, 0xA97C5A, 0xE8C4A2,
    };

    private final StudioScene studio;
    private int width;
    private int height;
    /** ARGB, row by row from the top. */
    private int[] pixels;
    Path file;
    private String name = "Sprite";
    private int revision;
    private int savedRevision;

    private Texture texture;
    private int uploadedRevision = -1;
    private int uploadedWidth;
    private int uploadedHeight;

    private Tool tool = Tool.PENCIL;
    private Color color = Color.rgb(0x1D1D24);
    private final List<Color> recent = new ArrayList<>();
    private int brush = 1;
    private boolean pixelGrid = true;
    private boolean frames;
    private int frameWidth = 16;
    private int frameHeight = 16;

    private float zoom = 12f;
    private float panX;
    private float panY;
    private boolean centred;
    private int panButton = -1;

    private boolean stroking;
    private int fromX;
    private int fromY;
    private int lastX;
    private int lastY;
    private final ArrayDeque<int[]> undo = new ArrayDeque<>();
    private final ArrayDeque<int[]> redo = new ArrayDeque<>();
    private final ArrayDeque<int[]> undoSizes = new ArrayDeque<>();
    private final ArrayDeque<int[]> redoSizes = new ArrayDeque<>();

    SpritePanel(StudioScene studio) {
        this.studio = studio;
    }

    boolean dirty() {
        return pixels != null && revision != savedRevision;
    }

    boolean hasPicture() {
        return pixels != null;
    }

    String title() {
        return name + (dirty() ? " *" : "");
    }

    String name() {
        return name;
    }

    // --- Files ----------------------------------------------------------------------------------------------------

    void newSprite(int w, int h) {
        width = Math.clamp(w, 1, 2048);
        height = Math.clamp(h, 1, 2048);
        pixels = new int[width * height];
        file = null;
        name = "Sprite";
        revision++;
        savedRevision = revision;
        clearHistory();
        centred = false;
    }

    boolean open(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                studio.status("Can't read " + path.getFileName() + " as a picture", true);
                return false;
            }
            width = image.getWidth();
            height = image.getHeight();
            pixels = image.getRGB(0, 0, width, height, null, 0, width);
            file = path.toAbsolutePath().normalize();
            name = path.getFileName().toString();
            revision++;
            savedRevision = revision;
            clearHistory();
            centred = false;
            return true;
        } catch (IOException | RuntimeException e) {
            studio.status("Couldn't open " + path.getFileName() + ": " + e.getMessage(), true);
            return false;
        }
    }

    void save(Runnable then) {
        if (pixels == null) {
            return;
        }
        if (file == null) {
            saveAs(then);
            return;
        }
        write(file);
        if (then != null) {
            then.run();
        }
    }

    void saveAs(Runnable then) {
        if (pixels == null) {
            return;
        }
        Path folder = studio.assetsRoot().resolve("sprites");
        String suggested = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        studio.dialog(Dialog.prompt("Save Sprite", "Name, in " + studio.displayPath(folder), suggested, studio::fileNameProblem,
                chosen -> {
                    String fileName = chosen.trim().replaceAll("[^A-Za-z0-9 _()\\-]", "");
                    Path target = folder.resolve((fileName.isEmpty() ? "Sprite" : fileName) + ".png");
                    write(target);
                    if (then != null) {
                        then.run();
                    }
                }));
    }

    private void write(Path target) {
        try {
            Files.createDirectories(target.getParent());
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            ImageIO.write(image, "png", target.toFile());
            file = target.toAbsolutePath().normalize();
            name = file.getFileName().toString();
            savedRevision = revision;
            String asset = studio.assetPath(file);
            if (asset != null && studio.doc() != null && studio.doc().scene != null && studio.doc().scene.engine() != null) {
                studio.doc().scene.assets().reloadTexture(asset);
            }
            studio.assets.forgetPictures(file);
            studio.assets.refresh();
            studio.status("Saved " + studio.displayPath(file), false);
        } catch (IOException | RuntimeException e) {
            studio.status("Couldn't save the sprite: " + e.getMessage(), true);
        }
    }

    // --- Undo -----------------------------------------------------------------------------------------------------

    private void clearHistory() {
        undo.clear();
        redo.clear();
        undoSizes.clear();
        redoSizes.clear();
    }

    private void remember() {
        undo.push(pixels.clone());
        undoSizes.push(new int[]{width, height});
        if (undo.size() > 60) {
            undo.removeLast();
            undoSizes.removeLast();
        }
        redo.clear();
        redoSizes.clear();
    }

    void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(pixels);
        redoSizes.push(new int[]{width, height});
        pixels = undo.pop();
        int[] size = undoSizes.pop();
        width = size[0];
        height = size[1];
        revision++;
    }

    void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(pixels);
        undoSizes.push(new int[]{width, height});
        pixels = redo.pop();
        int[] size = redoSizes.pop();
        width = size[0];
        height = size[1];
        revision++;
    }

    // --- Drawing --------------------------------------------------------------------------------------------------

    void draw(Ui ui, Rect area) {
        if (pixels == null) {
            newSprite(32, 32);
        }
        Rect bar = new Rect(area.x(), area.y(), area.width(), 32f);
        toolbar(ui, bar);
        float sideWidth = Math.min(236f, area.width() * 0.26f);
        Rect canvas = new Rect(area.x(), bar.y() + bar.height(), area.width() - sideWidth, area.height() - bar.height());
        Rect side = new Rect(canvas.x() + canvas.width(), canvas.y(), sideWidth, canvas.height());
        if (ui.pressed(canvas) || ui.rightPressed(canvas)) {
            studio.setFocus(StudioScene.Focus.SPRITE);
        }
        canvas(ui, canvas);
        side(ui, side);
        if (studio.focus() == StudioScene.Focus.SPRITE && !ui.typing() && !ui.modal()) {
            keys(ui);
        }
    }

    private void toolbar(Ui ui, Rect bar) {
        ui.fill(bar, Theme.PANEL_HEADER);
        ui.hline(bar.x(), bar.y() + bar.height() - 1f, bar.width(), Theme.BORDER);
        float x = bar.x() + 6f;
        float y = bar.y() + 4f;
        if (ui.button("spriteNew", "New...", new Rect(x, y, 58f, 24f))) {
            studio.confirmSpriteSaved(this::askNew);
        }
        x += 62f;
        if (ui.button("spriteOpen", "Open...", new Rect(x, y, 66f, 24f))) {
            Rect at = new Rect(x, y, 66f, 24f);
            AssetPicker.open(ui, studio, "spriteOpenPicker", at.x(), at.y() + 26f, 340f, new String[]{"png"},
                    path -> studio.openSpriteFile(studio.assetsRoot().resolve(path)));
        }
        x += 70f;
        if (ui.button("spriteSave", "Save", new Rect(x, y, 52f, 24f))) {
            save(null);
        }
        x += 56f;
        if (ui.button("spriteSaveAs", "Save As...", new Rect(x, y, 80f, 24f))) {
            saveAs(null);
        }
        x += 90f;
        ui.vline(x, y + 2f, 20f, Theme.DIVIDER);
        x += 10f;
        for (Tool t : Tool.values()) {
            float w = ui.width(t.label, Theme.SMALL_SCALE) + 18f;
            if (ui.flatButton("spriteTool:" + t, t.label, new Rect(x, y, w, 24f), tool == t)) {
                tool = t;
            }
            x += w + 3f;
        }
        x += 8f;
        ui.textIn("Size", bar, x, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        x += ui.width("Size", Theme.SMALL_SCALE) + 6f;
        ui.number("spriteBrush", new Rect(x, y, 44f, 24f), brush, 0.1, 1, 32, true, v -> brush = (int) Math.round(v));
        x += 54f;
        pixelGrid = ui.checkbox("spritePixelGrid", new Rect(x, y + 3f, 18f, 18f), pixelGrid);
        ui.textIn("Grid", bar, x + 22f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        x += 60f;
        frames = ui.checkbox("spriteFrames", new Rect(x, y + 3f, 18f, 18f), frames);
        ui.textIn("Frames", bar, x + 22f, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        x += 22f + ui.width("Frames", Theme.SMALL_SCALE) + 6f;
        ui.number("spriteFrameW", new Rect(x, y, 40f, 24f), frameWidth, 0.1, 1, 1024, true, v -> frameWidth = (int) Math.round(v));
        x += 44f;
        ui.textIn("x", bar, x, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        x += 12f;
        ui.number("spriteFrameH", new Rect(x, y, 40f, 24f), frameHeight, 0.1, 1, 1024, true, v -> frameHeight = (int) Math.round(v));
        String where = (file == null ? "not saved yet" : studio.displayPath(file)) + "   " + width + " x " + height + "   "
                + Math.round(zoom * 100f) + "%";
        float room = bar.x() + bar.width() - x - 60f;
        if (room > 60f) {
            ui.textRight(ui.fit(where, room, Theme.SMALL_SCALE), bar, bar.x() + bar.width() - 10f, Theme.SMALL_SCALE,
                    Theme.TEXT_MUTED);
        }
    }

    private void askNew() {
        studio.dialog(Dialog.prompt("New Sprite", "Size in pixels, as width x height", width + " x " + height, text -> {
            int[] size = parseSize(text);
            return size == null ? "Write it as 32 x 32, up to 2048 x 2048" : null;
        }, text -> {
            int[] size = parseSize(text);
            newSprite(size[0], size[1]);
            studio.status("A new " + width + " x " + height + " sprite: paint it, then Save", false);
        }));
    }

    static int[] parseSize(String text) {
        String[] parts = text.toLowerCase(java.util.Locale.ROOT).replace("*", "x").split("x");
        try {
            if (parts.length == 1) {
                int side = Integer.parseInt(parts[0].trim());
                return side >= 1 && side <= 2048 ? new int[]{side, side} : null;
            }
            if (parts.length == 2) {
                int w = Integer.parseInt(parts[0].trim());
                int h = Integer.parseInt(parts[1].trim());
                return w >= 1 && h >= 1 && w <= 2048 && h <= 2048 ? new int[]{w, h} : null;
            }
        } catch (NumberFormatException ignored) {
            // not a size
        }
        return null;
    }

    private void canvas(Ui ui, Rect canvas) {
        ui.fill(canvas, Theme.FIELD);
        if (!centred && canvas.width() > 10f) {
            zoom = Math.max(1f, Math.min(48f, (float) Math.floor(Math.min((canvas.width() - 80f) / width,
                    (canvas.height() - 80f) / height))));
            panX = (canvas.width() - width * zoom) * 0.5f;
            panY = (canvas.height() - height * zoom) * 0.5f;
            centred = true;
        }
        pointerPanZoom(ui, canvas);
        ui.gui.pushClip(canvas);
        Rect image = new Rect(canvas.x() + panX, canvas.y() + panY, width * zoom, height * zoom);
        checker(ui, image, canvas);
        upload();
        if (texture != null) {
            ui.gui.image(texture, image, Color.WHITE);
        }
        if (pixelGrid && zoom >= 6f) {
            Color line = Color.WHITE.withAlpha(0.07f);
            for (int x = 1; x < width; x++) {
                ui.gui.line(image.x() + x * zoom, image.y(), image.x() + x * zoom, image.y() + image.height(), 1f, line);
            }
            for (int y = 1; y < height; y++) {
                ui.gui.line(image.x(), image.y() + y * zoom, image.x() + image.width(), image.y() + y * zoom, 1f, line);
            }
        }
        if (frames && frameWidth > 0 && frameHeight > 0) {
            Color line = Theme.ACCENT.withAlpha(0.55f);
            for (int x = frameWidth; x < width; x += frameWidth) {
                ui.gui.line(image.x() + x * zoom, image.y(), image.x() + x * zoom, image.y() + image.height(), 1.5f, line);
            }
            for (int y = frameHeight; y < height; y += frameHeight) {
                ui.gui.line(image.x(), image.y() + y * zoom, image.x() + image.width(), image.y() + y * zoom, 1.5f, line);
            }
        }
        ui.gui.outline(image, 1f, Theme.FIELD_BORDER);

        int px = (int) Math.floor((ui.mouseX() - image.x()) / zoom);
        int py = (int) Math.floor((ui.mouseY() - image.y()) / zoom);
        boolean hover = ui.hover(canvas) && !ui.modal() && panButton < 0;
        if (hover && inside(px, py)) {
            int half = (brush - 1) / 2;
            int size = tool == Tool.PENCIL || tool == Tool.ERASER ? brush : 1;
            int at = size == 1 ? 0 : half;
            ui.gui.outline(new Rect(image.x() + (px - at) * zoom, image.y() + (py - at) * zoom, size * zoom, size * zoom), 1.5f,
                    tool == Tool.ERASER ? Theme.ERROR : Theme.TEXT_BRIGHT);
        }
        paint(ui, hover, px, py, image);
        ui.gui.popClip();
        ui.text(ui.fit((inside(px, py) && hover ? px + ", " + py + "   " : "") + "Middle-drag to pan  -  wheel to zoom  -  "
                + "right click picks a colour  -  Ctrl+Z undoes", canvas.width() - 20f, Theme.SMALL_SCALE), canvas.x() + 10f,
                canvas.y() + canvas.height() - 20f, Theme.SMALL_SCALE, Theme.TEXT_DISABLED);
    }

    /** A grey checkerboard where the picture is see-through. */
    private void checker(Ui ui, Rect image, Rect clip) {
        float cell = Math.max(8f, zoom * Math.max(1, Math.round(8f / zoom)));
        ui.gui.rect(image, new Color(0.30f, 0.30f, 0.32f, 1f));
        float left = Math.max(image.x(), clip.x());
        float top = Math.max(image.y(), clip.y());
        float right = Math.min(image.x() + image.width(), clip.x() + clip.width());
        float bottom = Math.min(image.y() + image.height(), clip.y() + clip.height());
        Color light = new Color(0.40f, 0.40f, 0.42f, 1f);
        int startX = (int) Math.floor((left - image.x()) / cell);
        int startY = (int) Math.floor((top - image.y()) / cell);
        for (int cy = startY; image.y() + cy * cell < bottom; cy++) {
            for (int cx = startX; image.x() + cx * cell < right; cx++) {
                if ((cx + cy) % 2 == 0) {
                    continue;
                }
                float x0 = image.x() + cx * cell;
                float y0 = image.y() + cy * cell;
                float w = Math.min(cell, image.x() + image.width() - x0);
                float h = Math.min(cell, image.y() + image.height() - y0);
                ui.gui.rect(x0, y0, w, h, light);
            }
        }
    }

    private void pointerPanZoom(Ui ui, Rect canvas) {
        boolean hover = ui.hover(canvas) && !ui.modal();
        if (panButton < 0 && hover && ui.input.isMousePressed(MouseButton.MIDDLE)) {
            panButton = MouseButton.MIDDLE;
        } else if (panButton >= 0) {
            if (ui.input.isMouseDown(panButton)) {
                panX += ui.input.mouseDeltaX() / ui.gui.scale();
                panY += ui.input.mouseDeltaY() / ui.gui.scale();
            } else {
                panButton = -1;
            }
        }
        float wheel = hover ? ui.input.scroll() : 0f;
        if (wheel != 0f) {
            float before = zoom;
            zoom = Math.clamp(zoom * (wheel > 0 ? 1.25f : 0.8f), 0.5f, 96f);
            // Keep the pixel under the pointer where it is.
            float mx = ui.mouseX() - canvas.x();
            float my = ui.mouseY() - canvas.y();
            panX = mx - (mx - panX) * zoom / before;
            panY = my - (my - panY) * zoom / before;
        }
    }

    private void paint(Ui ui, boolean hover, int px, int py, Rect image) {
        if (stroking) {
            if (!ui.input.isMouseDown(MouseButton.LEFT)) {
                if (tool == Tool.LINE) {
                    line(fromX, fromY, px, py, false);
                } else if (tool == Tool.RECTANGLE) {
                    rectangle(fromX, fromY, px, py, ui.input.shiftDown());
                }
                stroking = false;
                remembered(color);
                return;
            }
            switch (tool) {
                case PENCIL, ERASER -> {
                    line(lastX, lastY, px, py, true);
                    lastX = px;
                    lastY = py;
                }
                case LINE -> previewLine(ui, image, fromX, fromY, px, py);
                case RECTANGLE -> previewRectangle(ui, image, fromX, fromY, px, py);
                default -> {
                }
            }
            return;
        }
        if (!hover || !inside(px, py)) {
            return;
        }
        if (ui.input.isMousePressed(MouseButton.RIGHT)) {
            pickColor(px, py);
            return;
        }
        if (!ui.input.isMousePressed(MouseButton.LEFT)) {
            return;
        }
        switch (tool) {
            case PICKER -> pickColor(px, py);
            case FILL -> {
                remember();
                fill(px, py, argb(color));
                remembered(color);
            }
            default -> {
                remember();
                stroking = true;
                fromX = lastX = px;
                fromY = lastY = py;
                if (tool == Tool.PENCIL || tool == Tool.ERASER) {
                    dab(px, py);
                }
            }
        }
    }

    private void pickColor(int px, int py) {
        int value = pixels[py * width + px];
        color = new Color(((value >> 16) & 0xFF) / 255f, ((value >> 8) & 0xFF) / 255f, (value & 0xFF) / 255f,
                ((value >>> 24) & 0xFF) / 255f);
        if (tool == Tool.PICKER || tool == Tool.ERASER) {
            tool = Tool.PENCIL;
        }
    }

    private boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    private static int argb(Color c) {
        return (Math.round(Math.clamp(c.a(), 0f, 1f) * 255f) << 24) | (Math.round(Math.clamp(c.r(), 0f, 1f) * 255f) << 16)
                | (Math.round(Math.clamp(c.g(), 0f, 1f) * 255f) << 8) | Math.round(Math.clamp(c.b(), 0f, 1f) * 255f);
    }

    /** One touch of the pencil or eraser: a square of the brush's size. */
    private void dab(int x, int y) {
        int value = tool == Tool.ERASER ? 0 : argb(color);
        int half = (brush - 1) / 2;
        for (int dy = 0; dy < brush; dy++) {
            for (int dx = 0; dx < brush; dx++) {
                set(x - half + dx, y - half + dy, value);
            }
        }
    }

    private void set(int x, int y, int value) {
        if (inside(x, y) && pixels[y * width + x] != value) {
            pixels[y * width + x] = value;
            revision++;
        }
    }

    /** Every pixel from one to the other: dabbed with the brush, or set one by one in the colour. */
    private void line(int x0, int y0, int x1, int y1, boolean dabbed) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        int value = argb(color);
        while (true) {
            if (dabbed) {
                dab(x0, y0);
            } else {
                set(x0, y0, value);
            }
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int e2 = 2 * error;
            if (e2 >= dy) {
                error += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    private void rectangle(int ax, int ay, int bx, int by, boolean filled) {
        int x0 = Math.min(ax, bx);
        int x1 = Math.max(ax, bx);
        int y0 = Math.min(ay, by);
        int y1 = Math.max(ay, by);
        int value = argb(color);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (filled || x == x0 || x == x1 || y == y0 || y == y1) {
                    set(x, y, value);
                }
            }
        }
    }

    /** Fills the run of same-coloured pixels joined side by side to one. */
    private void fill(int startX, int startY, int value) {
        int target = pixels[startY * width + startX];
        if (target == value) {
            return;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startY * width + startX);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            if (pixels[i] != target) {
                continue;
            }
            pixels[i] = value;
            int x = i % width;
            int y = i / width;
            if (x > 0) {
                queue.add(i - 1);
            }
            if (x < width - 1) {
                queue.add(i + 1);
            }
            if (y > 0) {
                queue.add(i - width);
            }
            if (y < height - 1) {
                queue.add(i + width);
            }
        }
        revision++;
    }

    private void previewLine(Ui ui, Rect image, int x0, int y0, int x1, int y1) {
        ui.gui.line(image.x() + (x0 + 0.5f) * zoom, image.y() + (y0 + 0.5f) * zoom, image.x() + (x1 + 0.5f) * zoom,
                image.y() + (y1 + 0.5f) * zoom, Math.max(1f, zoom * 0.6f), color.withAlpha(Math.max(0.5f, color.a())));
    }

    private void previewRectangle(Ui ui, Rect image, int ax, int ay, int bx, int by) {
        Rect r = new Rect(image.x() + Math.min(ax, bx) * zoom, image.y() + Math.min(ay, by) * zoom,
                (Math.abs(bx - ax) + 1) * zoom, (Math.abs(by - ay) + 1) * zoom);
        ui.gui.outline(r, Math.max(1f, zoom), color.withAlpha(Math.max(0.5f, color.a())));
    }

    private void remembered(Color used) {
        if (tool == Tool.ERASER) {
            return;
        }
        recent.removeIf(c -> argb(c) == argb(used));
        recent.addFirst(used);
        while (recent.size() > 12) {
            recent.removeLast();
        }
    }

    /** The picture on the GPU, sent again when it has changed since. */
    private void upload() {
        if (texture != null && uploadedRevision == revision && uploadedWidth == width && uploadedHeight == height) {
            return;
        }
        ByteBuffer rgba = MemoryUtil.memAlloc(width * height * 4);
        try {
            for (int value : pixels) {
                rgba.put((byte) (value >> 16)).put((byte) (value >> 8)).put((byte) value).put((byte) (value >>> 24));
            }
            rgba.flip();
            if (texture == null || uploadedWidth != width || uploadedHeight != height) {
                if (texture != null) {
                    texture.delete();
                }
                texture = Texture.fromPixels(rgba, width, height).setNearestFiltering(true);
            } else {
                texture.updateRegion(rgba, 0, 0, width, height);
            }
            uploadedRevision = revision;
            uploadedWidth = width;
            uploadedHeight = height;
        } finally {
            MemoryUtil.memFree(rgba);
        }
    }

    // --- The colour panel ---------------------------------------------------------------------------------------

    private void side(Ui ui, Rect side) {
        ui.fill(side, Theme.PANEL);
        ui.vline(side.x(), side.y(), side.height(), Theme.BORDER);
        float x = side.x() + 12f;
        float y = side.y() + 12f;
        float w = side.width() - 24f;
        ui.heading("Colour", x, y, Theme.TEXT_BRIGHT);
        y += 26f;
        ColorPicker.swatch(ui, "spriteColor", new Rect(x, y, w, 30f), color, picked -> color = picked);
        y += 40f;
        ui.text("Palette", x, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
        y += 20f;
        y = swatches(ui, "spritePalette", x, y, w, paletteColors());
        if (!recent.isEmpty()) {
            y += 8f;
            ui.text("Last used", x, y, Theme.SMALL_SCALE, Theme.TEXT_MUTED);
            y += 20f;
            y = swatches(ui, "spriteRecent", x, y, w, recent);
        }
        y += 10f;
        Rect clear = new Rect(x, y, w, 24f);
        if (ui.button("spriteClearAll", "Clear to see-through", clear)) {
            remember();
            java.util.Arrays.fill(pixels, 0);
            revision++;
        }
        y += 30f;
        Rect fit = new Rect(x, y, w, 24f);
        if (ui.button("spriteFit", "Fit in view (Home)", fit)) {
            centred = false;
        }
    }

    private static List<Color> paletteColors() {
        List<Color> colors = new ArrayList<>();
        for (int value : PALETTE) {
            colors.add(Color.rgb(value));
        }
        return colors;
    }

    private float swatches(Ui ui, String id, float x, float y, float w, List<Color> colors) {
        int across = 6;
        float gap = 4f;
        float size = (w - gap * (across - 1)) / across;
        for (int i = 0; i < colors.size(); i++) {
            Color c = colors.get(i);
            Rect r = new Rect(x + (i % across) * (size + gap), y + (i / across) * (size + gap), size, size);
            ui.gui.rounded(r, 3f, c);
            if (argb(c) == argb(color)) {
                ui.gui.roundedOutline(r, 3f, 2f, Theme.HIGHLIGHT);
            } else if (ui.hover(r)) {
                ui.gui.roundedOutline(r, 3f, 1f, Theme.TEXT);
            }
            if (ui.clicked(id + ":" + i, r)) {
                color = c;
                if (tool == Tool.ERASER || tool == Tool.PICKER) {
                    tool = Tool.PENCIL;
                }
            }
        }
        return y + ((colors.size() + across - 1) / across) * (size + gap);
    }

    private void keys(Ui ui) {
        if (ui.input.isKeyPressed(Key.HOME)) {
            centred = false;
        }
    }

    void delete() {
        if (texture != null) {
            texture.delete();
            texture = null;
        }
    }
}
