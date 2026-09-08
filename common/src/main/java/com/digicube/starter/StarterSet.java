package com.digicube.starter;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The partners a new player may choose from, in display order, and the level they start
 * at. Loaded once at startup from the bundled {@code data/digicube/starters.json} on both
 * sides, like the species and spawn tables; only the server reads it at runtime, the
 * client shows whatever the offer payload carries.
 *
 * @param species ordered species ids, shown left to right
 * @param level   level of the granted partner
 */
public record StarterSet(List<Identifier> species, int level) {

    public static final int MIN_STARTERS = 2;
    /** Also the bound of the offer payload's species list. */
    public static final int MAX_STARTERS = 8;
    public static final int DEFAULT_LEVEL = Progression.MIN_LEVEL;

    private static final String PATH = "/data/" + Constants.MOD_ID + "/starters.json";
    private static StarterSet builtIn;

    public StarterSet {
        species = List.copyOf(species);
        if (species.size() < MIN_STARTERS || species.size() > MAX_STARTERS) {
            throw new IllegalArgumentException("a starter set needs " + MIN_STARTERS + " to " + MAX_STARTERS
                    + " species, got " + species.size());
        }
        if (new HashSet<>(species).size() != species.size()) {
            throw new IllegalArgumentException("a starter set cannot list a species twice");
        }
        if (level < Progression.MIN_LEVEL || level > Progression.LEVEL_CAP) {
            throw new IllegalArgumentException("starter level must be " + Progression.MIN_LEVEL + ".." + Progression.LEVEL_CAP
                    + ", got " + level);
        }
    }

    /** Loads the bundled set. Call after the species registry is populated. */
    public static void registerBuiltIn() {
        builtIn = load(id -> DigimonSpeciesRegistry.get(id).isPresent());
        Constants.LOG.info("Registered {} starter Digimon at level {}.", builtIn.species().size(), builtIn.level());
    }

    /** The bundled set; fails loudly if {@link #registerBuiltIn()} has not run. */
    public static StarterSet get() {
        if (builtIn == null) throw new IllegalStateException("Starter set not loaded");
        return builtIn;
    }

    /**
     * Parse and validate the bundled file.
     * @param knownSpecies tells whether a species id is registered
     */
    public static StarterSet load(Predicate<Identifier> knownSpecies) {
        try (var input = StarterSet.class.getResourceAsStream(PATH)) {
            if (input == null) throw new IllegalStateException("Missing bundled starter resource " + PATH);
            return parse(GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8)), knownSpecies);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled starter resource " + PATH, e);
        }
    }

    /**
     * Decode one set. Unqualified species names use the mod namespace.
     * @param json         the document
     * @param knownSpecies tells whether a species id is registered
     * @return validated immutable set
     */
    public static StarterSet parse(JsonObject json, Predicate<Identifier> knownSpecies) {
        List<Identifier> species = new ArrayList<>();
        for (var element : GsonHelper.getAsJsonArray(json, "starters")) {
            String value = GsonHelper.convertToString(element, "starter");
            Identifier id = value.contains(":") ? Identifier.tryParse(value) : Identifier.tryBuild(Constants.MOD_ID, value);
            if (id == null) throw new IllegalArgumentException("starters: invalid species id " + value);
            if (!knownSpecies.test(id)) throw new IllegalArgumentException("starters: unknown species " + id);
            species.add(id);
        }
        return new StarterSet(species, GsonHelper.getAsInt(json, "level", DEFAULT_LEVEL));
    }

    public boolean contains(Identifier id) {
        return species.contains(id);
    }

    /** Species ids as a set, for callers that only test membership. */
    public Set<Identifier> asSet() {
        return Set.copyOf(species);
    }
}
