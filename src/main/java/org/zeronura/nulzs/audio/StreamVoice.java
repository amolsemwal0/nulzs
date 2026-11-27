package org.zeronura.nulzs.audio;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ShortBuffer;
import java.nio.file.Path;

import static org.lwjgl.openal.AL10.*;

/**
 * One long sound being decoded as it plays: a music track, an ambience bed.
 *
 * <p>The streaming half of the pair with {@link AudioClip}, and the reason it exists is arithmetic. This
 * game's music and ambience run 60 to 148 seconds each, which decoded is 10 to 25 MB apiece and
 * <b>160 MB for the set</b> — more than every texture in the game put together. Compressed they are 3 to
 * 4 MB. So a track is held encoded and pulled through a handful of small buffers, and what is resident is
 * measured in kilobytes.
 *
 * <h2>How the ring works</h2>
 * {@link #BUFFER_COUNT} buffers of {@link #BUFFER_SECONDS} each are queued on the source. Each
 * {@link #pump()} asks OpenAL how many it has finished with, refills exactly those and queues them again.
 * The queue is the lead time: {@value #BUFFER_COUNT} × {@value #BUFFER_SECONDS} s of audio is already
 * inside the driver, so the game can miss a pump — or several — without the sound breaking.
 *
 * <h2>Looping is done here, not by OpenAL</h2>
 * {@code AL_LOOPING} on a streaming source is wrong: it loops the <i>queue</i>, not the file, so a track
 * would repeat whatever fragment happened to be buffered. Instead a refill that runs short of data rewinds
 * the decoder and <b>keeps filling the same buffer</b> from the top of the file. That is what makes the
 * loop gapless — the join lands in the middle of a buffer rather than at the seam between two — which
 * matters most for the ambience beds, each of them exactly 60.0 s and plainly authored to run forever.
 *
 * <h2>Gain is ramped here rather than by the caller</h2>
 * A crossfade has to sound smooth, and a game's frame delta is neither smooth nor bounded — the loading
 * screen alone blocks for seconds. {@link #fadeTo} sets a target and {@link #pump()} walks toward it using
 * the audio thread's own clock, so a fade takes the time it was asked for whatever the frame rate is
 * doing. Gain applies at mix time, not when a buffer is queued, so the lead time above does not delay it.
 */
public final class StreamVoice {

    /** Buffers in the ring. Four is two seconds of lead at the size below — see the class note. */
    public static final int BUFFER_COUNT = 4;

    /**
     * Seconds of audio per buffer.
     *
     * <p>Measured, Vorbis decodes at about 950x realtime here, so refilling one of these costs ~0.5 ms.
     * That is affordable even on the main thread; it is on its own thread anyway because the game blocks
     * for <b>seconds</b> at a time when loading a session, and an audio ring cannot survive that at any
     * buffer size worth holding.
     */
    public static final float BUFFER_SECONDS = 0.5f;

    private final int source;
    private final int[] buffers = new int[BUFFER_COUNT];
    private ShortBuffer staging;

    private VorbisStream stream;
    private boolean looping;
    private boolean playing;
    /** Set once the decoder runs out on a non-looping track; the tail is still in the queue. */
    private boolean drained;

    /**
     * How many buffers this voice has filled since it started — a diagnostic, and the only one that can
     * tell the two silent failures apart.
     *
     * <p>Sound has no frame to inspect, and "nothing is playing" looks the same whether the track never
     * opened, the audio thread died, or the gain is zero. A count that is zero says the ring never turned;
     * one that is stuck says it turned and stopped; one that climbs steadily says the only thing left to
     * suspect is a volume. That is the same job {@code shadow_map.png} does for shadows.
     */
    private volatile long buffersFilled;

    /**
     * Where this voice sits in its own crossfade, 0 to 1. Not the gain OpenAL is given.
     *
     * <p>Kept apart from {@link #busGain} because the two change for unrelated reasons and at unrelated
     * rates: the mix is a crossfade the director is driving over seconds, the bus is a volume slider the
     * player moved. Folded into one number, moving the slider mid-crossfade would either be overwritten by
     * the next fade step or snap the fade to wherever the slider was.
     */
    private float mix;
    private float targetMix;
    /** Mix units per second; 0 means jump straight there. */
    private float fadeRate;
    /** The channel volume this voice sits under — see {@link #setBusGain}. */
    private float busGain = 1f;
    /**
     * This particular track's loudness trim, set when it is opened.
     *
     * <p>A third multiplier rather than being folded into either of the others, because it belongs to
     * neither: the mix is a crossfade in progress, the bus is where the player left a slider, and this is
     * a measured property of one file. Folded into the bus it would be wiped by the next volume change;
     * folded into the mix it would be wiped by the next fade step.
     */
    private float trim = 1f;

    public StreamVoice() {
        source = alGenSources();
        alGenBuffers(buffers);
        // Never positioned: the score and the ambience bed are not in the world, they are on top of it.
        // Relative-to-listener at the origin is the standard way to say "no distance, no panning" and is
        // what keeps a stereo buffer's image intact.
        alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
        alSource3f(source, AL_POSITION, 0f, 0f, 0f);
        alSourcef(source, AL_GAIN, 0f);
    }

