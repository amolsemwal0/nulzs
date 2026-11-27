package org.zeronura.nulzs.graphics3d;

import org.joml.Vector3f;
import org.zeronura.nulzs.physics.AABB;
import org.zeronura.nulzs.scene.Component;

/**
 * A solid axis-aligned box that 3D characters collide with and can stand on: walls, crates, platforms.
 * Centred on the entity position plus {@link #offset}; rotation is ignored.
 */
public class BoxCollider3D extends Component {

    public final Vector3f halfExtents = new Vector3f();
    public final Vector3f offset = new Vector3f();

    private final Vector3f scratch = new Vector3f();

    /** A one-metre cube, for the editor's Add Component; set {@link #halfExtents} to size it. */
    public BoxCollider3D() {
        this(1f, 1f, 1f);
    }

    public BoxCollider3D(float width, float height, float depth) {
        halfExtents.set(width * 0.5f, height * 0.5f, depth * 0.5f);
    }

    public BoxCollider3D withOffset(float x, float y, float z) {
        offset.set(x, y, z);
        return this;
    }

    public AABB bounds() {
        Vector3f centre = transform().worldPosition(scratch).add(offset);
        return AABB.ofCenter(centre.x, centre.y, centre.z, halfExtents.x, halfExtents.y, halfExtents.z);
    }
}
