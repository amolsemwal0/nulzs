package org.zeronura.nulzs.platform.steam;

import org.zeronura.nulzs.platform.Platform;
import org.zeronura.nulzs.platform.PlatformUser;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

/**
 * Steam as a {@link Platform}, through the Steamworks SDK's own library ({@code steam_api64.dll},
 * {@code libsteam_api.so} or {@code libsteam_api.dylib}), called directly with Java's foreign function API.
 *
 * <ul>
 *   <li><b>Friends</b> are the player's Steam friends; one running a game with this App ID shows as playing it.</li>
 *   <li><b>Messages</b> go through ISteamNetworkingMessages, which reaches a Steam user wherever they are, through
 *   Valve's relays when there is no direct route. Only a friend can open a session with this player.</li>
 *   <li><b>Invitations</b> use Steam's own invite ("Join game"), and the invite overlay.</li>
 *   <li><b>Presence</b> is Steam rich presence: {@code steam_display} names a token from the localization file uploaded
 *   on Steamworks, and other keys fill it in.</li>
 *   <li><b>Achievements</b> and <b>cloud files</b> use the achievements and Steam Cloud set up for the App ID.</li>
 * </ul>
 *
 * <p>Start it once with {@link #start}, call {@link #update} every frame, and {@link #close} when the game ends.
 */
public final class SteamPlatform implements Platform {

    /** How {@link #start} went. */
    public sealed interface Start {

        record Started(SteamPlatform platform) implements Start {
        }

        /** The game was not launched by Steam. Steam is launching it again, so this copy should quit now. */
        record Relaunching() implements Start {
        }

        record Failed(String reason) implements Start {
        }
    }

    private static final long FRIENDS_REFRESH_NANOS = 2_000_000_000L;

    private final SteamApi api;
    private final int appId;
    private final PlatformUser self;
    private final Map<Integer, Deque<Message>> inboxes = new HashMap<>();
    private final Deque<String> lost = new ArrayDeque<>();
    private final Deque<JoinRequest> joins = new ArrayDeque<>();
    private final Achievements achievements = new SteamAchievements();
    private final CloudFiles cloud = new SteamCloud();
    private List<PlatformUser> friends = List.of();
    private long friendsLoadedAt;
    private boolean friendsLoaded;
    private boolean closed;

    /**
     * Connects to the Steam client.
     *
     * @param appId      the game's Steam App ID; 480 is Valve's public test app
     * @param developer  running from a development build: the App ID is handed to Steam directly, as a
     *                   {@code steam_appid.txt} would, instead of the game being relaunched through the Steam client
     * @param searchDirs folders to look for Steam's library in, in order; {@code -Dsteam.library=<file>} overrides them
     */
    public static Start start(int appId, boolean developer, List<Path> searchDirs) {
        Path library = findLibrary(searchDirs);
        if (library == null) {
            return new Start.Failed("Steam's library, " + libraryName() + ", was not found. Looked in " + searchDirs + ".");
        }
        try {
            if (developer) {
                SteamApi.setEnvironment("SteamAppId", String.valueOf(appId));
                SteamApi.setEnvironment("SteamGameId", String.valueOf(appId));
            }
            SteamApi api = SteamApi.load(library);
            if (!developer && api.restartAppIfNecessary(appId)) {
                return new Start.Relaunching();
            }
            SteamApi.Init init = api.init();
            return switch (init.result()) {
                case SteamApi.INIT_OK -> new Start.Started(new SteamPlatform(api, appId));
                case SteamApi.INIT_NO_STEAM_CLIENT -> new Start.Failed("Steam isn't running. Start Steam, sign in, and try again.");
                case SteamApi.INIT_VERSION_MISMATCH -> new Start.Failed("Steam needs to be updated. Restart Steam to update it, then try again.");
                default -> new Start.Failed("Steam couldn't be started: " + init.message());
            };
        } catch (RuntimeException e) {
            return new Start.Failed("Steam couldn't be started: " + e.getMessage());
        }
    }

