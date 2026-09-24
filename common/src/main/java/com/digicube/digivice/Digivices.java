package com.digicube.digivice;

import com.digicube.registry.DCItems;
import com.digicube.platform.Services;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

/** Binding, legacy adoption and duplicate rejection run only on the server. */
public final class Digivices {
    private static final String OWNER = "digicube_owner", TOKEN = "digicube_device";
    private Digivices() {}

    public static UUID owner(ItemStack stack) { return uuid(stack, OWNER); }
    public static UUID token(ItemStack stack) { return uuid(stack, TOKEN); }
    private static UUID uuid(ItemStack stack, String key) {
        String value = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr(key, "");
        if (value.isEmpty()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException e) { return null; }
    }
    private static void bind(ItemStack stack, UUID owner, UUID token) {
        stack.setCount(1);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(OWNER, owner.toString()); tag.putString(TOKEN, token.toString());
        });
    }
    public static ItemStack issue(MinecraftServer server, UUID owner) {
        var data = DigiviceSavedData.get(server);
        if (data.device(owner) != null) return ItemStack.EMPTY;
        UUID token = UUID.randomUUID();
        data.put(new DigiviceSavedData.Device(owner, token, Optional.empty()));
        ItemStack stack = new ItemStack(DCItems.DIGIVICE);
        bind(stack, owner, token);
        return stack;
    }
    /** First encountered legacy/unbound item becomes the device; later extras cannot create another. */
    public static boolean adopt(MinecraftServer server, ItemStack stack, UUID player) {
        if (owner(stack) != null) return true;
        var data = DigiviceSavedData.get(server);
        if (data.device(player) != null) return false;
        UUID token = UUID.randomUUID();
        data.put(new DigiviceSavedData.Device(player, token, Optional.empty()));
        bind(stack, player, token);
        return true;
    }
    public static boolean usable(ServerPlayer player, ItemStack stack) {
        if (!stack.is(DCItems.DIGIVICE) || !player.getUUID().equals(owner(stack))) return false;
        var device = DigiviceSavedData.get(player.level().getServer()).device(player.getUUID());
        return device != null && device.token().equals(token(stack)) && device.drop().isEmpty();
    }
    public static boolean hasDevice(ServerPlayer player) {
        return player.getInventory().contains(stack -> usable(player, stack));
    }

    /** Operator /give is a replacement, never an additional device. Validate input before calling. */
    public static void replace(ServerPlayer player, ItemStack requested) {
        var server = player.level().getServer();
        var data = DigiviceSavedData.get(server);
        var previous = data.device(player.getUUID());
        if (previous != null) previous.drop().ifPresent(drop -> removeSignal(server, drop));
        UUID credential = UUID.randomUUID();
        data.put(new DigiviceSavedData.Device(player.getUUID(), credential, Optional.empty()));
        // Revocation also covers old stacks in unopened containers/unloaded entities on their next encounter.
        for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) {
            if (entity instanceof DroppedDigivice drop && player.getUUID().equals(owner(drop.stack()))) drop.discard();
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.is(DCItems.DIGIVICE) && (owner(stack) == null || player.getUUID().equals(owner(stack))))
                player.getInventory().setItem(i, ItemStack.EMPTY);
        }
        var carried = player.containerMenu.getCarried();
        if (carried.is(DCItems.DIGIVICE) && (owner(carried) == null || player.getUUID().equals(owner(carried))))
            player.containerMenu.setCarried(ItemStack.EMPTY);
        ItemStack fresh = requested.copyWithCount(1);
        bind(fresh, player.getUUID(), credential);
        // Preserve every other item. With no free slot, safely put the new device at the recipient's feet.
        if (player.getInventory().getFreeSlot() < 0 || !player.getInventory().add(fresh)) player.drop(fresh, false);
        player.containerMenu.broadcastChanges();
    }

    private static void removeSignal(MinecraftServer server, DigiviceSavedData.Drop drop) {
        var payload = new DigiviceRemovedPayload(drop.entity());
        for (var viewer : server.getPlayerList().getPlayers()) Services.PLATFORM.sendToPlayer(viewer, payload);
    }

    /** Snapshot before vanilla can delete Curse of Vanishing stacks. A cancelled death has no side effect. */
    public static void beforeDeath(ServerPlayer player) {
        if (player.level().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY)) return;
        for (int i=0;i<player.getInventory().getContainerSize();i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (usable(player, stack)) { DigiviceSavedData.get(player.level().getServer()).beforeDeath(player.getUUID(), stack); return; }
        }
        ItemStack cursor = player.containerMenu.getCarried();
        if (usable(player,cursor)) DigiviceSavedData.get(player.level().getServer()).beforeDeath(player.getUUID(),cursor);
    }
    /** Normal deaths leave a protected drop; keepInventory retains the device along with other items. */
    public static void afterDeath(ServerPlayer player) {
        var data = DigiviceSavedData.get(player.level().getServer());
        ItemStack snapshot = data.takeDeath(player.getUUID());
        if (player.level().getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY)) return;
        // Direct die() callers may bypass the normal lethal-damage event, but retained inventory is still recoverable.
        if (snapshot == null) { beforeDeath(player); snapshot = data.takeDeath(player.getUUID()); }
        if (snapshot == null) return;
        var d = data.device(player.getUUID());
        if (d == null || !d.token().equals(token(snapshot)) || d.drop().isPresent()) return;
        for (int i=0;i<player.getInventory().getContainerSize();i++) {
            var stack = player.getInventory().getItem(i);
            if (usable(player,stack)) player.getInventory().setItem(i,ItemStack.EMPTY);
        }
        if (usable(player,player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
        player.drop(snapshot, true);
    }

    /** Includes the menu cursor: foreign devices taken out of containers return to the world, never rebound. */
    public static void reconcile(ServerPlayer player) {
        var seen = new HashSet<UUID>();
        // Visit the actual inventory first. Menu slots may reference exactly these same stack objects.
        var stacks = new java.util.ArrayList<ItemStack>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) stacks.add(player.getInventory().getItem(i));
        stacks.add(player.containerMenu.getCarried());
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ItemStack, Boolean>());
        boolean changed = false;
        for (ItemStack stack : stacks) {
            if (!stack.is(DCItems.DIGIVICE) || !visited.add(stack)) continue;
            if (!adopt(player.level().getServer(), stack, player.getUUID())) { stack.setCount(0); changed = true; continue; }
            UUID owner = owner(stack);
            var d = DigiviceSavedData.get(player.level().getServer()).device(owner);
            if (d == null || !d.token().equals(token(stack)) || d.drop().isPresent() || !seen.add(owner)) {
                stack.setCount(0); changed = true; continue;
            }
            if (!player.getUUID().equals(owner)) {
                ItemStack returned = stack.copyWithCount(1);
                stack.setCount(0);
                player.drop(returned, false);
                changed = true;
            } else if (stack.getCount() != 1) { stack.setCount(1); changed = true; }
        }
        if (changed) player.containerMenu.broadcastChanges();
    }

    /** Fabric's allow-load hook handles Q, death, containers, commands and saved vanilla legacy drops. */
    public static boolean allowLoad(Entity entity, ServerLevel level) {
        if (entity instanceof DroppedDigivice drop) return claimDrop(drop, level);
        if (!(entity instanceof ItemEntity item) || !item.getItem().is(DCItems.DIGIVICE)) return true;
        ItemStack stack = item.getItem().copyWithCount(1);
        if (owner(stack) == null && item.getOwner() instanceof ServerPlayer player
                && !adopt(level.getServer(), stack, player.getUUID())) return false;
        DroppedDigivice drop = new DroppedDigivice(level, stack, item.position(), item.getDeltaMovement());
        level.addFreshEntity(drop);
        return false;
    }
    private static boolean claimDrop(DroppedDigivice drop, ServerLevel level) {
        UUID owner = owner(drop.stack());
        if (owner == null) return true; // pre-binding legacy world item, claimed on first eligible pickup
        var data = DigiviceSavedData.get(level.getServer());
        var d = data.device(owner);
        if (d == null || !d.token().equals(token(drop.stack()))) return false;
        if (d.drop().isPresent() && !d.drop().get().entity().equals(drop.getUUID())) return false;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (d.drop().isEmpty() && player != null && hasDevice(player)) return false;
        remember(drop, level);
        return true;
    }
    public static void remember(DroppedDigivice drop, ServerLevel level) {
        UUID owner = owner(drop.stack());
        if (owner == null) return;
        var data = DigiviceSavedData.get(level.getServer());
        var d = data.device(owner);
        if (d == null || !d.token().equals(token(drop.stack()))) return;
        data.put(new DigiviceSavedData.Device(owner, d.token(), Optional.of(
                new DigiviceSavedData.Drop(drop.getUUID(), level.dimension().identifier(), drop.position(), drop.beaconAt()))));
    }
    public static boolean pickup(DroppedDigivice drop, ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || player.getInventory().getFreeSlot() < 0) return false;
        var server = player.level().getServer();
        var stack = drop.stack();
        if (!adopt(server, stack, player.getUUID()) || !player.getUUID().equals(owner(stack))) return false;
        var data = DigiviceSavedData.get(server);
        var d = data.device(player.getUUID());
        if (d == null || !d.token().equals(token(stack)) || hasDevice(player)
                || d.drop().isPresent() && !d.drop().get().entity().equals(drop.getUUID())) return false;
        ItemStack taken = stack.copyWithCount(1);
        // Rotate the credential on every pickup, invalidating previously copied/stashed stacks.
        UUID next = UUID.randomUUID();
        bind(taken, player.getUUID(), next);
        if (!player.getInventory().add(taken)) return false;
        data.put(new DigiviceSavedData.Device(player.getUUID(), next, Optional.empty()));
        d.drop().ifPresent(address -> removeSignal(server, address));
        player.containerMenu.broadcastChanges();
        return true;
    }
}
