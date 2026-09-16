package com.digicube.digimon;

import net.minecraft.resources.Identifier;
import java.util.*;

/** Ordered, data-authored Rookie to Champion rules. Unsupported requirements fail closed. */
public final class EvolutionRules {
    private EvolutionRules() {}
    public static boolean supported(Evolution route) {
        return route.minBond()==0 && route.minTraining()==0 && !route.hasWeightRequirement() && !route.hasItemRequirement()
                && DigimonSpeciesRegistry.get(route.target()).map(s->s.stage()==DigimonStage.ADULT).orElse(false);
    }
    public static Optional<Identifier> target(Identifier source,int level) {
        return DigimonSpeciesRegistry.get(source).filter(s->s.stage()==DigimonStage.CHILD)
                .flatMap(s->s.evolutions().stream().filter(EvolutionRules::supported)
                        .filter(e->level>=Math.max(Progression.CHAMPION_LEVEL,e.minLevel())).map(Evolution::target).findFirst());
    }
    public static List<Identifier> origins(Identifier champion) {
        return DigimonSpeciesRegistry.all().stream().filter(s->validOrigin(s.id(),champion)).map(DigimonSpecies::id).sorted().toList();
    }
    public static boolean validOrigin(Identifier rookie,Identifier champion) {
        if(rookie==null)return false;
        return DigimonSpeciesRegistry.get(rookie).filter(s->s.stage()==DigimonStage.CHILD)
                .map(s->s.evolutions().stream().anyMatch(e->supported(e)&&e.target().equals(champion))).orElse(false);
    }
    public static boolean champion(Identifier species){return DigimonSpeciesRegistry.get(species).map(s->s.stage()==DigimonStage.ADULT).orElse(false);}
    public static boolean rookie(Identifier species){return DigimonSpeciesRegistry.get(species).map(s->s.stage()==DigimonStage.CHILD).orElse(false);}
}
