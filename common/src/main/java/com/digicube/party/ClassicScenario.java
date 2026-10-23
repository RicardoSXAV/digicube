package com.digicube.party;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttribute;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.EvolutionState;
import com.digicube.digimon.EvolutionTimeline;
import com.digicube.digimon.Progression;
import com.digicube.digimon.RaisingPlace;
import com.digicube.entity.DigimonEntity;
import com.digicube.mode.DigiCubeMode;
import com.digicube.mode.DigiCubeModes;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.UUID;

/**
 * {@code DIGICUBE_SCENARIO=classic_checks}: CLASSIC's dynamic digivolution on the dedicated dev server, with a stand-in
 * tamer. A Koromon raised against Data in the water waits out a fight at level 10, then grows by itself into Betamon,
 * its record starting over; the Betamon, raised against Virus in the forest, digivolves into Dinohyumon when its
 * tamer asks, and stays bound to it whatever its record says next; a defeat in CLASSIC records the foe and where, by
 * day and by night, and one in TAMER records nothing. The dev world's own mode is put back before the verdict.
 */
public final class ClassicScenario {
    private static final Identifier KOROMON = Constants.id("koromon"), BETAMON = Constants.id("betamon"),
            DINOHYUMON = Constants.id("dinohyumon"), SEADRAMON = Constants.id("seadramon");
    private static boolean started, done;
    private static ServerPlayer owner;
    private static PartySavedData data;
    private static PartyMember member;
    private static DigimonEntity partner, prey;
    private static DigiCubeMode original;
    private static int step, waitUntil, cases;

