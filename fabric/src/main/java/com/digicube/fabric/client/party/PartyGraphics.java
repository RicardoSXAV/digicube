package com.digicube.fabric.client.party;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.party.PartyMemberView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Shared icon and color treatment for the collection, party cards and hotbar. */
final class PartyGraphics {
    static final int INK = 0xFF101B28;
    static final int PANEL = 0xFF172536;
    static final int CARD = 0xFF203448;
    static final int EDGE = 0xFF3B5267;
    static final int WHITE = 0xFFF1F5EB;
    static final int MUTED = 0xFF9DAFBE;
    static final int TEAL = 0xFF74DFC4;
    static final int ORANGE = 0xFFFFBC69;

    private PartyGraphics() {}

    static Component name(PartyMemberView member) {
        if (!member.nickname().isEmpty()) return Component.literal(member.nickname());
        return DigimonSpeciesRegistry.get(member.species()).map(DigimonSpecies::translationKey)
                .map(Component::translatable).orElseGet(() -> Component.literal(member.species().getPath()));
    }

    static void icon(GuiGraphicsExtractor graphics, PartyMemberView member, int x, int y, int size) {
        Identifier texture = member.species().withPath(path -> "textures/gui/digimon/" + path + ".png");
        if (Minecraft.getInstance().getResourceManager().getResource(texture).isPresent()) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, size, size, 32, 32, 32, 32);
        } else {
            graphics.outline(x + 4, y + 4, size - 8, size - 8, EDGE);
            graphics.centeredText(Minecraft.getInstance().font, "?", x + size / 2, y + size / 2 - 4, MUTED);
        }
    }

    static void health(GuiGraphicsExtractor graphics, PartyMemberView member, int x, int y, int width) {
        float fraction = member.maxHealth() <= 0 ? 0 : Math.clamp(member.health() / member.maxHealth(), 0, 1);
        graphics.fill(x, y, x + width, y + 2, INK);
        int color = fraction > 0.5F ? TEAL : fraction > 0.25F ? ORANGE : 0xFFEF7980;
        graphics.fill(x, y, x + Math.round(width * fraction), y + 2, color);
    }

    static String shortText(Font font, Component text, int width) {
        String value = text.getString();
        return font.width(value) <= width ? value : font.plainSubstrByWidth(value, Math.max(0, width - 6)) + "…";
    }

    static Component status(PartyMemberView member) {
        if (member.health() <= 0) return Component.translatable("gui.digicube.party.defeated");
        if (member.slot() < 0) return Component.translatable("gui.digicube.party.reserve");
        return Component.translatable(member.deployed() ? "gui.digicube.party.active" : "gui.digicube.party.waiting");
    }
}
