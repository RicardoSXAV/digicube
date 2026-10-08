package com.digicube.analyzer;

import com.digicube.digimon.CombatMark;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.CombatMarkState;
import com.digicube.entity.DigimonEntity;
import com.digicube.party.PartyManager;
import com.digicube.party.PartySavedData;
import com.digicube.platform.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Fills each tamer's {@link AnalyzerRecord} from what they witness. A species is recorded once one of its kind has
 * stayed in sight for {@link #WATCH_TICKS}: within {@link #RANGE}, in front of the tamer and with nothing solid in
 * between. A tamer's own Digimon are recorded at once, every form they have, came from or have reached. A combat
 * mark is recorded the moment its emblem shows over a body in sight, the tamer's own included. The game mode plays
 * no part: what is witnessed in creative is recorded too, and it is the Analyzer that opens every entry there. The
 * first species of a family on record is the scan's first sighting of that family ({@link com.digicube.scan.Scan#sight}).
 */
public final class AnalyzerWitness {
    /** How far a tamer reads a body, in blocks. */
    public static final double RANGE = 24;
    /** The tamers look around this often. */
    public static final int INTERVAL_TICKS = 5;
    /** A species has to stay in sight this long, without a break, to be recorded. */
    public static final int WATCH_TICKS = 20;
    /** How far off the line of sight still counts as in front of the tamer, in degrees. */
    public static final double VIEW_DEGREES = 50;
    private static final double VIEW_COS = Math.cos(Math.toRadians(VIEW_DEGREES));
    /** A body this close is in view whichever way the tamer looks. */
    private static final double TOUCHING = 1.5;

    private AnalyzerWitness() {}

    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % INTERVAL_TICKS != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isAlive() && !player.isSpectator()) observe(player);
        }
    }

    /** One look around for {@code player}, {@link #INTERVAL_TICKS} after the last. */
    public static void observe(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        AnalyzerSavedData data = AnalyzerSavedData.get(server);
        PartySavedData parties = PartySavedData.get(server);
        UUID id = player.getUUID();
        List<Identifier> own = PartyManager.knownSpecies(parties, id);
        AnalyzerRecord record = data.record(id);
        if (record == null) {
            // The first look of all: what the tamer already has is on record without an announcement.
            data.open(id, own);
            parties.session(id).sync.invalidate();
            com.digicube.scan.Scan.sight(player, own);
            return;
        }
        boolean changed = false;
        for (Identifier species : own) if (record.add(species)) changed |= announce(player, AnalyzerDiscoveryPayload.species(species));

        Map<Identifier, Integer> watch = data.watch(id);
        Set<Identifier> seen = new HashSet<>();
        for (LivingEntity body : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(RANGE), LivingEntity::isAlive)) {
            if (body != player && !sees(player, body)) continue;
            CombatMarkState marks = (CombatMarkState) body;
            for (CombatMark mark : CombatMark.showing(marks.digicube$marks(), marks.digicube$marks2())) {
                if (record.add(mark)) changed |= announce(player, AnalyzerDiscoveryPayload.mark(mark));
            }
            if (body instanceof DigimonEntity digimon && !record.knows(digimon.getSpeciesId())) seen.add(digimon.getSpeciesId());
        }
        watch.keySet().retainAll(seen);
        for (Identifier species : seen) {
            if (watch.merge(species, INTERVAL_TICKS, Integer::sum) < WATCH_TICKS) continue;
            watch.remove(species);
            if (record.add(species)) changed |= announce(player, AnalyzerDiscoveryPayload.species(species));
        }
        // A family on record for the first time fills part of its Digitama; a record kept from before the scan does too.
        com.digicube.scan.Scan.sight(player, record.species());
        if (!changed) return;
        data.setDirty();
        // An open Digivice shows the new entry at once.
        parties.session(id).sync.invalidate();
    }

    private static boolean announce(ServerPlayer player, AnalyzerDiscoveryPayload discovery) {
        Services.PLATFORM.sendToPlayer(player, discovery);
        return true;
    }

    /** Whether {@code player} can read {@code body}: near, in front, visible and with a clear line to it. */
    public static boolean sees(ServerPlayer player, LivingEntity body) {
        if (body.isInvisible() || body.level() != player.level()) return false;
        Vec3 to = body.getBoundingBox().getCenter().subtract(player.getEyePosition());
        return inView(player.getViewVector(1), to) && player.hasLineOfSight(body);
    }

    /**
     * @param look the tamer's line of sight, a unit vector
     * @param to   from the tamer's eyes to the middle of the body
     */
    public static boolean inView(Vec3 look, Vec3 to) {
        double distance = to.length();
        if (distance > RANGE) return false;
        return distance <= TOUCHING || look.dot(to) / distance >= VIEW_COS;
    }

    /** The species the Analyzer may describe for {@code player}: the record, and what the tamer owns this very tick. */
    public static List<Identifier> species(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Set<Identifier> known = new TreeSet<>(PartyManager.knownSpecies(PartySavedData.get(server), player.getUUID()));
        AnalyzerRecord record = AnalyzerSavedData.get(server).record(player.getUUID());
        if (record != null) known.addAll(record.species());
        return List.copyOf(known);
    }

    /** The marks the Analyzer may describe for {@code player}, as {@link CombatMark#mask}. */
    public static int marks(ServerPlayer player) {
        AnalyzerRecord record = AnalyzerSavedData.get(player.level().getServer()).record(player.getUUID());
        return record == null ? 0 : CombatMark.mask(record.marks());
    }

    public static void disconnect(ServerPlayer player) {
        AnalyzerSavedData.get(player.level().getServer()).forgetSession(player.getUUID());
    }

    /** Developer panel: every species and every mark goes on record, without an announcement. */
    public static String revealAll(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        AnalyzerSavedData data = AnalyzerSavedData.get(server);
        AnalyzerRecord record = data.open(player.getUUID(), DigimonSpeciesRegistry.all().stream().map(DigimonSpecies::id).toList());
        for (CombatMark mark : CombatMark.values()) record.add(mark);
        PartySavedData.get(server).session(player.getUUID()).sync.invalidate();
        return "Recorded " + record.species().size() + " Digimon and " + record.marks().size() + " marks";
    }

    /** Developer panel: the record starts over empty; the tamer's own Digimon come back at the next look. */
    public static String forgetAll(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        AnalyzerSavedData.get(server).open(player.getUUID(), List.of());
        PartySavedData.get(server).session(player.getUUID()).sync.invalidate();
        return "The Analyzer record starts over";
    }
}
