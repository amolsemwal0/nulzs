package org.zeronura.nulzs.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link Platform} with no online service behind it, for development and tests. Players made on the same
 * {@link Network} in one process are each other's friends, all playing this game, and can message and invite each other.
 * Achievements and cloud files are kept in memory, or in a folder when one is given.
 */
public final class LocalPlatform implements Platform {

    /** Players who can reach each other. */
    public static final class Network {
        private final Map<String, LocalPlatform> players = new LinkedHashMap<>();
    }

    private final Network network;
    private final PlatformUser self;
    private final Path folder;
    private final Map<Integer, Deque<Message>> inboxes = new HashMap<>();
    private final Deque<String> lost = new ArrayDeque<>();
    private final Deque<JoinRequest> joins = new ArrayDeque<>();
    private final Map<String, String> presence = new LinkedHashMap<>();
    private final Set<String> unlocked = new LinkedHashSet<>();
    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, Long> fileTimes = new HashMap<>();
    private final Achievements achievements = new LocalAchievements();
    private final CloudFiles cloud = new LocalCloud();
    private boolean closed;

    /** A player on their own, with nobody to reach. */
    public LocalPlatform(String name, Path folder) {
        this(new Network(), "local", name, folder);
    }

    /** @param folder where achievements and cloud files are kept between runs, or null to keep them in memory */
    public LocalPlatform(Network network, String id, String name, Path folder) {
        if (network.players.containsKey(id)) {
            throw new IllegalArgumentException("there is already a player " + id + " on this network");
        }
        this.network = network;
        this.self = new PlatformUser(id, name, PlatformUser.Status.IN_THIS_GAME);
        this.folder = folder;
        network.players.put(id, this);
        if (folder != null && Files.isRegularFile(achievementFile())) {
            try {
                Files.readAllLines(achievementFile()).stream().map(String::trim).filter(line -> !line.isEmpty()).forEach(unlocked::add);
            } catch (IOException e) {
                System.err.println("[LocalPlatform] Could not read achievements: " + e.getMessage());
            }
        }
    }

    @Override
    public String name() {
        return "Local";
    }

    @Override
    public PlatformUser self() {
        return self;
    }

    @Override
    public void update() {
    }

    @Override
    public List<PlatformUser> friends() {
        List<PlatformUser> friends = new ArrayList<>();
        for (LocalPlatform player : network.players.values()) {
            if (player != this) {
                friends.add(player.self);
            }
        }
        return friends;
    }

    @Override
    public boolean send(String userId, int channel, byte[] data) {
        LocalPlatform target = network.players.get(userId);
        if (closed || target == null || target.closed) {
            lost.add(userId);
            return false;
        }
        target.inboxes.computeIfAbsent(channel, c -> new ArrayDeque<>()).add(new Message(self.id(), data.clone()));
        return true;
    }

    @Override
    public Message receive(int channel) {
        Deque<Message> inbox = inboxes.get(channel);
        return inbox == null ? null : inbox.poll();
    }

    @Override
    public String pollLostPlayer() {
        return lost.poll();
    }

    @Override
    public void endSession(String userId) {
    }

    @Override
    public boolean inviteToGame(String userId, String connect) {
        LocalPlatform target = network.players.get(userId);
        if (target == null) {
            return false;
        }
        target.joins.add(new JoinRequest(self.id(), connect));
        return true;
    }

    @Override
    public JoinRequest pollJoinRequest() {
        return joins.poll();
    }

    @Override
    public boolean openOverlay(Overlay overlay) {
        return false;
    }

    @Override
    public void setPresence(String status, Map<String, String> details) {
        presence.clear();
        presence.putAll(details);
        presence.put("status", status);
    }

    @Override
    public void clearPresence() {
        presence.clear();
    }

    /** What was last set for a presence key, or null. */
    public String presence(String key) {
        return presence.get(key);
    }

    @Override
    public Achievements achievements() {
        return achievements;
    }

    @Override
    public CloudFiles cloud() {
        return cloud;
    }

    @Override
    public void close() {
        closed = true;
        network.players.remove(self.id(), this);
    }

    private Path achievementFile() {
        return folder.resolve("achievements.txt");
    }

    private void saveAchievements() {
        if (folder == null) {
            return;
        }
        try {
            Files.createDirectories(folder);
            Files.write(achievementFile(), unlocked);
        } catch (IOException e) {
            System.err.println("[LocalPlatform] Could not save achievements: " + e.getMessage());
        }
    }

    private final class LocalAchievements implements Achievements {
        @Override
        public boolean unlock(String id) {
            if (unlocked.add(id)) {
                saveAchievements();
            }
            return true;
        }

        @Override
        public boolean isUnlocked(String id) {
            return unlocked.contains(id);
        }

        @Override
        public void showProgress(String id, int current, int max) {
        }

        @Override
        public void reset(String id) {
            if (unlocked.remove(id)) {
                saveAchievements();
            }
        }
    }

    private final class LocalCloud implements CloudFiles {
        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean write(String name, byte[] data) {
            if (folder == null) {
                files.put(name, data.clone());
                fileTimes.put(name, System.currentTimeMillis() / 1000L);
                return true;
            }
            try {
                Path file = folder.resolve("cloud").resolve(name);
                Files.createDirectories(file.getParent());
                Files.write(file, data);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public byte[] read(String name) {
            if (folder == null) {
                byte[] data = files.get(name);
                return data == null ? null : data.clone();
            }
            Path file = folder.resolve("cloud").resolve(name);
            try {
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public long modified(String name) {
            if (folder == null) {
                return fileTimes.getOrDefault(name, -1L);
            }
            Path file = folder.resolve("cloud").resolve(name);
            try {
                return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() / 1000L : -1L;
            } catch (IOException e) {
                return -1L;
            }
        }
    }
}
