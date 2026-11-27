package org.zeronura.engine.rig2d;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The GL-free parts of the 2.5D rig: keyforms, deformers, posing, motions, physics, PSD reading and the rig file. */
class Rig2DTest {

    // --- Keyforms ---------------------------------------------------------------------------------------------

    private static RigModel modelWith(RigParameter... parameters) {
        RigModel model = new RigModel();
        model.parameters.addAll(List.of(parameters));
        model.resolve();
        return model;
    }

    @Test
    void oneParameterTrackBlendsBetweenKeysAndHoldsAtTheEnds() {
        RigModel model = modelWith(new RigParameter("A", -10f, 10f, 0f));
        Keyforms keyforms = new Keyforms(1);
        keyforms.tracks.add(Keyforms.Track.of("A", new float[]{-10f, 0f, 10f},
                new float[]{-4f}, new float[]{0f}, new float[]{8f}));
        keyforms.resolve(model);
        assertEquals(4f, blend(keyforms, 5f), 1e-5f);
        assertEquals(-2f, blend(keyforms, -5f), 1e-5f);
        assertEquals(8f, blend(keyforms, 10f), 1e-5f);
        assertEquals(8f, blend(keyforms, 99f), 1e-5f, "past the last key the last keyform holds");
        assertEquals(-4f, blend(keyforms, -99f), 1e-5f);
    }

    private static float blend(Keyforms keyforms, float value) {
        float[] out = new float[1];
        keyforms.addTo(new float[]{value}, out);
        return out[0];
    }

    @Test
    void twoParameterTrackBlendsBilinearlyAndTracksAddUp() {
        RigModel model = modelWith(new RigParameter("A", 0f, 1f, 0f), new RigParameter("B", 0f, 1f, 0f));
        Keyforms.Track grid = new Keyforms.Track();
        grid.parameters.addAll(List.of("A", "B"));
        grid.keys.add(new float[]{0f, 1f});
        grid.keys.add(new float[]{0f, 1f});
        // (a0 b0) (a1 b0) (a0 b1) (a1 b1)
        grid.forms.add(new float[]{0f});
        grid.forms.add(new float[]{10f});
        grid.forms.add(new float[]{20f});
        grid.forms.add(new float[]{40f});
        Keyforms keyforms = new Keyforms(1);
        keyforms.tracks.add(grid);
        keyforms.tracks.add(Keyforms.Track.of("A", new float[]{0f, 1f}, new float[]{0f}, new float[]{100f}));
        keyforms.resolve(model);
        float[] out = new float[1];
        keyforms.addTo(new float[]{0.5f, 0.5f}, out);
        // Bilinear middle of 0, 10, 20, 40 is 17.5; the second track adds 50.
        assertEquals(67.5f, out[0], 1e-4f);
    }

    // --- Deformers --------------------------------------------------------------------------------------------

    @Test
    void anUnbentWarpLeavesEveryPointWhereItWas() {
        Deformer.Warp warp = new Deformer.Warp("W", null, 10f, 20f, 100f, 80f, 4, 3);
        float[] state = new float[warp.formSize()];
        warp.pose(state);
        float[] points = {10f, 20f, 37f, 51f, 110f, 100f, 60f, 60f, -30f, 200f};
        float[] expected = points.clone();
        warp.apply(state, points, 0, points.length / 2);
        assertArrayEquals(expected, points, 1e-3f);
    }

    @Test
    void aWarpCarriesItsPointsAlongAndOutsidePointsFollowTheEdge() {
        Deformer.Warp warp = new Deformer.Warp("W", null, 0f, 0f, 100f, 100f, 2, 2);
        float[] state = new float[warp.formSize()];
        // Move every grid point 5 right.
        for (int i = 0; i < state.length; i += 2) {
            state[i] = 5f;
        }
        warp.pose(state);
        float[] points = {50f, 50f, 150f, 50f};
        warp.apply(state, points, 0, 2);
        assertEquals(55f, points[0], 1e-3f);
        assertEquals(50f, points[1], 1e-3f);
        assertEquals(155f, points[2], 1e-3f, "a point beyond the right edge moves with it");
    }

