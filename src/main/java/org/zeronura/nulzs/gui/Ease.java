package org.zeronura.nulzs.gui;

/**
 * Easing curves: how an animation moves between its start and end. Each maps t in [0, 1] to a progress
 * value that starts at 0 and ends at 1 (and may overshoot in between, for the back and elastic curves).
 */
public enum Ease {

    LINEAR {
        @Override
        public float apply(float t) {
            return t;
        }
    },
    QUAD_IN {
        @Override
        public float apply(float t) {
            return t * t;
        }
    },
    QUAD_OUT {
        @Override
        public float apply(float t) {
            return 1f - (1f - t) * (1f - t);
        }
    },
    QUAD_IN_OUT {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
        }
    },
    CUBIC_IN {
        @Override
        public float apply(float t) {
            return t * t * t;
        }
    },
    CUBIC_OUT {
        @Override
        public float apply(float t) {
            float u = 1f - t;
            return 1f - u * u * u;
        }
    },
    CUBIC_IN_OUT {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
        }
    },
    SINE_IN_OUT {
        @Override
        public float apply(float t) {
            return -((float) Math.cos(Math.PI * t) - 1f) / 2f;
        }
    },
    /** Overshoots slightly, then settles. Good for things popping into place. */
    BACK_OUT {
        @Override
        public float apply(float t) {
            float c1 = 1.70158f;
            float c3 = c1 + 1f;
            float u = t - 1f;
            return 1f + c3 * u * u * u + c1 * u * u;
        }
    },
    /** Springs past the end and wobbles back. */
    ELASTIC_OUT {
        @Override
        public float apply(float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }
            double c4 = (2.0 * Math.PI) / 3.0;
            return (float) (Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0);
        }
    },
    /** Bounces against the end like a dropped ball. */
    BOUNCE_OUT {
        @Override
        public float apply(float t) {
            float n1 = 7.5625f;
            float d1 = 2.75f;
            if (t < 1f / d1) {
                return n1 * t * t;
            } else if (t < 2f / d1) {
                t -= 1.5f / d1;
                return n1 * t * t + 0.75f;
            } else if (t < 2.5f / d1) {
                t -= 2.25f / d1;
                return n1 * t * t + 0.9375f;
            }
            t -= 2.625f / d1;
            return n1 * t * t + 0.984375f;
        }
    };

    public abstract float apply(float t);
}
