package org.zeronura.nulzs.asset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Drawing icons: a single-icon file whole, its gradients, and its silhouette. */
class SvgSymbolsTest {

    @TempDir
    Path temp;

    private Path write(String svg) throws Exception {
        Path file = temp.resolve("icon.svg");
        Files.writeString(file, svg);
        return file;
    }

    private static int[] rgb(BufferedImage image, int x, int y) {
        int argb = image.getRGB(x, y);
        return new int[]{argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24};
    }

    @Test
    void aSingleIconFileIsDrawnWholeWithItsLinearGradient() throws Exception {
        SvgSymbols icon = SvgSymbols.load(write("""
                <svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
                  <defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="64" gradientUnits="userSpaceOnUse">
                    <stop offset="0" stop-color="#ff0000"/><stop offset="1" stop-color="#0000ff"/></linearGradient></defs>
                  <title>x</title>
                  <g><path d="M0 0 H64 V64 H0 Z" fill="url(#g)"/></g>
                </svg>"""));
        BufferedImage image = icon.image(SvgSymbols.WHOLE, 64, Color.WHITE);
        int[] top = rgb(image, 32, 1);
        int[] bottom = rgb(image, 32, 62);
        assertTrue(top[0] > 230 && top[2] < 25, "red at the top");
        assertTrue(bottom[2] > 230 && bottom[0] < 25, "blue at the bottom");
        assertEquals(255, top[3]);
    }

    @Test
    void aBoundingBoxRadialGradientWithStopOpacitySpansTheShape() throws Exception {
        SvgSymbols icon = SvgSymbols.load(write("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
                  <defs><radialGradient id="r"><stop offset="0%" stop-color="#ffffff"/>
                    <stop offset="100%" stop-color="#000000" stop-opacity="0"/></radialGradient></defs>
                  <rect x="20" y="20" width="60" height="60" fill="url(#r)"/>
                </svg>"""));
        BufferedImage image = icon.image(SvgSymbols.WHOLE, 100, Color.WHITE);
        assertTrue(rgb(image, 50, 50)[0] > 240 && rgb(image, 50, 50)[3] > 240, "white and solid in the middle");
        assertTrue(rgb(image, 21, 50)[3] < 40, "fading out to its edge");
        assertEquals(0, rgb(image, 5, 5)[3], "nothing outside the shape");
    }

    @Test
    void flatItIsASilhouetteInTheColourAsked() throws Exception {
        SvgSymbols icon = SvgSymbols.load(write("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10">
                  <defs><linearGradient id="g"><stop offset="0" stop-color="#123456"/><stop offset="1" stop-color="#abcdef"/>
                  </linearGradient></defs>
                  <circle cx="5" cy="5" r="4" fill="url(#g)" stroke="#ff00ff" stroke-width="1"/>
                </svg>"""));
        BufferedImage image = icon.image(SvgSymbols.WHOLE, 40, new Color(0x00FF00), true);
        int[] middle = rgb(image, 20, 20);
        assertArrayEquals(new int[]{0, 255, 0, 255}, middle);
        int[] rim = rgb(image, 20, 2);
        assertEquals(0, rim[0], "the stroke takes the colour too");
        assertEquals(0, rim[2]);
    }

    @Test
    void sheetsStillDrawTheirSymbolsInTheCurrentColour() throws Exception {
        SvgSymbols sheet = SvgSymbols.load(write("""
                <svg xmlns="http://www.w3.org/2000/svg">
                  <symbol id="dot" viewBox="0 0 10 10"><circle cx="5" cy="5" r="5" fill="currentColor"/></symbol>
                </svg>"""));
        assertTrue(sheet.has("dot"));
        assertArrayEquals(new int[]{255, 128, 0, 255}, rgb(sheet.image("dot", 20, new Color(255, 128, 0)), 10, 10));
    }
}
