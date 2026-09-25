package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.BoomerangEntity;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.ThrowerState;
import com.digicube.registry.DCEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dedicated-server checks of a thrower's mechanics and AI (Mojyamon), {@code DIGICUBE_SCENARIO=thrower_checks}: one
 * staged fixture after another on a walled stone floor, every combatant healed each tick, the game sprinting.
 *
 * <ul>
 * <li>{@code still}: a standing target. Bones are thrown, strike and are caught (at least 80 % of them); icicles are thrown.</li>
 * <li>{@code strafing}: a target walking to and fro across the line. The throws still land and 70 % come home to the hand.</li>
 * <li>{@code group}: three enemies in a rank. At least one flight strikes two or more of them.</li>
 * <li>{@code lost}: the thrower is held away from the catch; the bone drops, lies, and a new one grows 6 s later.</li>
 * <li>{@code fetch}: a bone dropped a few blocks off is walked to and picked up before the new one would grow.</li>
 * <li>{@code frozen}: a target held frozen draws a full charge, and the heavy spear lands.</li>
 * <li>{@code pace}: while an icicle is held the thrower never moves faster than its walk.</li>
 * <li>{@code far}: a target 22 blocks off, out of a tapped throw's reach, the thrower slowed to a crawl: it holds the
 * wind-up and a charged throw turns beyond the tap's reach and strikes.</li>
 * <li>{@code leap}: a target 30 blocks off, out of even a full charge's reach standing: the thrower takes off and throws
 * from the top of a leap, and it strikes.</li>
 * <li>{@code give}: {@code /digicube give mojyamon <player>} with no level, for a creative stand-in player: the partner
 * is deployed at the Champion level and is still out 60 ticks later (it used to be stored on its first tick).</li>
 * </ul>
 * Prints {@code [thrower-checks] <case> PASS|FAIL ...} per case and {@code [thrower-checks] RESULT n of n}.
 */
final class ThrowerScenario {
    private static final List<String> CASES = List.of("still", "strafing", "group", "lost", "fetch", "frozen", "pace", "far", "leap", "give");
    /** A slowed thrower's share of its pace: it cannot walk into a tap's reach in time. */
    private static final double CRAWL = .25;
    private static final Vec3 START = new Vec3(.5, 301, .5);
    private static final AABB ARENA = new AABB(-24, 296, -24, 25, 312, 25);
    private static int index = -1, ticks, passed, failed, lostAt = -1, regrownAt = -1;
    private static boolean initialized, done;
    private static DigimonEntity thrower;
    private static net.minecraft.server.level.ServerPlayer owner;
    private static final List<DigimonEntity> targets = new ArrayList<>();
    private static double fastestHold, walkPace;
    private static float heaviestHit;

    private ThrowerScenario() {}