    @Test
    void aRotationTurnsAboutItsOriginClockwiseOnScreen() {
        Deformer.Rotation rotation = new Deformer.Rotation("R", null, 10f, 10f);
        float[] state = {90f, 0f, 0f, 0f};
        rotation.pose(state);
        float[] point = {20f, 10f};
        rotation.apply(state, point, 0, 1);
        // y runs down, so a quarter turn clockwise takes "right of the pivot" to "below it".
        assertEquals(10f, point[0], 1e-4f);
        assertEquals(20f, point[1], 1e-4f);
    }

    // --- Posing -----------------------------------------------------------------------------------------------

    private static RigLayer square(String path, int left, int top, int size) {
        int[] argb = new int[size * size];
        java.util.Arrays.fill(argb, 0xFF336699);
        return new RigLayer(path, left, top, size, size, argb, false);
    }

    @Test
    void meshesHangThroughNestedDeformers() {
        RigModel model = modelWith(new RigParameter("Turn", 0f, 1f, 0f), new RigParameter("Shift", 0f, 1f, 0f));
        model.layers.add(square("Part", 0, 0, 10));
        Deformer.Warp outer = new Deformer.Warp("Outer", null, -50f, -50f, 200f, 200f, 2, 2);
        float[] shift = new float[outer.formSize()];
        for (int i = 0; i < shift.length; i += 2) {
            shift[i] = 7f;
        }
        outer.keyforms.tracks.add(Keyforms.Track.of("Shift", new float[]{0f, 1f}, new float[outer.formSize()], shift));
        Deformer.Rotation inner = new Deformer.Rotation("Inner", "Outer", 0f, 0f);
        inner.keyforms.tracks.add(Keyforms.Track.of("Turn", new float[]{0f, 1f}, new float[4],
                new float[]{90f, 0f, 0f, 0f}));
        model.deformers.add(inner); // children may come before parents; resolve orders them
        model.deformers.add(outer);
        model.meshes.add(new ArtMesh("Part", "Part", "Inner", new float[]{10f, 0f}, new int[0], 0));
        model.resolve();
        RigInstance rig = new RigInstance(model);
        rig.set("Turn", 1f);
        rig.set("Shift", 1f);
        rig.evaluate();
        float[] p = rig.positions(0);
        assertEquals(7f, p[0], 1e-3f, "turned to below the pivot, then shifted 7 right");
        assertEquals(10f, p[1], 1e-3f);
    }

    @Test
    void motionsLoopAndOneShotsFadeBackToTheLoop() {
        RigModel model = modelWith(new RigParameter("A", -1f, 1f, 0f));
        RigMotion idle = new RigMotion("idle", 2f, true).curve("A", new float[]{0f, 1f}, new float[]{0f, 0.5f});
        RigMotion poke = new RigMotion("poke", 0.5f, false).curve("A", new float[]{0f, 0.5f}, new float[]{-1f, -1f});
        poke.fadeIn = 0f;
        poke.fadeOut = 0.1f;
        model.motions.put(idle.id, idle);
        model.motions.put(poke.id, poke);
        model.resolve();
        RigInstance rig = new RigInstance(model);
        rig.physicsEnabled = false;
        rig.loop("idle");
        for (int i = 0; i < 10; i++) {
            rig.update(0.1f); // steps longer than 0.1 s are cut short
        }
        assertEquals(0.5f, rig.get("A"), 1e-3f);
        for (int i = 0; i < 10; i++) {
            rig.update(0.1f);
        }
        assertEquals(0f, rig.get("A"), 1e-3f, "round the loop to the start");
        rig.play("poke");
        rig.update(0.1f);
        assertEquals(-1f, rig.get("A"), 1e-4f);
        for (int i = 0; i < 5; i++) {
            rig.update(0.1f);
        }
        assertFalse(rig.isPlaying("poke"));
    }

