package com.digicube.spawn;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.function.Predicate;

/** Validates the bundled spawn tables, the loader's rejections, the filters, the weighted pick and the settings codec. */
public final class SpawnTableRegressionTest {
    private SpawnTableRegressionTest() {}

    private static final Predicate<Identifier> KNOWN_SPECIES = id -> DigimonSpeciesRegistry.get(id).isPresent();

    /** @param args unused */
    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            DigimonSpeciesBootstrap.registerBuiltIn();
            checkBundledTables();
            checkValidation();
            checkFilters();
            checkWeightedPick();
            checkSettings();
            Constants.LOG.info("Spawn table regression checks passed: bundled tables, validation, filters, weighted pick and settings.");
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static void checkBundledTables() {
        List<SpawnTable> tables = BundledSpawnTableLoader.load(KNOWN_SPECIES);
        check(tables.size() == 1 && tables.getFirst().dimension().equals(Level.OVERWORLD), "one bundled table, for the overworld");
        SpawnTable overworld = tables.getFirst();
        check(overworld.entries().size() == 7, "starter habitats plus the rare stone champion");
        SpawnEntry golemon = overworld.entries().stream().filter(entry -> entry.species().equals(Constants.id("golemon"))).findFirst().orElseThrow();
        check(golemon.weight() == 3 && golemon.minLevel() == 14 && golemon.maxLevel() == 20 && golemon.maxPack() == 1,
                "Golemon is a rare solitary adult in rocky habitats");
        check(overworld.entries().stream().noneMatch(entry -> entry.species().equals(Constants.id("garurumon"))),
                "Garurumon stays out of the wild until it has attacks");
        check(overworld.entries().stream().allMatch(entry -> KNOWN_SPECIES.test(entry.species())), "every entry names a known species");
        for (SpawnEntry entry : overworld.entries()) {
            boolean swimmer = entry.species().equals(Constants.id("gomamon"));
            check((entry.placement() == SpawnPlacement.WATER) == swimmer, "only Gomamon spawns in water");
            check(entry.time() == SpawnTime.ANY, "the starter table has no day or night entries yet");
        }
        SpawnEntry greymon = overworld.entries().stream().filter(entry -> entry.species().equals(Constants.id("greymon"))).findFirst().orElseThrow();
        check(greymon.weight() == 3 && greymon.minLevel() == 14 && greymon.maxLevel() == 20 && greymon.maxPack() == 1,
                "Greymon is rare, strong and alone");
        SpawnEntry koromon = overworld.entries().getFirst();
        check(koromon.species().equals(Constants.id("koromon")) && koromon.weight() == 30 && koromon.minLevel() == 1
                && koromon.maxLevel() == 4 && koromon.minPack() == 1 && koromon.maxPack() == 3 && koromon.biomes().size() == 3,
                "Koromon leads the table as the common early spawn");
        check(koromon.biomes().get(2).isTag() && koromon.biomes().get(2).tag().equals(BiomeTags.IS_FOREST)
                && !koromon.biomes().getFirst().isTag() && koromon.biomes().getFirst().reference().equals("minecraft:plains"),
                "biome ids and #tags both parse");
        SpawnTables.registerBuiltIn();
        check(SpawnTables.size() == 1 && SpawnTables.get(Level.OVERWORLD).isPresent() && SpawnTables.get(Level.NETHER).isEmpty(),
                "the registry serves the overworld only");
    }

    private static void checkValidation() {
        JsonObject table = GsonHelper.parse("""
                {"dimension":"minecraft:overworld","entries":[{"species":"koromon","weight":30,"level":[1,4],"pack":[1,3],
                 "biomes":["minecraft:plains","#minecraft:is_forest"],"placement":"land","time":"any"}]}
                """);
        JsonObject entry = table.getAsJsonArray("entries").get(0).getAsJsonObject();
        SpawnTable parsed = BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES);
        check(parsed.entries().size() == 1 && parsed.entries().getFirst().species().equals(Constants.id("koromon")), "a valid table parses");

