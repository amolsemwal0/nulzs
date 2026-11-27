package org.zeronura.engine.debug;

import org.junit.jupiter.api.Test;
import org.zeronura.engine.data.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading the debug mode's readings: what limits the frame, each part's own time, the calls by function. */
class DebugReportTest {

    private static Map<String, Object> row(String name, int depth, double cpu, double gpu) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("depth", depth);
        row.put("cpu", cpu);
        row.put("gpu", gpu);
        row.put("times", 1.0);
        return row;
    }

    /** A frame of {@code frame} ms: {@code work} of render on the CPU, {@code gpu} of it on the card, the rest swap. */
    private static Map<String, Object> readings(double frame, double work, double gpu, boolean vsync) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("gpuTimed", true);
        info.put("vsync", vsync);
        info.put("game", "Test");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("info", info);
        out.put("fps", 1000.0 / frame);
        out.put("frameMs", frame);
        out.put("worstMs", frame);
        out.put("scopes", List.of(
                row("Frame", 0, frame, gpu),
                row("Render", 1, work, gpu),
                row("Shadow map", 2, work * 0.25, gpu * 0.6),
                row("Terrain", 2, work * 0.5, gpu * 0.3),
                row(DebugMode.SWAP, 1, frame - work, 0.1)));
        Map<String, Object> gl = new LinkedHashMap<>();
        gl.put("traced", true);
        gl.put("sites", List.of(List.of("glDrawElements", "engine.A.draw", 100.0, 0.5),
                List.of("glDrawElements", "engine.B.draw", 50.0, 0.25), List.of("glUniform1f", "engine.A.draw", 900.0, 0.1)));
        out.put("gl", gl);
        return out;
    }

    @Test
    void aSlowCardIsGpuBound() {
        DebugReport.Verdict verdict = DebugReport.verdict(readings(20, 5, 18, false));
        assertEquals(DebugReport.Limit.GPU, verdict.limit());
        assertTrue(verdict.detail().contains("Render > Shadow map"), verdict.detail());
    }

    @Test
    void slowCodeIsCpuBound() {
        assertEquals(DebugReport.Limit.CPU, DebugReport.verdict(readings(20, 19, 6, false)).limit());
    }

    @Test
    void waitingForTheScreenIsVsync() {
        assertEquals(DebugReport.Limit.VSYNC, DebugReport.verdict(readings(16.7, 6, 7, true)).limit());
    }

    @Test
    void aVeryShortFrameIsFast() {
        assertEquals(DebugReport.Limit.FAST, DebugReport.verdict(readings(0.6, 0.2, 0.4, false)).limit());
    }

    @Test
    void sentencesNameAPartByItsLastTwoSteps() {
        assertEquals("World > Shadow map", DebugReport.shortPath("Render > OverworldScene > World > Shadow map"));
        assertEquals("Render > Terrain", DebugReport.shortPath("Render > Terrain"));
        assertEquals("Overlay", DebugReport.shortPath("Overlay"));
    }

    @Test
    void aPartsOwnTimeLeavesOutThePartsInsideIt() {
        List<DebugReport.Part> parts = DebugReport.parts(readings(20, 8, 10, false), "cpu");
        Map<String, Double> byPath = new LinkedHashMap<>();
        for (DebugReport.Part part : parts) {
            byPath.put(part.path(), part.ms());
        }
        assertEquals(2.0, byPath.get("Render"), 1e-9);
        assertEquals(4.0, byPath.get("Render > Terrain"), 1e-9);
        assertTrue(!byPath.containsKey(DebugMode.SWAP), "the swap is a wait, not work");
        assertEquals("Render > Terrain", parts.getFirst().path());
    }

    @Test
    void callsAddUpByFunction() {
        List<DebugReport.Function> functions = DebugReport.functions(readings(20, 8, 10, false));
        assertEquals("glDrawElements", functions.getFirst().name());
        assertEquals(150.0, functions.getFirst().calls(), 1e-9);
        assertEquals(0.75, functions.getFirst().ms(), 1e-9);
        assertEquals(2, functions.getFirst().sites());
    }

    @Test
    void readingsSentAsJsonReadTheSame() {
        Map<String, Object> sent = Json.parseObject(Json.compact(readings(20, 5, 18, false)));
        assertEquals(DebugReport.Limit.GPU, DebugReport.verdict(sent).limit());
        String text = DebugReport.text(sent);
        assertTrue(text.contains("GPU-bound"), text);
        assertTrue(text.contains("glDrawElements"), text);
    }
}
