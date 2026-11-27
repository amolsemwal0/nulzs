package org.zeronura.nulzs.audio;

import org.joml.Vector3f;


import static org.lwjgl.openal.AL10.*;

/**
 * Everything the game talks to when it wants a sound: a pool of one-shot voices for effects, two
 * continuous layers for the score and the ambience bed, and the thread that keeps them fed.
 *
 * <p>The engine-side facade, in the same position {@code Renderer} occupies for drawing — it knows about
 * gains, positions and file paths, and nothing about biomes, weather or tools. Which track belongs to
 * which moment is {@code MusicDirector}'s decision, and which sound a pickaxe makes is {@code Sounds}'.
 *
 * <h2>Silence is a working state</h2>
 * If {@link #open()} finds no device, every method here still works and does nothing. That is deliberate
 * and is why callers need no null checks and no {@code isAvailable} guards: a machine with no sound card
 * is an ordinary machine, and the game has to be exactly as playable on it.
 *
 * <h2>Streaming runs on its own thread; effects fire on the caller's</h2>
 * The two halves have opposite requirements, so they are treated differently rather than uniformly.
 *
 * <p>A stream must never be late. Vorbis decoding is cheap — measured at about 950x realtime, so refilling
 * half a second of audio costs ~0.5 ms — so cost is <b>not</b> the reason it is threaded. The reason is
 * that this game blocks the main thread for <i>seconds</i> when it loads a session, and for milliseconds
 * at a time when it streams terrain; a ring buffer cannot ride that out at any size worth holding, and an
 * audible dropout is far more noticeable than a dropped frame.
 *
 * <p>An effect must never be early or late by more than a few milliseconds — a pickaxe strike heard a
 * frame after the impact reads as broken — so it is fired synchronously, from wherever the game announced
 * it. Nothing is shared: the pool's sources belong to the caller's thread and the streams' to the audio
 * thread, and OpenAL Soft handles the driver end.
 */
public final class AudioSystem {

    /**
     * One-shot voices. Measured, this driver offers 256 sources; this is what a game frame can plausibly
     * want at once, and past it the oldest sound is stolen rather than the newest dropped — a strike you
     * just made matters more than one already half over.
     */
    public static final int ONE_SHOT_VOICES = 32;

    /** How often the audio thread services the streams. 100 Hz — see {@link StreamVoice#BUFFER_SECONDS}. */
    private static final long PUMP_INTERVAL_MILLIS = 10;

    private final AudioDevice device = new AudioDevice();

    private CrossfadeChannel music;
    private CrossfadeChannel ambience;
    private int[] voices = new int[0];
    /** Round-robin cursor, so a repeated sound spreads across the pool rather than stealing one voice. */
    private int nextVoice;

    private Thread pumpThread;
    private volatile boolean running;

    private float sfxGain = 1f;

    /** Brings audio up. Never throws; check the return only to log it. */
    public boolean open() {
        if (!device.open()) {
            return false;
        }
        music = new CrossfadeChannel();
        ambience = new CrossfadeChannel();
        voices = new int[ONE_SHOT_VOICES];
        for (int i = 0; i < voices.length; i++) {
            voices[i] = alGenSources();
        }
        running = true;
        pumpThread = new Thread(this::pumpLoop, "theta-audio");
        // A daemon so a stuck audio thread can never keep the process alive after the window has gone.
        pumpThread.setDaemon(true);
        pumpThread.start();
        return true;
    }

    public boolean isAvailable() {
        return device.isAvailable();
    }

    public String describe() {
        return device.describe();
    }

