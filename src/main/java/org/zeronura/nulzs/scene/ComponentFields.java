package org.zeronura.nulzs.scene;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.zeronura.nulzs.graphics2d.Color;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A component's settings as an editor sees them: its public fields of the kinds an Inspector can show and a scene file
 * can hold, read and written by reflection.
 *
 * <p>The rule is the one {@link Component} has always promised: <b>public fields are settings</b>. A field shows up if
 * it is public, not static, not {@code transient} and not {@link HideInEditor}, and is one of:
 * <ul>
 *   <li>{@code boolean}, {@code int}, {@code long}, {@code float}, {@code double}, {@code String}, an enum, or a
 *       {@link Color}</li>
 *   <li>a JOML {@code Vector2f}, {@code Vector3f} or {@code Vector4f} — these may be {@code final}, being changed in
 *       place</li>
 *   <li>an {@link Entity}, or any {@link Component} type: a reference to another object in the scene, drawn as a wire in
 *       the editor's Connections view</li>
 *   <li>a {@link Signal}, usually {@code final}: an event wired to other entities' methods</li>
 * </ul>
 * Anything else — a mesh, a texture, a list — is the component's own business and is left out.
 */
public final class ComponentFields {

    /** What sort of value a field holds, which decides how it is drawn and saved. */
    public enum Kind {
        BOOLEAN, INT, LONG, FLOAT, DOUBLE, STRING, ENUM, COLOR, VECTOR2, VECTOR3, VECTOR4, ENTITY, COMPONENT, SIGNAL;

        /** Whether this is a link to something else in the scene rather than a value. */
        public boolean isReference() {
            return this == ENTITY || this == COMPONENT || this == SIGNAL;
        }
    }

    /** One editable field. */
    public static final class Property {
        private final Field field;
        private final Kind kind;
        private final String label;

        Property(Field field, Kind kind) {
            this.field = field;
            this.kind = kind;
            this.label = ComponentFields.label(field.getName());
        }

        public String name() {
            return field.getName();
        }

        /** The field's name as words: {@code jumpHeight} as "Jump Height". */
        public String label() {
            return label;
        }

        public Kind kind() {
            return kind;
        }

        public Class<?> type() {
            return field.getType();
        }

        public Field field() {
            return field;
        }

        public Asset asset() {
            return field.getAnnotation(Asset.class);
        }

        public Range range() {
            return field.getAnnotation(Range.class);
        }

        public String tooltip() {
            Tooltip tooltip = field.getAnnotation(Tooltip.class);
            return tooltip == null ? null : tooltip.value();
        }

