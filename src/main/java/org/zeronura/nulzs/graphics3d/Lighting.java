package org.zeronura.nulzs.graphics3d;

import org.joml.Vector3f;
import org.zeronura.nulzs.render.SceneLighting;

/**
 * Ready-made {@link SceneLighting} for common times of day. The fog colour doubles as the sky colour a 3D
 * scene clears to, so each preset's fog is chosen to read as its sky.
 */
public final class Lighting {

    private Lighting() {
    }

    public static SceneLighting daylight() {
        return new SceneLighting(
                new Vector3f(0.45f, 0.75f, 0.35f).normalize(),
                new Vector3f(1.00f, 0.96f, 0.88f),
                new Vector3f(0.46f, 0.52f, 0.62f),
                new Vector3f(0.90f, 0.92f, 1.00f),
                new Vector3f(0.62f, 0.76f, 0.90f),
                80f, 480f, 1.0f, 0.6f);
    }

    public static SceneLighting sunset() {
        return new SceneLighting(
                new Vector3f(0.85f, 0.22f, 0.30f).normalize(),
                new Vector3f(1.00f, 0.72f, 0.50f),
                new Vector3f(0.36f, 0.34f, 0.48f),
                new Vector3f(1.00f, 0.70f, 0.50f),
                new Vector3f(0.90f, 0.62f, 0.50f),
                50f, 400f, 1.0f, 0.55f);
    }

    public static SceneLighting overcast() {
        return new SceneLighting(
                new Vector3f(0.30f, 0.85f, 0.20f).normalize(),
                new Vector3f(0.78f, 0.80f, 0.84f),
                new Vector3f(0.52f, 0.55f, 0.60f),
                new Vector3f(0.70f, 0.72f, 0.76f),
                new Vector3f(0.66f, 0.69f, 0.73f),
                30f, 300f, 0.8f, 0.25f);
    }

    public static SceneLighting night() {
        return new SceneLighting(
                new Vector3f(-0.30f, 0.70f, 0.40f).normalize(),
                new Vector3f(0.42f, 0.50f, 0.72f),
                new Vector3f(0.12f, 0.15f, 0.24f),
                new Vector3f(0.40f, 0.50f, 0.80f),
                new Vector3f(0.05f, 0.07f, 0.12f),
                30f, 280f, 0.7f, 0.4f);
    }

    /** The same lighting with a different fog range — shorter for mist, longer for clear air. */
    public static SceneLighting withFog(SceneLighting base, float fogStart, float fogEnd) {
        return new SceneLighting(base.toSun(), base.sunColor(), base.ambientColor(), base.rimColor(),
                base.fogColor(), fogStart, fogEnd, fogEnd, base.fogHeightFalloff(), base.fogBaseY(),
                base.saturation(), base.shadowStrength(), base.skyZenith(), base.skyMiddle(), base.skyHorizon(),
                base.sunDirection());
    }
}
