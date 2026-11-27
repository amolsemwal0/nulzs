package org.zeronura.engine.rig2d;

import java.util.Random;

/**
 * Eyes that live on their own, the way Cubism's eye blink does, grown into a temperament: a rig's eyes hold an
 * expression (heavy lids, brows knitted or raised, a smile in them, wide open), blink now and then, glance about and
 * have their moments, all as a {@link Style} says. {@link RigInstance} runs it every update before its motions, so a
 * motion that moves the eyes (a flinch, a grin) takes over while it plays and hands back after.
 *
 * <p>It drives the eye parameters {@link AutoRig} makes ({@code EyeOpen}, {@code EyeLid}, {@code EyeSmile},
 * {@code EyeWide}, {@code EyeBallX}, {@code EyeBallY}, {@code BrowAngle}, {@code BrowY}); a rig without one of them
 * just doesn't show that part.
 */
public final class RigEyes {

    /** How a pair of eyes looks and behaves. Values are the parameters' own: 0..1, or -1..1 where they go both ways. */
    public static final class Style {
        /** Heavy upper lids, 0 (none) to 1 (half shut, flat across). */
        public float lid;
        /** Brows and upper lids: 1 knitted down at the inner corners (cross, set on it), -1 raised there (worried). */
        public float brow;
        /** Brows raised (1) or lowered (-1). */
        public float browY;
        /** Lower lids pushed up by a smile, 0..1. */
        public float smile;
        /** Eyes opened wide, 0..1. */
        public float wide;
        /** Where the gaze rests: x toward where the rig faces (1) or away (-1), y up (1) or down (-1). */
        public float lookX;
        public float lookY;

        /** Seconds between blinks, from {@code blinkMin} to {@code blinkMax}. */
        public float blinkMin = 2f;
        public float blinkMax = 5.5f;
        /** Seconds the lids take to shut, stay shut and open again. */
        public float blinkClose = 0.07f;
        public float blinkHold = 0.04f;
        public float blinkOpen = 0.11f;
        /** Chance a blink is followed straight away by another. */
        public float doubleBlink = 0.15f;
        /** Chance a blink is instead a drowsy droop: the lids sink slowly, then snap open. */
        public float droop;

        /** Seconds between glances, from {@code glanceMin} to {@code glanceMax}; 0 never glances. */
        public float glanceMin = 1.2f;
        public float glanceMax = 3.5f;
        /** How far a glance goes, 0..1 of the eye's reach. */
        public float glanceRange = 0.6f;
        /** Seconds a glance takes to get there: short darts, long drifts. */
        public float glanceTime = 0.09f;
        /** Chance a glance goes back to where the gaze rests. */
        public float glanceBack = 0.45f;
        /** Glances lean this way (x as {@link #lookX}): a shy one looks away. */
        public float glanceLean;
        /** A small restless shiver of the gaze, 0..1. */
        public float tremble;

        /** Seconds between moments, from {@code momentMin} to {@code momentMax}; 0 has none. */
        public float momentMin;
        public float momentMax;
        /** How long a moment lasts, coming and going included. */
        public float momentLength = 1f;
        /** What a moment adds to the expression. */
        public float momentLid;
        public float momentBrow;
        public float momentBrowY;
        public float momentSmile;
        public float momentWide;
        public float momentLookX;
        public float momentLookY;

        public Style copy() {
            Style s = new Style();
            s.lid = lid;
            s.brow = brow;
            s.browY = browY;
            s.smile = smile;
            s.wide = wide;
            s.lookX = lookX;
            s.lookY = lookY;
            s.blinkMin = blinkMin;
            s.blinkMax = blinkMax;
            s.blinkClose = blinkClose;
            s.blinkHold = blinkHold;
            s.blinkOpen = blinkOpen;
            s.doubleBlink = doubleBlink;
            s.droop = droop;
            s.glanceMin = glanceMin;
            s.glanceMax = glanceMax;
            s.glanceRange = glanceRange;
            s.glanceTime = glanceTime;
            s.glanceBack = glanceBack;
            s.glanceLean = glanceLean;
            s.tremble = tremble;
            s.momentMin = momentMin;
            s.momentMax = momentMax;
            s.momentLength = momentLength;
            s.momentLid = momentLid;
            s.momentBrow = momentBrow;
            s.momentBrowY = momentBrowY;
            s.momentSmile = momentSmile;
            s.momentWide = momentWide;
            s.momentLookX = momentLookX;
            s.momentLookY = momentLookY;
            return s;
        }
    }

