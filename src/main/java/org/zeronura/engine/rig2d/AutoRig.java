package org.zeronura.engine.rig2d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rigs a layered painting by its layer names, as a starting point to refine in the editor's Rig Tool.
 *
 * <p>The body (a layer named Body, or else the biggest) gets a warp over the whole figure that breathes, leans, stretches,
 * squashes, lunges forward and recoils back, its feet staying planted. Every other layer is a part, and its name says
 * how it moves:
 * <ul>
 *   <li><b>Head</b>, Face: turns about its neck ({@code HeadAngle}).</li>
 *   <li><b>Jaw</b>, Mouth, Beak Lower: swings open downward ({@code MouthOpen}).</li>
 *   <li><b>Eye</b>: blinks ({@code EyeOpen}), and takes on a look: heavy lids ({@code EyeLid}), a smile pushing the
 *   lower lid up ({@code EyeSmile}, with the eye shut a happy arc), wide open ({@code EyeWide}), the gaze moving
 *   ({@code EyeBallX}, {@code EyeBallY}) and the upper lid slanting with the brows ({@code BrowAngle}). The brows
 *   themselves are the paint above each eye, on whatever the eye sits on: they rise and fall ({@code BrowY}) and knit
 *   or lift at the inner corner ({@code BrowAngle}). {@link RigEyes} drives all of it at run time.</li>
 *   <li><b>Tail</b>, Fin, Wing, Ear, Antenna, Tentacle, Leaf, Flame, Hair, Tuft, Crest, Gill, Whisker, Frond, Mane,
 *   Petal, Plume, Feather, Vine, Lure, Stalk, Tendril, Ribbon, Streamer: bends along its length ({@code Sway <name>}),
 *   with physics, so it trails and swings when the body moves.</li>
 *   <li><b>Arm</b>, Claw, Hand, Paw, Talon: swings about its shoulder ({@code Swing <name>}).</li>
 *   <li>Anything else (legs, horns, shells, spikes): rides on whatever it is attached to.</li>
 * </ul>
 * A part's pivot is where it overlaps what it is attached to; a layer inside a group hangs from the group's own part
 * (a "Head" group's eyes and jaw move with the head). Motions are made to match: {@code idle} (looping), {@code attack},
 * {@code hit}, {@code faint}, {@code happy} and {@code appear}.
 */
public final class AutoRig {

    enum Kind { BODY, HEAD, JAW, EYE, FLEX, LIMB, RIGID }

    private static final String[] HEAD = {"head", "face"};
    private static final String[] JAW = {"jaw", "mouth", "beak lower", "lower beak", "mandible"};
    private static final String[] EYE = {"eye"};
    private static final String[] FLEX = {"tail", "fin", "wing", "ear", "antenna", "tentacle", "leaf", "leaves", "flame",
            "fire", "hair", "tuft", "crest", "gill", "whisker", "frond", "mane", "petal", "plume", "feather", "vine",
            "lure", "stalk", "tendril", "ribbon", "streamer", "sprout", "flower", "bulb", "cape", "scarf", "fur",
            "sail", "flipper", "trunk", "tongue", "smoke", "cloud", "wisp", "aura", "spark", "bell"};
    private static final String[] LIMB = {"arm", "claw", "hand", "paw", "talon", "fist", "pincer", "forearm"};
    private static final String[] BODY = {"body", "torso", "base"};

    public static final String BREATH = "Breath";
    public static final String BODY_ANGLE_X = "BodyAngleX";
    public static final String BODY_ANGLE_Y = "BodyAngleY";
    public static final String LUNGE = "Lunge";
    public static final String RECOIL = "Recoil";
    public static final String HEAD_ANGLE = "HeadAngle";
    public static final String MOUTH_OPEN = "MouthOpen";
    public static final String EYE_OPEN = "EyeOpen";
    public static final String EYE_LID = "EyeLid";
    public static final String EYE_SMILE = "EyeSmile";
    public static final String EYE_WIDE = "EyeWide";
    public static final String EYE_BALL_X = "EyeBallX";
    public static final String EYE_BALL_Y = "EyeBallY";
    public static final String BROW_ANGLE = "BrowAngle";
    public static final String BROW_Y = "BrowY";
    public static final String BODY_WARP = "Body";

    private AutoRig() {
    }

    /**
     * What a layer's name can't say: where the part joins on, how it moves ({@code flex}, {@code jaw}, {@code eye},
     * {@code head}, {@code limb}, {@code rigid}) and how far it bends or turns, in degrees. Any may be null.
     */
    public record Hint(float[] pivot, String kind, Float degrees) {
    }

    static Kind kind(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (contains(n, JAW)) {
            return Kind.JAW;
        }
        if (contains(n, EYE)) {
            return Kind.EYE;
        }
        if (contains(n, HEAD)) {
            return Kind.HEAD;
        }
        if (contains(n, LIMB)) {
            return Kind.LIMB;
        }
        if (contains(n, FLEX)) {
            return Kind.FLEX;
        }
        if (contains(n, BODY)) {
            return Kind.BODY;
        }
        return Kind.RIGID;
    }

    private static boolean contains(String name, String[] words) {
        for (String word : words) {
            if (name.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** A part being rigged: a layer, or a group of them. */
    private static final class Part {
        final String name;
        final Kind kind;
        final List<RigLayer> layers = new ArrayList<>();
        Part parent;
        Hint hint;
        String deformer;
        float pivotX;
        float pivotY;
        float tipX;
        float tipY;
        float left;
        float top;
        float right;
        float bottom;

        Part(String name, Kind kind) {
            this.name = name;
            this.kind = kind;
        }

        float width() {
            return right - left;
        }

        float height() {
            return bottom - top;
        }

        int alphaAt(int x, int y) {
            int best = 0;
            for (RigLayer layer : layers) {
                best = Math.max(best, layer.alphaAt(x, y));
            }
            return best;
        }

        void bounds() {
            left = Float.MAX_VALUE;
            top = Float.MAX_VALUE;
            right = -Float.MAX_VALUE;
            bottom = -Float.MAX_VALUE;
            for (RigLayer layer : layers) {
                left = Math.min(left, layer.left());
                top = Math.min(top, layer.top());
                right = Math.max(right, layer.left() + layer.width());
                bottom = Math.max(bottom, layer.top() + layer.height());
            }
        }
    }

    /**
     * Rigs these layers (bottom first, as a layered file lists them) on a canvas.
     *
     * @param facing -1 when the art looks to the left, 1 to the right
     */
    public static RigModel rig(List<RigLayer> source, int canvasWidth, int canvasHeight, int facing) {
        return rig(source, canvasWidth, canvasHeight, facing, Map.of());
    }

    /** As {@link #rig(List, int, int, int)}, with hints for parts by layer name. */
    public static RigModel rig(List<RigLayer> source, int canvasWidth, int canvasHeight, int facing,
                               Map<String, Hint> hints) {
        RigModel model = new RigModel();
        model.canvasWidth = canvasWidth;
        model.canvasHeight = canvasHeight;
        model.facing = facing < 0 ? -1 : 1;
        List<RigLayer> layers = new ArrayList<>();
        for (RigLayer layer : source) {
            RigLayer trimmed = layer.trimmed();
            if (trimmed != null) {
                layers.add(trimmed);
            }
        }
        model.layers.addAll(layers);
        if (layers.isEmpty()) {
            model.pivotX = canvasWidth * 0.5f;
            model.pivotY = canvasHeight;
            model.resolve();
            return model;
        }

        // The figure's box, and the feet: the bottom middle of what is painted.
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (RigLayer l : layers) {
            minX = Math.min(minX, l.left());
            minY = Math.min(minY, l.top());
            maxX = Math.max(maxX, l.left() + l.width());
            maxY = Math.max(maxY, l.top() + l.height());
        }
        model.pivotX = (minX + maxX) * 0.5f;
        model.pivotY = maxY;

        // Parts: the body, each group, and each layer not the main layer of its group.
        RigLayer bodyLayer = null;
        for (RigLayer l : layers) {
            if (l.path().indexOf('/') < 0 && kind(l.name()) == Kind.BODY) {
                bodyLayer = l;
            }
        }
        if (bodyLayer == null) {
            long biggest = -1;
            for (RigLayer l : layers) {
                long area = (long) l.width() * l.height();
                if (area > biggest) {
                    biggest = area;
                    bodyLayer = l;
                }
            }
        }
        Part body = new Part(BODY_WARP, Kind.BODY);
        body.layers.add(bodyLayer);
        Map<String, Part> groups = new HashMap<>();
        List<Part> parts = new ArrayList<>();
        Map<RigLayer, Part> owner = new HashMap<>();
        owner.put(bodyLayer, body);
        for (RigLayer l : layers) {
            String[] path = l.path().split("/");
            Part parent = body;
            StringBuilder prefix = new StringBuilder();
            for (int g = 0; g < path.length - 1; g++) {
                if (!prefix.isEmpty()) {
                    prefix.append('/');
                }
                prefix.append(path[g]);
                String key = prefix.toString();
                Part group = groups.get(key);
                if (group == null) {
                    Kind k = hintedKind(hints.get(path[g]), kind(path[g]));
                    group = new Part(path[g], k == Kind.BODY ? Kind.RIGID : k);
                    group.hint = hints.get(path[g]);
                    group.parent = parent;
                    groups.put(key, group);
                    parts.add(group);
                }
                group.layers.add(l);
                parent = group;
            }
            if (l == bodyLayer) {
                continue;
            }
            // A layer named like its group is the group's own picture: it moves with the group's part.
            if (parent != body && l.name().equalsIgnoreCase(parent.name)) {
                owner.put(l, parent);
                continue;
            }
            Kind k = hintedKind(hints.get(l.name()), kind(l.name()));
            Part part = new Part(l.name(), k == Kind.BODY ? Kind.RIGID : k);
            part.hint = hints.get(l.name());
            part.layers.add(l);
            part.parent = parent;
            parts.add(part);
            owner.put(l, part);
        }
        body.bounds();
        for (Part part : parts) {
            part.bounds();
        }

        // Parameters.
        model.parameters.add(new RigParameter(BREATH, 0f, 1f, 0f));
        model.parameters.add(new RigParameter(BODY_ANGLE_X, -10f, 10f, 0f));
        model.parameters.add(new RigParameter(BODY_ANGLE_Y, -10f, 10f, 0f));
        model.parameters.add(new RigParameter(LUNGE, 0f, 1f, 0f));
        model.parameters.add(new RigParameter(RECOIL, 0f, 1f, 0f));

        // The body warp, over the whole figure with room round it.
        float figureW = maxX - minX;
        float figureH = maxY - minY;
        float marginX = figureW * 0.12f + 4f;
        float marginTop = figureH * 0.12f + 4f;
        Deformer.Warp warp = new Deformer.Warp(BODY_WARP, null, minX - marginX, minY - marginTop,
                figureW + 2f * marginX, figureH + marginTop + 2f, 4, 4);
        body.deformer = warp.id;
        bodyTracks(warp, model.pivotX, maxY, figureW, figureH, model.facing);
        model.deformers.add(warp);

        List<String> sways = new ArrayList<>();
        Map<String, Integer> names = new HashMap<>();
        for (Part part : parts) {
            Part parent = part.parent == null ? body : part.parent;
            pivot(part, parent);
            if (part.hint != null && part.hint.pivot() != null && part.hint.pivot().length >= 2) {
                part.pivotX = part.hint.pivot()[0];
                part.pivotY = part.hint.pivot()[1];
                tip(part);
            }
            String base = part.name.trim().isEmpty() ? "Part" : part.name.trim();
            int seen = names.merge(base, 1, Integer::sum);
            String id = seen == 1 ? base : base + " " + seen;
            switch (part.kind) {
                case HEAD -> {
                    addOnce(model, new RigParameter(HEAD_ANGLE, -10f, 10f, 0f));
                    Deformer.Rotation rot = new Deformer.Rotation(id, parent.deformer, part.pivotX, part.pivotY);
                    rot.keyforms.tracks.add(Keyforms.Track.of(HEAD_ANGLE, new float[]{-10f, 0f, 10f},
                            new float[]{-degrees(part, 7f), 0f, 0f, 0f}, new float[4],
                            new float[]{degrees(part, 7f), 0f, 0f, 0f}));
                    model.deformers.add(rot);
                    part.deformer = rot.id;
                }
                case JAW -> {
                    addOnce(model, new RigParameter(MOUTH_OPEN, 0f, 1f, 0f));
                    Deformer.Rotation rot = new Deformer.Rotation(id, parent.deformer, part.pivotX, part.pivotY);
                    // Open = the jaw's far end swings down: clockwise on screen when it reaches right of its hinge.
                    float reach = (part.left + part.right) * 0.5f - part.pivotX;
                    float open = (reach >= 0f ? 1f : -1f) * degrees(part, 20f);
                    rot.keyforms.tracks.add(Keyforms.Track.of(MOUTH_OPEN, new float[]{0f, 1f},
                            new float[4], new float[]{open, 0f, 0f, 0f}));
                    model.deformers.add(rot);
                    part.deformer = rot.id;
                }
                case FLEX -> {
                    String sway = RigModel.unique("Sway " + id, model.parameters.stream().map(RigParameter::id).toList());
                    model.parameters.add(new RigParameter(sway, -1f, 1f, 0f));
                    sways.add(sway);
                    Deformer.Warp bend = bendWarp(id, parent.deformer, part, sway);
                    model.deformers.add(bend);
                    part.deformer = bend.id;
                    RigPhysics physics = new RigPhysics(id, sway);
                    physics.inputs.add(new RigPhysics.Input(BODY_ANGLE_X, 0.8f));
                    physics.inputs.add(new RigPhysics.Input(BODY_ANGLE_Y, 0.4f));
                    physics.inputs.add(new RigPhysics.Input(LUNGE, 0.7f * model.facing));
                    physics.inputs.add(new RigPhysics.Input(RECOIL, -0.7f * model.facing));
                    if (model.parameterIndex(HEAD_ANGLE) >= 0 && under(part, Kind.HEAD)) {
                        physics.inputs.add(new RigPhysics.Input(HEAD_ANGLE, 0.6f));
                    }
                    float length = (float) Math.hypot(part.tipX - part.pivotX, part.tipY - part.pivotY);
                    physics.stiffness = Math.max(30f, 110f - length * 0.35f);
                    physics.damping = 4.5f;
                    physics.gain = 1.3f;
                    model.physics.add(physics);
                }
                case LIMB -> {
                    String swing = RigModel.unique("Swing " + id, model.parameters.stream().map(RigParameter::id).toList());
                    model.parameters.add(new RigParameter(swing, -1f, 1f, 0f));
                    Deformer.Rotation rot = new Deformer.Rotation(id, parent.deformer, part.pivotX, part.pivotY);
                    rot.keyforms.tracks.add(Keyforms.Track.of(swing, new float[]{-1f, 0f, 1f},
                            new float[]{-degrees(part, 14f), 0f, 0f, 0f}, new float[4],
                            new float[]{degrees(part, 14f), 0f, 0f, 0f}));
                    model.deformers.add(rot);
                    part.deformer = rot.id;
                }
                default -> part.deformer = parent.deformer;
            }
        }

        // The eyes: each one's oval, and which way its inner corner lies — toward the other eyes, or for a lone eye
        // toward where the rig faces.
        List<EyeFrame> eyes = new ArrayList<>();
        for (RigLayer l : layers) {
            Part part = owner.get(l);
            if (part != null && part.kind == Kind.EYE) {
                eyes.add(new EyeFrame(l.left() + l.width() * 0.5f, l.top() + l.height() * 0.5f,
                        Math.max(2f, l.width() * 0.5f), Math.max(2f, l.height() * 0.5f), model.facing,
                        part.parent == null ? body : part.parent));
            }
        }
        if (eyes.size() >= 2) {
            float face = 0f;
            for (EyeFrame e : eyes) {
                face += e.cx;
            }
            face /= eyes.size();
            for (int i = 0; i < eyes.size(); i++) {
                EyeFrame e = eyes.get(i);
                if (Math.abs(face - e.cx) > e.rx * 0.5f) {
                    eyes.set(i, new EyeFrame(e.cx, e.cy, e.rx, e.ry, Math.signum(face - e.cx), e.parent));
                }
            }
        }
        if (!eyes.isEmpty()) {
            addOnce(model, new RigParameter(EYE_OPEN, 0f, 1f, 1f));
            addOnce(model, new RigParameter(EYE_LID, 0f, 1f, 0f));
            addOnce(model, new RigParameter(EYE_SMILE, 0f, 1f, 0f));
            addOnce(model, new RigParameter(EYE_WIDE, 0f, 1f, 0f));
            addOnce(model, new RigParameter(EYE_BALL_X, -1f, 1f, 0f));
            addOnce(model, new RigParameter(EYE_BALL_Y, -1f, 1f, 0f));
            addOnce(model, new RigParameter(BROW_ANGLE, -1f, 1f, 0f));
            addOnce(model, new RigParameter(BROW_Y, -1f, 1f, 0f));
        }

        // Meshes, one per layer, in the file's order.
        int order = 0;
        int eyeIndex = 0;
        for (RigLayer l : layers) {
            Part part = owner.get(l);
            if (part == null) {
                part = body;
            }
            AutoMesh.Result mesh;
            if (part.kind == Kind.EYE) {
                // Fine enough for the lids to fold and the gaze to move inside the eye.
                mesh = AutoMesh.of(l, Math.max(3, (int) Math.ceil(Math.max(l.width(), l.height()) / 14f)), 2);
            } else {
                mesh = AutoMesh.of(l, l == bodyLayer ? 28 : 10);
            }
            String id = RigModel.unique(l.name().isBlank() ? "Mesh" : l.name(), model.meshIds());
            ArtMesh art = new ArtMesh(id, l.path(), part.deformer, mesh.vertices(), mesh.triangles(), order);
            order += 10;
            if (part.kind == Kind.EYE) {
                eyeTracks(art, eyes.get(eyeIndex++));
            } else {
                List<EyeFrame> above = new ArrayList<>();
                for (EyeFrame e : eyes) {
                    if (e.parent == part) {
                        above.add(e);
                    }
                }
                if (!above.isEmpty()) {
                    browTracks(art, l, above);
                }
            }
            model.meshes.add(art);
        }

        model.resolve();
        motions(model, sways);
        model.resolve();
        return model;
    }

    private static Kind hintedKind(Hint hint, Kind fallback) {
        if (hint == null || hint.kind() == null) {
            return fallback;
        }
        try {
            return Kind.valueOf(hint.kind().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static boolean under(Part part, Kind kind) {
        for (Part p = part.parent; p != null; p = p.parent) {
            if (p.kind == kind) {
                return true;
            }
        }
        return false;
    }

    private static void addOnce(RigModel model, RigParameter parameter) {
        if (model.parameterIndex(parameter.id()) < 0) {
            model.parameters.add(parameter);
            model.resolve();
        }
    }

    /**
     * Where a part joins what it hangs from: the middle of where the two overlap, or failing any overlap, the point of
     * the part's box nearest the parent's middle. The tip is the painted pixel furthest from there.
     */
    private static void pivot(Part part, Part parent) {
        long sx = 0;
        long sy = 0;
        long n = 0;
        int l = (int) Math.floor(part.left);
        int t = (int) Math.floor(part.top);
        int r = (int) Math.ceil(part.right);
        int b = (int) Math.ceil(part.bottom);
        for (int y = t; y < b; y++) {
            for (int x = l; x < r; x++) {
                if (part.alphaAt(x, y) > 128 && parent.alphaAt(x, y) > 128) {
                    sx += x;
                    sy += y;
                    n++;
                }
            }
        }
        float parentX = (parent.left + parent.right) * 0.5f;
        float parentY = (parent.top + parent.bottom) * 0.5f;
        if (n >= 12) {
            part.pivotX = sx / (float) n;
            part.pivotY = sy / (float) n;
            // Pull the pivot toward the part's edge nearest the parent: the overlap's middle sits inside the joint.
            float nx = Math.max(part.left, Math.min(part.right, parentX));
            float ny = Math.max(part.top, Math.min(part.bottom, parentY));
            part.pivotX += (nx - part.pivotX) * 0.25f;
            part.pivotY += (ny - part.pivotY) * 0.25f;
        } else {
            part.pivotX = Math.max(part.left, Math.min(part.right, parentX));
            part.pivotY = Math.max(part.top, Math.min(part.bottom, parentY));
        }
        tip(part);
    }

    /** The painted pixel furthest from the part's pivot. */
    private static void tip(Part part) {
        int l = (int) Math.floor(part.left);
        int t = (int) Math.floor(part.top);
        int r = (int) Math.ceil(part.right);
        int b = (int) Math.ceil(part.bottom);
        float best = -1f;
        part.tipX = part.pivotX;
        part.tipY = part.pivotY;
        for (int y = t; y < b; y += 2) {
            for (int x = l; x < r; x += 2) {
                if (part.alphaAt(x, y) > 128) {
                    float d = (x - part.pivotX) * (x - part.pivotX) + (y - part.pivotY) * (y - part.pivotY);
                    if (d > best) {
                        best = d;
                        part.tipX = x;
                        part.tipY = y;
                    }
                }
            }
        }
    }

    /** The body warp's tracks: breathing, leaning, stretching and squashing, lunging and recoiling, feet planted. */
    private static void bodyTracks(Deformer.Warp warp, float feetX, float feetY, float w, float h, int facing) {
        int points = (warp.columns + 1) * (warp.rows + 1);
        float[] breath = new float[points * 2];
        float[] leanRight = new float[points * 2];
        float[] leanLeft = new float[points * 2];
        float[] stretch = new float[points * 2];
        float[] squash = new float[points * 2];
        float[] lunge = new float[points * 2];
        float[] recoil = new float[points * 2];
        for (int r = 0; r <= warp.rows; r++) {
            for (int c = 0; c <= warp.columns; c++) {
                int i = (r * (warp.columns + 1) + c) * 2;
                float gx = warp.restX(c);
                float gy = warp.restY(r);
                // How high up the figure (0 at the feet, 1 at the top) and how far across it (-1..1).
                float up = Math.max(0f, Math.min(1.15f, (feetY - gy) / h));
                float across = Math.max(-1.3f, Math.min(1.3f, (gx - feetX) / (w * 0.5f)));
                float middle = (float) Math.sin(Math.PI * Math.min(1f, up));

                breath[i] = across * 0.014f * w * middle;
                breath[i + 1] = -0.03f * h * up;

                float lean = 0.075f * w * (float) Math.pow(up, 1.4);
                leanRight[i] = lean;
                leanRight[i + 1] = 0.018f * h * up * across;
                leanLeft[i] = -lean;
                leanLeft[i + 1] = -0.018f * h * up * across;

                stretch[i] = -across * 0.035f * w * up;
                stretch[i + 1] = -0.07f * h * up;
                squash[i] = across * 0.06f * w * (0.4f * up + 0.6f * middle);
                squash[i + 1] = 0.085f * h * up;

                lunge[i] = facing * (0.12f * w * (float) Math.pow(up, 1.2) + 0.03f * w * middle);
                lunge[i + 1] = 0.04f * h * up;
                recoil[i] = -facing * 0.09f * w * (float) Math.pow(up, 1.3);
                recoil[i + 1] = 0.03f * h * up;
                recoil[i] += across * 0.02f * w * middle;
            }
        }
        float[] zero = new float[points * 2];
        warp.keyforms.tracks.add(Keyforms.Track.of(BREATH, new float[]{0f, 1f}, zero, breath));
        warp.keyforms.tracks.add(Keyforms.Track.of(BODY_ANGLE_X, new float[]{-10f, 0f, 10f}, leanLeft, zero, leanRight));
        warp.keyforms.tracks.add(Keyforms.Track.of(BODY_ANGLE_Y, new float[]{-10f, 0f, 10f}, squash, zero, stretch));
        warp.keyforms.tracks.add(Keyforms.Track.of(LUNGE, new float[]{0f, 1f}, zero, lunge));
        warp.keyforms.tracks.add(Keyforms.Track.of(RECOIL, new float[]{0f, 1f}, zero, recoil));
    }

    private static float degrees(Part part, float fallback) {
        return part.hint != null && part.hint.degrees() != null ? part.hint.degrees() : fallback;
    }

    /** A warp over a loose part that bends it along its length either way: more toward the tip, none at the root. */
    private static Deformer.Warp bendWarp(String id, String parent, Part part, String sway) {
        float margin = Math.max(part.width(), part.height()) * 0.15f + 4f;
        Deformer.Warp warp = new Deformer.Warp(id, parent, part.left - margin, part.top - margin,
                part.width() + 2f * margin, part.height() + 2f * margin, 4, 4);
        float length = Math.max(8f, (float) Math.hypot(part.tipX - part.pivotX, part.tipY - part.pivotY));
        String lower = part.name.toLowerCase(Locale.ROOT);
        float degrees = part.hint != null && part.hint.degrees() != null ? part.hint.degrees()
                : lower.contains("wing") ? 24f : lower.contains("tail") ? 18f : lower.contains("fin") ? 15f
                : lower.contains("ear") ? 10f : 13f;
        int points = (warp.columns + 1) * (warp.rows + 1);
        float[] plus = new float[points * 2];
        float[] minus = new float[points * 2];
        for (int r = 0; r <= warp.rows; r++) {
            for (int c = 0; c <= warp.columns; c++) {
                int i = (r * (warp.columns + 1) + c) * 2;
                float dx = warp.restX(c) - part.pivotX;
                float dy = warp.restY(r) - part.pivotY;
                float along = Math.min(1.4f, (float) Math.hypot(dx, dy) / length);
                double angle = Math.toRadians(degrees) * Math.pow(along, 1.3);
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);
                plus[i] = dx * cos - dy * sin - dx;
                plus[i + 1] = dx * sin + dy * cos - dy;
                minus[i] = dx * cos + dy * sin - dx;
                minus[i + 1] = -dx * sin + dy * cos - dy;
            }
        }
        warp.keyforms.tracks.add(Keyforms.Track.of(sway, new float[]{-1f, 0f, 1f}, minus, new float[points * 2], plus));
        return warp;
    }

    /** An eye's oval on the canvas; {@code inner} is the way (+1 right, -1 left) its inner corner lies. */
    private record EyeFrame(float cx, float cy, float rx, float ry, float inner, Part parent) {
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float smooth(float t) {
        t = clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * Where a point of an eye (in the eye's own units: u across, -1 to 1, v down, -1 at the top to 1 at the bottom)
     * goes for one corner of the open/lid/smile shapes. Shut, everything folds onto a thin line (a happy arc when
     * smiling); heavy lids fold the top of the eye down onto a flat line, so the lash line comes down over it; a smile
     * folds the bottom up under an arching lower lid.
     */
    private static float eyeShape(float u, float v, boolean open, boolean lid, boolean smile) {
        float uc = clamp(u, -1f, 1f);
        if (!open) {
            float line = smile ? -0.15f + 0.5f * uc * uc : 0.1f;
            if (lid) {
                line += smile ? 0.1f : 0.15f;
            }
            return line + (v - 0.1f) * 0.12f;
        }
        float top = lid ? (smile ? -0.15f : 0.08f) : -99f;
        float bottom = smile ? (lid ? 0.2f + 0.45f * uc * uc : 0.15f + 0.5f * uc * uc) : 99f;
        if (v < top) {
            return top + (v - top) * 0.14f;
        }
        if (v > bottom) {
            return bottom + (v - bottom) * 0.14f;
        }
        return v;
    }

    /** An eye's tracks: blink, lids and smile together; the upper lid's slant; the gaze; opening wide. */
    private static void eyeTracks(ArtMesh mesh, EyeFrame e) {
        int size = mesh.keyforms.size();
        int n = mesh.vertexCount();

        // EyeOpen x EyeLid x EyeSmile, the first changing fastest.
        Keyforms.Track shape = new Keyforms.Track();
        shape.parameters.add(EYE_OPEN);
        shape.parameters.add(EYE_LID);
        shape.parameters.add(EYE_SMILE);
        shape.keys.add(new float[]{0f, 1f});
        shape.keys.add(new float[]{0f, 1f});
        shape.keys.add(new float[]{0f, 1f});
        for (int s = 0; s <= 1; s++) {
            for (int l = 0; l <= 1; l++) {
                for (int o = 0; o <= 1; o++) {
                    float[] form = new float[size];
                    if (!(o == 1 && l == 0 && s == 0)) {
                        for (int v = 0; v < n; v++) {
                            float u = (mesh.vertices[v * 2] - e.cx) / e.rx;
                            float vv = (mesh.vertices[v * 2 + 1] - e.cy) / e.ry;
                            form[ArtMesh.VERTICES + v * 2 + 1] = (eyeShape(u, vv, o == 1, l == 1, s == 1) - vv) * e.ry;
                        }
                    }
                    shape.forms.add(form);
                }
            }
        }
        mesh.keyforms.tracks.add(shape);

        // BrowAngle: the upper lid slants down to the inner corner (cross), or droops at the outer one (worried).
        float[] cross = new float[size];
        float[] worried = new float[size];
        for (int v = 0; v < n; v++) {
            float u = (mesh.vertices[v * 2] - e.cx) / e.rx;
            float vv = (mesh.vertices[v * 2 + 1] - e.cy) / e.ry;
            float upper = clamp((0.4f - vv) / 1.4f, 0f, 1f);
            float side = clamp(u, -1f, 1f) * e.inner;
            cross[ArtMesh.VERTICES + v * 2 + 1] = upper * 0.58f * clamp((side + 0.5f) / 1.5f, 0f, 1f) * e.ry;
            worried[ArtMesh.VERTICES + v * 2 + 1] = upper * 0.46f * clamp((0.5f - side) / 1.5f, 0f, 1f) * e.ry;
        }
        mesh.keyforms.tracks.add(Keyforms.Track.of(BROW_ANGLE, new float[]{-1f, 0f, 1f}, worried, new float[size], cross));

        // EyeBallX x EyeBallY: the middle of the eye moves, its rim stays.
        Keyforms.Track gaze = new Keyforms.Track();
        gaze.parameters.add(EYE_BALL_X);
        gaze.parameters.add(EYE_BALL_Y);
        gaze.keys.add(new float[]{-1f, 0f, 1f});
        gaze.keys.add(new float[]{-1f, 0f, 1f});
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                float[] form = new float[size];
                for (int v = 0; v < n; v++) {
                    float u = (mesh.vertices[v * 2] - e.cx) / e.rx;
                    float vv = (mesh.vertices[v * 2 + 1] - e.cy) / e.ry;
                    float inside = 1f - smooth(((float) Math.hypot(u, vv) - 0.35f) / 0.6f);
                    form[ArtMesh.VERTICES + v * 2] = x * 0.2f * e.rx * inside;
                    form[ArtMesh.VERTICES + v * 2 + 1] = -y * 0.14f * e.ry * inside;
                }
                gaze.forms.add(form);
            }
        }
        mesh.keyforms.tracks.add(gaze);

        // EyeWide: the eye grows about its middle.
        float[] wide = new float[size];
        for (int v = 0; v < n; v++) {
            wide[ArtMesh.VERTICES + v * 2] = (mesh.vertices[v * 2] - e.cx) * 0.15f;
            wide[ArtMesh.VERTICES + v * 2 + 1] = (mesh.vertices[v * 2 + 1] - e.cy) * 0.15f;
        }
        mesh.keyforms.tracks.add(Keyforms.Track.of(EYE_WIDE, new float[]{0f, 1f}, new float[size], wide));
    }

    /**
     * The brows on what the eyes sit on: the paint over each eye rises and falls ({@code BrowY}) and knits down or
     * lifts at the inner corner ({@code BrowAngle}), fading out round it so the fur only stretches.
     */
    private static void browTracks(ArtMesh mesh, RigLayer layer, List<EyeFrame> eyes) {
        int size = mesh.keyforms.size();
        float[] up = new float[size];
        float[] down = new float[size];
        float[] cross = new float[size];
        float[] worried = new float[size];
        boolean any = false;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            float x = mesh.vertices[v * 2];
            float y = mesh.vertices[v * 2 + 1];
            float best = 0f;
            EyeFrame near = null;
            for (EyeFrame e : eyes) {
                float dx = (x - e.cx) / (e.rx * 1.5f);
                float dy = (y - (e.cy - e.ry * 1.45f)) / (e.ry * 0.95f);
                float w = 1f - smooth(((float) Math.hypot(dx, dy) - 0.45f) / 0.55f);
                if (w > best) {
                    best = w;
                    near = e;
                }
            }
            if (near == null || best <= 0f) {
                continue;
            }
            // Held still near the figure's edge, so the outline never pulls in to show what is behind it.
            float reach = Math.max(3f, near.ry * 0.6f);
            int solid = 0;
            for (int k = 0; k < 12; k++) {
                double a = Math.PI * 2.0 * k / 12.0;
                if (layer.alphaAt(Math.round(x + (float) Math.cos(a) * reach),
                        Math.round(y + (float) Math.sin(a) * reach)) > 128) {
                    solid++;
                }
            }
            best *= (float) Math.pow(solid / 12f, 3);
            if (best <= 0.01f) {
                continue;
            }
            any = true;
            float side = clamp((x - near.cx) / (near.rx * 1.2f) * near.inner, -1f, 1f);
            float tilt = best * 0.45f * near.ry * (0.35f + 0.65f * side);
            int at = ArtMesh.VERTICES + v * 2 + 1;
            up[at] = -0.45f * near.ry * best;
            down[at] = 0.35f * near.ry * best;
            cross[at] = tilt;
            worried[at] = -tilt;
        }
        if (any) {
            mesh.keyforms.tracks.add(Keyforms.Track.of(BROW_Y, new float[]{-1f, 0f, 1f}, down, new float[size], up));
            mesh.keyforms.tracks.add(Keyforms.Track.of(BROW_ANGLE, new float[]{-1f, 0f, 1f}, worried, new float[size],
                    cross));
        }
    }

    /** The motions every auto rig gets, for the parameters it has. */
    static void motions(RigModel model, List<String> sways) {
        int f = model.facing;
        boolean head = model.parameterIndex(HEAD_ANGLE) >= 0;
        boolean mouth = model.parameterIndex(MOUTH_OPEN) >= 0;
        boolean eyes = model.parameterIndex(EYE_OPEN) >= 0;
        List<String> swings = new ArrayList<>();
        for (RigParameter p : model.parameters) {
            if (p.id().startsWith("Swing ")) {
                swings.add(p.id());
            }
        }

        RigMotion idle = new RigMotion("idle", 3.2f, true);
        idle.curve(BREATH, new float[]{0f, 1.6f}, new float[]{0f, 1f});
        idle.curve(BODY_ANGLE_X, new float[]{0f, 0.8f, 2.4f}, new float[]{0f, 2.2f, -2.2f});
        idle.curve(BODY_ANGLE_Y, new float[]{0f, 1.6f}, new float[]{-0.8f, 0.8f});
        if (head) {
            idle.curve(HEAD_ANGLE, new float[]{0.3f, 1.9f}, new float[]{-1.5f, 1.5f});
        }
        // No blink here: RigEyes blinks at its own times, so rigs side by side never blink together.
        for (int i = 0; i < sways.size(); i++) {
            float phase = 0.35f * i;
            boolean wing = sways.get(i).toLowerCase(Locale.ROOT).contains("wing");
            if (wing) {
                idle.curve(sways.get(i), new float[]{(phase) % 3.2f, (phase + 0.4f) % 3.2f, (phase + 0.8f) % 3.2f,
                        (phase + 1.2f) % 3.2f, (phase + 1.6f) % 3.2f, (phase + 2.0f) % 3.2f, (phase + 2.4f) % 3.2f,
                        (phase + 2.8f) % 3.2f}, new float[]{-0.6f, 0.6f, -0.6f, 0.6f, -0.6f, 0.6f, -0.6f, 0.6f});
                sortCurve(idle.curves.getLast());
            } else {
                idle.curve(sways.get(i), new float[]{phase % 3.2f, (phase + 1.6f) % 3.2f}, new float[]{-0.35f, 0.35f});
                sortCurve(idle.curves.getLast());
            }
        }
        model.motions.put(idle.id, idle);

        RigMotion attack = new RigMotion("attack", 0.85f, false);
        attack.fadeIn = 0.04f;
        attack.fadeOut = 0.25f;
        attack.curve(RECOIL, new float[]{0f, 0.16f, 0.26f, 0.85f}, new float[]{0f, 0.7f, 0f, 0f});
        attack.curve(LUNGE, new float[]{0f, 0.16f, 0.3f, 0.46f, 0.85f}, new float[]{0f, 0f, 1f, 0.9f, 0f});
        attack.curve(BODY_ANGLE_Y, new float[]{0f, 0.16f, 0.3f, 0.6f}, new float[]{0f, -6f, 5f, 0f});
        if (mouth) {
            attack.curve(MOUTH_OPEN, new float[]{0f, 0.2f, 0.3f, 0.5f, 0.75f}, new float[]{0f, 0.2f, 1f, 1f, 0f});
        }
        if (head) {
            attack.curve(HEAD_ANGLE, new float[]{0f, 0.16f, 0.3f, 0.7f}, new float[]{0f, -4f * f, 6f * f, 0f});
        }
        for (String swing : swings) {
            attack.curve(swing, new float[]{0f, 0.16f, 0.3f, 0.7f}, new float[]{0f, -0.8f, 1f, 0f});
        }
        if (eyes) {
            // Set on it: brows knit, eyes wide on the strike, looking where it strikes.
            attack.curve(BROW_ANGLE, new float[]{0f, 0.16f, 0.5f, 0.85f}, new float[]{0f, 0.8f, 0.8f, 0f});
            attack.curve(BROW_Y, new float[]{0f, 0.16f, 0.5f, 0.85f}, new float[]{0f, -0.5f, -0.4f, 0f});
            attack.curve(EYE_WIDE, new float[]{0f, 0.2f, 0.32f, 0.6f}, new float[]{0f, 0f, 0.35f, 0f});
            attack.curve(EYE_LID, new float[]{0f, 0.16f, 0.6f}, new float[]{0f, 0.25f, 0f});
            attack.curve(EYE_SMILE, new float[]{0f}, new float[]{0f});
            attack.curve(EYE_OPEN, new float[]{0f}, new float[]{1f});
            attack.curve(EYE_BALL_X, new float[]{0f, 0.12f, 0.7f, 0.85f}, new float[]{0f, 0.8f * f, 0.8f * f, 0f});
            attack.curve(EYE_BALL_Y, new float[]{0f}, new float[]{0f});
        }
        model.motions.put(attack.id, attack);

        RigMotion hit = new RigMotion("hit", 0.65f, false);
        hit.fadeIn = 0.02f;
        hit.fadeOut = 0.25f;
        hit.curve(RECOIL, new float[]{0f, 0.07f, 0.25f, 0.65f}, new float[]{0f, 1f, 0.55f, 0f});
        hit.curve(BODY_ANGLE_Y, new float[]{0f, 0.08f, 0.3f, 0.65f}, new float[]{0f, -7f, 2f, 0f});
        if (eyes) {
            // Squeezed shut, brows up in the middle, then a wide-eyed start.
            hit.curve(EYE_OPEN, new float[]{0f, 0.05f, 0.4f, 0.55f}, new float[]{1f, 0.05f, 0.05f, 1f});
            hit.curve(BROW_ANGLE, new float[]{0f, 0.06f, 0.45f, 0.65f}, new float[]{0f, -0.9f, -0.7f, 0f});
            hit.curve(BROW_Y, new float[]{0f, 0.06f, 0.45f, 0.65f}, new float[]{0f, -0.4f, 0.3f, 0f});
            hit.curve(EYE_WIDE, new float[]{0f, 0.4f, 0.5f, 0.65f}, new float[]{0f, 0f, 0.3f, 0f});
            hit.curve(EYE_LID, new float[]{0f}, new float[]{0f});
            hit.curve(EYE_SMILE, new float[]{0f}, new float[]{0f});
        }
        if (mouth) {
            hit.curve(MOUTH_OPEN, new float[]{0f, 0.08f, 0.45f}, new float[]{0f, 0.45f, 0f});
        }
        if (head) {
            hit.curve(HEAD_ANGLE, new float[]{0f, 0.08f, 0.5f}, new float[]{0f, -7f * f, 0f});
        }
        model.motions.put(hit.id, hit);

        RigMotion faint = new RigMotion("faint", 1.1f, false);
        faint.fadeIn = 0.05f;
        faint.fadeOut = 0.4f;
        faint.curve(RECOIL, new float[]{0f, 0.2f, 1.1f}, new float[]{0f, 1f, 0.8f});
        faint.curve(BODY_ANGLE_Y, new float[]{0f, 0.2f, 0.6f, 1.1f}, new float[]{0f, 4f, -10f, -10f});
        if (eyes) {
            faint.curve(EYE_OPEN, new float[]{0f, 0.15f}, new float[]{1f, 0f});
            faint.curve(BROW_ANGLE, new float[]{0f, 0.2f}, new float[]{0f, -0.7f});
            faint.curve(BROW_Y, new float[]{0f, 0.2f, 1.1f}, new float[]{0f, 0.2f, -0.3f});
            faint.curve(EYE_LID, new float[]{0f, 0.2f}, new float[]{0f, 0.5f});
            faint.curve(EYE_SMILE, new float[]{0f}, new float[]{0f});
            faint.curve(EYE_WIDE, new float[]{0f}, new float[]{0f});
        }
        if (mouth) {
            faint.curve(MOUTH_OPEN, new float[]{0f, 0.2f, 1.1f}, new float[]{0f, 0.7f, 0.4f});
        }
        if (head) {
            faint.curve(HEAD_ANGLE, new float[]{0f, 0.3f, 1.1f}, new float[]{0f, -10f * f, -10f * f});
        }
        model.motions.put(faint.id, faint);

        RigMotion happy = new RigMotion("happy", 1.1f, false);
        happy.fadeIn = 0.08f;
        happy.fadeOut = 0.25f;
        happy.curve(BODY_ANGLE_Y, new float[]{0f, 0.15f, 0.32f, 0.5f, 0.68f, 0.85f, 1.1f},
                new float[]{0f, -7f, 8f, -6f, 7f, -3f, 0f});
        if (mouth) {
            happy.curve(MOUTH_OPEN, new float[]{0f, 0.2f, 0.9f, 1.1f}, new float[]{0f, 0.7f, 0.7f, 0f});
        }
        if (head) {
            happy.curve(HEAD_ANGLE, new float[]{0f, 0.32f, 0.68f, 1.1f}, new float[]{0f, 5f, -5f, 0f});
        }
        for (String sway : sways) {
            happy.curve(sway, new float[]{0f, 0.25f, 0.55f, 0.85f, 1.1f}, new float[]{0f, 0.6f, -0.6f, 0.4f, 0f});
        }
        if (eyes) {
            // Eyes shut in happy arcs, brows up.
            happy.curve(EYE_SMILE, new float[]{0f, 0.12f, 0.9f, 1.1f}, new float[]{0f, 1f, 1f, 0f});
            happy.curve(EYE_OPEN, new float[]{0f, 0.12f, 0.9f, 1.1f}, new float[]{1f, 0f, 0f, 1f});
            happy.curve(BROW_Y, new float[]{0f, 0.15f, 0.9f, 1.1f}, new float[]{0f, 0.7f, 0.7f, 0f});
            happy.curve(BROW_ANGLE, new float[]{0f, 0.15f, 0.9f, 1.1f}, new float[]{0f, -0.35f, -0.35f, 0f});
            happy.curve(EYE_LID, new float[]{0f}, new float[]{0f});
            happy.curve(EYE_WIDE, new float[]{0f}, new float[]{0f});
        }
        model.motions.put(happy.id, happy);

        RigMotion appear = new RigMotion("appear", 0.75f, false);
        appear.fadeIn = 0f;
        appear.fadeOut = 0.2f;
        appear.curve(BODY_ANGLE_Y, new float[]{0f, 0.12f, 0.3f, 0.5f, 0.75f}, new float[]{-10f, 7f, -4f, 2f, 0f});
        if (mouth) {
            appear.curve(MOUTH_OPEN, new float[]{0f, 0.2f, 0.5f}, new float[]{0f, 0.9f, 0f});
        }
        if (eyes) {
            // Eyes pop open, wide, brows up, as it lands.
            appear.curve(EYE_OPEN, new float[]{0f, 0.1f, 0.22f}, new float[]{0f, 0f, 1f});
            appear.curve(EYE_WIDE, new float[]{0f, 0.22f, 0.32f, 0.7f}, new float[]{0f, 0f, 0.4f, 0f});
            appear.curve(BROW_Y, new float[]{0f, 0.25f, 0.7f}, new float[]{0f, 0.7f, 0f});
        }
        model.motions.put(appear.id, appear);
    }

    private static void sortCurve(RigMotion.Curve curve) {
        int n = curve.times.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Float.compare(curve.times[a], curve.times[b]));
        float[] t = new float[n];
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            t[i] = curve.times[order[i]];
            v[i] = curve.values[order[i]];
        }
        System.arraycopy(t, 0, curve.times, 0, n);
        System.arraycopy(v, 0, curve.values, 0, n);
    }
}
