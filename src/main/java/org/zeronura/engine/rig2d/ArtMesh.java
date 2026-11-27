package org.zeronura.engine.rig2d;

/**
 * One layer of the art laid on a mesh of triangles, which is what moves: its vertices are bent by its keyforms and
 * then by every deformer above it. Vertices are canvas pixels at rest, and the picture is pinned to them there, so a
 * vertex moved drags its patch of the layer with it.
 *
 * <p>A keyform holds {@code 2 + 2 * vertexCount} floats: opacity (added to 1), draw order (added to {@link #drawOrder}),
 * then each vertex's offset.
 */
public final class ArtMesh {

    public static final int OPACITY = 0;
    public static final int ORDER = 1;
    public static final int VERTICES = 2;

    public String id;
    /** The layer drawn, by its path in the layered file ({@code Head/Jaw}). */
    public String layer;
    /** The deformer it hangs under, or null for the canvas. */
    public String parent;
    /** x, y pairs in canvas pixels, at rest. */
    public float[] vertices;
    /** Three vertex indices per triangle. */
    public int[] triangles;
    /** Lower draws first (further back); ties keep the layered file's order. */
    public int drawOrder;
    public Keyforms keyforms;

    int parentIndex = -1;
    int layerIndex = -1;

    public ArtMesh(String id, String layer, String parent, float[] vertices, int[] triangles, int drawOrder) {
        this.id = id;
        this.layer = layer;
        this.parent = parent;
        this.vertices = vertices;
        this.triangles = triangles;
        this.drawOrder = drawOrder;
        this.keyforms = new Keyforms(VERTICES + vertices.length);
    }

    public int vertexCount() {
        return vertices.length / 2;
    }

    /** Replaces the mesh; keyform vertex offsets are carried over by nearest old vertex. */
    public void remesh(float[] newVertices, int[] newTriangles) {
        int oldCount = vertices.length / 2;
        int[] nearest = new int[newVertices.length / 2];
        for (int v = 0; v < nearest.length; v++) {
            float best = Float.MAX_VALUE;
            for (int o = 0; o < oldCount; o++) {
                float dx = newVertices[v * 2] - vertices[o * 2];
                float dy = newVertices[v * 2 + 1] - vertices[o * 2 + 1];
                float d = dx * dx + dy * dy;
                if (d < best) {
                    best = d;
                    nearest[v] = o;
                }
            }
        }
        Keyforms carried = new Keyforms(VERTICES + newVertices.length);
        for (Keyforms.Track track : keyforms.tracks) {
            Keyforms.Track copy = new Keyforms.Track();
            copy.parameters.addAll(track.parameters);
            for (float[] k : track.keys) {
                copy.keys.add(k.clone());
            }
            for (float[] form : track.forms) {
                float[] next = new float[VERTICES + newVertices.length];
                next[OPACITY] = form[OPACITY];
                next[ORDER] = form[ORDER];
                for (int v = 0; v < nearest.length && oldCount > 0; v++) {
                    next[VERTICES + v * 2] = form[VERTICES + nearest[v] * 2];
                    next[VERTICES + v * 2 + 1] = form[VERTICES + nearest[v] * 2 + 1];
                }
                copy.forms.add(next);
            }
            carried.tracks.add(copy);
        }
        vertices = newVertices;
        triangles = newTriangles;
        keyforms = carried;
    }

    public ArtMesh copy() {
        ArtMesh copy = new ArtMesh(id, layer, parent, vertices.clone(), triangles.clone(), drawOrder);
        copy.keyforms = keyforms.copy();
        return copy;
    }
}
