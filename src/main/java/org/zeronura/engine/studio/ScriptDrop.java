package org.zeronura.engine.studio;

import org.zeronura.engine.scene.Entity;
import org.zeronura.engine.ui.Rect;

/** A script dragged from the Project panel and dropped on an entity's Inspector becomes one of its components. */
final class ScriptDrop {

    private ScriptDrop() {
    }

    static void accept(Ui ui, StudioScene studio, Entity entity, Rect rect) {
        if (ui.dragOver(rect, AssetBrowser.AssetDrag.class)
                && ui.dragPayload() instanceof AssetBrowser.AssetDrag drag && AssetKind.of(drag.path()) == AssetKind.SCRIPT) {
            ui.gui.roundedOutline(rect, Theme.RADIUS, 2f, Theme.ACCENT);
        }
        AssetBrowser.AssetDrag dropped = ui.dropped(rect, AssetBrowser.AssetDrag.class);
        if (dropped != null && AssetKind.of(dropped.path()) == AssetKind.SCRIPT) {
            studio.addScript(entity, dropped.path());
        }
    }
}
