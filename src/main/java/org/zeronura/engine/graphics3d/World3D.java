package org.zeronura.engine.graphics3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.zeronura.engine.core.Engine;
import org.zeronura.engine.core.EngineConfig;
import org.zeronura.engine.core.UserSettings;
import org.zeronura.engine.debug.DebugMode;
import org.zeronura.engine.graphics2d.Color;
import org.zeronura.engine.physics.AABB;
import org.zeronura.engine.physics.PhysicsWorld;
import org.zeronura.engine.physics.SolidField;
import org.zeronura.engine.render.Camera;
import org.zeronura.engine.render.FoliageMaterial;
import org.zeronura.engine.render.GroundTextures;
import org.zeronura.engine.render.LitImpostor;
import org.zeronura.engine.render.LitImpostorRenderer;
import org.zeronura.engine.render.PointLights;
import org.zeronura.engine.render.Renderer;
import org.zeronura.engine.render.SceneLighting;
import org.zeronura.engine.render.ShadowMap;
import org.zeronura.engine.render.SkyDome;
import org.zeronura.engine.render.StaticInstance;
import org.zeronura.engine.render.StaticMesh;
import org.zeronura.engine.render.TexturedInstance;
import org.zeronura.engine.render.ViewFrustum;
import org.zeronura.engine.scene.Scene;
import org.zeronura.engine.terrain.TerrainSurface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scene's 3D world: the camera, the sun and sky, optional terrain, and the collision world characters
 * walk in. Get it with {@code scene.world3D()}.
 *
 * <p>Rendering is automatic. Each frame the world draws, in order: the sun's shadow map, terrain, terrain
 * decorations, every {@link MeshRenderer} and {@link TexturedMeshRenderer}, every {@link SpriteBillboard}, water, and
 * every {@link ModelRenderer}. The screen is cleared to
 * the lighting's fog colour, which is what distant geometry fades into, so the horizon has no seam.
 *
 * <p>Collision for {@link CharacterController3D} comes from three sources combined: the terrain surface, an
 * optional flat ground plane, and every {@link BoxCollider3D} in the scene.
 */
public final class World3D {

    private final Scene scene;
    private final Camera camera;
    private final PhysicsWorld physics = new PhysicsWorld();
    private final List<AABB> obstacles = new ArrayList<>();
    private final List<StaticMesh> ownedMeshes = new ArrayList<>();
    private final List<StaticInstance> instances = new ArrayList<>();
    private final List<StaticInstance> shadowCasters = new ArrayList<>();
    private final Map<FoliageMaterial, List<TexturedInstance>> sprites = new LinkedHashMap<>();
    private final Map<FoliageMaterial, List<TexturedInstance>> textured = new LinkedHashMap<>();
    private final Map<FoliageMaterial, List<TexturedInstance>> texturedShadows = new LinkedHashMap<>();
    private final Vector3f shadowFocus = new Vector3f();
    /** What the camera sees this frame, and what the sun's shadow box takes in: what is outside either is not drawn into it. */
    private final ViewFrustum cameraView = new ViewFrustum();
    private final ViewFrustum shadowView = new ViewFrustum();
    private final Matrix4f lightSpace = new Matrix4f();
    /** One pass's worth of what survived culling, refilled for each pass: drawn and done with before the next. */
    private final List<StaticMesh> seenMeshes = new ArrayList<>();
    private final List<StaticInstance> seenProps = new ArrayList<>();
    private final List<TexturedInstance> seenTextured = new ArrayList<>();
    private final List<Matrix4f> seenPictures = new ArrayList<>();
    /** Draws the terrain's far props as pictures of themselves; made the first time there are any. */
    private LitImpostorRenderer pictureRenderer;
    /**
     * How far across the ground from the camera the terrain's objects are drawn, or infinity for as far as they are
     * gathered: a game's top-down camera sets it to what it can see, plus a margin.
     */
    private float drawDistance = Float.POSITIVE_INFINITY;
    private final PointLights pointLights = new PointLights();
    private final PointLights.Frame pointLightFrame = new PointLights.Frame();
    private float propGlow = 1f;
    private final Matrix4f waterMatrix = new Matrix4f();

