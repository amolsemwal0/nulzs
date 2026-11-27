package org.zeronura.nulzs.editor;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.zeronura.nulzs.graphics3d.Lighting;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.Renderer;
import org.zeronura.nulzs.render.SceneLighting;
import org.zeronura.nulzs.render.StaticInstance;
import org.zeronura.nulzs.render.TexturedInstance;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.worldmap.PrefabCatalog;
import org.zeronura.nulzs.worldmap.PrefabLook;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL33.*;

/**
 * Pictures of prefabs for the asset drawer, rendered in three-quarter view and lit like the world: a prefab with
 * textured {@linkplain PrefabLook looks} in its first look, as the game draws it, and any other from its own mesh. A picture is asked for the first time its card is drawn and rendered off screen a few a frame by
 * {@link #bakePending}, which runs outside the frame's drawing, so opening a drawer of a hundred props does not stall.
 */
public final class PrefabThumbnails {

    public static final int SIZE = 128;
    private static final int PER_FRAME = 6;

    private final Map<String, Texture> textures = new HashMap<>();
    private final Set<String> wanted = new LinkedHashSet<>();
    private final SceneLighting lighting = Lighting.withFog(Lighting.daylight(), 5000f, 10000f).withoutShadows();
    private final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
    private int framebuffer;
    private int colour;
    private int depth;

    /** The picture of a prefab, or null until it has been rendered. */
    public Texture get(PrefabCatalog.Prefab prefab) {
        Texture texture = textures.get(prefab.id());
        if (texture == null) {
            wanted.add(prefab.id());
        }
        return texture;
    }

    /** Renders a few of the pictures asked for since the last call. Needs the GL context, outside a frame's drawing. */
    public void bakePending(Renderer renderer, PrefabCatalog catalog) {
        if (wanted.isEmpty()) {
            return;
        }
        ensureTarget();
        int previousFramebuffer = glGetInteger(GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        float[] clear = new float[4];
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, SIZE, SIZE);
        try {
            Iterator<String> ids = wanted.iterator();
            int rendered = 0;
            while (ids.hasNext() && rendered < PER_FRAME) {
                PrefabCatalog.Prefab prefab = catalog.get(ids.next());
                ids.remove();
                if (prefab != null && prefab.mesh() != null) {
                    textures.put(prefab.id(), render(renderer, prefab));
                    rendered++;
                }
            }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glClearColor(clear[0], clear[1], clear[2], clear[3]);
        }
    }

    private Texture render(Renderer renderer, PrefabCatalog.Prefab prefab) {
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Frame the prefab's bounding sphere, looking a little down on it from the front right.
        float height = Math.max(0.2f, prefab.height());
        float radius = Math.max(0.2f, prefab.radius());
        float sphere = (float) Math.sqrt(radius * radius + height * height * 0.25f);
        Vector3f target = new Vector3f(0f, height * 0.5f, 0f);
        float distance = sphere / (float) Math.sin(Math.toRadians(Camera.DEFAULT_FOV_DEGREES * 0.5)) * 1.02f;
        Vector3f eye = new Vector3f(0.75f, 0.5f, 1f).normalize(distance).add(target);
        Camera camera = new Camera(eye, target, 1f);
        camera.setFarPlane(distance * 4f);
        PrefabLook look = prefab.looks().isEmpty() ? null : prefab.looks().getFirst();
        if (look == null) {
            renderer.renderProps(camera, lighting, List.of(new StaticInstance(prefab.mesh(), new Matrix4f())));
        } else {
            if (look.plain() != null) {
                renderer.renderProps(camera, lighting, List.of(new StaticInstance(look.plain(), new Matrix4f())));
            }
            for (PrefabLook.Piece piece : look.pieces()) {
                renderer.renderFoliage(camera, lighting, List.of(new TexturedInstance(piece.mesh(), new Matrix4f())),
                        piece.material());
            }
            // Its doors too, shut.
            for (PrefabLook.Swing swing : look.swings()) {
                if (swing.plain() != null) {
                    renderer.renderProps(camera, lighting, List.of(new StaticInstance(swing.plain(), new Matrix4f())));
                }
                for (PrefabLook.Piece piece : swing.pieces()) {
                    renderer.renderFoliage(camera, lighting, List.of(new TexturedInstance(piece.mesh(), new Matrix4f())),
                            piece.material());
                }
            }
        }

        pixels.clear();
        glReadPixels(0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        // GL reads the bottom row first; textures are stored top row first.
        ByteBuffer flipped = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        for (int row = SIZE - 1; row >= 0; row--) {
            for (int k = 0; k < SIZE * 4; k++) {
                flipped.put(pixels.get(row * SIZE * 4 + k));
            }
        }
        flipped.flip();
        return Texture.fromPixels(flipped, SIZE, SIZE);
    }

    private void ensureTarget() {
        if (framebuffer != 0) {
            return;
        }
        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        colour = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colour);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colour, 0);
        depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, SIZE, SIZE);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("thumbnail framebuffer incomplete");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void delete() {
        textures.values().forEach(Texture::delete);
        textures.clear();
        wanted.clear();
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            glDeleteTextures(colour);
            glDeleteRenderbuffers(depth);
            framebuffer = 0;
        }
    }
}
