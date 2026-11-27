package org.zeronura.nulzs.net;

/**
 * A two-way link to another running game, carrying text messages of one line each, in order.
 *
 * <p>Sending never waits on the other side, and messages that arrive are queued until {@link #poll} takes them, so a
 * scene can hold a conversation from {@code onUpdate} without threads of its own. How the link is made is up to the
 * implementation: a LAN socket ({@link TcpConnection}), an in-memory pair ({@link LocalConnection}), or a platform's
 * own networking later.
 *
 * <p>Messages that arrived before the link closed can still be polled after {@link #isOpen} turns false.
 */
public interface Connection extends AutoCloseable {

    /** Sends a message. Does nothing once the link is closed. */
    void send(String message);

    /** The next message received, or null if none is waiting. */
    String poll();

    /** False once either side has closed the link, or it has failed. */
    boolean isOpen();

    @Override
    void close();

    /** Rejects a message that could not travel as a single line. */
    static void requireOneLine(String message) {
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("a message must be a single line");
        }
    }
}
