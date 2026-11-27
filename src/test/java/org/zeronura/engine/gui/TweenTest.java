package org.zeronura.engine.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TweenTest {

    private final Tweens tweens = new Tweens();

    @Test
    void aLinearTweenReachesTheMidpointHalfwayAndThenStops() {
        float[] value = {-1f};
        int[] completed = {0};
        tweens.to(0f, 10f, 1f, Ease.LINEAR, v -> value[0] = v).onComplete(() -> completed[0]++);

        tweens.update(0.5f);
        assertEquals(5f, value[0], 1e-4f);

        tweens.update(0.6f);
        tweens.update(1f);
        assertEquals(10f, value[0], 1e-4f);
        assertEquals(1, completed[0]);
        assertEquals(0, tweens.count());
    }

    @Test
    void aDelayedTweenDoesNotTouchItsValueUntilItStarts() {
        float[] value = {-1f};
        tweens.to(0f, 1f, 1f, Ease.LINEAR, v -> value[0] = v).delay(0.5f);

        tweens.update(0.4f);
        assertEquals(-1f, value[0]);

        tweens.update(0.35f);
        assertEquals(0.25f, value[0], 1e-4f);
    }

    @Test
    void yoyoRepeatsPlayBackwards() {
        float[] value = {-1f};
        tweens.to(0f, 1f, 1f, Ease.LINEAR, v -> value[0] = v).repeat(1).yoyo(true);

        tweens.update(1f);
        assertEquals(1f, value[0], 1e-4f);
        tweens.update(0.5f);
        assertEquals(0.5f, value[0], 1e-4f);
        tweens.update(0.5f);
        assertEquals(0f, value[0], 1e-4f);
        assertEquals(0, tweens.count());
    }

    @Test
    void tweensStartedFromACallbackRunOnTheNextUpdate() {
        float[] second = {-1f};
        tweens.to(0f, 1f, 0.1f, Ease.LINEAR, v -> {
        }).onComplete(() -> tweens.to(0f, 1f, 1f, Ease.LINEAR, v -> second[0] = v));

        tweens.update(0.2f);
        assertEquals(-1f, second[0]);
        assertEquals(1, tweens.count());

        tweens.update(0.5f);
        assertEquals(0.5f, second[0], 1e-4f);
    }

    @Test
    void cancelledTweensStopWithoutCompleting() {
        int[] completed = {0};
        Tween tween = tweens.to(0f, 1f, 1f, Ease.LINEAR, v -> {
        }).onComplete(() -> completed[0]++);
        tweens.update(0.2f);
        tween.cancel();
        tweens.update(2f);
        assertEquals(0, completed[0]);
        assertEquals(0, tweens.count());
    }

    @Test
    void everyEaseStartsAtZeroAndEndsAtOne() {
        for (Ease ease : Ease.values()) {
            assertEquals(0f, ease.apply(0f), 1e-4f, ease.name());
            assertEquals(1f, ease.apply(1f), 1e-4f, ease.name());
        }
    }
}
