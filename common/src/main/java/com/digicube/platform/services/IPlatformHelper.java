package com.digicube.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

import java.nio.file.Path;

/**
 * Things every mod loader can do, but each does differently.
 *
 * <p>Common code calls these through {@link com.digicube.platform.Services#PLATFORM};
 * each loader module ships its own implementation. When you need something from
 * Fabric or NeoForge inside common code, add a method here rather than importing
 * the loader directly.
 */
public interface IPlatformHelper {

    /** Human readable name of the loader currently running, e.g. "Fabric". */
    String getPlatformName();

    /** @return true if a mod with the given id is installed. */
    boolean isModLoaded(String modId);

    /** @return true when running from a dev workspace rather than a shipped jar. */
    boolean isDevelopmentEnvironment();

    /** @return "development" or "production". */
    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /**
     * Registers the default attributes (max health, speed, ...) of a living entity type.
     * Vanilla keeps these in an immutable map, so each loader exposes its own hook.
     */
    void registerEntityAttributes(EntityType<? extends LivingEntity> type, AttributeSupplier.Builder attributes);

    /**
     * Sends a custom payload to one player, if that client registered its channel. Each
     * loader owns the transport; common code only builds payload records.
     */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /**
     * The directory the game runs in ({@code .minecraft}, or {@code fabric/runs/client} in a
     * dev run). The developer panel walks up from it to find the repository.
     */
    Path gameDirectory();
}
