package org.zeronura.nulzs.worldmap;

/**
 * A prop placed on a map: which prefab, where, facing which way, how big.
 *
 * <p>Height is stored as an offset from the ground rather than an absolute y, so objects stay planted when
 * the terrain under them is sculpted.
 */
public final class MapObject {

    public String prefab;
    public float x;
    public float z;
    /** Raise (positive) or sink (negative) relative to the ground. */
    public float offsetY;
    /** Rotation about the vertical axis, radians. */
    public float yaw;
    /**
     * Tilt, radians: {@code pitch} raises the object's +z end, {@code roll} its +x side. Upright things leave these
     * at zero; a road piece on a slope is tilted to lie along it.
     */
    public float pitch;
    public float roll;
    public float scale = 1f;
    /**
     * The {@linkplain MapSpline#id spline} that laid it — a house along a street — or 0 for one placed by hand. A
     * spline's objects are taken up and laid again whenever it changes.
     */
    public int owner;
    /**
     * Whether a spline laid over it clears it — a tree on a road, a rock under a row of houses — leaving it undrawn and
     * solid to nothing while the spline is there ({@link SplineClearing}). Off for what must stay whatever is drawn over
     * it: a quest's barrier, a landmark.
     */
    public boolean clearedBySpline = true;

    public MapObject(String prefab, float x, float z, float yaw, float scale) {
        this.prefab = prefab;
        this.x = x;
        this.z = z;
        this.yaw = yaw;
        this.scale = scale;
    }

    public MapObject copy() {
        MapObject copy = new MapObject(prefab, x, z, yaw, scale);
        copy.offsetY = offsetY;
        copy.pitch = pitch;
        copy.roll = roll;
        copy.owner = owner;
        copy.clearedBySpline = clearedBySpline;
        return copy;
    }
}
