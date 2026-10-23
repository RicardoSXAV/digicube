package com.digicube.digimon;

import java.util.Objects;

/**
 * A route's raising in CLASSIC, the {@code raising} of an evolution in a species sheet: the attribute of the wild
 * Digimon a partner keeps beating to take this route, and the place it is at home in. {@link EvolutionRules#raised}
 * takes the ready route whose raising the partner's record meets best.
 *
 * @param foe   the attribute of the foes it beats (the triangle's prey of the target: Vaccine beats Virus, Virus beats
 *              Data, Data beats Vaccine)
 * @param place where it fights
 */
public record RaisingCondition(DigimonAttribute foe, RaisingPlace place) {
    public RaisingCondition {
        Objects.requireNonNull(foe, "raising foe");
        Objects.requireNonNull(place, "raising place");
    }
}
