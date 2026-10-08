package com.digicube.scan;

import com.digicube.Constants;
import com.digicube.analyzer.AnalyzerWitness;
import com.digicube.digimon.DigimonFamilies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.DigimonStage;
import com.digicube.digimon.Progression;
import com.digicube.digivice.Digivices;
import com.digicube.entity.DigimonEntity;
import com.digicube.party.PartyManager;
import com.digicube.party.PartyMember;
import com.digicube.party.PartySavedData;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Headless check of the scan with two stand-in tamers and no client: {@code DIGICUBE_SCENARIO=scan_checks}. The first
 * look of the Analyzer sights the starter's family; a wild Tsunomon felled by a partner is a sighting and a defeat at
 * the stage's yield times the level gap; a wild Agumon felled by two tamers' partners feeds both by the XP's split; a
 * full bar loses what is past 200; CONVERT puts a Digitama item in the inventory, refused without a free slot; used from
 * the hand it goes into the Digivice (which must be with the tamer) and keeps out of the party, and it hatches into a
 * level-1 Koromon after its ten minutes. The verdict line is {@code [scenario] PASS scan_checks cases=n}.
 */
public final class ScanScenario {
    private static boolean started, done;
    private static int step, waitUntil, cases;
    private static ServerPlayer tamer, rival;
    private static DigimonEntity partner, rivalPartner;
    private static PartySavedData parties;
    /** The wild Digimon this run put down; any other wild one is cleared away, so nothing else is sighted. */
    private static final java.util.Set<UUID> staged = new java.util.HashSet<>();
    private static final Identifier KOROMON = Constants.id("koromon"), TSUNOMON = Constants.id("tsunomon"), AGUMON = Constants.id("agumon");

