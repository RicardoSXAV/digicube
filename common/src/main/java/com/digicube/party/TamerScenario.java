package com.digicube.party;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.digivice.Digivices;
import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEffects;
import com.digicube.registry.DCEntityTypes;
import com.digicube.registry.DCItems;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Headless check of a tamer's side against wild Digimon, with a stand-in tamer and no client:
 * {@code DIGICUBE_SCENARIO=tamer_checks}. A wild Koromon the tamer strikes goes for the nearest partner, not the tamer;
 * as partners fall it turns to the next one and, with none left, to the tamer. A fallen partner's damage stops counting
 * for its XP; it rests thirty seconds in the Digivice and comes back to its own slot with one point of health, and
 * without the fire or effects it fell with; food from
 * the hand heals it, a full one or a wild one eats nothing; out of a fight a partner mends on its own, and not within
 * five seconds of a blow. Bites of food are spaced: one every thirty seconds in a fight, kept through a recall, one every
 * 1.6 seconds when calm. The Digivice put away takes the party in, and back with the
 * tamer the same party comes out in the same slots. A wild Digimon only a partner hurt lets the tamer be once that
 * partner falls. A wild Digimon no player came near lingers for minutes, and the spawner sees the wild Digimon near a
 * spot by species (its crowd rule). A wild Digimon drops Digimeat by its stage; a fallen partner and a Battle Testing
 * fighter drop nothing. The verdict line is
 * {@code [scenario] PASS tamer_checks cases=n}.
 */
public final class TamerScenario {
    private static boolean started, done;
    private static int step, waitUntil, cases;
    /** Partners and wild Digimon are healed every tick while true, so only the checks' own blows decide who falls. */
    private static boolean guard = true;
    private static ServerPlayer tamer;
    private static PartySavedData parties;
    private static PartyMember agumon, gabumon, koromon;
    private static DigimonEntity wild;
    private static ItemStack device;
    /** The wild Digimon this run put down; any other wild one is cleared away. */
    private static final java.util.Set<UUID> staged = new java.util.HashSet<>();
    private static final Identifier KOROMON = Constants.id("koromon");

