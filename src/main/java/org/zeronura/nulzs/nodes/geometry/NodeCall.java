package org.zeronura.nulzs.nodes.geometry;

import org.zeronura.nulzs.nodes.Node;
import org.zeronura.nulzs.nodes.NodeValues;
import org.zeronura.nulzs.nodes.Ref;
import org.zeronura.nulzs.nodes.Socket;
import org.zeronura.nulzs.nodes.SocketType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One execution of one node: its inputs, pulled on demand (so a Switch only evaluates the branch it takes),
 * and the outputs it sets. Inputs are named by socket identifier, falling back to the socket's name.
 */
public final class NodeCall {

    public final Node node;
    final GeometryEvaluator.TreeRun run;
    final GeometryEvaluator.Scope scope;
    final Map<String, Object> outputs = new HashMap<>();

    NodeCall(Node node, GeometryEvaluator.TreeRun run, GeometryEvaluator.Scope scope) {
        this.node = node;
        this.run = run;
        this.scope = scope;
    }

    public EvalEnv env() {
        return run.env;
    }

    public NodeResolver resolver() {
        return run.evaluator().resolver;
    }

    public GeometryEvaluator evaluator() {
        return run.evaluator();
    }

    // --- Sockets -------------------------------------------------------------------------------------------

    public Socket inputSocket(String idOrName) {
        Socket socket = node.input(idOrName);
        if (socket == null) {
            socket = node.inputNamed(idOrName);
        }
        return socket;
    }

    public Socket outputSocket(String idOrName) {
        Socket socket = node.output(idOrName);
        if (socket == null) {
            socket = node.outputNamed(idOrName);
        }
        return socket;
    }

    public boolean has(String idOrName) {
        return inputSocket(idOrName) != null;
    }

    public boolean linked(String idOrName) {
        Socket socket = inputSocket(idOrName);
        return socket != null && socket.isLinked();
    }

    /** The input's value as evaluated: a Field for field types, a GeometrySet, a String, a Ref... */
    public Object raw(String idOrName) {
        Socket socket = inputSocket(idOrName);
        if (socket == null) {
            return null;
        }
        return run.inputValue(socket, scope);
    }

    /** The input as a field of its own type; an unconnected hidden input gives its implicit field. */
    public Field field(String idOrName) {
        Socket socket = inputSocket(idOrName);
        if (socket == null) {
            return Field.constant(DataType.FLOAT, 0f);
        }
        Object value = run.inputValue(socket, scope);
        if (value instanceof Field f) {
            return f;
        }
        DataType type = DataType.of(socket.type);
        return Field.constant(type == null ? DataType.FLOAT : type, value);
    }

    public Field field(String idOrName, DataType type) {
        return field(idOrName).as(type);
    }

    public float f(String idOrName) {
        return field(idOrName).singleFloat();
    }

    public int i(String idOrName) {
        return field(idOrName).singleInt();
    }

    public boolean b(String idOrName) {
        return field(idOrName).singleBool();
    }

    public float[] v(String idOrName) {
        return field(idOrName, DataType.VECTOR).singleVector();
    }

    public GeometrySet geometry(String idOrName) {
        Object value = raw(idOrName);
        return value instanceof GeometrySet g ? g : GeometrySet.EMPTY;
    }

    /** Every geometry linked into a multi-input socket, top to bottom. */
    public List<GeometrySet> geometries(String idOrName) {
        Socket socket = inputSocket(idOrName);
        List<GeometrySet> out = new ArrayList<>();
        if (socket == null) {
            return out;
        }
        for (Object value : run.inputValues(socket, scope)) {
            if (value instanceof GeometrySet g) {
                out.add(g);
            }
        }
        return out;
    }

    public String string(String idOrName) {
        Object value = raw(idOrName);
        return value == null ? "" : NodeValues.toText(value, "");
    }

    public Ref ref(String idOrName) {
        Object value = raw(idOrName);
        return value instanceof Ref r ? r : null;
    }

    public SocketType inputType(String idOrName) {
        Socket socket = inputSocket(idOrName);
        return socket == null ? SocketType.CUSTOM : socket.type;
    }

    // --- Settings ------------------------------------------------------------------------------------------

    public String prop(String key, String fallback) {
        return node.prop(key, fallback);
    }

    public int propInt(String key, int fallback) {
        return node.propInt(key, fallback);
    }

    public float propFloat(String key, float fallback) {
        return node.propFloat(key, fallback);
    }

    public boolean propBool(String key, boolean fallback) {
        return node.propBool(key, fallback);
    }

    public Domain domain(Domain fallback) {
        return Domain.parse(node.prop("domain", null), fallback);
    }

    // --- Outputs -------------------------------------------------------------------------------------------

    public void out(String idOrName, Object value) {
        Socket socket = outputSocket(idOrName);
        outputs.put(socket != null ? socket.id : idOrName, value);
    }

    /** Sets every output not yet set to its type's empty value, so a node can set only the ones it computes. */
    void fillDefaults() {
        for (Socket socket : node.outputs()) {
            if (!outputs.containsKey(socket.id)) {
                outputs.put(socket.id, GeometryEvaluator.defaultValue(socket.type, null));
            }
        }
    }

    // --- Field evaluation helpers --------------------------------------------------------------------------

    public FieldContext context(GeometryComponent component, Domain domain) {
        return new FieldContext(component, domain, run.env);
    }

    /** A selection field evaluated on a component's domain, as booleans. */
    public boolean[] selection(Field selection, GeometryComponent component, Domain domain) {
        int n = component.count(domain);
        boolean[] out = new boolean[n];
        if (selection.isConstant()) {
            java.util.Arrays.fill(out, selection.singleBool());
            return out;
        }
        Column c = context(component, domain).evalAs(selection, DataType.BOOL);
        for (int k = 0; k < n; k++) {
            out[k] = c.i[k] != 0;
        }
        return out;
    }

    public UnsupportedNodeException unsupported(String detail) {
        return new UnsupportedNodeException(node, detail);
    }
}