    /** Bright, open eyes that blink and look about: what a rig gets when nothing else is said. */
    public static Style lively() {
        return new Style();
    }

    private Style style;
    private final Random random;

    // Blinking: time to the next blink, and the one under way (-1 for none).
    private float untilBlink;
    private float blinkAt = -1f;
    private boolean drooping;
    private float droopLength;

    // Glancing: the gaze, where it is going and from where.
    private float gazeX;
    private float gazeY;
    private float fromX;
    private float fromY;
    private float toX;
    private float toY;
    private float moveAt = -1f;
    private float moveLength;
    private float untilGlance;
    private float shiverX;
    private float shiverY;
    private float untilShiver;

    // Moments.
    private float untilMoment;
    private float momentAt = -1f;

    // The parameters as last worked out.
    float open = 1f;
    float lid;
    float brow;
    float browY;
    float smile;
    float wide;
    float ballX;
    float ballY;

    public RigEyes(Style style, long seed) {
        this.random = new Random(seed);
        style(style);
        untilBlink = between(0.4f, Math.max(0.5f, this.style.blinkMax * 0.8f));
        untilGlance = between(0.3f, Math.max(0.4f, this.style.glanceMax));
        untilMoment = this.style.momentMax > 0f ? between(this.style.momentMin * 0.5f, this.style.momentMax) : 0f;
        settle();
    }

    public Style style() {
        return style;
    }

    /** Changes temperament; the gaze and blinks carry on from where they are. */
    public void style(Style next) {
        style = next == null ? lively() : next.copy();
    }

    private float between(float a, float b) {
        return a + random.nextFloat() * Math.max(0f, b - a);
    }

    /** Puts the eyes at rest in their expression at once, with nothing under way. */
    public void settle() {
        blinkAt = -1f;
        moveAt = -1f;
        momentAt = -1f;
        gazeX = toX = style.lookX;
        gazeY = toY = style.lookY;
        shiverX = 0f;
        shiverY = 0f;
        update(0f);
    }

