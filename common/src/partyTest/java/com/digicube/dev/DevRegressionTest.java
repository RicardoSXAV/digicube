package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttribute;
import com.digicube.digimon.DigimonBody;
import com.digicube.digimon.DigimonFlight;
import com.digicube.digimon.DigimonLocomotion;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonStage;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Developer panel checks that need no game: tuning validation, the sheet text edit and the player loadout table. */
public final class DevRegressionTest {
    private DevRegressionTest() {}

    public static void main(String[] args) {
        try {
            run();
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static void run() {
        SharedConstants.tryDetectVersion();
        DigimonSpecies plain = species(DigimonLocomotion.DEFAULT, Optional.empty());

        CompoundTag values = SpeciesTuning.values(plain);
        check(values.getDoubleOr(SpeciesTuning.BASE_SPEED, 0) == 0.3F && !values.contains(SpeciesTuning.MOUNT_SPEED),
                "values mirror the sheet and omit the mount speed without a mount");
        CompoundTag edit = new CompoundTag();
        edit.putDouble(SpeciesTuning.BASE_SPEED, 0.42);
        edit.putDouble(SpeciesTuning.SWIM_SPEED, 0.5);
        DigimonSpecies tuned = SpeciesTuning.with(plain, edit);
        check(Math.abs(tuned.baseSpeed() - 0.42F) < 1e-6 && tuned.locomotion().swimSpeed() == 0.5
                && tuned.locomotion().walkSpeed() == DigimonLocomotion.DEFAULT.walkSpeed(), "a tune changes only the keys it names");
        check(tuned.attacks().equals(plain.attacks()) && tuned.evolutions().equals(plain.evolutions()), "a tune keeps the rest of the sheet");
        check(rejected(plain, SpeciesTuning.BASE_SPEED, 0) && rejected(plain, SpeciesTuning.RUN_SPEED, 0.5)
                && rejected(plain, SpeciesTuning.SWIM_SPEED, 2), "the sheet validators reject bad tunes");

        String agumon = """
                {
                  "stage": "child",
                  "base_speed": 0.30,
                  "attacks": ["pepper_breath", "claw"],
                  "evolutions": [
                    {
                      "target": "digicube:greymon",
                      "min_level": 20
                    }
                  ]
                }
                """;
        String written = SpeciesSheetWriter.rewrite(agumon, tuned);
        check(written.contains("\"base_speed\": 0.42,"), "base speed replaced in place");
        check(written.contains("\"attacks\": [\"pepper_breath\", \"claw\"],"), "untouched lines keep their formatting");
        check(written.contains("  ],\n  \"locomotion\": {\n    \"follow_start_distance\": 10.0,")
                && written.contains("\"run_speed\": 1.15,\n    \"swim_speed\": 0.5\n  }\n}\n"), "a missing locomotion block is appended");

        DigimonSpecies rider = species(new DigimonLocomotion(5, 2.5F, 1.2, 1.2, 0),
                Optional.of(new DigimonBody.Mount(new Vec3(0, 2, 0), 0.5F, 1)));
        String garurumon = """
                {
                  "base_speed": 0.42,
                  "locomotion": {
                    "follow_start_distance": 5.0,
                    "walk_speed": 1.2,
                    "run_speed": 1.2
                  },
                  "body": {
                    "mount": {
                      "seat": [0.0, 2.1875, -0.375],
                      "speed": 0.5,
                      "step_height": 1.0
                    }
                  }
                }
                """;
        CompoundTag riderEdit = new CompoundTag();
        riderEdit.putDouble(SpeciesTuning.WALK_SPEED, 1.0);
        riderEdit.putDouble(SpeciesTuning.RUN_SPEED, 1.75);
        riderEdit.putDouble(SpeciesTuning.MOUNT_SPEED, 0.65);
        String riderWritten = SpeciesSheetWriter.rewrite(garurumon, SpeciesTuning.with(rider, riderEdit));
        check(riderWritten.contains("\"walk_speed\": 1.0,\n    \"run_speed\": 1.75\n  },"), "existing locomotion keys replaced without adding swim");
        check(riderWritten.contains("\"speed\": 0.65,") && riderWritten.contains("\"base_speed\": 0.3,"), "mount speed keyed separately from base speed");
        check(!riderWritten.contains("swim_speed"), "no swim key for a species that cannot swim");

        CompoundTag swimEdit = new CompoundTag();
        swimEdit.putDouble(SpeciesTuning.SWIM_SPEED, 0.4);
        String swimWritten = SpeciesSheetWriter.rewrite(garurumon, SpeciesTuning.with(rider, swimEdit));
        check(swimWritten.contains("\"run_speed\": 1.2,\n    \"swim_speed\": 0.4\n  },"), "swim key added inside an existing block");
        DigimonFlight wings = new DigimonFlight(0.5, 600, 600, 100, 0.5, 40, 40, 10, 3, 2, 2.1F, 2.35F);
        DigimonSpecies flyer = species(new DigimonLocomotion(5, 2.5F, 1.2, 1.2, 0, wings),
                Optional.of(new DigimonBody.Mount(new Vec3(0, 2, 0), 0.5F, 1)));
        check(SpeciesTuning.values(flyer).getDoubleOr(SpeciesTuning.FLIGHT_SPEED, 0) == 0.5, "flight speed is tunable when the species flies");
        CompoundTag flightEdit = new CompoundTag();
        flightEdit.putDouble(SpeciesTuning.FLIGHT_SPEED, 0.6);
        DigimonSpecies faster = SpeciesTuning.with(flyer, flightEdit);
        check(faster.locomotion().flight().speed() == 0.6 && faster.locomotion().flight().capacityTicks() == 600
                && faster.body().mount().get().speed() == 0.5F, "a flight tune keeps the other flight and mount numbers");
        String tentomon = """
                {
                  "base_speed": 0.3,
                  "locomotion": {
                    "walk_speed": 1.2,
                    "run_speed": 1.2,
                    "flight": {
                      "speed": 0.5,
                      "capacity_ticks": 600
                    }
                  },
                  "body": {
                    "mount": {
                      "speed": 0.5,
                      "step_height": 1.0
                    }
                  }
                }
                """;
        String flightWritten = SpeciesSheetWriter.rewrite(tentomon, faster);
        check(flightWritten.contains("\"flight\": {\n      \"speed\": 0.6,") && flightWritten.contains("\"mount\": {\n      \"speed\": 0.5,"),
                "flight and mount speeds are edited inside their own blocks");
        check(SpeciesSheetWriter.format(1).equals("1.0") && SpeciesSheetWriter.format(0.30000001).equals("0.3")
                && SpeciesSheetWriter.format(0.4567).equals("0.457"), "numbers written short and with a decimal point");

        loadouts();
        Constants.LOG.info("Developer panel regression checks passed: tuning validation, sheet rewrite and player loadouts.");
    }

    /**
     * The loadout table. It needs the vanilla registries, so this bootstraps them; item
     * components stay unbound offline, so nothing here may build an {@code ItemStack}.
     */
    private static void loadouts() {
        Bootstrap.bootStrap();
        // Items exist only after the bootstrap, so these cannot be static fields.
        Set<Item> swords = Set.of(Items.STONE_SWORD, Items.COPPER_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);
        Set<Item> pickaxes = Set.of(Items.STONE_PICKAXE, Items.COPPER_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
        List<PlayerLoadout> all = PlayerLoadouts.ALL;
        check(all.size() == 5 && all.stream().mapToInt(PlayerLoadout::hours).boxed().toList().equals(List.of(1, 3, 5, 10, 15)),
                "five loadouts, one per play time, in order");
        check(all.stream().map(PlayerLoadout::id).distinct().count() == 5 && PlayerLoadouts.get("10h").isPresent()
                && PlayerLoadouts.get("nope").isEmpty(), "loadouts resolve by id");
        for (PlayerLoadout loadout : all) {
            check(loadout.hotbar().size() == Inventory.SELECTION_SIZE, loadout.id() + " fills the hotbar");
            check(loadout.pack().size() <= PlayerLoadout.PACK_SIZE, loadout.id() + " fits the inventory");
            check(!loadout.gear().isBlank() && !loadout.supplies().isBlank(), loadout.id() + " describes itself");
            check(swords.contains(loadout.hotbar().getFirst().item()), loadout.id() + " keeps the sword in slot 1");
            check(loadout.hotbar().stream().anyMatch(gear -> pickaxes.contains(gear.item())), loadout.id() + " carries a pickaxe");
            check(loadout.pack().stream().filter(gear -> gear.potion().isPresent()).allMatch(gear -> gear.item() == Items.POTION),
                    loadout.id() + " keeps potions in potion items");
        }
        PlayerLoadout first = all.getFirst();
        PlayerLoadout last = all.getLast();
        check(first.armor().isEmpty() && first.offhand() == null, "the first hour has no armor and no shield");
        check(last.armor().size() == 4 && last.offhand() != null && last.offhand().item() == Items.SHIELD
                && last.armor().values().stream().allMatch(piece -> !piece.enchants().isEmpty()),
                "fifteen hours wears a full enchanted set with a shield");
        check(all.stream().skip(1).allMatch(loadout -> loadout.armor().size() == 4 && loadout.offhand() != null),
                "every loadout from three hours on wears full armor and a shield");
        check(rejectedGear(Items.ENDER_PEARL, 65) && rejectedGear(Items.ENDER_PEARL, 0), "over-stacked and empty gear is rejected");
        try {
            new PlayerLoadout.Enchant(Enchantments.SHARPNESS, 0);
            check(false, "a level 0 enchantment is rejected");
        } catch (IllegalArgumentException expected) {
            // The validators are the point.
        }
    }

    private static boolean rejectedGear(Item item, int count) {
        try {
            new PlayerLoadout.Gear(item, count, Optional.empty(), List.of());
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean rejected(DigimonSpecies species, String key, double value) {
        CompoundTag edit = new CompoundTag();
        edit.putDouble(key, value);
        try {
            SpeciesTuning.with(species, edit);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static DigimonSpecies species(DigimonLocomotion locomotion, Optional<DigimonBody.Mount> mount) {
        DigimonBody body = new DigimonBody(1, EntityDimensions.scalable(1, 1).withEyeHeight(0.8F), mount);
        return new DigimonSpecies(Constants.id("testmon"), DigimonStage.byId("child"), DigimonAttribute.byId("vaccine"),
                20, 6, 4, 0.3F, List.of(), List.of(), body, locomotion);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
