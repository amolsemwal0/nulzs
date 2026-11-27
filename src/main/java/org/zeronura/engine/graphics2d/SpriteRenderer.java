package org.zeronura.engine.graphics2d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.render.Texture;
import org.zeronura.engine.scene.Component;

/**
 * Draws an image (or a solid rectangle) at its entity's transform.
 *
 * <p>{@link #width} and {@link #height} are in world units and are then multiplied by the transform's
 * scale, so the size of the art and any animated squash are independent. The pivot is the point the
 * transform position refers to and the sprite rotates about: (0.5, 0.5) is the centre, (0.5, 0) the feet.
 */
public class SpriteRenderer extends Component implements Renderable2D {

    /** The image, or null for a solid rectangle of {@link #color}. */
    public TextureRegion region;
    /** Multiplied into the image; white leaves it unchanged. */
    public Color color = Color.WHITE;
    public float width;
    public float height;
    public float pivotX = 0.5f;
    public float pivotY = 0.5f;
    public boolean flipX;
    public boolean flipY;
    public int layer;

    private final Matrix4f world = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Vector3f local = new Vector3f();

    public SpriteRenderer(TextureRegion region, float width, float height) {
        this.region = region;
        this.width = width;
        this.height = height;
    }

    /** A solid-colour rectangle. */
    public SpriteRenderer(Color color, float width, float height) {
        this((TextureRegion) null, width, height);
        this.color = color;
    }

    public static SpriteRenderer of(Texture texture, float width, float height) {
        return new SpriteRenderer(TextureRegion.of(texture), width, height);
    }

    public SpriteRenderer setLayer(int newLayer) {
        this.layer = newLayer;
        return this;
    }

    public SpriteRenderer setPivot(float x, float y) {
        this.pivotX = x;
        this.pivotY = y;
        return this;
    }

    @Override
    public int layer() {
        return layer;
    }

    @Override
    public void draw(SpriteBatch batch, Camera2D camera) {
        transform().worldMatrix(world);
        batch.draw(region, world, -pivotX * width, -pivotY * height, width, height, flipX, flipY, color);
    }

    /** Whether a world point lies on this sprite, honouring rotation, scale and parents. */
    public boolean contains(float worldX, float worldY) {
        transform().worldMatrix(world);
        // A sprite scaled to zero on an axis (mid-flip, say) has no area and cannot be hit.
        if (Math.abs(world.m00() * world.m11() - world.m01() * world.m10()) < 1e-12f) {
            return false;
        }
        world.invertAffine(inverse).transformPosition(worldX, worldY, 0f, local);
        return local.x >= -pivotX * width && local.x <= (1f - pivotX) * width
                && local.y >= -pivotY * height && local.y <= (1f - pivotY) * height;
    }
}
