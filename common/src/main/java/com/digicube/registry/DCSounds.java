package com.digicube.registry;

import com.digicube.Constants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/**
 * Original procedural cues: evolution, the Hunting Cannon's report and burst, Digmon's drills, the Digivice recall,
 * Ikkakumon's voice, blow and footfalls (named by id in voices.json and the model catalog), and Howling Blaster's start,
 * loop and end (named by id in breath_attacks.json).
 */
public final class DCSounds {
    public static final SoundEvent GATHER=register("evolution_gather"),SHED=register("evolution_shed"),RESHAPE=register("evolution_reshape"),
            RECONSTRUCT=register("evolution_reconstruct"),ARRIVAL=register("evolution_arrival"),SHORT=register("evolution_short"),
            SHORT_REVEAL=register("evolution_short_reveal"),RETURN=register("evolution_return"),
            SKY=register("evolution_sky"),GRID=register("evolution_grid"),LANDING=register("evolution_landing"),
            HUNTING_CANNON_FIRE=register("hunting_cannon_fire"),HUNTING_CANNON_IMPACT=register("hunting_cannon_impact"),
            DIGMON_DRILL_SPIN=register("digmon_drill_spin"),GOLD_RUSH_LAUNCH=register("gold_rush_launch"),
            GOLD_RUSH_FLIGHT=register("gold_rush_flight"),GOLD_RUSH_IMPACT=register("gold_rush_impact"),
            BIG_CRACK_GRIND=register("big_crack_grind"),BIG_CRACK_RUPTURE=register("big_crack_rupture"),
            RECALL_CALL=register("digivice_recall_call"),RECALL_WAKE=register("digivice_recall_wake"),
            RECALL_FLIGHT=register("digivice_recall_flight"),RECALL_CATCH=register("digivice_recall_catch"),
            IKKAKUMON_CALL=register("ikkakumon_call"),IKKAKUMON_HURT=register("ikkakumon_hurt"),IKKAKUMON_DEATH=register("ikkakumon_death"),
            IKKAKUMON_BELLOW=register("ikkakumon_bellow"),IKKAKUMON_STEP=register("ikkakumon_step"),GARURUMON_STEP=register("garurumon_step"),
            HOWLING_BLASTER_START=register("howling_blaster_start"),HOWLING_BLASTER_LOOP=register("howling_blaster_loop"),
            HOWLING_BLASTER_END=register("howling_blaster_end");
    private DCSounds() {}
    private static SoundEvent register(String name){var id=Constants.id(name);return Registry.register(BuiltInRegistries.SOUND_EVENT,id,SoundEvent.createVariableRangeEvent(id));}
    public static void init() {}
}
