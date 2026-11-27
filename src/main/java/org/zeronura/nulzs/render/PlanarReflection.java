package org.zeronura.nulzs.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.FloatBuffer;

/**
 * A mirrored render of the scene, for water to reflect.
 *
 * <p>The scene is drawn a second time from a camera reflected through a horizontal plane, into a target of
 * its own, and the water samples that target projectively. It is the only way to get trees and hills into
 * a reflection in this renderer: there is no cubemap, and screen-space is not available — at the fluid pass
 * the HDR scene buffer is the bound draw target — so its own depth and colour cannot be sampled without a
 * feedback loop — and the sky has not even been drawn yet, which is most of what a reflection is made of.
 *
 * <h2>A volumetric fluid has no single water plane, so the plane is chosen every frame</h2>
 * This is the awkward fact the whole class is shaped around. A planar reflection assumes one flat mirror,
 * and this world has a pond in a hollow, a river down a mountainside and a waterfall off a cliff, all at
 * different heights and often all on screen. One plane can only be right for one of them.
 *
 * <p>So {@link #choosePlane} picks the water the player is actually looking at — the height that the most
 * surface, weighted toward the camera, actually sits at — and everything not close to that height falls
 * back to the sky reflection in {@code fluid.frag}. A waterfall reflecting its own pool's plane would be
 * worse than a waterfall reflecting the sky, so the fallback is not a degraded case but the right answer
 * for water the plane does not describe.
 *
 * <p>The plane is read out of the <b>surface mesh that is about to be drawn</b> rather than from the fluid
 * simulation. That mesh is rebuilt every frame and already holds one vertex per surface corner, so it is
 * exactly the geometry the reflection has to serve, it costs a walk over a buffer already in memory, and it
 * keeps this class free of any dependency on the sim.
 */
public class PlanarReflection {

    /**
     * Fraction of the window this is rendered at.
     *
     * <p>Half in each axis, so a quarter of the pixels. A reflection is seen through a rippled surface that
     * is displacing the lookup anyway, so the detail a full-resolution pass would buy is detail the ripple
     * immediately throws away — this is the cheapest place in the whole feature to take a saving.
     */
    public static final float RESOLUTION_SCALE = 0.5f;

    /**
     * How far from the chosen plane a surface may sit and still use the mirrored render.
     *
     * <p>Beyond it the reflection would be visibly wrong — the mirror is at the wrong height, so everything
     * in it sits at the wrong distance — and the sky is the better answer. Kept tight: half a metre is
     * about the roughness of one settled body's surface, so a single pond stays wholly on the plane while a
     * stream a metre lower does not.
     */
    public static final float PLANE_TOLERANCE = 0.5f;

    /** Below this many surface vertices there is not enough water on screen to be worth a second pass. */
    private static final int MIN_SURFACE_VERTICES = 24;

    /** Vertices in the fluid mesh, and the offsets this reads. Mirrors {@code FluidSim}'s writer. */
    private static final int FLOATS_PER_VERTEX = 10;
    private static final int NORMAL_Y = 4;

    /**
     * How the plane vote is bucketed, in world units.
     *
     * <p>Coarser than {@link #PLANE_TOLERANCE} would merge two nearby bodies into one wrong plane; much
     * finer and a single body's own surface roughness splits its vote across several buckets and no height
     * wins clearly. A quarter of a metre is comfortably inside the tolerance and coarser than the ripple.
     */
    private static final float VOTE_BUCKET = 0.25f;

    /**
     * Beyond this the weight of a surface toward the vote has fallen to a quarter.
     *
     * <p>The point of weighting at all is that a reflection is judged on the water you are standing beside,
     * not on a lake on the horizon that occupies more of the mesh.
     */
    private static final float VOTE_FALLOFF = 40f;

    private final Framebuffer target;
    private final Matrix4f viewProjection = new Matrix4f();
    private float planeY = Float.NaN;

    public PlanarReflection(int width, int height) {
        target = new Framebuffer(scaled(width), scaled(height));
    }

    private static int scaled(int size) {
        return Math.max(1, Math.round(size * RESOLUTION_SCALE));
    }

    public void resize(int width, int height) {
        target.resize(scaled(width), scaled(height));
    }

