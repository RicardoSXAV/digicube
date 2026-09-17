package com.digicube.fabric.client.dev;

import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The way into the developer panel: a gear in the bottom right corner of the command wheel,
 * in development environments only. Resting the cursor on it for {@link #DWELL_TICKS} opens
 * the panel; while the cursor is on it the wheel selects no order.
 */
public final class DevGear {
    public static final int SIZE = 24, MARGIN = 8, DWELL_TICKS = 5;
    private static final int SLACK = 2, ICON = 16;
    private static final String LABEL = "DEV";
    /** 16 x 16: '#' the toothed ring, '+' the hub ring. Eight teeth, drawn from the polar form once. */
    private static final String[] GEAR = gear();

    private DevGear() {}

    /** Whether the wheel shows the gear at all. */
    public static boolean available() { return DevClient.get() != null; }

    public static boolean over(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        int x = screenWidth - MARGIN - SIZE - SLACK, y = screenHeight - MARGIN - SIZE - SLACK;
        return available() && mouseX >= x && mouseX < x + SIZE + 2 * SLACK && mouseY >= y && mouseY < y + SIZE + 2 * SLACK;
    }

    public static void open() {
        DevClient client = DevClient.get();
        if (client != null) Minecraft.getInstance().gui.setScreen(new DevPanelScreen(client));
    }

    /** @param dwell ticks the cursor has rested on the gear, with the partial tick */
    public static void draw(GuiGraphicsExtractor g, Font font, int screenWidth, int screenHeight, boolean over, float dwell, float fade) {
        int x = screenWidth - MARGIN - SIZE, y = screenHeight - MARGIN - SIZE;
        DigiPanels.frame(g, x - 1, y - 1, SIZE + 2, SIZE + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 3);
        DigiPanels.frame(g, x, y, SIZE, SIZE, tint(DigiTheme.PANEL, 0xE6, fade), 0, 2);
        g.fill(x + 1, y + 2, x + SIZE - 1, y + 7, tint(DigiTheme.PANEL_RAISED, 0x90, fade));
        if (over) {
            int fill = Math.round((SIZE - 2) * Math.min(1.0F, dwell / DWELL_TICKS));
            g.fill(x + 1, y + SIZE - 1 - fill, x + SIZE - 1, y + SIZE - 1, tint(DigiTheme.AMBER, 0x50, fade));
        }
        DigiPanels.bevel(g, x, y, SIZE, SIZE, 2, tint(over ? DigiTheme.AMBER : DigiTheme.EDGE_LIGHT, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        int ix = x + (SIZE - ICON) / 2, iy = y + (SIZE - ICON) / 2, drop = tint(DigiTheme.VOID, 0xC0, fade);
        bitmap(g, ix + 1, iy + 1, drop, drop);
        bitmap(g, ix, iy, tint(over ? DigiTheme.AMBER : DigiTheme.WHITE, 0xFF, fade), tint(DigiTheme.CYAN, 0xFF, fade));
        if (over) DigiPanels.brackets(g, x, y, SIZE, SIZE, 6, 2, tint(DigiTheme.AMBER, 0xFF, fade));
        g.text(font, LABEL, x + (SIZE - font.width(LABEL)) / 2, y - 11, tint(over ? DigiTheme.AMBER : DigiTheme.CYAN, 0xE0, fade), true);
    }

    static void bitmap(GuiGraphicsExtractor g, int x, int y, int body, int accent) {
        for (int row = 0; row < GEAR.length; row++) {
            for (int col = 0; col < ICON; col++) {
                char pixel = GEAR[row].charAt(col);
                if (pixel != '.') g.fill(x + col, y + row, x + col + 1, y + row + 1, pixel == '#' ? body : accent);
            }
        }
    }

    private static String[] gear() {
        String[] rows = new String[ICON];
        for (int y = 0; y < ICON; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < ICON; x++) {
                double dx = x - 7.5, dy = y - 7.5, radius = Math.hypot(dx, dy);
                double outer = Math.cos(Math.atan2(dy, dx) * 8) > 0.1 ? 7.9 : 5.9;
                row.append(radius > outer || radius < 2.4 ? '.' : radius < 3.7 ? '+' : '#');
            }
            rows[y] = row.toString();
        }
        return rows;
    }

    private static int tint(int color, int alpha, float fade) {
        return DigiTheme.withAlpha(color, Math.round(alpha * fade));
    }
}
