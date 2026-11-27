package org.zeronura.nulzs.audio;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ShortBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decoder, checked without an audio device.
 *
 * <p>Decoding is deliberately free of OpenAL so it can be tested at all — the same split that lets a chunk
 * worker be tested without a GL context. Nothing here opens a device, so these run on a machine with no
 * sound card.
 */
class VorbisStreamTest {

    /**
     * A short stereo effect, a tone on the left and noise on the right fading out (so the two channels differ and a
     * downmix that took one of them shows), made for these tests with ffmpeg's libvorbis.
     */
    private static final Path CHOP = Path.of("src/test/resources/audio/two_channel_burst.ogg");

    @Test
    void aFileDecodesToTheLengthItAdvertises() throws IOException {
        try (VorbisStream stream = VorbisStream.open(CHOP)) {
            assertEquals(44100, stream.sampleRate());
            assertEquals(2, stream.channels(), "every delivered effect is stereo - see AudioClip");
            assertTrue(stream.seconds() > 0.1f && stream.seconds() < 5f,
                    "a tool effect should be a fraction of a second, got " + stream.seconds());
        }
    }

    @Test
    void theMonoDownmixHalvesTheSamplesAndKeepsTheSignal() throws IOException {
        PcmData stereo = VorbisStream.decode(CHOP, false);
        PcmData mono = VorbisStream.decode(CHOP, true);
        try {
            assertEquals(2, stereo.channels());
            assertEquals(1, mono.channels());
            assertEquals(stereo.frameCount(), mono.frameCount(),
                    "a downmix changes the channel count, not the length");
            assertEquals(stereo.samples().remaining() / 2, mono.samples().remaining());

            // It has to be the average of the two channels, not one of them and not their sum: a sum
            // clips the moment the channels agree, which for a centred effect is most of the file.
            int base = stereo.samples().position();
            long worst = 0;
            for (int frame = 0; frame < mono.frameCount(); frame++) {
                int expected = (stereo.samples().get(base + frame * 2)
                        + stereo.samples().get(base + frame * 2 + 1)) / 2;
                worst = Math.max(worst, Math.abs(expected - mono.samples().get(frame)));
            }
            assertEquals(0, worst, "the downmix is not the mean of the channels");

            // And it must not be silence, which is what a plausible-looking broken decode produces.
            double sumSquares = 0;
            for (int frame = 0; frame < mono.frameCount(); frame++) {
                double sample = mono.samples().get(frame);
                sumSquares += sample * sample;
            }
            assertTrue(Math.sqrt(sumSquares / mono.frameCount()) > 100,
                    "the downmixed effect is silent");
        } finally {
            MemoryUtil.memFree(stereo.samples());
            MemoryUtil.memFree(mono.samples());
        }
    }

    @Test
    void askingStbForFewerChannelsThanTheFileHasIsNeverDone() throws IOException {
        // The landmine this class is shaped around, pinned as a property rather than as a comment.
        //
        // stb_vorbis takes a channel count, and asking a stereo file for one looks exactly like the way to
        // get a downmix. It is not: that path asserts buf_c == 2, the assert is compiled out of a release
        // build, and it writes two shorts per frame into a buffer sized for one. Measured on a stereo
        // effect, 806 shorts past the end and a hard SIGSEGV inside liblwjgl_stb.
        //
        // So read() must always ask for the stream's own channel count. If that ever changes, a buffer
        // sized for exactly the frames requested is where it will be noticed - here, rather than as a
        // crash somewhere unrelated.
        try (VorbisStream stream = VorbisStream.open(CHOP)) {
            ShortBuffer exact = MemoryUtil.memAllocShort(stream.totalFrames() * stream.channels());
            try {
                int frames = stream.read(exact);
                assertEquals(stream.totalFrames(), frames,
                        "a buffer sized at the file's own channel count should decode it whole");
            } finally {
                MemoryUtil.memFree(exact);
            }
        }
    }

    @Test
    void rewindingStartsTheSameAudioAgain() throws IOException {
        // What makes a looping bed loop: the decoder goes back to the top mid-buffer rather than the
        // source being restarted, so the join lands inside a buffer instead of at a seam between two.
        try (VorbisStream stream = VorbisStream.open(CHOP)) {
            int frames = 1000;
            ShortBuffer first = MemoryUtil.memAllocShort(frames * stream.channels());
            ShortBuffer second = MemoryUtil.memAllocShort(frames * stream.channels());
            try {
                stream.read(first);
                stream.rewind();
                stream.read(second);
                for (int i = 0; i < frames * stream.channels(); i++) {
                    assertEquals(first.get(i), second.get(i), "sample " + i + " differs after a rewind");
                }
            } finally {
                MemoryUtil.memFree(first);
                MemoryUtil.memFree(second);
            }
        }
    }

    @Test
    void anUnreadableFileIsAnExceptionRatherThanACrash() {
        assertThrows(IOException.class, () -> VorbisStream.open(Path.of("assets/Audio/nope.ogg")));
        // A real file that is not Vorbis: it must be rejected rather than decoded as noise, and the
        // native memory holding it must be released on the way out.
        assertThrows(IOException.class, () -> VorbisStream.open(Path.of("pom.xml")));
    }
}