        entry.addProperty("species", "digicube:unknownmon");
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "unknown species rejected");
        entry.addProperty("species", "koromon");
        for (int weight : new int[] {0, -1}) {
            entry.addProperty("weight", weight);
            rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "weight " + weight + " rejected");
        }
        entry.remove("weight");
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "missing weight rejected");
        entry.addProperty("weight", 30);
        for (int[] level : new int[][] {{0, 4}, {5, 3}, {1, 51}}) {
            entry.add("level", array(level));
            rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "level range " + level[0] + ".." + level[1] + " rejected");
        }
        entry.add("level", array(new int[] {1}));
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "one-element level range rejected");
        entry.addProperty("level", 5);
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "scalar level rejected");
        entry.add("level", array(new int[] {1, 4}));
        for (int[] pack : new int[][] {{0, 1}, {3, 2}, {1, 9}}) {
            entry.add("pack", array(pack));
            rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "pack range " + pack[0] + ".." + pack[1] + " rejected");
        }
        entry.remove("pack");
        check(BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES).entries().getFirst().maxPack() == 1, "pack defaults to a single Digimon");
        entry.addProperty("placement", "sky");
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "unknown placement rejected");
        entry.remove("placement");
        entry.addProperty("time", "dusk");
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "unknown time rejected");
        entry.remove("time");
        SpawnEntry defaults = BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES).entries().getFirst();
        check(defaults.placement() == SpawnPlacement.LAND && defaults.time() == SpawnTime.ANY, "placement and time default to land and any");
        JsonArray biomes = new JsonArray();
        biomes.add("not a valid id!");
        entry.add("biomes", biomes);
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "malformed biome id rejected");
        biomes.remove(0);
        biomes.add("#Bad Tag");
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "malformed biome tag rejected");
        entry.remove("biomes");
        check(BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES).entries().getFirst().biomes().isEmpty(), "biomes default to any");
        table.addProperty("dimension", "Not A Dimension");
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "malformed dimension rejected");
        table.addProperty("dimension", "minecraft:overworld");
        table.add("entries", new JsonArray());
        rejects(() -> BundledSpawnTableLoader.parse("test", table, KNOWN_SPECIES), "empty table rejected");
    }

    private static void checkFilters() {
        Identifier koromon = Constants.id("koromon");
        Predicate<Identifier> plains = id -> id.equals(Identifier.parse("minecraft:plains"));
        Predicate<TagKey<Biome>> forest = tag -> tag.equals(BiomeTags.IS_FOREST);
        SpawnEntry meadow = new SpawnEntry(koromon, 10, 1, 4, 1, 1,
                List.of(BiomeFilter.parse("minecraft:plains"), BiomeFilter.parse("#minecraft:is_forest")),
                SpawnPlacement.LAND, SpawnTime.ANY);
        check(meadow.matches(SpawnPlacement.LAND, true, false, plains, tag -> false), "a biome id matches");
        check(meadow.matches(SpawnPlacement.LAND, false, true, id -> false, forest), "a biome tag matches");
        check(!meadow.matches(SpawnPlacement.LAND, true, false, id -> false, tag -> false), "another biome does not");
        check(!meadow.matches(SpawnPlacement.WATER, true, false, plains, forest), "placement must agree");
        SpawnEntry anywhere = new SpawnEntry(koromon, 10, 1, 4, 1, 1, List.of(), SpawnPlacement.WATER, SpawnTime.ANY);
        check(anywhere.matches(SpawnPlacement.WATER, false, false, id -> false, tag -> false), "no biome list means every biome");
        SpawnEntry night = new SpawnEntry(koromon, 10, 1, 4, 1, 1, List.of(), SpawnPlacement.LAND, SpawnTime.NIGHT);
        check(night.matches(SpawnPlacement.LAND, false, true, id -> false, tag -> false)
                && !night.matches(SpawnPlacement.LAND, true, false, id -> false, tag -> false)
                && !night.matches(SpawnPlacement.LAND, false, false, id -> false, tag -> false), "night entries wait for the dark, not dusk");
        SpawnEntry day = new SpawnEntry(koromon, 10, 1, 4, 1, 1, List.of(), SpawnPlacement.LAND, SpawnTime.DAY);
        check(day.matches(SpawnPlacement.LAND, true, false, id -> false, tag -> false)
                && !day.matches(SpawnPlacement.LAND, false, true, id -> false, tag -> false), "day entries need daylight");
        SpawnTable table = new SpawnTable(Level.OVERWORLD, List.of(meadow, anywhere, night, day));
        check(table.candidates(SpawnPlacement.LAND, true, false, plains, forest).equals(List.of(meadow, day)), "candidates keep table order");
        check(table.candidates(SpawnPlacement.WATER, false, true, id -> false, tag -> false).equals(List.of(anywhere)), "water at night");
        rejects(() -> new SpawnEntry(koromon, 0, 1, 4, 1, 1, List.of(), SpawnPlacement.LAND, SpawnTime.ANY), "entries validate themselves");
        rejects(() -> new SpawnTable(Level.OVERWORLD, List.of()), "tables validate themselves");
        rejects(() -> BiomeFilter.parse("#"), "an empty tag reference is rejected");
        check(BiomeFilter.parse("plains").reference().equals("minecraft:plains"), "bare biome names are vanilla biomes");
    }

    private static void checkWeightedPick() {
        Identifier koromon = Constants.id("koromon");
        SpawnEntry common = new SpawnEntry(koromon, 30, 1, 4, 1, 1, List.of(), SpawnPlacement.LAND, SpawnTime.ANY);
        SpawnEntry rare = new SpawnEntry(Constants.id("agumon"), 10, 4, 9, 1, 1, List.of(), SpawnPlacement.LAND, SpawnTime.ANY);
        SpawnEntry excluded = new SpawnEntry(Constants.id("greymon"), 1000, 14, 20, 1, 1, List.of(), SpawnPlacement.WATER, SpawnTime.ANY);
        SpawnTable table = new SpawnTable(Level.OVERWORLD, List.of(common, rare, excluded));
        List<SpawnEntry> candidates = table.candidates(SpawnPlacement.LAND, true, false, id -> false, tag -> false);
        RandomSource random = RandomSource.create(42L);
        int picks = 20000;
        int commons = 0;
        for (int index = 0; index < picks; index++) {
            SpawnEntry picked = SpawnTable.pick(random, candidates);
            check(picked == common || picked == rare, "a filtered-out entry is never picked");
            if (picked == common) commons++;
        }
        double fraction = commons / (double) picks;
        check(fraction > 0.72 && fraction < 0.78, "weights 30:10 pick the common entry about three times in four, got " + fraction);
        check(SpawnTable.pick(random, List.of()) == null, "nothing to pick from gives null");
        check(SpawnTable.pick(random, List.of(rare)) == rare, "a single candidate always wins");
        for (int index = 0; index < 200; index++) {
            int level = rare.rollLevel(random);
            int pack = common.rollPack(random);
            check(level >= 4 && level <= 9 && pack == 1, "rolls stay inside the entry's ranges");
        }
    }

    private static void checkSettings() {
        WildSpawnSettings settings = new WildSpawnSettings();
        check(settings.enabled() && settings.intervalTicks() == 400 && settings.maxPerPlayer() == 4 && settings.maxPerLevel() == 24
                && settings.minDistance() == 24 && settings.maxDistance() == 48 && settings.levelBonusPer500Blocks() == 0
                && !settings.debug(), "defaults match the design");
        check(!settings.isDirty(), "a fresh settings object is clean");
        check(settings.cap(1) == 4 && settings.cap(3) == 12 && settings.cap(10) == 24, "the cap grows per player up to the dimension cap");
        WildSpawnSettings fromEmpty = WildSpawnSettings.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        check(fromEmpty.intervalTicks() == 400 && fromEmpty.maxDistance() == 48 && fromEmpty.enabled(), "missing fields fill in the defaults");

        settings.setEnabled(false);
        check(settings.setIntervalTicks(5) == 20 && settings.intervalTicks() == 20, "intervals never drop below one second");
        settings.setCaps(2, -3);
        check(settings.maxPerPlayer() == 2 && settings.maxPerLevel() == 0, "caps clamp at zero");
        settings.setDistance(60, 40);
        check(settings.minDistance() == 60 && settings.maxDistance() == 60, "an inverted ring collapses onto its minimum");
        settings.setDistance(0, 500);
        check(settings.minDistance() == 1 && settings.maxDistance() == WildSpawnSettings.MAX_DISTANCE, "distances stay within 1..128");
        settings.setLevelBonusPer500Blocks(2);
        settings.setDebug(true);
        check(settings.isDirty(), "changes mark the data for saving");

        var encoded = WildSpawnSettings.CODEC.encodeStart(NbtOps.INSTANCE, settings).getOrThrow();
        WildSpawnSettings decoded = WildSpawnSettings.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        check(!decoded.enabled() && decoded.intervalTicks() == 20 && decoded.maxPerPlayer() == 2 && decoded.maxPerLevel() == 0
                && decoded.minDistance() == 1 && decoded.maxDistance() == WildSpawnSettings.MAX_DISTANCE
                && decoded.levelBonusPer500Blocks() == 2 && decoded.debug(), "settings round-trip through the codec");

        SpawnAttempt attempt = new SpawnAttempt(1200, SpawnAttempt.Result.CAP, "24 / 24");
        settings.record(Level.OVERWORLD, attempt);
        check(settings.lastAttempt(Level.OVERWORLD).orElseThrow() == attempt && settings.lastAttempt(Level.NETHER).isEmpty(),
                "the last attempt is remembered per dimension");
        check(!attempt.succeeded() && SpawnAttempt.of(0, SpawnAttempt.Result.SPAWNED).succeeded()
                && attempt.result().translationKey().equals("commands.digicube.wild.result.cap"), "attempt outcomes translate");
    }

    private static JsonArray array(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) array.add(value);
        return array;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException | JsonParseException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
