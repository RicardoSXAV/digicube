package com.digicube.fabric.client.party;

import com.digicube.digimon.DigimonAttribute;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.EvolutionRules;
import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.fabric.client.party.PartyHudReadout.Status;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartyRoster;
import com.digicube.party.PartySnapshotPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The party strip: a header tab and one bevelled card per party slot, stacked in the
 * top-left corner of the screen at a little under full GUI size. Each card is the
 * Digivice's reading of one partner: sprite on the data grid, level plate, name band, HP,
 * status code, XP rail and the DigiSoul cell. Everything is {@code fill} calls in GUI units
 * plus the species sprite, drawn inside one scaled pose. Design: {@code design/party-hud-strip.md} section 12.
 *
 * <p>The strip keeps a little client memory per partner (last health for the damage
 * ghost, last level for the level-up flash), advanced from the client tick, never from
 * the frame.
 */
final class PartyHud {
    private static final int W = PartyHudReadout.CARD_WIDTH;
    private static final int H = PartyHudReadout.CARD_HEIGHT;
    private static final int STUB = PartyHudReadout.STUB_HEIGHT;
    private static final int GAP = PartyHudReadout.GAP;
    private static final int HEADER = PartyHudReadout.HEADER_HEIGHT;
    /** Distance from the top and left edges of the screen, in unscaled GUI units. */
    private static final int MARGIN = 4;
    /** The strip stays visible but steps back while the chat is open. */
    private static final float CHAT_FADE = 0.45F;
    private static final int GHOST_HOLD_TICKS = 10;
    private static final float GHOST_DRAIN_PER_TICK = 0.02F;
    private static final int SCAN_PERIOD_TICKS = 160;
    private static final int SCAN_SWEEP_TICKS = 64;
    private static final int LOW_HEALTH_BREATH_TICKS = 40;
    private static final float LOW_HEALTH = 0.25F;
    private static final float HALF_HEALTH = 0.5F;
    private static final int TEXT_X = 40;
    private static final int TEXT_W = 60;
    private static final int NAME_W = 46;
    private static final int SPRITE = 24;

    private static final class Memory {
        float health = Float.NaN;
        float ghost;
        int hold;
        int level = -1;
        int flash;
    }

    private final Map<UUID, Memory> memories = new HashMap<>();
    private int tick;

    /** Once per client tick: remembers health and level per partner and runs the ghost and flash timers. */
    void tick(PartySnapshotPayload snapshot) {
        tick++;
        Set<UUID> present = new HashSet<>();
        for (PartyMemberView member : snapshot.party()) {
            present.add(member.id());
            Memory memory = memories.computeIfAbsent(member.id(), id -> new Memory());
            float health = PartyHudReadout.healthFraction(member);
            if (!Float.isNaN(memory.health) && health < memory.health) {
                memory.ghost = Math.max(memory.ghost, memory.health);
                memory.hold = GHOST_HOLD_TICKS;
            }
            memory.health = health;
            if (memory.hold > 0) memory.hold--;
            else memory.ghost = Math.max(health, memory.ghost - GHOST_DRAIN_PER_TICK);
            if (memory.level >= 0 && member.level() > memory.level) memory.flash = DigiTheme.FLASH_TICKS;
            memory.level = member.level();
            if (memory.flash > 0) memory.flash--;
        }
        memories.keySet().retainAll(present);
    }

