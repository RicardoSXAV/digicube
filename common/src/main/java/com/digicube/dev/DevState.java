package com.digicube.dev;

import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.party.PartyMember;
import com.digicube.party.PartyMemberView;
import com.digicube.party.PartySavedData;
import com.digicube.spawn.WildSpawnSettings;
import com.digicube.spawn.WildSpawner;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;

/**
 * What the developer panel shows, captured on the server where all of it lives. The tag
 * keys below are the contract with the client screen; add keys freely, never rename one.
 *
 * <pre>
 * party:      list of {name, species, level, xp, xpToNext, health, maxHealth, slot, deployed, defeated}
 * collection: number of owned Digimon
 * wild:       {count, cap, enabled, interval, maxPerPlayer, maxPerLevel} for the player's dimension
 * player:     {dimension, x, y, z}
 * </pre>
 */
public final class DevState {
    public static final String PARTY = "party";
    public static final String COLLECTION = "collection";
    public static final String WILD = "wild";
    public static final String PLAYER = "player";
    /** {@code {species, values, bundled}} for the species the request named; see {@link SpeciesTuning}. */
    public static final String TUNE = "tune";

    private DevState() {}

    /**
     * @param args the request's arguments; a {@code species} names the sheet whose tunable
     *             numbers go into {@code tune} as {@code values} (runtime) and {@code bundled} (as shipped)
     */
    public static CompoundTag capture(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        CompoundTag state = new CompoundTag();
        DigimonSpeciesRegistry.resolve(args.getStringOr(DevActions.SPECIES_ARG, "")).ifPresent(species -> {
            CompoundTag tune = new CompoundTag();
            tune.putString("species", species.id().toString());
            tune.put("values", SpeciesTuning.values(species));
            tune.put("bundled", SpeciesTuning.values(DigimonSpeciesBootstrap.bundled(species.id())));
            state.put(TUNE, tune);
        });
        PartySavedData data = PartySavedData.get(server);
        List<PartyMember> owned = data.roster().owned(player.getUUID()).stream()
                // Party slots first, in order; the reserve after them.
                .sorted(Comparator.comparingInt(member -> member.active() ? member.slot() : Integer.MAX_VALUE))
                .toList();
        ListTag party = new ListTag();
        for (PartyMember member : owned) {
            PartyMemberView view = PartyMemberView.of(data, member);
            CompoundTag entry = new CompoundTag();
            entry.putString("name", view.nickname().isEmpty() ? view.species().getPath() : view.nickname());
            entry.putString("species", view.species().toString());
            entry.putInt("level", view.level());
            entry.putInt("xp", view.xp());
            entry.putInt("xpToNext", Progression.xpToNext(view.level()));
            entry.putFloat("health", view.health());
            entry.putFloat("maxHealth", view.maxHealth());
            entry.putInt("slot", view.slot());
            entry.putBoolean("deployed", view.deployed());
            entry.putBoolean("defeated", member.defeated());
            party.add(entry);
        }
        state.put(PARTY, party);
        state.putInt(COLLECTION, owned.size());

        ServerLevel level = player.level();
        WildSpawnSettings settings = WildSpawnSettings.get(server);
        CompoundTag wild = new CompoundTag();
        wild.putInt("count", WildSpawner.wild(level).size());
        wild.putInt("cap", settings.cap(level.players().size()));
        wild.putBoolean("enabled", settings.enabled());
        wild.putInt("interval", settings.intervalTicks());
        wild.putInt("maxPerPlayer", settings.maxPerPlayer());
        wild.putInt("maxPerLevel", settings.maxPerLevel());
        state.put(WILD, wild);

        BlockPos position = player.blockPosition();
        CompoundTag where = new CompoundTag();
        where.putString("dimension", level.dimension().identifier().toString());
        where.putInt("x", position.getX());
        where.putInt("y", position.getY());
        where.putInt("z", position.getZ());
        state.put(PLAYER, where);
        return state;
    }
}