    /** Moves the eyes on by {@code dt} seconds. */
    public void update(float dt) {
        Style s = style;
        float closed = 0f;

        // Blinks.
        if (blinkAt < 0f) {
            untilBlink -= dt;
            if (untilBlink <= 0f) {
                blinkAt = 0f;
                drooping = random.nextFloat() < s.droop;
                droopLength = drooping ? between(1.4f, 2.4f) : 0f;
            }
        }
        if (blinkAt >= 0f) {
            blinkAt += dt;
            if (drooping) {
                // The lids sink over most of the droop, hang there, then snap open.
                float sink = droopLength * 0.6f;
                float hang = droopLength * 0.3f;
                if (blinkAt < sink) {
                    closed = 0.75f * smooth(blinkAt / sink);
                } else if (blinkAt < sink + hang) {
                    closed = 0.75f;
                } else {
                    closed = 0.75f * (1f - smooth((blinkAt - sink - hang) / Math.max(0.01f, droopLength - sink - hang)));
                }
                if (blinkAt >= droopLength) {
                    endBlink(false);
                }
            } else {
                float c = Math.max(0.01f, s.blinkClose);
                float h = Math.max(0f, s.blinkHold);
                float o = Math.max(0.01f, s.blinkOpen);
                if (blinkAt < c) {
                    closed = blinkAt / c;
                } else if (blinkAt < c + h) {
                    closed = 1f;
                } else {
                    closed = 1f - (blinkAt - c - h) / o;
                }
                closed = Math.max(0f, Math.min(1f, closed));
                if (blinkAt >= c + h + o) {
                    endBlink(true);
                }
            }
        }

        // Moments: a while of a different face.
        float m = 0f;
        if (s.momentMax > 0f) {
            if (momentAt < 0f) {
                untilMoment -= dt;
                if (untilMoment <= 0f) {
                    momentAt = 0f;
                }
            }
            if (momentAt >= 0f) {
                momentAt += dt;
                float length = Math.max(0.2f, s.momentLength);
                float in = Math.min(0.25f, length * 0.3f);
                float out = Math.min(0.35f, length * 0.35f);
                if (momentAt < in) {
                    m = smooth(momentAt / in);
                } else if (momentAt < length - out) {
                    m = 1f;
                } else {
                    m = 1f - smooth((momentAt - (length - out)) / out);
                }
                if (momentAt >= length) {
                    momentAt = -1f;
                    m = 0f;
                    untilMoment = between(s.momentMin, s.momentMax);
                }
            }
        }

        // Glances: a quick dart (or a slow drift) to somewhere new, held there a while.
        if (s.glanceMax > 0f) {
            untilGlance -= dt;
            if (untilGlance <= 0f && moveAt < 0f) {
                float tx;
                float ty;
                if (random.nextFloat() < s.glanceBack) {
                    tx = s.lookX;
                    ty = s.lookY;
                } else {
                    double angle = random.nextDouble() * Math.PI * 2.0;
                    float reach = s.glanceRange * (0.55f + 0.45f * random.nextFloat());
                    tx = s.lookX + (float) Math.cos(angle) * reach + s.glanceLean * 0.5f;
                    ty = s.lookY + (float) Math.sin(angle) * reach * 0.6f;
                }
                tx = Math.max(-1f, Math.min(1f, tx));
                ty = Math.max(-1f, Math.min(1f, ty));
                // A big look round often comes with a blink, as it does in people.
                if (Math.hypot(tx - gazeX, ty - gazeY) > 0.7f && blinkAt < 0f && random.nextFloat() < 0.3f) {
                    untilBlink = 0f;
                }
                fromX = gazeX;
                fromY = gazeY;
                toX = tx;
                toY = ty;
                moveAt = 0f;
                moveLength = Math.max(0.02f, s.glanceTime * (0.8f + 0.4f * random.nextFloat()));
                untilGlance = between(Math.max(0.15f, s.glanceMin), Math.max(s.glanceMin, s.glanceMax));
            }
        }
        if (moveAt >= 0f) {
            moveAt += dt;
            float t = Math.min(1f, moveAt / moveLength);
            // Darts ease out (fast off, settling); drifts ease both ways.
            float e = moveLength < 0.2f ? 1f - (1f - t) * (1f - t) : smooth(t);
            gazeX = fromX + (toX - fromX) * e;
            gazeY = fromY + (toY - fromY) * e;
            if (t >= 1f) {
                moveAt = -1f;
            }
        }
        if (s.tremble > 0f) {
            untilShiver -= dt;
            if (untilShiver <= 0f) {
                shiverX = (random.nextFloat() * 2f - 1f) * 0.12f * s.tremble;
                shiverY = (random.nextFloat() * 2f - 1f) * 0.08f * s.tremble;
                untilShiver = between(0.06f, 0.2f);
            }
        } else {
            shiverX = 0f;
            shiverY = 0f;
        }

        lid = clamp01(s.lid + s.momentLid * m);
        brow = clamp(s.brow + s.momentBrow * m);
        browY = clamp(s.browY + s.momentBrowY * m);
        smile = clamp01(s.smile + s.momentSmile * m);
        wide = clamp01(s.wide + s.momentWide * m);
        ballX = clamp(gazeX + s.momentLookX * m + shiverX);
        ballY = clamp(gazeY + s.momentLookY * m + shiverY);
        open = 1f - closed;
    }

    private void endBlink(boolean mayDouble) {
        blinkAt = -1f;
        drooping = false;
        if (mayDouble && random.nextFloat() < style.doubleBlink) {
            untilBlink = between(0.08f, 0.16f);
        } else {
            untilBlink = between(Math.max(0.3f, style.blinkMin), Math.max(style.blinkMin, style.blinkMax));
        }
    }

    /** Whether the eyes are shut or shutting right now (a blink or a droop under way). */
    public boolean blinking() {
        return blinkAt >= 0f;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