    /**
     * @param age       client ticks since {@code snapshot} arrived, for the local countdowns
     * @param selected  the party slot the arrow keys have selected; its card wears the amber corner brackets
     * @param evolveKey the Digivolve key, for the READY hint on the selected card
     */
    void draw(GuiGraphicsExtractor graphics, DeltaTracker delta, PartySnapshotPayload snapshot, int age, int selected, KeyMapping evolveKey) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.isSpectator() || client.gui.hud.isHidden() || snapshot.total() == 0
                // the dev panel is translucent and centred: the strip would show through it
                || client.gui.screen() instanceof com.digicube.fabric.client.dev.DevPanelScreen) return;
        Font font = client.font;
        float time = tick + delta.getGameTimeDeltaPartialTick(true);
        float fade = client.gui.screen() instanceof ChatScreen ? CHAT_FADE : 1.0F;
        PartyMemberView[] slots = new PartyMemberView[PartyRoster.PARTY_SIZE];
        int filled = 0;
        for (PartyMemberView member : snapshot.party()) {
            if (member.slot() >= 0 && member.slot() < slots.length && slots[member.slot()] == null) {
                slots[member.slot()] = member;
                filled++;
            }
        }
        Map<UUID, Float> flightFuel = new HashMap<>();
        if (client.level != null) {
            for (var entity : client.level.entitiesForRendering()) {
                if (entity instanceof DigimonEntity digimon && digimon.canFly()) flightFuel.put(entity.getUUID(), digimon.getFlightFuel());
            }
        }
        // Top-left corner, a touch under full size. Everything below is drawn from the origin inside this transform.
        float scale = PartyHudReadout.stripScale(client.getWindow().getGuiScale());
        graphics.pose().pushMatrix();
        graphics.pose().translate(MARGIN, MARGIN);
        graphics.pose().scale(scale, scale);
        int x = 0;
        int y = 0;
        header(graphics, font, x, y, filled, fade);
        y += HEADER;
        for (int slot = 0; slot < slots.length; slot++) {
            PartyMemberView member = slots[slot];
            if (member == null) {
                stub(graphics, font, x, y, slot, fade);
                y += STUB + GAP;
                continue;
            }
            boolean isSelected = slot == selected;
            String key = isSelected && evolveKey != null && !evolveKey.isUnbound() ? evolveKey.getTranslatedKeyMessage().getString() : "";
            card(graphics, font, x, y, member, age, isSelected, key, flightFuel.get(member.id()),
                    memories.computeIfAbsent(member.id(), id -> new Memory()), time, fade);
            y += H + GAP;
        }
        graphics.pose().popMatrix();
    }

    // --- pieces ----------------------------------------------------------------------------

    private void header(GuiGraphicsExtractor g, Font font, int x, int y, int filled, float fade) {
        rect(g, x + 2, y + 9, W - 4, 1, tint(DigiTheme.EDGE_DIM, 0xFF, fade));
        for (int i = 0; i < 40; i += 4) rect(g, x + 2 + i, y + 9, 4, 1, tint(DigiTheme.CYAN, Math.round(0xB0 * (1 - i / 40.0F)), fade));
        g.text(font, Component.translatable("gui.digicube.hud.party").getString(), x + 6, y, tint(DigiTheme.CYAN, 0xFF, fade), true);
        String count = Component.translatable("gui.digicube.hud.count", filled, PartyRoster.PARTY_SIZE).getString();
        g.text(font, count, x + W - 4 - font.width(count), y, tint(DigiTheme.MUTED, 0xFF, fade), true);
    }

    private void stub(GuiGraphicsExtractor g, Font font, int x, int y, int slot, float fade) {
        DigiPanels.frame(g, x - 1, y - 1, W + 2, STUB + 2, 0, tint(DigiTheme.VOID, 0x90, fade), 3);
        DigiPanels.frame(g, x, y, W, STUB, tint(DigiTheme.PANEL, 0x90, fade), 0, 2);
        DigiPanels.bevel(g, x, y, W, STUB, 2, tint(DigiTheme.EDGE_DIM, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, x + 1, y + 3, 3, STUB - 6, tint(DigiTheme.EDGE_DIM, 0xC0, fade));
        g.text(font, Component.translatable("gui.digicube.hud.slot_empty", slot + 1).getString(), x + 8, y + 3, tint(DigiTheme.MUTED, 0xC0, fade), true);
    }

    private void card(GuiGraphicsExtractor g, Font font, int x, int y, PartyMemberView m, int age, boolean selected, String key,
                      Float fuel, Memory memory, float time, float fade) {
        float health = PartyHudReadout.healthFraction(m);
        boolean alive = m.health() > 0;
        boolean lit = alive && m.deployed();
        boolean hurt = alive && health <= LOW_HEALTH;
        boolean route = EvolutionRules.target(m.species(), m.level()).isPresent();
        Status status = PartyHudReadout.status(m, age, route);
        DigimonSpecies species = DigimonSpeciesRegistry.get(m.species()).orElse(null);
        DigimonAttribute attribute = species == null ? DigimonAttribute.UNKNOWN : species.attribute();
        int attributeColor = DigiPanels.attributeColor(attribute);
        int light = lit ? DigiTheme.EDGE_LIGHT : DigiTheme.EDGE_DIM;
        if (hurt) light = DigiTheme.mix(DigiTheme.EDGE_DIM, DigiTheme.RED, 0.35F + 0.65F * breath(time, LOW_HEALTH_BREATH_TICKS, 0));

        // frame: outline, shaded body, bevel
        DigiPanels.frame(g, x - 1, y - 1, W + 2, H + 2, 0, tint(DigiTheme.VOID, 0xB0, fade), 3);
        DigiPanels.frame(g, x, y, W, H, tint(DigiTheme.PANEL, 0xDC, fade), 0, 2);
        rect(g, x + 1, y + 2, W - 2, 7, tint(DigiTheme.PANEL_RAISED, 0x90, fade));
        rect(g, x + 1, y + 9, W - 2, 3, tint(DigiTheme.PANEL_RAISED, 0x40, fade));
        rect(g, x + 1, y + H - 9, W - 2, 7, tint(DigiTheme.VOID, 0x60, fade));
        DigiPanels.bevel(g, x, y, W, H, 2, tint(light, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));

        // attribute stripe
        rect(g, x + 1, y + 3, 3, H - 6, tint(attributeColor, m.deployed() ? 0xE0 : 0x80, fade));
        rect(g, x + 1, y + 3, 1, H - 6, tint(DigiTheme.WHITE, 0x28, fade));
        rect(g, x + 3, y + 3, 1, H - 6, tint(DigiTheme.VOID, 0x50, fade));

        // viewport
        int vx = x + 6, vy = y + 4, vw = 30, vh = 32;
        rect(g, vx - 1, vy - 1, vw + 2, vh + 2, tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, vx, vy, vw, vh, tint(DigiTheme.VOID, 0xE6, fade));
        DigiPanels.grid(g, vx + 1, vy + 1, vw - 2, vh - 2, 7, tint(DigiTheme.GRID, 0x3A, fade));
        rect(g, vx, vy, vw, 1, tint(DigiTheme.VOID, 0xA0, fade));
        rect(g, vx, vy, 1, vh, tint(DigiTheme.VOID, 0xA0, fade));
        int finder = tint(DigiTheme.GRID_BRIGHT, 0x90, fade);
        rect(g, vx + 1, vy + 1, 4, 1, finder); rect(g, vx + 1, vy + 1, 1, 4, finder);
        rect(g, vx + vw - 5, vy + 1, 4, 1, finder); rect(g, vx + vw - 2, vy + 1, 1, 4, finder);
        rect(g, vx + 1, vy + vh - 2, 4, 1, finder); rect(g, vx + 1, vy + vh - 5, 1, 4, finder);
        rect(g, vx + vw - 5, vy + vh - 2, 4, 1, finder); rect(g, vx + vw - 2, vy + vh - 5, 1, 4, finder);
        for (int i = 0; i < 3; i++) {
            rect(g, vx + vw - 4, vy + 3 + i * 3, 2, 2, tint(DigiTheme.DATA_LIGHT, Math.round(0x30 + 0x70 * breath(time, DigiTheme.BREATH_TICKS, i * 17)), fade));
        }
        if (status == Status.SOUL) DigiPanels.frame(g, vx, vy, vw, vh, 0, tint(DigiTheme.DATA_LIGHT, 0x60, fade), 1);
        rect(g, vx + 6, vy + 27, 18, 1, lit ? tint(DigiTheme.GRID_BRIGHT, 0x80, fade) : tint(DigiTheme.GRID, 0x40, fade));
        DigiPanels.icon(g, m.species(), vx + 3, vy + 3, SPRITE, DigiTheme.withAlpha(DigiTheme.WHITE, Math.round(255 * fade)));
        int scan = Math.floorMod(tick, SCAN_PERIOD_TICKS);
        if (scan < SCAN_SWEEP_TICKS) rect(g, vx + 1, vy + 1 + scan / 2, vw - 2, 1, tint(DigiTheme.GRID_BRIGHT, 0x2C, fade));
        int dim = !alive ? 0x99 : !m.deployed() ? 0x66 : 0;
        if (dim > 0) rect(g, vx, vy, vw, vh, tint(DigiTheme.VOID, dim, fade));

        // level plate, pocketed over the viewport's bottom edge
        String level = Integer.toString(m.level());
        int plateWidth = 7 + 2 + (4 * level.length() - 1) + 6;
        int px = x + 4, py = y + 31;
        rect(g, px - 1, py - 1, plateWidth + 2, 10, tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, px, py, plateWidth, 8, tint(DigiTheme.PANEL_RAISED, 0xFF, fade));
        DigiPanels.bevel(g, px, py, plateWidth, 8, 1, tint(DigiTheme.EDGE_LIGHT, 0xFF, fade), tint(DigiTheme.SHADOW, 0xFF, fade));
        DigiPanels.readout(g, px + 3, py + 2, "LV", tint(DigiTheme.MUTED, 0xFF, fade));
        DigiPanels.readout(g, px + 12, py + 2, level, tint(memory.flash > 0 ? DigiTheme.GRID_BRIGHT : DigiTheme.WHITE, 0xFF, fade));

        // name band
        int nx = x + TEXT_X;
        rect(g, nx, y + 4, TEXT_W, 10, tint(DigiTheme.PANEL_RAISED, 0xB8, fade));
        rect(g, nx, y + 4, TEXT_W, 1, tint(DigiTheme.WHITE, 0x14, fade));
        for (int i = 0; i < TEXT_W; i += 4) rect(g, nx + i, y + 13, 4, 1, tint(DigiTheme.CYAN, Math.round(0xC8 * (1 - i / (float) TEXT_W)), fade));
        g.text(font, DigiPanels.shortText(font, PartyGraphics.name(m), NAME_W), nx + 2, y + 5, tint(DigiTheme.WHITE, 0xFF, fade), true);
        rect(g, nx + TEXT_W - 9, y + 5, 9, 8, tint(DigiTheme.VOID, 0xC0, fade));
        g.text(font, Integer.toString(m.slot() + 1), nx + TEXT_W - 7, y + 5, tint(DigiTheme.MUTED, 0xFF, fade), false);

        // HP, two-tone with scale ticks, an end cap and the damage ghost behind
        int hx = x + TEXT_X, hy = y + 17;
        rect(g, hx - 1, hy - 1, TEXT_W + 2, 6, tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, hx, hy, TEXT_W, 4, tint(DigiTheme.VOID, 0xFF, fade));
        if (memory.ghost > health) rect(g, hx, hy, Math.round(TEXT_W * memory.ghost), 4, tint(DigiTheme.RED, 0xB0, fade));
        int healthColor = health > HALF_HEALTH ? DigiTheme.TEAL : health > LOW_HEALTH ? DigiTheme.AMBER : DigiTheme.RED;
        int healthWidth = Math.round(TEXT_W * health);
        rect(g, hx, hy, healthWidth, 4, tint(healthColor, 0xFF, fade));
        rect(g, hx, hy, healthWidth, 2, tint(DigiTheme.mix(healthColor, DigiTheme.WHITE, 0.3F), 0xFF, fade));
        if (healthWidth > 1) rect(g, hx + healthWidth - 1, hy, 1, 4, tint(DigiTheme.WHITE, 0x90, fade));
        for (int tx = hx + 6; tx < hx + TEXT_W; tx += 6) rect(g, tx, hy, 1, 4, tint(DigiTheme.PANEL, 0xC0, fade));
        if (fuel != null) {
            rect(g, hx, y + 23, TEXT_W, 1, tint(DigiTheme.EDGE_DIM, 0xFF, fade));
            rect(g, hx, y + 23, Math.round(TEXT_W * Math.clamp(fuel, 0.0F, 1.0F)), 1, tint(DigiTheme.FLIGHT, 0xFF, fade));
        }

        // status row
        int soul = PartyHudReadout.soul(m, age);
        int sx = x + TEXT_X;
        if (status == Status.STAGE) {
            DigiPanels.attributeGlyph(g, attribute, sx, y + 27, tint(attributeColor, 0xD0, fade));
            sx += 8;
        }
        g.text(font, DigiPanels.shortText(font, Component.literal(code(m, status, age, soul, key, species)), x + TEXT_X + TEXT_W - sx), sx, y + 26,
                tint(codeColor(status, soul), 0xFF, fade), true);

        // XP rail
        int xpWidth = Math.round(TEXT_W * PartyHudReadout.xpFraction(m));
        rect(g, hx, y + 36, TEXT_W, 2, tint(DigiTheme.EDGE_DIM, 0xFF, fade));
        rect(g, hx, y + 36, xpWidth, 2, tint(memory.flash > 0 ? DigiTheme.GRID_BRIGHT : DigiTheme.DATA, 0xFF, fade));
        rect(g, hx, y + 36, xpWidth, 1, tint(memory.flash > 0 ? DigiTheme.WHITE : DigiTheme.DATA_LIGHT, 0x90, fade));

        // DigiSoul cell
        int gx = x + 103, gy = y + 4, gw = 5, gh = 34;
        rect(g, gx - 1, gy - 1, gw + 2, gh + 2, tint(DigiTheme.SHADOW, 0xFF, fade));
        rect(g, gx, gy, gw, gh, tint(DigiTheme.VOID, 0xE6, fade));
        boolean locked = PartyHudReadout.soulLocked(m);
        float segments = PartyHudReadout.soulSegments(soul);
        int soulColor = soulColor(status, soul);
        for (int i = 0; i < PartyHudReadout.SOUL_SEGMENTS; i++) {
            int cy = gy + 2 + (PartyHudReadout.SOUL_SEGMENTS - 1 - i) * 4;
            if (locked) {
                rect(g, gx + 1, cy, 3, 3, tint(DigiTheme.EDGE_DIM, 0x50, fade));
                continue;
            }
            float part = Math.clamp(segments - i, 0.0F, 1.0F);
            if (part > 0) {
                int alpha = Math.round(0x50 + 0xAF * part);
                rect(g, gx + 1, cy, 3, 3, tint(soulColor, alpha, fade));
                rect(g, gx + 1, cy, 3, 1, tint(DigiTheme.mix(soulColor, DigiTheme.WHITE, 0.3F), alpha, fade));
            } else rect(g, gx + 1, cy, 3, 3, tint(DigiTheme.EDGE_DIM, 0x60, fade));
        }
        rect(g, gx, gy + 2 + (PartyHudReadout.SOUL_SEGMENTS - PartyHudReadout.minimumSegments()) * 4 - 1, gw, 1,
                tint(locked ? DigiTheme.EDGE_DIM : DigiTheme.MUTED, 0xC0, fade));

        // ready: the gauge cap breathes; selected: steady amber corner brackets, as on the Partner Link screen's chosen card
        if (status == Status.READY) {
            rect(g, gx + 1, gy + 2, 3, 1, tint(DigiTheme.WHITE, Math.round(0x60 + 0x9F * breath(time, DigiTheme.BREATH_TICKS, 0)), fade));
        }
        if (selected) DigiPanels.brackets(g, x, y, W, H, 8, 2, tint(DigiTheme.AMBER, 0xFF, fade));
        // level up: data squares bloom over the card
        if (memory.flash > 0) {
            int[][] cells = {{8, 8}, {18, 12}, {10, 20}, {22, 22}, {16, 28}};
            for (int i = 0; i < cells.length; i++) {
                float alpha = Math.max(0, Mth.sin(memory.flash / (float) DigiTheme.FLASH_TICKS * Mth.PI + i)) * 0x70;
                rect(g, x + cells[i][0], y + cells[i][1], 6, 6, tint(DigiTheme.DATA_LIGHT, Math.round(alpha), fade));
            }
        }
    }

    // --- rules -----------------------------------------------------------------------------

    private static String code(PartyMemberView m, Status status, int age, int soul, String key, DigimonSpecies species) {
        return switch (status) {
            case REST -> Component.translatable("gui.digicube.hud.rest", PartyGraphics.clock(PartyHudReadout.restTicks(m, age))).getString();
            case DEFEATED -> Component.translatable("gui.digicube.hud.defeated").getString();
            case EVOLVING -> Component.translatable("gui.digicube.hud.evolving").getString();
            case REVERTING -> Component.translatable("gui.digicube.hud.reverting").getString();
            case SOUL -> Component.translatable("gui.digicube.hud.soul", PartyGraphics.clock(soul)).getString();
            case NO_SPACE -> Component.translatable("gui.digicube.hud.no_space").getString();
            case COOLDOWN -> Component.translatable("gui.digicube.hud.cooldown", (PartyHudReadout.cooldown(m, age) + 19) / 20).getString();
            case READY -> (key.isEmpty() ? Component.translatable("gui.digicube.hud.ready") : Component.translatable("gui.digicube.hud.ready_key", key)).getString();
            case STAGE -> species == null ? "" : Component.translatable("digicube.stage." + species.stage().getId()).getString().toUpperCase(Locale.ROOT);
        };
    }

    private static int codeColor(Status status, int soul) {
        return switch (status) {
            case DEFEATED -> DigiTheme.RED;
            case EVOLVING, REVERTING -> DigiTheme.CYAN;
            case SOUL -> soul < PartyHudReadout.SOUL_CRITICAL_TICKS ? DigiTheme.RED : soul < PartyHudReadout.SOUL_WARN_TICKS ? DigiTheme.AMBER : DigiTheme.CYAN;
            case NO_SPACE, READY -> DigiTheme.AMBER;
            case REST, COOLDOWN, STAGE -> DigiTheme.MUTED;
        };
    }

    private static int soulColor(Status status, int soul) {
        if (status == Status.SOUL) return soul < PartyHudReadout.SOUL_CRITICAL_TICKS ? DigiTheme.RED : soul < PartyHudReadout.SOUL_WARN_TICKS ? DigiTheme.AMBER : DigiTheme.DATA_LIGHT;
        if (status == Status.COOLDOWN || status == Status.REVERTING) return DigiTheme.MUTED;
        return soul >= com.digicube.digimon.Progression.DIGISOUL_MINIMUM ? DigiTheme.DATA_LIGHT : DigiTheme.DATA;
    }

    // --- primitives ------------------------------------------------------------------------

    /** A filled rectangle by size rather than corners; nothing is drawn for a fully transparent colour. */
    private static void rect(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
        if ((color >>> 24) == 0 || width <= 0 || height <= 0) return;
        g.fill(x, y, x + width, y + height, color);
    }

    /** {@code color} at {@code alpha}, scaled by the strip's fade. */
    private static int tint(int color, int alpha, float fade) {
        return DigiTheme.withAlpha(color, Math.round(alpha * fade));
    }

    /** 0..1 sine breathing with the given period in ticks. */
    private static float breath(float time, int periodTicks, float phase) {
        return 0.5F + 0.5F * Mth.sin((time + phase) * Mth.TWO_PI / periodTicks);
    }
}
