package org.zeronura.engine.studio;

import org.zeronura.engine.graphics2d.Color;

/** The editor's colours and sizes: a dark theme in the manner of the usual engine editors. Sizes are in GUI units. */
final class Theme {

    private Theme() {
    }

    // --- Surfaces -----------------------------------------------------------------------------------------------
    static final Color WINDOW = Color.rgb(0x18191C);
    static final Color MENU_BAR = Color.rgb(0x222327);
    static final Color TOOLBAR = Color.rgb(0x26272B);
    static final Color PANEL = Color.rgb(0x2B2D31);
    static final Color PANEL_HEADER = Color.rgb(0x222327);
    static final Color PANEL_ALT = Color.rgb(0x2F3136);
    static final Color SECTION = Color.rgb(0x34363B);
    static final Color FIELD = Color.rgb(0x1E1F23);
    static final Color FIELD_BORDER = Color.rgb(0x43454B);
    static final Color BORDER = Color.rgb(0x141517);
    static final Color DIVIDER = Color.rgb(0x3A3C42);
    static final Color HOVER = Color.rgb(0x3A3C42);
    static final Color PRESSED = Color.rgb(0x494C53);
    static final Color POPUP = Color.rgb(0x2E3035);
    static final Color POPUP_BORDER = Color.rgb(0x4A4D54);
    static final Color VIEWPORT_2D = Color.rgb(0x2A2C30);

    // --- Accents ------------------------------------------------------------------------------------------------
    static final Color ACCENT = Color.rgb(0x4C8DF6);
    static final Color ACCENT_DIM = Color.rgb(0x2D4F86);
    static final Color SELECTION = Color.rgb(0x2F4A78);
    static final Color SELECTION_INACTIVE = Color.rgb(0x3B4250);
    static final Color HIGHLIGHT = Color.rgb(0xF5A524);
    static final Color PLAY = Color.rgb(0x52C46A);
    static final Color PLAYING_TINT = Color.rgb(0x3D5A44);
    static final Color WARNING = Color.rgb(0xE8B339);
    static final Color ERROR = Color.rgb(0xF2645A);
    static final Color SUCCESS = Color.rgb(0x6CCB7A);
    static final Color AXIS_X = Color.rgb(0xEC4D54);
    static final Color AXIS_Y = Color.rgb(0x7BCB4A);
    static final Color AXIS_Z = Color.rgb(0x3F8CF5);
    static final Color REFERENCE_WIRE = Color.rgb(0x6EA8FF);
    static final Color SIGNAL_WIRE = Color.rgb(0xF29B38);
    static final Color PARENT_WIRE = Color.rgb(0x7A7E87);

    // --- Text ---------------------------------------------------------------------------------------------------
    static final Color TEXT = Color.rgb(0xDDDFE4);
    static final Color TEXT_BRIGHT = Color.rgb(0xF4F5F7);
    static final Color TEXT_MUTED = Color.rgb(0x8E929A);
    static final Color TEXT_DISABLED = Color.rgb(0x5D6168);

    // --- Sizes --------------------------------------------------------------------------------------------------
    /** Body text: a capital is 7 times this tall. */
    static final float TEXT_SCALE = 1.62f;
    static final float SMALL_SCALE = 1.42f;
    static final float HEADING_SCALE = 1.72f;
    static final float CODE_SCALE = 1.6f;

    static final float MENU_BAR_HEIGHT = 28f;
    static final float TOOLBAR_HEIGHT = 40f;
    static final float TAB_HEIGHT = 30f;
    static final float STATUS_HEIGHT = 24f;
    static final float ROW = 24f;
    static final float FIELD_HEIGHT = 22f;
    static final float PAD = 8f;
    static final float RADIUS = 4f;
    static final float SPLITTER = 5f;
}
