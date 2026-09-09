package com.digicube.dev;

import com.digicube.dev.PlayerLoadout.Enchant;
import com.digicube.dev.PlayerLoadout.Gear;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The loadouts the developer panel offers: what an average vanilla player has on them
 * after 1, 3, 5, 10 and 15 hours of survival in Minecraft 26.2, clutter included. The
 * reasoning and the sources behind each list are in {@code ../design/player-loadouts.md};
 * tweak the lists here, then update the table there.
 *
 * <p>The pacing follows the common 30-day guides at twenty minutes per day: hour one is
 * day three (stone tools, a bed, no armor); hour three is day nine (iron pickaxe, shield,
 * iron helmet and chestplate over copper, a looted bow); hour five is day fifteen (full
 * iron, the first diamonds spent on a pickaxe); hour ten is day thirty (still iron armor
 * with the first enchantments, diamond sword and pickaxe, the Nether just entered); hour
 * fifteen is day forty-five (enchanted diamond chest and legs, potions from the first
 * blaze rods, pearls). Golden apples cost eight gold each, so they stay at one or two.
 * Netherite, maces, spears and elytra are all past fifteen hours for an average player.
 */
public final class PlayerLoadouts {
    public static final List<PlayerLoadout> ALL = List.of(
            new PlayerLoadout("1h", "1 h · stone", 1,
                    "stone sword and tools, no armor",
                    "5 steak, 12 torches, cobble, dirt, logs, bed",
                    Map.of(),
                    null,
                    List.of(item(Items.STONE_SWORD), item(Items.STONE_PICKAXE), item(Items.STONE_AXE), item(Items.STONE_SHOVEL),
                            item(Items.COOKED_BEEF, 5), item(Items.TORCH, 12), item(Items.COBBLESTONE, 40), item(Items.DIRT, 32),
                            item(Items.CRAFTING_TABLE)),
                    List.of(item(Items.BED.red()), item(Items.FURNACE), item(Items.OAK_LOG, 20), item(Items.OAK_PLANKS, 12), item(Items.STICK, 10),
                            item(Items.COAL, 10), item(Items.RAW_IRON, 4), item(Items.RAW_COPPER, 8), item(Items.APPLE, 3),
                            item(Items.WHEAT_SEEDS, 8), item(Items.OAK_SAPLING, 4), item(Items.WOOL.white(), 2), item(Items.ROTTEN_FLESH, 3),
                            item(Items.BONE, 2), item(Items.STRING), item(Items.FEATHER, 2), item(Items.GRAVEL, 6), item(Items.FLINT))),

            new PlayerLoadout("3h", "3 h · iron/copper", 3,
                    "iron sword, iron helm + chest, copper legs + boots, shield, bow",
                    "12 steak, 6 bread, 30 torches, water bucket, ores",
                    Map.of(EquipmentSlot.HEAD, item(Items.IRON_HELMET), EquipmentSlot.CHEST, item(Items.IRON_CHESTPLATE),
                            EquipmentSlot.LEGS, item(Items.COPPER_LEGGINGS), EquipmentSlot.FEET, item(Items.COPPER_BOOTS)),
                    item(Items.SHIELD),
                    List.of(item(Items.IRON_SWORD), item(Items.IRON_PICKAXE), item(Items.STONE_AXE), item(Items.BOW),
                            item(Items.COOKED_BEEF, 12), item(Items.TORCH, 30), item(Items.COBBLESTONE, 64), item(Items.WATER_BUCKET),
                            item(Items.CRAFTING_TABLE)),
                    List.of(item(Items.ARROW, 12), item(Items.BREAD, 6), item(Items.APPLE, 2), item(Items.BED.red()), item(Items.STONE_SHOVEL),
                            item(Items.DIRT, 40), item(Items.OAK_LOG, 24), item(Items.OAK_PLANKS, 20), item(Items.STICK, 12), item(Items.COAL, 20),
                            item(Items.IRON_INGOT, 5), item(Items.RAW_IRON, 6), item(Items.COPPER_INGOT, 10), item(Items.RAW_COPPER, 14),
                            item(Items.WHEAT_SEEDS, 10), item(Items.OAK_SAPLING, 3), item(Items.WOOL.white(), 3), item(Items.ROTTEN_FLESH, 6),
                            item(Items.BONE, 5), item(Items.STRING, 4), item(Items.GUNPOWDER, 2), item(Items.FEATHER, 3), item(Items.LEATHER, 3),
                            item(Items.GRAVEL, 10), item(Items.FLINT, 3), item(Items.REDSTONE, 8), item(Items.OAK_BOAT))),

            new PlayerLoadout("5h", "5 h · iron", 5,
                    "iron sword + axe, full iron armor, shield, bow, diamond pick",
                    "24 steak, 10 bread, 64 torches, compass, bundle",
                    Map.of(EquipmentSlot.HEAD, item(Items.IRON_HELMET), EquipmentSlot.CHEST, item(Items.IRON_CHESTPLATE),
                            EquipmentSlot.LEGS, item(Items.IRON_LEGGINGS), EquipmentSlot.FEET, item(Items.IRON_BOOTS)),
                    item(Items.SHIELD),
                    List.of(item(Items.IRON_SWORD), item(Items.DIAMOND_PICKAXE), item(Items.IRON_AXE), item(Items.BOW),
                            item(Items.COOKED_BEEF, 24), item(Items.TORCH, 64), item(Items.COBBLESTONE, 64), item(Items.WATER_BUCKET),
                            item(Items.IRON_SHOVEL)),
                    List.of(item(Items.ARROW, 24), item(Items.BREAD, 10), item(Items.BAKED_POTATO, 8), item(Items.BED.red()), item(Items.CRAFTING_TABLE),
                            item(Items.IRON_PICKAXE), item(Items.DIRT, 48), item(Items.OAK_LOG, 32), item(Items.OAK_PLANKS, 24), item(Items.STICK, 16),
                            item(Items.COAL, 32), item(Items.IRON_INGOT, 14), item(Items.RAW_IRON, 10), item(Items.COPPER_INGOT, 20),
                            item(Items.GOLD_INGOT, 3), item(Items.DIAMOND, 2), item(Items.REDSTONE, 20), item(Items.LAPIS_LAZULI, 12),
                            item(Items.BUCKET), item(Items.COMPASS), item(Items.BUNDLE), item(Items.WOOL.white(), 4), item(Items.ROTTEN_FLESH, 10),
                            item(Items.BONE, 8), item(Items.STRING, 6), item(Items.GUNPOWDER, 4), item(Items.GRAVEL, 16))),

            new PlayerLoadout("10h", "10 h · iron, dia tools", 10,
                    "Sharp II diamond sword, iron armor Prot I, shield, bow",
                    "48 steak, 1 golden apple, 2 pearls, Nether kit",
                    Map.of(EquipmentSlot.HEAD, item(Items.IRON_HELMET, enchant(Enchantments.PROTECTION, 1)),
                            EquipmentSlot.CHEST, item(Items.IRON_CHESTPLATE, enchant(Enchantments.PROTECTION, 1)),
                            EquipmentSlot.LEGS, item(Items.IRON_LEGGINGS),
                            EquipmentSlot.FEET, item(Items.IRON_BOOTS, enchant(Enchantments.FEATHER_FALLING, 1))),
                    item(Items.SHIELD),
                    List.of(item(Items.DIAMOND_SWORD, enchant(Enchantments.SHARPNESS, 2)),
                            item(Items.DIAMOND_PICKAXE, enchant(Enchantments.EFFICIENCY, 2), enchant(Enchantments.UNBREAKING, 1)),
                            item(Items.IRON_AXE), item(Items.BOW, enchant(Enchantments.POWER, 1)),
                            item(Items.COOKED_BEEF, 48), item(Items.TORCH, 64), item(Items.COBBLESTONE, 64), item(Items.WATER_BUCKET),
                            item(Items.ENDER_PEARL, 2)),
                    List.of(item(Items.ARROW, 32), item(Items.BREAD, 16), item(Items.GOLDEN_APPLE), item(Items.BED.red()), item(Items.CRAFTING_TABLE),
                            item(Items.IRON_PICKAXE), item(Items.IRON_SHOVEL), item(Items.GOLDEN_BOOTS), item(Items.FLINT_AND_STEEL),
                            item(Items.OBSIDIAN, 4), item(Items.DIRT, 32), item(Items.OAK_LOG, 32), item(Items.OAK_PLANKS, 48), item(Items.GLASS, 16),
                            item(Items.COAL, 40), item(Items.IRON_INGOT, 20), item(Items.COPPER_INGOT, 24), item(Items.GOLD_INGOT, 10),
                            item(Items.DIAMOND, 3), item(Items.REDSTONE, 32), item(Items.LAPIS_LAZULI, 30), item(Items.EMERALD, 4),
                            item(Items.ROTTEN_FLESH, 12), item(Items.BOOKSHELF, 3), item(Items.NETHERRACK, 16), item(Items.GOLD_NUGGET, 5),
                            item(Items.OAK_BOAT))),

            new PlayerLoadout("15h", "15 h · ench. diamond", 15,
                    "Sharp III diamond sword, diamond chest + legs Prot, shield",
                    "64 steak, 2 golden apples, 3 potions, 6 pearls",
                    Map.of(EquipmentSlot.HEAD, item(Items.IRON_HELMET, enchant(Enchantments.PROTECTION, 1)),
                            EquipmentSlot.CHEST, item(Items.DIAMOND_CHESTPLATE, enchant(Enchantments.PROTECTION, 2)),
                            EquipmentSlot.LEGS, item(Items.DIAMOND_LEGGINGS, enchant(Enchantments.PROTECTION, 1)),
                            EquipmentSlot.FEET, item(Items.IRON_BOOTS, enchant(Enchantments.FEATHER_FALLING, 2))),
                    item(Items.SHIELD),
                    List.of(item(Items.DIAMOND_SWORD, enchant(Enchantments.SHARPNESS, 3), enchant(Enchantments.UNBREAKING, 2)),
                            item(Items.DIAMOND_PICKAXE, enchant(Enchantments.EFFICIENCY, 3), enchant(Enchantments.UNBREAKING, 2), enchant(Enchantments.FORTUNE, 1)),
                            item(Items.DIAMOND_AXE), item(Items.BOW, enchant(Enchantments.POWER, 2), enchant(Enchantments.UNBREAKING, 1)),
                            item(Items.COOKED_BEEF, 64), item(Items.TORCH, 64), item(Items.COBBLESTONE, 64), item(Items.WATER_BUCKET),
                            item(Items.ENDER_PEARL, 6)),
                    List.of(item(Items.ARROW, 40), item(Items.BREAD, 20), item(Items.GOLDEN_APPLE, 2), potion(Potions.FIRE_RESISTANCE),
                            potion(Potions.FIRE_RESISTANCE), potion(Potions.HEALING), item(Items.BED.red()), item(Items.CRAFTING_TABLE),
                            item(Items.IRON_PICKAXE), item(Items.FLINT_AND_STEEL), item(Items.OBSIDIAN, 6), item(Items.DIRT, 32), item(Items.OAK_LOG, 32),
                            item(Items.OAK_PLANKS, 64), item(Items.GLASS, 24), item(Items.COAL, 64), item(Items.IRON_INGOT, 30),
                            item(Items.COPPER_INGOT, 40), item(Items.GOLD_INGOT, 12), item(Items.DIAMOND, 4), item(Items.REDSTONE, 48),
                            item(Items.LAPIS_LAZULI, 64), item(Items.EMERALD, 8), item(Items.BLAZE_ROD, 4), item(Items.ENDER_EYE, 3),
                            item(Items.NETHERRACK, 24), item(Items.BUNDLE)))
    );

    static {
        if (ALL.stream().map(PlayerLoadout::id).distinct().count() != ALL.size()) throw new IllegalStateException("Duplicate loadout id");
        if (!ALL.equals(ALL.stream().sorted(Comparator.comparingInt(PlayerLoadout::hours)).toList())) {
            throw new IllegalStateException("Loadouts must be listed by hours");
        }
    }

    private PlayerLoadouts() {}

    public static Optional<PlayerLoadout> get(String id) {
        return ALL.stream().filter(loadout -> loadout.id().equals(id)).findFirst();
    }

    private static Gear item(Item item) {
        return item(item, 1);
    }

    private static Gear item(Item item, int count) {
        return new Gear(item, count, Optional.empty(), List.of());
    }

    private static Gear item(Item item, Enchant... enchants) {
        return new Gear(item, 1, Optional.empty(), List.of(enchants));
    }

    private static Gear potion(Holder<Potion> potion) {
        return new Gear(Items.POTION, 1, Optional.of(potion), List.of());
    }

    private static Enchant enchant(ResourceKey<Enchantment> key, int level) {
        return new Enchant(key, level);
    }
}
