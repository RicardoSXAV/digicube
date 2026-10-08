package com.digicube.digimon;

import net.minecraft.resources.Identifier;
import java.util.*;

/**
 * Ordered, data-authored routes the tamer picks among. Unsupported requirements fail closed. A Baby II's routes are its
 * growth into one of its family's Rookies (from {@link Progression#GROWTH_LEVEL}, for good, no DigiSoul); a Rookie's
 * are its Champions (from {@link Progression#CHAMPION_LEVEL}, on DigiSoul), and which one it takes is the tamer's choice
 * ({@link EvolutionState#line}), made once. An Armor form in a Rookie's routes counts as its Champion.
 */
public final class EvolutionRules {
    private EvolutionRules() {}
    /** The stages a Rookie digivolves into. */
    public static boolean championTier(DigimonStage stage){return stage==DigimonStage.ADULT||stage==DigimonStage.ARMOR;}
    /** A Rookie's Champion route with no requirement this version cannot check. */
    public static boolean supported(Evolution route) {
        return plain(route) && DigimonSpeciesRegistry.get(route.target()).map(s->championTier(s.stage())).orElse(false);
    }
    private static boolean plain(Evolution route){return route.minBond()==0 && route.minTraining()==0 && !route.hasWeightRequirement() && !route.hasItemRequirement();}
    /** A Baby II's growth into a Rookie with no requirement this version cannot check. */
    private static boolean growthRoute(Evolution route) {
        return plain(route) && DigimonSpeciesRegistry.get(route.target()).map(s->s.stage()==DigimonStage.CHILD).orElse(false);
    }
    /** Every supported route of a Baby II or a Rookie, in sheet order, whatever its level. */
    public static List<Evolution> routes(Identifier source) {
        return DigimonSpeciesRegistry.get(source).map(s->switch(s.stage()) {
            case CHILD -> s.evolutions().stream().filter(EvolutionRules::supported).toList();
            case BABY_II -> s.evolutions().stream().filter(EvolutionRules::growthRoute).toList();
            default -> List.<Evolution>of();
        }).orElse(List.of());
    }
    /** The level a route opens at: its own, never under {@link Progression#GROWTH_LEVEL} for a growth or {@link Progression#CHAMPION_LEVEL} for a Champion. */
    public static int level(Evolution route){return Math.max(growth(route)?Progression.GROWTH_LEVEL:Progression.CHAMPION_LEVEL,route.minLevel());}
    /** Whether the route is a Baby II's growth into a Rookie. */
    public static boolean growth(Evolution route){return DigimonSpeciesRegistry.get(route.target()).map(s->s.stage()==DigimonStage.CHILD).orElse(false);}
    /** The forms {@code source} at {@code level} could take now, in sheet order, before any choice binds it. */
    public static List<Identifier> targets(Identifier source,int level) {
        return routes(source).stream().filter(e->level>=level(e)).map(Evolution::target).toList();
    }
    /** The first of {@link #targets}: whether {@code source} can grow or digivolve at all at this level. */
    public static Optional<Identifier> target(Identifier source,int level) {
        return targets(source,level).stream().findFirst();
    }
    public static List<Identifier> origins(Identifier champion) {
        return DigimonSpeciesRegistry.all().stream().filter(s->validOrigin(s.id(),champion)).map(DigimonSpecies::id).sorted().toList();
    }
    /** Whether {@code rookie} is a Rookie with a route into the Champion {@code champion}: the form a Champion returns to. */
    public static boolean validOrigin(Identifier rookie,Identifier champion) {
        if(rookie==null||!rookie(rookie))return false;
        return routes(rookie).stream().anyMatch(e->e.target().equals(champion));
    }
    public static boolean champion(Identifier species){return DigimonSpeciesRegistry.get(species).map(s->championTier(s.stage())).orElse(false);}
    public static boolean rookie(Identifier species){return DigimonSpeciesRegistry.get(species).map(s->s.stage()==DigimonStage.CHILD).orElse(false);}
    /** A Baby II: it grows, permanently and without DigiSoul, into one of its family's Rookies. */
    public static boolean baby(Identifier species){return DigimonSpeciesRegistry.get(species).map(s->s.stage()==DigimonStage.BABY_II).orElse(false);}
    /** A form whose routes the tamer picks among in its tree: a Baby II's growth or a Rookie's digivolution. */
    public static boolean chooser(Identifier species){return rookie(species)||baby(species);}
    /** The lowest level at which {@code species} could take any route: growth's for a Baby II, the Champion level otherwise. */
    public static int minimumLevel(Identifier species){return baby(species)?Progression.GROWTH_LEVEL:Progression.CHAMPION_LEVEL;}
}
