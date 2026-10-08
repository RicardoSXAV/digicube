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

    /** Keys that cast rider slot 0, 1, ... while the wheel is open: the same as a partner's attack slots on foot. */
    static final String[] KEYS = CommandWheelReadout.ATTACK_KEYS;
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
     * of the clock face that is still to come, and the seconds left stand on it. A stacked attack (Gold Rush) shows
     * its ready uses in the bottom right corner; while one is left the sweep of the next only shades the tile.
     */
    static void tile(GuiGraphicsExtractor g, Font font, DigimonEntity mount, DigimonAttack attack, int x, int y, int size, float partial, int alpha) {
        Identifier ready = attack.id().withPath(path -> "textures/gui/attack/" + path + ".png");
        if (Minecraft.getInstance().getResourceManager().getResource(ready).isEmpty()) {
            g.outline(x, y, size, size, DigiTheme.EDGE);
            return;
        }
        int color = DigiTheme.withAlpha(DigiTheme.WHITE, alpha);
        g.blit(RenderPipelines.GUI_TEXTURED, ready, x, y, 0, 0, size, size, TEXTURE, TEXTURE, TEXTURE, TEXTURE, color);
        // A cooldown drains as a clock; a stream's tile shows its tank the same way, and an emptied tank comes back
        // round the clock with the seconds it still needs.
        float left = attack.fuel() != null ? mount.riderRefillTicks(attack) : Math.max(0, mount.seenCooldown(attack) - partial);
        float done = mount.riderReadiness(attack, partial);
        // A hold is only lit while it has prey: a dull tile says a press would do nothing.
        var spec = mount.riderSpec(attack);
        if (done >= 1 && spec != null && spec.aim() == com.digicube.digimon.RiderAttack.Aim.GRAB && mount.grabPrey() == null) done = 0;
        Identifier off = attack.id().withPath(path -> "textures/gui/attack/" + path + "_off.png");
        // A rush fills its tile round the clock as it braces, and burns with a pulsing amber rim once it charges.
        float rush = spec != null && spec.aim() == com.digicube.digimon.RiderAttack.Aim.RUSH ? mount.rushBuild(partial) : -1;
        // A spin fills its tile round the clock as it spins up in the shell, and burns once it is spun up all the way.
        if (spec != null && spec.aim() == com.digicube.digimon.RiderAttack.Aim.SPIN) rush = mount.spinCharge();
        if (rush >= 0) {
            if (rush < 1) dullSweep(g, off, x, y, size, rush, color);
            else rushRim(g, x, y, size, mount.tickCount + partial, alpha);
            return;
        }
        // A gauge fills its tile round the clock as its hits land (riderReadiness) and burns once full; a drawn weapon's burns while it is out.
        if (burns(mount, attack)) {
            rushRim(g, x, y, size, mount.tickCount + partial, alpha);
            return;
        }
        boolean stacked = com.digicube.digimon.AttackCharges.of(attack) > 1;
        int uses = stacked ? mount.readyUses(attack) : 0;
        if (done >= 1) {
            if (stacked) uses(g, x, y, size, uses, alpha);
            return;
        }
        // With a use still ready the tile stays lit: the refill only shades it (55 % lit, 45 % dull, as approved in v3).
        int sweep = stacked && uses > 0 ? DigiTheme.withAlpha(DigiTheme.WHITE, alpha * 115 / 255) : color;
        dullSweep(g, off, x, y, size, done, sweep);
        if (stacked) uses(g, x, y, size, uses, alpha);
        if (left >= 20 && size >= HUD_TILE && uses == 0) {
            String seconds = Integer.toString((int) Math.ceil(left / 20));
            g.text(font, seconds, x + (size - font.width(seconds)) / 2 + 1, y + (size - 8) / 2 + 1, DigiTheme.withAlpha(DigiTheme.WHITE, alpha), true);
        }
    }

    /** Whether {@code attack} has tile art of its own; one without wears its kind's placeholder glyph in the wheel. */
    static boolean hasArt(DigimonAttack attack) {
        return Minecraft.getInstance().getResourceManager().getResource(attack.id().withPath(path -> "textures/gui/attack/" + path + ".png")).isPresent();
    }

    /**
     * An attack tile in the command wheel, {@code size} units square: its art, or until it has some its kind's
     * placeholder glyph on the data grid, dulled round the clock while it cools, with the seconds left on it.
     * {@code digimon} is the partner as this client sees it, or null while it is out of sight, when the tile shows
     * ready. A {@code ridden} mount's tile with art is the rider's own ({@link #tile}): a hold without prey is dull, a
     * charging rush burns.
     */
    static void wheelTile(GuiGraphicsExtractor g, Font font, DigimonEntity digimon, DigimonAttack attack, int x, int y, int size,
                          float partial, int alpha, boolean ridden) {
        boolean art = hasArt(attack);
        if (art && ridden && digimon != null) {
            tile(g, font, digimon, attack, x, y, size, partial, alpha);
            return;
        }
        float done = digimon == null ? 1 : digimon.riderReadiness(attack, partial);
        float left = digimon == null ? 0 : attack.fuel() != null ? digimon.riderRefillTicks(attack) : Math.max(0, digimon.seenCooldown(attack) - partial);
        boolean stacked = com.digicube.digimon.AttackCharges.of(attack) > 1;
        int uses = stacked && digimon != null ? digimon.readyUses(attack) : 0;
        int color = DigiTheme.withAlpha(DigiTheme.WHITE, alpha);
        if (art) {
            g.blit(RenderPipelines.GUI_TEXTURED, attack.id().withPath(path -> "textures/gui/attack/" + path + ".png"), x, y, 0, 0, size, size,
                    TEXTURE, TEXTURE, TEXTURE, TEXTURE, color);
            int sweep = stacked && uses > 0 ? DigiTheme.withAlpha(DigiTheme.WHITE, alpha * 115 / 255) : color;
            if (done < 1) dullSweep(g, attack.id().withPath(path -> "textures/gui/attack/" + path + "_off.png"), x, y, size, done, sweep);
        } else {
            DigiPanels.grid(g, x + 1, y + 1, size - 2, size - 2, 6, DigiTheme.withAlpha(DigiTheme.GRID, alpha * 0x30 / 0xFF));
            // a compound wears the glyph of its first form's kind
            var compound = com.digicube.digimon.CompoundAttacks.get(attack);
            String[] glyph = CommandIcons.glyph(compound != null ? compound.forms().getFirst().attack().kind() : attack.kind());
            int gx = x + (size - CommandIcons.GLYPH) / 2, gy = y + (size - CommandIcons.GLYPH) / 2, drop = DigiTheme.withAlpha(DigiTheme.VOID, alpha * 0xC0 / 0xFF);
            CommandIcons.draw(g, glyph, gx + 1, gy + 1, drop, drop, 0);
            CommandIcons.draw(g, glyph, gx, gy, color, DigiTheme.withAlpha(DigiTheme.CYAN, alpha), 0);
            if (done < 1) shadeSweep(g, x, y, size, done, DigiTheme.withAlpha(DigiTheme.VOID, alpha * 0xA8 / 0xFF));
        }
        // A full gauge and a drawn weapon burn with the rush's amber rim.
        if (digimon != null && burns(digimon, attack)) rushRim(g, x, y, size, digimon.tickCount + partial, alpha);
        if (stacked) uses(g, x, y, size, uses, alpha);
        if (done < 1 && left >= 20 && uses == 0) {
            String seconds = Integer.toString((int) Math.ceil(left / 20));
            g.text(font, seconds, x + (size - font.width(seconds)) / 2 + 1, y + (size - 8) / 2 + 1, color, true);
        }
    }

    /** A shade over the part of a {@code size}-unit clock face a sweep that has reached {@code done} (0 to 1, from twelve) has not. */
    private static void shadeSweep(GuiGraphicsExtractor g, int x, int y, int size, float done, int shade) {
        for (int row = 0; row < size; row++) {
            int start = -1;
            for (int column = 0; column <= size; column++) {
                boolean dull = column < size && angle(column, row, size) >= done;
                if (dull && start < 0) start = column;
                else if (!dull && start >= 0) {
                    g.fill(x + start, y + row, x + column, y + row + 1, shade);
                    start = -1;
                }
            }
        }
    }

    /** The dull twin over the part of the clock face a sweep that has reached {@code done} (0 to 1, from twelve) has not. */
    private static void dullSweep(GuiGraphicsExtractor g, Identifier off, int x, int y, int size, float done, int sweep) {
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
                    g.blit(RenderPipelines.GUI_TEXTURED, off, start, row, start, row, column - start, 1, column - start, 1, TEXTURE, TEXTURE, sweep);
                    start = -1;
                }
            }
        }
        g.pose().popMatrix();
    }

    /** A compound's tile burns with the rush's rim: its gauge full, or its weapon out. */
    static boolean burns(DigimonEntity digimon, DigimonAttack attack) {
        var compound = com.digicube.digimon.CompoundAttacks.get(attack);
        return compound != null && (compound.gauge() != null && digimon.gaugeFull(attack) || compound.stance() != null && attack.equals(digimon.stanceMove()));
    }

    private static final int RUSH_RIM = 0xFFFFA62E, RUSH_GLOW = 0xFFFFE6A0, RUSH_SHEEN = 0xFFFFB84D;

    /** A charging rush: an amber rim pulsing round the tile (over its frame) and a warm sheen over the art. */
    private static void rushRim(GuiGraphicsExtractor g, int x, int y, int size, float time, int alpha) {
        float pulse = .5F + .5F * (float) Math.sin(time * .9F);
        g.fill(x, y, x + size, y + size, DigiTheme.withAlpha(RUSH_SHEEN, (int) (alpha * .2F * pulse)));
        g.outline(x - 2, y - 2, size + 4, size + 4, DigiTheme.withAlpha(RUSH_RIM, (int) (alpha * (.6F + .4F * pulse))));
        g.outline(x - 1, y - 1, size + 2, size + 2, DigiTheme.withAlpha(RUSH_GLOW, (int) (alpha * .85F * pulse)));
    }

    /** 3 x 5 texel digits for the stack count. */
    private static final String[] DIGITS = {"111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
            "111100111001111", "111100111101111", "111001010010010", "111101111101111", "111101111001111"};
    private static final int PLATE = 0xFF1C100A, INK = 0xFFFFF6B0, INK_SPENT = 0xFF8C8C96;

    /**
     * The ready uses of a stacked attack in the tile's bottom right corner, in the tile's own texels: a dark plate
     * 5 x 7 against the frame (texels 26-30, 24-30) and the digit centred on it, grey at none.
     */
    private static void uses(GuiGraphicsExtractor g, int x, int y, int size, int uses, int alpha) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size / (float) TEXTURE, size / (float) TEXTURE);
        g.fill(26, 24, 31, 31, DigiTheme.withAlpha(PLATE, alpha));
        String rows = DIGITS[Math.clamp(uses, 0, 9)];
        int ink = DigiTheme.withAlpha(uses > 0 ? INK : INK_SPENT, alpha);
        for (int i = 0; i < 15; i++) if (rows.charAt(i) == '1') g.fill(27 + i % 3, 25 + i / 3, 28 + i % 3, 26 + i / 3, ink);
        g.pose().popMatrix();
    }

    /** Clock angle of a texel's centre, 0..1 clockwise from twelve: the same sweep as the approved preview. */
    private static float angle(int column, int row) {
        return angle(column, row, TEXTURE);
    }

    /** {@link #angle(int, int)} on a face {@code size} cells wide. */
    private static float angle(int column, int row, int size) {
        double a = Math.atan2(column + .5 - size / 2.0, size / 2.0 - (row + .5));
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
