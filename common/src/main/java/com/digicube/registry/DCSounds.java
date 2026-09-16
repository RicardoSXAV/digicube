package com.digicube.registry;

import com.digicube.Constants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/** Original procedural evolution cues; the reproducible score lives in harness v2. */
public final class DCSounds {
    public static final SoundEvent GATHER=register("evolution_gather"),SHED=register("evolution_shed"),RESHAPE=register("evolution_reshape"),
            RECONSTRUCT=register("evolution_reconstruct"),ARRIVAL=register("evolution_arrival"),SHORT=register("evolution_short"),
            SHORT_REVEAL=register("evolution_short_reveal"),RETURN=register("evolution_return"),
            SKY=register("evolution_sky"),GRID=register("evolution_grid"),LANDING=register("evolution_landing");
    private DCSounds() {}
    private static SoundEvent register(String name){var id=Constants.id(name);return Registry.register(BuiltInRegistries.SOUND_EVENT,id,SoundEvent.createVariableRangeEvent(id));}
    public static void init() {}
}
