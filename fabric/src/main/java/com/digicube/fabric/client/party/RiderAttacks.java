package com.digicube.fabric.client.party;

import com.digicube.digimon.DigimonAttack;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Mounted combat on the client: the attack tiles of the Digimon the player rides. On the HUD
 * they take the place of vanilla's mount hearts (the party strip already shows its health),
 * right-aligned above the hotbar behind a middle-mouse glyph that says how to reach them; the
 * command wheel shows the same tiles with the key that casts each. A tile cooling down is
 * dull, and its colour comes back clockwise. Cooldowns are counted on the client from the
 * attack starts it has seen, so they tick every frame.
 */
public final class RiderAttacks {
    private RiderAttacks() {}

    /** Keys that cast rider slot 0, 1, ... while the wheel is open. */
    static final String[] KEYS = {"Q", "E"};
    static final int TEXTURE = 32;
    private static final int HUD_TILE = 16, HUD_GAP = 2;
    private static final String[] MOUSE = {
            "...#####...", "..#..+..#..", ".#..+++..#.", ".#..+++..#.", ".#..+++..#.", ".#...+...#.", ".#########.",
            ".#.......#.", ".#.......#.", ".#.......#.", ".#.......#.", "..#.....#..", "...#####..."};

    /** The Digimon whose attacks the local player casts, or null. */
    static DigimonEntity mount(Minecraft minecraft) {
        return minecraft.player != null && minecraft.player.getVehicle() instanceof DigimonEntity digimon
                && digimon.getControllingPassenger() == minecraft.player && !digimon.riderAttacks().isEmpty() ? digimon : null;
    }

    /** Replaces vanilla's mount hearts: nothing for any Digimon mount, the attack tiles for one that fights under its rider. */
    public static void hud(GuiGraphicsExtractor g, DeltaTracker delta, Runnable vanilla) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.getVehicle() instanceof DigimonEntity)) {
            vanilla.run();
            return;
        }
        DigimonEntity mount = mount(minecraft);
        if (mount == null || minecraft.gui.hud.isHidden()) return;
        List<DigimonAttack> attacks = mount.riderAttacks();
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int right = g.guiWidth() / 2 + 91, y = g.guiHeight() - 31 - HUD_TILE;
        int x = right - attacks.size() * HUD_TILE - (attacks.size() - 1) * HUD_GAP;
        int mx = x - MOUSE[0].length() - 3, my = y + (HUD_TILE - MOUSE.length) / 2;
        CommandIcons.draw(g, MOUSE, mx + 1, my + 1, DigiTheme.withAlpha(DigiTheme.VOID, 0xC0), DigiTheme.withAlpha(DigiTheme.VOID, 0xC0), 0);
        CommandIcons.draw(g, MOUSE, mx, my, DigiTheme.WHITE, DigiTheme.AMBER, 0);
        for (DigimonAttack attack : attacks) {
            tile(g, minecraft.font, mount, attack, x, y, HUD_TILE, partial, 0xFF);
            x += HUD_TILE + HUD_GAP;
        }
    }

    /**
     * One attack tile, {@code size} GUI units square. While the attack cools down the dull twin covers the part
     * of the clock face that is still to come, and the seconds left stand on it.
     */
    static void tile(GuiGraphicsExtractor g, Font font, DigimonEntity mount, DigimonAttack attack, int x, int y, int size, float partial, int alpha) {
        Identifier ready = attack.id().withPath(path -> "textures/gui/attack/" + path + ".png");
        if (Minecraft.getInstance().getResourceManager().getResource(ready).isEmpty()) {
            g.outline(x, y, size, size, DigiTheme.EDGE);
            return;
        }
        int color = DigiTheme.withAlpha(DigiTheme.WHITE, alpha);
        g.blit(RenderPipelines.GUI_TEXTURED, ready, x, y, 0, 0, size, size, TEXTURE, TEXTURE, TEXTURE, TEXTURE, color);
        float left = Math.max(0, mount.seenCooldown(attack) - partial);
        if (left <= 0 || attack.cooldownTicks() <= 0) return;
        float done = 1 - Math.min(1, left / attack.cooldownTicks());
        Identifier off = attack.id().withPath(path -> "textures/gui/attack/" + path + "_off.png");
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size / (float) TEXTURE, size / (float) TEXTURE);
        // texel rows of the dull twin, each cut to the part of the clock face the sweep has not reached
        for (int row = 1; row < TEXTURE - 1; row++) {
            int start = -1;
            for (int column = 1; column <= TEXTURE - 1; column++) {
                boolean dull = column < TEXTURE - 1 && angle(column, row) >= done;
                if (dull && start < 0) start = column;
                else if (!dull && start >= 0) {
                    g.blit(RenderPipelines.GUI_TEXTURED, off, start, row, start, row, column - start, 1, column - start, 1, TEXTURE, TEXTURE, color);
                    start = -1;
                }
            }
        }
        g.pose().popMatrix();
        if (left >= 20 && size >= HUD_TILE) {
            String seconds = Integer.toString((int) Math.ceil(left / 20));
            g.text(font, seconds, x + (size - font.width(seconds)) / 2 + 1, y + (size - 8) / 2 + 1, DigiTheme.withAlpha(DigiTheme.WHITE, alpha), true);
        }
    }

    /** Clock angle of a texel's centre, 0..1 clockwise from twelve: the same sweep as the approved preview. */
    private static float angle(int column, int row) {
        double a = Math.atan2(column + .5 - TEXTURE / 2.0, TEXTURE / 2.0 - (row + .5));
        return (float) ((a + Math.PI * 2) % (Math.PI * 2) / (Math.PI * 2));
    }

    /** A key cap in the wheel's style, as beside its next-partner arrow. */
    static void keyCap(GuiGraphicsExtractor g, Font font, String key, int x, int y, int alpha) {
        int w = font.width(key) + 6;
        g.fill(x - 1, y - 1, x + w + 1, y + 11, DigiTheme.withAlpha(DigiTheme.SHADOW, alpha * 0xE0 / 0xFF));
        g.fill(x, y, x + w, y + 10, DigiTheme.withAlpha(DigiTheme.PANEL_RAISED, alpha * 0xF0 / 0xFF));
        DigiPanels.bevel(g, x, y, w, 10, 1, DigiTheme.withAlpha(DigiTheme.EDGE_LIGHT, alpha), DigiTheme.withAlpha(DigiTheme.SHADOW, alpha));
        g.text(font, key, x + 3, y + 1, DigiTheme.withAlpha(DigiTheme.WHITE, alpha), false);
    }
}
