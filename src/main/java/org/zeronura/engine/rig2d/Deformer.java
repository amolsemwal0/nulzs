package org.zeronura.engine.rig2d;

/**
 * Something that bends what hangs under it: art meshes and other deformers name it as their parent, and whatever it
 * does — warp a grid, turn about a pivot — moves all of them with it. Deformers nest: a jaw's rotation inside a head's
 * warp inside the body's warp turns with the jaw, then bends with the head, then sways with the body.
 *
 * <p>Every deformer is laid out in canvas pixels (y down, as the art is painted) at rest, and posed by its
 * {@link #keyforms}, which hold offsets from that rest layout. Posing gives a state (a float array the rig instance
 * keeps, so one model can be posed differently by many instances); {@link #apply} moves points by it.
 */
public abstract sealed class Deformer permits Deformer.Warp, Deformer.Rotation {

    public String id;
    /** The deformer this one hangs under, or null for the canvas. */
    public String parent;
    public Keyforms keyforms;

    int parentIndex = -1;

    Deformer(String id, String parent, int formSize) {
        this.id = id;
        this.parent = parent;
        this.keyforms = new Keyforms(formSize);
    }

    /** How many floats one keyform (and one state) holds. */
    public abstract int formSize();

    /** Turns a blend of keyform offsets into the posed state, in place. */
    abstract void pose(float[] state);

    /** Moves {@code count} points (x, y pairs from {@code offset}) by the posed state. */
    abstract void apply(float[] state, float[] points, int offset, int count);

    public abstract Deformer copy();

    // ------------------------------------------------------------------------------------------------------------

    /**
     * A grid of control points over a rectangle, {@code columns} x {@code rows} cells; whatever lies in the rectangle
     * follows the grid smoothly (Catmull-Rom between the points, so bends have no corners). A keyform moves the
     * grid's points: {@code 2 * (columns + 1) * (rows + 1)} offsets, point by point along each row from the top left.
     * What lies outside the rectangle moves with its nearest edge.
     */
    public static final class Warp extends Deformer {
        public float x;
        public float y;
        public float width;
        public float height;
        public int columns;
        public int rows;

        public Warp(String id, String parent, float x, float y, float width, float height, int columns, int rows) {
            super(id, parent, 2 * (columns + 1) * (rows + 1));
            this.x = x;
            this.y = y;
            this.width = Math.max(1f, width);
            this.height = Math.max(1f, height);
            this.columns = Math.max(1, columns);
            this.rows = Math.max(1, rows);
        }

        @Override
        public int formSize() {
            return 2 * (columns + 1) * (rows + 1);
        }

        /** Rest position of grid point (column, row). */
        public float restX(int column) {
            return x + width * column / columns;
        }

        public float restY(int row) {
            return y + height * row / rows;
        }

        @Override
        void pose(float[] state) {
            for (int r = 0; r <= rows; r++) {
                for (int c = 0; c <= columns; c++) {
                    int i = (r * (columns + 1) + c) * 2;
                    state[i] += restX(c);
                    state[i + 1] += restY(r);
                }
            }
        }

        @Override
        void apply(float[] state, float[] points, int offset, int count) {
            for (int p = 0; p < count; p++) {
                int i = offset + p * 2;
                float px = points[i];
                float py = points[i + 1];
                float u = (px - x) / width * columns;
                float v = (py - y) / height * rows;
                float uc = Math.max(0f, Math.min(columns, u));
                float vc = Math.max(0f, Math.min(rows, v));
                // Outside the rectangle a point keeps its distance from the edge, moving with it.
                float outX = (u - uc) * width / columns;
                float outY = (v - vc) * height / rows;
                int ci = Math.min(columns - 1, (int) Math.floor(uc));
                int ri = Math.min(rows - 1, (int) Math.floor(vc));
                float s = uc - ci;
                float t = vc - ri;
                float sx = 0f;
                float sy = 0f;
                for (int b = 0; b < 4; b++) {
                    float wr = catmullRom(t, b);
                    if (wr == 0f) {
                        continue;
                    }
                    for (int a = 0; a < 4; a++) {
                        float w = catmullRom(s, a) * wr;
                        if (w == 0f) {
                            continue;
                        }
                        sx += w * gridX(state, ci - 1 + a, ri - 1 + b);
                        sy += w * gridY(state, ci - 1 + a, ri - 1 + b);
                    }
                }
                points[i] = sx + outX;
                points[i + 1] = sy + outY;
            }
        }

