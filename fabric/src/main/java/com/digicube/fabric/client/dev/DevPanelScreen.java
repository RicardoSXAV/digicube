package com.digicube.fabric.client.dev;

import com.digicube.dev.DevActions;
import com.digicube.dev.SpeciesSheetWriter;
import com.digicube.dev.SpeciesTuning;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.fabric.client.dev.DevClient.Section;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;

/**
 * The developer panel in focus: the same panel the overlay shows, with live controls on
 * its rows. It does not pause the world, so a tune can be judged while the Digimon moves.
 * Esc or F7 hand the panel back to the overlay; F6 hides it altogether. Drawing and input
 * are scaled by {@link DevClient#SCALE}, so the widgets live in panel units too.
 */
final class DevPanelScreen extends Screen {
    private static final float SCALE = DevClient.SCALE;

    private final DevClient client;
    private final EnumMap<Section, HeaderButton> headers = new EnumMap<>(Section.class);
    private SpeciesDropdown dropdown;
    private EditBox levelBox;
    private final AbstractWidget[] levelButtons = new AbstractWidget[4];
    private AbstractWidget spawnWild;
    private AbstractWidget givePartner;
    private AbstractWidget healAll;
    private final EditBox[] fieldBoxes = new EditBox[SpeciesTuning.FIELDS.size()];
    private final AbstractWidget[] fieldMinus = new AbstractWidget[SpeciesTuning.FIELDS.size()];
    private final AbstractWidget[] fieldPlus = new AbstractWidget[SpeciesTuning.FIELDS.size()];
    private AbstractWidget apply;
    private AbstractWidget reset;
    private AbstractWidget write;
    private DevPanelView.Layout layout;
    /** Set while a box is filled from the readout, so its responder does not record an edit. */
    private boolean syncing;

    DevPanelScreen(DevClient client) {
        super(Component.translatable("gui.digicube.dev.title"));
        this.client = client;
    }

    // --- widgets ---------------------------------------------------------------------

    @Override
    protected void init() {
        headers.clear();
        int left = DevPanelView.X + DevPanelView.PAD;
        int inner = DevPanelView.INNER;
        int row = DevPanelView.ROW;
        int small = DevPanelView.SMALL_BUTTON;

        DigimonSpecies initial = client.species() == null ? null : DigimonSpeciesRegistry.get(client.species()).orElse(null);
        dropdown = addRenderableWidget(new SpeciesDropdown(font, left, 0, inner, row, initial, species -> client.setSpecies(species.id())));
        if (dropdown.selected() != null) client.setSpecies(dropdown.selected().id());
        for (Section section : Section.values()) {
            headers.put(section, addRenderableWidget(new HeaderButton(section)));
        }

        levelBox = addRenderableWidget(new EditBox(font, 0, 0, 26, row, Component.translatable("gui.digicube.dev.level")));
        levelBox.setMaxLength(2);
        levelBox.setResponder(text -> {
            if (syncing) return;
            try { client.setLevel(Integer.parseInt(text.trim())); } catch (NumberFormatException ignored) { /* keep the last valid level */ }
        });
        int[] steps = {-1, 1, -10, 10};
        String[] glyphs = {"-", "+", "<", ">"};
        for (int i = 0; i < 4; i++) {
            int step = steps[i];
            levelButtons[i] = addRenderableWidget(Button.builder(Component.literal(glyphs[i]), button -> bumpLevel(step)).size(small, row).build());
        }
        spawnWild = addRenderableWidget(Button.builder(Component.translatable("gui.digicube.dev.spawn_wild"), button -> act(DevActions.SPAWN))
                .size((inner - 4) / 2, row).build());
        givePartner = addRenderableWidget(Button.builder(Component.translatable("gui.digicube.dev.give_partner"), button -> act(DevActions.GIVE))
                .size((inner - 4) / 2, row).build());
        healAll = addRenderableWidget(Button.builder(Component.translatable("gui.digicube.dev.heal_all"), button -> client.send(DevActions.HEAL, client.speciesArgs()))
                .size(50, DevPanelView.HEADER).build());

        for (int i = 0; i < SpeciesTuning.FIELDS.size(); i++) {
            SpeciesTuning.Field field = SpeciesTuning.FIELDS.get(i);
            EditBox box = addRenderableWidget(new EditBox(font, 0, 0, DevPanelView.FIELD_BOX_WIDTH, row, Component.literal(field.label())));
            box.setMaxLength(8);
            box.setResponder(text -> {
                if (syncing) return;
                try { client.edit(field.key(), Double.parseDouble(text.trim())); } catch (NumberFormatException ignored) { /* keep the last valid number */ }
            });
            fieldBoxes[i] = box;
            fieldMinus[i] = addRenderableWidget(Button.builder(Component.literal("-"), button -> bumpField(field, -1)).size(small, row).build());
            fieldPlus[i] = addRenderableWidget(Button.builder(Component.literal("+"), button -> bumpField(field, 1)).size(small, row).build());
        }
        int third = (inner - 6) / 3;
        apply = addRenderableWidget(Button.builder(Component.translatable("gui.digicube.dev.apply"), button -> applyTune()).size(third, row).build());
        reset = addRenderableWidget(Button.builder(Component.translatable("gui.digicube.dev.reset"), button -> resetTune()).size(third, row).build());
        write = addRenderableWidget(Button.builder(Component.translatable("gui.digicube.dev.write"), button -> writeTune()).size(third, row).build());
        place();
        client.refresh();
    }

