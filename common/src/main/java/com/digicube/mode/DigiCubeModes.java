package com.digicube.mode;

import com.digicube.Constants;
import com.digicube.platform.Services;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The one place every rule reads the world's DigiCube Mode from (TAMER or CLASSIC), and the mode's whole life on the
 * server.
 *
 * <p>The choice travels from Create World to the new world in the game rule {@code digicube:mode}
 * ({@link com.digicube.platform.services.IPlatformHelper#modeRule}), which vanilla copies into the world it creates.
 * On the world's first start {@link #adopt} writes it into {@link DigiCubeModeData}, the authority from then on; a
 * world whose rule was never set (an old world, a dedicated server's) plays as TAMER. The mode never changes after
 * that: {@link #ruleChanged} sets the rule straight back. Server code only; the client learns the mode from
 * {@link DigiCubeModePayload} when its tamer joins.
 */
public final class DigiCubeModes {

    private DigiCubeModes() {}

    /** The world's mode, TAMER or CLASSIC. */
    public static DigiCubeMode of(MinecraftServer server) {
        DigiCubeModeData data = data(server);
        if (!data.mode().chosen()) adopt(server);
        return data.mode();
    }

    public static boolean tamer(MinecraftServer server) {
        return of(server) == DigiCubeMode.TAMER;
    }

    public static boolean classic(MinecraftServer server) {
        return of(server) == DigiCubeMode.CLASSIC;
    }

    /** What a world with no mode yet plays as: the mode Create World set in its rule, else TAMER. */
    public static DigiCubeMode resolve(DigiCubeMode rule) {
        return rule.chosen() ? rule : DigiCubeMode.TAMER;
    }

    /**
     * The world's first start with modes: its mode is written from the rule ({@link #resolve}), then the rule is made
     * to show it. Later starts only keep the rule in step. The loader calls this once the server has started.
     */
    public static void adopt(MinecraftServer server) {
        DigiCubeModeData data = data(server);
        if (adopt(data, rule(server), System.currentTimeMillis())) {
            Constants.LOG.info("This world's DigiCube Mode is {}{}.", data.mode(),
                    data.adopted() ? ", since no mode was chosen when it was created" : "");
        }
        if (rule(server) != data.mode()) setRule(server, data.mode());
    }

    /** @return whether {@code data} had no mode yet and now has one, from {@code rule} ({@link #resolve}) */
    static boolean adopt(DigiCubeModeData data, DigiCubeMode rule, long now) {
        if (data.mode().chosen()) return false;
        data.write(resolve(rule), !rule.chosen(), now);
        return true;
    }

    /**
     * The lock. The loader calls this whenever the rule changes ({@code /gamerule}, the in-world Game Rules screen):
     * a value other than the world's mode is set back at once, and the operators online are told.
     */
    public static void ruleChanged(MinecraftServer server, DigiCubeMode value) {
        if (server.overworld() == null) return; // still loading: adopt() runs once the server has started
        DigiCubeModeData data = data(server);
        if (!data.mode().chosen() || value == data.mode()) return;
        setRule(server, data.mode());
        Component message = Component.translatable("chat.digicube.mode.locked", name(data.mode()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) {
                player.sendSystemMessage(message);
            }
        }
        Constants.LOG.info("DigiCube Mode can't be changed after creation; the game rule is back to {}.", data.mode());
    }

    /** A tamer joined: their client learns the mode, and the first time in this world they read it in chat. */
    public static void joined(MinecraftServer server, ServerPlayer player) {
        DigiCubeMode mode = of(server);
        Services.PLATFORM.sendToPlayer(player, new DigiCubeModePayload(mode));
        if (data(server).greet(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("chat.digicube.mode.joined", name(mode)));
        }
    }

    /** The mode's name as players read it: Tamer, Classic. */
    public static Component name(DigiCubeMode mode) {
        return Component.translatable(mode.translationKey());
    }

    /**
     * Headless scenarios only: puts the world in {@code mode} for a staged check, past the lock. Play never calls
     * this; a scenario that changes the dev world's mode puts the old one back before it ends.
     */
    public static void stage(MinecraftServer server, DigiCubeMode mode) {
        data(server).write(mode, false, System.currentTimeMillis());
        if (rule(server) != mode) setRule(server, mode);
    }

    static DigiCubeModeData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(DigiCubeModeData.TYPE);
    }

    static DigiCubeMode rule(MinecraftServer server) {
        return server.getGameRules().get(Services.PLATFORM.modeRule());
    }

    static void setRule(MinecraftServer server, DigiCubeMode mode) {
        server.getGameRules().set(Services.PLATFORM.modeRule(), mode, server);
    }
}
