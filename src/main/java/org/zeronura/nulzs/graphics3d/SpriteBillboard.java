package org.zeronura.nulzs.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.nulzs.render.Camera;
import org.zeronura.nulzs.render.FoliageMaterial;
import org.zeronura.nulzs.render.TexturedInstance;
import org.zeronura.nulzs.render.TexturedMesh;
import org.zeronura.nulzs.render.TexturedMeshBuilder;
import org.zeronura.nulzs.render.Texture;
import org.zeronura.nulzs.scene.Component;
import org.zeronura.nulzs.scene.Scene;

/**
 * A frame of a sprite sheet stood up in the 3D world and turned to face the camera: a character drawn as pixel art
 * among 3D buildings, the way a 2.5D game draws its people.
 *
 * <p>The sheet is a grid of equal frames, and {@link #frame} picks one, counted along the rows from the top left. The
 * entity's position is where the frame's pivot pixel stands — usually between the feet. The entity's own rotation is
 * ignored, since a sprite always faces the camera, so a character can turn (and turn whatever rides with it) without
 * its picture turning edge-on. Swap {@link #sheet} to change animations; every sheet given must be cut the same way.
 *
 * <h2>Upright, and taller to make up for it</h2>
 * The picture stands straight up rather than leaning back toward a camera that looks down on it, so it never cuts into
 * a wall or a tree just behind the character. Seen from above, an upright picture is foreshortened; with
 * {@link #keepScreenProportions} it is stretched upward by just as much, and the art comes out on screen in its own
 * proportions.
 *
 * <h2>Lit like the ground</h2>
 * Its normals point straight up. A flat picture lit by the way it faces goes dark whenever the sun is behind it; lit as
 * the ground under it is, it dims at dusk and in a building's shadow along with everything else. It casts no shadow
 * of its own: the shadow map would record the whole rectangle.
 */
public class SpriteBillboard extends Component {

    /** Below this alpha a texel is left out. Pixel art is all or nothing, so anywhere in between does. */
    private static final float ALPHA_CUTOFF = 0.5f;
    /** The most the picture is stretched, for a camera looking almost straight down. */
    private static final float MAX_STRETCH = 3f;

    /** The sheet drawn from: nearest-neighbour sampled for pixel art's crisp pixels, smoothly for painted art. */
    public Texture sheet;
    /** Which frame, counted along the rows from the top left. */
    public int frame;
    /** Stretch the picture upward by however much a camera looking down foreshortens it. */
    public boolean keepScreenProportions = true;
    /**
     * Moves the picture off its pivot, in the art's pixels, rightward and upward as seen on screen: lines up a frame
     * drawn off-centre in its cell with the frames around it.
     */
    public float offsetX;
    public float offsetY;
    /** Draws the picture this much larger, about its pivot: for something that grows into view or shrinks away. */
    public float scale = 1f;
    /** Draws the picture flipped left to right about its pivot: art drawn facing one way, shown facing the other. */
    public boolean mirrored;
    /**
     * How far behind its position the picture is drawn, away from the camera, in world units: someone standing at the
     * same spot — a rider on what they ride — is then drawn in front of it rather than through it.
     */
    public float behind;
    /**
     * Drawn see-through, tinted {@link #throughTint}, wherever something stands between it and the camera — a building
     * it has walked behind — so the player is never lost from sight.
     */
    public boolean showsThrough;
    /** How solid it is drawn where it shows through, from 0 to 1. */
    public float throughOpacity = 0.5f;
    /** What it is tinted where it shows through: a cool ghost of itself. */
    public final Vector3f throughTint = new Vector3f(0.75f, 0.9f, 1.25f);

    private final int columns;
    private final int rows;
    private final float pixelsPerUnit;
    private final float pivotX;
    private final float pivotY;
    private final Matrix4f matrix = new Matrix4f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f where = new Vector3f();
    private TexturedMesh[] meshes;
    private Texture materialSheet;
    private FoliageMaterial material;

    /**
     * @param sheet         the first sheet to draw from
     * @param columns       frames across the sheet
     * @param rows          frames down it
     * @param pixelsPerUnit how many of the art's pixels make a world unit
     * @param pivotX        the pixel, from a frame's left edge, that stands at the entity's position
     * @param pivotY        the same from the frame's top edge: the feet, for a character
     */
    public SpriteBillboard(Texture sheet, int columns, int rows, float pixelsPerUnit, float pivotX, float pivotY) {
        if (columns < 1 || rows < 1 || pixelsPerUnit <= 0f) {
            throw new IllegalArgumentException("bad sprite sheet " + columns + "x" + rows + " @" + pixelsPerUnit);
        }
        this.sheet = sheet;
        this.columns = columns;
        this.rows = rows;
        this.pixelsPerUnit = pixelsPerUnit;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
    }