    /** Moves every widget to where this frame's layout puts it and hides those in folded sections. */
    private void place() {
        layout = DevPanelView.layout(client);
        int left = DevPanelView.X + DevPanelView.PAD;
        int right = DevPanelView.X + DevPanelView.WIDTH - DevPanelView.PAD;
        dropdown.setY(layout.speciesY);
        for (Section section : Section.values()) {
            HeaderButton header = headers.get(section);
            header.setY(layout.headerY.get(section));
        }

        boolean spawn = layout.levelY >= 0;
        show(levelBox, spawn, left + 16, layout.levelY);
        int x = left + 16 + 26 + 2;
        for (int i = 0; i < 4; i++) {
            show(levelButtons[i], spawn, x, layout.levelY);
            x += DevPanelView.SMALL_BUTTON + (i == 1 ? 4 : 2);
        }
        show(spawnWild, spawn, left, layout.spawnButtonsY);
        show(givePartner, spawn, left + (DevPanelView.INNER - 4) / 2 + 4, layout.spawnButtonsY);
        show(healAll, true, right - 50, layout.headerY.get(Section.PARTY));

        for (int i = 0; i < SpeciesTuning.FIELDS.size(); i++) {
            boolean shown = layout.fieldShown(i);
            int y = layout.fieldY[i];
            show(fieldBoxes[i], shown, left + DevPanelView.FIELD_BOX_X, y);
            show(fieldMinus[i], shown, left + DevPanelView.FIELD_BOX_X + DevPanelView.FIELD_BOX_WIDTH + 2, y);
            show(fieldPlus[i], shown, left + DevPanelView.FIELD_BOX_X + DevPanelView.FIELD_BOX_WIDTH + 2 + DevPanelView.SMALL_BUTTON + 2, y);
        }
        boolean tune = layout.tuneButtonsY >= 0;
        int third = (DevPanelView.INNER - 6) / 3;
        show(apply, tune, left, layout.tuneButtonsY);
        show(reset, tune, left + third + 3, layout.tuneButtonsY);
        show(write, tune, left + 2 * (third + 3), layout.tuneButtonsY);
        syncBoxes();
    }

    private static void show(AbstractWidget widget, boolean visible, int x, int y) {
        widget.visible = visible;
        if (visible) {
            widget.setX(x);
            widget.setY(y);
        }
    }

    /** Boxes follow the readout unless the developer is typing in them. */
    private void syncBoxes() {
        syncing = true;
        try {
            String level = Integer.toString(client.level());
            if (!levelBox.isFocused() && !levelBox.getValue().equals(level)) levelBox.setValue(level);
            for (int i = 0; i < SpeciesTuning.FIELDS.size(); i++) {
                if (!layout.fieldShown(i) || fieldBoxes[i].isFocused()) continue;
                String value = SpeciesSheetWriter.format(DevPanelView.shown(client, layout, SpeciesTuning.FIELDS.get(i)));
                if (!fieldBoxes[i].getValue().equals(value)) fieldBoxes[i].setValue(value);
            }
        } finally {
            syncing = false;
        }
    }

