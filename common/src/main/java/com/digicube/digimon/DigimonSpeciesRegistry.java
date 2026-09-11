package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every {@link DigimonSpecies} the game knows about, keyed by id.
 *
 * <p>This is a plain in-memory map on purpose. Species are content, not vanilla
 * registry objects. Bundled definitions come from {@code data/digicube/species/};
 * datapack reload and server catalog synchronization remain future work.
 *
 * <p>Thread safety: populated once during mod init, read-only
 * afterwards. Do not mutate it during gameplay.
 */
public final class DigimonSpeciesRegistry {

    private static final Map<Identifier, DigimonSpecies> SPECIES = new LinkedHashMap<>();
    private static Map<Identifier, Identifier> commandNames = Map.of();

    private DigimonSpeciesRegistry() {}

    public static void register(DigimonSpecies species) {
        DigimonSpecies previous = SPECIES.put(species.id(), species);
        if (previous != null) {
            Constants.LOG.warn("Species {} was registered twice; the later definition wins.", species.id());
        }
    }

    /** Swaps a species for a tuned or reloaded copy of itself. Developer tooling only. */
    public static void replace(DigimonSpecies species) {
        SPECIES.put(species.id(), species);
    }

    public static Optional<DigimonSpecies> get(Identifier id) {
        return Optional.ofNullable(SPECIES.get(id));
    }

    /**
     * @throws IllegalArgumentException if the species is missing. Use this only where
     *         a missing species is a bug rather than bad user data.
     */
    public static DigimonSpecies getOrThrow(Identifier id) {
        DigimonSpecies species = SPECIES.get(id);
        if (species == null) {
            throw new IllegalArgumentException("Unknown Digimon species: " + id);
        }
        return species;
    }

    /**
     * Looks a species up the way a person types it: {@code agumon} is shorthand for
     * {@code digicube:agumon}. An id parsed from a bare name lands in the {@code minecraft}
     * namespace, so that namespace is treated as "none given".
     */
    public static Optional<DigimonSpecies> resolve(Identifier id) {
        Optional<DigimonSpecies> species = get(id);
        if (species.isEmpty() && Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace())) {
            id = Constants.id(id.getPath());
            species = get(id);
        }
        if (species.isEmpty()) {
            for (var entry : commandNames.entrySet()) {
                if (entry.getValue().equals(id)) return get(entry.getKey());
            }
        }
        return species;
    }

    /** {@link #resolve(Identifier)} for raw text; an unparseable string resolves to nothing. */
    public static Optional<DigimonSpecies> resolve(String text) {
        Identifier id = Identifier.tryParse(text.trim().toLowerCase(Locale.ROOT));
        return id == null ? Optional.empty() : resolve(id);
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

    /** @return the suggested command spelling while saved species ids remain stable */
    public static Identifier commandId(Identifier speciesId) {
        return commandNames.getOrDefault(speciesId, speciesId);
    }

    /** Validate aliases before publishing them during catalog initialization. */
    static void setCommandNames(Map<Identifier, Identifier> names) {
        var used = new java.util.HashSet<Identifier>();
        for (var entry : names.entrySet()) {
            if (!SPECIES.containsKey(entry.getKey()) || !used.add(entry.getValue())
                    || SPECIES.containsKey(entry.getValue()) && !entry.getKey().equals(entry.getValue())) {
                throw new IllegalArgumentException("Invalid or ambiguous species command name: " + entry);
            }
        }
        commandNames = Map.copyOf(names);
    }

    /** Wipes the registry. Called before a reload; not for gameplay code. */
    public static void clear() {
        SPECIES.clear();
        commandNames = Map.of();
    }
}
