package com.digicube.fabric.client.mode;

import com.digicube.mode.DigiCubeMode;
import com.digicube.platform.Services;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The DigiCube Mode section at the foot of Create World's Game tab: the label with its info key, the note, and the
 * Classic and Tamer buttons, none picked at first. A pick writes the game rule {@code digicube:mode} into the screen's
 * {@link WorldCreationUiState}, which vanilla copies into the new world (the server side is
 * {@link com.digicube.mode.DigiCubeModes}). Until a mode is picked Create New World stays inactive, and Enter, which
 * would create the world, moves the focus to Classic and flashes both buttons instead.
 */
public final class ModeSection {
    /** The layout's units: the info key five after the label, the note three under it, the buttons six under that. */
    private static final int LABEL_GAP = 5, NOTE_GAP = 3, BUTTONS_GAP = 6, BUTTON_WIDTH = 101, BUTTON_HEIGHT = 20, BUTTON_SPACING = 8;
    /** How long both buttons flash after Enter was turned away, in client ticks: three blinks. */
    private static final int FLASH_TICKS = 18;
    private static final String CREATE_KEY = "selectWorld.create";

    private final WorldCreationUiState state;
    final InfoKey info = new InfoKey();
    final ModeButton classic, tamer;
    private int flash;

    private ModeSection(CreateWorldScreen screen) {
        this.state = screen.getUiState();
        this.classic = new ModeButton(this, DigiCubeMode.CLASSIC, BUTTON_WIDTH, BUTTON_HEIGHT);
        this.tamer = new ModeButton(this, DigiCubeMode.TAMER, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    /** Builds the section under the last of {@code grid}'s rows: the Game tab's, as the tab is built. */
    public static void addTo(GridLayout grid, CreateWorldScreen screen) {
        ModeSection section = new ModeSection(screen);
        int[] rows = {0};
        grid.visitChildren(child -> rows[0]++);
        grid.addChild(section.layout(Minecraft.getInstance().font), rows[0], 0);
    }

    private LinearLayout layout(Font font) {
        LinearLayout column = LinearLayout.vertical();
        LinearLayout label = LinearLayout.horizontal();
        label.addChild(new StringWidget(Component.translatable("gui.digicube.mode.label"), font), settings -> settings.paddingTop(2));
        label.addChild(SpacerElement.width(LABEL_GAP));
        label.addChild(info);
        column.addChild(label);
        column.addChild(SpacerElement.height(NOTE_GAP));
        column.addChild(new StringWidget(Component.translatable("gui.digicube.mode.note").withStyle(ChatFormatting.GRAY), font));
        column.addChild(SpacerElement.height(BUTTONS_GAP));
        LinearLayout buttons = LinearLayout.horizontal().spacing(BUTTON_SPACING);
        buttons.addChild(classic);
        buttons.addChild(tamer);
        column.addChild(buttons);
        return column;
    }

    /** The mode picked so far: {@link DigiCubeMode#UNSET} until a button (or the Game Rules screen) sets one. */
    DigiCubeMode picked() {
        return mode(state);
    }

    void pick(DigiCubeMode mode) {
        GameRules rules = state.getGameRules();
        rules.set(Services.PLATFORM.modeRule(), mode, null);
        state.setGameRules(rules);
        flash = 0;
    }

    /** Whether the buttons show the yellow flash now: on for three ticks, off for three, from the refusal on. */
    boolean flashing() {
        return flash > 0 && (FLASH_TICKS - flash) / 3 % 2 == 0;
    }

    private static DigiCubeMode mode(WorldCreationUiState state) {
        return state.getGameRules().get(Services.PLATFORM.modeRule());
    }

    // ---------- the screen's hooks ----------

    /** Wires a Create World screen up each time it is built: the create guard, the Enter guard and the cards. */
    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof CreateWorldScreen create)) return;
            ScreenEvents.afterTick(create).register(s -> tick(create));
            ScreenEvents.afterExtract(create).register((s, graphics, mouseX, mouseY, partial) -> {
                ModeSection section = section(create);
                if (section != null && section.info.open())
                    ModeCards.draw(graphics, Screens.getFont(create), create.width, section.info.getY(), section.picked());
            });
            ScreenKeyboardEvents.allowKeyPress(create).register((s, key) -> allowKey(create, key));
            tick(create);
        });
    }

    /** The section of the Game tab while it is on screen, found through its widgets; null on another tab. */
    static ModeSection section(CreateWorldScreen screen) {
        for (AbstractWidget widget : Screens.getWidgets(screen)) if (widget instanceof ModeButton button) return button.section;
        return null;
    }

    /** Create New World waits for a mode, and says so; the flash counts down. */
    private static void tick(CreateWorldScreen screen) {
        boolean chosen = mode(screen.getUiState()).chosen();
        for (AbstractWidget widget : Screens.getWidgets(screen)) {
            if (widget instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents text
                    && text.getKey().equals(CREATE_KEY)) {
                button.active = chosen;
                button.setTooltip(chosen ? null : Tooltip.create(Component.translatable("gui.digicube.mode.blocked")));
            }
        }
        ModeSection section = section(screen);
        if (section != null && section.flash > 0) section.flash--;
    }

    /**
     * Enter that would create the world while no mode is picked (in the name box, on the info key, on an inactive
     * button, on nothing that takes Enter itself) is turned away: the focus goes to Classic and both buttons flash.
     */
    private static boolean allowKey(CreateWorldScreen screen, KeyEvent key) {
        if (!key.isConfirmation() || mode(screen.getUiState()).chosen()) return true;
        // an active button takes Enter itself; an inactive one lets it fall through to the screen, which would create
        if (screen.getFocused() instanceof AbstractButton button && button.isActive()) return true;
        refuse(screen);
        return false;
    }

    /** A create request with no mode picked: the focus goes to Classic and both buttons flash. */
    static void refuse(CreateWorldScreen screen) {
        ModeSection section = section(screen);
        if (section == null) return;
        screen.setFocused(section.classic);
        section.flash = FLASH_TICKS;
    }
}
