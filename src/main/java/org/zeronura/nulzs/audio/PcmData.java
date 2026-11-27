package org.zeronura.nulzs.audio;

import java.nio.ShortBuffer;

/**
 * A block of decoded 16-bit PCM, and the two numbers needed to interpret it.
 *
 * <p>Deliberately free of OpenAL as well as of any file format: it is what a decoder produces and what a
 * buffer consumes, and keeping it in the middle is what lets the decoding be unit-tested with no audio
 * device — the same split {@code MeshData} makes between a chunk worker and a GL upload, and for the same
 * reason. Decoding happens off the main thread; only the upload may not.
 *
 * <p>Samples are <b>interleaved</b> when {@link #channels} is 2 (L, R, L, R, …), which is the layout both
 * {@code stb_vorbis} and OpenAL use, so no shuffling happens anywhere in between.
 *
 * @param samples  interleaved 16-bit samples, positioned at 0 and limited to the data
 * @param channels 1 or 2
 * @param sampleRate frames per second (44100 for every file this game ships)
 */
public record PcmData(ShortBuffer samples, int channels, int sampleRate) {

    /** How many frames (sample-groups across all channels) this block holds. */
    public int frameCount() {
        return samples.remaining() / channels;
    }

    /** How long this block lasts, in seconds. */
    public float seconds() {
        return frameCount() / (float) sampleRate;
    }
}
