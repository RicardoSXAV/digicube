package com.digicube.dev;

import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.entity.DigimonEntity;
import com.digicube.party.PartyManager;
import com.digicube.party.PartyMember;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The developer panel's tools, by id. Adding a tool is one {@link #register} call here
 * and one control on the client screen; the payloads never change. Argument keys are
 * shared constants so both sides spell them the same way.
 */
public final class DevActions {
    public static final String REFRESH = "refresh";
    public static final String SPAWN = "spawn";
    public static final String GIVE = "give";
    public static final String HEAL = "heal";
    /** Tune the selected species' speeds at runtime: {@code values} holds {@link SpeciesTuning} keys. */
    public static final String TUNE = "tune";
    /** Put the bundled sheet back. */
    public static final String TUNE_RESET = "tune_reset";
    /** Write the current runtime numbers into the sheet in the repository. */
    public static final String TUNE_WRITE = "tune_write";
    /** Replace the player's inventory with a {@link PlayerLoadouts} loadout named by {@link #LOADOUT_ARG}. */
    public static final String LOADOUT = "loadout";
    /** Empty the player's inventory, offhand and armor. */
    public static final String LOADOUT_CLEAR = "loadout_clear";

    public static final String SPECIES_ARG = "species";
    public static final String LEVEL_ARG = "level";
    public static final String VALUES_ARG = "values";
    public static final String LOADOUT_ARG = "loadout";

    private static final Map<String, DevAction> ACTIONS = new LinkedHashMap<>();

    static {
        register(REFRESH, (server, player, args) -> "");
        register(SPAWN, DevActions::spawn);
        register(GIVE, DevActions::give);
        register(HEAL, DevActions::heal);
        register(TUNE, DevActions::tune);
        register(TUNE_RESET, DevActions::tuneReset);
        register(TUNE_WRITE, DevActions::tuneWrite);
        register(LOADOUT, DevActions::loadout);
        register(LOADOUT_CLEAR, DevActions::loadoutClear);
    }

    private DevActions() {}

    public static void register(String id, DevAction action) {
        ACTIONS.put(id, action);
    }

    public static Optional<DevAction> get(String id) {
        return Optional.ofNullable(ACTIONS.get(id));
    }

    public static Set<String> ids() {
        return Collections.unmodifiableSet(ACTIONS.keySet());
    }

    // --- built-in tools ----------------------------------------------------------------

    private static String spawn(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        DigimonSpecies species = species(args);
        if (species == null) return unknownSpecies(args);
        int level = level(args);
        DigimonEntity digimon = DigimonEntity.spawnWild(player.level(), species, level, player.position());
        if (digimon == null) return "Could not create the Digimon entity";
        return "Spawned wild " + species.id().getPath() + " Lv " + digimon.getLevel();
    }

    private static String give(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        DigimonSpecies species = species(args);
        if (species == null) return unknownSpecies(args);
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(player.level(), EntitySpawnReason.COMMAND);
        if (digimon == null) return "Could not create the Digimon entity";
        digimon.initializeAs(species, level(args));
        PartyMember member = PartyManager.give(player, digimon);
        return (member.active() ? "Added " : "Stored in reserve ") + species.id().getPath() + " Lv " + member.level()
                + (member.active() ? " to the party" : ": the party is full");
    }

    private static String heal(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        int healed = PartyManager.healAll(player);
        return healed == 0 ? "No Digimon to heal" : "Healed " + healed + " Digimon";
    }

    private static String tune(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        DigimonSpecies species = species(args);
        if (species == null) return unknownSpecies(args);
        try {
            DigimonSpecies tuned = SpeciesTuning.apply(server, species, args.getCompoundOrEmpty(VALUES_ARG));
            return "Applied to " + tuned.id().getPath() + " and its live Digimon";
        } catch (IllegalArgumentException rejected) {
            // User input failed the sheet validators; the reply is the feedback.
            return "Rejected: " + rejected.getMessage();
        }
    }

    private static String tuneReset(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        DigimonSpecies species = species(args);
        if (species == null) return unknownSpecies(args);
        SpeciesTuning.reset(server, species.id());
        return "Reset " + species.id().getPath() + " to its bundled sheet";
    }

    private static String tuneWrite(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        DigimonSpecies species = species(args);
        if (species == null) return unknownSpecies(args);
        try {
            Path file = SpeciesSheetWriter.write(species);
            return "Wrote " + file.getFileName() + " (" + file.getParent().getFileName() + "/)";
        } catch (IOException failure) {
            // The write is the action; its failure is the outcome the developer needs to see.
            return "Write failed: " + failure.getMessage();
        }
    }

    private static String loadout(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        String id = args.getStringOr(LOADOUT_ARG, "");
        PlayerLoadout loadout = PlayerLoadouts.get(id).orElse(null);
        if (loadout == null) return "Unknown loadout: " + id;
        int stacks = loadout.equip(server, player);
        return "Equipped the " + loadout.label() + " loadout: " + stacks + " stacks";
    }

    private static String loadoutClear(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        PlayerLoadout.clear(player);
        return "Cleared the inventory and armor";
    }

    private static DigimonSpecies species(CompoundTag args) {
        return DigimonSpeciesRegistry.resolve(args.getStringOr(SPECIES_ARG, "")).orElse(null);
    }

    private static int level(CompoundTag args) {
        return Progression.clampLevel(args.getIntOr(LEVEL_ARG, Progression.MIN_LEVEL));
    }

    private static String unknownSpecies(CompoundTag args) {
        return "Unknown species: " + args.getStringOr(SPECIES_ARG, "");
    }
}
