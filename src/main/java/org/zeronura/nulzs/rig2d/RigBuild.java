package org.zeronura.nulzs.rig2d;

import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Makes rigs from layered files on the command line: {@code ./engine rig <folder|file.psd>... [--force] [--preview DIR]}.
 *
 * <p>Every {@code .psd} found (folders are searched all the way down) that has no {@code .rig.json} beside it gets one
 * from {@link AutoRig}, using the {@code .parts.json} hints beside it if there are any. {@code --force} remakes rigs
 * that exist (losing edits made to them). {@code --preview DIR} draws each rig's motions as a pose sheet
 * ({@code <folder>_<name>.png}: one row per motion, poses across it) to check them without starting a game.
 */
public final class RigBuild {

    private RigBuild() {
    }

    public static void main(String[] args) throws IOException {
        boolean force = false;
        Path preview = null;
        int cell = 220;
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--force" -> force = true;
                case "--preview" -> preview = Path.of(args[++i]);
                case "--cell" -> cell = Integer.parseInt(args[++i]);
                default -> roots.add(Path.of(args[i]));
            }
        }
        List<Path> psds = new ArrayList<>();
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".psd")).sorted().forEach(psds::add);
                }
            } else if (Files.isRegularFile(root)) {
                psds.add(root);
            } else {
                System.err.println("No such file or folder: " + root);
            }
        }
        int made = 0;
        for (Path psd : psds) {
            Path rigFile = RigFiles.rigFile(psd);
            RigModel model;
            if (Files.exists(rigFile) && !force) {
                model = RigFiles.load(rigFile);
                System.out.println("kept   " + rigFile);
            } else {
                model = RigFiles.autoRig(psd, psd.getFileName().toString().startsWith("back") ? 1 : -1);
                RigFiles.save(model, rigFile);
                made++;
                System.out.println("rigged " + rigFile + "  (" + model.meshes.size() + " meshes, "
                        + model.deformers.size() + " deformers, " + model.parameters.size() + " parameters)");
            }
            if (preview != null) {
                Files.createDirectories(preview);
                String name = psd.getParent().getFileName() + "_" + psd.getFileName().toString().replace(".psd", "");
                writeSheet(model, preview.resolve(name + ".png"), cell);
            }
        }
        System.out.println(made + " rig(s) made, " + (psds.size() - made) + " kept");
    }

    /** One row per motion, eight poses across it, on a light background. */
    static void writeSheet(RigModel model, Path file, int cell) {
        int columns = 8;
        List<RigMotion> motions = new ArrayList<>(model.motions.values());
        int rows = Math.max(1, motions.size());
        int width = cell * columns;
        int height = cell * rows;
        int[] sheet = new int[width * height];
        java.util.Arrays.fill(sheet, 0xFFE6ECE6);
        float[] box = model.restBounds();
        float scale = Math.min((cell - 30f) / box[2], (cell - 30f) / box[3]);
        RigInstance rig = new RigInstance(model);
        for (int row = 0; row < motions.size(); row++) {
            RigMotion motion = motions.get(row);
            for (int c = 0; c < columns; c++) {
                float time = motion.duration * c / (motion.loop ? columns : columns - 1);
                rig.reset();
                rig.poseAt(motion, time);
                float offsetX = cell / 2f - (box[0] + box[2] / 2f) * scale;
                float offsetY = cell - 12f - (box[1] + box[3]) * scale;
                int[] pixels = RigSnapshot.render(rig, cell, cell, scale, offsetX, offsetY);
                for (int y = 0; y < cell; y++) {
                    for (int x = 0; x < cell; x++) {
                        int src = pixels[y * cell + x];
                        int sa = src >>> 24;
                        if (sa == 0) {
                            continue;
                        }
                        int at = (row * cell + y) * width + c * cell + x;
                        int dst = sheet[at];
                        int rr = ((src >> 16 & 0xFF) * sa + (dst >> 16 & 0xFF) * (255 - sa)) / 255;
                        int gg = ((src >> 8 & 0xFF) * sa + (dst >> 8 & 0xFF) * (255 - sa)) / 255;
                        int bb = ((src & 0xFF) * sa + (dst & 0xFF) * (255 - sa)) / 255;
                        sheet[at] = 0xFF000000 | rr << 16 | gg << 8 | bb;
                    }
                }
            }
        }
        ByteBuffer rgba = MemoryUtil.memAlloc(width * height * 4);
        try {
            for (int i = 0; i < sheet.length; i++) {
                int c = sheet[i];
                rgba.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
            }
            rgba.flip();
            STBImageWrite.stbi_write_png(file.toString(), width, height, 4, rgba, width * 4);
        } finally {
            MemoryUtil.memFree(rgba);
        }
        System.out.println("  sheet " + file + "  (rows: " + String.join(", ", model.motions.keySet()) + ")");
    }
}
