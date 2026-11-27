package org.zeronura.nulzs.render;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the clouds of a {@link SkyDome} are: pictures on a dome round the camera, each at a direction (how far round,
 * how high) and an angular size, so a cloud stands where it is put in the sky however the camera moves, from heaps on
 * the horizon to small clouds straight overhead.
 *
 * <p>The wind turns them slowly round the sky, each at its own {@link Cloud#drift}, so higher and lower clouds pass
 * one another. A cloud shows once the sky is cloudy enough ({@link Look#coverage} past its {@link Cloud#threshold}).
 * They are drawn in the order given, so the caller lists the far ones (the horizon) first.
 *
 * <h2>Right overhead</h2>
 * Clouds straight up are seen from underneath, not from the side, so a picture standing on the dome looks wrong
 * there. The field can also hold a {@link Layer}: pictures of clouds as seen from below, lying flat on a plane one unit
 * over the camera. Near the top of the sky they spread out wide, and toward the horizon they foreshorten into the
 * distance, as a real layer of cloud does. The wind carries them across the layer in a straight line. The layer wraps
 * round a square {@value #LAYER_REACH} units either way, and each cloud fades out below
 * {@link #LAYER_FADE_HIGH_DEGREES} degrees up, so it is gone long before it wraps.
 *
 * <p>No GL here: it only says where each cloud is drawn and how faded, and the dome draws them.
 */
public final class CloudField {

    /**
     * A picture in the atlas, as texture coordinates, its width over its height, and where its base is: the line its
     * bottom row of puffs hangs from, as a share of its height from the top.
     */
    public record Picture(float u0, float v0, float u1, float v1, float aspect, float base) {
    }

    /**
     * A cloud on the dome.
     *
     * @param azimuth   how far round the sky it starts, in radians, from +x toward +z
     * @param elevation how high its base is over the horizon, in radians; a little under 0 stands a heap on the horizon
     * @param width     how wide it is, as an angle in radians
     * @param picture   which of the pictures it is
     * @param threshold how much of the sky must be cloud ({@link Look#coverage}) before it shows, 0 to 1
     * @param drift     how fast the wind turns it round the sky, relative to the wind
     * @param flip      drawn mirrored, so one picture serves as two clouds
     */
    public record Cloud(float azimuth, float elevation, float width, int picture, float threshold, float drift,
                        boolean flip) {
    }

    /**
     * A cloud on the layer right overhead, lying flat and seen from underneath.
     *
     * @param x         where its middle is on the layer, east-west, in units of the layer's height over the camera
     * @param z         the same, north-south
     * @param width     how wide its picture is, in the same units
     * @param picture   which of the layer's pictures it is
     * @param threshold how much of the sky must be cloud ({@link Look#coverage}) before it shows, 0 to 1
     * @param turn      how it is turned on the layer, in radians
     * @param flip      drawn mirrored
     */
    public record Overhead(float x, float z, float width, int picture, float threshold, float turn, boolean flip) {
    }

    /**
     * The layer of cloud right overhead: its pictures (as seen from below; their {@link Picture#base} is unused), its
     * clouds, which way the wind carries them across it (radians, from +x toward +z) and how fast, relative to the
     * wind: at a drift of 1, a cloud straight overhead crosses the sky as fast as a dome cloud of drift 1 turns.
     */
    public record Layer(List<Picture> pictures, List<Overhead> clouds, float heading, float drift) {

        public static Layer none() {
            return new Layer(List.of(), List.of(), 0f, 0f);
        }
    }

    /**
     * Where one overhead cloud is drawn: its picture's corners on the layer (bottom left, bottom right, top right, top
     * left, as its picture is painted), and how opaque each corner is, faded toward the horizon.
     */
    public record Laid(Overhead cloud, Vector3f[] corners, float[] alpha) {
    }

    /**
     * How the clouds look this frame.
     *
     * @param litTint   multiplies the sunlit parts of the pictures: white at noon, warm at sunset, dim at night
     * @param shadeTint multiplies their shaded parts
     * @param coverage  how much of the sky is cloud, 0 to 1: a cloud shows once this passes its threshold
     * @param opacity   all the clouds' opacity, for fog that hides them
     * @param haze      how much the clouds low over the horizon fade into its colour, 0 to 1
     * @param wind      how fast the wind turns the sky, in radians a second at a drift of 1
     * @param sun       how bright the sun's disc and glow are, 0 (night, or hidden by overcast) to 1
     */
    public record Look(Vector3f litTint, Vector3f shadeTint, float coverage, float opacity, float haze, float wind,
                       float sun) {

        /** Noon on a clear day: the pictures as painted, half the sky's clouds out, no wind, the sun out. */
        public static Look plain() {
            return new Look(new Vector3f(1f), new Vector3f(1f), 0.5f, 1f, 0.6f, 0f, 1f);
        }
    }

    /**
     * Where one cloud is drawn: the direction of its picture's middle, the way across it (to the right as seen) and up
     * it, both at right angles to that direction, the tangents of half its width and height, and its opacity.
     */
    public record Placed(Cloud cloud, Vector3f middle, Vector3f right, Vector3f up, float halfWidth, float halfHeight,
                         float alpha) {
    }

    /**
     * The wind's turn wraps after this many whole turns of the sky. A cloud whose drift is a whole number of eighths
     * (1/8, 1/2, 1, 1.25...) lands back where it was when it wraps; anything else would jump.
     */
    private static final float LAP = 8f;
    /** The highest a picture's top may reach: short of straight up, where its way across would have no meaning. */
    public static final float HIGHEST = (float) Math.toRadians(88.0);
    /** The layer overhead wraps round a square this far either way of straight up, in units of its height. */
    public static final float LAYER_REACH = 4f;
    /** Between these heights over the horizon, in degrees, a cloud on the layer fades out toward the horizon. */
    public static final float LAYER_FADE_LOW_DEGREES = 22f;
    public static final float LAYER_FADE_HIGH_DEGREES = 38f;

    private final List<Picture> pictures;
    private final List<Cloud> clouds;
    private final Layer layer;
    private final List<Placed> placed = new ArrayList<>();
    private final List<Laid> laid = new ArrayList<>();
    private double windTurn;
    /** How far the wind has carried the layer, each way, wrapped round its square. */
    private double layerX;
    private double layerZ;
    private Look look = Look.plain();

    public CloudField(List<Picture> pictures, List<Cloud> clouds) {
        this(pictures, clouds, Layer.none());
    }

    public CloudField(List<Picture> pictures, List<Cloud> clouds, Layer layer) {
        for (Overhead cloud : layer.clouds()) {
            if (cloud.picture() < 0 || cloud.picture() >= layer.pictures().size()) {
                throw new IllegalArgumentException("no overhead picture " + cloud.picture() + " of "
                        + layer.pictures().size());
            }
            if (Math.abs(cloud.x()) > LAYER_REACH || Math.abs(cloud.z()) > LAYER_REACH || cloud.width() <= 0f) {
                throw new IllegalArgumentException("an overhead cloud lies off its layer, or has no size: " + cloud);
            }
        }
        this.layer = new Layer(List.copyOf(layer.pictures()), List.copyOf(layer.clouds()), layer.heading(),
                layer.drift());
        for (Cloud cloud : clouds) {
            if (cloud.picture() < 0 || cloud.picture() >= pictures.size()) {
                throw new IllegalArgumentException("no picture " + cloud.picture() + " of " + pictures.size());
            }
            if (!steadyDrift(cloud.drift())) {
                throw new IllegalArgumentException("a drift of " + cloud.drift() + " would jump when the wind wraps:"
                        + " use a whole number of eighths");
            }
            Picture picture = pictures.get(cloud.picture());
            float top = cloud.elevation() + picture.base() * cloud.width() / picture.aspect();
            if (top > HIGHEST) {
                throw new IllegalArgumentException("a cloud's top reaches " + Math.toDegrees(top)
                        + " degrees up, past " + Math.toDegrees(HIGHEST));
            }
        }
        this.pictures = List.copyOf(pictures);
        this.clouds = List.copyOf(clouds);
    }

    public List<Picture> pictures() {
        return pictures;
    }

    public List<Cloud> clouds() {
        return clouds;
    }

    public Layer layer() {
        return layer;
    }

    /** How the clouds look from now on: set every frame, from the light and the weather. */
    public void setLook(Look look) {
        this.look = look;
    }

    public Look look() {
        return look;
    }

    /** Lets the wind turn the clouds on. Fed the paused-aware delta, so they hold still with the world. */
    public void update(float dt) {
        turn(look.wind() * dt);
    }

    /** Turns the sky on by an angle at once, as that much wind would: for starting the clouds somewhere else. */
    public void turn(float radians) {
        windTurn += radians;
        // Kept small, so a long session keeps its precision; whole eighths of drift make the wrap move no one.
        double span = 2.0 * Math.PI * LAP;
        windTurn -= span * Math.floor(windTurn / span);
        // The layer is carried straight on, each way wrapped round its square, which moves no cloud.
        double carried = (double) radians * layer.drift();
        layerX = wrap(layerX + carried * Math.cos(layer.heading()));
        layerZ = wrap(layerZ + carried * Math.sin(layer.heading()));
    }

    /** A place on the layer, either way, brought back into its square. */
    static double wrap(double v) {
        double side = 2.0 * LAYER_REACH;
        return v - side * Math.floor((v + LAYER_REACH) / side);
    }

    /** The drifts a cloud may have, so the wind's turn can wrap without moving it ({@link #LAP}). */
    public static boolean steadyDrift(float drift) {
        float laps = LAP * drift;
        return drift >= 0f && Math.abs(laps - Math.round(laps)) < 1e-4f;
    }

    /** Where every showing cloud is, in the order given. The list is reused. */
    public List<Placed> place() {
        placed.clear();
        for (Cloud cloud : clouds) {
            // Shows once the sky clouds over past its threshold, fading in over a tenth of it.
            float alpha = smoothstep(cloud.threshold(), cloud.threshold() + 0.1f, look.coverage()) * look.opacity();
            if (alpha <= 0.002f) {
                continue;
            }
            Picture picture = pictures.get(cloud.picture());
            float height = cloud.width() / picture.aspect();
            // The middle of the picture, from its base: the base is picture.base() of the way down it.
            float middle = cloud.elevation() + (picture.base() - 0.5f) * height;
            float azimuth = (float) ((cloud.azimuth() + windTurn * cloud.drift()) % (2.0 * Math.PI));
            float cosA = (float) Math.cos(azimuth);
            float sinA = (float) Math.sin(azimuth);
            float cosE = (float) Math.cos(middle);
            float sinE = (float) Math.sin(middle);
            Vector3f toward = new Vector3f(cosE * cosA, sinE, cosE * sinA);
            // Across: along the circle round the sky; up: toward straight overhead, both on the dome there.
            Vector3f right = new Vector3f(-sinA, 0f, cosA);
            Vector3f up = new Vector3f(right).cross(toward).normalize();
            placed.add(new Placed(cloud, toward, right, up, (float) Math.tan(cloud.width() * 0.5f),
                    (float) Math.tan(height * 0.5f), alpha));
        }
        return placed;
    }

    /** Where every showing cloud on the layer overhead is, in its order. The list is reused. */
    public List<Laid> lay() {
        laid.clear();
        float low = (float) Math.toRadians(LAYER_FADE_LOW_DEGREES);
        float high = (float) Math.toRadians(LAYER_FADE_HIGH_DEGREES);
        for (Overhead cloud : layer.clouds()) {
            float alpha = smoothstep(cloud.threshold(), cloud.threshold() + 0.1f, look.coverage()) * look.opacity();
            if (alpha <= 0.002f) {
                continue;
            }
            Picture picture = layer.pictures().get(cloud.picture());
            float x = (float) wrap(cloud.x() + layerX);
            float z = (float) wrap(cloud.z() + layerZ);
            float cos = (float) Math.cos(cloud.turn());
            float sin = (float) Math.sin(cloud.turn());
            float halfWidth = cloud.width() * 0.5f;
            float halfHeight = halfWidth / picture.aspect();
            Vector3f[] corners = new Vector3f[4];
            float[] alphas = new float[4];
            float most = 0f;
            float[][] signs = {{-1f, -1f}, {1f, -1f}, {1f, 1f}, {-1f, 1f}};
            for (int i = 0; i < 4; i++) {
                float a = signs[i][0] * halfWidth;
                float b = signs[i][1] * halfHeight;
                // Across the picture along its turn; up the picture at right angles to that, on the layer.
                Vector3f corner = new Vector3f(x + cos * a - sin * b, 1f, z + sin * a + cos * b);
                float elevation = (float) Math.atan2(1.0, Math.hypot(corner.x, corner.z));
                corners[i] = corner;
                alphas[i] = alpha * smoothstep(low, high, elevation);
                most = Math.max(most, alphas[i]);
            }
            if (most > 0.002f) {
                laid.add(new Laid(cloud, corners, alphas));
            }
        }
        return laid;
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
