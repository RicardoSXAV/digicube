package com.digicube.fabric.client.dev;

import com.digicube.dev.PlayerLoadout;
import com.digicube.dev.PlayerLoadouts;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** The player-items picker: one entry per {@link PlayerLoadouts} loadout, by play time. */
final class LoadoutDropdown extends Dropdown<PlayerLoadout> {
    LoadoutDropdown(Font font, int x, int y, int width, int height, PlayerLoadout initial, Consumer<PlayerLoadout> onSelect) {
        super(font, x, y, width, height, Component.translatable("gui.digicube.dev.loadout"), PlayerLoadouts.ALL, initial, onSelect);
    }

    @Override
    protected String label(PlayerLoadout loadout) {
        return loadout.label();
    }
}
