package com.digicube.fabric.client.mode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * The info key after the section's label: an 11 x 11 ring with the font's "?", grey at rest and white when pointed at
 * or focused from the keyboard, when the two cards show ({@link ModeCards}). It does nothing when pressed; its narration
 * reads both cards.
 */
final class InfoKey extends AbstractWidget {
    static final int SIZE = 11;
    private static final String[] RING = {"...#####...", "..#.....#..", ".#.......#.", "#.........#", "#.........#",
            "#.........#", "#.........#", "#.........#", ".#.......#.", "..#.....#..", "...#####..."};

    InfoKey() {
        super(0, 0, SIZE, SIZE, Component.translatable("gui.digicube.mode.info"));
    }

    /** Whether the cards show: pointed at, or focused from the keyboard (a click alone does not pin them). */
    boolean open() {
        return visible && (isHovered() || isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard());
    }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        int color = open() ? 0xFFFFFFFF : 0xFFA0A0A0;
        ModeButton.stamp(g, RING, getX() + 1, getY() + 1, ModeButton.shadow(color));
        ModeButton.stamp(g, RING, getX(), getY(), color);
        g.text(Minecraft.getInstance().font, "?", getX() + 3, getY() + 2, color, true);
    }

    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {}

    @Override public void playDownSound(SoundManager sounds) {}

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        output.add(NarratedElementType.HINT, ModeCards.narration());
    }
}
