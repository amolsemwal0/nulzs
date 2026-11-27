package org.zeronura.nulzs.studio;

import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.debug.DebugMode;
import org.zeronura.nulzs.debug.DebugReport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * The editor's end of a game's debug mode: a port on this computer the game sends its readings to, half a second
 * at a time ({@link DebugMode}), kept here for the Debug panel. One game at a time; starting another forgets the last.
 */
final class DebugLink {

    enum State { IDLE, WAITING, LIVE, CLOSED }

    /** Frame times kept for the graph: about fifteen seconds at 60 fps. */
    static final int HISTORY = 900;

    private ServerSocket server;
    private volatile Socket game;
    private volatile Map<String, Object> readings;
    private volatile State state = State.IDLE;
    private volatile String gameName = "";
    private final float[] history = new float[HISTORY];
    private int historyCount;
    private int historyNext;

    /** The port games send to, opened the first time. */
    synchronized int port() throws IOException {
        if (server == null) {
            ServerSocket socket = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            server = socket;
            Thread accept = new Thread(() -> acceptLoop(socket), "editor-debug-link");
            accept.setDaemon(true);
            accept.start();
        }
        return server.getLocalPort();
    }

    /** A game is starting in debug mode: forget the last one's readings. */
    synchronized void starting(String name) {
        closeGame();
        readings = null;
        historyCount = 0;
        historyNext = 0;
        gameName = name;
        state = State.WAITING;
    }

    /** The game's process ended. */
    void gameEnded() {
        closeGame();
        if (state != State.IDLE) {
            state = State.CLOSED;
        }
    }

    State state() {
        return state;
    }

    String gameName() {
        return gameName;
    }

    /** The latest readings, or null before the first. */
    Map<String, Object> readings() {
        return readings;
    }

    /** The frame times kept, oldest first, in ms. */
    synchronized float[] history() {
        float[] out = new float[historyCount];
        int start = (historyNext - historyCount + HISTORY) % HISTORY;
        for (int i = 0; i < historyCount; i++) {
            out[i] = history[(start + i) % HISTORY];
        }
        return out;
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket next = socket.accept();
                closeGame();
                game = next;
                Thread reader = new Thread(() -> read(next), "editor-debug-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void read(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    take(Json.parseObject(line));
                } catch (IllegalArgumentException e) {
                    // a line cut short as the game closed
                }
            }
        } catch (IOException ignored) {
            // the game went away
        }
        if (game == socket) {
            state = State.CLOSED;
        }
    }

    private synchronized void take(Map<String, Object> next) {
        readings = next;
        state = State.LIVE;
        for (Object time : DebugReport.list(next, "frameTimes")) {
            history[historyNext] = (float) DebugReport.number(time);
            historyNext = (historyNext + 1) % HISTORY;
            historyCount = Math.min(HISTORY, historyCount + 1);
        }
    }

    private void closeGame() {
        Socket socket = game;
        game = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    void close() {
        closeGame();
        synchronized (this) {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
                server = null;
            }
        }
    }

    /**
     * The arguments that start a game in debug mode, before its main class: the readings sent here, and with
     * {@code traceCalls} the agent that counts its OpenGL calls ({@code org.zeronura.nulzs.debug.GlCallAgent}).
     */
    List<String> javaArguments(boolean traceCalls) throws IOException {
        List<String> arguments = new java.util.ArrayList<>(List.of("-D" + DebugMode.PROPERTY + "=true",
                "-D" + DebugMode.PORT_PROPERTY + "=" + port(), "-XX:FlightRecorderOptions:stackdepth=128"));
        if (traceCalls) {
            arguments.add("-javaagent:" + agentJar());
        }
        return arguments;
    }

    /**
     * The agent's jar: a manifest naming the agent, nothing more, as the agent's class is on the game's class path
     * already. Made in the temporary folder each time, so it always names the class this editor has.
     */
    private static Path agentJar() throws IOException {
        Path jar = Path.of(System.getProperty("java.io.tmpdir"), "gameengine-gl-call-agent.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), "org.zeronura.nulzs.debug.GlCallAgent");
        Path partial = Files.createTempFile(jar.getParent(), "gameengine-gl-call-agent", ".jar");
        try (OutputStream file = Files.newOutputStream(partial); JarOutputStream out = new JarOutputStream(file, manifest)) {
            out.finish();
        }
        Files.move(partial, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }
}
