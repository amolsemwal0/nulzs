package org.zeronura.engine.platform;

import java.util.List;
import java.util.Map;

/**
 * What a game gets from the store or online service it ships on — Steam today, another one later: who the player is,
 * their friends, messages to other players, invitations, achievements, files kept in the cloud, and what friends see
 * the player doing.
 *
 * <p>Games talk only to this interface, so moving to another platform means one more implementation rather than changes
 * all through the game. {@link org.zeronura.engine.platform.steam.SteamPlatform} is Steam's; {@link LocalPlatform} runs
 * with no service behind it, for development and tests.
 *
 * <p>Call everything from the game loop's thread, and {@link #update} once a frame.
 */
public interface Platform extends AutoCloseable {

    /** A message from another player. */
    record Message(String from, byte[] data) {
    }

    /** The player accepted, through the platform's own interface, a friend's invitation to this game. */
    record JoinRequest(String from, String connect) {
    }

    /** The platform's own screens, drawn over the game. */
    enum Overlay { FRIENDS, INVITE }

    /** Shown to the player, e.g. "Steam". */
    String name();

    PlatformUser self();

    /** Handles whatever the platform has to report. Call once a frame. */
    void update();

    /** The player's friends, playing this game first, then online, then away, then offline. */
    List<PlatformUser> friends();

    /** Sends a message to another player, reliably and in order, on a channel their game reads with {@link #receive}. */
    boolean send(String userId, int channel, byte[] data);

    /** The next message waiting on a channel, or null. */
    Message receive(int channel);

    /** The next player whose messages stopped getting through, or null. */
    String pollLostPlayer();

    /** Stops talking to a player until one side sends again. */
    void endSession(String userId);

    /** Invites a friend to this game through the platform; {@code connect} reaches them in a {@link JoinRequest}. */
    boolean inviteToGame(String userId, String connect);

    /** The next invitation the player accepted through the platform, or null. */
    JoinRequest pollJoinRequest();

    /** Opens one of the platform's own screens over the game; false if it has none. */
    boolean openOverlay(Overlay overlay);

    /**
     * Shows friends what the player is doing. {@code status} names one of the game's statuses — Steam shows the
     * {@code #Status_<status>} text from the game's rich presence localization — and {@code details} fill in its blanks.
     */
    void setPresence(String status, Map<String, String> details);

    void clearPresence();

    Achievements achievements();

    CloudFiles cloud();

    @Override
    void close();

    /** Achievements, by the ids they were given on the platform. */
    interface Achievements {

        /** Unlocks an achievement; true if it is unlocked now. */
        boolean unlock(String id);

        boolean isUnlocked(String id);

        /** Shows the player how far along an achievement they are, e.g. 12 of 25. */
        void showProgress(String id, int current, int max);

        /** Locks an achievement again, for testing. */
        void reset(String id);
    }

    /** Files the platform keeps for the player online, so they follow them between computers. */
    interface CloudFiles {

        /** Whether files written now reach the cloud: the player and the game both have it turned on. */
        boolean isEnabled();

        boolean write(String name, byte[] data);

        /** A file's contents, or null if there is none. */
        byte[] read(String name);

        /** When a file was last written, in seconds since 1970, or -1 if there is none. */
        long modified(String name);
    }
}
