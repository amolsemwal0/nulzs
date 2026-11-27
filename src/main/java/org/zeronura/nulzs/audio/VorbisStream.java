package org.zeronura.nulzs.audio;

import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.stb.STBVorbis.*;

/**
 * The only place in the engine that touches {@code org.lwjgl.stb.STBVorbis} — the same rule
 * {@code AssetLoader} follows for Assimp and {@code Texture} for {@code STBImage}.
 *
 * <p>Two ways to get at a file, because the game has two kinds of sound and they want opposite things.
 * {@link #decode} reads the whole thing into memory for a short effect; an instance of this class is an
 * open decoder a long track is pulled through a chunk at a time. Neither touches OpenAL, so the decoding
 * is unit-testable with no audio device and can run off the main thread — the same split {@code MeshData}
 * makes between a chunk worker and a GL upload.
 *
 * <h2>Ogg Vorbis cost this project no new dependency</h2>
 * {@code STBVorbis} ships in the <b>same</b> {@code lwjgl-stb} artifact and the same native library as the
 * {@code STBImage} that loads every texture and the {@code STBTruetype} that rasterises the font. Only
 * {@code lwjgl-openal} had to be added, and that is for playback rather than for decoding.
 *
 * <h2>NEVER ASK STB FOR FEWER CHANNELS THAN THE FILE HAS</h2>
 * {@code stb_vorbis_get_samples_short_interleaved} takes a channel count, and asking a stereo file for one
 * channel looks exactly like the way to get a mono downmix. It is not: internally that path asserts
 * {@code buf_c == 2}, and in a release build — which is what ships — the assert is compiled out and it
 * writes <b>two shorts per frame into a buffer sized for one</b>. Measured on {@code chop_1.ogg}: 806
 * shorts written past the end of the buffer, and a hard {@code SIGSEGV} inside {@code liblwjgl_stb.so}.
 * It is not even a downmix — the data that lands is the stereo stream.
 *
 * <p>So this class always decodes at the file's own channel count and does the downmix in Java
 * ({@link #downmixToMono}). That is not a preference: see {@link AudioClip} for why a positional sound
 * <i>must</i> be mono, which makes this conversion compulsory for every effect in the game.
 *
 * <h2>The encoded bytes must outlive the handle</h2>
 * {@code stb_vorbis_open_memory} does not copy — it keeps a pointer into the buffer it is handed and reads
 * from it for the whole life of the decoder. The buffer is therefore a field here rather than a local, and
 * it is {@code memAlloc}/{@code memFree}, never {@code create}-and-let-go: the allocator that hands memory
 * out is the only thing entitled to take it back, which is the rule the font packer's double free had to
 * be fixed to follow.
 */
public final class VorbisStream implements AutoCloseable {

    private final ByteBuffer encoded;
    private long handle;
    private final int channels;
    private final int sampleRate;
    private final int totalFrames;

    private VorbisStream(ByteBuffer encoded, long handle, int channels, int sampleRate, int totalFrames) {
        this.encoded = encoded;
        this.handle = handle;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.totalFrames = totalFrames;
    }

    /**
     * Opens a file for streaming: the compressed bytes are read into native memory once and decoded from
     * there on demand.
     *
     * <p>The whole <i>compressed</i> file is resident, which for the longest track here is 3.9 MB against
     * the 25 MB its PCM would take — the point of streaming — and it means a refill never touches the
     * disk, so a chunk cannot arrive late because something else was reading a file.
     *
     * @throws IOException if the file cannot be read or is not decodable Vorbis
     */
    public static VorbisStream open(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        encoded.put(bytes).flip();
        int[] error = new int[1];
        long handle = stb_vorbis_open_memory(encoded, error, null);
        if (handle == MemoryUtil.NULL) {
            MemoryUtil.memFree(encoded);
            throw new IOException("Not decodable as Ogg Vorbis (stb error " + error[0] + "): " + path);
        }
        int channels;
        int sampleRate;
        try (STBVorbisInfo info = STBVorbisInfo.malloc()) {
            stb_vorbis_get_info(handle, info);
            channels = info.channels();
            sampleRate = info.sample_rate();
        }
        int frames = stb_vorbis_stream_length_in_samples(handle);
        return new VorbisStream(encoded, handle, channels, sampleRate, frames);
    }

    /**
     * Decodes a whole file into memory, optionally downmixed to mono.
     *
     * <p>For short effects only. The music and ambience here run 60 to 148 seconds, which is 10 to 25 MB
     * of PCM each and 160 MB for the set — see {@link StreamVoice}, which exists so that never happens.
     *
     * @param mono collapse a stereo file to one channel, which is what a positional sound requires
     */
    public static PcmData decode(Path path, boolean mono) throws IOException {
        try (VorbisStream stream = open(path)) {
            // Sized at the file's own channel count, never at the requested one: see the class note on
            // what stb does with a buffer narrower than the stream.
            ShortBuffer raw = MemoryUtil.memAllocShort(stream.totalFrames * stream.channels);
            int frames = stream.read(raw);
            raw.limit(frames * stream.channels);
            if (mono && stream.channels > 1) {
                ShortBuffer mixed = downmixToMono(raw, stream.channels);
                MemoryUtil.memFree(raw);
                return new PcmData(mixed, 1, stream.sampleRate);
            }
            return new PcmData(raw, stream.channels, stream.sampleRate);
        }
    }

    /**
     * Averages every frame's channels into one.
     *
     * <p>The average rather than the sum, or anything already mixed near full scale clips the moment two
     * channels agree — which for these effects, recorded close and centred, is most of the file.
     *
     * <p>The result is a freshly {@code memAlloc}ed buffer; the caller owns it.
     */
    public static ShortBuffer downmixToMono(ShortBuffer interleaved, int channels) {
        int frames = interleaved.remaining() / channels;
        ShortBuffer mono = MemoryUtil.memAllocShort(frames);
        int base = interleaved.position();
        for (int frame = 0; frame < frames; frame++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += interleaved.get(base + frame * channels + c);
            }
            mono.put(frame, (short) (sum / channels));
        }
        return mono;
    }

    /**
     * Pulls the next samples into {@code dest}, at this stream's own channel count.
     *
     * @return frames actually written, which is short of the buffer only at the end of the file
     */
    public int read(ShortBuffer dest) {
        return stb_vorbis_get_samples_short_interleaved(handle, channels, dest);
    }

    /** Back to the first sample — how a looping track continues rather than stopping. */
    public void rewind() {
        stb_vorbis_seek_start(handle);
    }

    public int channels() {
        return channels;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int totalFrames() {
        return totalFrames;
    }

    public float seconds() {
        return totalFrames / (float) sampleRate;
    }

    @Override
    public void close() {
        if (handle != MemoryUtil.NULL) {
            stb_vorbis_close(handle);
            handle = MemoryUtil.NULL;
            // Only after the decoder is closed: it has been reading from this buffer the whole time.
            MemoryUtil.memFree(encoded);
        }
    }
}