    @Test
    void physicsTrailsBehindAndSettles() {
        RigModel model = modelWith(new RigParameter("Lean", -1f, 1f, 0f), new RigParameter("Sway", -1f, 1f, 0f));
        RigPhysics physics = new RigPhysics("Tail", "Sway");
        physics.inputs.add(new RigPhysics.Input("Lean", 1f));
        model.physics.add(physics);
        model.resolve();
        RigInstance rig = new RigInstance(model);
        rig.update(0.016f);
        rig.set("Lean", 1f);
        rig.update(0.016f);
        assertTrue(Math.abs(rig.get("Sway")) > 0.01f, "a sudden lean swings the tail");
        for (int i = 0; i < 400; i++) {
            rig.update(0.016f);
        }
        assertEquals(0f, rig.get("Sway"), 0.01f, "and it settles");
    }

    @Test
    void curvesEaseThroughKeyframesWithoutOvershootingPeaks() {
        RigMotion.Curve curve = new RigMotion.Curve("A", new float[]{0f, 1f, 2f}, new float[]{0f, 1f, 0f});
        float max = 0f;
        for (float t = 0f; t <= 2f; t += 0.01f) {
            max = Math.max(max, curve.sample(t, 2f, false));
        }
        assertEquals(1f, max, 1e-3f);
        assertEquals(0.5f, curve.sample(0.5f, 2f, false), 0.1f);
    }

    // --- Auto rig ---------------------------------------------------------------------------------------------

    @Test
    void autoRigGivesNamedPartsTheirMovesAndMotions() {
        List<RigLayer> layers = new ArrayList<>();
        layers.add(square("Tail", 140, 120, 60));
        layers.add(square("Body", 60, 100, 120));
        layers.add(square("Head/Head", 30, 50, 80));
        layers.add(square("Head/Jaw", 30, 100, 40));
        layers.add(square("Head/Eye", 50, 70, 12));
        RigModel model = AutoRig.rig(layers, 256, 256, -1);
        assertNotNull(model.parameter(AutoRig.BREATH));
        assertNotNull(model.parameter(AutoRig.MOUTH_OPEN));
        assertNotNull(model.parameter(AutoRig.EYE_OPEN));
        assertNotNull(model.parameter(AutoRig.HEAD_ANGLE));
        assertNotNull(model.parameter("Sway Tail"));
        assertEquals("Head", model.deformer("Jaw").parent, "the jaw hangs from the head");
        assertEquals(5, model.meshes.size());
        assertEquals(1, model.physics.size());
        for (String motion : List.of("idle", "attack", "hit", "faint", "happy", "appear")) {
            assertTrue(model.motions.containsKey(motion), motion);
        }
        assertEquals(115f, model.pivotX, 1e-3f);
        assertEquals(220f, model.pivotY, 1e-3f);

        RigInstance rig = new RigInstance(model);
        float[] rest = rig.positions(model.meshes.indexOf(model.mesh("Jaw"))).clone();
        rig.set(AutoRig.MOUTH_OPEN, 1f);
        rig.evaluate();
        float[] open = rig.positions(model.meshes.indexOf(model.mesh("Jaw")));
        float moved = 0f;
        for (int i = 0; i < rest.length; i++) {
            moved = Math.max(moved, Math.abs(open[i] - rest[i]));
        }
        assertTrue(moved > 3f, "opening the mouth moves the jaw");

        rig.reset();
        rig.loop("idle");
        for (int i = 0; i < 100; i++) {
            rig.update(0.033f);
            for (int m = 0; m < model.meshes.size(); m++) {
                for (float v : rig.positions(m)) {
                    assertTrue(Float.isFinite(v));
                }
            }
        }
    }

