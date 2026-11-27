package org.zeronura.engine.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Accepts TCP connections in the background and queues them for the game loop to take with {@link #poll}. */
public final class TcpListener implements AutoCloseable {

    private final ServerSocket server;
    private final Queue<TcpConnection> accepted = new ConcurrentLinkedQueue<>();

    /** Listens on {@code port}; 0 picks any free port, which {@link #port} then reports. */
    public TcpListener(int port) throws IOException {
        server = new ServerSocket(port);
        Thread thread = new Thread(this::acceptLoop, "tcp-accept " + server.getLocalPort());
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                accepted.add(new TcpConnection(server.accept()));
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    /** The next connection accepted, or null if none is waiting. */
    public TcpConnection poll() {
        return accepted.poll();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        for (TcpConnection connection = accepted.poll(); connection != null; connection = accepted.poll()) {
            connection.close();
        }
    }
}
