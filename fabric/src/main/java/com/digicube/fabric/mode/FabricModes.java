package com.digicube.fabric.mode;

import com.digicube.Constants;
import com.digicube.mode.DigiCubeMode;
import com.digicube.mode.DigiCubeModePayload;
import com.digicube.mode.DigiCubeModes;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/** Fabric side of the DigiCube Mode: the enum game rule, its lock, and the server events. The rules live in {@link DigiCubeModes}. */
public final class FabricModes {
    private static GameRule<DigiCubeMode> rule;

    private FabricModes() {}

    /** {@code digicube:mode}: unset until Create World picks TAMER or CLASSIC. */
    public static GameRule<DigiCubeMode> rule() {
        if (rule == null) throw new IllegalStateException("The DigiCube Mode game rule is registered in FabricModes.init");
        return rule;
    }

    public static void init() {
        rule = GameRuleBuilder.forEnum(DigiCubeMode.UNSET).category(GameRuleCategory.MISC).buildAndRegister(Constants.id("mode"));
        GameRuleEvents.changeCallback(rule).register((value, server) -> DigiCubeModes.ruleChanged(server, value));
        PayloadTypeRegistry.clientboundPlay().register(DigiCubeModePayload.TYPE, DigiCubeModePayload.STREAM_CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(DigiCubeModes::adopt);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> DigiCubeModes.joined(server, handler.player));
    }
}
