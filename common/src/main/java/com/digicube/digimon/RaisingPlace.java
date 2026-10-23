package com.digicube.digimon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.EnumSet;
import java.util.Locale;

/**
 * Where a partner fought, for CLASSIC's raising ({@link RaisingRecord}): at each wild defeat every place that applies
 * where the partner stands counts once. The ids are written to species sheets and saves, so renaming one is a data
 * migration.
 */
public enum RaisingPlace {
    /** In water, or in an ocean, river or beach biome. */
    WATER,
    /** Where snow can fall: snowy plains and taigas, ice spikes, frozen rivers and oceans, the peaks. */
    COLD,
    /** A hot, dry biome: desert, badlands, savanna, the Nether. */
    HOT,
    /** A forest, jungle or taiga biome. */
    FOREST,
    /** No sky above, below sea level: caves. */
    UNDERGROUND,
    /** Night in the overworld. */
    NIGHT;

    /** The id a species sheet names the place by, {@code "water"}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RaisingPlace byId(String id) {
        for (RaisingPlace place : values()) if (place.id().equals(id)) return place;
        throw new IllegalArgumentException("Unknown raising place: " + id);
    }

    /** The places that apply where {@code entity} stands now. */
    public static EnumSet<RaisingPlace> at(Entity entity) {
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        Holder<Biome> biome = level.getBiome(pos);
        EnumSet<RaisingPlace> places = EnumSet.noneOf(RaisingPlace.class);
        if (entity.isInWater() || biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)
                || biome.is(BiomeTags.IS_RIVER) || biome.is(BiomeTags.IS_BEACH)) places.add(WATER);
        if (biome.value().coldEnoughToSnow(pos, level.getSeaLevel())) places.add(COLD);
        if (!biome.value().hasPrecipitation() && biome.value().getBaseTemperature() > 1.0F) places.add(HOT);
        if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE) || biome.is(BiomeTags.IS_TAIGA)) places.add(FOREST);
        if (!level.canSeeSky(pos) && pos.getY() < level.getSeaLevel()) places.add(UNDERGROUND);
        if (level.dimension() == Level.OVERWORLD && level.isDarkOutside()) places.add(NIGHT);
        return places;
    }
}