    /**
     * The water height worth mirroring, or {@link Float#NaN} if there is none.
     *
     * <p>A vote over the surface mesh's <b>upward-facing</b> vertices only: a waterfall's curtain and a
     * pond's side walls are vertical, and a vertical face has no business choosing a horizontal mirror.
     * Each vote is weighted by how close its water is to the camera, so the body being looked at wins over
     * a larger one further off.
     *
     * <p>Static and free of GL so the choice can be unit-tested against a hand-built buffer, which is the
     * only way to state what it should do when two ponds are in frame.
     */
    public static float choosePlane(FloatBuffer mesh, int vertexCount, Vector3f viewpoint) {
        if (vertexCount < MIN_SURFACE_VERTICES) {
            return Float.NaN;
        }

        // Buckets by height. A map would be tidier; this is walked over tens of thousands of vertices every
        // frame, so it stays an open-addressed scan over a small array of live buckets instead.
        float[] heights = new float[64];
        float[] weights = new float[64];
        int used = 0;

        for (int v = 0; v < vertexCount; v++) {
            int base = v * FLOATS_PER_VERTEX;
            if (mesh.get(base + NORMAL_Y) <= 0.5f) {
                continue;   // a side face; it cannot vote on a horizontal plane
            }
            float x = mesh.get(base);
            float y = mesh.get(base + 1);
            float z = mesh.get(base + 2);

            float dx = x - viewpoint.x;
            float dz = z - viewpoint.z;
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            float weight = VOTE_FALLOFF / (VOTE_FALLOFF + distance);

            float bucket = Math.round(y / VOTE_BUCKET) * VOTE_BUCKET;
            int slot = -1;
            for (int i = 0; i < used; i++) {
                if (Math.abs(heights[i] - bucket) < VOTE_BUCKET * 0.5f) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                if (used == heights.length) {
                    continue;   // more distinct water levels than anyone can look at; the leaders are set
                }
                slot = used++;
                heights[slot] = bucket;
            }
            weights[slot] += weight;
        }

        int best = -1;
        for (int i = 0; i < used; i++) {
            if (best < 0 || weights[i] > weights[best]) {
                best = i;
            }
        }
        return best < 0 ? Float.NaN : heights[best];
    }

    /**
     * Binds the reflection target and returns the camera the scene must be drawn from.
     *
     * <p>The mirrored camera is the original reflected through {@code y = planeY}: position and target both
     * flipped about the plane. Reflecting a view matrix flips handedness, so what was front-facing is now
     * back-facing — the caller has to draw this pass with the winding it would otherwise cull, or every
     * solid in the reflection turns inside out. {@code Renderer}'s passes set their own culling, so the
     * practical answer is to let them and accept that the reflection shows back faces of closed geometry;
     * for terrain and trees seen from above a mirror plane that is not visible.
     *
     * @return the mirrored camera, or null when there is no plane worth reflecting — in which case nothing
     *         was bound and the caller must skip the pass entirely
     */
    public Camera begin(Camera camera, float planeY) {
        this.planeY = planeY;
        if (Float.isNaN(planeY)) {
            return null;
        }

        Vector3f position = new Vector3f(camera.getPosition());
        Vector3f lookAt = new Vector3f(camera.getTarget());
        position.y = 2f * planeY - position.y;
        lookAt.y = 2f * planeY - lookAt.y;

        Camera mirrored = new Camera(position, lookAt, camera.getAspectRatio());
        mirrored.setFovDegrees(camera.getFovDegrees());
        mirrored.setFarPlane(camera.getFarPlane());

        viewProjection.set(mirrored.getProjectionMatrix()).mul(mirrored.getViewMatrix());

        target.bind();
        return mirrored;
    }

    /** Restores the previous target. The caller re-binds its own; this only restores the viewport size. */
    public void end(int windowWidth, int windowHeight) {
        org.lwjgl.opengl.GL33.glBindFramebuffer(org.lwjgl.opengl.GL33.GL_FRAMEBUFFER, 0);
        org.lwjgl.opengl.GL33.glViewport(0, 0, windowWidth, windowHeight);
    }

    /** Clip-space transform of the mirrored camera, for the water to project world positions through. */
    public Matrix4f viewProjection() {
        return viewProjection;
    }

    public int texture() {
        return target.colorTexture();
    }

    public float planeY() {
        return planeY;
    }

    public void delete() {
        target.delete();
    }
}
