package org.zeronura.nulzs.platform.steam;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.*;

/**
 * The parts of the Steamworks SDK's flat C API the engine uses ({@code steam_api_flat.h}), bound with Java's foreign
 * function API. Each call is a method handle straight to a function in Steam's own library, so there is no wrapper
 * library to build or keep in step with the SDK.
 *
 * <p>Structures are read at the offsets the SDK's headers give, noted beside each. They hold on every desktop platform
 * Steam supports: the packing differs between Windows and the rest, but not at any field read here.
 *
 * <p>Call it from one thread, as Steam expects.
 */
final class SteamApi {

    static final int INIT_OK = 0;
    static final int INIT_NO_STEAM_CLIENT = 2;
    static final int INIT_VERSION_MISMATCH = 3;

    /** GameRichPresenceJoinRequested_t: the friend's CSteamID at 0, the connect string (256 chars) at 8. */
    static final int CALLBACK_JOIN_REQUESTED = 300 + 37;
    /** SteamNetworkingMessagesSessionRequest_t: the remote SteamNetworkingIdentity at 0. */
    static final int CALLBACK_SESSION_REQUEST = 1250 + 1;
    /** SteamNetworkingMessagesSessionFailed_t: a SteamNetConnectionInfo_t, which starts with the remote identity. */
    static final int CALLBACK_SESSION_FAILED = 1250 + 2;

    static final int PERSONA_OFFLINE = 0;
    static final int PERSONA_AWAY = 3;
    static final int PERSONA_SNOOZE = 4;

    /** SteamNetworkingIdentity is packed to 1 byte: a type (int), a size (int), then a union starting with the SteamID. */
    static final long IDENTITY_STEAM_ID = 8;
    private static final long IDENTITY_BYTES = 136;
    // SteamNetworkingMessage_t: data pointer at 0, its size (int) at 8, connection handle at 12, sender identity at 16.
    private static final long MESSAGE_DATA = 0;
    private static final long MESSAGE_DATA_SIZE = 8;
    private static final long MESSAGE_SENDER = 16 + IDENTITY_STEAM_ID;
    private static final long MESSAGE_BYTES = 200;
    // CallbackMsg_t: user handle (int) at 0, callback id (int) at 4, structure pointer at 8, its size (int) at 16.
    private static final long CALLBACK_ID = 4;
    private static final long CALLBACK_DATA = 8;
    private static final long CALLBACK_DATA_SIZE = 16;
    private static final long CALLBACK_BYTES = 24;
    /** FriendGameInfo_t: the CGameID (the App ID in its low 24 bits) at 0, 24 bytes in all. */
    private static final long GAME_INFO_BYTES = 24;
    private static final int FRIEND_FLAG_IMMEDIATE = 0x04;
    private static final int SEND_RELIABLE = 8;
    private static final int SEND_AUTO_RESTART_BROKEN_SESSION = 32;
    private static final int RESULT_OK = 1;
    private static final int MAX_MESSAGES_PER_CALL = 32;
    private static final AddressLayout POINTER = ADDRESS_UNALIGNED;

    record Init(int result, String message) {
    }

    record Received(long from, byte[] data) {
    }

    @FunctionalInterface
    interface CallbackHandler {
        /** {@code data} is only valid during the call. */
        void handle(int id, MemorySegment data);
    }

    @FunctionalInterface
    private interface NativeCall<T> {
        T run() throws Throwable;
    }

    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup library;

