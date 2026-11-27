package org.zeronura.nulzs.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    private final Scheduler scheduler = new Scheduler();

    @Test
    void afterFiresOnceWhenItsTimeComes() {
        int[] fired = {0};
        scheduler.after(1f, () -> fired[0]++);

        scheduler.update(0.9f);
        assertEquals(0, fired[0]);
        scheduler.update(0.2f);
        scheduler.update(5f);
        assertEquals(1, fired[0]);
        assertEquals(0, scheduler.size());
    }

    @Test
    void everyKeepsItsRateAcrossALongFrame() {
        int[] fired = {0};
        scheduler.every(0.1f, () -> fired[0]++);

        scheduler.update(0.55f);

        assertEquals(5, fired[0]);
    }

    @Test
    void cancelledTasksNeverFire() {
        int[] fired = {0};
        Scheduler.Task task = scheduler.after(0.5f, () -> fired[0]++);
        task.cancel();
        scheduler.update(1f);
        assertEquals(0, fired[0]);
    }

    @Test
    void aRepeatingTaskCanCancelItself() {
        int[] fired = {0};
        Scheduler.Task[] self = new Scheduler.Task[1];
        self[0] = scheduler.every(0.1f, () -> {
            if (++fired[0] == 3) {
                self[0].cancel();
            }
        });
        scheduler.update(10f);
        assertEquals(3, fired[0]);
    }

    @Test
    void tasksScheduledFromATaskWaitForTheNextUpdate() {
        int[] fired = {0};
        scheduler.after(0.1f, () -> scheduler.after(0f, () -> fired[0]++));

        scheduler.update(0.2f);
        assertEquals(0, fired[0]);
        scheduler.update(0.01f);
        assertEquals(1, fired[0]);
    }
}
