package com.digicube.dev;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * One tool of the developer panel, run on the server thread for an operator in a
 * development environment. Returns the one-line reply the panel shows; the readout is
 * captured separately after every action, so an action never has to describe state.
 */
@FunctionalInterface
public interface DevAction {
    String run(MinecraftServer server, ServerPlayer player, CompoundTag args);
}
