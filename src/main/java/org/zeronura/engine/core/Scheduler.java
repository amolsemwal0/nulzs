package org.zeronura.engine.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Delayed and repeating actions measured in game time: "flip the cards back in one second", "spawn an
 * enemy every five".
 *
 * <p>Each scene has its own, updated with the scene, so timers stop when the scene is paused or covered
 * and vanish with it — a timer that fires into a scene that has already exited is a classic crash.
 */
public final class Scheduler {

    /** A pending action. Cancel it to stop it firing. */
    public static final class Task {
        private final Runnable action;
        private final float interval;
        private float remaining;
        private boolean cancelled;

        private Task(Runnable action, float delay, float interval) {
            this.action = action;
            this.remaining = delay;
            this.interval = interval;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean isRepeating() {
            return interval > 0f;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private final List<Task> added = new ArrayList<>();
    private boolean updating;

    /** Runs {@code action} once, {@code seconds} from now. */
    public Task after(float seconds, Runnable action) {
        return add(new Task(action, seconds, 0f));
    }

    /** Runs {@code action} every {@code seconds}, first after one interval. */
    public Task every(float seconds, Runnable action) {
        if (seconds <= 0f) {
            throw new IllegalArgumentException("interval must be positive: " + seconds);
        }
        return add(new Task(action, seconds, seconds));
    }

    private Task add(Task task) {
        // Tasks scheduled from inside a running task join after this update, so a task that reschedules
        // itself cannot run twice in one frame.
        (updating ? added : tasks).add(task);
        return task;
    }

    public void update(float dt) {
        updating = true;
        try {
            tasks.removeIf(task -> advance(task, dt));
        } finally {
            updating = false;
        }
        tasks.addAll(added);
        added.clear();
    }

    /** Returns true when the task is finished and should be dropped. */
    private static boolean advance(Task task, float dt) {
        if (task.cancelled) {
            return true;
        }
        task.remaining -= dt;
        if (task.interval <= 0f) {
            if (task.remaining <= 0f) {
                task.action.run();
                return true;
            }
            return false;
        }
        // A long frame can span several intervals; each one fires, so "every 0.1s" stays a rate.
        while (task.remaining <= 0f && !task.cancelled) {
            task.action.run();
            task.remaining += task.interval;
        }
        return task.cancelled;
    }

    public void cancelAll() {
        tasks.forEach(Task::cancel);
        added.forEach(Task::cancel);
    }

    /** Pending tasks, including ones added during the current update. */
    public int size() {
        return tasks.size() + added.size();
    }
}