    private final List<RenderPass> passes = new ArrayList<>();

    private SceneLighting lighting = Lighting.daylight();
    private boolean shadows = true;
    /** Whether the last {@link #render} drew the sun's shadow map: sun rays are shaped by it, and need it. */
    private boolean shadowsDrawn;
    private float sunRayStrength;
    private float sunRayGround;
    private final org.joml.Vector3f sunRayFocus = new org.joml.Vector3f();
    private boolean hasSunRayFocus;
    private boolean visible = true;
    private SkyDome sky;
    private TerrainSurface terrain;
    private float groundPlaneY = Float.NaN;
    private Color waterColor = Color.rgb(0x2F7FB0);
    private StaticMesh waterMesh;
    private GroundTextures waterTextures = GroundTextures.NONE;
    /** The water's own moving art, which it wears instead of {@link #waterTextures} when set. */
    private org.zeronura.engine.render.WaterLook waterLook;
    /** When the world was made: the water's clock runs from here, steady whatever the frame rate. */
    private final long waterClockStart = System.nanoTime();
    private float[] waterWeights;

    private final SolidField solidField = (x, y, z) ->
            (!Float.isNaN(groundPlaneY) && y < groundPlaneY) || (terrain != null && terrain.isSolid(x, y, z))
                    || y < groundOfComponents(x, z);

    public World3D(Scene scene) {
        this.scene = scene;
        Engine engine = scene.engine();
        int width = engine != null ? engine.width() : EngineConfig.DEFAULT_WIDTH;
        int height = engine != null ? engine.height() : EngineConfig.DEFAULT_HEIGHT;
        camera = new Camera(new Vector3f(0f, 2f, 6f), new Vector3f(0f, 1f, 0f), width / (float) Math.max(1, height));
        camera.setFarPlane(700f);
    }

    // --- Extra passes ---------------------------------------------------------------------------------------

    /**
     * Something else the world draws each frame alongside its own passes: meshes with their own shaders, such as
     * the materials of a Blender node package ({@link org.zeronura.engine.nodes.render.NodeRenderer}). It casts
     * into the sun's shadow map with everything else, draws in the opaque pass, and gets a turn after the water
     * for anything that blends.
     */
    public interface RenderPass {
        void castShadows(ShadowMap shadowMap);

        void renderOpaque(Renderer renderer, Camera camera, SceneLighting lighting);

        void renderTransparent(Renderer renderer, Camera camera, SceneLighting lighting);
    }

    /** Adds a pass, once; the same pass added again is ignored. */
    public void addPass(RenderPass pass) {
        if (!passes.contains(pass)) {
            passes.add(pass);
        }
    }

    public void removePass(RenderPass pass) {
        passes.remove(pass);
    }

    /** The first pass of a type, or null: so a system can find the pass it added. */
    public <T extends RenderPass> T pass(Class<T> type) {
        for (RenderPass pass : passes) {
            if (type.isInstance(pass)) {
                return type.cast(pass);
            }
        }
        return null;
    }

    // --- Setup ------------------------------------------------------------------------------------------

    public Camera camera() {
        return camera;
    }

    public SceneLighting lighting() {
        return lighting;
    }

    /** Sun, ambient light, fog and sky colour. See {@link Lighting} for presets. */
    public void setLighting(SceneLighting lighting) {
        this.lighting = lighting;
    }

    /**
     * Whether the world is drawn at all. Off while the scene covers the whole screen with something else for a while —
     * an editor's flat map — so the world costs nothing then and is still there, built, when it is shown again.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * How far across the ground from the camera the terrain's objects — trees, buildings, props, ground cover — are
     * drawn, in metres; infinity (or 0) for as far as the terrain gathers them. The ground itself is always drawn to
     * the far plane, and everything still casts its shadow into view from wherever it stands. Whatever this says,
     * nothing outside the camera's view is drawn.
     */
    public void setDrawDistance(float metres) {
        drawDistance = metres > 0f ? metres : Float.POSITIVE_INFINITY;
    }

