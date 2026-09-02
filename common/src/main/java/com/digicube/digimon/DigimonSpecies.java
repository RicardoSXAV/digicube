package com.digicube.digimon;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * The immutable, shared definition of one Digimon -- the "species sheet".
 *
 * <p>There is exactly ONE of these per Digimon, held by {@link DigimonSpeciesRegistry}.
 * Anything that differs between two individuals of the same species (level, bond,
 * nickname, current HP) belongs on the entity, never here.
 *
 * @param id           unique identifier, e.g. {@code digicube:agumon}
 * @param stage        evolution stage
 * @param attribute    attribute used for the damage triangle
 * @param baseHealth   health at level 1
 * @param baseAttack   attack at level 1
 * @param baseDefence  defence at level 1
 * @param baseSpeed    movement speed multiplier
 * @param evolutions   digivolutions available from this species, most specific first
 */
public record DigimonSpecies(
        ResourceLocation id,
        DigimonStage stage,
        DigimonAttribute attribute,
        int baseHealth,
        int baseAttack,
        int baseDefence,
        float baseSpeed,
        List<Evolution> evolutions
) {

    public DigimonSpecies {
        Objects.requireNonNull(id, "species id");
        Objects.requireNonNull(stage, "species stage");
        Objects.requireNonNull(attribute, "species attribute");
        // Defensive copy: a species must stay immutable once registered.
        evolutions = List.copyOf(evolutions);
    }

    /** Translation key for the species name, e.g. {@code digimon.digicube.agumon}. */
    public String translationKey() {
        return "digimon." + id.getNamespace() + "." + id.getPath();
    }

    /** The path segment only, e.g. {@code agumon}. Handy for asset lookups. */
    public String name() {
        return id.getPath();
    }
}
