package com.digicube.fabric.client.starter;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.fabric.client.gui.DigiPanels;
import com.digicube.fabric.client.gui.DigiTheme;
import com.digicube.fabric.client.gui.DigimonPreview;
import com.digicube.starter.StarterChoicePayload;
import com.digicube.starter.StarterFlow;
import com.digicube.starter.StarterOfferPayload;
import com.digicube.starter.StarterResultPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Partner Link screen, compact: one panel over a still-visible world. A single
 * viewport shows the candidate under the cursor, or the selected one, on a lit platform;
 * its name and kind sit beside it with the Link button; a strip of icon tiles below is
 * where the choice is made, and it pages once there are more tiles than fit. Two steps,
 * select then Link, so a misclick never binds a partner. Esc or the corner button puts
 * the choice off. The world keeps running behind it.
 */
public final class StarterScreen extends Screen {
    private static final int FADE_TICKS = 10;
    private static final int REVEAL_TICKS = 4;
    private static final int LINK_TIMEOUT_TICKS = 60;
    private static final int NOTICE_TICKS = 100;

    // Panel geometry in GUI units, validated at 320 x 240.
    private static final int PANEL_WIDTH = 196;
    private static final int PANEL_HEIGHT = 140;
    private static final int VIEWPORT = 72;
    private static final int VIEWPORT_X = 6;
    private static final int VIEWPORT_Y = 32;
    private static final int INFO_X = 84;
    private static final int INFO_WIDTH = 106;
    private static final int LINK_Y = 88;
    private static final int LINK_HEIGHT = 16;
    private static final int STRIP_Y = 114;
    private static final int TILE = 20;
    private static final int TILE_GAP = 3;
    private static final int ARROW_WIDTH = 10;
    private static final int MAX_VISIBLE_TILES = 6;
    private static final int CROSS = 13;

    private final StarterClient client;
    private final StarterOfferPayload offer;
    private final Map<Identifier, DigimonPreview> previews = new HashMap<>();
    private final List<Tile> tiles = new ArrayList<>();
    private PreviewPane pane;
    private LinkButton linkButton;
    private List<DigiPanels.DataCell> readout = List.of();
    private List<DigiPanels.DataCell> headerCells = List.of();
    private int panelX;
    private int panelY;
    /** What the viewport shows: the tile under the cursor, else the selection, else the last browsed one. */
    private int focused;
    private int browse;
    private int selected = -1;
    private int firstTile;
    private boolean linking;
    private int linkTicks;
    /** Linked or deferred: closing sends nothing and the offer is not shown again. */
    private boolean finished;
    private int age;
    private String notice = "";
    private int noticeTicks;
    // Preview motion, shared by whichever candidate is shown.
    private float yaw;
    private float yawO;
    private float face;
    private float faceO;
    private float lock;
    private float lockO;
    private int flashTicks;

    StarterScreen(StarterClient client, StarterOfferPayload offer) {
        super(Component.translatable("gui.digicube.starter.title"));
        this.client = client;
        this.offer = offer;
    }

    static Component name(Identifier species) {
        return DigimonSpeciesRegistry.get(species).map(DigimonSpecies::translationKey)
                .map(Component::translatable).orElseGet(() -> Component.literal(species.getPath()));
    }

    /** "Rookie · Vaccine", from the species sheet. */
    private static Component kind(Identifier species) {
        return DigimonSpeciesRegistry.get(species).map(sheet -> (Component) Component.translatable("gui.digicube.starter.kind",
                Component.translatable("digicube.stage." + sheet.stage().getId()),
                Component.translatable("digicube.attribute." + sheet.attribute().getId()))).orElse(Component.empty());
    }

    private Component option(int index) {
        return Component.translatable("gui.digicube.starter.option", name(offer.species().get(index)), index + 1, offer.species().size());
    }

    // --- layout ----------------------------------------------------------------------

