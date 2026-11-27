package org.zeronura.nulzs.audio;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One continuous layer of sound — the score, or the ambience bed — that can change what it is playing
 * without a gap.
 *
 * <p>Two {@link StreamVoice}s and nothing else: the outgoing track fades down on one while the incoming
 * track fades up on the other, and they swap roles. One voice cannot do this, because a single OpenAL
 * source plays one buffer queue and switching it means stopping — a hole in the sound at exactly the
 * moment the game is trying to change mood smoothly.
 *
 * <p>Generic on purpose: this class knows about paths, gains and seconds, and has never heard of a biome
 * or a time of day. What <i>should</i> be playing is a game decision and lives in {@code MusicDirector}.
 *
 * <h2>Asking for what is already playing does nothing</h2>
 * {@link #play} compares against the last request and returns early. That is not an optimisation, it is
 * what makes this usable from a per-frame update at all: a director that recomputes "the grasslands theme
 * should be playing" sixty times a second would otherwise restart the track sixty times a second. The
 * same guard {@code Animator.setClip} needs, for the same reason.
 *
 * <h2>The caller only ever states intent</h2>
 * {@link #play} touches neither OpenAL nor the disk — it records what is wanted, and {@link #pump} acts on
 * it from the audio thread. Starting a track means reading several megabytes off disk and decoding two
 * seconds of audio to prime the ring, which is a hitch the frame loop should never wear; and it keeps
 * every AL call for streaming on one thread, so the only thing crossing between them is a field.
 */
public final class CrossfadeChannel {

    /** What the game has asked for. Immutable and swapped as a unit, so the audio thread cannot read a
     *  half-changed request. */
    private record Request(Path path, boolean looping, float fadeSeconds, float trim) {
    }

    private final StreamVoice front;
    private final StreamVoice back;
    /** Which of the two is carrying the current track; the other is either idle or fading out. */
    private boolean frontIsCurrent = true;

    private volatile Request requested = new Request(null, true, 0f, 1f);
    private Request applied = new Request(null, true, 0f, 1f);
    private volatile float busGain = 1f;
    private float appliedBusGain = 1f;

    public CrossfadeChannel() {
        front = new StreamVoice();
        back = new StreamVoice();
    }

    /**
     * Asks for {@code path} to be brought in over {@code fadeSeconds}, taking whatever is playing out over
     * the same time. Returns at once; the swap happens on the audio thread.
     *
     * <p>A null path fades to silence and keeps it — a state the score genuinely wants, unlike the
     * ambience bed: there is nothing to play in a biome with no theme, and silence beats the wrong theme.
     *
     * @param looping whether to run forever; a stinger is the one thing here that is not
     * @param trim    the track's own measured loudness trim, kept apart from the channel volume
     */
    public void play(Path path, boolean looping, float fadeSeconds, float trim) {
        Request current = requested;
        if (Objects.equals(current.path(), path) && current.looping() == looping) {
            return;
        }
        requested = new Request(path, looping, fadeSeconds, trim);
    }

    /** Silence, keeping whatever trim was last set — {@code play(null, ...)} spelled for the common case. */
    public void silence(float fadeSeconds) {
        play(null, true, fadeSeconds, 1f);
    }

    /** Services both voices, and performs any pending change. Called from the audio thread. */
    public void pump(float dt) {
        float wantedBus = busGain;
        if (wantedBus != appliedBusGain) {
            appliedBusGain = wantedBus;
            front.setBusGain(wantedBus);
            back.setBusGain(wantedBus);
        }

        Request wanted = requested;
        if (!Objects.equals(wanted.path(), applied.path()) || wanted.looping() != applied.looping()) {
            applySwap(wanted);
        }

        front.pump(dt);
        back.pump(dt);
        // A voice that has faded out and is no longer wanted still holds an open decoder and its buffers.
        // Releasing it here rather than at the next change is what stops a walk along a biome boundary
        // accumulating one stopped stream per crossing.
        StreamVoice idle = frontIsCurrent ? back : front;
        if (idle.isSilent() && idle.isPlaying()) {
            idle.stop();
        }
    }

    private void applySwap(Request wanted) {
        applied = wanted;
        StreamVoice outgoing = frontIsCurrent ? front : back;
        StreamVoice incoming = frontIsCurrent ? back : front;
        outgoing.fadeTo(0f, wanted.fadeSeconds());
        if (wanted.path() == null) {
            return;
        }
        try {
            // The incoming voice may still be fading out from a change a moment ago — walking along a
            // biome edge does exactly this. Restarting it from silence is correct: it is the same source,
            // and whatever it held is already inaudible.
            incoming.play(wanted.path(), wanted.looping(), 0f, wanted.trim());
            incoming.setBusGain(appliedBusGain);
            incoming.fadeTo(1f, wanted.fadeSeconds());
            frontIsCurrent = !frontIsCurrent;
        } catch (IOException e) {
            // A track that will not load costs its own layer and nothing else. Music is the one part of a
            // game whose absence is survivable; taking the session down for it would not be.
            System.err.println("Could not play " + wanted.path() + ": " + e);
        }
    }

    /** The channel volume — where a player's music or ambience slider lands. */
    public void setBusGain(float gain) {
        busGain = Math.max(0f, gain);
    }

    /** What the game has most recently asked for, or null for silence. */
    public Path current() {
        return requested.path();
    }

    /**
     * Buffers filled across both voices — the diagnostic that says whether the ring is actually turning.
     *
     * <p>Zero means nothing ever started; a value that stops climbing means the audio thread has stopped
     * servicing it. See {@link StreamVoice#buffersFilled()}.
     */
    public long buffersFilled() {
        return front.buffersFilled() + back.buffersFilled();
    }

    public void delete() {
        front.delete();
        back.delete();
    }
}
