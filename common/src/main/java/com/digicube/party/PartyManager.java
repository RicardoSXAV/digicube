package com.digicube.party;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/** Server-authoritative storage, migration and deployment. Loader events only delegate here. */
public final class PartyManager {
    private PartyManager() {}

    public static PartyMember give(ServerPlayer player, DigimonEntity digimon) {
        PartySavedData data = PartySavedData.get(player.level().getServer());
        digimon.setOwner(player);
        PartyMember member = remember(data, digimon);
        // A cramped room must not force a large partner inside a wall. It stays selected
        // and waits for space, while subsequent gifts still respect the three-slot cap.
        if (member.active()) deploy(data, member, player);
        return member;
    }

    private static CompoundTag save(DigimonEntity digimon) {
        try (var problems = new ProblemReporter.ScopedCollector(Constants.LOG)) {
            TagValueOutput output = TagValueOutput.createWithContext(problems, digimon.registryAccess());
            digimon.saveWithoutId(output);
            CompoundTag tag = output.buildResult();
            // Riding another entity / carrying a player is not part of a stored partner.
            tag.remove("Passengers");
            return tag;
        }
    }

    private static String nickname(DigimonEntity entity) {
        return entity.getCustomName() == null ? "" : entity.getCustomName().getString();
    }

    private static PartyMember remember(PartySavedData data, DigimonEntity entity) {
        PartyMember existing = data.roster().get(entity.getUUID());
        if (existing != null) return existing;
        PartyMember member = new PartyMember(entity.getUUID(), entity.getOwnerReference().getUUID(),
                entity.getSpeciesId(), nickname(entity), entity.getHealth(), entity.getMaxHealth(),
                entity.getLevel(), entity.getXp(), -1, entity.getPartyGeneration(), save(entity));
        data.roster().add(member);
        data.session(member.owner()).sync.invalidate();
        data.setDirty();
        return member;
    }

    private static void capture(PartySavedData data, PartyMember member, DigimonEntity entity) {
        member.capture(entity.getSpeciesId(), nickname(entity), entity.getHealth(), entity.getMaxHealth(),
                entity.getLevel(), entity.getXp(), save(entity));
        data.setDirty();
    }

    /**
     * Restores every Digimon a player owns to full health: deployed ones on the entity,
     * reserve ones in their saved data. A defeated partner comes back to life this way and
     * waits in reserve until it is selected again.
     * @return how many partners were healed
     */
    public static int healAll(ServerPlayer owner) {
        PartySavedData data = PartySavedData.get(owner.level().getServer());
        return healAll(data, owner.getUUID());
    }

    /** The roster-level part of {@link #healAll(ServerPlayer)}, testable without a server. */
    static int healAll(PartySavedData data, UUID owner) {
        List<PartyMember> owned = data.roster().owned(owner);
        for (PartyMember member : owned) {
            DigimonEntity live = data.live.get(member.id());
            if (live != null) {
                live.setHealth(live.getMaxHealth());
                capture(data, member, live);
                continue;
            }
            member.setHealth(member.maxHealth());
        }
        if (!owned.isEmpty()) {
            data.session(owner).sync.invalidate();
            data.setDirty();
        }
        return owned.size();
    }

    /**
     * One regeneration pulse for every partner of {@code owner} resting in the Digivice:
     * stored and below full health. A defeated partner first spends its rest
     * ({@link Progression#DEFEAT_REST_TICKS}) and then heals from zero like any other;
     * {@link #healAll} skips the rest. Deployed partners heal only through play. The pulse
     * runs while the tamer is online, every {@link Progression#RESERVE_REGEN_INTERVAL_TICKS}
     * ticks, and rest is spent at the same cadence.
     * @return how many partners regained health; resting ones are not counted
     */
    static int regenerateReserve(PartySavedData data, UUID owner) {
        int healed = 0;
        boolean changed = false;
        for (PartyMember member : data.roster().owned(owner)) {
            if (data.live.containsKey(member.id())) continue;
            if (member.resting()) {
                member.rest(Progression.RESERVE_REGEN_INTERVAL_TICKS);
                // The Digivice shows the countdown, and a rest that just ended reads as a fresh snapshot.
                data.session(owner).sync.invalidate();
                changed = true;
                continue;
            }
            float next = Progression.reserveHealth(member.health(), member.maxHealth());
            if (next == member.health()) continue;
            member.setHealth(next);
            data.session(owner).sync.recordHealth(member.id(), next, member.maxHealth());
            healed++;
            changed = true;
        }
        if (changed) data.setDirty();
        return healed;
    }

