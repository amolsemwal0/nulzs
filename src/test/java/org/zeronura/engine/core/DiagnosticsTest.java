package org.zeronura.engine.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** A report is one whole zip, holding what it was given, and its log is the newest part of what the game printed. */
class DiagnosticsTest {

    @Test
    void anArchiveHoldsTheReportTheLogAndItsAttachments(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("settings.txt"), "volume=1");
        Path folder = Files.createDirectories(dir.resolve("creatures/deep"));
        Files.writeString(folder.resolve("a.json"), "{}");
        Map<String, Path> attachments = new LinkedHashMap<>();
        attachments.put("settings/player.txt", file);
        attachments.put("save/creatures", dir.resolve("creatures"));
        attachments.put("not-there.png", dir.resolve("missing.png"));

        Path zip = Diagnostics.writeArchive(dir.resolve("out"), "bug", "It broke", attachments);

        try (ZipFile archive = new ZipFile(zip.toFile())) {
            TreeSet<String> names = new TreeSet<>();
            archive.stream().forEach(entry -> names.add(entry.getName()));
            assertEquals(new TreeSet<>(java.util.List.of("report.txt", "log.txt", "settings/player.txt", "save/creatures/deep/a.json")), names);
            assertEquals("It broke", new String(archive.getInputStream(archive.getEntry("report.txt")).readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(1L, Files.list(dir.resolve("out")).count(), "nothing left beside the zip");
    }

    @Test
    void twoReportsInTheSameSecondDoNotOverwrite(@TempDir Path dir) throws IOException {
        Path first = Diagnostics.writeArchive(dir, "bug", "one", Map.of());
        Path second = Diagnostics.writeArchive(dir, "bug", "two", Map.of());
        assertNotEquals(first, second);
    }

    @Test
    void theLogKeepsItsNewestPartFromAWholeLine() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 80_000; i++) {
            text.append("line number ").append(i).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        Diagnostics.remember(bytes, 0, bytes.length);
        Diagnostics.remember("last\n".getBytes(StandardCharsets.UTF_8), 0, 5);

        String log = Diagnostics.recentLog();
        assertTrue(log.endsWith("line number 79999\nlast\n"), "the newest is kept");
        assertTrue(log.startsWith("line number "), "the oldest line kept is a whole one");
        assertTrue(log.length() <= 512 * 1024, "and no more than the limit");
    }
}