    public int frameCount() {
        return columns * rows;
    }

    @Override
    protected void onExitScene(Scene scene) {
        if (meshes != null) {
            for (TexturedMesh mesh : meshes) {
                if (mesh != null) {
                    mesh.delete();
                }
            }
            meshes = null;
        }
    }

    /** The material the current sheet draws with, or null when there is no sheet. */
    FoliageMaterial material() {
        if (sheet == null) {
            return null;
        }
        if (sheet != materialSheet) {
            materialSheet = sheet;
            // One-sided: the picture always faces the camera, and a two-sided pass is a leaf card's, swaying in the wind.
            material = new FoliageMaterial(sheet, null, false, ALPHA_CUTOFF, false, false);
        }
        return material;
    }

    /** The current frame, stood at the entity and turned to face the camera. */
    TexturedInstance instance(Camera camera) {
        int index = Math.floorMod(frame, frameCount());
        if (meshes == null) {
            // Each frame as drawn, then each mirrored.
            meshes = new TexturedMesh[frameCount() * 2];
        }
        int mesh = mirrored ? index + frameCount() : index;
        if (meshes[mesh] == null) {
            meshes[mesh] = buildFrame(index, mirrored);
        }
        camera.getForward(forward);
        float level = (float) Math.sqrt(forward.x * forward.x + forward.z * forward.z);
        // Turned so the picture's front (+z) points back along the camera's level heading.
        float yaw = level > 1e-4f ? (float) Math.atan2(-forward.x, -forward.z) : 0f;
        float stretch = keepScreenProportions ? Math.min(MAX_STRETCH, 1f / Math.max(level, 1f / MAX_STRETCH)) : 1f;
        transform().worldPosition(where);
        if (behind != 0f && level > 1e-4f) {
            where.add(forward.x / level * behind, 0f, forward.z / level * behind);
        }
        matrix.translation(where).rotateY(yaw).scale(scale, stretch * scale, scale)
                .translate((mirrored ? -offsetX : offsetX) / pixelsPerUnit, offsetY / pixelsPerUnit, 0f);
        return new TexturedInstance(meshes[mesh], new Matrix4f(matrix));
    }

    private TexturedMesh buildFrame(int index, boolean mirror) {
        int column = index % columns;
        int row = index / columns;
        float frameWidth = sheet.getWidth() / (float) columns;
        float frameHeight = sheet.getHeight() / (float) rows;
        // Mirrored, the pivot stays put and the picture swaps sides about it: its texture runs right to left.
        float left = -(mirror ? frameWidth - pivotX : pivotX) / pixelsPerUnit;
        float right = (mirror ? pivotX : frameWidth - pivotX) / pixelsPerUnit;
        float top = pivotY / pixelsPerUnit;
        float bottom = -(frameHeight - pivotY) / pixelsPerUnit;
        float u0 = (mirror ? column + 1 : column) / (float) columns;
        float u1 = (mirror ? column : column + 1) / (float) columns;
        float v0 = row / (float) rows;
        float v1 = (row + 1) / (float) rows;
        Vector3f up = new Vector3f(0f, 1f, 0f);
        Vector3f white = new Vector3f(1f, 1f, 1f);
        Vector3f tangent = new Vector3f(1f, 0f, 0f);
        TexturedMeshBuilder builder = new TexturedMeshBuilder();
        // Image rows run downward, so the top of the frame (v0) is the top of the quad.
        int a = builder.addVertex(new Vector3f(left, bottom, 0f), up, u0, v1, white, tangent);
        int b = builder.addVertex(new Vector3f(right, bottom, 0f), up, u1, v1, white, tangent);
        int c = builder.addVertex(new Vector3f(right, top, 0f), up, u1, v0, white, tangent);
        int d = builder.addVertex(new Vector3f(left, top, 0f), up, u0, v0, white, tangent);
        // Counter-clockwise seen from +z, the side that faces the camera.
        builder.addTriangle(a, b, c);
        builder.addTriangle(a, c, d);
        return builder.build();
    }
}
