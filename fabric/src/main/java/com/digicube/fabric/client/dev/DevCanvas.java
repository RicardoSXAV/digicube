package com.digicube.fabric.client.dev;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * What the panel hands a {@link DevBody} while drawing: the graphics, the cursor, and a way to
 * register controls for this frame. Controls are immediate: a body declares them every frame
 * where it draws them, and the panel routes the next click or scroll to the topmost one.
 */
public interface DevCanvas {
    GuiGraphicsExtractor graphics();

    Font font();

    /** Whether the cursor is over this rectangle and nothing (a popup, the clipped edge) covers it. */
    boolean over(int x, int y, int width, int height);

    void click(int x, int y, int width, int height, Runnable action);

    /** Mouse wheel over a rectangle; the consumer gets +1 per notch up, -1 per notch down. */
    void wheel(int x, int y, int width, int height, IntConsumer steps);

    /** Opens the searchable Digimon list under (or above) the anchor rectangle. */
    void pickSpecies(Object owner, int x, int y, int anchorHeight, Identifier current, Consumer<Identifier> pick);

    /** Whether the Digimon list is open for {@code owner}. */
    boolean picking(Object owner);
}
