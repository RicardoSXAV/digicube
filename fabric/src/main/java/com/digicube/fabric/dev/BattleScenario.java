package com.digicube.fabric.dev;

import com.digicube.Constants;
import com.digicube.dev.BattleRoster;
import com.digicube.dev.BattleTest;
import com.digicube.dev.DevActions;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Headless check of the developer panel's Battle Testing with sides of several Digimon:
 * {@code DIGICUBE_SCENARIO=battle_checks} has a fake player stage three Gotsumon and an Agumon against a Golemon on a
 * stone floor at y=300, then checks the sides (sizes, team-mates spared, formation without overlaps, every fighter on
 * an enemy), that a right click puts the player in the Golemon's saddle while a wild Golemon outside the fight refuses
 * it, that the ridden Golemon is left to its rider and the player counts as its side, and that the fight ends with a
 * winner. The verdict line starts with {@code [battle-checks] RESULT}.
 */
public final class BattleScenario {
    private static final String NAME = System.getenv("DIGICUBE_SCENARIO");
    private static final int SETUP = 20, AIMED = SETUP + 40, DISMOUNT = SETUP + 120, TIMEOUT = SETUP + 20 * 180;

    private static int tick;
    private static ServerPlayer player;
    private static DigimonEntity outsider;
    private static boolean done;
    private static final List<String> passed = new ArrayList<>(), failures = new ArrayList<>();

    private BattleScenario() {}

