package org.zeronura.engine.rig2d;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the layers out of a layered Photoshop file (.psd): what PhotoCraft, Krita, GIMP and Photoshop all save, so a
 * rig's art can be painted in any of them.
 *
 * <p>Pure Java with no image library, so it reads the same on a phone as on a desktop. It takes 8-bit RGB documents,
 * raw or RLE (PackBits) channel data — what those programs write by default — and gives each pixel layer its name, the
 * groups it sits in, its rectangle on the canvas, its pixels with any layer mask applied, its opacity and whether it is
 * shown. Group folders become each layer's {@link Layer#groups}; adjustment and other non-pixel layers come out empty
 * and are dropped. The flattened image at the end of the file is not read.
 */
public final class PsdFile {

    /** One pixel layer, bottom of the stack first in {@link PsdFile#layers}. */
    public record Layer(String name, List<String> groups, int left, int top, int width, int height, int[] argb,
                        float opacity, boolean visible, String blend) {

        /** The layer's name with the groups it is in, outermost first: {@code Head/Jaw}. */
        public String path() {
            return groups.isEmpty() ? name : String.join("/", groups) + "/" + name;
        }

        /** Alpha at a canvas pixel, 0 outside the layer. */
        public int alphaAt(int canvasX, int canvasY) {
            int x = canvasX - left;
            int y = canvasY - top;
            if (x < 0 || y < 0 || x >= width || y >= height) {
                return 0;
            }
            return argb[y * width + x] >>> 24;
        }
    }

    private final int width;
    private final int height;
    private final List<Layer> layers;

    private PsdFile(int width, int height, List<Layer> layers) {
        this.width = width;
        this.height = height;
        this.layers = layers;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The pixel layers, bottom of the stack first: the order they are painted in. */
    public List<Layer> layers() {
        return layers;
    }

    public static PsdFile read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    public static PsdFile read(byte[] bytes) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        try {
            return parse(in);
        } catch (RuntimeException e) {
            throw new IOException("Not a PSD this reader takes: " + e.getMessage(), e);
        }
    }

    private static PsdFile parse(ByteBuffer in) throws IOException {
        if (in.getInt() != 0x38425053) { // "8BPS"
            throw new IOException("not a Photoshop file");
        }
        int version = in.getShort();
        if (version != 1) {
            throw new IOException("PSB (large document) files are not read; save as PSD");
        }
        skip(in, 6);
        in.getShort(); // channels of the flattened image
        int height = in.getInt();
        int width = in.getInt();
        int depth = in.getShort();
        int mode = in.getShort();
        if (depth != 8) {
            throw new IOException(depth + "-bit documents are not read; save at 8 bits per channel");
        }
        if (mode != 3) {
            throw new IOException("only RGB documents are read (this one is mode " + mode + ")");
        }
        skip(in, in.getInt()); // colour mode data
        skip(in, in.getInt()); // image resources
        long layerAndMaskLength = in.getInt() & 0xFFFFFFFFL;
        List<Layer> layers = new ArrayList<>();
        if (layerAndMaskLength == 0) {
            return new PsdFile(width, height, layers);
        }
        int layerAndMaskEnd = in.position() + (int) layerAndMaskLength;
        long layerInfoLength = in.getInt() & 0xFFFFFFFFL;
        if (layerInfoLength == 0) {
            in.position(layerAndMaskEnd);
            return new PsdFile(width, height, layers);
        }
        int count = Math.abs(in.getShort());
        List<Record> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            records.add(readRecord(in));
        }
        for (Record record : records) {
            readPixels(in, record);
        }
        in.position(layerAndMaskEnd);