    private final MethodHandle initFlat;
    private final MethodHandle shutdown;
    private final MethodHandle restartAppIfNecessary;
    private final MethodHandle getPipe;
    private final MethodHandle dispatchInit;
    private final MethodHandle dispatchRunFrame;
    private final MethodHandle dispatchNext;
    private final MethodHandle dispatchFree;
    private final MethodHandle steamUser;
    private final MethodHandle steamFriends;
    private final MethodHandle steamUserStats;
    private final MethodHandle steamRemoteStorage;
    private final MethodHandle steamNetworkingMessages;
    private final MethodHandle getSteamId;
    private final MethodHandle getPersonaName;
    private final MethodHandle getFriendCount;
    private final MethodHandle getFriendByIndex;
    private final MethodHandle getFriendPersonaName;
    private final MethodHandle getFriendPersonaState;
    private final MethodHandle getFriendGamePlayed;
    private final MethodHandle hasFriend;
    private final MethodHandle setRichPresence;
    private final MethodHandle clearRichPresence;
    private final MethodHandle activateOverlay;
    private final MethodHandle activateInviteDialog;
    private final MethodHandle inviteUserToGame;
    private final MethodHandle identitySetSteamId;
    private final MethodHandle sendMessageToUser;
    private final MethodHandle receiveMessagesOnChannel;
    private final MethodHandle acceptSessionWithUser;
    private final MethodHandle closeSessionWithUser;
    private final MethodHandle releaseMessage;
    private final MethodHandle setAchievement;
    private final MethodHandle getAchievement;
    private final MethodHandle clearAchievement;
    private final MethodHandle storeStats;
    private final MethodHandle indicateAchievementProgress;
    private final MethodHandle fileWrite;
    private final MethodHandle fileRead;
    private final MethodHandle fileExists;
    private final MethodHandle getFileSize;
    private final MethodHandle getFileTimestamp;
    private final MethodHandle cloudEnabledForAccount;
    private final MethodHandle cloudEnabledForApp;

    private final MemorySegment callbackMessage = Arena.global().allocate(CALLBACK_BYTES);
    private final MemorySegment messagePointers = Arena.global().allocate(ADDRESS, MAX_MESSAGES_PER_CALL);
    private final MemorySegment identity = Arena.global().allocate(IDENTITY_BYTES);
    private final MemorySegment gameInfo = Arena.global().allocate(GAME_INFO_BYTES);
    private final MemorySegment flag = Arena.global().allocate(1);

    private MemorySegment user;
    private MemorySegment friends;
    private MemorySegment userStats;
    private MemorySegment remoteStorage;
    private MemorySegment networkingMessages;
    private int pipe;

    /** Loads Steam's library and binds every function this class calls; fails if the library lacks any of them. */
    static SteamApi load(Path library) {
        return new SteamApi(SymbolLookup.libraryLookup(library, Arena.global()));
    }

