package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every {@link DigimonSpecies} the game knows about, keyed by id.
 *
 * <p>This is a plain in-memory map on purpose. Species are content, not vanilla
 * registry objects, which keeps them reloadable and easy to drive from JSON later
 * (see {@code data/digicube/species/} and the roadmap in AGENTS.md).
 *
 * <p>Thread safety: populated once during mod init / datapack reload, read-only
 * afterwards. Do not mutate it during gameplay.
 */
public final class DigimonSpeciesRegistry {

    private static final Map<ResourceLocation, DigimonSpecies> SPECIES = new LinkedHashMap<>();

    private DigimonSpeciesRegistry() {}

    public static void register(DigimonSpecies species) {
        DigimonSpecies previous = SPECIES.put(species.id(), species);
        if (previous != null) {
            Constants.LOG.warn("Species {} was registered twice; the later definition wins.", species.id());
        }
    }

    public static Optional<DigimonSpecies> get(ResourceLocation id) {
        return Optional.ofNullable(SPECIES.get(id));
    }

    /**
     * @throws IllegalArgumentException if the species is missing. Use this only where
     *         a missing species is a bug rather than bad user data.
     */
    public static DigimonSpecies getOrThrow(ResourceLocation id) {
        DigimonSpecies species = SPECIES.get(id);
        if (species == null) {
            throw new IllegalArgumentException("Unknown Digimon species: " + id);
        }
        return species;
    }

    public static Collection<DigimonSpecies> all() {
        return Collections.unmodifiableCollection(SPECIES.values());
    }

    public static List<DigimonSpecies> byStage(DigimonStage stage) {
        return SPECIES.values().stream().filter(s -> s.stage() == stage).toList();
    }

    public static int size() {
        return SPECIES.size();
    }

    /** Wipes the registry. Called before a reload; not for gameplay code. */
    public static void clear() {
        SPECIES.clear();
    }
}
