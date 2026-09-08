package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.platform.Services;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * Server side of the developer panel: the gate, the dispatch and the reply. Only an
 * operator in a development environment gets past the gate; everyone else receives an
 * empty readout with the reason. Loader modules hand the payload here from their receiver.
 */
public final class DevPanel {
    private static final Predicate<CommandSourceStack> OPERATOR = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private DevPanel() {}

    /** Whether the panel works for this player at all; the client asks the same question by trying. */
    public static boolean allowed(ServerPlayer player) {
        return Services.PLATFORM.isDevelopmentEnvironment() && OPERATOR.test(player.createCommandSourceStack());
    }

    public static void handle(MinecraftServer server, ServerPlayer player, DevActionPayload payload) {
        if (!Services.PLATFORM.isDevelopmentEnvironment()) {
            reply(player, new CompoundTag(), "The developer panel only works in a development environment");
            return;
        }
        if (!OPERATOR.test(player.createCommandSourceStack())) {
            reply(player, new CompoundTag(), "The developer panel needs operator rights");
            return;
        }
        String result = DevActions.get(payload.action())
                .map(action -> action.run(server, player, payload.args()))
                .orElse("Unknown developer action: " + payload.action());
        if (!result.isEmpty()) Constants.LOG.info("Dev panel [{}] {}: {}", player.getName().getString(), payload.action(), result);
        reply(player, DevState.capture(server, player, payload.args()), result);
    }

    private static void reply(ServerPlayer player, CompoundTag state, String message) {
        Services.PLATFORM.sendToPlayer(player, new DevStatePayload(state, message));
    }
}
