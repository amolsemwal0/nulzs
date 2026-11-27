package org.zeronura.engine.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A {@link Connection} over a TCP socket: one line of UTF-8 text per message. A background thread reads and queues,
 * so the game loop only polls. Sends write straight to the socket, which suits the short messages games exchange.
 */
public final class TcpConnection implements Connection {

    /** The longest message accepted. A longer line ends the connection instead of growing without bound. */
    public static final int MAX_MESSAGE = 64 * 1024;

    private final Socket socket;
    private final Writer writer;
    private final Queue<String> inbox = new ConcurrentLinkedQueue<>();
    private volatile boolean open = true;

    /** Connects to a listening game, giving up after {@code timeoutMillis}. Blocks, so call it off the game loop. */
    public static TcpConnection connect(InetSocketAddress address, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(address, timeoutMillis);
            return new TcpConnection(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** Wraps a socket that is already connected, such as one a {@link TcpListener} accepted. */
    public TcpConnection(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        Thread thread = new Thread(() -> read(reader), "tcp-read " + socket.getRemoteSocketAddress());
        thread.setDaemon(true);
        thread.start();
    }

    private void read(BufferedReader reader) {
        StringBuilder line = new StringBuilder();
        try {
            for (int c = reader.read(); c >= 0; c = reader.read()) {
                if (c == '\n') {
                    inbox.add(line.toString());
                    line.setLength(0);
                } else if (c != '\r') {
                    if (line.length() >= MAX_MESSAGE) {
                        break;
                    }
                    line.append((char) c);
                }
            }
        } catch (IOException ignored) {
            // The socket closed or failed; either way the connection is over.
        } finally {
            close();
        }
    }

    @Override
    public synchronized void send(String message) {
        Connection.requireOneLine(message);
        if (!open) {
            return;
        }
        try {
            writer.write(message);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            close();
        }
    }

    @Override
    public String poll() {
        return inbox.poll();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) socket.getRemoteSocketAddress();
    }
}
