package com.digicube.mode;

import com.digicube.Constants;
import com.digicube.platform.Services;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code DIGICUBE_SCENARIO=mode_checks}: the DigiCube Mode as world data on the dedicated dev server. The adoption
 * rule (a rule set by Create World, an unset rule, a world that already has its mode), the saved data and the payload
 * round trip, the saved file as the world list reads it, staging, the lock against a game rule change, and the join
 * line given once. The dev world's own mode is put back before the verdict.
 */
public final class ModeScenario {
    private static boolean done;
    private static int cases;

    private ModeScenario() {}

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static void pass(String name, String detail) {
        cases++;
        Constants.LOG.info("[scenario] PASS {} {}", name, detail);
    }

    public static void tick(ServerLevel level) {
        if (done || level != level.getServer().overworld()) return;
        done = true;
        MinecraftServer server = level.getServer();
        DigiCubeMode original = DigiCubeModes.of(server);
        String verdict;
        try {
            run(server);
            verdict = "PASS mode_checks cases=" + cases;
        } catch (AssertionError | RuntimeException failure) {
            Constants.LOG.error("[scenario] mode_checks failed", failure);
            verdict = "FAIL mode_checks " + failure.getMessage();
        } finally {
            DigiCubeModes.stage(server, original);
        }
        Constants.LOG.info("[scenario] {} (this world stays {})", verdict, DigiCubeModes.of(server));
        server.halt(false);
    }

    private static void run(MinecraftServer server) {
        check(DigiCubeModes.resolve(DigiCubeMode.UNSET) == DigiCubeMode.TAMER, "an unset rule plays as TAMER");
        check(DigiCubeModes.resolve(DigiCubeMode.TAMER) == DigiCubeMode.TAMER, "TAMER stays TAMER");
        check(DigiCubeModes.resolve(DigiCubeMode.CLASSIC) == DigiCubeMode.CLASSIC, "CLASSIC stays CLASSIC");
        pass("mode_resolve", "unset->TAMER, TAMER, CLASSIC");

        DigiCubeModeData created = new DigiCubeModeData();
        check(DigiCubeModes.adopt(created, DigiCubeMode.CLASSIC, 7) && created.mode() == DigiCubeMode.CLASSIC
                && !created.adopted() && created.setAt() == 7, "a world made in CLASSIC adopts CLASSIC");
        check(!DigiCubeModes.adopt(created, DigiCubeMode.TAMER, 9) && created.mode() == DigiCubeMode.CLASSIC
                && created.setAt() == 7, "a world with its mode keeps it whatever the rule says");
        DigiCubeModeData old = new DigiCubeModeData();
        check(DigiCubeModes.adopt(old, DigiCubeMode.UNSET, 3) && old.mode() == DigiCubeMode.TAMER && old.adopted(),
                "a world with no mode chosen (old, or a dedicated server's) adopts TAMER");
        pass("mode_adopt", "created CLASSIC kept; an unset world is TAMER, marked adopted");

        UUID tamer = UUID.randomUUID();
        check(created.greet(tamer) && !created.greet(tamer), "a tamer is greeted once");
        CompoundTag saved = (CompoundTag) DigiCubeModeData.CODEC.encodeStart(NbtOps.INSTANCE, created).getOrThrow();
        DigiCubeModeData loaded = DigiCubeModeData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        check(loaded.mode() == DigiCubeMode.CLASSIC && loaded.setAt() == 7 && !loaded.adopted() && loaded.greeted(tamer),
                "the saved mode, its time and the greeted tamers survive a save and load: " + saved);
        DigiCubeModeData empty = DigiCubeModeData.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        check(empty.mode() == DigiCubeMode.UNSET, "a file with no mode reads as unset, to be adopted");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        DigiCubeModePayload.STREAM_CODEC.encode(buffer, new DigiCubeModePayload(DigiCubeMode.CLASSIC));
        check(DigiCubeModePayload.STREAM_CODEC.decode(buffer).mode() == DigiCubeMode.CLASSIC, "the payload carries the mode");
        pass("mode_saved", "save and load, an empty file, the payload");

        Path folder = server.getWorldPath(LevelResource.ROOT).normalize();
        for (DigiCubeMode mode : List.of(DigiCubeMode.CLASSIC, DigiCubeMode.TAMER)) {
            DigiCubeModes.stage(server, mode);
            server.overworld().getDataStorage().saveAndJoin();
            check(DigiCubeModeData.read(folder) == mode, "the world list reads " + mode + " in " + DigiCubeModeData.file(folder));
        }
        try {
            Path scratch = Files.createTempDirectory("digicube-mode");
            try {
                check(DigiCubeModeData.read(scratch) == DigiCubeMode.UNSET, "a world with no saved mode shows none");
                Path unreadable = DigiCubeModeData.file(scratch);
                Files.createDirectories(unreadable.getParent());
                Files.writeString(unreadable, "not a saved mode");
                check(DigiCubeModeData.read(scratch) == DigiCubeMode.UNSET, "an unreadable file shows no mode");
            } finally {
                try (Stream<Path> paths = Files.walk(scratch)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        pass("mode_file", "CLASSIC and TAMER read back from the world's folder; no file and an unreadable one show none");

        for (DigiCubeMode mode : List.of(DigiCubeMode.CLASSIC, DigiCubeMode.TAMER)) {
            DigiCubeModes.stage(server, mode);
            check(DigiCubeModes.of(server) == mode && DigiCubeModes.rule(server) == mode, "staged " + mode);
            for (DigiCubeMode other : DigiCubeMode.values()) {
                if (other == mode) continue;
                server.getGameRules().set(Services.PLATFORM.modeRule(), other, server);
                check(DigiCubeModes.rule(server) == mode && DigiCubeModes.of(server) == mode,
                        "a /gamerule change to " + other + " is set back to " + mode);
            }
        }
        pass("mode_lock", "every change of the rule set back, in both modes");

        DigiCubeModeData world = DigiCubeModes.data(server);
        UUID joiner = UUID.randomUUID();
        check(!world.greeted(joiner) && world.greet(joiner) && !world.greet(joiner), "the join line is given once per tamer");
        world.forget(joiner);
        pass("mode_join", "the join line once");
    }
}
