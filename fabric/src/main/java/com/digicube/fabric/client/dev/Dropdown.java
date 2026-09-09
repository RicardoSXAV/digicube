package com.digicube.fabric.client.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * A picker for the developer panel: a header button showing the choice and, while open,
 * a scrolling list dropped below it. The list is not a screen child; the owning screen
 * hands it input first and draws it last so it sits above everything else. Subclasses
 * say how an option is labelled and, if they want, paint an icon before the label.
 */
abstract class Dropdown<T> extends AbstractButton {
    private static final int ITEM_HEIGHT = 14;
    private static final int VISIBLE_ITEMS = 6;
    static final int SELECTED = 0xFFFFC85A;

    /** Paints an icon at the left of a row and returns the width it used, 0 for none. */
    interface Icon {
        Icon NONE = (graphics, x, y, height) -> 0;

        int paint(GuiGraphicsExtractor graphics, int x, int y, int height);
    }

    protected final Font font;
    private final List<T> options;
    private final Consumer<T> onSelect;
    private T selected;
    private Popup popup;

    Dropdown(Font font, int x, int y, int width, int height, Component title, List<T> options, T initial, Consumer<T> onSelect) {
        super(x, y, width, height, title);
        this.font = font;
        this.options = List.copyOf(options);
        this.onSelect = onSelect;
        this.selected = initial != null && this.options.contains(initial) ? initial : this.options.isEmpty() ? null : this.options.getFirst();
    }

    protected abstract String label(T option);

    protected Icon icon(T option) {
        return Icon.NONE;
    }

    T selected() { return selected; }

    boolean isOpen() { return popup != null; }

    void close() { popup = null; }

    private void choose(T option) {
        selected = option;
        close();
        onSelect.accept(option);
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
        boolean hot = isHoveredOrFocused() || popup != null;
        String label = selected == null ? "" : label(selected);
        Icon icon = selected == null ? Icon.NONE : icon(selected);
        drawHeader(graphics, font, label, icon, getX(), getY(), getWidth(), getHeight(), hot, popup == null);
    }

    /** Draws the closed header without a widget: the same box, for the passive overlay. */
    static void drawStatic(GuiGraphicsExtractor graphics, Font font, String label, Icon icon, int x, int y, int width, int height) {
        drawHeader(graphics, font, label, icon, x, y, width, height, false, true);
    }

    private static void drawHeader(GuiGraphicsExtractor graphics, Font font, String label, Icon icon,
                                   int x, int y, int width, int height, boolean hot, boolean closed) {
        graphics.fill(x, y, x + width, y + height, 0xFF202020);
        graphics.outline(x, y, width, height, hot ? 0xFFFFFFFF : 0xFF8A8A8A);
        int used = icon.paint(graphics, x + 2, y, height);
        int textX = x + 3 + used;
        graphics.text(font, DevPanelView.fit(font, label, x + width - 12 - textX), textX, y + (height - 8) / 2, 0xFFFFFFFF, false);
        DevPanelView.triangle(graphics, x + width - 9, y + (height - 3) / 2, closed, hot ? 0xFFB0B0B0 : 0xFF707070);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    // --- the list --------------------------------------------------------------------------

    private final class Popup extends ObjectSelectionList<Row> {
        Popup(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ITEM_HEIGHT);
            setX(Dropdown.this.getX());
            Row current = null;
            for (T option : options) {
                Row row = new Row(option);
                addEntry(row);
                if (option == selected) current = row;
            }
            if (current != null) {
                setSelected(current);
                centerScrollOn(current);
            }
        }

        @Override public int getRowWidth() { return getWidth() - 8; }

        @Override protected int scrollBarX() { return getX() + getWidth() - 6; }
    }

    /** Named {@code Row}: an inner class called {@code Entry} would shadow the inherited one. */
    private final class Row extends ObjectSelectionList.Entry<Row> {
        private final T option;

        Row(T option) {
            this.option = option;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = getContentX();
            int y = getContentY();
            if (hovered) graphics.fill(x - 2, y, x + getContentWidth() + 2, y + getContentHeight(), 0xFF303030);
            int used = icon(option).paint(graphics, x, y, getContentHeight());
            int color = option == selected ? SELECTED : 0xFFFFFFFF;
            graphics.text(font, DevPanelView.fit(font, label(option), getContentWidth() - used - 2), x + used + 1, y + (getContentHeight() - 8) / 2, color, false);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            choose(option);
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(label(option));
        }
    }
}
