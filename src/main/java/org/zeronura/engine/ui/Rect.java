package org.zeronura.engine.ui;

/**
 * A screen-space rectangle in {@link UIRenderer}'s pixel coordinates (origin top-left).
 *
 * <p>The point of this type is that a widget's <i>drawn</i> shape and its <i>clickable</i> shape are the
 * same value rather than two copies of the same arithmetic. A hit test written separately from the draw
 * call looks right, tests clean, and drifts the first time a padding constant changes — leaving a button
 * whose top edge does nothing. So layout returns rectangles, drawing consumes them, and hit-testing
 * consumes the same ones.
 */
public record Rect(float x, float y, float width, float height) {

    /**
     * Whether a point is inside.
     *
     * <p>Half-open on the far edges, so rectangles laid out edge to edge — a row of menu options — never
     * both claim the same pixel. Which one wins would otherwise depend on iteration order.
     */
    public boolean contains(float pointX, float pointY) {
        return pointX >= x && pointX < x + width
                && pointY >= y && pointY < y + height;
    }

    public float centerX() {
        return x + width * 0.5f;
    }

    public float centerY() {
        return y + height * 0.5f;
    }
}
