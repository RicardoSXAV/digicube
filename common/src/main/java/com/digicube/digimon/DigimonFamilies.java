package com.digicube.digimon;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The families of the species sheets: a family is a first form (a Baby II) and every form its routes lead to, the
 * evolution tree the Digivice draws. The scan fills one Digitama per family, and a Digitama hatches into the family's
 * first form. Families are listed in the order their earliest member appears in the species catalog, which puts
 * Koromon's, Tsunomon's, Bukamon's and Motimon's in that order.
 *
 * <p>Derived from the registry on first use and kept while the registry keeps its size; species are loaded once at
 * startup and never change afterwards.
 */
public final class DigimonFamilies {
    private static Map<Identifier, Identifier> roots = Map.of();
    private static List<Identifier> families = List.of();
    private static int builtFor = -1;

    private DigimonFamilies() {}

    /** Every family's first form, in catalog order of its earliest member. Only first forms with routes count. */
    public static synchronized List<Identifier> all() {
        build();
        return families;
    }

    /** The first form of {@code species}' family, or null when it belongs to none. */
    public static synchronized Identifier of(Identifier species) {
        build();
        return roots.get(species);
    }

    /** Index of {@code family} in {@link #all}, or -1. */
    public static int index(Identifier family) {
        return all().indexOf(family);
    }

    /** Every member of {@code family}: the first form, then each form in the order its routes list it. */
    public static List<Identifier> members(Identifier family) {
        List<Identifier> members = new ArrayList<>();
        collect(family, members, 0);
        return members;
    }

    private static void collect(Identifier id, List<Identifier> into, int depth) {
        if (into.contains(id) || depth > 6 || DigimonSpeciesRegistry.get(id).isEmpty()) return;
        into.add(id);
        for (Evolution route : DigimonSpeciesRegistry.getOrThrow(id).evolutions()) collect(route.target(), into, depth + 1);
    }

    private static void build() {
        if (builtFor == DigimonSpeciesRegistry.size()) return;
        Map<Identifier, Integer> order = new LinkedHashMap<>();
        for (DigimonSpecies species : DigimonSpeciesRegistry.all()) order.put(species.id(), order.size());
        Map<Identifier, Identifier> parents = new LinkedHashMap<>();
        for (DigimonSpecies species : DigimonSpeciesRegistry.all())
            for (Evolution route : species.evolutions())
                if (DigimonSpeciesRegistry.get(route.target()).isPresent()) parents.putIfAbsent(route.target(), species.id());
        Map<Identifier, Identifier> found = new LinkedHashMap<>();
        Map<Identifier, Integer> earliest = new LinkedHashMap<>();
        for (DigimonSpecies species : DigimonSpeciesRegistry.all()) {
            Identifier root = species.id();
            for (int i = 0; i < 6 && parents.containsKey(root); i++) root = parents.get(root);
            DigimonSpecies first = DigimonSpeciesRegistry.getOrThrow(root);
            if (first.stage() != DigimonStage.BABY_II || first.evolutions().isEmpty()) continue;
            found.put(species.id(), root);
            earliest.merge(root, order.get(species.id()), Math::min);
        }
        List<Identifier> sorted = new ArrayList<>(earliest.keySet());
        sorted.sort((a, b) -> Integer.compare(earliest.get(a), earliest.get(b)));
        roots = Collections.unmodifiableMap(found);
        families = List.copyOf(sorted);
        builtFor = DigimonSpeciesRegistry.size();
    }
}
