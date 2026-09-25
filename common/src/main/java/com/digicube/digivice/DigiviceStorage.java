package com.digicube.digivice;

import com.digicube.registry.DCItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A device that is neither held nor dropped is kept somewhere in the world: a chest, a barrel, a hopper that carried it
 * on, a minecart, an item frame, a mob's hands, a donkey's pack, a shulker box or bundle inside any of those, or the
 * owner's ender chest. The Recall Chip takes it from there, and the flight starts there.
 */
public final class DigiviceStorage {
    /** Chunks searched around where the device was last seen; the nearest ones are loaded for it if they are not. */
    static final int CHUNK_REACH = 2, LOADED_REACH = 1;
    /** Blocks searched for entities that hold items, around the same place (loaded ones only). */
    static final double ENTITY_REACH = 40;
    /** Container lids the recall opened, closed again once the device is out. */
    private static final List<Closing> CLOSING = new ArrayList<>();
    private DigiviceStorage() {}

    /**
     * Where the device was: {@code position} is null for a place outside the world (the ender chest), and {@code block}
     * is the container block, if any, whose lid opens as the device leaves. {@code take} removes it from there.
     */
    public record Found(ServerLevel level, Vec3 position, BlockPos block, ItemStack stack, Runnable take) {}
    private record Closing(ServerLevel level, BlockPos pos, long at) {}