    private TamerScenario() {}

    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label + " step=" + step); }
    private static void pass(String name, String detail) { cases++; Constants.LOG.info("[scenario] PASS {} {}", name, detail); }

    public static void tick(ServerLevel level) {
        if (done) return;
        try {
            if (!started) { setup(level); return; }
            tamer.tickCount++;
            if (level.getServer().getTickCount() % 5 == 0) for (DigimonEntity stray : level.getEntitiesOfClass(DigimonEntity.class, new AABB(-64, 250, -64, 64, 350, 64),
                    digimon -> !digimon.isOwned() && !staged.contains(digimon.getUUID()))) stray.discard();
            tamer.setHealth(tamer.getMaxHealth());
            if (guard) {
                for (PartyMember member : List.of(agumon, gabumon, koromon)) {
                    DigimonEntity live = PartyManager.live(parties, member);
                    if (live != null && live.isAlive()) live.setHealth(live.getMaxHealth());
                }
                if (wild != null && wild.isAlive()) wild.setHealth(wild.getMaxHealth());
            }
            int now = level.getServer().getTickCount();
            if (now < waitUntil) return;
            int wait = switch (step++) {
                case 0 -> struck(level);
                case 1 -> partnerFirst(level);
                case 2 -> nextPartner(level);
                case 3 -> tamerLast(level);
                case 4 -> backInSlot();
                case 5 -> fed(level);
                case 6 -> deviceAway();
                case 7 -> deviceBack();
                case 8 -> partnerOnly(level);
                case 9 -> partnerOnlyFell(level);
                case 10 -> linger(level);
                case 11 -> crowd(level);
                case 12 -> mendReady();
                case 13 -> mendStart();
                case 14 -> mended(level);
                case 15 -> calmAfterBlow();
                case 16 -> bites(level);
                case 17 -> drops(level);
                default -> { finish(level); yield 0; }
            };
            waitUntil = now + wait;
        } catch (RuntimeException | AssertionError error) {
            Constants.LOG.error("[scenario] FAIL tamer_checks", error);
            done = true;
            level.getServer().halt(false);
        }
    }

    private static DigimonEntity live(PartyMember member) { return PartyManager.live(parties, member); }

    /** {@code wild} takes {@code amount} from {@code source}'s blow. */
    private static void hit(ServerLevel level, DigimonEntity target, net.minecraft.world.damagesource.DamageSource source, float amount) {
        target.invulnerableTime = 0;
        target.hurtServer(level, source, amount);
    }

    private static void fell(ServerLevel level, PartyMember member) {
        DigimonEntity live = live(member);
        check(live != null && live.isAlive(), member.species() + " is out to fall");
        live.invulnerableTime = 0;
        live.hurtServer(level, level.damageSources().genericKill(), 100_000);
        check(!live.isAlive(), member.species() + " fell");
    }

    private static DigimonEntity wild(ServerLevel level, int x, int z) {
        return wild(level, KOROMON, 5, x, z);
    }

    private static DigimonEntity wild(ServerLevel level, Identifier species, int digimonLevel, int x, int z) {
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        digimon.initializeAs(DigimonSpeciesRegistry.getOrThrow(species), digimonLevel);
        digimon.setPos(x + .5, 301, z + .5);
        digimon.setPersistenceRequired();
        staged.add(digimon.getUUID());
        level.addFreshEntity(digimon);
        return digimon;
    }

    /** The tamer strikes a wild Koromon with their own hand. */
    private static int struck(ServerLevel level) {
        wild = wild(level, 0, 5);
        hit(level, wild, level.damageSources().playerAttack(tamer), .5F);
        return 8;
    }

    /** It answers the nearest partner, not the tamer who struck it; a partner's blow counts toward that partner's XP. */
    private static int partnerFirst(ServerLevel level) {
        check(wild.holdsGrudge(), "the struck Koromon holds a grudge");
        check(wild.getTarget() == live(agumon), "the struck Koromon goes for the nearest partner, got " + name(wild.getTarget()));
        hit(level, wild, level.damageSources().mobAttack(live(agumon)), 1);
        check(wild.contributionOf(agumon.id()) > 0, "the Agumon's blow is on the Koromon's ledger");
        pass("wild_partner_first", "struck by the tamer, the wild Koromon fights the Agumon");
        // It falls Burned and Cracked: neither may come back with it.
        DigimonEntity burning = live(agumon);
        DigimonEntity.scorch(burning, 200);
        burning.addEffect(new MobEffectInstance(DCEffects.CRACKED, 200));
        check(burning.isOnFire() && burning.hasEffect(DCEffects.CRACKED), "the Agumon burns, Cracked, as it falls");
        fell(level, agumon);
        return 8;
    }

    /** The partner it fought fell: it owes that partner nothing, rests it, and turns to the next partner. */
    private static int nextPartner(ServerLevel level) {
        check(wild.contributionOf(agumon.id()) == 0, "a fallen partner's damage no longer counts for its XP");
        // A regular rest pulse may already have passed since the fall.
        check(!agumon.active() && agumon.resting() && agumon.restTicks() >= Progression.DEFEAT_REST_TICKS - Progression.RESERVE_REGEN_INTERVAL_TICKS
                && agumon.returnSlot() == 0, "the fallen Agumon rests thirty seconds in the Digivice, remembering slot 0");
        var stored = agumon.entityData();
        check(!stored.contains("Fire") && !stored.contains("active_effects"), "the Agumon is stored without the fire and the Crack it fell with");
        LivingEntity target = wild.getTarget();
        check(target != null && (target == live(gabumon) || target == live(koromon)), "the Koromon turns to the next partner, got " + name(target));
        pass("wild_next_partner", "Agumon fell: its share forgotten, the Koromon turns on the " + name(target));
        fell(level, gabumon);
        fell(level, koromon);
        return 8;
    }

    /** No partner left: the tamer who struck it is next. */
    private static int tamerLast(ServerLevel level) {
        check(wild.getTarget() == tamer, "with no partner left the Koromon goes for the tamer, got " + name(wild.getTarget()));
        pass("wild_tamer_last", "every partner down, the Koromon turns on the tamer");
        wild.discard();
        wild = null;
        guard = false;
        // The thirty seconds of rest, pulse by pulse, until the last one is up.
        for (int pulse = 0; pulse < 10 && (agumon.resting() || gabumon.resting() || koromon.resting()); pulse++)
            PartyManager.regenerateReserve(parties, tamer.getUUID());
        check(!agumon.resting() && !gabumon.resting() && !koromon.resting(), "thirty seconds of rest are over");
        return 25;
    }

    /**
     * Rested, each partner is back in its own slot, out in the world, with one point of health (one that fell earlier may
     * have mended a pulse or two in the Digivice while the others still rested).
     */
    private static int backInSlot() {
        PartyMember[] members = {agumon, gabumon, koromon};
        for (int slot = 0; slot < members.length; slot++) {
            DigimonEntity live = live(members[slot]);
            float pulse = live == null ? 0 : live.getMaxHealth() * Progression.RESERVE_REGEN_INTERVAL_TICKS / Progression.FULL_HEAL_TICKS;
            check(members[slot].slot() == slot && live != null && live.getHealth() >= Progression.REVIVE_HEALTH
                    && live.getHealth() <= Progression.REVIVE_HEALTH + 2 * pulse + 1.0E-3F,
                    members[slot].species() + " is back in slot " + slot + " with one point of health, got " + (live == null ? "none" : live.getHealth()));
        }
        DigimonEntity revived = live(agumon);
        check(!revived.isOnFire() && revived.getRemainingFireTicks() <= 0 && !revived.hasEffect(DCEffects.CRACKED),
                "the Agumon that fell burning comes back without the fire or the Crack, got " + revived.getRemainingFireTicks() + " fire ticks");
        pass("defeat_rest_return", "thirty seconds of rest, then Agumon, Gabumon and Koromon back in slots 0, 1, 2 at 1 HP, the Agumon that fell burning no longer alight");
        return 2;
    }

    /** Bread from the hand heals a quarter of a hurt partner; a full partner and a wild Digimon eat nothing. */
    private static int fed(ServerLevel level) {
        DigimonEntity live = live(agumon);
        tamer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 3));
        float expected = live.getHealth() + Progression.feedHeal(live.getMaxHealth(), 5);
        check(tamer.interactOn(live, InteractionHand.MAIN_HAND, live.position()).consumesAction(), "the Agumon takes the bread");
        check(Math.abs(live.getHealth() - expected) < 1.0E-3 && tamer.getMainHandItem().getCount() == 2,
                "one bread heals " + Progression.feedHeal(live.getMaxHealth(), 5) + ", got " + live.getHealth());
        tamer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(DCItems.DIGIMEAT, 2));
        tamer.interactOn(live, InteractionHand.MAIN_HAND, live.position());
        check(Math.abs(live.getHealth() - expected) < 1.0E-3 && tamer.getMainHandItem().getCount() == 2, "a second bite right away waits");
        tamer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 2));
        live.setHealth(live.getMaxHealth());
        tamer.interactOn(live, InteractionHand.MAIN_HAND, live.position());
        check(tamer.getMainHandItem().getCount() == 2, "a partner at full health leaves the food");
        DigimonEntity stranger = wild(level, 6, 6);
        stranger.setHealth(stranger.getMaxHealth() / 2);
        tamer.interactOn(stranger, InteractionHand.MAIN_HAND, stranger.position());
        check(stranger.getHealth() == stranger.getMaxHealth() / 2 && tamer.getMainHandItem().getCount() == 2, "a wild Digimon is not fed");
        stranger.discard();
        pass("feed", "one bread: +" + Progression.feedHeal(live.getMaxHealth(), 5) + " of " + live.getMaxHealth()
                + "; a second bite right away waits; refused at full health and by a wild one");
        tamer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        device = Digivices.issue(level.getServer(), tamer.getUUID());
        check(!device.isEmpty() && tamer.getInventory().add(device), "the tamer carries a Digivice");
        return 4;
    }

    /** The Digivice left the tamer: the whole party goes into it, each remembering its slot. */
    private static int deviceAway() {
        check(parties.roster().party(tamer.getUUID()).size() == 3, "the full party is out with the Digivice along");
        // The inventory holds the stack itself (adding it emptied the one handed over): take that one out.
        for (int slot = 0; slot < tamer.getInventory().getContainerSize(); slot++) {
            if (!tamer.getInventory().getItem(slot).is(DCItems.DIGIVICE)) continue;
            device = tamer.getInventory().getItem(slot);
            tamer.getInventory().setItem(slot, ItemStack.EMPTY);
        }
        return 4;
    }

    private static int deviceBack() {
        check(parties.roster().party(tamer.getUUID()).isEmpty() && live(agumon) == null && live(gabumon) == null && live(koromon) == null,
                "the Digivice away: every partner is in it");
        check(agumon.returnSlot() == 0 && gabumon.returnSlot() == 1 && koromon.returnSlot() == 2, "each remembers its slot");
        check(tamer.getInventory().add(device), "the Digivice is back with the tamer");
        waitForParty = true;
        return 25;
    }
    private static boolean waitForParty;

    /** Back with the tamer, the same party comes out in the same slots; then a wild Digimon only a partner hurts. */
    private static int partnerOnly(ServerLevel level) {
        if (waitForParty) {
            check(agumon.slot() == 0 && gabumon.slot() == 1 && koromon.slot() == 2 && live(agumon) != null && live(gabumon) != null
                    && live(koromon) != null, "the Digivice back: the same party out in the same slots");
            pass("digivice_formation", "away: party in the Digivice; back: Agumon, Gabumon, Koromon out again in slots 0, 1, 2");
            waitForParty = false;
        }
        check(PartyManager.select(tamer, gabumon.id(), -1).isEmpty() && PartyManager.select(tamer, koromon.id(), -1).isEmpty(),
                "Gabumon and Koromon called back into the Digivice");
        guard = true;
        wild = wild(level, 0, 5);
        hit(level, wild, level.damageSources().mobAttack(live(agumon)), 1);
        return 8;
    }

    /** Only the partner hurt it: once that partner falls, the wild Digimon lets the tamer be. */
    private static int partnerOnlyFell(ServerLevel level) {
        check(wild.getTarget() == live(agumon), "hurt by the Agumon, the Koromon fights it, got " + name(wild.getTarget()));
        fell(level, agumon);
        waitForCalm = true;
        return 10;
    }
    private static boolean waitForCalm;

    /** A wild Digimon lingers: no random despawn for five minutes, and none at all while a player is within 128 blocks of it then. */
    private static int linger(ServerLevel level) {
        if (waitForCalm) {
            check(wild.getTarget() == null && !wild.holdsGrudge(), "the tamer never struck it: with its partner down the Koromon calms, got " + name(wild.getTarget()));
            pass("wild_partner_only", "hurt only by the Agumon, the Koromon lets the tamer be once the Agumon falls");
            wild.discard();
            wild = null;
            waitForCalm = false;
        }
        DigimonEntity lingering = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.NATURAL);
        lingering.initializeAs(DigimonSpeciesRegistry.getOrThrow(KOROMON), 3);
        double near = 60 * 60, far = 130 * 130;
        lingering.setNoActionTime(700);
        check(!lingering.removeWhenFarAway(near), "a wild Digimon 60 blocks off after 35 seconds alone stays (vanilla could drop it)");
        lingering.setNoActionTime(DigimonEntity.WILD_LINGER_TICKS - 1);
        check(!lingering.removeWhenFarAway(near), "and still stays just short of five minutes");
        lingering.setNoActionTime(DigimonEntity.WILD_LINGER_TICKS + 1);
        check(lingering.removeWhenFarAway(near), "past five minutes alone it may wander off");
        lingering.setNoActionTime(0);
        check(lingering.removeWhenFarAway(far), "past 128 blocks from every player it goes at once");
        DigimonEntity partner = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        partner.initializeAs(DigimonSpeciesRegistry.getOrThrow(KOROMON), 3);
        partner.setOwner(tamer);
        partner.setNoActionTime(DigimonEntity.WILD_LINGER_TICKS + 1);
        check(!partner.removeWhenFarAway(far), "a partner never despawns");
        pass("wild_linger", "kept 60 blocks off for five minutes, gone past 128 blocks");
        return 2;
    }

    /** The spawner counts the wild Digimon already near a spot, by species, and each one divides its species' weight there. */
    private static int crowd(ServerLevel level) {
        List<DigimonEntity> three = List.of(wild(level, 2, 8), wild(level, -2, 8), wild(level, 0, 10));
        var centre = new net.minecraft.world.phys.Vec3(.5, 301, 8.5);
        long koromon = com.digicube.spawn.WildSpawner.near(level, centre, com.digicube.spawn.WildSpawner.CROWD_RADIUS).stream()
                .filter(digimon -> digimon.getSpeciesId().equals(KOROMON)).count();
        check(koromon == 3, "the spawner sees the three Koromon put down near the spot, got " + koromon);
        var entry = com.digicube.spawn.SpawnTables.get(net.minecraft.world.level.Level.OVERWORLD).orElseThrow().entries().stream()
                .filter(candidate -> candidate.species().equals(KOROMON)).findFirst().orElseThrow();
        int weight = com.digicube.spawn.SpawnTable.crowdedWeight(entry, java.util.Map.of(KOROMON, (int) koromon));
        check(weight == entry.weight() / 4, "three near divide Koromon's weight by four");
        three.forEach(DigimonEntity::discard);
        pass("spawn_crowd", "three wild Koromon near a spot: Koromon's weight " + entry.weight() + " -> " + weight + " there");
        return 2;
    }

    private static float mendFrom, afterBlow;

    /** A partner sent out again, left to settle past the first five seconds of its new life. */
    private static int mendReady() {
        guard = false;
        check(PartyManager.select(tamer, gabumon.id(), 1).isEmpty() && live(gabumon) != null, "the Gabumon is out again");
        return Progression.FIELD_REGEN_DELAY_TICKS + 20;
    }

    private static int mendStart() {
        DigimonEntity live = live(gabumon);
        live.setHealth(live.getMaxHealth() / 2);
        mendFrom = live.getHealth();
        return 45;
    }

    /** Calm out in the world, a partner mends a pulse a second at the Digivice's pace; then a blow. */
    private static int mended(ServerLevel level) {
        DigimonEntity live = live(gabumon);
        float pulse = Progression.fieldHeal(live.getMaxHealth()), gained = live.getHealth() - mendFrom;
        check(gained >= 2 * pulse - 1.0E-3F && gained <= 3 * pulse + 1.0E-3F, "45 calm ticks mend two or three pulses of " + pulse + ", got " + gained);
        hit(level, live, level.damageSources().generic(), 1);
        afterBlow = live.getHealth();
        return Progression.FIELD_REGEN_DELAY_TICKS - 20;
    }

    /** For five seconds after a blow it mends nothing. */
    private static int calmAfterBlow() {
        DigimonEntity live = live(gabumon);
        check(Math.abs(live.getHealth() - afterBlow) < 1.0E-4F, "no mending within five seconds of a blow, got " + live.getHealth() + " from " + afterBlow);
        pass("field_mend", "a calm partner mends " + Progression.fieldHeal(live.getMaxHealth()) + " a second (full in two minutes), none for five seconds after a blow");
        return 2;
    }

    /**
     * Bites are spaced: fighting, one every fifteen seconds; calm, one every 1.6 seconds, back as soon as the fight is over;
     * calling the partner back and out again does not reset the clock. The wait its tamer reads is when it eats if nothing
     * new happens. The partner's own clock is wound forward instead of the run waiting it out.
     */
    private static int bites(ServerLevel level) {
        // The off hand: the main hand holds the Digivice, and a tamer without it has the party taken in.
        tamer.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(DCItems.DIGIMEAT, 16));
        DigimonEntity live = live(gabumon);
        DigimonEntity foe = wild(level, 6, 6);
        live.setTarget(foe);
        check(live.calmIn() < 0, "the Gabumon with a live foe is fighting");
        float heal = Progression.feedHeal(live.getMaxHealth(), Progression.DIGIMEAT_NUTRITION);
        int fight = Progression.FEED_FIGHT_INTERVAL_TICKS;
        check(bite(live), "the first bite in a fight is taken");
        check(!bite(live) && live.biteWait() == fight, "fighting, the next bite waits fifteen seconds, got " + live.biteWait() + " ticks");
        check(PartyManager.select(tamer, gabumon.id(), -1).isEmpty() && PartyManager.select(tamer, gabumon.id(), 1).isEmpty()
                && live(gabumon) != null, "the Gabumon called back and out again");
        live = live(gabumon);
        live.setTarget(foe);
        check(!bite(live) && live.biteWait() == fight, "called back and out again it still waits: the bite clock went with it, got " + live.biteWait());
        live.tickCount += fight - 1;
        check(!bite(live) && live.biteWait() == 1, "a tick short of fifteen seconds it still waits");
        live.tickCount++;
        check(live.calmIn() < 0 && bite(live), "fifteen seconds on, still fighting, it eats");
        pass("feed_fight", "fighting, one Digimeat (+" + heal + " of " + live.getMaxHealth() + ") every fifteen seconds, kept through a recall");
        live.setHealth(live.getMaxHealth());
        hit(level, live, level.damageSources().generic(), 1);
        foe.discard();
        int quiet = Progression.FIELD_REGEN_DELAY_TICKS;
        check(live.calmIn() == quiet && !bite(live) && live.biteWait() == quiet,
                "its foe gone, the next bite comes with five seconds of quiet, not fifteen, got " + live.biteWait());
        live.tickCount += quiet;
        check(live.calm() && bite(live), "calm five seconds after the fight, it eats");
        check(!bite(live) && live.biteWait() == Progression.FEED_INTERVAL_TICKS, "calm, the next bite waits 1.6 seconds, got " + live.biteWait());
        live.tickCount += Progression.FEED_INTERVAL_TICKS;
        check(bite(live), "1.6 seconds on it eats again");
        foe = wild(level, 6, 6);
        live.setTarget(foe);
        check(!bite(live) && live.biteWait() == fight, "a new fight: fifteen seconds from its last bite, got " + live.biteWait());
        foe.discard();
        pass("feed_calm", "calm, one Digimeat every " + Progression.FEED_INTERVAL_TICKS + " ticks, from five seconds after a fight");
        tamer.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        return 2;
    }

    /**
     * The tamer offers one Digimeat from the off hand to {@code live}, brought down to one point of health first.
     * @return whether it ate: a bite is taken whole (+30 %, one less in the hand) or not at all
     */
    private static boolean bite(DigimonEntity live) {
        live.setHealth(1);
        int held = tamer.getOffhandItem().getCount();
        tamer.interactOn(live, InteractionHand.OFF_HAND, live.position());
        float fed = 1 + Progression.feedHeal(live.getMaxHealth(), Progression.DIGIMEAT_NUTRITION);
        boolean ate = tamer.getOffhandItem().getCount() == held - 1 && Math.abs(live.getHealth() - fed) < 1.0E-3F;
        boolean waited = tamer.getOffhandItem().getCount() == held && live.getHealth() == 1;
        check(ate || waited, "a bite is whole or none, got health " + live.getHealth() + " and " + tamer.getOffhandItem().getCount() + " in hand");
        return ate;
    }

    /** A wild Digimon drops Digimeat by its stage, whatever killed it; a fallen partner and a Battle Testing fighter drop none. */
    private static int drops(ServerLevel level) {
        int baby = killedFor(level, KOROMON, 5, -8, 12);
        int rookie = killedFor(level, Constants.id("agumon"), 5, 0, 12);
        int champion = killedFor(level, Constants.id("greymon"), 20, 8, 12);
        check(baby == 1, "a wild Baby II drops one Digimeat, got " + baby);
        check(rookie >= 1 && rookie <= 2, "a wild Rookie drops one or two, got " + rookie);
        check(champion >= 1 && champion <= 3, "a wild Champion drops one to three, got " + champion);
        DigimonEntity partner = live(gabumon);
        check(partner != null, "the Gabumon is out to fall");
        Vec3 spot = partner.position();
        fell(level, gabumon);
        check(meatNear(level, spot) == 0, "a fallen partner drops nothing");
        String fighter = "";
        if (com.digicube.platform.Services.PLATFORM.isDevelopmentEnvironment()) {
            DigimonEntity staged = wild(level, Constants.id("agumon"), 5, 8, -12);
            staged.joinBattleSide(1);
            check(staged.battleSide() == 1, "the Agumon stands on a Battle Testing side");
            hit(level, staged, level.damageSources().playerAttack(tamer), 100_000);
            check(!staged.isAlive() && meatNear(level, staged.position()) == 0, "a Battle Testing fighter drops nothing");
            fighter = " and a Battle Testing fighter";
        }
        pass("digimeat_drops", "a wild Koromon dropped " + baby + ", an Agumon " + rookie + ", a Greymon " + champion
                + " (Baby II 1, Rookie 1-2, Champion 1-3); a fallen partner" + fighter + " none");
        return 2;
    }

    /** A wild {@code species} put down at x, z and killed by the tamer's hand: the Digimeat it left there. */
    private static int killedFor(ServerLevel level, Identifier species, int digimonLevel, int x, int z) {
        DigimonEntity digimon = wild(level, species, digimonLevel, x, z);
        hit(level, digimon, level.damageSources().playerAttack(tamer), 100_000);
        check(!digimon.isAlive(), species + " fell");
        return meatNear(level, digimon.position());
    }

    /** Digimeat lying within three blocks of {@code spot}, cleared away once counted. */
    private static int meatNear(ServerLevel level, Vec3 spot) {
        int count = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(spot, spot).inflate(3),
                item -> item.getItem().is(DCItems.DIGIMEAT))) {
            count += item.getItem().getCount();
            item.discard();
        }
        return count;
    }

    private static String name(LivingEntity entity) {
        if (entity == null) return "nobody";
        if (entity instanceof DigimonEntity digimon) return (digimon.isOwned() ? "partner " : "wild ") + digimon.getSpeciesId().getPath();
        return entity.getName().getString();
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
        server.getPlayerList().getPlayers().add(player);
        server.getPlayerList().getPlayersByUUID().put(player.getUUID(), player);
        level.addNewPlayer(player);
        return player;
    }

    private static PartyMember give(ServerLevel level, Identifier species, int slot, double x, double z) {
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        digimon.initializeAs(DigimonSpeciesRegistry.getOrThrow(species), 1);
        PartyMember member = PartyManager.give(tamer, digimon);
        DigimonEntity live = PartyManager.live(parties, member);
        check(live != null && member.slot() == slot, species + " out in slot " + slot);
        live.setNoAi(true);
        live.setPos(x, 301, z);
        return member;
    }

    private static void setup(ServerLevel level) {
        // A fresh world loads the forced chunks over a few ticks: the arena waits for them.
        boolean loaded = true;
        for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) {
            level.setChunkForced(x, z, true);
            loaded &= level.isPositionEntityTicking(new BlockPos(x * 16 + 8, 64, z * 16 + 8));
        }
        if (!loaded) return;
        started = true;
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) for (int y = 300; y < 312; y++)
            level.setBlock(new BlockPos(x, y, z), (y == 300 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
        level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(-18, 298, -18, 18, 315, 18)).forEach(net.minecraft.world.entity.Entity::discard);
        parties = PartySavedData.get(level.getServer());
        tamer = player(level, "TamerTest", -3);
        agumon = give(level, Constants.id("agumon"), 0, -1, 0);
        gabumon = give(level, Constants.id("gabumon"), 1, 4, 0);
        koromon = give(level, KOROMON, 2, -6, 2);
        waitUntil = level.getServer().getTickCount() + 5;
    }

    private static void finish(ServerLevel level) {
        done = true;
        PartyManager.disconnect(tamer);
        level.getServer().getPlayerList().getPlayers().remove(tamer);
        level.getServer().getPlayerList().getPlayersByUUID().remove(tamer.getUUID());
        tamer.discard();
        Constants.LOG.info("[scenario] PASS tamer_checks cases={}", cases);
        level.getServer().halt(false);
    }
}
