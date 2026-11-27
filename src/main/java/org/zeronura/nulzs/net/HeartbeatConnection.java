package org.zeronura.nulzs.net;

import java.util.function.LongSupplier;

/**
 * A {@link Connection} that notices when the other game has gone quiet, which a link on its own may never do.
 *
 * <p>A game that closes, crashes or leaves closes its end, and the other end hears of it at once. One whose computer
 * loses power or its network, goes to sleep, or whose game hangs says nothing at all: a TCP socket with no data in
 * flight then stays open for good, and a scene waiting on it waits for good. So each end sends a short
 * {@value #BEAT} line every {@link #INTERVAL_NANOS two seconds}, swallowed at the other end, and a link that has heard
 * nothing — no beat, no message — for {@link #TIMEOUT_NANOS fifteen seconds} counts as lost: {@link #isOpen} turns
 * false and the link underneath is closed, as if the other game had left.
 *
 * <p>The silence only counts once the other end has been heard beating. A game from before heartbeats never beats,
 * and may rightly say nothing for minutes while its player thinks; with one of those the link behaves as it always did.
 *
 * <p>Beats are sent from {@link #poll}, which a session calls every frame. A game that stops running its loop for
 * longer than the timeout — on Windows, while its window is held by the title bar — goes quiet with it, and the other
 * game takes it for gone; it finds its own link closed when it comes back.
 */
public final class HeartbeatConnection implements Connection {

    /** The line each end sends to say it is still there. Older games ignore it, as they ignore any line they don't know. */
    public static final String BEAT = "BEAT";
    /** How often a beat goes out. */
    public static final long INTERVAL_NANOS = 2_000_000_000L;
    /** How long the other end may say nothing at all before the link counts as lost. */
    public static final long TIMEOUT_NANOS = 15_000_000_000L;

    private final Connection link;
    private final LongSupplier clock;
    private long lastSent;
    private long lastHeard;
    /** Whether a beat has gone out yet: the first is what tells the other end that this one beats at all. */
    private boolean beaten;
    private boolean partnerBeats;
    private boolean timedOut;

    private HeartbeatConnection(Connection link, LongSupplier clock) {
        this.link = link;
        this.clock = clock;
        long now = clock.getAsLong();
        lastHeard = now;
        lastSent = now;
    }

    /** {@code link} with a heartbeat, on the system clock; a link that already has one is returned as it is. */
    public static HeartbeatConnection wrap(Connection link) {
        return wrap(link, System::nanoTime);
    }

    /** {@code link} with a heartbeat, timed by {@code clock} in nanoseconds; a link that already has one is returned as it is. */
    public static HeartbeatConnection wrap(Connection link, LongSupplier clock) {
        return link instanceof HeartbeatConnection beating ? beating : new HeartbeatConnection(link, clock);
    }

    @Override
    public void send(String message) {
        link.send(message);
        // Any line tells the other end this one is still there; a beat is only needed in between.
        lastSent = clock.getAsLong();
    }

    @Override
    public String poll() {
        beat();
        for (String line = link.poll(); line != null; line = link.poll()) {
            lastHeard = clock.getAsLong();
            if (line.equals(BEAT)) {
                partnerBeats = true;
                continue;
            }
            return line;
        }
        return null;
    }

    @Override
    public boolean isOpen() {
        if (timedOut) {
            return false;
        }
        if (partnerBeats && clock.getAsLong() - lastHeard > TIMEOUT_NANOS) {
            timedOut = true;
            link.close();
            return false;
        }
        return link.isOpen();
    }

    /** Whether the link was given up because the other end went quiet, rather than closed by either side. */
    public boolean timedOut() {
        isOpen();
        return timedOut;
    }

    @Override
    public void close() {
        link.close();
    }

    private void beat() {
        long now = clock.getAsLong();
        // The first goes out on the first poll whatever was sent before it, so the other end learns straight away that
        // this one beats; after that any line will do in place of one.
        if (!timedOut && (!beaten || now - lastSent >= INTERVAL_NANOS) && link.isOpen()) {
            link.send(BEAT);
            lastSent = now;
            beaten = true;
        }
    }
}
