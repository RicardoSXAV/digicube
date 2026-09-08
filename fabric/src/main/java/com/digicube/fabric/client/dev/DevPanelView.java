package com.digicube.fabric.client.dev;

import com.digicube.dev.DevState;
import com.digicube.dev.SpeciesSheetWriter;
import com.digicube.dev.SpeciesTuning;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.dev.DevClient.Section;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Layout and drawing of the developer panel, shared by the passive overlay and the focused
 * screen so both look the same. Everything is in panel units: the caller scales the pose
 * by {@link DevClient#SCALE}. {@link #layout} decides where every row goes for the current
 * readout and folded sections; {@link #draw} paints the frame, headers and text, and in
 * overlay mode also the static stand-ins for the controls the screen puts widgets on.
 */
final class DevPanelView {
    static final int X = 4;
    static final int Y = 4;
    static final int WIDTH = 176;
    static final int PAD = 4;
    static final int LINE = 10;
    static final int ROW = 14;
    static final int HEADER = 12;
    static final int INNER = WIDTH - 2 * PAD;
    /** Left edge of the value box on a tuning row; the label sits before it. */
    static final int FIELD_BOX_X = 32;
    static final int FIELD_BOX_WIDTH = 40;
    static final int SMALL_BUTTON = 14;

    static final int PANEL = 0xE8101010;
    static final int EDGE = 0xFF6A6A6A;
    static final int WHITE = 0xFFFFFFFF;
    static final int MUTED = 0xFFA0A0A0;
    static final int ACCENT = 0xFFFFC85A;
    static final int GOOD = 0xFF7CE38B;
    static final int BAD = 0xFFF07A7A;

    private DevPanelView() {}

    /** One owned Digimon as the server described it. */
    record Member(String name, int level, int xp, int xpToNext, float health, float maxHealth,
                  int slot, boolean deployed, boolean defeated) {
        static Member of(CompoundTag tag) {
            return new Member(tag.getStringOr("name", "?"), tag.getIntOr("level", 1), tag.getIntOr("xp", 0),
                    tag.getIntOr("xpToNext", 0), tag.getFloatOr("health", 0), tag.getFloatOr("maxHealth", 0),
                    tag.getIntOr("slot", -1), tag.getBooleanOr("deployed", false), tag.getBooleanOr("defeated", false));
        }

        String status() {
            if (defeated) return "DEFEATED";
            if (slot >= 0) return deployed ? "slot " + (slot + 1) : "slot " + (slot + 1) + " · away";
            return "reserve";
        }
    }

    /** The tuning numbers of the chosen species: as they run now, and as the sheet ships them. */
    record Tune(String species, CompoundTag values, CompoundTag bundled) {
        static Tune of(CompoundTag state) {
            if (!state.contains(DevState.TUNE)) return null;
            CompoundTag tag = state.getCompoundOrEmpty(DevState.TUNE);
            return new Tune(tag.getStringOr("species", ""), tag.getCompoundOrEmpty("values"), tag.getCompoundOrEmpty("bundled"));
        }
    }

    /** Where everything goes this frame. Rows in folded sections keep {@code -1}. */
    static final class Layout {
        final List<Member> members = new ArrayList<>();
        Tune tune;
        final List<SpeciesTuning.Field> fields = new ArrayList<>();
        final EnumMap<Section, Integer> headerY = new EnumMap<>(Section.class);
        int speciesY;
        int levelY = -1;
        int spawnButtonsY = -1;
        int membersY = -1;
        final int[] fieldY = new int[SpeciesTuning.FIELDS.size()];
        int tuneButtonsY = -1;
        int worldY = -1;
        int replyY;
        int height;

        boolean fieldShown(int index) { return fieldY[index] >= 0; }
    }

    static Layout layout(DevClient client) {
        Layout layout = new Layout();
        CompoundTag state = client.state();
        state.getListOrEmpty(DevState.PARTY).compoundStream().map(Member::of).forEach(layout.members::add);
        layout.tune = Tune.of(state);
        java.util.Arrays.fill(layout.fieldY, -1);
        boolean tuneMatches = layout.tune != null && client.species() != null && layout.tune.species().equals(client.species().toString());
        for (SpeciesTuning.Field field : SpeciesTuning.FIELDS) {
            if (tuneMatches && layout.tune.values().contains(field.key())) layout.fields.add(field);
        }

        int y = Y + PAD;
        y += LINE + 3;                       // title row and its separator
        layout.speciesY = y;
        y += ROW + 3;

        layout.headerY.put(Section.SPAWN, y);
        y += HEADER;
        if (!client.collapsed(Section.SPAWN)) {
            layout.levelY = y;
            y += ROW + 2;
            layout.spawnButtonsY = y;
            y += ROW + 3;
        }

        layout.headerY.put(Section.PARTY, y);
        y += HEADER;
        if (!client.collapsed(Section.PARTY)) {
            layout.membersY = y;
            y += layout.members.isEmpty() ? LINE + 2 : layout.members.size() * (2 * LINE + 1) + 2;
        }

        layout.headerY.put(Section.TUNE, y);
        y += HEADER;
        if (!client.collapsed(Section.TUNE)) {
            if (layout.fields.isEmpty()) {
                y += LINE + 2;
            } else {
                for (SpeciesTuning.Field field : layout.fields) {
                    layout.fieldY[SpeciesTuning.FIELDS.indexOf(field)] = y;
                    y += ROW + 1;
                }
                y += 1;
                layout.tuneButtonsY = y;
                y += ROW + 3;
            }
        }

        layout.headerY.put(Section.WORLD, y);
        y += HEADER;
        if (!client.collapsed(Section.WORLD)) {
            layout.worldY = y;
            y += 3 * LINE + 2;
        }

        y += 1;                               // separator
        layout.replyY = y + 3;
        y += LINE + 4;
        layout.height = y - Y;
        return layout;
    }

    /** The number a tuning row shows: the pending edit if there is one, else the running value. */
    static double shown(DevClient client, Layout layout, SpeciesTuning.Field field) {
        if (client.edits().contains(field.key())) return client.edits().getDoubleOr(field.key(), 0);
        return layout.tune == null ? 0 : layout.tune.values().getDoubleOr(field.key(), 0);
    }

    // --- drawing ---------------------------------------------------------------------

    /**
     * @param focused true when the screen is drawing: widgets paint their own rows, so the
     *                stand-ins for controls are skipped
     */
    static void draw(GuiGraphicsExtractor graphics, Font font, DevClient client, Layout layout, boolean focused) {
        int left = X + PAD;
        int right = X + WIDTH - PAD;
        graphics.fill(X, Y, X + WIDTH, Y + layout.height, PANEL);
        graphics.outline(X, Y, WIDTH, layout.height, EDGE);

        int y = Y + PAD;
        graphics.text(font, Component.translatable("gui.digicube.dev.title").getString(), left, y, ACCENT, false);
        String hint = Component.translatable(focused ? "gui.digicube.dev.hint_focus" : "gui.digicube.dev.hint_hud").getString();
        graphics.text(font, hint, right - font.width(hint), y, MUTED, false);
        separator(graphics, y + LINE + 1);

        if (!focused) {
            DigimonSpecies species = client.species() == null ? null : DigimonSpeciesRegistry.get(client.species()).orElse(null);
            SpeciesDropdown.drawStatic(graphics, font, species, left, layout.speciesY, INNER, ROW);
        }

        // SPAWN
        header(graphics, font, client, layout, Section.SPAWN, Component.translatable(Section.SPAWN.labelKey).getString());
        if (layout.levelY >= 0) {
            String level = Component.translatable("gui.digicube.dev.level").getString();
            graphics.text(font, level, left, layout.levelY + 3, WHITE, false);
            if (!focused) graphics.text(font, Integer.toString(client.level()), left + 16, layout.levelY + 3, ACCENT, false);
        }

        // PARTY
        header(graphics, font, client, layout, Section.PARTY, Component.translatable(Section.PARTY.labelKey).getString() + "  " + layout.members.size());
        if (layout.membersY >= 0) {
            int line = layout.membersY;
            if (layout.members.isEmpty()) {
                String none = Component.translatable(client.stateReceived() ? "gui.digicube.dev.none" : "gui.digicube.dev.waiting").getString();
                graphics.text(font, fit(font, none, INNER), left, line, MUTED, false);
            }
            for (Member member : layout.members) {
                String status = member.status();
                int statusWidth = font.width(status);
                graphics.text(font, fit(font, member.name(), INNER - statusWidth - 4), left, line, member.slot() >= 0 ? WHITE : MUTED, false);
                graphics.text(font, status, right - statusWidth, line, member.defeated() ? BAD : MUTED, false);
                int hp = Math.round(member.health());
                int max = Math.round(member.maxHealth());
                int hpColor = member.defeated() ? BAD : hp >= max ? GOOD : hp * 3 < max ? BAD : WHITE;
                String xp = member.xpToNext() == 0 ? "MAX" : member.xp() + "/" + member.xpToNext() + " XP";
                String level = "Lv " + member.level() + " · ";
                String health = hp + "/" + max + " HP";
                graphics.text(font, level, left + 4, line + LINE, MUTED, false);
                int x = left + 4 + font.width(level);
                graphics.text(font, health, x, line + LINE, hpColor, false);
                x += font.width(health);
                graphics.text(font, fit(font, " · " + xp, right - x), x, line + LINE, MUTED, false);
                line += 2 * LINE + 1;
            }
        }

        // TUNE
        header(graphics, font, client, layout, Section.TUNE, Component.translatable(Section.TUNE.labelKey).getString());
        if (!client.collapsed(Section.TUNE)) {
            if (layout.fields.isEmpty()) {
                String none = Component.translatable(client.stateReceived() ? "gui.digicube.dev.none" : "gui.digicube.dev.waiting").getString();
                graphics.text(font, none, left, layout.headerY.get(Section.TUNE) + HEADER, MUTED, false);
            }
            for (SpeciesTuning.Field field : layout.fields) {
                int index = SpeciesTuning.FIELDS.indexOf(field);
                int rowY = layout.fieldY[index];
                double value = shown(client, layout, field);
                double bundled = layout.tune.bundled().getDoubleOr(field.key(), value);
                boolean changed = Math.abs(value - bundled) > 1e-6;
                graphics.text(font, field.label(), left, rowY + 3, changed ? ACCENT : WHITE, false);
                if (!focused) graphics.text(font, SpeciesSheetWriter.format(value), left + FIELD_BOX_X + 2, rowY + 3, changed ? ACCENT : WHITE, false);
                String shipped = "(" + SpeciesSheetWriter.format(bundled) + ")";
                graphics.text(font, shipped, right - font.width(shipped), rowY + 3, MUTED, false);
            }
        }

        // WORLD
        header(graphics, font, client, layout, Section.WORLD, Component.translatable(Section.WORLD.labelKey).getString());
        if (layout.worldY >= 0) {
            CompoundTag wild = client.state().getCompoundOrEmpty(DevState.WILD);
            CompoundTag where = client.state().getCompoundOrEmpty(DevState.PLAYER);
            String first = "Wild " + wild.getIntOr("count", 0) + "/" + wild.getIntOr("cap", 0)
                    + " · spawner " + (wild.getBooleanOr("enabled", false) ? "on" : "off")
                    + " · " + wild.getIntOr("interval", 0) + "t";
            String second = "cap " + wild.getIntOr("maxPerPlayer", 0) + "/player · " + wild.getIntOr("maxPerLevel", 0) + "/dimension";
            String dimension = where.getStringOr("dimension", "?");
            int colon = dimension.indexOf(':');
            String third = (colon >= 0 ? dimension.substring(colon + 1) : dimension)
                    + "  " + where.getIntOr("x", 0) + " " + where.getIntOr("y", 0) + " " + where.getIntOr("z", 0);
            graphics.text(font, fit(font, first, INNER), left, layout.worldY, WHITE, false);
            graphics.text(font, fit(font, second, INNER), left, layout.worldY + LINE, MUTED, false);
            graphics.text(font, fit(font, third, INNER), left, layout.worldY + 2 * LINE, MUTED, false);
        }

        separator(graphics, layout.replyY - 3);
        String reply = client.reply();
        if (!reply.isEmpty()) graphics.text(font, fit(font, reply, INNER), left, layout.replyY, ACCENT, false);
    }

    private static void header(GuiGraphicsExtractor graphics, Font font, DevClient client, Layout layout, Section section, String label) {
        int y = layout.headerY.get(section);
        graphics.fill(X + 1, y, X + WIDTH - 1, y + HEADER, 0x40FFFFFF);
        triangle(graphics, X + PAD + 1, y + (HEADER - 3) / 2 - 1, !client.collapsed(section), MUTED);
        graphics.text(font, label, X + PAD + 10, y + 2, MUTED, false);
    }

    private static void separator(GuiGraphicsExtractor graphics, int y) {
        graphics.fill(X + 3, y, X + WIDTH - 3, y + 1, EDGE);
    }

    /** A small pixel triangle, 5 wide and 3 tall, pointing down when {@code down}. */
    static void triangle(GuiGraphicsExtractor graphics, int x, int y, boolean down, int color) {
        for (int row = 0; row < 3; row++) {
            int inset = down ? row : 2 - row;
            graphics.fill(x + inset, y + row, x + 5 - inset, y + row + 1, color);
        }
    }

    static String fit(Font font, String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - 6)) + "…";
    }
}