    /**
     * Starts a track, replacing whatever was playing.
     *
     * @param startGain the gain to begin at — 0 when this voice is the incoming half of a crossfade
     */
    public void play(Path path, boolean looping, float startGain, float trim) throws IOException {
        stop();
        stream = VorbisStream.open(path);
        this.looping = looping;
        this.trim = Math.max(0f, trim);
        this.buffersFilled = 0;
        this.drained = false;
        this.mix = startGain;
        this.targetMix = startGain;
        this.fadeRate = 0f;
        applyGain();

        int capacity = (int) (BUFFER_SECONDS * stream.sampleRate()) * stream.channels();
        if (staging == null || staging.capacity() < capacity) {
            if (staging != null) {
                MemoryUtil.memFree(staging);
            }
            staging = MemoryUtil.memAllocShort(capacity);
        }
        for (int buffer : buffers) {
            if (!fill(buffer)) {
                break;
            }
            alSourceQueueBuffers(source, buffer);
        }
        alSourcePlay(source);
        playing = true;
    }

    /**
     * Services the ring: recycles finished buffers and steps any fade in progress.
     *
     * <p>Called from the audio thread. {@code dt} is that thread's own elapsed time, not the frame's.
     */
    public void pump(float dt) {
        stepFade(dt);
        if (!playing) {
            return;
        }
        int processed = alGetSourcei(source, AL_BUFFERS_PROCESSED);
        while (processed-- > 0) {
            int buffer = alSourceUnqueueBuffers(source);
            if (fill(buffer)) {
                alSourceQueueBuffers(source, buffer);
            }
        }
        if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
            if (alGetSourcei(source, AL_BUFFERS_QUEUED) > 0) {
                // Ran dry: the game stalled for longer than the ring holds. Restarting is the only thing
                // to do, and it is audible as a small gap rather than as silence from here on.
                alSourcePlay(source);
            } else {
                playing = false;
            }
        }
    }

    /**
     * Decodes the next chunk into {@code buffer}.
     *
     * @return false once a non-looping track has nothing left, so the caller stops requeueing
     */
    private boolean fill(int buffer) {
        if (stream == null || drained) {
            return false;
        }
        staging.clear();
        int channels = stream.channels();
        int wanted = staging.capacity() / channels;
        int frames = 0;
        while (frames < wanted) {
            staging.position(frames * channels);
            int got = stream.read(staging);
            if (got <= 0) {
                if (!looping) {
                    drained = true;
                    break;
                }
                // Straight over the join, into the same buffer: this is what makes the loop gapless.
                stream.rewind();
                int retry = 0;
                staging.position(frames * channels);
                retry = stream.read(staging);
                if (retry <= 0) {
                    drained = true;
                    break;
                }
                frames += retry;
                continue;
            }
            frames += got;
        }
        if (frames <= 0) {
            return false;
        }
        staging.position(0);
        staging.limit(frames * channels);
        alBufferData(buffer, channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16,
                staging, stream.sampleRate());
        staging.limit(staging.capacity());
        buffersFilled++;
        return true;
    }

    private void stepFade(float dt) {
        if (mix == targetMix) {
            return;
        }
        if (fadeRate <= 0f) {
            mix = targetMix;
        } else {
            float step = fadeRate * dt;
            mix = mix < targetMix
                    ? Math.min(targetMix, mix + step)
                    : Math.max(targetMix, mix - step);
        }
        applyGain();
    }

    /** Ramps toward {@code target} over {@code seconds}; 0 seconds sets it outright. */
    public void fadeTo(float target, float seconds) {
        targetMix = Math.max(0f, target);
        fadeRate = seconds <= 0f ? 0f : Math.abs(targetMix - mix) / seconds;
        if (fadeRate == 0f) {
            mix = targetMix;
            applyGain();
        }
    }

    /**
     * The channel volume this voice plays under, which is where a player's music slider lands.
     *
     * <p>Multiplied with the crossfade position rather than replacing it — see {@link #mix}.
     */
    public void setBusGain(float busGain) {
        float clamped = Math.max(0f, busGain);
        if (clamped != this.busGain) {
            this.busGain = clamped;
            applyGain();
        }
    }

    private void applyGain() {
        alSourcef(source, AL_GAIN, mix * busGain * trim);
    }

    /** Where this voice is in its crossfade — what a director reads to know a fade has finished. */
    public float gain() {
        return mix;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Buffers filled since this voice last started — see the field note. */
    public long buffersFilled() {
        return buffersFilled;
    }

    /** Whether the voice has faded out completely, so it can be reused. */
    public boolean isSilent() {
        return mix <= 0.0001f && targetMix <= 0.0001f;
    }

    public void stop() {
        if (stream != null) {
            alSourceStop(source);
            // Detaches every buffer at once, queued and processed alike. Unqueueing them one at a time is
            // the other way and needs the counts to be right; this cannot be got wrong.
            alSourcei(source, AL_BUFFER, 0);
            stream.close();
            stream = null;
        }
        playing = false;
        drained = false;
        mix = 0f;
        targetMix = 0f;
        applyGain();
    }

    public void delete() {
        stop();
        alDeleteSources(source);
        alDeleteBuffers(buffers);
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
    }
}
