package org.zeronura.engine.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

/**
 * The OpenAL device and context — audio's counterpart to what {@code Window} does for GL.
 *
 * <h2>A missing sound card must never stop the game</h2>
 * This is the whole reason the class exists rather than the two ALC calls being made inline. A player with
 * no audio device, a broken PulseAudio socket or a container with no sound is a completely ordinary
 * situation, and the correct response to it is a silent game, not a stack trace on startup. So
 * {@link #open()} never throws: it reports {@link #isAvailable()} false and every call above it becomes a
 * no-op. That is the same degrade-rather-than-fail rule the font loader follows for a missing typeface and
 * {@code GroundMaterials} for missing art — and it is more important here, because unlike a texture,
 * nothing on screen reveals that audio is absent.
 *
 * <h2>Process-wide, not per session</h2>
 * Built once in {@code Engine.run} and destroyed when the window is. Music plays on the title screen and
 * carries into a session, so a device torn down and rebuilt at that boundary would cost a gap in the sound
 * at exactly the moment the game is trying to feel continuous — and opening a device is the one expensive
 * thing here (it starts a mixer thread in the driver).
 *
 * <p>Unlike GL, OpenAL's context is <b>not</b> thread-affine in the way a GL context is: LWJGL binds it
 * per-process by default, so the audio thread in {@link AudioSystem} can call into AL without any
 * make-current dance. That is what lets streaming run off the main thread at all.
 */
public final class AudioDevice {

    private long device = MemoryUtil.NULL;
    private long context = MemoryUtil.NULL;
    private boolean available;

    /**
     * Opens the default output device. Safe to call when there is none; check {@link #isAvailable()}.
     *
     * @return whether sound is actually going to come out
     */
    public boolean open() {
        try {
            device = alcOpenDevice((ByteBuffer) null);
            if (device == MemoryUtil.NULL) {
                System.err.println("No audio device available - running silently.");
                return false;
            }
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            context = alcCreateContext(device, (IntBuffer) null);
            if (context == MemoryUtil.NULL || !alcMakeContextCurrent(context)) {
                System.err.println("Could not create an audio context - running silently.");
                closeQuietly();
                return false;
            }
            AL.createCapabilities(alcCaps);

            // Inverse-distance is how sound actually falls off, and clamped so a source the player is
            // standing on top of does not divide its way to an enormous gain. What each sound considers
            // "close" is per source (AL_REFERENCE_DISTANCE), because a pickaxe strike and a waterfall
            // are audible over very different ranges.
            alDistanceModel(AL_INVERSE_DISTANCE_CLAMPED);
            available = true;
            return true;
        } catch (Throwable t) {
            // Throwable, not Exception: a missing or unloadable native library arrives as an
            // UnsatisfiedLinkError, and that is exactly the case this method exists to survive.
            System.err.println("Audio unavailable (" + t + ") - running silently.");
            closeQuietly();
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Where the player's ears are, and which way they face.
     *
     * <p>Position and orientation together in one call because they are one fact: a listener updated for
     * position but not facing pans every sound in the world to the wrong side the moment the player turns.
     *
     * @param forward unit vector the listener looks along
     * @param up      unit vector out of the top of the listener's head
     */
    public void setListener(float x, float y, float z,
                            float forwardX, float forwardY, float forwardZ,
                            float upX, float upY, float upZ) {
        if (!available) {
            return;
        }
        alListener3f(AL_POSITION, x, y, z);
        alListenerfv(AL_ORIENTATION,
                new float[]{forwardX, forwardY, forwardZ, upX, upY, upZ});
    }

    /** Master gain, applied by the driver over everything. 0 is silence. */
    public void setMasterGain(float gain) {
        if (available) {
            alListenerf(AL_GAIN, Math.max(0f, gain));
        }
    }

    /** The driver's own name, for a diagnostic line — "OpenAL Soft" and its version. */
    public String describe() {
        if (!available) {
            return "silent (no device)";
        }
        return alGetString(AL_RENDERER) + " / " + alGetString(AL_VERSION);
    }

    public void destroy() {
        available = false;
        closeQuietly();
    }

    private void closeQuietly() {
        if (context != MemoryUtil.NULL) {
            alcMakeContextCurrent(MemoryUtil.NULL);
            alcDestroyContext(context);
            context = MemoryUtil.NULL;
        }
        if (device != MemoryUtil.NULL) {
            alcCloseDevice(device);
            device = MemoryUtil.NULL;
        }
    }
}
