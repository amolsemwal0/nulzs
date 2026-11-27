package org.zeronura.engine.audio;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ShortBuffer;
import java.nio.file.Path;

import static org.lwjgl.openal.AL10.*;

/**
 * A short sound, decoded once and kept in an OpenAL buffer to be fired any number of times.
 *
 * <p>The resident half of the pair with {@link StreamVoice}: a clip is loaded whole because it is short
 * and is wanted <i>now</i>, with no decode between the click and the sound. Measured on this game's
 * effects, that costs about 50 kB each.
 *
 * <h2>A POSITIONAL SOUND MUST BE MONO — and every file here is stereo</h2>
 * OpenAL spatialises a source only when its buffer has <b>one</b> channel; the specification says a
 * multi-channel buffer "will be played without 3D spatialization", and OpenAL Soft implements exactly
 * that. So a stereo buffer given a world position is not merely approximate — the position, the distance
 * attenuation and the panning are all <i>silently discarded</i>, and the sound plays flat at full volume
 * wherever the player is standing. Nothing errors, which is what makes it dangerous: chopping a tree on
 * the horizon would sound precisely as close as chopping the one you are touching.
 *
 * <p>Measured, every effect this game ships is 44.1 kHz <b>stereo</b> — so this is not a corner case, it is
 * the only case. {@link #load} therefore downmixes by default, in Java, because stb cannot be asked to do
 * it (see {@link VorbisStream}).
 *
 * <p>{@link #loadStereo} is the deliberate exception, for a sound that is <i>not</i> in the world — a UI
 * click, a stinger — where the stereo image is the point and there is no position to lose.
 */
public final class AudioClip {

    private int buffer;
    private final float seconds;
    private final int channels;

    private AudioClip(int buffer, float seconds, int channels) {
        this.buffer = buffer;
        this.seconds = seconds;
        this.channels = channels;
    }

    /** Loads a sound for playback <b>in the world</b>: downmixed to mono so it can be positioned. */
    public static AudioClip load(Path path) throws IOException {
        return upload(VorbisStream.decode(path, true));
    }

    /**
     * Loads a sound keeping its stereo image, which means it can never be positioned.
     *
     * <p>For sounds that belong to the player rather than to a place — see the class note.
     */
    public static AudioClip loadStereo(Path path) throws IOException {
        return upload(VorbisStream.decode(path, false));
    }

    /**
     * Uploads sound the program made itself — a synthesised effect — rather than decoded from a file.
     *
     * @param samples  16-bit samples, interleaved when {@code channels} is 2; mono ones can be positioned
     */
    public static AudioClip fromSamples(short[] samples, int channels, int sampleRate) {
        ShortBuffer buffer = MemoryUtil.memAllocShort(samples.length);
        buffer.put(samples).flip();
        return upload(new PcmData(buffer, channels, sampleRate));
    }

    private static AudioClip upload(PcmData pcm) {
        int buffer = alGenBuffers();
        int format = pcm.channels() == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
        alBufferData(buffer, format, pcm.samples(), pcm.sampleRate());
        float seconds = pcm.seconds();
        int channels = pcm.channels();
        // The PCM has been copied into the driver's own storage by alBufferData, so the staging buffer is
        // ours to release and must be released — it was memAlloc'd by the decoder.
        MemoryUtil.memFree(pcm.samples());
        return new AudioClip(buffer, seconds, channels);
    }

    public int buffer() {
        return buffer;
    }

    public float seconds() {
        return seconds;
    }

    /** 1 for anything that can be positioned in the world. */
    public int channels() {
        return channels;
    }

    /** Whether this clip can carry a world position at all — see the class note. */
    public boolean isPositional() {
        return channels == 1;
    }

    public void delete() {
        if (buffer != 0) {
            alDeleteBuffers(buffer);
            buffer = 0;
        }
    }
}
