package com.digicube.digimon;

import net.minecraft.resources.Identifier;

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
 * @param attacks      moves in priority order (first usable one wins); empty = cannot fight
 * @param body         physical dimensions, model scale and optional mount settings
 * @param locomotion   follow distances and walking/running speed modifiers
 */
public record DigimonSpecies(
        Identifier id,
        DigimonStage stage,
        DigimonAttribute attribute,
        int baseHealth,
        int baseAttack,
        int baseDefence,
        float baseSpeed,
        List<Evolution> evolutions,
        List<DigimonAttack> attacks,
        DigimonBody body,
        DigimonLocomotion locomotion
) {

    public DigimonSpecies {
        Objects.requireNonNull(id, "species id");
        Objects.requireNonNull(stage, "species stage");
        Objects.requireNonNull(attribute, "species attribute");
        Objects.requireNonNull(body, "species body");
        Objects.requireNonNull(locomotion, "species locomotion");
        if (body.mount().map(m -> m.flight()!=null).orElse(false) && !locomotion.canFly()) {
            throw new IllegalArgumentException("Aerial riding requires a flight reserve and locomotion definition");
        }
        // Defensive copies: a species must stay immutable once registered.
        evolutions = List.copyOf(evolutions);
        attacks = List.copyOf(attacks);
    }

    /** Translation key for the species name, e.g. {@code digimon.digicube.agumon}. */
    public String translationKey() {
        return "digimon." + id.getNamespace() + "." + id.getPath();
    }

    /** The path segment only, e.g. {@code agumon}. Handy for asset lookups. */
    public String name() {
        return id.getPath();
    }

    public boolean canFight() {
        return !attacks.isEmpty();
    }
}