    @Test
    void eyesBlinkAtTheirOwnTimesAndHoldTheirLook() {
        List<RigLayer> layers = new ArrayList<>();
        layers.add(square("Body", 40, 40, 160));
        layers.add(square("Eye Left", 80, 90, 24));
        layers.add(square("Eye Right", 140, 90, 24));
        RigModel model = AutoRig.rig(layers, 256, 256, -1);
        for (String p : List.of(AutoRig.EYE_LID, AutoRig.EYE_SMILE, AutoRig.EYE_WIDE, AutoRig.EYE_BALL_X,
                AutoRig.EYE_BALL_Y, AutoRig.BROW_ANGLE, AutoRig.BROW_Y)) {
            assertNotNull(model.parameter(p), p);
        }
        assertNull(model.motions.get("idle").curve(AutoRig.EYE_OPEN), "the idle loop leaves blinking to the eyes");

        // Each rig blinks: over a minute its eyes shut at least once, and not always at the same moments as another's.
        RigInstance a = new RigInstance(model);
        RigInstance b = new RigInstance(model);
        assertNotNull(a.eyes);
        a.eyes = new RigEyes(RigEyes.lively(), 1);
        b.eyes = new RigEyes(RigEyes.lively(), 2);
        a.loop("idle");
        b.loop("idle");
        int shutA = 0;
        int together = 0;
        for (int i = 0; i < 1800; i++) {
            a.update(1f / 30f);
            b.update(1f / 30f);
            boolean sa = a.get(AutoRig.EYE_OPEN) < 0.2f;
            boolean sb = b.get(AutoRig.EYE_OPEN) < 0.2f;
            shutA += sa ? 1 : 0;
            together += sa && sb ? 1 : 0;
        }
        assertTrue(shutA > 0, "it blinks");
        assertTrue(together < shutA, "two rigs don't blink in step");

        // A heavy-lidded look holds between blinks; a motion that moves the eyes takes over while it plays.
        RigEyes.Style sleepy = new RigEyes.Style();
        sleepy.lid = 0.7f;
        sleepy.blinkMin = 100f;
        sleepy.blinkMax = 100f;
        sleepy.glanceMax = 0f;
        a.eyes = new RigEyes(sleepy, 3);
        a.update(0.1f);
        assertEquals(0.7f, a.get(AutoRig.EYE_LID), 1e-4f);
        assertEquals(1f, a.get(AutoRig.EYE_OPEN), 1e-4f);
        a.play("happy");
        a.update(0.5f);
        assertTrue(a.get(AutoRig.EYE_SMILE) > 0.9f && a.get(AutoRig.EYE_OPEN) < 0.1f, "happy shuts the eyes in arcs");

        // Shut, an eye folds flat; heavy lids lower its top edge and leave the bottom where it was.
        int eye = model.meshes.indexOf(model.mesh("Eye Left"));
        RigInstance pose = new RigInstance(model);
        pose.evaluate();
        float[] rest = pose.positions(eye).clone();
        pose.set(AutoRig.EYE_LID, 1f);
        pose.evaluate();
        float[] lidded = pose.positions(eye).clone();
        pose.set(AutoRig.EYE_LID, 0f);
        pose.set(AutoRig.EYE_OPEN, 0f);
        pose.evaluate();
        float[] shut = pose.positions(eye);
        float restTop = Float.MAX_VALUE, restBottom = -Float.MAX_VALUE, lidTop = Float.MAX_VALUE, lidBottom = -Float.MAX_VALUE;
        float shutTop = Float.MAX_VALUE, shutBottom = -Float.MAX_VALUE;
        for (int i = 1; i < rest.length; i += 2) {
            restTop = Math.min(restTop, rest[i]);
            restBottom = Math.max(restBottom, rest[i]);
            lidTop = Math.min(lidTop, lidded[i]);
            lidBottom = Math.max(lidBottom, lidded[i]);
            shutTop = Math.min(shutTop, shut[i]);
            shutBottom = Math.max(shutBottom, shut[i]);
        }
        assertTrue(lidTop > restTop + 8f, "heavy lids come down");
        assertEquals(restBottom, lidBottom, 0.5f);
        assertTrue(shutBottom - shutTop < (restBottom - restTop) * 0.3f, "a shut eye is a thin line");
    }