        public Object get(Object owner) {
            try {
                return field.get(owner);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Sets the field. Vectors are copied into the one already there (their fields may be final); numbers are
         * converted to the field's own type, so an editor can hand over any {@link Number}.
         */
        public void set(Object owner, Object value) {
            try {
                switch (kind) {
                    case VECTOR2 -> ((Vector2f) field.get(owner)).set((Vector2f) value);
                    case VECTOR3 -> ((Vector3f) field.get(owner)).set((Vector3f) value);
                    case VECTOR4 -> ((Vector4f) field.get(owner)).set((Vector4f) value);
                    case SIGNAL -> throw new IllegalArgumentException("a signal is changed through its connections");
                    case INT -> field.setInt(owner, ((Number) value).intValue());
                    case LONG -> field.setLong(owner, ((Number) value).longValue());
                    case FLOAT -> field.setFloat(owner, ((Number) value).floatValue());
                    case DOUBLE -> field.setDouble(owner, ((Number) value).doubleValue());
                    case BOOLEAN -> field.setBoolean(owner, (Boolean) value);
                    default -> field.set(owner, value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final Map<Class<?>, List<Property>> CACHE = new ConcurrentHashMap<>();

    private ComponentFields() {
    }

    /** The editable fields of a class, base class first, each class's in the order they are written. */
    public static List<Property> of(Class<?> type) {
        return CACHE.computeIfAbsent(type, ComponentFields::scan);
    }

    /** Forgets what was learned about classes, for when a project's scripts are compiled again. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static List<Property> scan(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class && c != Component.class; c = c.getSuperclass()) {
            chain.addFirst(c);
        }
        List<Property> properties = new ArrayList<>();
        for (Class<?> c : chain) {
            for (Field field : c.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)
                        || field.isSynthetic() || field.isAnnotationPresent(HideInEditor.class)) {
                    continue;
                }
                Kind kind = kindOf(field.getType());
                if (kind == null) {
                    continue;
                }
                boolean changedInPlace = kind == Kind.VECTOR2 || kind == Kind.VECTOR3 || kind == Kind.VECTOR4
                        || kind == Kind.SIGNAL;
                if (Modifier.isFinal(modifiers) && !changedInPlace) {
                    continue;
                }
                properties.add(new Property(field, kind));
            }
        }
        return List.copyOf(properties);
    }

    /** The kind a field of this type is, or null if an editor can't show it. */
    public static Kind kindOf(Class<?> type) {
        if (type == boolean.class) {
            return Kind.BOOLEAN;
        }
        if (type == int.class) {
            return Kind.INT;
        }
        if (type == long.class) {
            return Kind.LONG;
        }
        if (type == float.class) {
            return Kind.FLOAT;
        }
        if (type == double.class) {
            return Kind.DOUBLE;
        }
        if (type == String.class) {
            return Kind.STRING;
        }
        if (type.isEnum()) {
            return Kind.ENUM;
        }
        if (type == Color.class) {
            return Kind.COLOR;
        }
        if (type == Vector2f.class) {
            return Kind.VECTOR2;
        }
        if (type == Vector3f.class) {
            return Kind.VECTOR3;
        }
        if (type == Vector4f.class) {
            return Kind.VECTOR4;
        }
        if (type == Entity.class) {
            return Kind.ENTITY;
        }
        if (Component.class.isAssignableFrom(type)) {
            return Kind.COMPONENT;
        }
        if (type == Signal.class) {
            return Kind.SIGNAL;
        }
        return null;
    }

    /**
     * Whether an editor can make one of these: a public, concrete component class with a public constructor taking
     * nothing.
     */
    public static boolean isCreatable(Class<?> type) {
        if (!Component.class.isAssignableFrom(type) || type == MissingComponent.class) {
            return false;
        }
        int modifiers = type.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers) || type.isInterface()
                || type.isAnonymousClass() || type.isLocalClass()
                || (type.getEnclosingClass() != null && !Modifier.isStatic(modifiers))) {
            return false;
        }
        try {
            Constructor<?> constructor = type.getConstructor();
            return Modifier.isPublic(constructor.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** A new component of a creatable type. */
    public static Component create(Class<?> type) throws ReflectiveOperationException {
        return (Component) type.getConstructor().newInstance();
    }

    // --- Saving -------------------------------------------------------------------------------------------------

    /**
     * The settings of a component (or any object with such fields) as JSON values, by field name.
     *
     * @param idOf the saved id of an entity, null for one that isn't saved
     */
    public static Map<String, Object> save(Object owner, Function<Entity, String> idOf) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Property property : of(owner.getClass())) {
            Object json = toJson(property, property.get(owner), idOf);
            if (json != null || property.kind() == Kind.STRING) {
                out.put(property.name(), json);
            }
        }
        return out;
    }

    /** One value as JSON. */
    public static Object toJson(Property property, Object value, Function<Entity, String> idOf) {
        if (value == null) {
            return null;
        }
        return switch (property.kind()) {
            case BOOLEAN, INT, LONG, STRING -> value;
            case FLOAT -> decimal((Float) value);
            case DOUBLE -> value;
            case ENUM -> ((Enum<?>) value).name();
            case COLOR -> colorToHex((Color) value);
            case VECTOR2 -> List.of(decimal(((Vector2f) value).x), decimal(((Vector2f) value).y));
            case VECTOR3 -> vector((Vector3f) value);
            case VECTOR4 -> {
                Vector4f v = (Vector4f) value;
                yield List.of(decimal(v.x), decimal(v.y), decimal(v.z), decimal(v.w));
            }
            case ENTITY -> idOf.apply((Entity) value);
            case COMPONENT -> {
                Component component = (Component) value;
                String id = component.entity() == null ? null : idOf.apply(component.entity());
                yield id == null ? null : componentReference(id, component);
            }
            case SIGNAL -> {
                List<Object> wires = new ArrayList<>();
                for (Signal.Connection connection : ((Signal) value).connections()) {
                    String id = connection.target() == null ? null : idOf.apply(connection.target());
                    if (id != null) {
                        Map<String, Object> wire = new LinkedHashMap<>();
                        wire.put("entity", id);
                        wire.put("component", connection.component());
                        wire.put("method", connection.method());
                        wires.add(wire);
                    }
                }
                yield wires;
            }
        };
    }

    private static Map<String, Object> componentReference(String entityId, Component component) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("entity", entityId);
        reference.put("type", component.getClass().getName());
        return reference;
    }

    /**
     * Sets the plain values of {@code owner} — everything but references — from saved JSON. Fields the JSON doesn't
     * mention keep their defaults; a value that doesn't fit its field is reported, not thrown.
     *
     * @param problems where to say what could not be set
     */
    public static void loadValues(Object owner, Map<String, Object> fields, List<String> problems) {
        for (Property property : of(owner.getClass())) {
            if (property.kind().isReference() || !fields.containsKey(property.name())) {
                continue;
            }
            try {
                Object value = fromJson(property, fields.get(property.name()));
                if (value != null || property.kind() == Kind.STRING) {
                    property.set(owner, value);
                }
            } catch (RuntimeException e) {
                problems.add(owner.getClass().getSimpleName() + "." + property.name() + ": " + e.getMessage());
            }
        }
    }

    /** Sets the references of {@code owner} from saved JSON, once every entity they might point at exists. */
    public static void loadReferences(Object owner, Map<String, Object> fields, Function<String, Entity> entityById,
                                      List<String> problems) {
        for (Property property : of(owner.getClass())) {
            if (!property.kind().isReference() || !fields.containsKey(property.name())) {
                continue;
            }
            Object json = fields.get(property.name());
            try {
                switch (property.kind()) {
                    case ENTITY -> property.set(owner, json instanceof String id ? entityById.apply(id) : null);
                    case COMPONENT -> {
                        Component target = null;
                        if (json instanceof Map<?, ?> reference && reference.get("entity") instanceof String id) {
                            Entity entity = entityById.apply(id);
                            Object type = reference.get("type");
                            if (entity != null) {
                                target = type instanceof String name ? Signal.find(entity, name) : null;
                                if (target == null || !property.type().isInstance(target)) {
                                    @SuppressWarnings("unchecked")
                                    Class<Object> wanted = (Class<Object>) property.type();
                                    target = (Component) entity.get(wanted);
                                }
                            }
                        }
                        property.set(owner, target);
                    }
                    case SIGNAL -> {
                        Signal signal = (Signal) property.get(owner);
                        if (signal == null || !(json instanceof List<?> wires)) {
                            break;
                        }
                        signal.clearConnections();
                        for (Object item : wires) {
                            if (item instanceof Map<?, ?> wire && wire.get("entity") instanceof String id) {
                                Entity entity = entityById.apply(id);
                                if (entity != null) {
                                    signal.connect(entity, String.valueOf(wire.get("component")),
                                            String.valueOf(wire.get("method")));
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                problems.add(owner.getClass().getSimpleName() + "." + property.name() + ": " + e.getMessage());
            }
        }
    }

    /** A plain value from JSON, as its field holds it. Null for JSON null. */
    public static Object fromJson(Property property, Object json) {
        if (json == null) {
            return null;
        }
        return switch (property.kind()) {
            case BOOLEAN -> (Boolean) json;
            case INT -> ((Number) json).intValue();
            case LONG -> ((Number) json).longValue();
            case FLOAT -> ((Number) json).floatValue();
            case DOUBLE -> ((Number) json).doubleValue();
            case STRING -> (String) json;
            case ENUM -> enumConstant(property.type(), (String) json);
            case COLOR -> colorFromHex((String) json);
            case VECTOR2 -> {
                float[] v = floats(json, 2);
                yield new Vector2f(v[0], v[1]);
            }
            case VECTOR3 -> {
                float[] v = floats(json, 3);
                yield new Vector3f(v[0], v[1], v[2]);
            }
            case VECTOR4 -> {
                float[] v = floats(json, 4);
                yield new Vector4f(v[0], v[1], v[2], v[3]);
            }
            default -> throw new IllegalArgumentException(property.kind() + " is a reference");
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    // --- Little formats -----------------------------------------------------------------------------------------

    /** A colour as {@code #RRGGBBAA}. */
    public static String colorToHex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X%02X", channel(color.r()), channel(color.g()),
                channel(color.b()), channel(color.a()));
    }

    private static int channel(float value) {
        return Math.round(Math.clamp(value, 0f, 1f) * 255f);
    }

    /** A colour from {@code #RRGGBB} or {@code #RRGGBBAA}. */
    public static Color colorFromHex(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (digits.length() == 6) {
            return Color.rgb(Integer.parseInt(digits, 16));
        }
        if (digits.length() == 8) {
            long value = Long.parseLong(digits, 16);
            return new Color(((value >> 24) & 0xFF) / 255f, ((value >> 16) & 0xFF) / 255f, ((value >> 8) & 0xFF) / 255f,
                    (value & 0xFF) / 255f);
        }
        throw new IllegalArgumentException("not a colour: " + hex);
    }

    /** A vector as a JSON list. */
    public static List<Object> vector(Vector3f v) {
        return List.of(decimal(v.x), decimal(v.y), decimal(v.z));
    }

    /** A float as the shortest double that reads back as it: 0.1f saves as 0.1, not 0.10000000149011612. */
    public static double decimal(float value) {
        return Float.isFinite(value) ? Double.parseDouble(Float.toString(value)) : 0.0;
    }

    /** {@code count} numbers from a JSON list; missing ones are 0. */
    public static float[] floats(Object json, int count) {
        float[] out = new float[count];
        if (json instanceof List<?> list) {
            for (int i = 0; i < Math.min(count, list.size()); i++) {
                if (list.get(i) instanceof Number number) {
                    out[i] = number.floatValue();
                }
            }
        }
        return out;
    }

    /**
     * A field or constant name as words: {@code jumpHeight} as "Jump Height", {@code MAX_SPEED} as "Max Speed",
     * {@code Shape3D} as "Shape 3D".
     */
    public static String label(String name) {
        StringBuilder out = new StringBuilder();
        boolean upperRun = name.length() > 1 && name.equals(name.toUpperCase(Locale.ROOT))
                && name.chars().anyMatch(Character::isLetter);
        String source = upperRun ? name.toLowerCase(Locale.ROOT) : name;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '_') {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                continue;
            }
            boolean startsWord = out.isEmpty() || out.charAt(out.length() - 1) == ' ';
            if (!startsWord && i > 0) {
                char before = source.charAt(i - 1);
                boolean upperAfterLower = Character.isUpperCase(c) && Character.isLowerCase(before);
                boolean upperEndsRun = Character.isUpperCase(c) && Character.isUpperCase(before)
                        && i + 1 < source.length() && Character.isLowerCase(source.charAt(i + 1));
                boolean digitAfterLetter = Character.isDigit(c) && Character.isLetter(before);
                if (upperAfterLower || upperEndsRun || digitAfterLetter) {
                    out.append(' ');
                    startsWord = true;
                }
            }
            out.append(startsWord ? Character.toUpperCase(c) : c);
        }
        // "3d" read back out of a constant is "3D".
        return upperRun ? java.util.regex.Pattern.compile("(\\d)([a-z]+)").matcher(out)
                .replaceAll(m -> m.group(1) + m.group(2).toUpperCase(Locale.ROOT)) : out.toString();
    }

    /** How an enum constant reads in an editor: its own {@code toString} if it has one, else its name as words. */
    public static String enumLabel(Enum<?> constant) {
        String text = constant.toString();
        if (!text.equals(constant.name())) {
            return text;
        }
        // Many of the engine's enums carry their own name for menus, as a public label.
        try {
            Field field = constant.getDeclaringClass().getField("label");
            if (field.getType() == String.class && field.get(constant) instanceof String own && !own.isBlank()) {
                return own;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // no label of its own
        }
        return label(text);
    }
}