    private SteamApi(SymbolLookup library) {
        this.library = library;
        initFlat = bind("SteamAPI_InitFlat", JAVA_INT, ADDRESS);
        shutdown = bind("SteamAPI_Shutdown", null);
        restartAppIfNecessary = bind("SteamAPI_RestartAppIfNecessary", JAVA_BOOLEAN, JAVA_INT);
        getPipe = bind("SteamAPI_GetHSteamPipe", JAVA_INT);
        dispatchInit = bind("SteamAPI_ManualDispatch_Init", null);
        dispatchRunFrame = bind("SteamAPI_ManualDispatch_RunFrame", null, JAVA_INT);
        dispatchNext = bind("SteamAPI_ManualDispatch_GetNextCallback", JAVA_BOOLEAN, JAVA_INT, ADDRESS);
        dispatchFree = bind("SteamAPI_ManualDispatch_FreeLastCallback", null, JAVA_INT);

        steamUser = bind("SteamAPI_SteamUser_v023", ADDRESS);
        steamFriends = bind("SteamAPI_SteamFriends_v018", ADDRESS);
        steamUserStats = bind("SteamAPI_SteamUserStats_v013", ADDRESS);
        steamRemoteStorage = bind("SteamAPI_SteamRemoteStorage_v016", ADDRESS);
        steamNetworkingMessages = bind("SteamAPI_SteamNetworkingMessages_SteamAPI_v002", ADDRESS);

        getSteamId = bind("SteamAPI_ISteamUser_GetSteamID", JAVA_LONG, ADDRESS);
        getPersonaName = bind("SteamAPI_ISteamFriends_GetPersonaName", ADDRESS, ADDRESS);
        getFriendCount = bind("SteamAPI_ISteamFriends_GetFriendCount", JAVA_INT, ADDRESS, JAVA_INT);
        getFriendByIndex = bind("SteamAPI_ISteamFriends_GetFriendByIndex", JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT);
        getFriendPersonaName = bind("SteamAPI_ISteamFriends_GetFriendPersonaName", ADDRESS, ADDRESS, JAVA_LONG);
        getFriendPersonaState = bind("SteamAPI_ISteamFriends_GetFriendPersonaState", JAVA_INT, ADDRESS, JAVA_LONG);
        getFriendGamePlayed = bind("SteamAPI_ISteamFriends_GetFriendGamePlayed", JAVA_BOOLEAN, ADDRESS, JAVA_LONG, ADDRESS);
        hasFriend = bind("SteamAPI_ISteamFriends_HasFriend", JAVA_BOOLEAN, ADDRESS, JAVA_LONG, JAVA_INT);
        setRichPresence = bind("SteamAPI_ISteamFriends_SetRichPresence", JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
        clearRichPresence = bind("SteamAPI_ISteamFriends_ClearRichPresence", null, ADDRESS);
        activateOverlay = bind("SteamAPI_ISteamFriends_ActivateGameOverlay", null, ADDRESS, ADDRESS);
        activateInviteDialog = bind("SteamAPI_ISteamFriends_ActivateGameOverlayInviteDialogConnectString", null, ADDRESS, ADDRESS);
        inviteUserToGame = bind("SteamAPI_ISteamFriends_InviteUserToGame", JAVA_BOOLEAN, ADDRESS, JAVA_LONG, ADDRESS);

        identitySetSteamId = bind("SteamAPI_SteamNetworkingIdentity_SetSteamID", null, ADDRESS, JAVA_LONG);
        sendMessageToUser = bind("SteamAPI_ISteamNetworkingMessages_SendMessageToUser", JAVA_INT,
                ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT);
        receiveMessagesOnChannel = bind("SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel", JAVA_INT,
                ADDRESS, JAVA_INT, ADDRESS, JAVA_INT);
        acceptSessionWithUser = bind("SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser", JAVA_BOOLEAN, ADDRESS, ADDRESS);
        closeSessionWithUser = bind("SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser", JAVA_BOOLEAN, ADDRESS, ADDRESS);
        releaseMessage = bind("SteamAPI_SteamNetworkingMessage_t_Release", null, ADDRESS);

        setAchievement = bind("SteamAPI_ISteamUserStats_SetAchievement", JAVA_BOOLEAN, ADDRESS, ADDRESS);
        getAchievement = bind("SteamAPI_ISteamUserStats_GetAchievement", JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS);
        clearAchievement = bind("SteamAPI_ISteamUserStats_ClearAchievement", JAVA_BOOLEAN, ADDRESS, ADDRESS);
        storeStats = bind("SteamAPI_ISteamUserStats_StoreStats", JAVA_BOOLEAN, ADDRESS);
        indicateAchievementProgress = bind("SteamAPI_ISteamUserStats_IndicateAchievementProgress", JAVA_BOOLEAN,
                ADDRESS, ADDRESS, JAVA_INT, JAVA_INT);

        fileWrite = bind("SteamAPI_ISteamRemoteStorage_FileWrite", JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
        fileRead = bind("SteamAPI_ISteamRemoteStorage_FileRead", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
        fileExists = bind("SteamAPI_ISteamRemoteStorage_FileExists", JAVA_BOOLEAN, ADDRESS, ADDRESS);
        getFileSize = bind("SteamAPI_ISteamRemoteStorage_GetFileSize", JAVA_INT, ADDRESS, ADDRESS);
        getFileTimestamp = bind("SteamAPI_ISteamRemoteStorage_GetFileTimestamp", JAVA_LONG, ADDRESS, ADDRESS);
        cloudEnabledForAccount = bind("SteamAPI_ISteamRemoteStorage_IsCloudEnabledForAccount", JAVA_BOOLEAN, ADDRESS);
        cloudEnabledForApp = bind("SteamAPI_ISteamRemoteStorage_IsCloudEnabledForApp", JAVA_BOOLEAN, ADDRESS);
    }

    private MethodHandle bind(String name, MemoryLayout result, MemoryLayout... arguments) {
        MemorySegment function = library.find(name).orElseThrow(() ->
                new IllegalStateException("the Steam library has no " + name + "; it may be from an older Steamworks SDK"));
        FunctionDescriptor descriptor = result == null ? FunctionDescriptor.ofVoid(arguments) : FunctionDescriptor.of(result, arguments);
        return linker.downcallHandle(function, descriptor);
    }

    private static <T> T call(String what, NativeCall<T> call) {
        try {
            return call.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Steam call " + what + " failed", e);
        }
    }

    /**
     * Sets an environment variable of this process. The Steam library reads {@code SteamAppId} as it loads, so call this
     * before {@link #load}.
     */
    static void setEnvironment(String name, String value) {
        Linker linker = Linker.nativeLinker();
        call("setting " + name, () -> {
            try (Arena temp = Arena.ofConfined()) {
                if (isWindows()) {
                    SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32", Arena.global());
                    MethodHandle set = linker.downcallHandle(kernel.find("SetEnvironmentVariableA").orElseThrow(),
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
                    return set.invoke(temp.allocateFrom(name), temp.allocateFrom(value));
                }
                MethodHandle set = linker.downcallHandle(linker.defaultLookup().find("setenv").orElseThrow(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
                return set.invoke(temp.allocateFrom(name), temp.allocateFrom(value), 1);
            }
        });
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    // --- Starting and stopping --------------------------------------------------------------------------

    /** True when the game was not started by Steam: Steam is now starting it again, and this copy should quit. */
    boolean restartAppIfNecessary(int appId) {
        return call("RestartAppIfNecessary", () -> (boolean) restartAppIfNecessary.invoke(appId));
    }

    /** Connects to the running Steam client; on success, also gets the interfaces and switches to manual callbacks. */
    Init init() {
        return call("InitFlat", () -> {
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment error = temp.allocate(1024);
                int result = (int) initFlat.invoke(error);
                if (result == INIT_OK) {
                    user = (MemorySegment) steamUser.invoke();
                    friends = (MemorySegment) steamFriends.invoke();
                    userStats = (MemorySegment) steamUserStats.invoke();
                    remoteStorage = (MemorySegment) steamRemoteStorage.invoke();
                    networkingMessages = (MemorySegment) steamNetworkingMessages.invoke();
                    dispatchInit.invoke();
                    pipe = (int) getPipe.invoke();
                }
                return new Init(result, error.getString(0));
            }
        });
    }

    void shutdown() {
        call("Shutdown", () -> shutdown.invoke());
    }

    /** Hands each waiting callback to {@code handler}. */
    void runCallbacks(CallbackHandler handler) {
        call("callbacks", () -> {
            dispatchRunFrame.invoke(pipe);
            while ((boolean) dispatchNext.invoke(pipe, callbackMessage)) {
                try {
                    int id = callbackMessage.get(JAVA_INT_UNALIGNED, CALLBACK_ID);
                    int size = callbackMessage.get(JAVA_INT_UNALIGNED, CALLBACK_DATA_SIZE);
                    MemorySegment data = callbackMessage.get(POINTER, CALLBACK_DATA).reinterpret(Math.max(0, size));
                    handler.handle(id, data);
                } finally {
                    dispatchFree.invoke(pipe);
                }
            }
            return null;
        });
    }

    // --- The player and their friends -------------------------------------------------------------------

    long steamId() {
        return call("GetSteamID", () -> (long) getSteamId.invoke(user));
    }

    String personaName() {
        return call("GetPersonaName", () -> string((MemorySegment) getPersonaName.invoke(friends)));
    }

    int friendCount() {
        return call("GetFriendCount", () -> (int) getFriendCount.invoke(friends, FRIEND_FLAG_IMMEDIATE));
    }

    long friendByIndex(int index) {
        return call("GetFriendByIndex", () -> (long) getFriendByIndex.invoke(friends, index, FRIEND_FLAG_IMMEDIATE));
    }

    String friendName(long steamId) {
        return call("GetFriendPersonaName", () -> string((MemorySegment) getFriendPersonaName.invoke(friends, steamId)));
    }

    int friendState(long steamId) {
        return call("GetFriendPersonaState", () -> (int) getFriendPersonaState.invoke(friends, steamId));
    }

    /** The App ID of the game a friend is playing, or 0. */
    int friendAppId(long steamId) {
        return call("GetFriendGamePlayed", () -> (boolean) getFriendGamePlayed.invoke(friends, steamId, gameInfo)
                ? (int) (gameInfo.get(JAVA_LONG_UNALIGNED, 0) & 0xFFFFFFL) : 0);
    }

    boolean isFriend(long steamId) {
        return call("HasFriend", () -> (boolean) hasFriend.invoke(friends, steamId, FRIEND_FLAG_IMMEDIATE));
    }

    boolean setRichPresence(String key, String value) {
        return call("SetRichPresence", () -> {
            try (Arena temp = Arena.ofConfined()) {
                return (boolean) setRichPresence.invoke(friends, temp.allocateFrom(key), temp.allocateFrom(value));
            }
        });
    }

    void clearRichPresence() {
        call("ClearRichPresence", () -> clearRichPresence.invoke(friends));
    }

    void activateOverlay(String dialog) {
        call("ActivateGameOverlay", () -> {
            try (Arena temp = Arena.ofConfined()) {
                return activateOverlay.invoke(friends, temp.allocateFrom(dialog));
            }
        });
    }

    void activateInviteDialog(String connect) {
        call("ActivateGameOverlayInviteDialogConnectString", () -> {
            try (Arena temp = Arena.ofConfined()) {
                return activateInviteDialog.invoke(friends, temp.allocateFrom(connect));
            }
        });
    }

    boolean inviteUserToGame(long steamId, String connect) {
        return call("InviteUserToGame", () -> {
            try (Arena temp = Arena.ofConfined()) {
                return (boolean) inviteUserToGame.invoke(friends, steamId, temp.allocateFrom(connect));
            }
        });
    }

    // --- Messages ---------------------------------------------------------------------------------------

    boolean send(long steamId, byte[] data, int channel) {
        return call("SendMessageToUser", () -> {
            identitySetSteamId.invoke(identity, steamId);
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment bytes = temp.allocateFrom(JAVA_BYTE, data);
                int result = (int) sendMessageToUser.invoke(networkingMessages, identity, bytes, data.length,
                        SEND_RELIABLE | SEND_AUTO_RESTART_BROKEN_SESSION, channel);
                return result == RESULT_OK;
            }
        });
    }

    List<Received> receive(int channel) {
        return call("ReceiveMessagesOnChannel", () -> {
            int count = (int) receiveMessagesOnChannel.invoke(networkingMessages, channel, messagePointers, MAX_MESSAGES_PER_CALL);
            List<Received> received = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                MemorySegment message = messagePointers.getAtIndex(POINTER, i).reinterpret(MESSAGE_BYTES);
                try {
                    int size = message.get(JAVA_INT_UNALIGNED, MESSAGE_DATA_SIZE);
                    byte[] data = message.get(POINTER, MESSAGE_DATA).reinterpret(Math.max(0, size)).toArray(JAVA_BYTE);
                    received.add(new Received(message.get(JAVA_LONG_UNALIGNED, MESSAGE_SENDER), data));
                } finally {
                    releaseMessage.invoke(message);
                }
            }
            return received;
        });
    }

    void acceptSession(long steamId) {
        call("AcceptSessionWithUser", () -> {
            identitySetSteamId.invoke(identity, steamId);
            return acceptSessionWithUser.invoke(networkingMessages, identity);
        });
    }

    void closeSession(long steamId) {
        call("CloseSessionWithUser", () -> {
            identitySetSteamId.invoke(identity, steamId);
            return closeSessionWithUser.invoke(networkingMessages, identity);
        });
    }

    // --- Achievements -----------------------------------------------------------------------------------

    boolean setAchievement(String id) {
        return call("SetAchievement", () -> {
            try (Arena temp = Arena.ofConfined()) {
                return (boolean) setAchievement.invoke(userStats, temp.allocateFrom(id));
            }
        });
    }

    /** Whether an achievement is unlocked; false too if the game has no achievement by that id. */
    boolean achievement(String id) {
        return call("GetAchievement", () -> {
            try (Arena temp = Arena.ofConfined()) {
                flag.set(JAVA_BYTE, 0, (byte) 0);
                boolean known = (boolean) getAchievement.invoke(userStats, temp.allocateFrom(id), flag);
                return known && flag.get(JAVA_BYTE, 0) != 0;
            }
        });
    }

    boolean clearAchievement(String id) {
        return call("ClearAchievement", () -> {
            try (Arena temp = Arena.ofConfined()) {
                return (boolean) clearAchievement.invoke(userStats, temp.allocateFrom(id));
            }
        });
    }

    boolean storeStats() {
        return call("StoreStats", () -> (boolean) storeStats.invoke(userStats));
    }

    boolean indicateProgress(String id, int current, int max) {
        return call("IndicateAchievementProgress", () -> {
            try (Arena temp = Arena.ofConfined()) {
                return (boolean) indicateAchievementProgress.invoke(userStats, temp.allocateFrom(id), current, max);
            }
        });
    }

    // --- Cloud files ------------------------------------------------------------------------------------

    boolean cloudEnabled() {
        return call("IsCloudEnabled", () -> (boolean) cloudEnabledForAccount.invoke(remoteStorage)
                && (boolean) cloudEnabledForApp.invoke(remoteStorage));
    }

    boolean fileWrite(String name, byte[] data) {
        return call("FileWrite", () -> {
            try (Arena temp = Arena.ofConfined()) {
                return (boolean) fileWrite.invoke(remoteStorage, temp.allocateFrom(name), temp.allocateFrom(JAVA_BYTE, data), data.length);
            }
        });
    }

    /** A cloud file's contents, or null if there is none or it could not be read whole. */
    byte[] fileRead(String name) {
        return call("FileRead", () -> {
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment file = temp.allocateFrom(name);
                if (!(boolean) fileExists.invoke(remoteStorage, file)) {
                    return null;
                }
                int size = (int) getFileSize.invoke(remoteStorage, file);
                MemorySegment buffer = temp.allocate(Math.max(1, size));
                int read = (int) fileRead.invoke(remoteStorage, file, buffer, size);
                return read == size ? buffer.asSlice(0, size).toArray(JAVA_BYTE) : null;
            }
        });
    }

    /** When a cloud file was written, in seconds since 1970, or -1 if there is none. */
    long fileTimestamp(String name) {
        return call("GetFileTimestamp", () -> {
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment file = temp.allocateFrom(name);
                return (boolean) fileExists.invoke(remoteStorage, file) ? (long) getFileTimestamp.invoke(remoteStorage, file) : -1L;
            }
        });
    }

    private static String string(MemorySegment pointer) {
        return pointer.equals(MemorySegment.NULL) ? "" : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }
}
