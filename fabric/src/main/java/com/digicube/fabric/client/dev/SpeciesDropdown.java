package com.digicube.fabric.client.dev;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/** The species picker: every registered species by translated name, with its icon. */
final class SpeciesDropdown extends Dropdown<DigimonSpecies> {
    private static final int ICON = 12;

    SpeciesDropdown(Font font, int x, int y, int width, int height, DigimonSpecies initial, Consumer<DigimonSpecies> onSelect) {
        super(font, x, y, width, height, Component.translatable("gui.digicube.dev.species"),
                List.copyOf(DigimonSpeciesRegistry.all()), initial, onSelect);
    }

    @Override
    protected String label(DigimonSpecies species) {
        return Component.translatable(species.translationKey()).getString();
    }

    @Override
    protected Icon icon(DigimonSpecies species) {
        return icon(species, ICON);
    }

    private static Icon icon(DigimonSpecies species, int size) {
        return (graphics, x, y, height) -> {
            DigiPanels.icon(graphics, species.id(), x, y + (height - size) / 2, size);
            return size + 3;
        };
    }

    /** Draws the closed header without a widget: the same box, for the passive overlay. */
    static void drawStatic(GuiGraphicsExtractor graphics, Font font, DigimonSpecies species, int x, int y, int width, int height) {
        drawStatic(graphics, font, species == null ? "" : Component.translatable(species.translationKey()).getString(), species == null ? Icon.NONE : icon(species, ICON),
                x, y, width, height);
    }
}
