package com.digicube.fabric.client.mode;

import com.digicube.Constants;
import com.digicube.mode.DigiCubeMode;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

/**
 * Development only, {@code DIGICUBE_MODE_SHOTS=1}: once the title screen is up, opens Create World and saves a
 * screenshot of each state of the DigiCube Mode section into the run's {@code screenshots} folder (none picked, the
 * cards up, Classic picked with and without them, the flash of a turned-away Enter), then quits. Inert otherwise.
 */
public final class ModeScreenShots {
    private static final String[] SHOTS = {"mode_unset", "mode_cards", "mode_cards_classic", "mode_classic", "mode_enter_refused"};
    private int step = -1, wait;
    private CreateWorldScreen screen;

    private ModeScreenShots() {}

    public static void init() {
        if (!"1".equals(System.getenv("DIGICUBE_MODE_SHOTS")) || !FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        ModeScreenShots shots = new ModeScreenShots();
        ClientTickEvents.END_CLIENT_TICK.register(shots::tick);
    }

    private void tick(Minecraft client) {
        if (wait-- > 0) return;
        if (step == -1) {
            if (!(client.gui.screen() instanceof TitleScreen) || !client.isGameLoadFinished()) return;
            CreateWorldScreen.openFresh(client, () -> {});
            step = 0;
            wait = 40;
            return;
        }
        if (screen == null && client.gui.screen() instanceof CreateWorldScreen create) screen = create;
        ModeSection section = screen == null ? null : ModeSection.section(screen);
        if (section == null) { Constants.LOG.error("[mode-shots] no DigiCube Mode section on screen"); client.stop(); return; }
        if (step > 0) Screenshot.grab(client.gameDirectory, SHOTS[step - 1] + ".png", client.gameRenderer.mainRenderTarget(), 1,
                message -> Constants.LOG.info("[mode-shots] {}", message.getString()));
        switch (step++) {
            case 0 -> {}
            case 1 -> { client.setLastInputType(InputType.KEYBOARD_TAB); screen.setFocused(section.info); }
            case 2 -> section.pick(DigiCubeMode.CLASSIC);
            case 3 -> { client.setLastInputType(InputType.MOUSE); screen.setFocused(null); }
            case 4 -> {
                section.pick(DigiCubeMode.UNSET);
                for (AbstractWidget widget : Screens.getWidgets(screen)) if (widget instanceof EditBox box) screen.setFocused(box);
                ModeSection.refuse(screen);
            }
            default -> { Constants.LOG.info("[mode-shots] done"); client.stop(); return; }
        }
        // the refused Enter's flash is on for its first three ticks
        wait = step == SHOTS.length ? 1 : 3;
    }
}
