package org.zeronura.engine.worldmap;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.zeronura.engine.asset.TexturedPart;
import org.zeronura.engine.graphics2d.Color;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A model's pictures, read without a GL context. */
class TexturedModelsTest {

    private static ByteBuffer png(int... rgb) throws IOException {
        BufferedImage image = new BufferedImage(rgb.length, 1, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < rgb.length; x++) {
            image.setRGB(x, 0, rgb[x]);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        byte[] data = bytes.toByteArray();
        return ByteBuffer.allocateDirect(data.length).put(data).flip();
    }

    @Test
    void aPictureIsDrawnFlatInItsAverageColour() throws IOException {
        Color average = TexturedModels.averageOf(png(0xFF0000, 0x0000FF));
        assertEquals(0.5f, average.r(), 0.01f);
        assertEquals(0f, average.g(), 0.01f);
        assertEquals(0.5f, average.b(), 0.01f);
    }

    /** One triangle in the ground plane, its normals up, its corners going round as {@code indices} say. */
    private static TexturedPart sheet(boolean twoSided, int... indices) {
        float[] positions = {0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f};
        float[] normals = {0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f};
        return new TexturedPart("roof", positions, normals, new float[6], indices, new Vector4f(1f), null, twoSided);
    }

    @Test
    void aTwoSidedSheetIsBuiltFacingBothWays() {
        TexturedPart both = TexturedModels.sided(sheet(true, 0, 1, 2));
        assertArrayEquals(new int[]{0, 1, 2, 3, 5, 4}, both.indices(), "the sheet, then the same turned round");
        assertEquals(1f, both.normals()[1], 0f, "the front lit from above");
        assertEquals(-1f, both.normals()[10], 0f, "the back from below");
    }

    @Test
    void aFaceWoundAgainstItsNormalsIsTurnedToFaceTheirWay() {
        // 0, 2, 1 goes round facing down; its normals face up.
        TexturedPart both = TexturedModels.sided(sheet(true, 0, 2, 1));
        assertArrayEquals(new int[]{0, 1, 2, 3, 5, 4}, both.indices());
    }

    @Test
    void aOneSidedPartIsBuiltAsItIs() {
        TexturedPart part = sheet(false, 0, 2, 1);
        assertSame(part, TexturedModels.sided(part));
    }

    @Test
    void aPictureThatWillNotDecodeHasNone() {
        ByteBuffer junk = ByteBuffer.allocateDirect(16).put(new byte[16]).flip();
        assertNull(TexturedModels.averageOf(junk));
    }
}
