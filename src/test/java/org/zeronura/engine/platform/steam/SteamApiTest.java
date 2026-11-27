package org.zeronura.engine.platform.steam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Steam's library without the Steam client: the functions bind, and the library is found where the game looks. */
class SteamApiTest {

    /**
     * The Steamworks SDK's libraries: under $STEAMWORKS_SDK (the SDK's sdk/ folder) if set, else SteamSDK/sdk here.
     * Valve's licence keeps the SDK out of the repository, so without one the test that needs it is skipped.
     */
    private static final Path SDK_LIBRARIES = Path.of(System.getenv().getOrDefault("STEAMWORKS_SDK",
            Path.of("SteamSDK", "sdk").toString())).resolve("redistributable_bin");

    @Test
    void everyFunctionTheEngineCallsIsInTheShippedSteamLibrary() {
        Path library = SDK_LIBRARIES.resolve(SteamPlatform.sdkFolder()).resolve(SteamPlatform.libraryName());
        assumeTrue(Files.isRegularFile(library), "no Steamworks SDK: set STEAMWORKS_SDK to its sdk/ folder");
        assertDoesNotThrow(() -> SteamApi.load(library.toAbsolutePath()));
    }

    @Test
    void theLibraryIsFoundInTheFirstFolderThatHasIt(@TempDir Path temp) throws IOException {
        Path first = Files.createDirectory(temp.resolve("first"));
        Path second = Files.createDirectory(temp.resolve("second"));
        Files.createFile(second.resolve(SteamPlatform.libraryName()));
        assertEquals(second.resolve(SteamPlatform.libraryName()).toAbsolutePath(), SteamPlatform.findLibrary(List.of(first, second)));
        assertNull(SteamPlatform.findLibrary(List.of(first)));
    }
}