    @Test
    void theFeetStayPlantedWhileTheBodyLeans() {
        RigModel model = AutoRig.rig(List.of(square("Body", 50, 50, 100)), 200, 200, -1);
        RigInstance rig = new RigInstance(model);
        float[] rest = rig.positions(0).clone();
        rig.set(AutoRig.BODY_ANGLE_X, 10f);
        rig.evaluate();
        float[] leaning = rig.positions(0);
        for (int v = 0; v < rest.length; v += 2) {
            if (rest[v + 1] >= 149f) {
                assertEquals(rest[v], leaning[v], 0.5f, "bottom row stays put");
            }
        }
        float topShift = 0f;
        for (int v = 0; v < rest.length; v += 2) {
            if (rest[v + 1] <= 51f) {
                topShift = Math.max(topShift, leaning[v] - rest[v]);
            }
        }
        assertTrue(topShift > 3f, "the top leans over");
    }

    // --- Files ------------------------------------------------------------------------------------------------

    @Test
    void aRigSurvivesSavingAndLoading() throws IOException {
        Path folder = Files.createTempDirectory("rig2d");
        try {
            List<RigLayer> layers = List.of(square("Body", 10, 10, 40), square("Fin", 40, 20, 20));
            RigModel model = AutoRig.rig(layers, 64, 64, 1);
            model.art = "art.psd";
            String json = RigFiles.toJson(model);
            RigModel again = RigFiles.fromJson(org.zeronura.engine.data.Json.parseObject(json),
                    new RigFiles.Art(64, 64, layers));
            assertEquals(model.parameters.size(), again.parameters.size());
            assertEquals(model.deformers.size(), again.deformers.size());
            assertEquals(model.meshes.size(), again.meshes.size());
            assertEquals(model.motions.keySet(), again.motions.keySet());
            assertEquals(1, again.facing);
            RigInstance a = new RigInstance(model);
            RigInstance b = new RigInstance(again);
            a.set("Sway Fin", 1f);
            b.set("Sway Fin", 1f);
            a.evaluate();
            b.evaluate();
            assertArrayEquals(a.positions(1), b.positions(1), 0.05f);
        } finally {
            try (var walk = Files.walk(folder)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void numbersAreWrittenShort() {
        assertEquals("3", RigFiles.n(3f));
        assertEquals("0.5", RigFiles.n(0.5f));
        assertEquals("-1.25", RigFiles.n(-1.25f));
        assertEquals("0.33", RigFiles.n(1f / 3f));
    }

    // --- PSD ----------------------------------------------------------------------------------------------------

    @Test
    void psdLayersComeOutWithNamesGroupsAndPixels() throws IOException {
        byte[] psd = writePsd(8, 6);
        PsdFile file = PsdFile.read(psd);
        assertEquals(8, file.width());
        assertEquals(6, file.height());
        assertEquals(2, file.layers().size());
        PsdFile.Layer body = file.layers().get(0);
        assertEquals("Body", body.name());
        assertEquals(List.of(), body.groups());
        assertEquals(0xFFFF0000, body.argb()[0]);
        PsdFile.Layer jaw = file.layers().get(1);
        assertEquals("Jaw", jaw.name());
        assertEquals(List.of("Head"), jaw.groups());
        assertEquals("Head/Jaw", jaw.path());
        assertEquals(2, jaw.left());
        assertEquals(1, jaw.top());
        assertEquals(0x8000FF00, jaw.argb()[0], "RLE-packed green at half alpha");
    }

    /**
     * A small PSD as Photoshop lays it out: a red Body (raw), then a group Head holding a green Jaw (RLE), written bottom
     * up — Body, the group's end marker, Jaw, the group's folder.
     */
    private static byte[] writePsd(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("8BPS");
        out.writeShort(1);
        out.write(new byte[6]);
        out.writeShort(4);
        out.writeInt(height);
        out.writeInt(width);
        out.writeShort(8);
        out.writeShort(3);
        out.writeInt(0);
        out.writeInt(0);

        ByteArrayOutputStream info = new ByteArrayOutputStream();
        DataOutputStream layers = new DataOutputStream(info);
        ByteArrayOutputStream pixelBytes = new ByteArrayOutputStream();
        DataOutputStream pixels = new DataOutputStream(pixelBytes);
        layers.writeShort(4);
        // Body: whole canvas, raw.
        record(layers, "Body", 0, 0, height, width, -1, rawLength(width, height));
        for (int c = 0; c < 4; c++) {
            pixels.writeShort(0);
            // Channels in order -1 (alpha), 0, 1, 2: opaque red.
            for (int i = 0; i < width * height; i++) {
                pixels.write(c == 0 ? 255 : (c == 1 ? 255 : 0));
            }
        }
        // Group end marker.
        record(layers, "</Layer group>", 0, 0, 0, 0, 3, new int[]{2, 2, 2, 2});
        pixels.write(new byte[8]);
        // Jaw: 4x2 at (2, 1), RLE.
        int jw = 4;
        int jh = 2;
        byte[][] channel = new byte[4][];
        int[] fill = {128, 0, 255, 0};
        int[] lengths = new int[4];
        for (int c = 0; c < 4; c++) {
            ByteArrayOutputStream ch = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(ch);
            d.writeShort(1);
            for (int y = 0; y < jh; y++) {
                d.writeShort(2);
            }
            for (int y = 0; y < jh; y++) {
                d.write(-(jw - 1));
                d.write(fill[c]);
            }
            channel[c] = ch.toByteArray();
            lengths[c] = channel[c].length;
        }
        record(layers, "Jaw", 1, 2, 1 + jh, 2 + jw, -1, lengths);
        for (byte[] c : channel) {
            pixels.write(c);
        }
        // Group folder.
        record(layers, "Head", 0, 0, 0, 0, 1, new int[]{2, 2, 2, 2});
        pixels.write(new byte[8]);

        byte[] records = info.toByteArray();
        byte[] data = pixelBytes.toByteArray();
        int layerInfoLength = records.length + data.length;
        out.writeInt(4 + layerInfoLength + 4);
        out.writeInt(layerInfoLength);
        out.write(records);
        out.write(data);
        out.writeInt(0); // global mask info
        out.writeShort(0); // flattened image: raw, nothing in it (never read)
        return bytes.toByteArray();
    }

    private static int[] rawLength(int w, int h) {
        int n = 2 + w * h;
        return new int[]{n, n, n, n};
    }

    private static void record(DataOutputStream out, String name, int top, int left, int bottom, int right,
                               int section, int[] channelLengths) throws IOException {
        out.writeInt(top);
        out.writeInt(left);
        out.writeInt(bottom);
        out.writeInt(right);
        out.writeShort(4);
        int[] ids = {-1, 0, 1, 2};
        for (int c = 0; c < 4; c++) {
            out.writeShort(ids[c]);
            out.writeInt(channelLengths[c]);
        }
        out.writeBytes("8BIM");
        out.writeBytes("norm");
        out.write(255);
        out.write(0);
        out.write(0);
        out.write(0);
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        DataOutputStream e = new DataOutputStream(extra);
        e.writeInt(0); // mask
        e.writeInt(0); // blending ranges
        byte[] ascii = name.getBytes(StandardCharsets.ISO_8859_1);
        e.write(ascii.length);
        e.write(ascii);
        int padded = (ascii.length + 1 + 3) & ~3;
        e.write(new byte[padded - ascii.length - 1]);
        if (section >= 0) {
            e.writeBytes("8BIM");
            e.writeBytes("lsct");
            e.writeInt(4);
            e.writeInt(section);
        }
        e.writeBytes("8BIM");
        e.writeBytes("luni");
        e.writeInt(4 + name.length() * 2);
        e.writeInt(name.length());
        e.writeChars(name);
        byte[] extraBytes = extra.toByteArray();
        out.writeInt(extraBytes.length);
        out.write(extraBytes);
    }
}
