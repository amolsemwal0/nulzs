package org.zeronura.engine.scene;

import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.graphics3d.Lighting;
import org.zeronura.engine.graphics3d.World3D;
import org.zeronura.engine.render.SceneLighting;

import java.util.List;
import java.util.Map;

/**
 * What a scene file says about the scene as a whole rather than any entity in it: whether it is a 3D world or a flat 2D
 * one, its light, and its floor. Public fields, so an editor's Inspector shows them as it shows a component's.
 */
public final class SceneSettings {

    /** Whether the scene is a lit 3D world or a flat 2D one. */
    public enum Mode {
        WORLD_3D("3D"), FLAT_2D("2D");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The light a 3D scene is under. */
    public enum Sky {
        DAY, SUNSET, OVERCAST, NIGHT;

        public SceneLighting lighting() {
            return switch (this) {
                case DAY -> Lighting.daylight();
                case SUNSET -> Lighting.sunset();
                case OVERCAST -> Lighting.overcast();
                case NIGHT -> Lighting.night();
            };
        }
    }

    public Mode mode = Mode.WORLD_3D;
    @Tooltip("The time of day a 3D scene is lit for; its sky is the matching colour")
    public Sky lighting = Sky.DAY;
    public boolean shadows = true;
    @Tooltip("What a 2D scene clears to behind its sprites")
    public Color background = Color.rgb(0x1B2330);
    @Tooltip("An endless solid floor that characters stand on, at Ground Height")
    public boolean groundCollision = true;
    public float groundHeight = 0f;

    /** Puts the settings into effect on a scene: its light, shadows and floor, or its 2D background. */
    public void apply(Scene scene) {
        scene.setBackgroundColor(background);
        if (mode == Mode.WORLD_3D) {
            World3D world = scene.world3D();
            world.setVisible(true);
            world.setLighting(lighting.lighting());
            world.setShadows(shadows);
            if (groundCollision) {
                world.setGroundPlane(groundHeight);
            } else {
                world.clearGroundPlane();
            }
        } else if (scene.has3D()) {
            scene.world3D().setVisible(false);
        }
    }

    public Map<String, Object> toJson() {
        return ComponentFields.save(this, entity -> null);
    }

    public static SceneSettings fromJson(Object json, List<String> problems) {
        SceneSettings settings = new SceneSettings();
        if (json instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) map;
            ComponentFields.loadValues(settings, fields, problems);
        }
        return settings;
    }

    public SceneSettings copy() {
        return fromJson(toJson(), new java.util.ArrayList<>());
    }
}
