package org.zeronura.nulzs.platform;

import org.zeronura.nulzs.net.Connection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link Connection}s to other players over a {@link Platform}'s messages, so code written for a socket — a trade, a
 * battle — runs unchanged over whatever the platform uses to reach people.
 *
 * <p>Everything travels on one channel, so it arrives in the order it was sent: lines on a player's link, the end of a
 * link, and control lines outside any link (invitations and their answers), which go to the {@link ControlHandler}.
 * A handler that opens a link as it reads an answer receives the link's first lines in the same {@link #update}.
 * Lines for a player with no open link are dropped.
 */
public final class PeerLinks {

    private static final byte CONTROL = 'X';
    private static final byte LINE = 'L';
    private static final byte CLOSE = 'C';

    /** Receives control lines: whatever the game sends outside a link. */
    @FunctionalInterface
    public interface ControlHandler {
        void handle(String from, String line);
    }

    private final Platform platform;
    private final int channel;
    private final ControlHandler control;
    private final Map<String, Link> links = new HashMap<>();

    public PeerLinks(Platform platform, int channel, ControlHandler control) {
        this.platform = platform;
        this.channel = channel;
        this.control = control;
    }

    /** Sends a control line to another player. */
    public boolean sendControl(String userId, String line) {
        return platform.send(userId, channel, encode(CONTROL, line));
    }

    /** The open link to a player, opening one if there is none. */
    public Connection open(String userId) {
        Link link = links.get(userId);
        if (link == null || !link.open) {
            link = new Link(userId);
            links.put(userId, link);
        }
        return link;
    }

    /** Hands out what has arrived. Call once a frame, after {@link Platform#update}. */
    public void update() {
        for (Platform.Message message = platform.receive(channel); message != null; message = platform.receive(channel)) {
            byte[] data = message.data();
            if (data.length == 0) {
                continue;
            }
            String text = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
            Link link = links.get(message.from());
            switch (data[0]) {
                case CONTROL -> control.handle(message.from(), text);
                case LINE -> {
                    if (link != null && link.open) {
                        link.inbox.add(text);
                    }
                }
                case CLOSE -> {
                    if (link != null) {
                        link.open = false;
                    }
                }
                default -> {
                }
            }
        }
        for (String lost = platform.pollLostPlayer(); lost != null; lost = platform.pollLostPlayer()) {
            Link link = links.get(lost);
            if (link != null) {
                link.open = false;
            }
        }
        links.values().removeIf(link -> !link.open && link.inbox.isEmpty());
    }

    /** Closes every link, telling the other players. */
    public void closeAll() {
        for (Link link : links.values().toArray(Link[]::new)) {
            link.close();
        }
        links.clear();
    }

    private static byte[] encode(byte kind, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[body.length + 1];
        data[0] = kind;
        System.arraycopy(body, 0, data, 1, body.length);
        return data;
    }

    private final class Link implements Connection {
        private final String userId;
        private final Deque<String> inbox = new ArrayDeque<>();
        private boolean open = true;

        Link(String userId) {
            this.userId = userId;
        }

        @Override
        public void send(String message) {
            if (open && !platform.send(userId, channel, encode(LINE, message))) {
                open = false;
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
            if (open) {
                open = false;
                platform.send(userId, channel, encode(CLOSE, ""));
            }
        }
    }
}