    static void tick(ServerLevel level) {
        if (done) return;
        try {
            if (!initialized) {
                initialized = true;
                for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) level.setChunkForced(x, z, true);
                level.getServer().tickRateManager().requestGameToSprint(100_000);
                build(level);
                next(level);
            } else observe(level);
        } catch (RuntimeException | AssertionError e) {
            Constants.LOG.error("[thrower-checks] aborted {}", index < 0 || index >= CASES.size() ? "?" : CASES.get(index), e);
            failed++;
            finish(level);
        }
    }

    private static void build(ServerLevel level) {
        for (int x = -23; x <= 23; x++) for (int z = -23; z <= 23; z++) for (int y = 298; y <= 306; y++) {
            boolean wall = Math.abs(x) == 23 || Math.abs(z) == 23;
            level.setBlock(new BlockPos(x, y, z), (y < 301 || wall && y < 304 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
        }
    }

    private static DigimonEntity spawn(ServerLevel level, String species, Vec3 at) {
        var d = DigimonEntity.spawnWild(level, DigimonSpeciesRegistry.getOrThrow(Constants.id(species)), 20, at);
        if (d == null) throw new AssertionError("spawn " + species);
        d.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024); d.setHealth(d.getMaxHealth());
        return d;
    }

    private static void next(ServerLevel level) {
        level.getEntities((Entity) null, ARENA).forEach(e -> { if (!(e instanceof net.minecraft.world.entity.player.Player)) e.discard(); });
        targets.clear();
        if (++index >= CASES.size()) { finish(level); return; }
        String c = CASES.get(index);
        ticks = 0; lostAt = regrownAt = -1; fastestHold = 0; heaviestHit = 0;
        if (c.equals("give")) { startGive(level); return; }
        thrower = spawn(level, "mojyamon", START);
        thrower.setYRot(0); thrower.yBodyRot = thrower.yHeadRot = 0;
        var g = thrower.getLocomotion().groundGait();
        walkPace = g.fullSpeed(thrower.getBody().modelScale());
        switch (c) {
            case "group" -> {
                for (int i = 0; i < 3; i++) targets.add(spawn(level, "agumon", START.add(-2.5 + 2.5 * i, 0, 9 + (i == 1 ? 0 : 1.5))));
            }
            case "frozen" -> targets.add(spawn(level, "golemon", START.add(0, 0, 8)));
            case "far", "leap" -> {
                double gap = c.equals("far") ? 22 : 30;
                thrower.setPos(START.add(0, 0, -gap / 2));
                targets.add(spawn(level, "agumon", START.add(0, 0, gap / 2)));
                var speed = thrower.getAttribute(Attributes.MOVEMENT_SPEED);
                speed.setBaseValue(speed.getBaseValue() * CRAWL);
            }
            default -> targets.add(spawn(level, "agumon", START.add(0, 0, 9)));
        }
        for (var t : targets) { t.setNoAi(true); t.setTarget(thrower); }
        // The staged throws go by hand, before the AI has a target to plan against.
        if (!c.equals("lost") && !c.equals("fetch")) thrower.setTarget(targets.getFirst());
        Constants.LOG.info("[thrower-checks] case {} begins", c);
    }

    private static void observe(ServerLevel level) {
        String c = CASES.get(index);
        ticks++;
        if (c.equals("give")) { observeGive(level); return; }
        for (var t : targets) if (t.isAlive()) t.setHealth(t.getMaxHealth());
        thrower.setHealth(thrower.getMaxHealth());
        if (!thrower.isAlive() || targets.stream().anyMatch(t -> !t.isAlive())) { verdict(level, false, "a combatant died"); return; }
        var state = thrower.thrower();
        var target = targets.getFirst();
        switch (c) {
            case "strafing" -> {
                double x = 5 * Math.sin(ticks * .035);
                target.setPos(START.x + x, START.y, START.z + 9);
            }
            case "frozen" -> {
                target.addEffect(new MobEffectInstance(DCEffects.FROZEN, 40, 0, false, false));
            }
            case "lost", "fetch" -> {
                // Staged throw: the thrower is walked away from where the bone comes home.
                if (ticks == 20) state.startThrow(target, 0, c.equals("lost") ? 10 : 5, 1);
                var bone = state.bone();
                if (bone != null && bone.phase() == BoomerangEntity.Phase.FLYING && ticks > 32) {
                    // Keep the thrower pinned at the far side while the bone is in the air.
                    thrower.setPos(START.x - 4, START.y, START.z - 3);
                    thrower.setDeltaMovement(Vec3.ZERO);
                }
                if (c.equals("fetch") && bone != null && bone.phase() == BoomerangEntity.Phase.GROUNDED && thrower.getTarget() == null) thrower.setTarget(target);
                if (c.equals("lost")) {
                    // Hold it there until the new bone has grown: no fetching in this one.
                    if (bone != null && bone.phase() != BoomerangEntity.Phase.FLYING) { thrower.setPos(START.x - 4, START.y, START.z - 3); thrower.setDeltaMovement(Vec3.ZERO); }
                    if (lostAt < 0 && skill("bone_lost") > 0) lostAt = ticks;
                    if (regrownAt < 0 && skill("bone_regrown") > 0) regrownAt = ticks;
                }
            }
            default -> {}
        }
        if (state.stage() == ThrowerState.Stage.ICE_HOLD || state.stage() == ThrowerState.Stage.ICE_FORM) {
            double pace = thrower.position().subtract(thrower.xo, thrower.yo, thrower.zo).horizontalDistance();
            fastestHold = Math.max(fastestHold, pace);
        }
        switch (c) {
            case "still" -> { if (ticks >= 900) verdict(level, skill("bone_throw") >= 3 && skill("bone_caught") * 10 >= skill("bone_throw") * 8 && hits() >= 3 && skill("icicle_start") >= 1,
                    summary()); }
            case "strafing" -> { if (ticks >= 1100) verdict(level, skill("bone_throw") >= 3 && skill("bone_caught") * 10 >= skill("bone_throw") * 7 && hits() >= 2, summary()); }
            case "group" -> { if (skill("bone_multi_hit") >= 1) verdict(level, true, summary()); else if (ticks >= 900) verdict(level, false, summary()); }
            case "lost" -> {
                if (regrownAt > 0) verdict(level, lostAt > 0 && Math.abs(regrownAt - lostAt - 120) <= 3,
                        "lost at " + lostAt + ", regrown at " + regrownAt + " (" + (regrownAt - lostAt) + " ticks) " + summary());
                else if (ticks >= 500) verdict(level, false, "never regrown; lost at " + lostAt + " " + summary());
            }
            case "fetch" -> { if (skill("bone_picked_up") >= 1) verdict(level, skill("bone_regrown") == 0, summary()); else if (ticks >= 400) verdict(level, false, summary()); }
            case "frozen" -> { if (skill("icicle_hit_heavy") >= 1) verdict(level, true, summary()); else if (ticks >= 900) verdict(level, false, summary()); }
            case "pace" -> { if (ticks >= 900) verdict(level, skill("icicle_start") >= 2 && fastestHold <= walkPace * 1.2,
                    String.format("fastest while holding %.3f b/t (walk %.3f) ", fastestHold, walkPace) + summary()); }
            case "far" -> {
                boolean far = skill("bone_throw_charged") + skill("bone_throw_far") >= 1 && skill("bone_hit_out") + skill("bone_hit_back") >= 1;
                if (far) verdict(level, true, String.format("impulse %.2f, ", state.lastImpulse()) + summary());
                else if (ticks >= 700) verdict(level, false, summary());
            }
            case "leap" -> {
                boolean leapt = skill("thrower_leap") >= 1 && skill("bone_throw_air") >= 1 && skill("bone_hit_out") + skill("bone_hit_back") >= 1;
                if (leapt) verdict(level, state.lastImpulse() > 1.25F, String.format("impulse %.2f, ", state.lastImpulse()) + summary());
                else if (ticks >= 900) verdict(level, false, summary());
            }
            default -> verdict(level, false, "unknown case");
        }
    }

    private static void startGive(ServerLevel level) {
        var server = level.getServer();
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ThrowerTest");
        owner = new net.minecraft.server.level.ServerPlayer(server, level, profile, net.minecraft.server.level.ClientInformation.createDefault());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) {}
            @Override public boolean isConnected() { return true; }
            @Override public void flushChannel() {}
        };
        new net.minecraft.server.network.ServerGamePacketListenerImpl(server, connection, owner,
                net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        owner.setPos(START.x, START.y, START.z - 4); owner.setInvulnerable(true);
        server.getPlayerList().getPlayers().add(owner); server.getPlayerList().getPlayersByUUID().put(owner.getUUID(), owner);
        level.addNewPlayer(owner);
        owner.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "digicube give mojyamon ThrowerTest");
        Constants.LOG.info("[thrower-checks] case give begins");
    }

    private static void observeGive(ServerLevel level) {
        if (ticks < 60) return;
        var out = level.getEntitiesOfClass(DigimonEntity.class, ARENA, d -> d.isAlive() && d.isOwnedBy(owner));
        String detail = out.size() + " partner(s) out after 60 ticks" + (out.isEmpty() ? "" : ", level " + out.getFirst().getLevel());
        boolean pass = out.size() == 1 && out.getFirst().getLevel() >= com.digicube.digimon.Progression.CHAMPION_LEVEL;
        com.digicube.party.PartyManager.disconnect(owner);
        level.getServer().getPlayerList().getPlayers().remove(owner);
        level.getServer().getPlayerList().getPlayersByUUID().remove(owner.getUUID());
        owner.discard();
        verdict(level, pass, detail);
    }

    private static int skill(String name) { return thrower.skillUses().getOrDefault(name, 0); }
    private static int hits() { return skill("bone_hit_out") + skill("bone_hit_back") + skill("icicle_hit_light") + skill("icicle_hit_mid") + skill("icicle_hit_heavy"); }
    private static String summary() {
        var s = new StringBuilder();
        for (Map.Entry<String, Integer> e : thrower.skillUses().entrySet()) s.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        return "t=" + ticks + " " + s.toString().trim();
    }

    private static void verdict(ServerLevel level, boolean pass, String detail) {
        String c = CASES.get(index);
        if (pass) passed++; else failed++;
        Constants.LOG.info("[thrower-checks] {} {} {}", c, pass ? "PASS" : "FAIL", detail);
        next(level);
    }

    private static void finish(ServerLevel level) {
        done = true;
        Constants.LOG.info("[thrower-checks] RESULT {} of {} checks passed", passed, passed + failed);
        level.getServer().halt(false);
    }
}