        /** A grid point, carried straight on past the edges so that an unbent grid maps every point to itself. */
        private float gridX(float[] state, int c, int r) {
            return grid(state, c, r, 0);
        }

        private float gridY(float[] state, int c, int r) {
            return grid(state, c, r, 1);
        }

        private float grid(float[] state, int c, int r, int axis) {
            if (c < 0) {
                return 2f * grid(state, 0, r, axis) - grid(state, Math.min(1, columns), r, axis);
            }
            if (c > columns) {
                return 2f * grid(state, columns, r, axis) - grid(state, Math.max(0, columns - 1), r, axis);
            }
            if (r < 0) {
                return 2f * grid(state, c, 0, axis) - grid(state, c, Math.min(1, rows), axis);
            }
            if (r > rows) {
                return 2f * grid(state, c, rows, axis) - grid(state, c, Math.max(0, rows - 1), axis);
            }
            return state[(r * (columns + 1) + c) * 2 + axis];
        }

        static float catmullRom(float s, int which) {
            float s2 = s * s;
            float s3 = s2 * s;
            return switch (which) {
                case 0 -> (-s3 + 2f * s2 - s) * 0.5f;
                case 1 -> (3f * s3 - 5f * s2 + 2f) * 0.5f;
                case 2 -> (-3f * s3 + 4f * s2 + s) * 0.5f;
                default -> (s3 - s2) * 0.5f;
            };
        }

        /** Changes the grid's cells, keeping keyform shapes by resampling them onto the new grid. */
        public void regrid(int newColumns, int newRows) {
            newColumns = Math.max(1, newColumns);
            newRows = Math.max(1, newRows);
            if (newColumns == columns && newRows == rows) {
                return;
            }
            Keyforms resampled = new Keyforms(2 * (newColumns + 1) * (newRows + 1));
            for (Keyforms.Track track : keyforms.tracks) {
                Keyforms.Track copy = new Keyforms.Track();
                copy.parameters.addAll(track.parameters);
                for (float[] k : track.keys) {
                    copy.keys.add(k.clone());
                }
                for (float[] form : track.forms) {
                    float[] state = form.clone();
                    pose(state);
                    float[] points = new float[2 * (newColumns + 1) * (newRows + 1)];
                    for (int r = 0; r <= newRows; r++) {
                        for (int c = 0; c <= newColumns; c++) {
                            int i = (r * (newColumns + 1) + c) * 2;
                            points[i] = x + width * c / newColumns;
                            points[i + 1] = y + height * r / newRows;
                        }
                    }
                    float[] rest = points.clone();
                    apply(state, points, 0, points.length / 2);
                    for (int i = 0; i < points.length; i++) {
                        points[i] -= rest[i];
                    }
                    copy.forms.add(points);
                }
                resampled.tracks.add(copy);
            }
            columns = newColumns;
            rows = newRows;
            keyforms = resampled;
        }

        @Override
        public Warp copy() {
            Warp copy = new Warp(id, parent, x, y, width, height, columns, rows);
            copy.keyforms = keyforms.copy();
            return copy;
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * Turns what hangs under it about a pivot. A keyform holds {@code [angle, scale, dx, dy]}: degrees clockwise on
     * screen, scale added to 1, and how far the pivot itself moves.
     */
    public static final class Rotation extends Deformer {
        public static final int ANGLE = 0;
        public static final int SCALE = 1;
        public static final int DX = 2;
        public static final int DY = 3;

        public float originX;
        public float originY;

        public Rotation(String id, String parent, float originX, float originY) {
            super(id, parent, 4);
            this.originX = originX;
            this.originY = originY;
        }

        @Override
        public int formSize() {
            return 4;
        }

        @Override
        void pose(float[] state) {
            state[SCALE] += 1f;
            state[DX] += originX;
            state[DY] += originY;
        }

        @Override
        void apply(float[] state, float[] points, int offset, int count) {
            double radians = Math.toRadians(state[ANGLE]);
            float cos = (float) Math.cos(radians) * state[SCALE];
            float sin = (float) Math.sin(radians) * state[SCALE];
            for (int p = 0; p < count; p++) {
                int i = offset + p * 2;
                float dx = points[i] - originX;
                float dy = points[i + 1] - originY;
                points[i] = state[DX] + dx * cos - dy * sin;
                points[i + 1] = state[DY] + dx * sin + dy * cos;
            }
        }

        @Override
        public Rotation copy() {
            Rotation copy = new Rotation(id, parent, originX, originY);
            copy.keyforms = keyforms.copy();
            return copy;
        }
    }
}