    /** A partner gained XP or a level: its owner's HUD and Digivice need a fresh snapshot. */
    public static void progressChanged(DigimonEntity entity) {
        if (!entity.isOwned() || !(entity.level() instanceof ServerLevel level)) return;
        PartySavedData.get(level.getServer()).session(entity.getOwnerReference().getUUID()).sync.invalidate();
    }

    /** Runs before chunk entities enter the world, so legacy excess partners never tick. */
    public static boolean allowLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof DigimonEntity digimon) || !digimon.isOwned()) return true;
        PartySavedData data = PartySavedData.get(level.getServer());
        PartyMember member = remember(data, digimon);
        return data.roster().accepts(member.id(), digimon.getOwnerReference().getUUID(), digimon.getPartyGeneration())
                && !data.live.containsKey(member.id());
    }

    public static void loaded(Entity entity, ServerLevel level) {
        if (entity instanceof DigimonEntity digimon && digimon.isOwned()) {
            PartySavedData data = PartySavedData.get(level.getServer());
            if (data.roster().accepts(digimon.getUUID(), digimon.getOwnerReference().getUUID(), digimon.getPartyGeneration())) {
                if (data.live.putIfAbsent(digimon.getUUID(), digimon) == null) {
                    data.session(digimon.getOwnerReference().getUUID()).sync.invalidate();
                }
            }
        }
    }

    /** Unloading becomes a recall. Generation checks reject the old chunk copy later. */
    public static void unloaded(Entity entity, ServerLevel level) {
        if (!(entity instanceof DigimonEntity digimon)) return;
        // Fabric's event is also a tracking-range event. Keep the reference while the
        // entity still exists in the level; otherwise a replacement would duplicate its UUID.
        if (digimon.getRemovalReason() == null) return;
        PartySavedData data = PartySavedData.get(level.getServer());
        if (!data.live.remove(entity.getUUID(), digimon)) return;
        PartyMember member = data.roster().get(entity.getUUID());
        if (member == null || member.generation() != digimon.getPartyGeneration()) return;
        capture(data, member, digimon);
        member.nextGeneration();
        if (digimon.getHealth() <= 0) {
            member.setSlot(-1);
            member.defeat(Progression.DEFEAT_REST_TICKS);
        }
        data.session(member.owner()).sync.invalidate();
        data.setDirty();
    }

    /** Also catches ownership assigned to an entity after its spawn (future taming). */
    public static boolean beforeEntityTick(DigimonEntity entity, ServerLevel level) {
        if (!entity.isOwned()) return true;
        PartySavedData data = PartySavedData.get(level.getServer());
        PartyMember member = remember(data, entity);
        if (!data.roster().accepts(member.id(), entity.getOwnerReference().getUUID(), entity.getPartyGeneration())
                || data.live.containsKey(member.id()) && data.live.get(member.id()) != entity) {
            entity.discard();
            return false;
        }
        if (data.live.put(member.id(), entity) == null) data.session(member.owner()).sync.invalidate();
        return true;
    }

    public static void tick(MinecraftServer server) {
        PartySavedData data = PartySavedData.get(server);
        for (DigimonEntity entity : List.copyOf(data.live.values())) {
            PartyMember member = data.roster().get(entity.getUUID());
            if (member == null) continue;
            if (entity.getHealth() <= 0) {
                capture(data, member, entity);
                member.setSlot(-1);
                member.defeat(Progression.DEFEAT_REST_TICKS);
                member.nextGeneration();
                data.live.remove(member.id());
                data.session(member.owner()).sync.invalidate();
                continue;
            }
            if (entity.isRemoved()) {
                unloaded(entity, (ServerLevel) entity.level());
                continue;
            }
            data.session(member.owner()).sync.recordHealth(member.id(), entity.getHealth(), entity.getMaxHealth());
            ServerPlayer owner = server.getPlayerList().getPlayer(member.owner());
            if (!entity.isOwnedBy(owner) || owner == null || !owner.isAlive() || owner.isSpectator()
                    || entity.level() != owner.level() || entity.distanceToSqr(owner) > 4096 || !member.active()) {
                if (!entity.isVehicle()) recall(data, member);
            } else if (server.getTickCount() % 20 == 0) {
                capture(data, member, entity);
            }
        }
        boolean regenerate = server.getTickCount() % Progression.RESERVE_REGEN_INTERVAL_TICKS == 0;
        if (server.getTickCount() % 20 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            if (regenerate) regenerateReserve(data, player.getUUID());
            for (PartyMember member : data.roster().party(player.getUUID())) {
                if (!data.live.containsKey(member.id())) deploy(data, member, player);
            }
        }
    }

    public static void disconnect(ServerPlayer player) {
        PartySavedData data = PartySavedData.get(player.level().getServer());
        for (PartyMember member : data.roster().party(player.getUUID())) recall(data, member);
        data.forgetSession(player.getUUID());
    }

    private static void recall(PartySavedData data, PartyMember member) {
        DigimonEntity live = data.live.remove(member.id());
        if (live != null) {
            live.ejectPassengers();
            live.stopRiding();
            capture(data, member, live);
            member.nextGeneration();
            live.discard();
            data.session(member.owner()).sync.invalidate();
            data.setDirty();
        }
    }

    /** Returns a translation key on failure, or the empty string on success. */
    public static String select(ServerPlayer player, UUID memberId, int slot) {
        PartySavedData data = PartySavedData.get(player.level().getServer());
        PartyMember member = data.roster().get(memberId);
        if (member == null || !member.owner().equals(player.getUUID()) || slot < -1 || slot >= PartyRoster.PARTY_SIZE) {
            return "gui.digicube.party.invalid";
        }
        if (!player.isAlive() || player.isSpectator()) return "gui.digicube.party.unavailable";
        if (member.defeated()) return "gui.digicube.party.defeated";
        PartyMember previous = slot < 0 ? null : data.roster().inSlot(player.getUUID(), slot);
        if (busy(data, member) || previous != null && busy(data, previous)) return "gui.digicube.party.riding";
        if (slot == member.slot()) return "";
        // Validate space before recalling the outgoing partner. A failed swap is a no-op.
        DigimonEntity prepared = null;
        if (slot >= 0 && !data.live.containsKey(member.id())) {
            prepared = restore(member, player);
            if (prepared == null) return "gui.digicube.party.species_missing";
            if (!positionNear(prepared, player)) return "gui.digicube.party.no_space";
        }
        int oldSlot = member.slot();
        if (!data.roster().select(player.getUUID(), member.id(), slot)) return "gui.digicube.party.invalid";
        if (previous != null && previous != member) recall(data, previous);
        if (slot < 0) recall(data, member);
        else if (prepared != null && !spawn(data, member, prepared, player.level())) {
            data.roster().select(player.getUUID(), member.id(), oldSlot);
            if (previous != null) data.roster().select(player.getUUID(), previous.id(), slot);
            data.setDirty();
            return "gui.digicube.party.spawn_failed";
        }
        data.session(member.owner()).sync.invalidate();
        data.setDirty();
        return "";
    }

    private static boolean busy(PartySavedData data, PartyMember member) {
        DigimonEntity entity = data.live.get(member.id());
        return entity != null && (entity.isVehicle() || entity.isPassenger());
    }

    public static boolean deployed(PartySavedData data, PartyMember member) {
        return data.live.containsKey(member.id());
    }

    private static DigimonEntity restore(PartyMember member, ServerPlayer player) {
        if (member.defeated() || DigimonSpeciesRegistry.get(member.species()).isEmpty()) return null;
        DigimonEntity entity = DCEntityTypes.DIGIMON.create(player.level(), EntitySpawnReason.LOAD);
        if (entity == null) return null;
        try (var problems = new ProblemReporter.ScopedCollector(Constants.LOG)) {
            entity.load(TagValueInput.create(problems, player.registryAccess(), member.entityData()));
        }
        entity.setUUID(member.id());
        entity.setOwner(player);
        entity.setDeltaMovement(Vec3.ZERO);
        return entity;
    }

    private static boolean deploy(PartySavedData data, PartyMember member, ServerPlayer player) {
        DigimonEntity entity = restore(member, player);
        return entity != null && positionNear(entity, player) && spawn(data, member, entity, player.level());
    }

    private static boolean spawn(PartySavedData data, PartyMember member, DigimonEntity entity, ServerLevel level) {
        entity.setPartyGeneration(member.nextGeneration());
        data.setDirty();
        return level.addFreshEntity(entity);
    }

    private static boolean positionNear(DigimonEntity entity, ServerPlayer player) {
        ServerLevel level = player.level();
        int start = Math.max(2, (int) Math.ceil(entity.getBbWidth()));
        for (int radius = start; radius <= start + 4; radius += 2) {
            for (int angle = 0; angle < 360; angle += 45) {
                double radians = Math.toRadians(angle - player.getYRot());
                for (int dy : new int[]{0, 1, -1, 2, -2}) {
                    BlockPos block = BlockPos.containing(player.getX() + Math.sin(radians) * radius,
                            player.getY() + dy, player.getZ() + Math.cos(radians) * radius);
                    if (!level.hasChunk(block.getX() >> 4, block.getZ() >> 4)) continue;
                    Vec3 safe = DismountHelper.findSafeDismountLocation(entity.getType(), level, block, true);
                    if (safe == null) continue;
                    entity.setPos(safe);
                    if (level.noCollision(entity) && level.getWorldBorder().isWithinBounds(entity.getBoundingBox())) {
                        entity.setYRot(player.getYRot());
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
