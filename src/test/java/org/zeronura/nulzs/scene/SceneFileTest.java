package org.zeronura.nulzs.scene;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.zeronura.nulzs.data.Json;
import org.zeronura.nulzs.graphics2d.Color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneFileTest {

    /** A component with one field of every kind a scene file holds. */
    public static class Probe extends Component {
        public enum Mood { CALM, ANGRY }

        public int count = 1;
        public float speed = 2.5f;
        public boolean on = true;
        public String label = "hi";
        public Mood mood = Mood.CALM;
        public Color tint = Color.WHITE;
        public final Vector3f offset = new Vector3f();
        public Entity target;
        public Probe partner;
        public final Signal fired = new Signal();
        public transient int notSaved = 5;
        public int calls;

        public void ping() {
            calls++;
        }
    }

    private static final ClassLoader LOADER = SceneFileTest.class.getClassLoader();

    private static Map<String, Object> reread(Map<String, Object> json) {
        return Json.parseObject(Json.compact(json));
    }

    @Test
    void valuesReferencesHierarchyAndSignalsSurviveSavingAndLoading() {
        Scene original = new Scene();
        Entity door = new Entity("Door");
        Entity hinge = new Entity("Hinge");
        door.addChild(hinge);
        original.add(door);
        door.tag("solid");
        door.transform().position.set(1f, 2f, 3f);
        door.transform().setYaw(0.5f);
        Probe probe = door.add(new Probe());
        probe.count = 7;
        probe.speed = 0.1f;
        probe.on = false;
        probe.label = "door";
        probe.mood = Probe.Mood.ANGRY;
        probe.tint = new Color(0.2f, 0.4f, 0.6f, 0.8f);
        probe.offset.set(4f, 5f, 6f);
        probe.notSaved = 99;
        Probe hingeProbe = hinge.add(new Probe());
        probe.target = hinge;
        probe.partner = hingeProbe;
        probe.fired.connect(hinge, Probe.class.getName(), "ping");

        Map<Entity, String> ids = new HashMap<>();
        Map<String, Object> json = reread(SceneFile.serialize(original, ids, new SceneSettings(), null));

        Scene loaded = new Scene();
        SceneFile.Loaded result = SceneFile.instantiate(json, loaded, LOADER);
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        Entity door2 = loaded.find("Door");
        Entity hinge2 = loaded.find("Hinge");
        assertNotSame(door, door2);
        assertSame(door2, hinge2.parent());
        assertTrue(door2.hasTag("solid"));
        assertEquals(new Vector3f(1f, 2f, 3f), door2.transform().position);
        assertEquals(0.5f, 2f * (float) Math.atan2(door2.transform().rotation.y, door2.transform().rotation.w), 1e-5f);

        Probe probe2 = door2.get(Probe.class);
        assertEquals(7, probe2.count);
        assertEquals(0.1f, probe2.speed);
        assertEquals(false, probe2.on);
        assertEquals("door", probe2.label);
        assertEquals(Probe.Mood.ANGRY, probe2.mood);
        assertEquals(0.6f, probe2.tint.b(), 0.003f);
        assertEquals(0.8f, probe2.tint.a(), 0.003f);
        assertEquals(new Vector3f(4f, 5f, 6f), probe2.offset);
        assertEquals(5, probe2.notSaved, "transient fields are the component's own and not saved");
        assertSame(hinge2, probe2.target);
        assertSame(hinge2.get(Probe.class), probe2.partner);

        probe2.fired.emit();
        assertEquals(1, hinge2.get(Probe.class).calls, "the wired method is called on the loaded copy");
        assertEquals(0, hingeProbe.calls, "and not on the original");
    }

    @Test
    void scenesSavedUnderTheEnginesOldPackageNameStillLoad() {
        Scene original = new Scene();
        Entity door = new Entity("Door");
        Entity hinge = new Entity("Hinge");
        original.add(door);
        original.add(hinge);
        Probe probe = door.add(new Probe());
        probe.count = 7;
        probe.partner = hinge.add(new Probe());
        probe.fired.connect(hinge, Probe.class.getName(), "ping");
        String text = Json.compact(SceneFile.serialize(original, new HashMap<>(), new SceneSettings(), null));
        assertTrue(text.contains("org.zeronura.nulzs.scene.SceneFileTest$Probe"), text);
        Map<String, Object> json = Json.parseObject(text.replace("org.zeronura.nulzs.", "org.zeronura.engine."));

        Scene loaded = new Scene();
        SceneFile.Loaded result = SceneFile.instantiate(json, loaded, LOADER);
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        Probe probe2 = loaded.find("Door").get(Probe.class);
        Entity hinge2 = loaded.find("Hinge");
        assertEquals(7, probe2.count);
        assertSame(hinge2.get(Probe.class), probe2.partner, "a component reference by the old name still finds it");
        probe2.fired.emit();
        assertEquals(1, hinge2.get(Probe.class).calls, "a signal wired by the old name still reaches it");
        String saved = Json.compact(SceneFile.serialize(loaded, result.ids(), result.settings(), null));
        assertTrue(!saved.contains("org.zeronura.engine."), "saving again writes today's name: " + saved);
    }

    @Test
    void floatsAreSavedInTheirShortForm() {
        assertEquals(0.1, ComponentFields.decimal(0.1f));
        Scene scene = new Scene();
        Entity entity = new Entity("e");
        scene.add(entity);
        entity.add(new Probe()).speed = 0.3f;
        String text = Json.compact(SceneFile.serialize(scene, new HashMap<>(), new SceneSettings(), null));
        assertTrue(text.contains("\"speed\":0.3"), text);
    }

    @Test
    void aComponentWhoseClassIsMissingIsKeptAsItWasWhenSavedAgain() {
        Map<String, Object> json = reread(Map.of("format", 1L, "entities", List.of(Map.of(
                "id", "a1", "name", "Thing",
                "components", List.of(Map.of("type", "com.example.Gone", "enabled", true, "fields", Map.of("power", 9L)))))));
        Scene scene = new Scene();
        SceneFile.Loaded loaded = SceneFile.instantiate(json, scene, LOADER);
        Entity thing = scene.find("Thing");
        MissingComponent missing = assertInstanceOf(MissingComponent.class, thing.components().getFirst());
        assertEquals("com.example.Gone", missing.type());
        assertTrue(loaded.problems().stream().anyMatch(p -> p.contains("com.example.Gone")));

        String saved = Json.compact(SceneFile.serialize(scene, loaded.ids(), loaded.settings(), null));
        assertTrue(saved.contains("\"type\":\"com.example.Gone\""), saved);
        assertTrue(saved.contains("\"power\":9"), saved);
    }

    @Test
    void pastedCopiesPointAtEachOtherAndKeepPointingOutside() {
        Scene scene = new Scene();
        Entity outside = new Entity("Outside");
        Entity a = new Entity("A");
        Entity b = new Entity("B");
        a.addChild(b);
        scene.add(outside);
        scene.add(a);
        Probe probe = a.add(new Probe());
        probe.target = b;
        probe.partner = outside.add(new Probe());

        Map<Entity, String> ids = new HashMap<>();
        List<Object> copied = SceneFile.serializeEntities(List.of(a), ids);
        Map<String, Entity> byId = new HashMap<>();
        ids.forEach((entity, id) -> byId.put(id, entity));
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : Json.parse(Json.compact(copied)) instanceof List<?> list ? list : List.of()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) item;
            maps.add(map);
        }
        List<Entity> tops = SceneFile.instantiateEntities(maps, scene, LOADER, byId, ids, true, new ArrayList<>());

        assertEquals(1, tops.size());
        Entity a2 = tops.getFirst();
        assertNotSame(a, a2);
        assertNotEquals(ids.get(a), ids.get(a2), "the copy has an id of its own");
        Probe probe2 = a2.get(Probe.class);
        assertSame(a2.children().getFirst(), probe2.target, "a link inside the copy follows the copy");
        assertSame(outside.get(Probe.class), probe2.partner, "a link outside it still points where it did");
    }

    @Test
    void settingsRoundTrip() {
        SceneSettings settings = new SceneSettings();
        settings.mode = SceneSettings.Mode.FLAT_2D;
        settings.lighting = SceneSettings.Sky.NIGHT;
        settings.groundHeight = -2f;
        SceneSettings back = SceneSettings.fromJson(reread(settings.toJson()), new ArrayList<>());
        assertEquals(SceneSettings.Mode.FLAT_2D, back.mode);
        assertEquals(SceneSettings.Sky.NIGHT, back.lighting);
        assertEquals(-2f, back.groundHeight);
    }

    @Test
    void fieldNamesReadAsWords() {
        assertEquals("Jump Height", ComponentFields.label("jumpHeight"));
        assertEquals("Shape 3D", ComponentFields.label("Shape3D"));
        assertEquals("Box Collider 3D", ComponentFields.label("BoxCollider3D"));
        assertEquals("Max Speed", ComponentFields.label("MAX_SPEED"));
        assertEquals("World 3D", ComponentFields.label("WORLD_3D"));
        assertEquals("Cube", ComponentFields.label("CUBE"));
        assertEquals("HTTP Server", ComponentFields.label("HTTPServer"));
    }

    @Test
    void onlyPublicFieldsOfKnownKindsAreSettings() {
        List<String> names = ComponentFields.of(Probe.class).stream().map(ComponentFields.Property::name).toList();
        assertEquals(List.of("count", "speed", "on", "label", "mood", "tint", "offset", "target", "partner", "fired", "calls"),
                names);
        assertTrue(ComponentFields.isCreatable(Probe.class));
        assertTrue(!ComponentFields.isCreatable(MissingComponent.class));
    }
}