    private ScanScenario() {}

    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label + " step=" + step); }
    private static void pass(String name, String detail) { cases++; Constants.LOG.info("[scenario] PASS {} {}", name, detail); }

    public static void tick(ServerLevel level) {
        if (done) return;
        try {
            if (!started) { setup(level); return; }
            tamer.tickCount++;
            rival.tickCount++;
            if (level.getServer().getTickCount() % 5 == 0) for (DigimonEntity stray : level.getEntitiesOfClass(DigimonEntity.class, new AABB(-64, 250, -64, 64, 350, 64),
                    digimon -> !digimon.isOwned() && !staged.contains(digimon.getUUID()))) stray.discard();
            tamer.setHealth(tamer.getMaxHealth());
            rival.setHealth(rival.getMaxHealth());
            int now = level.getServer().getTickCount();
            if (now < waitUntil) return;
            switch (step++) {
                case 0 -> sighting();
                case 1 -> defeat(level);
                case 2 -> shared(level);
                case 3 -> cap(level);
                case 4 -> convert();
                case 5 -> take(level);
                case 6 -> hatch();
                default -> finish(level);
            }
            waitUntil = now + 2;
        } catch (RuntimeException | AssertionError error) {
            Constants.LOG.error("[scenario] FAIL scan_checks", error);
            done = true;
            level.getServer().halt(false);
        }
    }

    private static ScanRecord record(ServerPlayer player) { return ScanSavedData.get(player.level().getServer()).record(player.getUUID()); }

    /** The Analyzer's first look records the starter, and that sights its family: 30 data, once. */
    private static void sighting() {
        AnalyzerWitness.observe(tamer);
        ScanRecord record = record(tamer);
        check(record.seen(KOROMON) && record.data(KOROMON) == Progression.FIRST_SIGHTING_DATA, "the starter's family is sighted: 30 data");
        check(!record.seen(TSUNOMON) && record.data(TSUNOMON) == 0, "a family not met is not");
        AnalyzerWitness.observe(tamer);
        check(record.data(KOROMON) == Progression.FIRST_SIGHTING_DATA, "a family is sighted once");
        List<ScanBar> bars = Scan.bars(tamer.level().getServer(), tamer.getUUID());
        check(bars.size() == 4 && bars.getFirst().family().equals(KOROMON) && bars.getFirst().data() == 30 && bars.getFirst().defeatsLeft() == -1,
                "four bars, Koromon's first, no pace yet");
        pass("scan_sighting", "Koromon family sighted at the first look: 30 of 100");
    }

    /** {@code attacker} takes {@code amount} of {@code wild}'s health; returns the health it actually lost. */
    private static float hit(ServerLevel level, DigimonEntity wild, DigimonEntity attacker, float amount) {
        wild.invulnerableTime = 0;
        float before = wild.getHealth();
        wild.hurtServer(level, level.damageSources().indirectMagic(attacker, attacker), amount);
        return before - Math.max(0, wild.getHealth());
    }

    private static DigimonEntity wild(ServerLevel level, Identifier species, int wildLevel, double x) {
        DigimonEntity wild = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        wild.initializeAs(DigimonSpeciesRegistry.getOrThrow(species), wildLevel);
        wild.setPos(x, 301, 4);
        wild.setNoAi(true);
        staged.add(wild.getUUID());
        level.addFreshEntity(wild);
        return wild;
    }

    /** A wild Tsunomon felled by the Koromon: its family sighted, then the stage's yield times the gap, floored. */
    private static void defeat(ServerLevel level) {
        DigimonEntity wild = wild(level, TSUNOMON, 4, 0);
        int xp = partner.getXp();
        float lost = 0;
        for (int i = 0; i < 40 && wild.isAlive(); i++) lost += hit(level, wild, partner, 4);
        check(!wild.isAlive() && lost > 0, "the wild Tsunomon fell to the partner");
        int expected = Progression.scanSplit(DigimonStage.BABY_II, 4, List.of(new Progression.Contributor(partner.getUUID(), 1, lost))).get(partner.getUUID());
        ScanRecord record = record(tamer);
        check(expected == 7, "6 data times 130 % for a wild three levels up is 7, got " + expected);
        check(record.seen(TSUNOMON) && record.data(TSUNOMON) == Progression.FIRST_SIGHTING_DATA + expected, "fought is seen: 30 and the defeat's 7");
        check(record.defeatsLeft(TSUNOMON) == 9 && record.defeatsLeft(KOROMON) == -1, "about 9 defeats left at this pace, none known for Koromon's");
        check(partner.getXp() > xp || partner.getLevel() > 1, "the same defeat still gives XP");
        pass("scan_defeat", "Tsunomon L4 by a L1 partner: +30 sighting, +7 data, 9 defeats left");
    }

    /** Two tamers' partners fell one wild Agumon: each tamer gets its partners' shares of the data, as with XP. */
    private static void shared(ServerLevel level) {
        DigimonEntity wild = wild(level, AGUMON, 9, 1);
        wild.setHealth(10);
        float mine = hit(level, wild, partner, 7);
        float theirs = 0;
        for (int i = 0; i < 20 && wild.isAlive(); i++) theirs += hit(level, wild, rivalPartner, 1.5F);
        check(!wild.isAlive() && mine > 0 && theirs > 0, "both partners hurt the Agumon and it fell");
        Map<UUID, Integer> split = Progression.scanSplit(DigimonStage.CHILD, 9, List.of(
                new Progression.Contributor(partner.getUUID(), partner.getLevel(), mine), new Progression.Contributor(rivalPartner.getUUID(), 1, theirs)));
        ScanRecord a = record(tamer), b = record(rival);
        check(a.data(KOROMON) == Progression.FIRST_SIGHTING_DATA + split.get(partner.getUUID()), "the tamer gets its partner's share: " + split);
        check(b.data(KOROMON) == Progression.FIRST_SIGHTING_DATA + split.get(rivalPartner.getUUID()), "the other tamer its own, after its first sighting");
        check(split.get(partner.getUUID()) > split.get(rivalPartner.getUUID()), "the bigger part of the damage, the bigger share");
        pass("scan_shared", "Agumon L9 split " + Math.round(mine * 10) / 10F + " / " + Math.round(theirs * 10) / 10F + " damage into " + split.values() + " data");
    }

    /** A bar holds two Digitama's worth; a defeat past it keeps the pace but loses the rest. */
    private static void cap(ServerLevel level) {
        ScanRecord record = record(tamer);
        record.set(KOROMON, 195);
        DigimonEntity wild = wild(level, AGUMON, 9, 2);
        wild.setHealth(5);
        for (int i = 0; i < 10 && wild.isAlive(); i++) hit(level, wild, partner, 3);
        check(!wild.isAlive() && record.data(KOROMON) == Progression.SCAN_CAPACITY, "a full bar stops at 200");
        check(record.defeatsLeft(KOROMON) == 0, "a ready bar has no defeats left");
        pass("scan_cap", "195 + a defeat stops at 200");
    }

    /** The inventory slot holding a Digitama of {@code family}, or -1. */
    private static int digitama(ServerPlayer player, Identifier family) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (family.equals(DigitamaItem.family(player.getInventory().getItem(i)))) return i;
        return -1;
    }

    /** CONVERT spends 100 and puts a Koromon Digitama in the inventory; with no free slot it is refused and spends nothing. */
    private static void convert() {
        ScanRecord record = record(tamer);
        int before = parties.roster().owned(tamer.getUUID()).size();
        check(Scan.convert(tamer, DigimonFamilies.index(TSUNOMON)).equals("gui.digicube.scan.not_ready"), "37 of 100 is no Digitama");
        check(Scan.convert(tamer, 9).equals("gui.digicube.party.invalid"), "a family that does not exist is refused");
        var inventory = tamer.getInventory();
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).isEmpty()) inventory.setItem(i, new ItemStack(Items.DIRT, 64));
        check(Scan.convert(tamer, DigimonFamilies.index(KOROMON)).equals("gui.digicube.scan.no_room") && record.data(KOROMON) == 200,
                "no free slot: no Digitama, nothing spent");
        for (int i = 0; i < 36; i++) if (inventory.getItem(i).is(Items.DIRT)) inventory.setItem(i, ItemStack.EMPTY);
        check(Scan.convert(tamer, DigimonFamilies.index(KOROMON)).isEmpty() && record.data(KOROMON) == 100, "CONVERT spends 100 of 200");
        int slot = digitama(tamer, KOROMON);
        check(slot >= 0 && inventory.getItem(slot).getCount() == 1, "a Koromon Digitama in the inventory");
        check(inventory.getItem(slot).getHoverName().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents name
                && name.getKey().equals("item.digicube.digitama.of"), "named for its family");
        check(parties.roster().owned(tamer.getUUID()).size() == before, "nothing in the Digivice until it is used");
        check(Scan.bars(tamer.level().getServer(), tamer.getUUID()).getFirst().data() == 100, "the snapshot's bar follows");
        pass("scan_convert", "200 -> 100 and a Koromon Digitama in the inventory; refused with no free slot");
    }

    /** Used from the hand, the Digitama goes into the Digivice, which must be with the tamer; there it keeps out of the party. */
    private static void take(ServerLevel level) {
        var inventory = tamer.getInventory();
        int slot = digitama(tamer, KOROMON);
        check(slot >= 0, "the Digitama is still in the inventory");
        ItemStack held = inventory.getItem(slot);
        inventory.setItem(slot, ItemStack.EMPTY);
        tamer.setItemInHand(InteractionHand.MAIN_HAND, held);
        int before = parties.roster().owned(tamer.getUUID()).size();
        check(!Digivices.hasDevice(tamer), "the tamer has no Digivice yet");
        check(!held.use(level, tamer, InteractionHand.MAIN_HAND).consumesAction() && held.getCount() == 1
                && parties.roster().owned(tamer.getUUID()).size() == before, "no Digivice with the tamer: the Digitama stays in the hand");
        inventory.add(Digivices.issue(level.getServer(), tamer.getUUID()));
        check(Digivices.hasDevice(tamer), "the tamer carries its Digivice");
        check(held.use(level, tamer, InteractionHand.MAIN_HAND).consumesAction() && tamer.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "used, the Digitama leaves the hand");
        List<PartyMember> owned = parties.roster().owned(tamer.getUUID());
        check(owned.size() == before + 1, "one Digitama more in the Digivice");
        PartyMember egg = owned.getLast();
        check(egg.egg() && egg.species().equals(KOROMON) && egg.level() == 1 && egg.slot() == -1 && egg.hatchTicks() == Progression.DIGITAMA_HATCH_TICKS,
                "a level-1 Koromon Digitama, ten minutes from hatching, not in the party");
        check(tamer.getCooldowns().isOnCooldown(DigitamaItem.of(KOROMON)), "the hand rests while the egg breaks into data");
        check(PartyManager.select(tamer, egg.id(), 1).equals("gui.digicube.scan.egg_party") && egg.slot() == -1, "a Digitama cannot join the party");
        pass("scan_take", "refused without a Digivice; used, a Koromon Digitama in the Digivice and out of the party");
    }

    /** Ten minutes of the tamer's time online, then the Digitama is a Koromon at level 1 that may join the party. */
    private static void hatch() {
        PartyMember egg = parties.roster().owned(tamer.getUUID()).stream().filter(PartyMember::egg).findFirst().orElseThrow();
        check(PartyManager.incubate(parties, tamer, Progression.DIGITAMA_HATCH_TICKS - 100) == 0 && egg.egg() && egg.hatchTicks() == 100, "not yet");
        check(PartyManager.incubate(parties, tamer, 100) == 1 && !egg.egg(), "hatched after ten minutes");
        check(egg.species().equals(KOROMON) && egg.level() == 1, "a level-1 Koromon");
        check(PartyManager.select(tamer, egg.id(), 1).isEmpty() && egg.slot() == 1, "a hatchling joins the party");
        pass("scan_hatch", "Digitama -> Koromon L1 in the party");
    }

    private static ServerPlayer player(ServerLevel level, String name, double x) {
        MinecraftServer server = level.getServer();
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) {}
            @Override public boolean isConnected() { return true; }
            @Override public void flushChannel() {}
        };
        new net.minecraft.server.network.ServerGamePacketListenerImpl(server, connection, player, net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        player.setPos(x, 301, -6);
        // Facing away from the arena: the Analyzer sights only what the checks give it.
        player.setYRot(180);
        player.setYHeadRot(180);
        player.setInvulnerable(true);
        server.getPlayerList().getPlayers().add(player);
        server.getPlayerList().getPlayersByUUID().put(player.getUUID(), player);
        level.addNewPlayer(player);
        return player;
    }

    private static DigimonEntity give(ServerLevel level, ServerPlayer owner, Identifier species, double x) {
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        digimon.initializeAs(DigimonSpeciesRegistry.getOrThrow(species), 1);
        PartyMember member = PartyManager.give(owner, digimon);
        DigimonEntity live = PartyManager.live(parties, member);
        check(live != null, species + " deployed");
        live.setNoAi(true);
        live.setPos(x, 301, 0);
        return live;
    }

    private static void setup(ServerLevel level) {
        started = true;
        for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) level.setChunkForced(x, z, true);
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) for (int y = 300; y < 312; y++)
            level.setBlock(new BlockPos(x, y, z), (y == 300 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
        level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(-18, 298, -18, 18, 315, 18)).forEach(net.minecraft.world.entity.Entity::discard);
        parties = PartySavedData.get(level.getServer());
        tamer = player(level, "ScanTest", -3);
        rival = player(level, "ScanRival", 3);
        partner = give(level, tamer, KOROMON, -2);
        rivalPartner = give(level, rival, TSUNOMON, 2);
        waitUntil = level.getServer().getTickCount() + 5;
    }

    private static void finish(ServerLevel level) {
        done = true;
        for (ServerPlayer player : new ArrayList<>(List.of(tamer, rival))) {
            PartyManager.disconnect(player);
            level.getServer().getPlayerList().getPlayers().remove(player);
            level.getServer().getPlayerList().getPlayersByUUID().remove(player.getUUID());
            player.discard();
        }
        Constants.LOG.info("[scenario] PASS scan_checks cases={}", cases);
        level.getServer().halt(false);
    }
}
