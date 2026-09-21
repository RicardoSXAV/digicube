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
 * they take the place of vanilla's mount hearts (the party strip already shows its health):
 * framed, right-aligned above the hotbar, behind small mouse glyphs that say how to cast them; the
 * command wheel shows the same tiles with the key that casts each. A tile cooling down is
 * dull, and its colour comes back clockwise. Cooldowns are counted on the client from the
 * attack starts it has seen, so they tick every frame.
 */
public final class RiderAttacks {
    private RiderAttacks() {}

    /** Keys that cast rider slot 0, 1, ... while the wheel is open. */
    static final String[] KEYS = {"Q", "E"};
    static final int TEXTURE = 32;
    /** A HUD tile sits in a frame {@code HUD_RIM} units thick; frames stand {@code HUD_GAP} apart. */
    private static final int HUD_TILE = 16, HUD_RIM = 2, HUD_FRAME = HUD_TILE + 2 * HUD_RIM, HUD_GAP = 1, GLYPH_GAP = 2;
    /** Mouse glyphs: the wheel lit (open the command wheel), the left button lit, the right button lit. */
    private static final String[] MOUSE = {
            ".#####.", "#..+..#", "#..+..#", "#..+..#", "#######", "#.....#", "#.....#", "#.....#", ".#####."};
    private static final String[] MOUSE_LEFT = {
            ".#####.", "#++#..#", "#++#..#", "#++#..#", "#######", "#.....#", "#.....#", "#.....#", ".#####."};
    private static final String[] MOUSE_RIGHT = {
            ".#####.", "#..#++#", "#..#++#", "#..#++#", "#######", "#.....#", "#.....#", "#.....#", ".#####."};

    /** The Digimon whose attacks the local player casts, or null. */
    static DigimonEntity mount(Minecraft minecraft) {
        return minecraft.player != null && minecraft.player.getVehicle() instanceof DigimonEntity digimon
                // rider(), not the controlling passenger: a wrap takes the reins for its six seconds, the saddle stays the rider's
                && digimon.rider() == minecraft.player && !digimon.riderAttacks().isEmpty() ? digimon : null;
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
        // Framed tiles end where the hotbar ends, one unit above the experience bar; the glyphs stand together to
        // their left, in tile order. They follow the hand: free, the mouse buttons that cast; holding something, the wheel.
        int right = g.guiWidth() / 2 + 91, y = g.guiHeight() - 30 - HUD_FRAME, glyph = MOUSE[0].length();
        boolean buttons = RiderControls.handsFree(minecraft.player) && attacks.size() <= 2;
        int x = right - attacks.size() * HUD_FRAME - (attacks.size() - 1) * HUD_GAP;
        int glyphs = buttons ? attacks.size() : 1, glyphX = x - glyphs * (glyph + GLYPH_GAP) - 1;
        for (int slot = 0; slot < glyphs; slot++)
            mouse(g, !buttons ? MOUSE : slot == 0 ? MOUSE_LEFT : MOUSE_RIGHT, glyphX + slot * (glyph + GLYPH_GAP), y);
        for (DigimonAttack attack : attacks) {
            frame(g, x, y);
            tile(g, minecraft.font, mount, attack, x + HUD_RIM, y + HUD_RIM, HUD_TILE, partial, 0xFF);
            x += HUD_FRAME + HUD_GAP;
        }
    }

    /** A slot in the panel style: dark outline, then a blue rim lit from the top left, around a dark well. */
    private static void frame(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + HUD_FRAME, y + HUD_FRAME, DigiTheme.VOID);
        DigiPanels.bevel(g, x + 1, y + 1, HUD_FRAME - 2, HUD_FRAME - 2, 0, DigiTheme.EDGE_LIGHT, DigiTheme.EDGE_DIM);
    }

    private static void mouse(GuiGraphicsExtractor g, String[] rows, int x, int frameY) {
        int y = frameY + (HUD_FRAME - rows.length + 1) / 2, shade = DigiTheme.withAlpha(DigiTheme.VOID, 0xC0);
        CommandIcons.draw(g, rows, x + 1, y + 1, shade, shade, 0);
        CommandIcons.draw(g, rows, x, y, DigiTheme.WHITE, DigiTheme.AMBER, 0);
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
        // A cooldown drains as a clock; a stream's tile shows its tank the same way.
        float left = attack.fuel() != null ? 0 : Math.max(0, mount.seenCooldown(attack) - partial);
        float done = mount.riderReadiness(attack, partial);
        // A hold is only lit while it has prey: a dull tile says a press would do nothing.
        var spec = mount.riderSpec(attack);
        if (done >= 1 && spec != null && spec.aim() == com.digicube.digimon.RiderAttack.Aim.GRAB && mount.grabPrey() == null) done = 0;
        if (done >= 1) return;
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
