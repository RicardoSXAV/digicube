package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * The loot table a defeated wild Digimon drops from: its species' own ({@code <namespace>:entities/digimon/species/<path>})
 * when the data has one, else its stage's ({@code digicube:entities/digimon/<stage id>}). The stage tables give
 * Digimeat, more for later stages, so a new species needs no table of its own.
 */
public final class DigimonDrops {
    private DigimonDrops() {}

    /** The table every species of {@code stage} shares. */
    public static ResourceKey<LootTable> stageTable(DigimonStage stage) {
        return ResourceKey.create(Registries.LOOT_TABLE, Constants.id("entities/digimon/" + stage.getId()));
    }

    /** The table one species may have of its own, in place of its stage's. */
    public static ResourceKey<LootTable> speciesTable(Identifier species) {
        return ResourceKey.create(Registries.LOOT_TABLE, species.withPrefix("entities/digimon/species/"));
    }

    /** The table a defeated {@code species} drops from, on the data {@code server} has loaded. */
    public static ResourceKey<LootTable> table(MinecraftServer server, DigimonSpecies species) {
        ResourceKey<LootTable> own = speciesTable(species.id());
        boolean present = server.reloadableRegistries().lookup().lookup(Registries.LOOT_TABLE)
                .flatMap(tables -> tables.get(own)).isPresent();
        return present ? own : stageTable(species.stage());
    }
}
