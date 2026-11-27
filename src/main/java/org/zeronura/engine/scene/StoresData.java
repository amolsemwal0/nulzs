package org.zeronura.engine.scene;

/**
 * A component with state beyond its public fields — the points of a spline, say — that it writes into the scene file
 * itself, beside its fields. What it saves must be JSON values: maps, lists, strings, numbers, booleans and null.
 */
public interface StoresData {

    /** The extra state, as JSON values. */
    Object saveData();

    /** Takes back what {@link #saveData} gave, as read from a scene file. */
    void loadData(Object data);
}
