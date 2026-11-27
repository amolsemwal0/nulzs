package org.zeronura.nulzs.scene;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Something a component announces — a button pressed, a trigger entered, an enemy defeated — for other entities to
 * answer, without the component knowing who they are.
 *
 * <pre>{@code
 * public class PressurePlate extends Component {
 *     public final Signal pressed = new Signal();
 *     ...
 *     pressed.emit();
 * }
 * }</pre>
 *
 * <p>Code answers with {@link #connect(Runnable)}. The editor answers too: in the Inspector or the Connections view a
 * signal is wired to a public method taking no arguments on a component of another entity — a door's {@code open()},
 * say. Those wires are saved with the scene, drawn in the Connections view, and called by {@link #emit} in the order
 * they were made, after the code listeners.
 */
public final class Signal {

    /**
     * A wire from a signal to a method: the entity, the component on it by class name (full, or simple if unique), and
     * a public method with no parameters.
     */
    public record Connection(Entity target, String component, String method) {
    }

    private final List<Runnable> listeners = new ArrayList<>();
    private final List<Connection> connections = new ArrayList<>();

    /** Calls {@code listener} on every {@link #emit}. */
    public void connect(Runnable listener) {
        listeners.add(listener);
    }

    public void disconnect(Runnable listener) {
        listeners.remove(listener);
    }

    /** Wires this signal to {@code method} of the component named {@code component} on {@code target}. */
    public Connection connect(Entity target, String component, String method) {
        Connection connection = new Connection(target, SceneFile.currentClassName(component), method);
        connections.add(connection);
        return connection;
    }

    public void disconnect(Connection connection) {
        connections.remove(connection);
    }

    /** The wires to other entities' methods, in the order they are called. */
    public List<Connection> connections() {
        return Collections.unmodifiableList(connections);
    }

    public void clearConnections() {
        connections.clear();
    }

    /** Replaces one wire with another in the same place, for an editor changing where a wire goes. */
    public void replace(Connection old, Connection replacement) {
        int index = connections.indexOf(old);
        if (index >= 0) {
            connections.set(index, replacement);
        }
    }

    /**
     * Tells everyone: the code listeners, then each wired method. A wire whose entity is gone, or whose component or
     * method can't be found, is skipped with a line in the log, so one broken wire doesn't stop the rest.
     */
    public void emit() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
        for (Connection connection : List.copyOf(connections)) {
            call(connection);
        }
    }

    private static void call(Connection connection) {
        Entity target = connection.target();
        if (target == null || target.isDestroyed()) {
            return;
        }
        Component component = find(target, connection.component());
        if (component == null) {
            System.err.println("[Signal] " + target.name() + " has no " + connection.component() + " for " + connection.method() + "()");
            return;
        }
        Method method = method(component.getClass(), connection.method());
        if (method == null) {
            System.err.println("[Signal] " + component.getClass().getSimpleName() + " has no public " + connection.method() + "()");
            return;
        }
        try {
            method.invoke(component);
        } catch (IllegalAccessException e) {
            System.err.println("[Signal] Could not call " + connection.method() + "(): " + e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** The component on {@code entity} whose class is named {@code type}, fully or simply. */
    public static Component find(Entity entity, String type) {
        String name = SceneFile.currentClassName(type);
        for (Component component : entity.components()) {
            Class<?> c = component.getClass();
            if (c.getName().equals(name) || c.getSimpleName().equals(name)) {
                return component;
            }
        }
        return null;
    }

    /** The public, parameterless method {@code name} a wire can call on {@code type}, or null. */
    public static Method method(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0
                    && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    /**
     * The methods of {@code type} a wire can call: public, taking nothing, declared by the component's own classes
     * rather than {@link Component} or {@link Object}, and not getters.
     */
    public static List<String> callableMethods(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Method method : type.getMethods()) {
            Class<?> owner = method.getDeclaringClass();
            if (owner == Object.class || owner == Component.class || method.getParameterCount() != 0
                    || Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            String name = method.getName();
            boolean getter = method.getReturnType() != void.class
                    && (name.startsWith("get") || name.startsWith("is") || name.startsWith("has"));
            if (!getter && !names.contains(name)) {
                names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }
}
