package org.zeronura.nulzs.scene;

import java.io.IOException;

/**
 * A component whose state lives in a file of its own rather than in the scene file — a map, say, too big to copy into
 * every scene that shows it. The scene keeps only which file it is. Saving the scene in an editor saves these files
 * too, first, so a scene never names a change its file doesn't have.
 */
public interface SavesOwnFiles {

    /** Writes whatever changed since the file was read or last written; does nothing if nothing did. */
    void saveOwnFiles() throws IOException;
}
