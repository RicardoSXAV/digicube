package com.digicube.fabric.client.dev;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A species picker for the developer panel: a header button showing the choice and, while
 * open, a scrolling list dropped below it. The list is not a screen child; the owning
 * screen hands it input first and draws it last so it sits above everything else.
 */
final class SpeciesDropdown extends AbstractButton {
    private static final int ITEM_HEIGHT = 14;
    private static final int VISIBLE_ITEMS = 6;
    private static final int ICON = 12;

    private final Font font;
    private final List<DigimonSpecies> options = new ArrayList<>(DigimonSpeciesRegistry.all());
    private final Consumer<DigimonSpecies> onSelect;
    private DigimonSpecies selected;
    private Popup popup;

    SpeciesDropdown(Font font, int x, int y, int width, int height, DigimonSpecies initial, Consumer<DigimonSpecies> onSelect) {
        super(x, y, width, height, Component.translatable("gui.digicube.dev.species"));
        this.font = font;
        this.onSelect = onSelect;
        this.selected = initial != null ? initial : options.isEmpty() ? null : options.getFirst();
    }

    DigimonSpecies selected() { return selected; }

    boolean isOpen() { return popup != null; }

    void close() { popup = null; }

    private void choose(DigimonSpecies species) {
        selected = species;
        close();
        onSelect.accept(species);
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (popup != null) {
            close();
            return;
        }
        int rows = Math.min(VISIBLE_ITEMS, Math.max(1, options.size()));
        popup = new Popup(Minecraft.getInstance(), getWidth(), rows * ITEM_HEIGHT + 4, getY() + getHeight() + 1);
    }

    // --- input handed down by the screen ------------------------------------------------

    /** @return true when the click is consumed: it landed in the list, or on the header while open */
    boolean handleClick(MouseButtonEvent event, boolean doubleClick) {
        if (popup == null) return false;
        if (popup.isMouseOver(event.x(), event.y())) {
            popup.mouseClicked(event, doubleClick);
            return true;
        }
        close();
        return isMouseOver(event.x(), event.y());
    }

    boolean handleScroll(double x, double y, double horizontal, double vertical) {
        return popup != null && popup.isMouseOver(x, y) && popup.mouseScrolled(x, y, horizontal, vertical);
    }

    void extractPopup(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (popup == null) return;
        int x = popup.getX();
        int y = popup.getY();
        graphics.fill(x - 1, y - 1, x + popup.getWidth() + 1, y + popup.getHeight() + 1, 0xFF9A9A9A);
        graphics.fill(x, y, x + popup.getWidth(), y + popup.getHeight(), 0xFF141414);
        popup.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    // --- header --------------------------------------------------------------------------

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        boolean hot = isHoveredOrFocused() || popup != null;
        graphics.fill(x, y, x + getWidth(), y + getHeight(), 0xFF202020);
        graphics.outline(x, y, getWidth(), getHeight(), hot ? 0xFFFFFFFF : 0xFF8A8A8A);
        if (selected != null) {
            DigiPanels.icon(graphics, selected.id(), x + 2, y + (getHeight() - ICON) / 2, ICON);
            String name = DigiPanels.shortText(font, Component.literal(selected.id().getPath()), getWidth() - ICON - 18);
            graphics.text(font, name, x + ICON + 5, y + (getHeight() - 8) / 2, 0xFFFFFFFF, false);
        }
        DevPanelView.triangle(graphics, x + getWidth() - 9, y + (getHeight() - 3) / 2, popup == null, 0xFFB0B0B0);
    }

    /** Draws the closed header without a widget: the same box, for the passive overlay. */
    static void drawStatic(GuiGraphicsExtractor graphics, Font font, DigimonSpecies species, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF202020);
        graphics.outline(x, y, width, height, 0xFF8A8A8A);
        if (species != null) {
            DigiPanels.icon(graphics, species.id(), x + 2, y + (height - ICON) / 2, ICON);
            String name = DigiPanels.shortText(font, Component.literal(species.id().getPath()), width - ICON - 18);
            graphics.text(font, name, x + ICON + 5, y + (height - 8) / 2, 0xFFFFFFFF, false);
        }
        DevPanelView.triangle(graphics, x + width - 9, y + (height - 3) / 2, true, 0xFF707070);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    // --- the list --------------------------------------------------------------------------

    private final class Popup extends ObjectSelectionList<SpeciesEntry> {
        Popup(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ITEM_HEIGHT);
            setX(SpeciesDropdown.this.getX());
            SpeciesEntry current = null;
            for (DigimonSpecies species : options) {
                SpeciesEntry entry = new SpeciesEntry(species);
                addEntry(entry);
                if (species == selected) current = entry;
            }
            if (current != null) {
                setSelected(current);
                centerScrollOn(current);
            }
        }

        @Override public int getRowWidth() { return getWidth() - 8; }

        @Override protected int scrollBarX() { return getX() + getWidth() - 6; }
    }

    private final class SpeciesEntry extends ObjectSelectionList.Entry<SpeciesEntry> {
        private final DigimonSpecies species;

        SpeciesEntry(DigimonSpecies species) {
            this.species = species;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX();
            int y = getContentY();
            if (hovered) graphics.fill(x - 2, y, x + getContentWidth() + 2, y + getContentHeight(), 0xFF303030);
            DigiPanels.icon(graphics, species.id(), x, y + (getContentHeight() - ICON) / 2, ICON);
            int color = species == selected ? 0xFFFFC85A : 0xFFFFFFFF;
            graphics.text(font, species.id().getPath(), x + ICON + 4, y + (getContentHeight() - 8) / 2, color, false);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            choose(species);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(species.id().getPath());
        }
    }
}
