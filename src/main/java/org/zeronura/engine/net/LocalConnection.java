package org.zeronura.engine.net;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Two {@link Connection}s joined to each other in memory, for tests and for anything that talks to itself. */
public final class LocalConnection implements Connection {

    private final Queue<String> inbox = new ConcurrentLinkedQueue<>();
    private LocalConnection other;
    private volatile boolean closed;

    private LocalConnection() {
    }

    /** Two ends of one link: each receives what the other sends, and closing either closes both. */
    public static Connection[] pair() {
        LocalConnection first = new LocalConnection();
        LocalConnection second = new LocalConnection();
        first.other = second;
        second.other = first;
        return new Connection[] {first, second};
    }

    @Override
    public void send(String message) {
        Connection.requireOneLine(message);
        if (isOpen()) {
            other.inbox.add(message);
        }
    }

    @Override
    public String poll() {
        return inbox.poll();
    }

    @Override
    public boolean isOpen() {
        return !closed && !other.closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
