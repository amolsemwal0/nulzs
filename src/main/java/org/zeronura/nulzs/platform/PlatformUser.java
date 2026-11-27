package org.zeronura.nulzs.platform;

/**
 * A player on a {@link Platform}.
 *
 * @param id     the platform's id for them, the same every time they play
 * @param status whether they are around, and whether they are playing this game
 */
public record PlatformUser(String id, String name, Status status) {

    /** In the order friend lists show them. */
    public enum Status {
        IN_THIS_GAME, ONLINE, AWAY, OFFLINE;

        public boolean isOnline() {
            return this != OFFLINE;
        }
    }
}