    private void pumpLoop() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (float) ((now - last) / 1_000_000_000.0);
            last = now;
            try {
                music.pump(dt);
                ambience.pump(dt);
            } catch (RuntimeException e) {
                // The audio thread dying takes all sound with it for the rest of the process and says
                // nothing. Reporting and carrying on is strictly better than that.
                System.err.println("Audio pump error: " + e);
            }
            try {
                Thread.sleep(PUMP_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The score. */
    public CrossfadeChannel music() {
        return music;
    }

    /** The ambience bed — birdsong, wind, rain. */
    public CrossfadeChannel ambience() {
        return ambience;
    }

    /**
     * Where the player's ears are and which way they face.
     *
     * <p>Called once a frame from the main thread with the camera's own basis, so what is heard and what
     * is seen agree about which way is left.
     */
    public void setListener(Vector3f position, Vector3f forward, Vector3f up) {
        device.setListener(position.x, position.y, position.z,
                forward.x, forward.y, forward.z, up.x, up.y, up.z);
    }

    /**
     * Fires a clip at a point in the world.
     *
     * <p>The clip must be mono or it will not be positioned at all — see {@link AudioClip}. Silently
     * does nothing if there is no device.
     *
     * @param gain             0..1 before the effects bus is applied
     * @param pitch            1 is as recorded; the caller varies this so a repeated sound is not identical
     * @param referenceDistance metres over which the sound is at full volume
     * @param maxDistance      metres past which it stops getting quieter (it never reaches zero on its own)
     */
    public void playAt(AudioClip clip, Vector3f position, float gain, float pitch,
                       float referenceDistance, float maxDistance) {
        if (!device.isAvailable() || clip == null) {
            return;
        }
        int source = takeVoice();
        alSourcei(source, AL_BUFFER, clip.buffer());
        alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);
        alSource3f(source, AL_POSITION, position.x, position.y, position.z);
        alSourcef(source, AL_REFERENCE_DISTANCE, referenceDistance);
        alSourcef(source, AL_MAX_DISTANCE, maxDistance);
        alSourcef(source, AL_ROLLOFF_FACTOR, 1f);
        alSourcef(source, AL_GAIN, gain * sfxGain);
        alSourcef(source, AL_PITCH, pitch);
        alSourcePlay(source);
    }

    /**
     * Fires a clip with no position — a UI sound, or a stinger about the player rather than about a place.
     *
     * <p>Relative to the listener at the origin, which is how a stereo clip keeps its image.
     */
    public void playUi(AudioClip clip, float gain, float pitch) {
        if (!device.isAvailable() || clip == null) {
            return;
        }
        int source = takeVoice();
        alSourcei(source, AL_BUFFER, clip.buffer());
        alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
        alSource3f(source, AL_POSITION, 0f, 0f, 0f);
        alSourcef(source, AL_ROLLOFF_FACTOR, 0f);
        alSourcef(source, AL_GAIN, gain * sfxGain);
        alSourcef(source, AL_PITCH, pitch);
        alSourcePlay(source);
    }

    /**
     * The next free voice, or the oldest in use.
     *
     * <p>Scans for one that has finished rather than trusting the cursor, because sounds here differ in
     * length by an order of magnitude and a plain round robin would steal a long one that is still audible
     * while short ones sat idle beside it.
     */
    private int takeVoice() {
        for (int i = 0; i < voices.length; i++) {
            int candidate = voices[(nextVoice + i) % voices.length];
            if (alGetSourcei(candidate, AL_SOURCE_STATE) != AL_PLAYING) {
                nextVoice = (nextVoice + i + 1) % voices.length;
                return candidate;
            }
        }
        int stolen = voices[nextVoice];
        nextVoice = (nextVoice + 1) % voices.length;
        alSourceStop(stolen);
        return stolen;
    }

    /**
     * The four volume sliders.
     *
     * <p>Master goes to the listener, so it covers everything including sounds already in flight. The other
     * three are per bus. The effects bus is applied when a sound <i>starts</i>, so moving that slider does
     * not change a hit already ringing — which at half a second long is not something anyone can notice.
     */
    public void setVolumes(float master, float musicGain, float ambienceGain, float effectsGain) {
        device.setMasterGain(master);
        sfxGain = Math.max(0f, effectsGain);
        if (music != null) {
            music.setBusGain(musicGain);
            ambience.setBusGain(ambienceGain);
        }
    }

    /**
     * Stops every effect immediately and lets go of the clips they played, so those clips can be deleted. The streams
     * are left alone — see {@code GameAudio}.
     */
    public void stopEffects() {
        if (!device.isAvailable()) {
            return;
        }
        for (int voice : voices) {
            alSourceStop(voice);
            // A stopped voice still holds its clip's buffer, and OpenAL refuses to delete a buffer a voice holds: the
            // clip's delete would fail with an error and its sound stay in memory for the rest of the process.
            alSourcei(voice, AL_BUFFER, AL_NONE);
        }
    }

    public void destroy() {
        running = false;
        if (pumpThread != null) {
            try {
                // Joined rather than abandoned: the streams' AL objects are deleted below, and deleting a
                // source the audio thread is mid-pump on is a use-after-free inside the driver.
                pumpThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pumpThread = null;
        }
        if (music != null) {
            music.delete();
            ambience.delete();
            music = null;
            ambience = null;
        }
        for (int voice : voices) {
            alDeleteSources(voice);
        }
        voices = new int[0];
        device.destroy();
    }
}
