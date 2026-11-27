package org.zeronura.engine.studio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Runs a program beside the editor — the game, Maven, the packaging script — with its output going to the Console. One
 * at a time per runner; starting another stops the last.
 */
final class ProcessRunner {

    private final ConsoleLog console;
    private final String source;
    private volatile Process process;

    ProcessRunner(ConsoleLog console, String source) {
        this.console = console;
        this.source = source;
    }

    boolean running() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Starts {@code command} in {@code folder}; {@code finished} gets its exit code, on another thread. */
    void start(List<String> command, Path folder, IntConsumer finished) {
        stop();
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true);
        try {
            Process started = builder.start();
            process = started;
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(started.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        console.add(ConsoleLog.levelOf(line, false), source, line);
                    }
                } catch (IOException ignored) {
                    // the process went away
                }
                try {
                    int code = started.waitFor();
                    if (finished != null) {
                        finished.accept(code);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "editor-" + source.toLowerCase(java.util.Locale.ROOT));
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            console.error("Could not start " + String.join(" ", command) + ": " + e.getMessage());
            if (finished != null) {
                finished.accept(-1);
            }
        }
    }

    void stop() {
        Process p = process;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
        process = null;
    }
}