    private ClassicScenario() {}

    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label + " step=" + step); }
    private static void pass(String name, String detail) { cases++; Constants.LOG.info("[scenario] PASS {} {}", name, detail); }

    public static void tick(ServerLevel level) {
        if (done) return;
        try {
            if (!started) { setup(level); return; }
            owner.tickCount++;
            owner.setHealth(owner.getMaxHealth());
            int now = level.getServer().getTickCount();
            if (now % 10 == 0) level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(-18, 298, -18, 18, 315, 18))
                    .stream().filter(m -> m != partner && m != prey).forEach(net.minecraft.world.entity.Entity::discard);
            if (now < waitUntil) return;
            switch (step++) {
                case 0 -> {
                    // A Koromon raised against Data in the water, held in a fight when it reaches level 10.
                    partner = give(level, KOROMON, 9);
                    for (int i = 0; i < 3; i++) partner.evolution().raising.win(DigimonAttribute.DATA, EnumSet.of(RaisingPlace.WATER));
                    prey = wild(level, Constants.id("gotsumon"), 9, 6);
                    prey.setInvulnerable(true);
                    partner.setTarget(prey);
                    check(PartyEvolution.action(owner, member.id(), "level", 10, null).isEmpty(), "dev level 10");
                    waitUntil = now + 60;
                }
                case 1 -> {
                    check(partner.getSpeciesId().equals(KOROMON) && !partner.evolution().transitioning(), "CLASSIC: no growth in the middle of a fight");
                    prey.discard();
                    prey = null;
                    partner.setTarget(null);
                    waitUntil = now + Progression.FIELD_REGEN_DELAY_TICKS + 40;
                }
                case 2 -> {
                    check(partner.evolution().transitioning() && Constants.id("betamon").equals(partner.evolution().target), "CLASSIC: calm again, it grows by itself, into Betamon");
                    waitUntil = now + EvolutionTimeline.LONG.duration();
                }
                case 3 -> {
                    EvolutionState s = partner.evolution();
                    check(partner.getSpeciesId().equals(BETAMON) && partner.getLevel() == 10 && s.phase == EvolutionState.Phase.RESTING, "CLASSIC: grown into Betamon at level 10");
                    check(s.raising.isEmpty() && s.line == null, "CLASSIC: the Rookie's record starts over, nothing bound");
                    pass("classic_growth", "Koromon raised against Data in the water waits out a fight at 10, then grows by itself into Betamon");
                    // The Betamon, raised against Virus in the forest, at level 20.
                    for (int i = 0; i < 3; i++) s.raising.win(DigimonAttribute.VIRUS, EnumSet.of(RaisingPlace.FOREST));
                    check(PartyEvolution.action(owner, member.id(), "level", 20, null).isEmpty(), "dev level 20");
                    check(PartyEvolution.action(owner, member.id(), "evolve", 0, SEADRAMON).endsWith("raised"), "CLASSIC: the tamer cannot name another form");
                    check(PartyEvolution.action(owner, member.id(), "evolve", 0, null).isEmpty(), "CLASSIC: digivolve, the raising picks");
                    check(DINOHYUMON.equals(partner.evolution().target) && partner.evolution().charge == Progression.DIGISOUL_CAPACITY - Progression.DIGISOUL_FEE,
                            "CLASSIC: into Dinohyumon, on DigiSoul");
                    waitUntil = now + EvolutionTimeline.LONG.duration();
                }
                case 4 -> {
                    check(partner.getSpeciesId().equals(DINOHYUMON) && DINOHYUMON.equals(partner.evolution().line), "CLASSIC: Dinohyumon committed and bound");
                    check(com.digicube.entity.EvolutionController.revert(partner).isEmpty(), "sent back");
                    waitUntil = now + 16 + Progression.EVOLUTION_COOLDOWN + 2;
                }
                case 5 -> {
                    check(partner.getSpeciesId().equals(BETAMON), "back to Betamon");
                    for (int i = 0; i < 10; i++) partner.evolution().raising.win(DigimonAttribute.VACCINE, EnumSet.of(RaisingPlace.WATER));
                    check(PartyEvolution.action(owner, member.id(), "evolve", 0, SEADRAMON).endsWith("locked"), "CLASSIC: bound, Seadramon is refused");
                    check(PartyEvolution.action(owner, member.id(), "evolve", 0, null).isEmpty() && DINOHYUMON.equals(partner.evolution().target),
                            "CLASSIC: a record that now points to Seadramon changes nothing: Dinohyumon again");
                    pass("classic_champion", "raised against Virus in the forest, Betamon digivolves into Dinohyumon and stays bound to it");
                    PartyManager.select(owner, member.id(), -1);
                    waitUntil = now + 2;
                }
                case 6 -> {
                    // A defeat by day and one by night: the record counts the foe and where.
                    time(level, "day");
                    partner = give(level, Constants.id("mochimon"), 1);
                    waitUntil = now + 4;
                }
                case 7 -> {
                    defeat(level, Constants.id("betamon"));
                    var r = partner.evolution().raising;
                    check(r.wins() == 1 && r.foes(DigimonAttribute.VIRUS) == 1, "CLASSIC: a defeat of a Virus Betamon is recorded: " + r.save());
                    check(r.places(RaisingPlace.NIGHT) == 0 && r.places(RaisingPlace.UNDERGROUND) == 0, "by day, under the sky: neither night nor underground");
                    time(level, "midnight");
                    waitUntil = now + 4;
                }
                case 8 -> {
                    defeat(level, Constants.id("gotsumon"));
                    var r = partner.evolution().raising;
                    check(r.wins() == 2 && r.foes(DigimonAttribute.DATA) == 1 && r.places(RaisingPlace.NIGHT) == 1, "CLASSIC: by night: " + r.save());
                    DigiCubeModes.stage(level.getServer(), DigiCubeMode.TAMER);
                    defeat(level, Constants.id("betamon"));
                    check(partner.evolution().raising.wins() == 2, "TAMER records nothing");
                    pass("classic_record", "a Virus foe by day, a Data foe by night; nothing in TAMER");
                    PartyManager.select(owner, member.id(), -1);
                    finish(level);
                }
                default -> throw new AssertionError("classic step");
            }
        } catch (RuntimeException | AssertionError error) {
            Constants.LOG.error("[scenario] FAIL classic_checks", error);
            done = true;
            if (original != null) DigiCubeModes.stage(level.getServer(), original);
            level.getServer().halt(false);
        }
    }

    /** The vanilla {@code /time set}, as an operator would type it: the clock is the world's to move. */
    private static void time(ServerLevel level, String when) {
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set " + when);
    }

    /** A fresh partner of {@code species} at {@code partyLevel}, out in the arena with no AI. */
    private static DigimonEntity give(ServerLevel level, Identifier species, int partyLevel) {
        var fresh = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        fresh.initializeAs(DigimonSpeciesRegistry.getOrThrow(species), partyLevel);
        member = PartyManager.give(owner, fresh);
        var live = data.live.get(member.id());
        check(live != null, "deployed " + species);
        live.setNoAi(true);
        live.setPos(0, 301, 0);
        live.setOnGround(true);
        return live;
    }

    private static DigimonEntity wild(ServerLevel level, Identifier species, int wildLevel, double z) {
        var wild = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        wild.initializeAs(DigimonSpeciesRegistry.getOrThrow(species), wildLevel);
        wild.setPos(0, 301, z);
        wild.setNoAi(true);
        level.addFreshEntity(wild);
        return wild;
    }

    /** A wild {@code species} next to the partner, felled by it in one blow. */
    private static void defeat(ServerLevel level, Identifier species) {
        prey = wild(level, species, 1, 3);
        prey.setHealth(1);
        prey.invulnerableTime = 0;
        prey.hurtServer(level, level.damageSources().indirectMagic(partner, partner), 4);
        check(!prey.isAlive(), "the wild " + species + " fell");
        prey = null;
    }

    private static void setup(ServerLevel level) {
        started = true;
        var server = level.getServer();
        server.tickRateManager().requestGameToSprint(20000);
        original = DigiCubeModes.of(server);
        DigiCubeModes.stage(server, DigiCubeMode.CLASSIC);
        for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) level.setChunkForced(x, z, true);
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) for (int y = 300; y < 320; y++)
            level.setBlock(new BlockPos(x, y, z), (y == 300 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
        level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(-18, 298, -18, 18, 315, 18)).forEach(net.minecraft.world.entity.Entity::discard);
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ClassicTest");
        owner = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) {}
            @Override public boolean isConnected() { return true; }
            @Override public void flushChannel() {}
        };
        new net.minecraft.server.network.ServerGamePacketListenerImpl(server, connection, owner, net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        owner.setPos(10, 301, 0);
        owner.setInvulnerable(true);
        server.getPlayerList().getPlayers().add(owner);
        server.getPlayerList().getPlayersByUUID().put(owner.getUUID(), owner);
        level.addNewPlayer(owner);
        data = PartySavedData.get(server);
        waitUntil = server.getTickCount() + 2;
    }

    private static void finish(ServerLevel level) {
        done = true;
        var server = level.getServer();
        PartyManager.disconnect(owner);
        server.getPlayerList().getPlayers().remove(owner);
        server.getPlayerList().getPlayersByUUID().remove(owner.getUUID());
        owner.discard();
        DigiCubeModes.stage(server, original);
        Constants.LOG.info("[scenario] PASS classic_checks cases={}", cases);
        server.halt(false);
    }
}
