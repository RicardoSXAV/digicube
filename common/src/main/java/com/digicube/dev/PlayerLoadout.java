package com.digicube.dev;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an average vanilla survival player carries after a given number of hours: worn
 * armor, the offhand, the hotbar in order and the rest of the inventory. {@link #equip}
 * replaces the player's whole inventory with it so a balance test always starts from the
 * same gear.
 *
 * <p>The table holds items, counts, potions and enchantment keys, never item stacks: in
 * 26.x an item's components are bound to its registry holder only once a server or
 * client has loaded its registries, so a stack built in a static initializer fails with
 * "Components not bound yet". Stacks are built on the server as the loadout is equipped.
 *
 * @param id       short stable id the panel sends, like {@code 5h}
 * @param label    what the dropdown shows
 * @param hours    play time the loadout stands for; the list is sorted by it
 * @param gear     one line describing weapons and armor, for the panel
 * @param supplies one line describing food, blocks and consumables, for the panel
 * @param armor    worn pieces by slot; missing slots stay empty
 * @param offhand  the offhand item or {@code null}
 * @param hotbar   at most {@link Inventory#SELECTION_SIZE} stacks, slot 0 first
 * @param pack     at most {@link #PACK_SIZE} stacks for the main inventory, first slot first
 */
public record PlayerLoadout(String id, String label, int hours, String gear, String supplies,
                            Map<EquipmentSlot, Gear> armor, Gear offhand, List<Gear> hotbar, List<Gear> pack) {
    /** Main-inventory slots after the hotbar. */
    public static final int PACK_SIZE = Inventory.INVENTORY_SIZE - Inventory.SELECTION_SIZE;
    /** The largest vanilla stack; the item's own limit is checked when the stack is built. */
    public static final int MAX_COUNT = 64;
    private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    public record Enchant(ResourceKey<Enchantment> key, int level) {
        public Enchant {
            if (level < 1) throw new IllegalArgumentException("Enchantment level must be at least 1: " + key.identifier());
        }
    }

    /**
     * One stack: an item and count, the potion it holds if it is a potion item, and the
     * enchantments to put on it.
     */
    public record Gear(Item item, int count, Optional<Holder<Potion>> potion, List<Enchant> enchants) {
        public Gear {
            if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("Count out of 1.." + MAX_COUNT + ": " + count + " " + item);
            enchants = List.copyOf(enchants);
        }

        ItemStack build(RegistryAccess registries) {
            ItemStack stack = potion.map(holder -> PotionContents.createItemStack(item, holder)).orElseGet(() -> new ItemStack(item));
            if (count > stack.getMaxStackSize()) throw new IllegalStateException(count + " is over the stack size of " + item);
            stack.setCount(count);
            for (Enchant enchant : enchants) {
                Holder<Enchantment> holder = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchant.key());
                stack.enchant(holder, enchant.level());
            }
            return stack;
        }
    }

    public PlayerLoadout {
        if (id.isBlank()) throw new IllegalArgumentException("A loadout needs an id");
        if (hours < 0) throw new IllegalArgumentException("Hours cannot be negative: " + id);
        for (EquipmentSlot slot : armor.keySet()) {
            if (!ARMOR_SLOTS.contains(slot)) throw new IllegalArgumentException(slot + " is not an armor slot: " + id);
        }
        if (hotbar.size() > Inventory.SELECTION_SIZE) throw new IllegalArgumentException("Hotbar over " + Inventory.SELECTION_SIZE + " stacks: " + id);
        if (pack.size() > PACK_SIZE) throw new IllegalArgumentException("Pack over " + PACK_SIZE + " stacks: " + id);
        armor = Map.copyOf(armor);
        hotbar = List.copyOf(hotbar);
        pack = List.copyOf(pack);
    }

    /** Every stack the loadout carries, worn pieces included. */
    public int stacks() {
        return armor.size() + (offhand == null ? 0 : 1) + hotbar.size() + pack.size();
    }

    /**
     * Replaces everything the player carries and wears with this loadout.
     *
     * @return how many stacks were placed
     */
    public int equip(MinecraftServer server, ServerPlayer player) {
        clear(player);
        RegistryAccess registries = server.registryAccess();
        Inventory inventory = player.getInventory();
        for (Map.Entry<EquipmentSlot, Gear> piece : armor.entrySet()) {
            player.setItemSlot(piece.getKey(), piece.getValue().build(registries));
        }
        if (offhand != null) player.setItemSlot(EquipmentSlot.OFFHAND, offhand.build(registries));
        for (int slot = 0; slot < hotbar.size(); slot++) {
            inventory.setItem(slot, hotbar.get(slot).build(registries));
        }
        for (int slot = 0; slot < pack.size(); slot++) {
            inventory.setItem(Inventory.SELECTION_SIZE + slot, pack.get(slot).build(registries));
        }
        inventory.setSelectedSlot(0);
        player.inventoryMenu.sendAllDataToRemote();
        return stacks();
    }

    /** Empties the inventory, the offhand and every worn armor piece. */
    public static void clear(ServerPlayer player) {
        player.getInventory().clearContent();
        for (EquipmentSlot slot : ARMOR_SLOTS) player.setItemSlot(slot, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        player.inventoryMenu.sendAllDataToRemote();
    }
}