    // --- actions ---------------------------------------------------------------------

    private void bumpLevel(int delta) {
        client.setLevel(client.level() + delta);
        syncBoxes();
    }

    private void bumpField(SpeciesTuning.Field field, int direction) {
        double value = DevPanelView.shown(client, layout, field) + direction * field.step();
        client.edit(field.key(), Math.max(0, Math.round(value * 1000.0) / 1000.0));
        syncBoxes();
    }

    private void act(String action) {
        DigimonSpecies species = dropdown.selected();
        if (species == null) return;
        CompoundTag args = client.speciesArgs();
        args.putInt(DevActions.LEVEL_ARG, Progression.clampLevel(client.level()));
        client.send(action, args);
    }

    private void applyTune() {
        if (client.species() == null) return;
        CompoundTag args = client.speciesArgs();
        args.put(DevActions.VALUES_ARG, client.edits().copy());
        client.send(DevActions.TUNE, args);
        client.clearEdits();
    }

    private void resetTune() {
        if (client.species() == null) return;
        client.send(DevActions.TUNE_RESET, client.speciesArgs());
        client.clearEdits();
    }

    /** Unapplied edits are applied first, so the sheet gets what is on screen. */
    private void writeTune() {
        if (client.species() == null) return;
        if (!client.edits().isEmpty()) applyTune();
        client.send(DevActions.TUNE_WRITE, client.speciesArgs());
    }

    // --- lifecycle -------------------------------------------------------------------

    @Override
    public void tick() {
        place();
        if (minecraft.player == null) onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean typing = getFocused() instanceof EditBox;
        if (!typing && DevClient.TOGGLE != null && DevClient.TOGGLE.matches(event)) {
            client.setVisible(false);
            onClose();
            return true;
        }
        if (!typing && DevClient.FOCUS != null && DevClient.FOCUS.matches(event)) {
            onClose();
            return true;
        }
        if (dropdown.isOpen() && event.key() == InputConstants.KEY_ESCAPE) {
            dropdown.close();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean isPauseScreen() { return false; }

    // --- scaled input: the screen works in panel units --------------------------------

    private static MouseButtonEvent scaled(MouseButtonEvent event) {
        return new MouseButtonEvent(event.x() / SCALE, event.y() / SCALE, event.buttonInfo());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent local = scaled(event);
        if (dropdown.handleClick(local, doubleClick)) return true;
        return super.mouseClicked(local, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(scaled(event));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(scaled(event), dragX / SCALE, dragY / SCALE);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (dropdown.handleScroll(x / SCALE, y / SCALE, horizontal, vertical)) return true;
        return super.mouseScrolled(x / SCALE, y / SCALE, horizontal, vertical);
    }

    @Override
    public void mouseMoved(double x, double y) {
        super.mouseMoved(x / SCALE, y / SCALE);
    }

    // --- drawing ---------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No dimming: the point is to watch what the actions do to the world.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int localX = Math.round(mouseX / SCALE);
        int localY = Math.round(mouseY / SCALE);
        graphics.pose().pushMatrix();
        graphics.pose().scale(SCALE, SCALE);
        DevPanelView.draw(graphics, font, client, layout, true);
        super.extractRenderState(graphics, localX, localY, partialTick);
        dropdown.extractPopup(graphics, localX, localY, partialTick);
        graphics.pose().popMatrix();
    }

    /** The click area of a section header; the view paints the header itself. */
    private final class HeaderButton extends AbstractButton {
        private final Section section;

        HeaderButton(Section section) {
            super(DevPanelView.X + 1, 0, DevPanelView.WIDTH - 2 - 54, DevPanelView.HEADER,
                    Component.translatable(section.labelKey));
            this.section = section;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            client.toggle(section);
            place();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            if (isHoveredOrFocused()) graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x30FFFFFF);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