    public static Found find(ServerPlayer owner, UUID token) {
        Predicate<ItemStack> mine = stack -> stack.is(DCItems.DIGIVICE) && owner.getUUID().equals(Digivices.owner(stack))
                && token.equals(Digivices.token(stack));
        var server = owner.level().getServer();
        var seen = DigiviceSavedData.get(server).seen(owner.getUUID());
        if (seen != null) {
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, seen.dimension()));
            var found = level == null ? null : around(level, seen.position(), mine);
            if (found != null) return found;
        }
        // A box in the owner's own bag, then their ender chest.
        var found = search(slots(owner.getInventory()), owner.level(), owner.position().add(0, .9, 0), null, mine);
        return found != null ? found : search(slots(owner.getEnderChestInventory()), owner.level(), null, null, mine);
    }

    private static Found around(ServerLevel level, Vec3 at, Predicate<ItemStack> mine) {
        int cx = SectionPos.blockToSectionCoord(at.x), cz = SectionPos.blockToSectionCoord(at.z);
        var containers = new ArrayList<BlockEntity>();
        for (int dx = -CHUNK_REACH; dx <= CHUNK_REACH; dx++) for (int dz = -CHUNK_REACH; dz <= CHUNK_REACH; dz++) {
            LevelChunk chunk = Math.max(Math.abs(dx), Math.abs(dz)) <= LOADED_REACH ? level.getChunk(cx + dx, cz + dz)
                    : level.getChunkSource().getChunkNow(cx + dx, cz + dz);
            if (chunk != null) for (var entity : chunk.getBlockEntities().values()) if (entity instanceof Container) containers.add(entity);
        }
        containers.sort(Comparator.comparingDouble(entity -> entity.getBlockPos().distToCenterSqr(at)));
        for (var entity : containers) {
            if (entity.isRemoved()) continue;
            var found = search(slots((Container)entity), level, Vec3.atCenterOf(entity.getBlockPos()), entity.getBlockPos(), mine);
            if (found != null) return found;
        }
        var box = new AABB(at, at).inflate(ENTITY_REACH, 24, ENTITY_REACH);
        for (var entity : level.getEntities((Entity)null, box, entity -> entity.isAlive() && !(entity instanceof Player))) {
            var found = search(slots(entity), level, entity.position().add(0, entity.getBbHeight() * .5, 0), null, mine);
            if (found != null) return found;
        }
        return null;
    }

    private static List<SlotAccess> slots(Container container) {
        var slots = new ArrayList<SlotAccess>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            int at = i;
            slots.add(SlotAccess.of(() -> container.getItem(at), stack -> { container.setItem(at, stack); container.setChanged(); }));
        }
        return slots;
    }
    /** Everything an entity can keep an item in. */
    private static List<SlotAccess> slots(Entity entity) {
        var slots = new ArrayList<SlotAccess>();
        if (entity instanceof Container container) slots.addAll(slots(container)); // chest and hopper minecarts, chest boats
        if (entity instanceof ItemFrame frame) slots.add(SlotAccess.of(frame::getItem, frame::setItem));
        if (entity instanceof InventoryCarrier carrier) slots.addAll(slots(carrier.getInventory()));
        if (entity instanceof AbstractHorse horse) for (int i = 0; i < horse.getInventorySize(); i++) {
            var slot = horse.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + i);
            if (slot != null) slots.add(slot);
        }
        if (entity instanceof LivingEntity living) for (var slot : EquipmentSlot.values()) slots.add(SlotAccess.forEquipmentSlot(living, slot));
        return slots;
    }

    /** The device in one of these slots, or inside a shulker box or bundle in one of them. */
    private static Found search(List<SlotAccess> slots, ServerLevel level, Vec3 position, BlockPos block, Predicate<ItemStack> mine) {
        for (var slot : slots) {
            var stack = slot.get();
            if (stack.isEmpty()) continue;
            if (mine.test(stack)) return new Found(level, position, block, stack.copyWithCount(1), () -> slot.set(ItemStack.EMPTY));
            var contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) {
                var items = contents.allItemsCopyStream().toList();
                for (int i = 0; i < items.size(); i++) if (mine.test(items.get(i))) {
                    int at = i;
                    return new Found(level, position, block, items.get(i).copyWithCount(1), () -> {
                        var rest = new ArrayList<>(items);
                        rest.set(at, ItemStack.EMPTY);
                        var box = stack.copy();
                        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(rest));
                        slot.set(box);
                    });
                }
            }
            var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
            if (bundle != null) {
                var items = bundle.items();
                for (int i = 0; i < items.size(); i++) {
                    var item = items.get(i).create();
                    if (!mine.test(item)) continue;
                    int at = i;
                    return new Found(level, position, block, item.copyWithCount(1), () -> {
                        var rest = new ArrayList<>(items);
                        rest.remove(at);
                        var box = stack.copy();
                        box.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(rest));
                        slot.set(box);
                    });
                }
            }
        }
        return null;
    }

    /** The container's lid opens as the device rises out of it and closes {@code ticks} later. */
    public static void open(ServerLevel level, BlockPos pos, int ticks) {
        var state = level.getBlockState(pos);
        var entity = level.getBlockEntity(pos);
        SoundEvent sound;
        if (entity instanceof ChestBlockEntity) { level.blockEvent(pos, state.getBlock(), 1, 1); sound = SoundEvents.CHEST_OPEN; }
        else if (entity instanceof ShulkerBoxBlockEntity) { level.blockEvent(pos, state.getBlock(), 1, 1); sound = SoundEvents.SHULKER_BOX_OPEN; }
        else if (state.getBlock() instanceof BarrelBlock && !state.getValue(BarrelBlock.OPEN)) {
            level.setBlock(pos, state.setValue(BarrelBlock.OPEN, true), 3);
            sound = SoundEvents.BARREL_OPEN;
        } else return;
        level.playSound(null, pos, sound, SoundSource.BLOCKS, .5F, level.getRandom().nextFloat() * .1F + .9F);
        CLOSING.add(new Closing(level, pos.immutable(), level.getGameTime() + ticks));
    }
    public static void tick(MinecraftServer server) {
        CLOSING.removeIf(closing -> {
            if (closing.level().getServer() != server) return true;
            if (closing.level().getGameTime() < closing.at()) return false;
            close(closing.level(), closing.pos());
            return true;
        });
    }
    /** Back to however many players have it open. */
    private static void close(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        var state = level.getBlockState(pos);
        var entity = level.getBlockEntity(pos);
        SoundEvent sound;
        if (entity instanceof ChestBlockEntity) {
            int viewers = ChestBlockEntity.getOpenCount(level, pos);
            level.blockEvent(pos, state.getBlock(), 1, viewers);
            if (viewers > 0) return;
            sound = SoundEvents.CHEST_CLOSE;
        } else if (entity instanceof ShulkerBoxBlockEntity) {
            if (viewed(level, entity)) return;
            level.blockEvent(pos, state.getBlock(), 1, 0);
            sound = SoundEvents.SHULKER_BOX_CLOSE;
        } else if (state.getBlock() instanceof BarrelBlock && state.getValue(BarrelBlock.OPEN)) {
            if (viewed(level, entity)) return;
            level.setBlock(pos, state.setValue(BarrelBlock.OPEN, false), 3);
            sound = SoundEvents.BARREL_CLOSE;
        } else return;
        level.playSound(null, pos, sound, SoundSource.BLOCKS, .5F, level.getRandom().nextFloat() * .1F + .9F);
    }
    private static boolean viewed(ServerLevel level, BlockEntity entity) {
        for (var player : level.players())
            for (var slot : player.containerMenu.slots) if (slot.container == entity) return true;
        return false;
    }
}
