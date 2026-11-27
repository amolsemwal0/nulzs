package org.zeronura.nulzs.terrain;

import org.zeronura.nulzs.render.StaticInstance;

import java.util.List;

/**
 * Places props — trees, rocks, ruins — on a terrain chunk as it is generated. Placements stream in and out
 * with their chunk, so an endless world can be densely decorated without holding the whole thing.
 *
 * <p><b>Runs on a worker thread.</b> Compute transforms only, using meshes created beforehand on the main
 * thread; never call OpenGL here. Seed any randomness from the chunk coordinates so the same chunk always
 * gets the same props.
 */
@FunctionalInterface
public interface ChunkDecorator {

    void decorate(Terrain.ChunkInfo chunk, Terrain terrain, List<StaticInstance> out);
}
