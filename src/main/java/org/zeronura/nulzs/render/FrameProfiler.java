package org.zeronura.nulzs.render;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.glFinish;

/**
 * Where a real frame's time goes, measured inside the running game rather than in a probe.
 *
 * <p><b>This exists because the offline probe could not reproduce the frame that was actually slow.</b>
 * {@code FrameCostProbe} builds no {@code World}, draws no character, runs no water reflection, no spray,
 * no particles and no UI, and for its whole life it stood in one spot in the grasslands at 1280x720. A
 * session reported at 46 ms measured 5 ms there — not because either number was wrong, but because they
 * were measurements of different frames. Chasing that gap by guessing at what the probe was missing is how
 * an afternoon gets spent optimising a subsystem that was never the problem.
 *
 * <p>So the game measures itself, at the resolution and viewpoint and weather the player is actually in,
 * and reports the split on screen.
 *
 * <h2>It deliberately makes the frame slower while it is on</h2>
 * Each phase is bracketed with {@link org.lwjgl.opengl.GL33#glFinish()}, which drains the GPU so the time
 * lands on the pass that caused it. That is exactly what a frame must not do — it destroys the overlap
 * between CPU and GPU that the whole pipeline depends on — so the <b>total will read higher than the same
 * frame costs with this switched off</b>. Read the split, never the total. It is the same trade
 * {@code FrameCostProbe} documents, and it is worth it: an unattributed 46 ms is not something anyone can
 * act on.
 *
 * <p>The honest alternative is {@code GL_TIME_ELAPSED} timer queries, which are asynchronous and would not
 * stall — that is the upgrade if this ever wants to be on during ordinary play rather than during a
 * diagnosis.
 *
 * <h2>Disabled is free</h2>
 * {@link #begin} and {@link #end} are a field read and a return when it is off, so the brackets can be
 * left in the frame loop permanently rather than compiled in and out.
 */
public final class FrameProfiler {

    /** How long samples are gathered before the display is refreshed — the FpsCounter's cadence. */
    private static final float REFRESH_SECONDS = 0.5f;

    /**
     * One phase's cost: the whole of it, and how much was the CPU's own work.
     *
     * <p>The two apart is the useful part. {@link #end} takes the clock <b>before</b> draining the GPU and
     * again after, so {@code cpuMillis} is what this thread spent and {@code millis} adds what it then had
     * to wait for the graphics driver to finish. A phase where the two are equal is CPU-bound and the code
     * in it is the thing to look at; one where {@code cpuMillis} is near zero submitted a little work and
     * waited a long time, and the code in it is not the problem at all.
     */
    public record Entry(String name, float millis, float cpuMillis) {}

    private final List<String> names = new ArrayList<>();
    private final List<Double> totals = new ArrayList<>();
    private final List<Double> cpuTotals = new ArrayList<>();
    private boolean enabled;
    private int openIndex = -1;
    private long openStart;
    private int frames;
    private float elapsed;
    private List<Entry> published = List.of();
    private String note = "";

    public boolean isEnabled() {
        return enabled;
    }

    /** Clears everything gathered, so a run of readings never mixes two settings. */
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            reset();
            published = List.of();
        }
        this.enabled = enabled;
    }

    /**
     * Opens a phase. Nested calls are not supported and are ignored rather than throwing — this is a
     * diagnostic, and a mismatched bracket somewhere in a long frame loop should not take the game down.
     */
    public void begin(String name) {
        if (!enabled || openIndex >= 0) {
            return;
        }
        int i = names.indexOf(name);
        if (i < 0) {
            names.add(name);
            totals.add(0.0);
            cpuTotals.add(0.0);
            i = names.size() - 1;
        }
        glFinish();
        openIndex = i;
        openStart = System.nanoTime();
    }

    /** Closes the open phase, draining the GPU first so its work is counted against it. */
    public void end() {
        if (!enabled || openIndex < 0) {
            return;
        }
        // The CPU's own time first, then again once the driver has caught up. See Entry.
        long cpuEnd = System.nanoTime();
        glFinish();
        long gpuEnd = System.nanoTime();
        cpuTotals.set(openIndex, cpuTotals.get(openIndex) + (cpuEnd - openStart) / 1e6);
        totals.set(openIndex, totals.get(openIndex) + (gpuEnd - openStart) / 1e6);
        openIndex = -1;
    }

    /**
     * Ends a frame and, once enough have gone by, publishes the averages.
     *
     * <p>Averaged over half a second for the reason the frame-rate readout is: a per-frame breakdown
     * changes far too fast to read, and what is being looked for here is which phase is <i>consistently</i>
     * large rather than what one frame happened to do.
     */
    public void endFrame(float dt) {
        if (!enabled) {
            return;
        }
        frames++;
        elapsed += dt;
        if (elapsed < REFRESH_SECONDS || frames == 0) {
            return;
        }
        List<Entry> out = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            out.add(new Entry(names.get(i), (float) (totals.get(i) / frames),
                    (float) (cpuTotals.get(i) / frames)));
        }
        // Largest first: the point of the list is which phase to look at, and that is the top line.
        out.sort((a, b) -> Float.compare(b.millis(), a.millis()));
        published = List.copyOf(out);
        reset();
    }

    private void reset() {
        for (int i = 0; i < totals.size(); i++) {
            totals.set(i, 0.0);
            cpuTotals.set(i, 0.0);
        }
        frames = 0;
        elapsed = 0f;
        openIndex = -1;
    }

    /**
     * A line of caller-supplied context shown under the breakdown — counts rather than times.
     *
     * <p>Times alone cannot separate "the same work costing more" from "more work", and those need
     * opposite fixes. When the fluid grid reads 46 ms it matters enormously whether it is stepping the
     * seventy-odd chunks a probe reproduces or ten times that, and no timing can say which.
     */
    public void setNote(String note) {
        this.note = note == null ? "" : note;
    }

    public String note() {
        return note;
    }

    /** The last published breakdown, largest phase first. Empty until half a second has been gathered. */
    public List<Entry> snapshot() {
        return published;
    }
}
