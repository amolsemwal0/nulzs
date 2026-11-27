package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.gui.Gui;
import org.zeronura.nulzs.ui.Rect;

/**
 * The editor's icons, drawn from shapes rather than pictures so they are crisp at any scale and need no files: each
 * fills a square, in one colour.
 */
enum Icon {
    PLAY, PAUSE, STOP, STEP, HAND, MOVE, ROTATE, SCALE, GLOBAL, LOCAL, SNAP, GRID,
    FOLDER, FOLDER_OPEN, FILE, IMAGE, MODEL, SCRIPT, SCENE, AUDIO, FONT, TEXT,
    ENTITY, CAMERA, LIGHT, SPRITE, SHAPE,
    CHEVRON_RIGHT, CHEVRON_DOWN, PLUS, CLOSE, SEARCH, GEAR, EYE, EYE_OFF, LINK, HAMMER, RUN, WARNING, ERROR, INFO,
    CHECK, DOTS, UP, REFRESH, GRAPH, CONSOLE, LIST, TILES, SIGNAL, SAVE, CIRCLE, PATH, TREE, SHADER, TOOLS, TERRAIN, TILEMAP, PENCIL,
    GEOMETRY;

    void draw(Gui gui, float x, float y, float size, Color c) {
        float s = size;
        float cx = x + s * 0.5f;
        float cy = y + s * 0.5f;
        float t = Math.max(1.2f, s * 0.09f);
        switch (this) {
            case PLAY -> gui.triangle(x + s * 0.25f, y + s * 0.15f, x + s * 0.85f, cy, x + s * 0.25f, y + s * 0.85f, c);
            case PAUSE -> {
                gui.rect(x + s * 0.22f, y + s * 0.18f, s * 0.2f, s * 0.64f, c);
                gui.rect(x + s * 0.58f, y + s * 0.18f, s * 0.2f, s * 0.64f, c);
            }
            case STOP -> gui.rect(x + s * 0.2f, y + s * 0.2f, s * 0.6f, s * 0.6f, c);
            case STEP -> {
                gui.triangle(x + s * 0.18f, y + s * 0.18f, x + s * 0.62f, cy, x + s * 0.18f, y + s * 0.82f, c);
                gui.rect(x + s * 0.66f, y + s * 0.18f, s * 0.16f, s * 0.64f, c);
            }
            case HAND -> {
                for (int i = 0; i < 4; i++) {
                    float fx = x + s * (0.22f + i * 0.15f);
                    gui.rect(fx, y + s * (i == 0 ? 0.35f : 0.15f), s * 0.11f, s * 0.45f, c);
                }
                gui.rect(x + s * 0.22f, y + s * 0.5f, s * 0.56f, s * 0.35f, c);
            }
            case MOVE -> {
                gui.line(x + s * 0.12f, cy, x + s * 0.88f, cy, t, c);
                gui.line(cx, y + s * 0.12f, cx, y + s * 0.88f, t, c);
                float a = s * 0.16f;
                gui.triangle(cx, y + s * 0.04f, cx + a, y + s * 0.04f + a, cx - a, y + s * 0.04f + a, c);
                gui.triangle(cx, y + s * 0.96f, cx - a, y + s * 0.96f - a, cx + a, y + s * 0.96f - a, c);
                gui.triangle(x + s * 0.04f, cy, x + s * 0.04f + a, cy - a, x + s * 0.04f + a, cy + a, c);
                gui.triangle(x + s * 0.96f, cy, x + s * 0.96f - a, cy + a, x + s * 0.96f - a, cy - a, c);
            }
            case ROTATE, REFRESH -> {
                arc(gui, cx, cy, s * 0.33f, 0.6f, 5.6f, t, c);
                float ex = cx + (float) Math.cos(0.6f) * s * 0.33f;
                float ey = cy + (float) Math.sin(0.6f) * s * 0.33f;
                gui.triangle(ex - s * 0.16f, ey - s * 0.02f, ex + s * 0.14f, ey - s * 0.06f, ex + s * 0.02f, ey + s * 0.2f, c);
            }
            case SCALE -> {
                gui.outline(new Rect(x + s * 0.12f, y + s * 0.42f, s * 0.46f, s * 0.46f), t, c);
                gui.line(x + s * 0.45f, y + s * 0.55f, x + s * 0.85f, y + s * 0.15f, t, c);
                gui.triangle(x + s * 0.9f, y + s * 0.1f, x + s * 0.9f, y + s * 0.42f, x + s * 0.58f, y + s * 0.1f, c);
            }
            case GLOBAL -> {
                ring(gui, cx, cy, s * 0.38f, t, c);
                gui.line(x + s * 0.12f, cy, x + s * 0.88f, cy, t, c);
                ellipse(gui, cx, cy, s * 0.16f, s * 0.38f, t, c);
            }
            case LOCAL -> {
                gui.outline(new Rect(x + s * 0.2f, y + s * 0.2f, s * 0.6f, s * 0.6f), t, c);
                gui.rect(cx - s * 0.08f, cy - s * 0.08f, s * 0.16f, s * 0.16f, c);
            }
            case SNAP -> {
                gui.rect(x + s * 0.18f, y + s * 0.15f, s * 0.18f, s * 0.45f, c);
                gui.rect(x + s * 0.64f, y + s * 0.15f, s * 0.18f, s * 0.45f, c);
                arc(gui, cx, y + s * 0.6f, s * 0.23f, 0f, (float) Math.PI, s * 0.18f, c);
            }
            case GRID, TILES -> {
                float g = s * 0.3f;
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        gui.rect(x + s * 0.14f + i * (g + s * 0.12f), y + s * 0.14f + j * (g + s * 0.12f), g, g, c);
                    }
                }
            }
            case LIST, CONSOLE, TEXT -> {
                for (int i = 0; i < 4; i++) {
                    float w = this == TEXT && i == 3 ? 0.4f : 0.7f;
                    gui.rect(x + s * 0.15f, y + s * (0.18f + i * 0.19f), s * w, t * 1.1f, c);
                }
                if (this == CONSOLE) {
                    gui.rect(x + s * 0.08f, y + s * 0.08f, s * 0.84f, t, c);
                }
            }
            case FOLDER, FOLDER_OPEN -> {
                gui.rounded(new Rect(x + s * 0.08f, y + s * 0.2f, s * 0.36f, s * 0.2f), s * 0.05f, c);
                if (this == FOLDER) {
                    gui.rounded(new Rect(x + s * 0.08f, y + s * 0.3f, s * 0.84f, s * 0.52f), s * 0.06f, c);
                } else {
                    gui.rounded(new Rect(x + s * 0.08f, y + s * 0.3f, s * 0.84f, s * 0.52f), s * 0.06f, c.scaled(0.7f));
                    gui.polygon(new float[]{x + s * 0.22f, y + s * 0.45f, x + s * 0.98f, y + s * 0.45f,
                            x + s * 0.84f, y + s * 0.82f, x + s * 0.08f, y + s * 0.82f}, c, c);
                }
            }
            case FILE, SCRIPT, SCENE, AUDIO, FONT, IMAGE -> {
                if (this == IMAGE) {
                    gui.outline(new Rect(x + s * 0.12f, y + s * 0.18f, s * 0.76f, s * 0.64f), t, c);
                    gui.triangle(x + s * 0.18f, y + s * 0.76f, x + s * 0.42f, y + s * 0.42f, x + s * 0.62f, y + s * 0.76f, c);
                    gui.triangle(x + s * 0.48f, y + s * 0.76f, x + s * 0.64f, y + s * 0.54f, x + s * 0.82f, y + s * 0.76f, c);
                    gui.circle(x + s * 0.68f, y + s * 0.34f, s * 0.07f, c);
                    break;
                }
                float[] page = {x + s * 0.2f, y + s * 0.08f, x + s * 0.62f, y + s * 0.08f, x + s * 0.8f, y + s * 0.26f,
                        x + s * 0.8f, y + s * 0.92f, x + s * 0.2f, y + s * 0.92f};
                gui.polygonOutline(page, t, c, c);
                switch (this) {
                    case SCRIPT -> {
                        gui.line(x + s * 0.42f, y + s * 0.44f, x + s * 0.32f, y + s * 0.58f, t, c);
                        gui.line(x + s * 0.32f, y + s * 0.58f, x + s * 0.42f, y + s * 0.72f, t, c);
                        gui.line(x + s * 0.58f, y + s * 0.44f, x + s * 0.68f, y + s * 0.58f, t, c);
                        gui.line(x + s * 0.68f, y + s * 0.58f, x + s * 0.58f, y + s * 0.72f, t, c);
                    }
                    case SCENE -> gui.triangle(x + s * 0.3f, y + s * 0.78f, x + s * 0.5f, y + s * 0.42f, x + s * 0.7f, y + s * 0.78f, c);
                    case AUDIO -> {
                        gui.circle(x + s * 0.42f, y + s * 0.72f, s * 0.09f, c);
                        gui.rect(x + s * 0.48f, y + s * 0.36f, t, s * 0.36f, c);
                        gui.rect(x + s * 0.48f, y + s * 0.36f, s * 0.16f, t, c);
                    }
                    case FONT -> {
                        gui.line(x + s * 0.32f, y + s * 0.78f, x + s * 0.5f, y + s * 0.36f, t, c);
                        gui.line(x + s * 0.68f, y + s * 0.78f, x + s * 0.5f, y + s * 0.36f, t, c);
                        gui.line(x + s * 0.38f, y + s * 0.64f, x + s * 0.62f, y + s * 0.64f, t, c);
                    }
                    default -> {
                        for (int i = 0; i < 3; i++) {
                            gui.rect(x + s * 0.32f, y + s * (0.44f + i * 0.14f), s * 0.36f, t, c);
                        }
                    }
                }
            }
            case MODEL, SHAPE, ENTITY -> {
                float[] top = {cx, y + s * 0.1f, x + s * 0.88f, y + s * 0.3f, cx, y + s * 0.5f, x + s * 0.12f, y + s * 0.3f};
                if (this == ENTITY) {
                    gui.polygonOutline(top, t, c, c);
                    gui.line(x + s * 0.12f, y + s * 0.3f, x + s * 0.12f, y + s * 0.7f, t, c);
                    gui.line(x + s * 0.88f, y + s * 0.3f, x + s * 0.88f, y + s * 0.7f, t, c);
                    gui.line(cx, y + s * 0.5f, cx, y + s * 0.9f, t, c);
                    gui.line(x + s * 0.12f, y + s * 0.7f, cx, y + s * 0.9f, t, c);
                    gui.line(x + s * 0.88f, y + s * 0.7f, cx, y + s * 0.9f, t, c);
                } else {
                    gui.polygon(top, c, c);
                    gui.polygon(new float[]{x + s * 0.12f, y + s * 0.34f, cx - s * 0.03f, y + s * 0.54f, cx - s * 0.03f,
                            y + s * 0.92f, x + s * 0.12f, y + s * 0.72f}, c.scaled(0.75f), c.scaled(0.75f));
                    gui.polygon(new float[]{cx + s * 0.03f, y + s * 0.54f, x + s * 0.88f, y + s * 0.34f, x + s * 0.88f,
                            y + s * 0.72f, cx + s * 0.03f, y + s * 0.92f}, c.scaled(0.55f), c.scaled(0.55f));
                }
            }
            case CAMERA -> {
                gui.rounded(new Rect(x + s * 0.08f, y + s * 0.3f, s * 0.58f, s * 0.44f), s * 0.06f, c);
                gui.triangle(x + s * 0.64f, cy, x + s * 0.92f, y + s * 0.28f, x + s * 0.92f, y + s * 0.76f, c);
            }
            case LIGHT -> {
                gui.circle(cx, cy, s * 0.2f, c);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    gui.line(cx + (float) Math.cos(a) * s * 0.3f, cy + (float) Math.sin(a) * s * 0.3f,
                            cx + (float) Math.cos(a) * s * 0.44f, cy + (float) Math.sin(a) * s * 0.44f, t, c);
                }
            }
            case SPRITE -> {
                gui.rounded(new Rect(x + s * 0.14f, y + s * 0.14f, s * 0.72f, s * 0.72f), s * 0.12f, c);
                gui.circle(x + s * 0.38f, y + s * 0.42f, s * 0.07f, Theme.PANEL);
                gui.circle(x + s * 0.62f, y + s * 0.42f, s * 0.07f, Theme.PANEL);
                gui.rect(x + s * 0.34f, y + s * 0.62f, s * 0.32f, t, Theme.PANEL);
            }
            case CHEVRON_RIGHT -> gui.triangle(x + s * 0.35f, y + s * 0.22f, x + s * 0.72f, cy, x + s * 0.35f, y + s * 0.78f, c);
            case CHEVRON_DOWN -> gui.triangle(x + s * 0.22f, y + s * 0.35f, x + s * 0.78f, y + s * 0.35f, cx, y + s * 0.72f, c);
            case UP -> gui.triangle(x + s * 0.2f, y + s * 0.68f, cx, y + s * 0.28f, x + s * 0.8f, y + s * 0.68f, c);
            case PLUS -> {
                gui.rect(cx - t * 0.6f, y + s * 0.18f, t * 1.2f, s * 0.64f, c);
                gui.rect(x + s * 0.18f, cy - t * 0.6f, s * 0.64f, t * 1.2f, c);
            }
            case CLOSE -> {
                gui.line(x + s * 0.24f, y + s * 0.24f, x + s * 0.76f, y + s * 0.76f, t * 1.2f, c);
                gui.line(x + s * 0.76f, y + s * 0.24f, x + s * 0.24f, y + s * 0.76f, t * 1.2f, c);
            }
            case SEARCH -> {
                ring(gui, x + s * 0.42f, y + s * 0.42f, s * 0.24f, t, c);
                gui.line(x + s * 0.6f, y + s * 0.6f, x + s * 0.86f, y + s * 0.86f, t * 1.4f, c);
            }
            case GEAR -> {
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    gui.line(cx, cy, cx + (float) Math.cos(a) * s * 0.44f, cy + (float) Math.sin(a) * s * 0.44f, s * 0.16f, c);
                }
                gui.circle(cx, cy, s * 0.3f, c);
                gui.circle(cx, cy, s * 0.12f, Theme.PANEL);
            }
            case EYE, EYE_OFF -> {
                ellipse(gui, cx, cy, s * 0.42f, s * 0.24f, t, c);
                gui.circle(cx, cy, s * 0.12f, c);
                if (this == EYE_OFF) {
                    gui.line(x + s * 0.12f, y + s * 0.88f, x + s * 0.88f, y + s * 0.12f, t * 1.2f, c);
                }
            }
            case LINK, GRAPH, SIGNAL -> {
                float r = s * 0.13f;
                gui.circle(x + s * 0.22f, y + s * 0.3f, r, c);
                gui.circle(x + s * 0.78f, y + s * 0.3f, r, c);
                gui.circle(cx, y + s * 0.76f, r, c);
                gui.line(x + s * 0.22f, y + s * 0.3f, cx, y + s * 0.76f, t, c);
                gui.line(x + s * 0.78f, y + s * 0.3f, cx, y + s * 0.76f, t, c);
                if (this == SIGNAL) {
                    gui.line(x + s * 0.22f, y + s * 0.3f, x + s * 0.78f, y + s * 0.3f, t, c);
                }
            }
            case HAMMER -> {
                gui.line(x + s * 0.2f, y + s * 0.85f, x + s * 0.62f, y + s * 0.42f, s * 0.13f, c);
                gui.polygon(new float[]{x + s * 0.38f, y + s * 0.3f, x + s * 0.62f, y + s * 0.06f, x + s * 0.94f,
                        y + s * 0.38f, x + s * 0.7f, y + s * 0.62f}, c, c);
            }
            case RUN -> {
                gui.outline(new Rect(x + s * 0.06f, y + s * 0.14f, s * 0.88f, s * 0.72f), t, c);
                gui.rect(x + s * 0.06f, y + s * 0.14f, s * 0.88f, s * 0.12f, c);
                gui.triangle(x + s * 0.4f, y + s * 0.38f, x + s * 0.66f, y + s * 0.56f, x + s * 0.4f, y + s * 0.74f, c);
            }
            case WARNING -> {
                gui.triangle(cx, y + s * 0.08f, x + s * 0.96f, y + s * 0.9f, x + s * 0.04f, y + s * 0.9f, c);
                gui.rect(cx - t * 0.6f, y + s * 0.36f, t * 1.2f, s * 0.28f, Theme.PANEL);
                gui.rect(cx - t * 0.6f, y + s * 0.7f, t * 1.2f, t * 1.2f, Theme.PANEL);
            }
            case ERROR, INFO -> {
                gui.circle(cx, cy, s * 0.44f, c);
                if (this == ERROR) {
                    gui.line(x + s * 0.32f, y + s * 0.32f, x + s * 0.68f, y + s * 0.68f, t * 1.3f, Theme.PANEL);
                    gui.line(x + s * 0.68f, y + s * 0.32f, x + s * 0.32f, y + s * 0.68f, t * 1.3f, Theme.PANEL);
                } else {
                    gui.rect(cx - t * 0.6f, y + s * 0.42f, t * 1.2f, s * 0.34f, Theme.PANEL);
                    gui.rect(cx - t * 0.6f, y + s * 0.24f, t * 1.2f, t * 1.2f, Theme.PANEL);
                }
            }
            case CHECK -> {
                gui.line(x + s * 0.16f, y + s * 0.52f, x + s * 0.4f, y + s * 0.76f, t * 1.4f, c);
                gui.line(x + s * 0.4f, y + s * 0.76f, x + s * 0.86f, y + s * 0.26f, t * 1.4f, c);
            }
            case DOTS -> {
                for (int i = 0; i < 3; i++) {
                    gui.circle(cx, y + s * (0.22f + i * 0.28f), s * 0.08f, c);
                }
            }
            case SAVE -> {
                gui.outline(new Rect(x + s * 0.14f, y + s * 0.14f, s * 0.72f, s * 0.72f), t, c);
                gui.rect(x + s * 0.3f, y + s * 0.14f, s * 0.4f, s * 0.24f, c);
                gui.rect(x + s * 0.3f, y + s * 0.56f, s * 0.4f, s * 0.3f, c);
            }
            case CIRCLE -> gui.circle(cx, cy, s * 0.3f, c);
            case PATH -> {
                float px = x + s * 0.18f;
                float py = y + s * 0.82f;
                for (int i = 1; i <= 16; i++) {
                    float u = i / 16f;
                    float nx = x + s * (0.18f + 0.64f * u);
                    float ny = y + s * (0.82f - 0.64f * u) + (float) Math.sin(u * Math.PI * 2) * s * 0.14f;
                    gui.line(px, py, nx, ny, s * 0.12f, c);
                    px = nx;
                    py = ny;
                }
                gui.circle(x + s * 0.18f, y + s * 0.82f, s * 0.1f, c);
                gui.circle(x + s * 0.82f, y + s * 0.18f, s * 0.1f, c);
            }
            case TREE -> {
                gui.rect(cx - s * 0.06f, y + s * 0.6f, s * 0.12f, s * 0.32f, c.scaled(0.7f));
                gui.circle(cx, y + s * 0.38f, s * 0.28f, c);
                gui.circle(cx - s * 0.2f, y + s * 0.52f, s * 0.18f, c);
                gui.circle(cx + s * 0.2f, y + s * 0.52f, s * 0.18f, c);
            }
            case SHADER -> {
                gui.circle(cx, cy, s * 0.4f, c.scaled(0.45f));
                gui.circle(cx - s * 0.08f, cy - s * 0.08f, s * 0.3f, c.scaled(0.75f));
                gui.circle(cx - s * 0.16f, cy - s * 0.16f, s * 0.13f, c);
            }
            case TOOLS -> {
                gui.line(x + s * 0.2f, y + s * 0.8f, x + s * 0.65f, y + s * 0.35f, s * 0.14f, c);
                ring(gui, x + s * 0.68f, y + s * 0.32f, s * 0.18f, s * 0.1f, c);
            }
            case TERRAIN -> {
                gui.triangle(x + s * 0.06f, y + s * 0.84f, x + s * 0.4f, y + s * 0.26f, x + s * 0.74f, y + s * 0.84f, c.scaled(0.75f));
                gui.triangle(x + s * 0.36f, y + s * 0.84f, x + s * 0.64f, y + s * 0.42f, x + s * 0.94f, y + s * 0.84f, c);
                gui.rect(x + s * 0.06f, y + s * 0.8f, s * 0.88f, s * 0.08f, c);
            }
            case TILEMAP -> {
                float cell = s * 0.24f;
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 3; col++) {
                        boolean on = (row + col) % 2 == 0 || row == 2;
                        gui.rect(x + s * 0.13f + col * (cell + s * 0.03f), y + s * 0.13f + row * (cell + s * 0.03f), cell, cell,
                                on ? c : c.scaled(0.45f));
                    }
                }
            }
            case GEOMETRY -> {
                // A cube seen from above a corner, its three faces in three shades: what geometry nodes make.
                float top = y + s * 0.12f;
                float mid = y + s * 0.34f;
                float low = y + s * 0.66f;
                float bottom = y + s * 0.88f;
                float left = x + s * 0.12f;
                float right = x + s * 0.88f;
                gui.polygon(new float[]{cx, top, right, mid, cx, cy + s * 0.06f, left, mid}, c, c);
                gui.polygon(new float[]{left, mid, cx, cy + s * 0.06f, cx, bottom, left, low}, c.scaled(0.7f), c.scaled(0.7f));
                gui.polygon(new float[]{cx, cy + s * 0.06f, right, mid, right, low, cx, bottom}, c.scaled(0.5f), c.scaled(0.5f));
            }
            case PENCIL -> {
                gui.line(x + s * 0.25f, y + s * 0.75f, x + s * 0.72f, y + s * 0.28f, s * 0.2f, c);
                gui.triangle(x + s * 0.14f, y + s * 0.86f, x + s * 0.2f, y + s * 0.66f, x + s * 0.34f, y + s * 0.8f, c);
                gui.line(x + s * 0.66f, y + s * 0.22f, x + s * 0.8f, y + s * 0.36f, s * 0.2f, c.scaled(0.6f));
            }
        }
    }

    /** An open curve round a centre, from one angle to another, in short straight pieces. */
    static void arc(Gui gui, float cx, float cy, float radius, float from, float to, float thickness, Color c) {
        int steps = Math.max(6, (int) Math.abs((to - from) * 6f));
        float px = cx + (float) Math.cos(from) * radius;
        float py = cy + (float) Math.sin(from) * radius;
        for (int i = 1; i <= steps; i++) {
            float a = from + (to - from) * i / steps;
            float nx = cx + (float) Math.cos(a) * radius;
            float ny = cy + (float) Math.sin(a) * radius;
            gui.line(px, py, nx, ny, thickness, c);
            px = nx;
            py = ny;
        }
    }

    static void ring(Gui gui, float cx, float cy, float radius, float thickness, Color c) {
        arc(gui, cx, cy, radius, 0f, (float) (Math.PI * 2), thickness, c);
    }

    static void ellipse(Gui gui, float cx, float cy, float rx, float ry, float thickness, Color c) {
        int steps = 24;
        float px = cx + rx;
        float py = cy;
        for (int i = 1; i <= steps; i++) {
            double a = Math.PI * 2 * i / steps;
            float nx = cx + (float) Math.cos(a) * rx;
            float ny = cy + (float) Math.sin(a) * ry;
            gui.line(px, py, nx, ny, thickness, c);
            px = nx;
            py = ny;
        }
    }
}