    @Override
    protected void init() {
        tiles.clear();
        for (Identifier species : offer.species()) {
            if (!previews.containsKey(species)) {
                DigimonPreview preview = DigimonPreview.create(minecraft, species);
                if (preview != null) previews.put(species, preview);
            }
        }
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        int count = offer.species().size();
        int visible = Math.min(count, MAX_VISIBLE_TILES);
        firstTile = Math.clamp(firstTile, 0, Math.max(0, count - visible));
        int stripWidth = visible * TILE + (visible - 1) * TILE_GAP;
        int stripLeft = panelX + (PANEL_WIDTH - stripWidth) / 2;
        pane = addRenderableWidget(new PreviewPane(panelX + VIEWPORT_X, panelY + VIEWPORT_Y));
        for (int slot = 0; slot < visible; slot++) {
            tiles.add(addRenderableWidget(new Tile(firstTile + slot, stripLeft + slot * (TILE + TILE_GAP), panelY + STRIP_Y)));
        }
        if (count > visible) {
            ArrowButton previous = addRenderableWidget(new ArrowButton(panelX + 6, panelY + STRIP_Y, "<",
                    Component.translatable("gui.digicube.starter.previous"), () -> page(-1)));
            previous.active = firstTile > 0;
            ArrowButton next = addRenderableWidget(new ArrowButton(panelX + PANEL_WIDTH - 6 - ARROW_WIDTH, panelY + STRIP_Y, ">",
                    Component.translatable("gui.digicube.starter.next"), () -> page(1)));
            next.active = firstTile + visible < count;
        }
        linkButton = addRenderableWidget(new LinkButton(panelX + INFO_X, panelY + LINK_Y, INFO_WIDTH, LINK_HEIGHT));
        addRenderableWidget(new LaterButton(panelX + PANEL_WIDTH - 6 - CROSS, panelY + 6));
        // Data squares live where nothing is read: under the kind line and at the header's far end.
        readout = DigiPanels.dataCells(width * 31 + height, 35,
                List.of(new DigiPanels.Area(panelX + INFO_X, panelY + 58, INFO_WIDTH, 26)));
        headerCells = DigiPanels.dataCells(width * 17 + height, 50,
                List.of(new DigiPanels.Area(panelX + PANEL_WIDTH - 62, panelY + 6, 40, 8)));
        applyState();
    }

    private void page(int delta) {
        int count = offer.species().size();
        int visible = Math.min(count, MAX_VISIBLE_TILES);
        firstTile = Math.clamp(firstTile + delta * visible, 0, Math.max(0, count - visible));
        rebuildWidgets();
    }

    // --- state -----------------------------------------------------------------------

    private void select(int index) {
        if (linking || index < 0 || index >= offer.species().size()) return;
        browse = index;
        int visible = Math.min(offer.species().size(), MAX_VISIBLE_TILES);
        if (index < firstTile || index >= firstTile + visible) {
            firstTile = Math.clamp(index, 0, Math.max(0, offer.species().size() - visible));
            rebuildWidgets();
        }
        if (index == selected) return;
        selected = index;
        applyState();
    }

    private void link() {
        if (linking || selected < 0 || finished) return;
        linking = true;
        linkTicks = 0;
        noticeTicks = 0;
        flashTicks = DigiTheme.FLASH_TICKS;
        applyState();
        client.send(StarterChoicePayload.choose(offer.species().get(selected)));
    }

    /** Pushes selection and linking state into the widgets. */
    private void applyState() {
        for (Tile tile : tiles) tile.active = !linking;
        pane.active = !linking;
        linkButton.active = selected >= 0 && !linking && !finished;
        linkButton.setMessage(linking ? Component.translatable("gui.digicube.starter.linking")
                : selected < 0 ? Component.translatable("gui.digicube.starter.link_none")
                : Component.translatable("gui.digicube.starter.link", name(offer.species().get(selected))));
    }

    private void show(String messageKey) {
        notice = messageKey;
        noticeTicks = NOTICE_TICKS;
    }

