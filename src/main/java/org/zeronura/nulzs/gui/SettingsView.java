package org.zeronura.nulzs.gui;

import org.zeronura.nulzs.core.Engine;
import org.zeronura.nulzs.core.UserSettings;
import org.zeronura.nulzs.core.UserSettings.AmbientOcclusion;
import org.zeronura.nulzs.core.UserSettings.AntiAliasing;
import org.zeronura.nulzs.core.UserSettings.Bloom;
import org.zeronura.nulzs.core.UserSettings.ShadowQuality;
import org.zeronura.nulzs.core.UserSettings.Grass;
import org.zeronura.nulzs.core.UserSettings.Particles;
import org.zeronura.nulzs.core.UserSettings.SunRays;
import org.zeronura.nulzs.graphics2d.Color;
import org.zeronura.nulzs.input.Input;
import org.zeronura.nulzs.input.InputMap;
import org.zeronura.nulzs.input.InputMap.Binding;
import org.zeronura.nulzs.input.InputMap.Definition;
import org.zeronura.nulzs.input.Key;
import org.zeronura.nulzs.input.MouseButton;
import org.zeronura.nulzs.ui.Rect;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The options every game shares — video, audio and controls — drawn with {@link SettingsRows} into a rectangle the
 * game gives it, and applied through {@link Engine#applySettings()} the moment they change.
 *
 * <p>A game draws one {@link Section} at a time beside categories of its own. Each section scrolls on its own with
 * the mouse wheel. Changes are saved straight away, except a slider, which saves when it is let go rather than once
 * a frame while it moves.
 *
 * <p>The controls section lists every rebindable action of the {@link InputMap} by group, each with two slots: click
 * one and press a key (or a mouse button other than the left or right) to bind it; Backspace or Delete empties it,
 * Esc leaves it as it was. While a slot waits for its key, {@link #isCapturing()} is true, and the game should leave
 * the keyboard alone.
 */
public final class SettingsView {

    /** The standard groups of settings, each drawn as one category. */
    public enum Section {
        VIDEO("Video", "Display, frame rate, graphics quality"),
        AUDIO("Audio", "Volume of each kind of sound"),
        CONTROLS("Controls", "Camera, and every key, rebindable");

        private final String label;
        private final String caption;

        Section(String label, String caption) {
            this.label = label;
            this.caption = caption;
        }

        public String label() {
            return label;
        }

        /** A line saying what is in it, for a menu of categories. */
        public String caption() {
            return caption;
        }
    }

    private static final Color ERROR = Color.rgb(0xF07178);
    private static final Color WARNING = Color.rgb(0xF2C94C);
    private static final float SCROLL_STEP = 66f;

    private final Engine engine;
    private final Map<Section, Float> scroll = new EnumMap<>(Section.class);
    /** How far each section's rows could scroll when last drawn. */
    private final Map<Section, Float> scrollLimit = new EnumMap<>(Section.class);
    private boolean unsaved;
    private String error;
    /** The action and slot waiting for a key, or null. */
    private String capturingAction;
    private int capturingSlot;
    /** The frame capture began on, so the click that began it is not taken as the binding. */
    private long captureFrame;

    public SettingsView(Engine engine) {
        this.engine = engine;
    }

    /** Whether a key slot is waiting for a key: the game should not act on keys meanwhile, Esc included. */
    public boolean isCapturing() {
        return capturingAction != null;
    }

    /** Stops waiting for a key, leaving the slot as it was. */
    public void cancelCapture() {
        capturingAction = null;
    }

    /**
     * Draws one section into {@code area}, scrolled as the player left it.
     *
     * @param extra rows a game adds at the end of the section, such as notes on its own keys; may be null
     */
    public void draw(Gui gui, Section section, Rect area, Consumer<SettingsRows> extra) {
        float offset = scroll.getOrDefault(section, 0f);
        Input input = engine.input();
        if (gui.hovered(area) && input.scroll() != 0f && !isCapturing()) {
            offset -= input.scroll() * SCROLL_STEP;
            // Kept inside the rows before they are laid out, so a turn past either end moves nothing, rather than
            // drawing them a frame too far and snapping back.
            offset = Math.max(0f, Math.min(scrollLimit.getOrDefault(section, Float.MAX_VALUE), offset));
        }
        // Room at the foot for an error line.
        Rect rowsArea = error == null ? area : new Rect(area.x(), area.y(), area.width(), area.height() - 36f);
        SettingsRows rows = new SettingsRows(gui, rowsArea, Math.max(0f, offset));
        UserSettings settings = engine.settings();
        switch (section) {
            case VIDEO -> video(rows, settings);
            case AUDIO -> audio(rows, settings);
            case CONTROLS -> controls(gui, rows, settings);
        }
        if (extra != null) {
            extra.accept(rows);
        }
        float max = rows.finish();
        scrollLimit.put(section, max);
        scroll.put(section, Math.max(0f, Math.min(max, offset)));

        // A slider saves when released, wherever the pointer ended up.
        if (unsaved && !input.isMouseDown(MouseButton.LEFT)) {
            save();
        }
        if (error != null) {
            gui.text(error, area.x(), area.y() + area.height() - 26f, 2f, ERROR);
        }
    }

    // --- Video ----------------------------------------------------------------------------------------------

    private void video(SettingsRows rows, UserSettings settings) {
        rows.heading("Display");
        int mode = rows.choice("video:mode", "Display mode", List.of("Windowed", "Fullscreen"), settings.fullscreen() ? 1 : 0);
        if ((mode == 1) != settings.fullscreen()) {
            settings.setFullscreen(mode == 1);
            changed(true);
        }
        List<int[]> sizes = windowSizes();
        List<String> sizeNames = sizes.stream().map(size -> size[0] + " x " + size[1]).toList();
        int current = nearest(sizes, settings.windowWidth(), settings.windowHeight());
        int size = rows.choice("video:size", "Window size", settings.fullscreen() ? "used when windowed" : null,
                sizeNames, current);
        if (size != current) {
            settings.setWindowSize(sizes.get(size)[0], sizes.get(size)[1]);
            changed(true);
        }
        boolean vsync = rows.toggle("video:vsync", "VSync", "matches the monitor, no tearing", settings.vsync());
        if (vsync != settings.vsync()) {
            settings.setVsync(vsync);
            changed(true);
        }
        List<String> limits = new ArrayList<>();
        int limitIndex = 0;
        for (int i = 0; i < UserSettings.FRAME_RATE_LIMITS.length; i++) {
            int limit = UserSettings.FRAME_RATE_LIMITS[i];
            limits.add(limit == 0 ? "Unlimited" : limit + " FPS");
            if (limit == settings.frameRateLimit()) {
                limitIndex = i;
            }
        }
        int limit = rows.choice("video:limit", "Frame rate limit", limits, limitIndex);
        if (limit != limitIndex) {
            settings.setFrameRateLimit(UserSettings.FRAME_RATE_LIMITS[limit]);
            changed(true);
        }
        boolean fps = rows.toggle("video:fps", "Show FPS", "or press F3", settings.showFps());
        if (fps != settings.showFps()) {
            settings.setShowFps(fps);
            changed(true);
        }

        rows.heading("Graphics");
        ShadowQuality[] qualities = ShadowQuality.values();
        int shadow = rows.choice("video:shadows", "Shadows", java.util.Arrays.stream(qualities).map(ShadowQuality::label).toList(),
                settings.shadowQuality().ordinal());
        if (shadow != settings.shadowQuality().ordinal()) {
            settings.setShadowQuality(qualities[shadow]);
            changed(true);
        }
        AmbientOcclusion[] levels = AmbientOcclusion.values();
        int occlusion = rows.choice("video:ao", "Ambient occlusion", "soft shade in corners and under things",
                java.util.Arrays.stream(levels).map(AmbientOcclusion::label).toList(), settings.ambientOcclusion().ordinal());
        if (occlusion != settings.ambientOcclusion().ordinal()) {
            settings.setAmbientOcclusion(levels[occlusion]);
            changed(true);
        }
        Bloom[] glows = Bloom.values();
        int bloom = rows.choice("video:bloom", "Bloom", "bright light glows; sprites stay crisp",
                java.util.Arrays.stream(glows).map(Bloom::label).toList(), settings.bloom().ordinal());
        if (bloom != settings.bloom().ordinal()) {
            settings.setBloom(glows[bloom]);
            changed(true);
        }
        SunRays[] beams = SunRays.values();
        int rays = rows.choice("video:sunrays", "Sun rays", "beams of sunlight through the trees; needs shadows",
                java.util.Arrays.stream(beams).map(SunRays::label).toList(), settings.sunRays().ordinal());
        if (rays != settings.sunRays().ordinal()) {
            settings.setSunRays(beams[rays]);
            changed(true);
        }
        Particles[] amounts = Particles.values();
        int particles = rows.choice("video:particles", "Particles", "falling leaves, fireflies, dust at your feet",
                java.util.Arrays.stream(amounts).map(Particles::label).toList(), settings.particles().ordinal());
        if (particles != settings.particles().ordinal()) {
            settings.setParticles(amounts[particles]);
            changed(true);
        }
        Grass[] fields = Grass.values();
        int grass = rows.choice("video:grass", "Grass", "blades growing over grassy ground, swaying in the wind",
                java.util.Arrays.stream(fields).map(Grass::label).toList(), settings.grass().ordinal());
        if (grass != settings.grass().ordinal()) {
            settings.setGrass(fields[grass]);
            changed(true);
        }
        AntiAliasing[] kinds = AntiAliasing.values();
        int smoothing = rows.choice("video:aa", "Anti-aliasing", "MSAA smooths edges; FXAA also softens pixel art",
                java.util.Arrays.stream(kinds).map(AntiAliasing::label).toList(), settings.antiAliasing().ordinal());
        if (smoothing != settings.antiAliasing().ordinal()) {
            settings.setAntiAliasing(kinds[smoothing]);
            changed(true);
        }
        List<String> filters = new ArrayList<>();
        int filterIndex = 0;
        for (int i = 0; i < UserSettings.TEXTURE_FILTERING.length; i++) {
            int samples = UserSettings.TEXTURE_FILTERING[i];
            filters.add(samples == 1 ? "Trilinear" : samples + "x Anisotropic");
            if (samples == settings.textureFiltering()) {
                filterIndex = i;
            }
        }
        int filter = rows.choice("video:filter", "Texture filtering", filters, filterIndex);
        if (filter != filterIndex) {
            settings.setTextureFiltering(UserSettings.TEXTURE_FILTERING[filter]);
            changed(true);
        }
        // In degrees, written without the sign, which the font does not have.
        float fov = rows.slider("video:fov", "Field of view", settings.fieldOfView(), UserSettings.MIN_FIELD_OF_VIEW,
                UserSettings.MAX_FIELD_OF_VIEW, String.valueOf(Math.round(settings.fieldOfView())));
        if (Math.round(fov) != Math.round(settings.fieldOfView())) {
            settings.setFieldOfView(Math.round(fov));
            changed(false);
        }
        rows.note("Lower shadows, ambient occlusion, grass and texture filtering, and leave supersampling off, for more frames "
                + "on a slower graphics card. Occlusion, bloom, sun rays and anti-aliasing are for the 3D views. The field of "
                + "view is the walking camera's; battles frame their own shot.");
        if (rows.button("video:reset", "Reset video to defaults")) {
            settings.setVsync(true);
            settings.setFrameRateLimit(0);
            settings.setShadowQuality(ShadowQuality.HIGH);
            settings.setAmbientOcclusion(AmbientOcclusion.HIGH);
            settings.setBloom(Bloom.SOFT);
            settings.setSunRays(SunRays.SOFT);
            settings.setParticles(Particles.HIGH);
            settings.setGrass(Grass.HIGH);
            settings.setAntiAliasing(UserSettings.DEFAULT_ANTI_ALIASING);
            settings.setTextureFiltering(8);
            settings.setFieldOfView(UserSettings.DEFAULT_FIELD_OF_VIEW);
            changed(true);
        }
    }

    // --- Audio ----------------------------------------------------------------------------------------------

    private void audio(SettingsRows rows, UserSettings settings) {
        rows.heading("Volume");
        float value = volume(rows, "Master", "master", settings.masterVolume());
        if (value != settings.masterVolume()) {
            settings.setMasterVolume(value);
            changed(false);
        }
        value = volume(rows, "Music", "music", settings.musicVolume());
        if (value != settings.musicVolume()) {
            settings.setMusicVolume(value);
            changed(false);
        }
        value = volume(rows, "Ambience", "ambience", settings.ambienceVolume());
        if (value != settings.ambienceVolume()) {
            settings.setAmbienceVolume(value);
            changed(false);
        }
        value = volume(rows, "Effects", "effects", settings.effectsVolume());
        if (value != settings.effectsVolume()) {
            settings.setEffectsVolume(value);
            changed(false);
        }
        rows.note("Master scales the other three: music, the world's ambience (wind, rain, water) and sound effects.");
    }

    private static float volume(SettingsRows rows, String label, String id, float value) {
        return rows.slider("audio:" + id, label, value, 0f, 1f, Math.round(value * 100f) + "%");
    }

    // --- Controls -------------------------------------------------------------------------------------------

    private void controls(Gui gui, SettingsRows rows, UserSettings settings) {
        InputMap actions = engine.input().actions();
        capture(actions, settings);

        rows.heading("Camera");
        float sensitivity = rows.slider("controls:sensitivity", "Mouse sensitivity", settings.mouseSensitivity(),
                UserSettings.MIN_SENSITIVITY, UserSettings.MAX_SENSITIVITY,
                String.format(Locale.ROOT, "%.2fx", settings.mouseSensitivity()));
        if (sensitivity != settings.mouseSensitivity()) {
            settings.setMouseSensitivity(sensitivity);
            changed(false);
        }
        boolean invert = rows.toggle("controls:invert", "Invert camera up/down", settings.invertY());
        if (invert != settings.invertY()) {
            settings.setInvertY(invert);
            changed(true);
        }

        String group = null;
        for (Definition definition : actions.definitions()) {
            if (!definition.group().equals(group)) {
                group = definition.group();
                rows.heading(group);
            }
            binding(gui, rows, actions, definition);
        }
        if (isCapturing()) {
            rows.note("Press a key to bind it. Backspace or Delete leaves the slot empty; Esc keeps what it was.");
        }
        if (rows.button("controls:reset", "Reset keys to defaults")) {
            capturingAction = null;
            actions.resetToDefaults();
            settings.setBindings(actions.saved());
            save();
        }
    }

    /** An action's row: its name, then a slot for each binding. A binding another action in its group shares is marked. */
    private void binding(Gui gui, SettingsRows rows, InputMap actions, Definition definition) {
        List<Binding> bound = actions.bindingsOf(definition.action());
        String clash = null;
        for (Binding binding : bound) {
            for (Definition other : actions.sharing(definition.action(), binding)) {
                if (other.group().equals(definition.group())) {
                    clash = "also " + other.label();
                }
            }
        }
        String detail = clash != null ? clash : bound.isEmpty() ? "unbound" : actions.isDefault(definition.action()) ? null : "changed";
        Rect control = rows.custom(definition.label(), detail);
        if (control == null) {
            return;
        }
        if (clash != null) {
            // A mark beside the slots: one of these keys does two things in the same place.
            gui.rect(control.x() - 10f, control.y(), 4f, control.height(), WARNING);
        }
        float gap = 10f;
        float slotWidth = (control.width() - gap) / InputMap.SLOTS;
        for (int slot = 0; slot < InputMap.SLOTS; slot++) {
            Rect box = new Rect(control.x() + slot * (slotWidth + gap), control.y(), slotWidth, control.height());
            boolean waiting = definition.action().equals(capturingAction) && capturingSlot == slot;
            String text = waiting ? "Press a key..." : slot < bound.size() ? bound.get(slot).label() : "-";
            boolean hover = gui.hoveredButton(box);
            gui.rect(box, waiting ? gui.style().buttonPressed : hover ? gui.style().buttonHover : gui.style().button);
            if (waiting) {
                gui.outline(box, 2f, gui.style().accent);
            }
            float scale = Math.min(2.3f, (box.width() - 16f) / Math.max(1f, gui.textWidth(text, 1f)));
            gui.textCentered(text, box, scale, slot < bound.size() || waiting ? gui.style().text : gui.style().mutedText);
            if (gui.clicked("bind:" + definition.action() + ":" + slot, box)) {
                capturingAction = definition.action();
                // A slot past the bindings there are fills the next free one.
                capturingSlot = Math.min(slot, bound.size());
                captureFrame = engine.time().frame();
            }
        }
    }

    /** Takes the key (or mouse button) pressed for the slot waiting for one. */
    private void capture(InputMap actions, UserSettings settings) {
        if (capturingAction == null || engine.time().frame() == captureFrame) {
            return;
        }
        Input input = engine.input();
        int key = input.pressedKey();
        int button = input.pressedMouseButton();
        if (key == Key.ESCAPE || button == MouseButton.LEFT || button == MouseButton.RIGHT) {
            // The left and right buttons drive the menus, so a click anywhere leaves the slot alone.
            input.consumeKey(Key.ESCAPE);
            input.consumeMouse(MouseButton.RIGHT);
            capturingAction = null;
            return;
        }
        Binding binding = null;
        if (key == Key.BACKSPACE || key == Key.DELETE) {
            actions.rebind(capturingAction, capturingSlot, null);
        } else if (key >= 0) {
            binding = Binding.key(key);
        } else if (button >= 0) {
            binding = Binding.mouse(button);
        } else {
            return;
        }
        if (binding != null) {
            actions.rebind(capturingAction, capturingSlot, binding);
            input.consumeKey(key);
        }
        capturingAction = null;
        settings.setBindings(actions.saved());
        save();
    }

    // --- Saving ---------------------------------------------------------------------------------------------

    /** The preset window sizes that fit the monitor, always at least the smallest. */
    private List<int[]> windowSizes() {
        int[] monitor = engine.window().monitorSize();
        List<int[]> sizes = new ArrayList<>();
        for (int[] size : UserSettings.WINDOW_SIZES) {
            if (monitor == null || (size[0] <= monitor[0] && size[1] <= monitor[1])) {
                sizes.add(size);
            }
        }
        if (sizes.isEmpty()) {
            sizes.add(UserSettings.WINDOW_SIZES[0]);
        }
        return sizes;
    }

    private static int nearest(List<int[]> sizes, int width, int height) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < sizes.size(); i++) {
            long dw = sizes.get(i)[0] - width;
            long dh = sizes.get(i)[1] - height;
            long distance = dw * dw + dh * dh;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private void changed(boolean saveNow) {
        engine.applySettings();
        unsaved = true;
        if (saveNow) {
            save();
        }
    }

    private void save() {
        unsaved = false;
        try {
            engine.settings().save();
            error = null;
        } catch (IOException e) {
            error = "Could not save settings: " + e.getMessage();
        }
    }
}
