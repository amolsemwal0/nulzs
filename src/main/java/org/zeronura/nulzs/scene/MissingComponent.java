package org.zeronura.nulzs.scene;

import java.util.Map;

/**
 * Stands in, in a loaded scene, for a component whose class can't be found — a script renamed, deleted, or not compiled
 * yet. It does nothing, but keeps what the file said, so saving the scene again writes it back unchanged and the
 * component returns once its class does. An editor shows it as a missing script.
 */
public final class MissingComponent extends Component {

    private final String type;
    private final Map<String, Object> saved;

    MissingComponent(String type, Map<String, Object> saved) {
        this.type = type;
        this.saved = Map.copyOf(saved);
    }

    /** The class name the scene file gave. */
    public String type() {
        return type;
    }

    /** The component as the file had it. */
    public Map<String, Object> saved() {
        return saved;
    }
}