    public static void tick(ServerLevel level) {
        if (done || !"battle_checks".equals(NAME) || level.dimension() != Level.OVERWORLD || !Services.PLATFORM.isDevelopmentEnvironment()) return;
        try {
            tick++;
            if (tick == 1) {
                for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) level.setChunkForced(cx, cz, true);
            } else if (tick == SETUP) {
                setup(level);
            } else if (tick == AIMED) {
                aimed();
            } else if (tick == DISMOUNT) {
                player.stopRiding();
            } else if (tick == DISMOUNT + 20) {
                DigimonEntity golemon = BattleTest.fighters(player, BattleTest.SIDE_B).getFirst();
                check(!golemon.isAlive() || golemon.getTarget() instanceof DigimonEntity enemy && enemy.battleSide() == 1,
                        "off its back, the Golemon takes an enemy again");
            } else if (tick > DISMOUNT + 20) {
                String winner = BattleTest.winner(player);
                if (!winner.isEmpty()) {
                    check(winner.equals(BattleTest.SIDE_A) || winner.equals(BattleTest.SIDE_B), "the fight ends with a winner (" + winner + ") after " + (tick - SETUP) + " ticks");
                    String cleared = BattleTest.clear(level.getServer(), player, new CompoundTag());
                    check(cleared.startsWith("Removed") && BattleTest.fighters(player, BattleTest.SIDE_A).isEmpty(), "CLEAR removes the fight: " + cleared);
                    finish(level);
                } else if (tick >= TIMEOUT) {
                    failures.add("no winner after " + (TIMEOUT - SETUP) / 20 + " s");
                    finish(level);
                }
            }
        } catch (RuntimeException e) {
            Constants.LOG.error("[battle-checks] aborted", e);
            failures.add("aborted: " + e);
            finish(level);
        }
    }

    private static void setup(ServerLevel level) {
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            level.setBlock(new BlockPos(x, 299, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 300; y < 312; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        player = new RidingPlayer(level);
        player.snapTo(0.5, 300, -12.5, 0, 0);
        player.setYHeadRot(0);

        CompoundTag args = new CompoundTag();
        args.put(DevActions.SIDE_A_ARG, BattleRoster.write(List.of(new BattleRoster.Entry("gotsumon", 20, 3), new BattleRoster.Entry("agumon", 20, 1))));
        args.put(DevActions.SIDE_B_ARG, BattleRoster.write(List.of(new BattleRoster.Entry("golemon", 30, 1))));
        String reply = BattleTest.start(level.getServer(), player, args);
        Constants.LOG.info("[battle-checks] staged: {}", reply);
        List<DigimonEntity> a = BattleTest.fighters(player, BattleTest.SIDE_A), b = BattleTest.fighters(player, BattleTest.SIDE_B);
        check(a.size() == 4 && b.size() == 1, "sides of 4 and 1 are staged (" + a.size() + " vs " + b.size() + ")");
        check(a.stream().allMatch(f -> f.battleSide() == 1) && b.getFirst().battleSide() == 2, "every fighter carries its side");
        check(a.get(0).isAllyOf(a.get(3)) && !a.get(0).isAllyOf(b.getFirst()) && !b.getFirst().isAllyOf(a.get(1)), "team-mates spare each other, enemies do not");
        boolean apart = true;
        for (int i = 0; i < a.size(); i++) for (int j = i + 1; j < a.size(); j++) apart &= !a.get(i).getBoundingBox().intersects(a.get(j).getBoundingBox());
        check(apart && a.stream().allMatch(f -> !f.getBoundingBox().intersects(b.getFirst().getBoundingBox())), "nobody is staged inside another");

        // riding: a staged fighter takes a right click, a wild one outside the fight does not
        outsider = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.resolve("golemon").orElseThrow(), 30, new Vec3(18.5, 300, 18.5));
        InteractionResult refused = outsider.interact(player, InteractionHand.MAIN_HAND, outsider.position());
        check(!refused.consumesAction() && player.getVehicle() == null, "a wild Golemon outside Battle Testing refuses the right click");
        DigimonEntity golemon = b.getFirst();
        InteractionResult taken = golemon.interact(player, InteractionHand.MAIN_HAND, golemon.position());
        check(taken.consumesAction() && player.getVehicle() == golemon && golemon.rider() == player && golemon.getControllingPassenger() == player,
                "a right click on the staged Golemon puts the player in the saddle, with the reins");
        check(golemon.isAllyOf(player) && !a.getFirst().isAllyOf(player), "the rider counts as the mount's side");
    }

    private static void aimed() {
        List<DigimonEntity> a = BattleTest.fighters(player, BattleTest.SIDE_A);
        DigimonEntity golemon = BattleTest.fighters(player, BattleTest.SIDE_B).getFirst();
        check(a.stream().filter(DigimonEntity::isAlive).allMatch(f -> f.getTarget() == golemon), "after the bell every fighter of side A is on the Golemon");
        check(golemon.rider() == player && golemon.getTarget() == null, "the ridden Golemon is left to its rider");
    }

    /**
     * Fabric's fake player refuses every ride, so this one seats itself the way {@code rider_checks} does, but only
     * where the mount's own passenger rule ({@code canAddPassenger}) lets it: the rule under test decides.
     */
    private static final class RidingPlayer extends FakePlayer {
        RidingPlayer(ServerLevel level) { super(level, new GameProfile(UUID.randomUUID(), "BattleTester")); }

        @Override
        public boolean startRiding(Entity vehicle, boolean force, boolean sendEvent) {
            try {
                var rule = Entity.class.getDeclaredMethod("canAddPassenger", Entity.class);
                rule.setAccessible(true);
                if (!force && !(boolean) rule.invoke(vehicle, this)) return false;
                var seat = Entity.class.getDeclaredField("vehicle");
                var riders = Entity.class.getDeclaredField("passengers");
                seat.setAccessible(true);
                riders.setAccessible(true);
                seat.set(this, vehicle);
                riders.set(vehicle, com.google.common.collect.ImmutableList.of(this));
                return true;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot seat the fake rider", e);
            }
        }

        @Override
        public void stopRiding() {
            Entity vehicle = getVehicle();
            if (vehicle == null) return;
            try {
                var seat = Entity.class.getDeclaredField("vehicle");
                var riders = Entity.class.getDeclaredField("passengers");
                seat.setAccessible(true);
                riders.setAccessible(true);
                seat.set(this, null);
                riders.set(vehicle, com.google.common.collect.ImmutableList.of());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot unseat the fake rider", e);
            }
        }
    }

    private static void check(boolean condition, String what) {
        (condition ? passed : failures).add(what);
        Constants.LOG.info("[battle-checks] {} {}", condition ? "ok" : "FAIL", what);
    }

    private static void finish(ServerLevel level) {
        done = true;
        if (outsider != null) outsider.discard();
        Constants.LOG.info("[battle-checks] RESULT {} of {} checks passed{}", passed.size(), passed.size() + failures.size(),
                failures.isEmpty() ? "" : "; failed: " + String.join(" | ", failures));
        level.getServer().halt(false);
    }
}