        // Records run from the bottom of the stack up; a group's closing marker comes below its layers and its folder
        // above them, so walking top-down a folder opens a group and a marker closes it.
        List<String> open = new ArrayList<>();
        List<Layer> topDown = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--) {
            Record record = records.get(i);
            if (record.section == 1 || record.section == 2) {
                open.add(record.name);
                continue;
            }
            if (record.section == 3) {
                if (!open.isEmpty()) {
                    open.removeLast();
                }
                continue;
            }
            if (record.argb == null || record.width <= 0 || record.height <= 0) {
                continue;
            }
            topDown.add(new Layer(record.name, List.copyOf(open), record.left, record.top, record.width, record.height,
                    record.argb, record.opacity / 255f, record.visible, record.blend));
        }
        List<Layer> bottomUp = new ArrayList<>(topDown.reversed());
        return new PsdFile(width, height, bottomUp);
    }

    /** A layer record as stored, before its pixels are read. */
    private static final class Record {
        int top;
        int left;
        int width;
        int height;
        short[] channelIds;
        long[] channelLengths;
        String blend;
        int opacity;
        boolean visible;
        String name = "";
        int section;
        int maskTop;
        int maskLeft;
        int maskWidth;
        int maskHeight;
        int maskDefault;
        int[] argb;
    }

    private static Record readRecord(ByteBuffer in) throws IOException {
        Record r = new Record();
        r.top = in.getInt();
        r.left = in.getInt();
        int bottom = in.getInt();
        int right = in.getInt();
        r.width = right - r.left;
        r.height = bottom - r.top;
        int channels = in.getShort();
        r.channelIds = new short[channels];
        r.channelLengths = new long[channels];
        for (int c = 0; c < channels; c++) {
            r.channelIds[c] = in.getShort();
            r.channelLengths[c] = in.getInt() & 0xFFFFFFFFL;
        }
        if (in.getInt() != 0x3842494D) { // "8BIM"
            throw new IOException("bad blend mode signature");
        }
        r.blend = fourCc(in.getInt());
        r.opacity = in.get() & 0xFF;
        in.get(); // clipping
        int flags = in.get() & 0xFF;
        r.visible = (flags & 0x02) == 0;
        in.get(); // filler
        int extraLength = in.getInt();
        int extraEnd = in.position() + extraLength;

        int maskLength = in.getInt();
        int maskEnd = in.position() + maskLength;
        if (maskLength >= 18) {
            r.maskTop = in.getInt();
            r.maskLeft = in.getInt();
            int maskBottom = in.getInt();
            int maskRight = in.getInt();
            r.maskWidth = maskRight - r.maskLeft;
            r.maskHeight = maskBottom - r.maskTop;
            r.maskDefault = in.get() & 0xFF;
        }
        in.position(maskEnd);
        skip(in, in.getInt()); // blending ranges

        int nameLength = in.get() & 0xFF;
        byte[] nameBytes = new byte[nameLength];
        in.get(nameBytes);
        r.name = new String(nameBytes, StandardCharsets.ISO_8859_1);
        int padded = (nameLength + 1 + 3) & ~3;
        skip(in, padded - nameLength - 1);

        // Additional layer information: the Unicode name and the group markers are what is read.
        while (in.position() + 12 <= extraEnd) {
            int signature = in.getInt();
            if (signature != 0x3842494D && signature != 0x38423634) { // "8BIM", "8B64"
                break;
            }
            String key = fourCc(in.getInt());
            int length = in.getInt();
            int dataStart = in.position();
            if (key.equals("luni") && length >= 4) {
                int chars = in.getInt();
                char[] text = new char[Math.max(0, Math.min(chars, (length - 4) / 2))];
                for (int i = 0; i < text.length; i++) {
                    text[i] = in.getChar();
                }
                r.name = new String(text).replace("\u0000", "");
            } else if ((key.equals("lsct") || key.equals("lsdk")) && length >= 4) {
                r.section = in.getInt();
            }
            in.position(Math.min(extraEnd, dataStart + length));
            // Blocks are padded to an even length by some writers and to four bytes by others: step over the padding
            // up to the next signature.
            while (in.position() < extraEnd && in.position() + 4 <= extraEnd) {
                int peek = in.getInt(in.position());
                if (peek == 0x3842494D || peek == 0x38423634) {
                    break;
                }
                in.position(in.position() + 1);
            }
        }
        in.position(extraEnd);
        return r;
    }

    private static void readPixels(ByteBuffer in, Record r) throws IOException {
        int pixels = Math.max(0, r.width) * Math.max(0, r.height);
        byte[] red = null;
        byte[] green = null;
        byte[] blue = null;
        byte[] alpha = null;
        byte[] mask = null;
        for (int c = 0; c < r.channelIds.length; c++) {
            int start = in.position();
            long length = r.channelLengths[c];
            short id = r.channelIds[c];
            boolean isMask = id == -2;
            int w = isMask ? r.maskWidth : r.width;
            int h = isMask ? r.maskHeight : r.height;
            byte[] data = null;
            if (length >= 2 && w > 0 && h > 0 && (id >= -2 && id <= 2)) {
                data = readChannel(in, w, h, start + (int) length);
            }
            in.position(start + (int) length);
            switch (id) {
                case 0 -> red = data;
                case 1 -> green = data;
                case 2 -> blue = data;
                case -1 -> alpha = data;
                case -2 -> mask = data;
                default -> {
                    // other channels (the real mask of a vector-masked layer, spot colours) are not used
                }
            }
        }
        if (pixels == 0 || red == null || green == null || blue == null) {
            return;
        }
        int[] argb = new int[pixels];
        for (int i = 0; i < pixels; i++) {
            int a = alpha == null ? 255 : alpha[i] & 0xFF;
            argb[i] = a << 24 | (red[i] & 0xFF) << 16 | (green[i] & 0xFF) << 8 | (blue[i] & 0xFF);
        }
        if (mask != null && r.maskWidth > 0 && r.maskHeight > 0) {
            for (int y = 0; y < r.height; y++) {
                for (int x = 0; x < r.width; x++) {
                    int mx = r.left + x - r.maskLeft;
                    int my = r.top + y - r.maskTop;
                    int m = mx >= 0 && my >= 0 && mx < r.maskWidth && my < r.maskHeight
                            ? mask[my * r.maskWidth + mx] & 0xFF : r.maskDefault;
                    int i = y * r.width + x;
                    int a = (argb[i] >>> 24) * m / 255;
                    argb[i] = a << 24 | argb[i] & 0xFFFFFF;
                }
            }
        }
        r.argb = argb;
    }

    private static byte[] readChannel(ByteBuffer in, int width, int height, int end) throws IOException {
        int compression = in.getShort();
        byte[] out = new byte[width * height];
        if (compression == 0) {
            int available = Math.min(out.length, end - in.position());
            in.get(out, 0, available);
            return out;
        }
        if (compression != 1) {
            throw new IOException("ZIP-compressed layers are not read; save the PSD with RLE compression");
        }
        int[] rowLengths = new int[height];
        for (int y = 0; y < height; y++) {
            rowLengths[y] = in.getShort() & 0xFFFF;
        }
        for (int y = 0; y < height; y++) {
            int rowEnd = in.position() + rowLengths[y];
            int x = 0;
            int at = y * width;
            while (in.position() < rowEnd && x < width) {
                int n = in.get();
                if (n >= 0) {
                    int run = Math.min(n + 1, width - x);
                    in.get(out, at + x, run);
                    in.position(in.position() + (n + 1 - run));
                    x += run;
                } else if (n != -128) {
                    byte value = in.get();
                    int run = Math.min(1 - n, width - x);
                    for (int i = 0; i < run; i++) {
                        out[at + x + i] = value;
                    }
                    x += run;
                }
            }
            in.position(rowEnd);
        }
        return out;
    }

    private static void skip(ByteBuffer in, int bytes) {
        in.position(in.position() + bytes);
    }

    private static String fourCc(int value) {
        return new String(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value},
                StandardCharsets.ISO_8859_1);
    }
}