    void receive(StarterResultPayload result) {
        if (result.ok()) {
            finished = true;
            client.clearPending();
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_ACTIVATE, 0.7F));
            onClose();
            return;
        }
        linking = false;
        show(result.messageKey());
        if (result.messageKey().equals(StarterFlow.Outcome.ALREADY_CHOSEN.translationKey())
                || result.messageKey().equals(StarterFlow.Eligibility.HAS_PARTNERS.translationKey())) {
            finished = true;
            client.clearPending();
            onClose();
            return;
        }
        applyState();
    }

    /** The tile under the cursor or holding keyboard focus, or -1. */
    private int hotTile() {
        for (Tile tile : tiles) if (tile.active && tile.isHoveredOrFocused()) return tile.index;
        return -1;
    }

    // --- lifecycle -------------------------------------------------------------------

    @Override
    public void tick() {
        age++;
        if (noticeTicks > 0) noticeTicks--;
        int hot = hotTile();
        if (hot >= 0) browse = hot;
        int shown = hot >= 0 ? hot : selected >= 0 ? selected : browse;
        if (shown != focused) {
            focused = shown;
            flashTicks = 0;
        }
        pane.setMessage(option(focused));
        yawO = yaw;
        faceO = face;
        lockO = lock;
        boolean chosen = selected >= 0 && focused == selected;
        boolean facing = chosen || hot >= 0 || (pane.active && pane.isHoveredOrFocused());
        float step = 1.0F / DigiTheme.TRANSITION_TICKS;
        face = Mth.approach(face, facing ? 1.0F : 0.0F, step);
        lock = Mth.approach(lock, chosen ? 1.0F : 0.0F, step);
        if (facing) yaw = Mth.rotLerp(0.35F, yaw, 0.0F);
        else if (!linking) yaw = Mth.wrapDegrees(yaw + DigiTheme.TURNTABLE_DEGREES_PER_TICK);
        if (flashTicks > 0) flashTicks--;
        previews.values().forEach(DigimonPreview::tick);
        if (linking && ++linkTicks > LINK_TIMEOUT_TICKS) {
            linking = false;
            show("gui.digicube.starter.no_answer");
            applyState();
        }
        // Death shows the death screen instead; removed() keeps the offer for after respawn.
        if (minecraft.player == null || !minecraft.player.isAlive()) minecraft.gui.setScreen(null);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (linking) return true;
        int digit = event.getDigit();
        if (digit >= 1 && digit <= offer.species().size()) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            select(digit - 1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean shouldCloseOnEsc() { return !linking; }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        if (!finished) {
            finished = true;
            client.send(StarterChoicePayload.defer());
        }
        super.onClose();
    }

    @Override
    public void removed() {
        previews.clear();
        if (!finished && minecraft.getConnection() != null) client.repend(offer);
        super.removed();
    }

    @Override
    public Component getNarrationMessage() {
        return Component.empty().append(title).append(". ")
                .append(Component.translatable("gui.digicube.starter.subtitle", offer.species().size()));
    }

    // --- drawing ---------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        float fade = Math.min(1.0F, (age + partialTick) / FADE_TICKS);
        DigiPanels.ground(graphics, width, height, Math.round(DigiTheme.GROUND_ALPHA * fade));
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (age < REVEAL_TICKS) return;
        float time = age + partialTick;
        int x = panelX;
        int y = panelY;
        DigiPanels.frame(graphics, x - 2, y - 2, PANEL_WIDTH + 4, PANEL_HEIGHT + 4, 0, DigiTheme.EDGE_DIM, DigiTheme.CHAMFER_PANEL + 1);
        DigiPanels.frame(graphics, x, y, PANEL_WIDTH, PANEL_HEIGHT, DigiTheme.PANEL, DigiTheme.EDGE, DigiTheme.CHAMFER_PANEL);
        graphics.text(font, Component.translatable("gui.digicube.starter.eyebrow"), x + 8, y + 7, DigiTheme.CYAN, false);
        graphics.text(font, Component.translatable("gui.digicube.starter.title").withStyle(ChatFormatting.BOLD), x + 8, y + 17, DigiTheme.WHITE, false);
        DigiPanels.dataSquares(graphics, headerCells, time);
        DigiPanels.separator(graphics, x + 6, y + 28, PANEL_WIDTH - 12);

        Identifier species = offer.species().get(focused);
        graphics.text(font, DigiPanels.shortText(font, name(species), INFO_WIDTH), x + INFO_X, y + 35, DigiTheme.WHITE, false);
        graphics.text(font, DigiPanels.shortText(font, kind(species), INFO_WIDTH), x + INFO_X, y + 47, DigiTheme.MUTED, false);
        DigiPanels.dataSquares(graphics, readout, time);
        DigiPanels.separator(graphics, x + 6, y + 110, PANEL_WIDTH - 12);

        // Server answers float under the panel, over the world, so the panel itself stays quiet.
        if (noticeTicks > 0) {
            graphics.centeredText(font, DigiPanels.shortText(font, Component.translatable(notice), width - 20),
                    x + PANEL_WIDTH / 2, y + PANEL_HEIGHT + 6, DigiTheme.AMBER);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    // --- widgets ---------------------------------------------------------------------

    /** The one viewport: grid, platform and the living preview of the focused candidate. */
    private final class PreviewPane extends AbstractButton {
        private final List<DigiPanels.DataCell> flashCells;

        PreviewPane(int x, int y) {
            super(x, y, VIEWPORT, VIEWPORT, option(focused));
            flashCells = DigiPanels.dataCells(x * 7 + y, 14, List.of(new DigiPanels.Area(x + 4, y + 4, VIEWPORT - 8, VIEWPORT - 8)));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            select(focused);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean chosen = selected >= 0 && focused == selected;
            boolean hot = isHoveredOrFocused() && active;
            int x = getX();
            int y = getY();
            int size = VIEWPORT;
            graphics.fill(x, y, x + size, y + size, DigiTheme.VOID);
            float faceNow = Mth.lerp(partialTick, faceO, face);
            int gridAlpha = flashTicks > 0 ? 0x90
                    : Math.round(Mth.lerp(faceNow, DigiTheme.GRID_ALPHA_REST, DigiTheme.GRID_ALPHA_HOT));
            DigiPanels.grid(graphics, x + 1, y + 1, size - 2, size - 2, DigiTheme.GRID_CELL_SMALL, DigiTheme.withAlpha(DigiTheme.GRID, gridAlpha));
            int footY = y + Math.round(size * DigiTheme.PREVIEW_FOOT_LINE);
            DigiPanels.platform(graphics, x + size / 2, footY - 2, size * 3 / 5,
                    chosen ? DigiTheme.GRID_BRIGHT : DigiTheme.withAlpha(DigiTheme.GRID, 0xA0));
            DigimonPreview preview = previews.get(offer.species().get(focused));
            if (preview != null) {
                float cx = x + size / 2.0F;
                float cy = y + size / 2.0F;
                boolean tracking = hot && !linking;
                float angleX = tracking ? (float) Math.atan((cx - mouseX) / 40.0F) * 20.0F * faceNow : 0.0F;
                float angleY = tracking ? (float) Math.atan((cy - mouseY) / 40.0F) * 20.0F * faceNow : 0.0F;
                float bodyYaw = Mth.rotLerp(partialTick, yawO, yaw) + angleX;
                preview.draw(graphics, x + 1, y + 1, x + size - 1, y + size - 1, footY, bodyYaw, angleX, -angleY, partialTick);
            }
            if (flashTicks > 0) DigiPanels.dataSquares(graphics, flashCells, (age + partialTick) * 4.0F);
            if (!active) graphics.fill(x, y, x + size, y + size, DigiTheme.withAlpha(DigiTheme.VOID, 0x66));
            int edge = chosen ? DigiTheme.AMBER : hot ? DigiTheme.CYAN : DigiTheme.EDGE_DIM;
            DigiPanels.frame(graphics, x, y, size, size, 0, edge, DigiTheme.CHAMFER_BUTTON);
            float lockNow = Mth.lerp(partialTick, lockO, lock);
            if (lockNow > 0.0F) {
                DigiPanels.brackets(graphics, x, y, size, size, 8, 1 + Math.round(3.0F * (1.0F - lockNow)),
                        DigiTheme.withAlpha(DigiTheme.AMBER, Math.round(255.0F * lockNow)));
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            if (selected >= 0 && focused == selected) {
                output.add(NarratedElementType.HINT, Component.translatable("gui.digicube.starter.selected"));
            }
        }
    }

    /** One candidate in the strip: its icon, cyan when shown, amber when chosen. */
    private final class Tile extends AbstractButton {
        final int index;
        private final Identifier species;

        Tile(int index, int x, int y) {
            super(x, y, TILE, TILE, option(index));
            this.index = index;
            this.species = offer.species().get(index);
            setTooltip(Tooltip.create(name(species)));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            select(index);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean chosen = index == selected;
            boolean hot = isHoveredOrFocused() && active;
            int x = getX();
            int y = getY();
            graphics.fill(x, y, x + TILE, y + TILE, chosen ? DigiTheme.PANEL_RAISED : DigiTheme.VOID);
            DigiPanels.icon(graphics, species, x + 1, y + 1, TILE - 2);
            if (!active) graphics.fill(x, y, x + TILE, y + TILE, DigiTheme.withAlpha(DigiTheme.VOID, 0x66));
            int edge = chosen ? DigiTheme.AMBER : hot || index == focused ? DigiTheme.CYAN : DigiTheme.EDGE_DIM;
            DigiPanels.frame(graphics, x, y, TILE, TILE, 0, edge, 1);
            if (chosen) DigiPanels.brackets(graphics, x, y, TILE, TILE, 5, 2, DigiTheme.AMBER);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            if (index == selected) output.add(NarratedElementType.HINT, Component.translatable("gui.digicube.starter.selected"));
        }
    }

    /** Pages the strip when there are more candidates than tiles. */
    private final class ArrowButton extends AbstractButton {
        private final String glyph;
        private final Runnable action;

        ArrowButton(int x, int y, String glyph, Component label, Runnable action) {
            super(x, y, ARROW_WIDTH, TILE, label);
            this.glyph = glyph;
            this.action = action;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            action.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            DigiPanels.ButtonState state = !active ? DigiPanels.ButtonState.DISABLED
                    : isHoveredOrFocused() ? DigiPanels.ButtonState.HOVER : DigiPanels.ButtonState.REST;
            DigiPanels.button(graphics, font, getX(), getY(), getWidth(), getHeight(), Component.literal(glyph), state);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** The one primary action. Disabled until a tile is selected. */
    private final class LinkButton extends AbstractButton {
        LinkButton(int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable("gui.digicube.starter.link_none"));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            link();
        }

        @Override
        public void playDownSound(SoundManager manager) {
            manager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT, 1.4F));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            DigiPanels.ButtonState state = !active ? DigiPanels.ButtonState.DISABLED
                    : isHoveredOrFocused() ? DigiPanels.ButtonState.PRIMARY_HOVER : DigiPanels.ButtonState.PRIMARY;
            DigiPanels.button(graphics, font, getX(), getY(), getWidth(), getHeight(), getMessage(), state);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** The corner cross: the mouse's way to decide later. */
    private final class LaterButton extends AbstractButton {
        LaterButton(int x, int y) {
            super(x, y, CROSS, CROSS, Component.translatable("gui.digicube.starter.later"));
            setTooltip(Tooltip.create(Component.translatable("gui.digicube.starter.hint")));
        }

        @Override
        public void onPress(InputWithModifiers input) {
            onClose();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean hot = isHoveredOrFocused() && active;
            DigiPanels.frame(graphics, getX(), getY(), getWidth(), getHeight(), DigiTheme.PANEL_RAISED,
                    hot ? DigiTheme.CYAN : DigiTheme.EDGE_DIM, 1);
            // A pixel cross, not a glyph: glyph metrics never centre in a box this small.
            int color = hot ? DigiTheme.WHITE : DigiTheme.MUTED;
            int left = getX() + (CROSS - 5) / 2;
            int top = getY() + (CROSS - 5) / 2;
            for (int i = 0; i < 5; i++) {
                graphics.fill(left + i, top + i, left + i + 1, top + i + 1, color);
                graphics.fill(left + 4 - i, top + i, left + 5 - i, top + i + 1, color);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
