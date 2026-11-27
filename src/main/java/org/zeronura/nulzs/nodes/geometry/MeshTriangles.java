package org.zeronura.nulzs.nodes.geometry;

import java.util.List;

/** A mesh face as triangles of corner indices, the way Blender splits faces for drawing. */
public final class MeshTriangles {

    private MeshTriangles() {
    }

    /** Triangles are triples of corner indices; quads split along 0-2, larger faces by ear clipping. */
    public static List<int[]> of(MeshGeometry mesh, int face) {
        return Triangulation.face(mesh, face);
    }
}
