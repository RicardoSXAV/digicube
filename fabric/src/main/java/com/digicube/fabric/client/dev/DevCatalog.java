package com.digicube.fabric.client.dev;

import com.digicube.dev.DevActions;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.function.Supplier;

/**
 * Everything the developer panel shows, declared in one place. Battle Testing is real. Every
 * section marked {@code example()} is a placeholder from the design pitch: its values live
 * only in the panel and change nothing in the game. Wire one by giving its rows a
 * {@link DevValue} that reads and writes the real number, and dropping the mark.
 */
final class DevCatalog {
    private static final Supplier<String> NOT_WIRED = () -> "EXAMPLE · NOTHING WAS WRITTEN";

    private DevCatalog() {}

    static void declare(DevClient client) {
        DevTabs tabs = client.tabs;

        client.battleSection = tabs.tab("COMBAT").section("BATTLE TESTING")
                .body(new BattleTestingBody(client))
                .keywords("fighter a", "fighter b", "fighter level", "start battle", "clear fighters")
                .note(() -> client.fighterA == null || client.fighterB == null ? "PICK BOTH FIGHTERS" : client.fighting() ? "A FIGHT IS RUNNING" : "SPAWNS IN FRONT OF YOU")
                .action("CLEAR", () -> true, () -> { client.send(DevActions.BATTLE_CLEAR, new CompoundTag()); return ""; })
                .primary("START BATTLE", () -> client.fighterA != null && client.fighterB != null, () -> {
                    CompoundTag args = new CompoundTag();
                    args.putString(DevActions.SPECIES_A_ARG, client.fighterA.toString());
                    args.putString(DevActions.SPECIES_B_ARG, client.fighterB.toString());
                    args.putInt(DevActions.LEVEL_A_ARG, client.levelA);
                    args.putInt(DevActions.LEVEL_B_ARG, client.levelB);
                    client.send(DevActions.BATTLE_START, args);
                    // Out of the way: the fight is the thing to watch, and the readout takes over.
                    Minecraft.getInstance().gui.setScreen(null);
                    return "";
                });

        tabs.tab("COMBAT")
                .section("DAMAGE").example()
                .number("ATTRIBUTE ADVANTAGE", 1, 3, 0.05, "x", DevValue.local(1.5))
                .number("LEVEL SCALING", 0, 0.2, 0.01, "", DevValue.local(0.04))
                .number("CRITICAL CHANCE", 0, 50, 1, "%", DevValue.local(5))
                .toggle("SHOW DAMAGE NUMBERS", DevValue.local(0))
                .tuning(NOT_WIRED)
                .section("ATTACK TIMING").example()
                .number("GLOBAL COOLDOWN", 0, 100, 1, "t", DevValue.local(20))
                .number("WINDUP SCALE", 0.25, 2, 0.05, "x", DevValue.local(1))
                .choice("TARGET PICK", List.of("NEAREST", "WEAKEST", "LAST HIT"), DevValue.local(0))
                .tuning(NOT_WIRED)
                .section("HITBOXES").example()
                .toggle("DRAW HIT PARTS", DevValue.local(0))
                .toggle("DRAW ATTACK REACH", DevValue.local(0));

        tabs.tab("MOVEMENT")
                .section("SPECIES SPEEDS").example()
                .number("WALK", 0, 1, 0.01, "", DevValue.local(0.22))
                .number("RUN", 0, 1, 0.01, "", DevValue.local(0.34))
                .number("SWIM", 0, 1, 0.01, "", DevValue.local(0.18))
                .number("FLIGHT", 0, 1, 0.01, "", DevValue.local(0.4))
                .tuning(NOT_WIRED)
                .section("FOLLOW").example()
                .number("START DISTANCE", 1, 32, 1, "b", DevValue.local(6))
                .number("TELEPORT DISTANCE", 8, 64, 1, "b", DevValue.local(24))
                .tuning(NOT_WIRED);

        tabs.tab("EVOLUTION")
                .section("DIGISOUL").example()
                .number("CHARGE PER SECOND", 0, 100, 1, "", DevValue.local(10))
                .number("EVOLVE FEE", 0, 3600, 50, "", DevValue.local(900))
                .number("COOLDOWN", 0, 120, 1, "s", DevValue.local(10))
                .tuning(NOT_WIRED);

        tabs.tab("SPAWNS")
                .section("WILD DENSITY").example()
                .number("CAP PER PLAYER", 0, 64, 1, "", DevValue.local(12))
                .number("SPAWN RADIUS", 16, 128, 4, "b", DevValue.local(48))
                .tuning(NOT_WIRED);

        tabs.tab("PARTY")
                .section("RECOVERY").example()
                .number("REST TIME", 0, 600, 10, "s", DevValue.local(120))
                .number("RESERVE REGEN", 0, 10, 0.5, "/s", DevValue.local(1))
                .tuning(NOT_WIRED);

        tabs.tab("WORLD")
                .section("TIME").example()
                .toggle("FREEZE DAYLIGHT", DevValue.local(0))
                .toggle("FREEZE WEATHER", DevValue.local(0));

        tabs.tab("AUDIO")
                .section("EVOLUTION MIX").example()
                .number("MUSIC DUCK", 0, 1, 0.05, "", DevValue.local(0.4))
                .tuning(NOT_WIRED);
    }
}