    /** The file name of Steam's library on this computer. */
    public static String libraryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return "steam_api64.dll";
        }
        return os.contains("mac") ? "libsteam_api.dylib" : "libsteam_api.so";
    }

    /** The folder under the Steamworks SDK's {@code redistributable_bin} that holds this computer's library. */
    public static String sdkFolder() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return "win64";
        }
        if (os.contains("mac")) {
            return "osx";
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64") ? "linuxarm64" : "linux64";
    }

    static Path findLibrary(List<Path> searchDirs) {
        String explicit = System.getProperty("steam.library");
        if (explicit != null && Files.isRegularFile(Path.of(explicit))) {
            return Path.of(explicit).toAbsolutePath();
        }
        for (Path dir : searchDirs) {
            Path candidate = dir.resolve(libraryName());
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    private SteamPlatform(SteamApi api, int appId) {
        this.api = api;
        this.appId = appId;
        this.self = new PlatformUser(Long.toUnsignedString(api.steamId()), api.personaName(), PlatformUser.Status.IN_THIS_GAME);
    }

    public int appId() {
        return appId;
    }

    @Override
    public String name() {
        return "Steam";
    }

    @Override
    public PlatformUser self() {
        return self;
    }

    @Override
    public void update() {
        if (!closed) {
            api.runCallbacks(this::onCallback);
        }
    }

    private void onCallback(int id, MemorySegment data) {
        switch (id) {
            case SteamApi.CALLBACK_SESSION_REQUEST -> {
                long from = data.get(JAVA_LONG_UNALIGNED, SteamApi.IDENTITY_STEAM_ID);
                // Only friends may talk to this player. Anyone else's request is left unanswered and lapses.
                if (api.isFriend(from)) {
                    api.acceptSession(from);
                }
            }
            case SteamApi.CALLBACK_SESSION_FAILED ->
                    lost.add(Long.toUnsignedString(data.get(JAVA_LONG_UNALIGNED, SteamApi.IDENTITY_STEAM_ID)));
            case SteamApi.CALLBACK_JOIN_REQUESTED ->
                    joins.add(new JoinRequest(Long.toUnsignedString(data.get(JAVA_LONG_UNALIGNED, 0)), data.getString(8)));
            default -> {
            }
        }
    }

    @Override
    public List<PlatformUser> friends() {
        long now = System.nanoTime();
        if (closed || friendsLoaded && now - friendsLoadedAt < FRIENDS_REFRESH_NANOS) {
            return friends;
        }
        List<PlatformUser> list = new ArrayList<>();
        int count = api.friendCount();
        for (int i = 0; i < count; i++) {
            long id = api.friendByIndex(i);
            int state = api.friendState(id);
            PlatformUser.Status status = api.friendAppId(id) == appId ? PlatformUser.Status.IN_THIS_GAME
                    : state == SteamApi.PERSONA_OFFLINE ? PlatformUser.Status.OFFLINE
                    : state == SteamApi.PERSONA_AWAY || state == SteamApi.PERSONA_SNOOZE ? PlatformUser.Status.AWAY
                    : PlatformUser.Status.ONLINE;
            list.add(new PlatformUser(Long.toUnsignedString(id), api.friendName(id), status));
        }
        list.sort(Comparator.comparing(PlatformUser::status).thenComparing(user -> user.name().toLowerCase(Locale.ROOT)));
        friends = List.copyOf(list);
        friendsLoaded = true;
        friendsLoadedAt = now;
        return friends;
    }

    @Override
    public boolean send(String userId, int channel, byte[] data) {
        long id = parse(userId);
        return !closed && id != 0 && api.send(id, data, channel);
    }

    @Override
    public Message receive(int channel) {
        Deque<Message> inbox = inboxes.computeIfAbsent(channel, c -> new ArrayDeque<>());
        if (inbox.isEmpty() && !closed) {
            for (SteamApi.Received received : api.receive(channel)) {
                inbox.add(new Message(Long.toUnsignedString(received.from()), received.data()));
            }
        }
        return inbox.poll();
    }

    @Override
    public String pollLostPlayer() {
        return lost.poll();
    }

    @Override
    public void endSession(String userId) {
        long id = parse(userId);
        if (!closed && id != 0) {
            api.closeSession(id);
        }
    }

    @Override
    public boolean inviteToGame(String userId, String connect) {
        long id = parse(userId);
        return !closed && id != 0 && api.inviteUserToGame(id, connect);
    }

    @Override
    public JoinRequest pollJoinRequest() {
        return joins.poll();
    }

    @Override
    public boolean openOverlay(Overlay overlay) {
        if (closed) {
            return false;
        }
        switch (overlay) {
            case FRIENDS -> api.activateOverlay("friends");
            case INVITE -> api.activateInviteDialog("join:" + self.id());
        }
        return true;
    }

    @Override
    public void setPresence(String status, Map<String, String> details) {
        if (!closed) {
            details.forEach((key, value) -> api.setRichPresence(key, value == null ? "" : value));
            api.setRichPresence("steam_display", "#Status_" + status);
        }
    }

    @Override
    public void clearPresence() {
        if (!closed) {
            api.clearRichPresence();
        }
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
        if (!closed) {
            closed = true;
            api.shutdown();
        }
    }

    private static long parse(String userId) {
        try {
            return Long.parseUnsignedLong(userId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private final class SteamAchievements implements Achievements {
        @Override
        public boolean unlock(String id) {
            if (closed) {
                return false;
            }
            boolean set = api.setAchievement(id);
            api.storeStats();
            return set;
        }

        @Override
        public boolean isUnlocked(String id) {
            return !closed && api.achievement(id);
        }

        @Override
        public void showProgress(String id, int current, int max) {
            if (!closed) {
                api.indicateProgress(id, current, max);
            }
        }

        @Override
        public void reset(String id) {
            if (!closed) {
                api.clearAchievement(id);
                api.storeStats();
            }
        }
    }

    private final class SteamCloud implements CloudFiles {
        @Override
        public boolean isEnabled() {
            return !closed && api.cloudEnabled();
        }

        @Override
        public boolean write(String name, byte[] data) {
            return !closed && api.fileWrite(name, data);
        }

        @Override
        public byte[] read(String name) {
            return closed ? null : api.fileRead(name);
        }

        @Override
        public long modified(String name) {
            return closed ? -1L : api.fileTimestamp(name);
        }
    }
}