    public float drawDistance() {
        return drawDistance;
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean shadows() {
        return shadows;
    }

    public void setShadows(boolean enabled) {
        this.shadows = enabled;
    }

    /**
     * Beams of sunlight in this world's air, where the sun gets past what casts its shadows (see
     * {@code render.SunRays}), when the player's settings have them: {@code strength} 1 for a clear day's, more for a
     * brighter sun, 0 for none (the default: a world asks for them). {@code groundY} is where the low haze they stand
     * in is thickest: the ground where the player is.
     */
    public void setSunRays(float strength, float groundY) {
        this.sunRayStrength = Math.max(0f, strength);
        this.sunRayGround = groundY;
        this.hasSunRayFocus = false;
    }

    /**
     * The same, with the shafts gathered round a place on the ground, {@code at} (x and z; its y is the ground's height):
     * where the player is. Under a cloud sky the shafts are the air's near there, wherever the camera stands.
     */
    public void setSunRays(float strength, org.joml.Vector3f at) {
        setSunRays(strength, at.y);
        sunRayFocus.set(at);
        hasSunRayFocus = true;
    }

    /** Where the shafts gather, or null for round the camera. */
    public org.joml.Vector3f sunRayFocus() {
        return hasSunRayFocus ? sunRayFocus : null;
    }

    public float sunRayStrength() {
        return sunRayStrength;
    }

    public float sunRayGround() {
        return sunRayGround;
    }

    /**
     * This world's lamps and other lights: set them once, and their brightness and focus as the day goes. See
     * {@link PointLights}. None until a world is given some.
     */
    public PointLights pointLights() {
        return pointLights;
    }

    /**
     * How brightly the props that glow burn — a lamp's glass, whose colour is brighter than white — from 0, lit only
     * as anything else is, to 1, their full glow and the default. Lamps by day are 0: the glass is still there, just
     * not alight. Magma in the ground is not a prop and is not dimmed by this.
     */
    public void setPropGlow(float glow) {
        this.propGlow = Math.max(0f, glow);
    }

    public float propGlow() {
        return propGlow;
    }

    /** Whether the last frame drew the sun's shadow map, which sun rays read. */
    public boolean shadowsDrawn() {
        return shadowsDrawn;
    }

    /**
     * The sky to draw behind everything: a gradient and clouds ({@link SkyDome}), or null (the default) for none, the
     * screen then left the {@link #skyColor} it is cleared to. Indoors has none. The dome stays its caller's to delete.
     */
    public void setSky(SkyDome sky) {
        this.sky = sky;
    }

    public SkyDome sky() {
        return sky;
    }

    /** The colour the screen clears to: the lighting's fog colour. */
    public Color skyColor() {
        Vector3f fog = lighting.fogColor();
        return new Color(fog.x, fog.y, fog.z);
    }

    public TerrainSurface terrain() {
        return terrain;
    }

    /**
     * Installs terrain — streamed procedural {@code Terrain} or an edited {@code MapTerrain} — closing any
     * previous terrain. The world closes it when the scene exits.
     */
    public void setTerrain(TerrainSurface newTerrain) {
        if (terrain != null && terrain != newTerrain) {
            terrain.close();
        }
        terrain = newTerrain;
    }

    /** An infinite flat floor at height {@code y} — the simplest possible level. */
    public void setGroundPlane(float y) {
        groundPlaneY = y;
    }

    public void clearGroundPlane() {
        groundPlaneY = Float.NaN;
    }

    public void setWaterColor(Color color) {
        waterColor = color;
        if (waterMesh != null) {
            waterMesh.delete();
            waterMesh = null;
        }
    }

    /**
     * Lays one of a set of ground textures over the water, projected from above like the ground's. The set is usually
     * the one the ground wears; {@code layer} picks the water's art in it.
     *
     * @param textures the set, or null to leave the water its flat colour
     */
    public void setWaterTexture(GroundTextures textures, int layer) {
        if (textures == null || !textures.isPresent() || layer < 0 || layer >= textures.materials().size()) {
            waterTextures = GroundTextures.NONE;
            waterWeights = null;
            return;
        }
        waterTextures = textures;
        waterWeights = new float[StaticMesh.GROUND_WEIGHT_COUNT];
        waterWeights[layer] = 1f;
    }

    /**
     * Gives the water art of its own, moved by the shader — rivers carried along their flow, the sea drifting, waves
     * rolling in to the shore: see {@link org.zeronura.engine.render.WaterLook}. Null goes back to
     * {@link #setWaterTexture}'s still picture. The look's textures stay the caller's to delete.
     */
    public void setWaterLook(org.zeronura.engine.render.WaterLook look) {
        waterLook = look;
    }

    /** Seconds on the water's clock, kept short of where a float loses the fractions the waves are made of. */
    private float waterTime() {
        return (float) ((System.nanoTime() - waterClockStart) / 1e9 % 7200.0);
    }

    /** Registers a mesh to be deleted when the scene exits. Returns it, for inline use. */
    public StaticMesh own(StaticMesh mesh) {
        ownedMeshes.add(mesh);
        return mesh;
    }

    // --- Physics ----------------------------------------------------------------------------------------

    public PhysicsWorld physics() {
        return physics;
    }

    /** Terrain, ground plane and nothing else — what "is there ground here" means to 3D physics. */
    public SolidField solidField() {
        return solidField;
    }

    /** The collider boxes gathered at the start of this fixed step. */
    public List<AABB> obstacles() {
        return Collections.unmodifiableList(obstacles);
    }

    /** Height of the terrain or ground plane below a point, or negative infinity where there is neither. */
    public float groundHeightAt(float x, float z) {
        float height = Float.NEGATIVE_INFINITY;
        if (!Float.isNaN(groundPlaneY)) {
            height = groundPlaneY;
        }
        if (terrain != null) {
            height = Math.max(height, terrain.heightAt(x, z));
        }
        return Math.max(height, groundOfComponents(x, z));
    }

    /**
     * The highest ground a {@link Ground3D} component in the scene has under a point, or negative infinity. The
     * components are gathered once a step and once a frame, not at every one of the many points a step asks about.
     */
    private float groundOfComponents(float x, float z) {
        float height = Float.NEGATIVE_INFINITY;
        for (Ground3D ground : grounds) {
            height = Math.max(height, ground.groundHeight(x, z));
        }
        return height;
    }

    private List<Ground3D> grounds = List.of();

    /**
     * Distance along a ray to the first collider box or ground surface, or {@link AABB#NO_HIT}.
     * For shooting, picking and line-of-sight checks.
     */
    public float raycast(Vector3f origin, Vector3f direction, float maxDistance) {
        Vector3f dir = new Vector3f(direction).normalize();
        float best = AABB.NO_HIT;
        for (BoxCollider3D collider : scene.components(BoxCollider3D.class)) {
            float hit = collider.bounds().rayDistance(origin, dir, maxDistance);
            if (hit != AABB.NO_HIT && (best == AABB.NO_HIT || hit < best)) {
                best = hit;
            }
        }
        float limit = best == AABB.NO_HIT ? maxDistance : best;
        float step = 0.25f;
        float previous = 0f;
        for (float t = step; t <= limit; t += step) {
            if (solidField.isSolid(origin.x + dir.x * t, origin.y + dir.y * t, origin.z + dir.z * t)) {
                // Bisect between the last clear sample and this solid one for a precise surface point.
                float low = previous;
                float high = t;
                for (int i = 0; i < 8; i++) {
                    float mid = (low + high) * 0.5f;
                    if (solidField.isSolid(origin.x + dir.x * mid, origin.y + dir.y * mid, origin.z + dir.z * mid)) {
                        high = mid;
                    } else {
                        low = mid;
                    }
                }
                return high;
            }
            previous = t;
        }
        return best;
    }

    // --- Engine plumbing --------------------------------------------------------------------------------

    public void beginFixedStep() {
        obstacles.clear();
        for (BoxCollider3D collider : scene.components(BoxCollider3D.class)) {
            obstacles.add(collider.bounds());
        }
        for (Collides3D solid : scene.components(Collides3D.class)) {
            solid.addColliders(obstacles);
        }
        grounds = scene.components(Ground3D.class);
        if (terrain != null) {
            obstacles.addAll(terrain.colliders());
        }
    }

    public void resize(int width, int height) {
        if (width > 0 && height > 0) {
            camera.setAspectRatio(width, height);
        }
    }

    public void render() {
        render(null);
    }

    /**
     * Draws the world: the shadow map, then the sky if it has one, then everything opaque, then the water.
     *
     * @param afterOpaque run once every opaque pass is drawn and before the water — where a screen-space pass over the
     *                    solid scene, such as ambient occlusion, goes; null for none
     */
    public void render(Runnable afterOpaque) {
        if (!visible) {
            return;
        }
        Renderer renderer = scene.engine().renderer3D();
        Vector3f focus = pointLights.focus();
        renderer.setPointLights(pointLights.nearest(focus != null ? focus : camera.getPosition(), pointLightFrame));
        renderer.setPropEmissive(propGlow);
        try {
            draw(renderer, afterOpaque);
        } finally {
            // Frame state: the next world to draw — an interior, an editor's view — has lamps of its own or none.
            renderer.setPointLights(PointLights.Frame.NONE);
            renderer.setPropEmissive(1f);
        }
    }

    private void draw(Renderer renderer, Runnable afterOpaque) {
        Engine engine = scene.engine();

        DebugMode.begin("Gather");
        if (terrain != null) {
            terrain.update(camera.getPosition());
        }
        grounds = scene.components(Ground3D.class);
        List<StaticMesh> terrainMeshes = terrain != null ? terrain.meshes() : List.of();
        List<StaticInstance> terrainProps = terrain != null ? terrain.instances() : List.of();
        List<StaticInstance> groundCover = terrain != null ? terrain.groundCover() : List.<StaticInstance>of();
        Map<FoliageMaterial, List<TexturedInstance>> texturedProps = terrain != null ? terrain.texturedInstances() : Map.of();
        Map<LitImpostor.Sprite, List<Matrix4f>> pictures = terrain != null ? terrain.impostors() : Map.of();

        instances.clear();
        shadowCasters.clear();
        for (MeshRenderer meshRenderer : scene.components(MeshRenderer.class)) {
            if (meshRenderer.mesh == null) {
                continue;
            }
            StaticInstance instance = meshRenderer.instance();
            instances.add(instance);
            if (meshRenderer.castShadows) {
                shadowCasters.add(instance);
            }
        }
        textured.clear();
        texturedShadows.clear();
        for (TexturedMeshRenderer texturedRenderer : scene.components(TexturedMeshRenderer.class)) {
            if (texturedRenderer.mesh == null || texturedRenderer.material == null) {
                continue;
            }
            TexturedInstance instance = texturedRenderer.instance();
            textured.computeIfAbsent(texturedRenderer.material, key -> new ArrayList<>()).add(instance);
            if (texturedRenderer.castShadows) {
                texturedShadows.computeIfAbsent(texturedRenderer.material, key -> new ArrayList<>()).add(instance);
            }
        }
        List<ModelRenderer> models = scene.components(ModelRenderer.class);
        DebugMode.end();

        // The player's shadow quality: none at all, or the shadow map at its size.
        UserSettings.ShadowQuality quality = scene.engine() == null ? UserSettings.ShadowQuality.HIGH
                : scene.engine().settings().shadowQuality();
        boolean castShadows = shadows && quality != UserSettings.ShadowQuality.OFF && lighting.shadowStrength() > 0f;
        shadowsDrawn = castShadows;
        if (castShadows) {
            DebugMode.begin("Shadow map");
            ShadowMap shadowMap = renderer.getShadowMap();
            shadowMap.setResolution(quality.resolution());
            // The terrain's own casters — ground, trees, buildings, cover — stand still: drawn once into a kept copy
            // of the map, and copied back each frame after while the sun and the shadowed box have barely moved and
            // the terrain has drawn nothing new. A terrain that keeps no count of its changes is drawn every frame.
            long version = terrain != null && KEEP_WORLD_SHADOWS ? terrain.drawnVersion() : -1L;
            boolean drawWorld;
            if (version >= 0L) {
                drawWorld = shadowMap.beginCached(lighting.toSun(), shadowFocus(), terrain, version);
            } else {
                shadowMap.begin(lighting.toSun(), shadowFocus());
                drawWorld = true;
            }
            if (drawWorld) {
                DebugMode.begin("World's own shadows");
                // Only what can throw a shadow into the box: the rest of the gathered world is a long way off.
                shadowView.set(shadowMap.lightSpaceMatrix(lightSpace));
                shadowMap.drawStatic(cull(terrainMeshes, shadowView));
                shadowMap.drawInstances(cull(terrainProps, shadowView, Float.POSITIVE_INFINITY));
                // Cover casts its shadow unbent: the depth pass has no parting of its own, so a clump the
                // player is standing in throws a shadow from where it grows rather than where its tips are.
                // At a tip displacement of tens of centimetres, under the body that is bending it, nothing
                // that a second shader kept in step with terrain.vert would be worth.
                shadowMap.drawInstances(cull(groundCover, shadowView, Float.POSITIVE_INFINITY));
                for (Map.Entry<FoliageMaterial, List<TexturedInstance>> pass : texturedProps.entrySet()) {
                    shadowMap.drawTextured(cullTextured(pass.getValue(), shadowView, Float.POSITIVE_INFINITY), pass.getKey());
                }
                if (version >= 0L) {
                    shadowMap.keepStatic();
                }
                DebugMode.end();
            }
            shadowMap.drawInstances(shadowCasters);
            for (Map.Entry<FoliageMaterial, List<TexturedInstance>> pass : texturedShadows.entrySet()) {
                shadowMap.drawTextured(pass.getValue(), pass.getKey());
            }
            for (RenderPass pass : passes) {
                DebugMode.begin(pass);
                pass.castShadows(shadowMap);
                DebugMode.end();
            }
            for (ModelRenderer model : models) {
                if (model.model == null || !model.castShadows) {
                    continue;
                }
                if (model.animator != null && model.drawnElsewhere.isEmpty()) {
                    renderer.uploadPose(model.animator);
                    shadowMap.drawSkinned(model.model, model.worldMatrix());
                } else {
                    shadowMap.drawRigid(model.model, model.worldMatrix());
                }
            }
            shadowMap.end();
            DebugMode.end();
        }
        SceneLighting frameLighting = castShadows ? lighting : lighting.withoutShadows();

        // Behind everything, so all that is drawn after covers it.
        if (sky != null) {
            DebugMode.begin("Sky");
            sky.render(camera, frameLighting);
            DebugMode.end();
        }
        StaticMesh.clearGroundWeights();
        // Nothing the camera cannot see is drawn; and the terrain's objects no farther than the draw distance.
        cameraView.set(camera);
        if (!terrainMeshes.isEmpty()) {
            DebugMode.begin("Terrain");
            renderer.renderTerrain(camera, frameLighting, cull(terrainMeshes, cameraView));
            DebugMode.end();
        }
        if (!terrainProps.isEmpty()) {
            DebugMode.begin("Terrain props");
            renderer.renderProps(camera, frameLighting, cull(terrainProps, cameraView, drawDistance));
            DebugMode.end();
        }
        if (!texturedProps.isEmpty()) {
            DebugMode.begin("Terrain foliage");
            for (Map.Entry<FoliageMaterial, List<TexturedInstance>> pass : texturedProps.entrySet()) {
                renderer.renderFoliage(camera, frameLighting, cullTextured(pass.getValue(), cameraView, drawDistance),
                        pass.getKey());
            }
            DebugMode.end();
        }
        if (!pictures.isEmpty()) {
            DebugMode.begin("Far trees");
            if (pictureRenderer == null) {
                pictureRenderer = new LitImpostorRenderer();
            }
            pictureRenderer.begin(camera, frameLighting);
            for (Map.Entry<LitImpostor.Sprite, List<Matrix4f>> picture : pictures.entrySet()) {
                pictureRenderer.draw(picture.getKey(), cullPictures(picture.getValue(), picture.getKey()), PICTURE_CUTOFF);
            }
            pictureRenderer.end();
            DebugMode.end();
        }
        if (!instances.isEmpty()) {
            DebugMode.begin("Meshes");
            renderer.renderProps(camera, frameLighting, instances);
            DebugMode.end();
        }
        if (!textured.isEmpty()) {
            DebugMode.begin("Textured meshes");
            for (Map.Entry<FoliageMaterial, List<TexturedInstance>> pass : textured.entrySet()) {
                renderer.renderFoliage(camera, frameLighting, pass.getValue(), pass.getKey());
            }
            DebugMode.end();
        }
        for (RenderPass pass : passes) {
            DebugMode.begin(pass);
            pass.renderOpaque(renderer, camera, frameLighting);
            DebugMode.end();
        }
        DebugMode.begin("Sprites");
        renderSprites(renderer, frameLighting);
        DebugMode.end();
        if (!groundCover.isEmpty()) {
            DebugMode.begin("Ground cover");
            renderer.renderGroundCover(camera, frameLighting, cull(groundCover, cameraView, drawDistance));
            DebugMode.end();
        }
        DebugMode.begin("Models");
        for (ModelRenderer model : models) {
            if (model.model == null || model.drawnElsewhere.size() >= model.model.getMeshes().size()) {
                continue;
            }
            // The parts in a shader are drawn by the model's own pass; the rest here, as the file has them.
            renderer.skipMeshes(model.drawnElsewhere);
            try {
                if (model.animator != null) {
                    renderer.renderFrame(camera, frameLighting, model.model, model.animator, model.worldMatrix());
                } else {
                    renderer.renderAttachment(camera, frameLighting, model.model, model.worldMatrix());
                }
            } finally {
                renderer.skipMeshes(null);
            }
        }
        DebugMode.end();
        if (afterOpaque != null) {
            afterOpaque.run();
        }
        // Translucent, so after everything solid: what stands in the water shows through it.
        DebugMode.begin("Water");
        if (terrain != null && terrain.hasWater()) {
            renderWater(renderer, frameLighting);
        }
        if (terrain != null && !terrain.waterMeshes().isEmpty()) {
            if (waterLook != null) {
                renderer.renderWater(camera, frameLighting, terrain.waterMeshes(), waterLook, waterTime());
            } else {
                renderer.renderProps(camera, frameLighting, terrain.waterMeshes(), waterTextures, waterWeights);
            }
        }
        DebugMode.end();
        DebugMode.begin("See-through");
        for (RenderPass pass : passes) {
            DebugMode.begin(pass);
            pass.renderTransparent(renderer, camera, frameLighting);
            DebugMode.end();
        }
        DebugMode.end();
    }

    /** Every {@link SpriteBillboard}, one pass per sheet. */
    private void renderSprites(Renderer renderer, SceneLighting frameLighting) {
        sprites.clear();
        for (SpriteBillboard sprite : scene.components(SpriteBillboard.class)) {
            FoliageMaterial material = sprite.material();
            if (material != null) {
                sprites.computeIfAbsent(material, key -> new ArrayList<>()).add(sprite.instance(camera));
            }
        }
        for (Map.Entry<FoliageMaterial, List<TexturedInstance>> pass : sprites.entrySet()) {
            renderer.renderFoliage(camera, frameLighting, pass.getValue(), pass.getKey(), false);
        }
        // Before the grass is drawn: someone wading through it is not hidden behind anything.
        for (SpriteBillboard sprite : scene.components(SpriteBillboard.class)) {
            FoliageMaterial material = sprite.material();
            if (sprite.showsThrough && material != null) {
                TexturedInstance drawn = sprite.instance(camera);
                renderer.renderHidden(camera, frameLighting,
                        List.of(new TexturedInstance(drawn.mesh(), drawn.modelMatrix(), sprite.throughTint)), material,
                        sprite.throughOpacity);
            }
        }
    }

    private void renderWater(Renderer renderer, SceneLighting frameLighting) {
        float size = camera.getFarPlane() * 2.2f;
        if (waterMesh == null) {
            waterMesh = Primitives.plane(1f, 1f, waterColor);
        }
        // Follows the camera in coarse steps, so it always reaches the horizon without visibly sliding.
        Vector3f eye = camera.getPosition();
        float snap = 64f;
        waterMatrix.translation((float) Math.floor(eye.x / snap) * snap, terrain.waterLevel(),
                (float) Math.floor(eye.z / snap) * snap).scale(size, 1f, size);
        List<StaticInstance> sea = List.of(new StaticInstance(waterMesh, waterMatrix));
        if (waterLook != null) {
            renderer.renderWater(camera, frameLighting, sea, waterLook, waterTime());
        } else {
            renderer.renderProps(camera, frameLighting, sea, waterTextures, waterWeights);
        }
    }

    /** Centre the shadow map a little ahead of the camera, where most of what is seen lies. */
    /** The terrain meshes — ground chunks, at the origin — with any part inside {@code view}, into {@link #seenMeshes}. */
    private List<StaticMesh> cull(List<StaticMesh> meshes, ViewFrustum view) {
        seenMeshes.clear();
        Matrix4f identity = IDENTITY;
        for (StaticMesh mesh : meshes) {
            if (mesh.bounds().visible(identity, view, 0f, 0f, Float.POSITIVE_INFINITY)) {
                seenMeshes.add(mesh);
            }
        }
        return seenMeshes;
    }

    /**
     * The props with any part inside {@code view} and within {@code reach} across the ground of the camera, into
     * {@link #seenProps}: so the list is good until the next call.
     */
    private List<StaticInstance> cull(List<StaticInstance> props, ViewFrustum view, float reach) {
        seenProps.clear();
        Vector3f eye = camera.getPosition();
        for (StaticInstance prop : props) {
            if (prop.mesh().bounds().visible(prop.modelMatrix(), view, eye.x, eye.z, reach)) {
                seenProps.add(prop);
            }
        }
        return seenProps;
    }

    /** The same for textured props — trees, buildings, roads — into {@link #seenTextured}. */
    private List<TexturedInstance> cullTextured(List<TexturedInstance> props, ViewFrustum view, float reach) {
        seenTextured.clear();
        Vector3f eye = camera.getPosition();
        for (TexturedInstance prop : props) {
            if (prop.mesh().bounds().visible(prop.modelMatrix(), view, eye.x, eye.z, reach)) {
                seenTextured.add(prop);
            }
        }
        return seenTextured;
    }

    /** The far props drawn as a picture whose square — any side of it may face the camera — is in view, into {@link #seenPictures}. */
    private List<Matrix4f> cullPictures(List<Matrix4f> placements, LitImpostor.Sprite picture) {
        seenPictures.clear();
        Vector3f eye = camera.getPosition();
        float middle = picture.bottom() + picture.extent() * 0.5f;
        float radius = picture.extent() * 0.7072f;
        for (Matrix4f m : placements) {
            float scale = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
            float r = radius * scale;
            float dx = m.m30() - eye.x;
            float dz = m.m32() - eye.z;
            if (drawDistance < Float.POSITIVE_INFINITY && dx * dx + dz * dz > (drawDistance + r) * (drawDistance + r)) {
                continue;
            }
            if (cameraView.containsSphere(m.m30(), m.m31() + middle * scale, m.m32(), r)) {
                seenPictures.add(m);
            }
        }
        return seenPictures;
    }

    /** What a far prop's picture keeps: its baked coverage is whole or nothing, so half way. */
    private static final float PICTURE_CUTOFF = 0.5f;

    private static final Matrix4f IDENTITY = new Matrix4f();

    /** Whether the terrain's own shadows are kept between frames; -Dengine.keepShadows=false draws them every frame, to compare. */
    private static final boolean KEEP_WORLD_SHADOWS = !"false".equalsIgnoreCase(System.getProperty("engine.keepShadows"));

    private Vector3f shadowFocus() {
        Vector3f eye = camera.getPosition();
        Vector3f look = camera.getForward(new Vector3f());
        look.y = 0f;
        if (look.lengthSquared() > 1e-6f) {
            look.normalize(ShadowMap.RADIUS * 0.5f);
        }
        return shadowFocus.set(eye).add(look);
    }

    public void dispose() {
        if (terrain != null) {
            terrain.close();
            terrain = null;
        }
        if (waterMesh != null) {
            waterMesh.delete();
            waterMesh = null;
        }
        ownedMeshes.forEach(StaticMesh::delete);
        ownedMeshes.clear();
        if (pictureRenderer != null) {
            pictureRenderer.delete();
            pictureRenderer = null;
        }
    }
}
