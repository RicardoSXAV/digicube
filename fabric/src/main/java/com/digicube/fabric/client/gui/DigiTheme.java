package com.digicube.fabric.client.gui;

/**
 * The DigiCube GUI language, v1: colour tokens and the few numbers every screen shares.
 * Everything is ARGB with full alpha unless the name says otherwise; use
 * {@link #withAlpha} for translucent fills. The starter screen is the pilot; the Digivice
 * and the party HUD still use their own colours until this look is approved.
 */
public final class DigiTheme {
    private DigiTheme() {}

    // --- ground and panels ---------------------------------------------------------------
    /** Screen ground, drawn at {@link #GROUND_ALPHA} over the world. */
    public static final int VOID = 0xFF06090F;
    public static final int PANEL = 0xFF0B1526;
    /** Nameplates, button rest. */
    public static final int PANEL_RAISED = 0xFF122238;
    public static final int EDGE = 0xFF2E5A8F;
    /** Disabled frames, separators. */
    public static final int EDGE_DIM = 0xFF1B3556;

    // --- accents --------------------------------------------------------------------------
    /** Hover and focus frames, eyebrow text. */
    public static final int CYAN = 0xFF63E3FF;
    /** Data squares; drawn between {@link #DATA_ALPHA_MIN} and {@link #DATA_ALPHA_MAX}. */
    public static final int DATA = 0xFF2F7BFF;
    /** The one-in-twelve bright data square. */
    public static final int DATA_LIGHT = 0xFF8CC4FF;
    /** Grid lines; drawn between {@link #GRID_ALPHA_REST} and {@link #GRID_ALPHA_HOT}. */
    public static final int GRID = 0xFF21A653;
    /** Platform outline, confirmations. */
    public static final int GRID_BRIGHT = 0xFF46F07A;
    /** Selection, the primary button, warnings. The one warm colour. */
    public static final int AMBER = 0xFFFFBC69;

    // --- text and semantics ---------------------------------------------------------------
    public static final int WHITE = 0xFFF1F5EB;
    public static final int MUTED = 0xFF9DAFBE;
    /** Health and "in party". Semantic only. */
    public static final int TEAL = 0xFF74DFC4;
    public static final int RED = 0xFFEF7980;

    // --- knobs ----------------------------------------------------------------------------
    /** Light on purpose: the compact panel sits over a world the player should still see. */
    public static final int GROUND_ALPHA = 0x40;
    public static final int GRID_ALPHA_REST = 0x40;
    public static final int GRID_ALPHA_HOT = 0x66;
    public static final int DATA_ALPHA_MIN = 0x18;
    public static final int DATA_ALPHA_MAX = 0x66;
    public static final int CHAMFER_PANEL = 3;
    public static final int CHAMFER_BUTTON = 2;
    /** Grid pitch inside a model viewport, in GUI units. */
    public static final int GRID_CELL = 16;
    /** Grid pitch inside a small viewport, in GUI units. */
    public static final int GRID_CELL_SMALL = 12;
    /** Data square edge, in GUI units; cells sit on a pitch of {@link #DATA_PITCH}. */
    public static final int DATA_CELL = 8;
    public static final int DATA_PITCH = 10;
    /** Selection and hover transitions. */
    public static final int TRANSITION_TICKS = 6;
    /** Emphasis flashes, at most once per action. */
    public static final int FLASH_TICKS = 12;
    /** Breathing period of the data squares. */
    public static final int BREATH_TICKS = 60;
    /** One turntable revolution every 12 seconds. */
    public static final float TURNTABLE_DEGREES_PER_TICK = 1.5F;
    /** How much of a viewport the tallest or widest side of a preview fills. */
    public static final float PREVIEW_FILL = 0.62F;
    /** Where the feet of a preview stand, as a fraction of the viewport height. */
    public static final float PREVIEW_FOOT_LINE = 0.82F;

    /** @param alpha 0..255 */
    public static int withAlpha(int color, int alpha) {
        return (Math.clamp(alpha, 0, 255) << 24) | (color & 0xFFFFFF);
    }
}
