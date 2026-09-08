package com.digicube.fabric.client.dev;

import com.digicube.Constants;
import com.digicube.dev.DevActionPayload;
import com.digicube.dev.DevActions;
import com.digicube.dev.DevStatePayload;
import com.digicube.digimon.Progression;
import com.digicube.platform.Services;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

import java.util.EnumSet;

/**
 * Client side of the developer panel. F6 shows or hides it as a passive overlay on the
 * left of the screen that stays while playing; F7 focuses it, opening the same panel as a
 * screen with live controls. This object holds everything both forms share: the latest
 * readout from the server, which sections are folded, the chosen species and level, and
 * the tuning edits not yet applied. The keys exist only in a development environment.
 */
public final class DevClient {
    /** Panel units to GUI units; the panel is drawn smaller than the rest of the interface. */
    public static final float SCALE = 0.7F;
    private static final int REFRESH_TICKS = 20;

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Constants.id("digicube"));
    /** Null outside a development environment. */
    public static final KeyMapping TOGGLE = key("key.digicube.dev_panel", InputConstants.KEY_F6);
    /** Null outside a development environment. */
    public static final KeyMapping FOCUS = key("key.digicube.dev_focus", InputConstants.KEY_F7);

    /** The foldable parts of the panel, in display order. */
    public enum Section {
        SPAWN("gui.digicube.dev.spawn"),
        PARTY("gui.digicube.dev.party"),
        TUNE("gui.digicube.dev.tune"),
        WORLD("gui.digicube.dev.world");

        final String labelKey;

        Section(String labelKey) { this.labelKey = labelKey; }
    }

    private CompoundTag state = new CompoundTag();
    private String reply = "";
    private boolean stateReceived;
    private boolean visible;
    private final EnumSet<Section> collapsed = EnumSet.noneOf(Section.class);
    // Remembered across openings and worlds: the species list is static, so this is safe.
    private Identifier species;
    private int level = Progression.MIN_LEVEL;
    private CompoundTag edits = new CompoundTag();
    private int ticks;

    private static KeyMapping key(String name, int defaultKey) {
        if (!Services.PLATFORM.isDevelopmentEnvironment()) return null;
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, defaultKey, CATEGORY));
    }

    public void init() {
        ClientPlayNetworking.registerGlobalReceiver(DevStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    state = payload.state();
                    stateReceived = true;
                    if (!payload.reply().isEmpty()) reply = payload.reply();
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            state = new CompoundTag();
            reply = "";
            stateReceived = false;
            edits = new CompoundTag();
        });
        if (TOGGLE == null) return;
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Constants.id("dev_panel"),
                (graphics, delta) -> drawOverlay(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        while (TOGGLE.consumeClick()) visible = !visible;
        while (FOCUS.consumeClick()) {
            if (client.gui.screen() == null && client.player != null) {
                visible = true;
                client.gui.setScreen(new DevPanelScreen(this));
            }
        }
        boolean focused = client.gui.screen() instanceof DevPanelScreen;
        if (client.player != null && (visible || focused) && ++ticks % REFRESH_TICKS == 0) refresh();
    }

    private void drawOverlay(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!visible || client.player == null || client.gui.screen() instanceof DevPanelScreen || client.gui.hud.isHidden()) return;
        graphics.pose().pushMatrix();
        graphics.pose().scale(SCALE, SCALE);
        DevPanelView.draw(graphics, client.font, this, DevPanelView.layout(this), false);
        graphics.pose().popMatrix();
    }

    // --- readout ---------------------------------------------------------------------

    CompoundTag state() { return state; }
    String reply() { return reply; }
    boolean stateReceived() { return stateReceived; }
    boolean visible() { return visible; }
    void setVisible(boolean visible) { this.visible = visible; }

    boolean collapsed(Section section) { return collapsed.contains(section); }

    void toggle(Section section) {
        if (!collapsed.remove(section)) collapsed.add(section);
    }

    // --- choices ---------------------------------------------------------------------

    Identifier species() { return species; }

    void setSpecies(Identifier species) {
        if (species != null && !species.equals(this.species)) {
            this.species = species;
            edits = new CompoundTag();
            refresh();
        }
    }

    int level() { return level; }
    void setLevel(int level) { this.level = Progression.clampLevel(level); }

    /** Tuning values typed or stepped but not yet applied, by {@link com.digicube.dev.SpeciesTuning} key. */
    CompoundTag edits() { return edits; }

    void edit(String key, double value) {
        edits.putDouble(key, value);
    }

    void clearEdits() {
        edits = new CompoundTag();
    }

    // --- transport -------------------------------------------------------------------

    /** The species argument every request carries, so the readout includes its tuning numbers. */
    CompoundTag speciesArgs() {
        CompoundTag args = new CompoundTag();
        if (species != null) args.putString(DevActions.SPECIES_ARG, species.toString());
        return args;
    }

    void refresh() {
        send(DevActions.REFRESH, speciesArgs());
    }

    void send(String action, CompoundTag args) {
        if (ClientPlayNetworking.canSend(DevActionPayload.TYPE)) ClientPlayNetworking.send(new DevActionPayload(action, args));
    }
}
