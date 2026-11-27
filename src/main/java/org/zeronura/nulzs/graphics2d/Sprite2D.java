package org.zeronura.nulzs.graphics2d;

import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.scene.Asset;
import org.zeronura.nulzs.scene.Scene;
import org.zeronura.nulzs.scene.Tooltip;

/**
 * A picture in a 2D scene, named by its asset path so the editor can set it by dropping an image from the Project
 * panel. With no image it is a rectangle of its colour.
 */
public class Sprite2D extends SpriteRenderer {

    @Asset({"png", "jpg", "jpeg", "bmp", "tga"})
    public String image = "";
    @Tooltip("Sample the image's pixels sharply, for pixel art, rather than smoothly")
    public boolean pixelArt;
    @Tooltip("Keep Width and Height at the image's own size in pixels")
    public boolean nativeSize;
    private int loadedRevision;

    private String loaded = "";
    private boolean loadedPixelArt;

    public Sprite2D() {
        super(Color.WHITE, 100f, 100f);
    }

    public Sprite2D(String image, float width, float height) {
        super(Color.WHITE, width, height);
        this.image = image;
    }

    @Override
    protected void onEnterScene(Scene scene) {
        reload();
    }

    @Override
    protected void preRender(float alpha) {
        reload();
    }

    /** Loads the image again if it changed. One that won't load leaves the plain rectangle and says why once. */
    public void reload() {
        String wanted = image == null ? "" : image.trim();
        int revision = scene() == null || scene().engine() == null ? 0 : scene().assets().revision(wanted);
        if (!wanted.equals(loaded) || pixelArt != loadedPixelArt || revision != loadedRevision) {
            loaded = wanted;
            loadedPixelArt = pixelArt;
            loadedRevision = revision;
            region = null;
            if (!wanted.isEmpty() && scene() != null && scene().engine() != null) {
                try {
                    Texture texture = pixelArt ? scene().assets().pixelTexture(wanted) : scene().assets().texture(wanted);
                    region = TextureRegion.of(texture);
                } catch (RuntimeException e) {
                    System.err.println("[Sprite2D] Could not load " + wanted + ": " + e.getMessage());
                }
            }
        }
        if (nativeSize && region != null) {
            width = region.width();
            height = region.height();
        }
    }
}
