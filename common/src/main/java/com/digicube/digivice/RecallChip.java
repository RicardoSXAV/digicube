package com.digicube.digivice;

import com.digicube.platform.Services;
import com.digicube.registry.DCItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.Optional;
import java.util.UUID;

/** Atomic custody transfer; the journey is cosmetic, so disconnects cannot strand a device in transit. */
public final class RecallChip extends Item {
    public RecallChip(Properties properties) { super(properties); }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer owner)) return InteractionResult.SUCCESS;
        var chip = owner.getItemInHand(hand);
        if (!owner.isAlive() || owner.isSpectator() || !chip.is(DCItems.RECALL_CHIP)
                || owner.getCooldowns().isOnCooldown(chip)) return InteractionResult.FAIL;
        var server = owner.level().getServer();
        var data = DigiviceSavedData.get(server);
        var device = data.device(owner.getUUID());
        if (device == null) return fail(owner, "no_device");
        if (Digivices.hasDevice(owner)) return fail(owner, "with_you");
        // The recalled device takes the used hand. Keep the rest of a chip stack safely in another slot.
        int remainder = chip.getCount() - (owner.getAbilities().instabuild ? 0 : 1);
        int free = owner.getInventory().getFreeSlot();
        if (remainder > 0 && free < 0) return fail(owner, "space");
        if (device.drop().isEmpty()) return summon(owner, hand, chip, remainder, free);
        var address = device.drop().orElseThrow();
        DroppedDigivice loaded = null;
        for (var dimension : server.getAllLevels()) {
            var entity = dimension.getEntity(address.entity()); // never loads a distant chunk
            if (entity instanceof DroppedDigivice drop && device.token().equals(Digivices.token(drop.stack()))) loaded = drop;
        }
        ItemStack recalled = loaded == null ? data.snapshot(owner.getUUID()) : loaded.stack().copyWithCount(1);
        // Old ledgers have no snapshot until the source chunk is next loaded. Their bound base device is recoverable.
        if (!recalled.is(DCItems.DIGIVICE) || !device.token().equals(Digivices.token(recalled))) recalled = new ItemStack(DCItems.DIGIVICE);
        UUID next = UUID.randomUUID();
        Digivices.bind(recalled, owner.getUUID(), next);
        // All validation precedes mutation. Revocation prevents a far/unloaded source from returning a duplicate.
        data.put(new DigiviceSavedData.Device(owner.getUUID(), next, Optional.empty()));
        data.rememberStack(owner.getUUID(), recalled);
        if (remainder > 0) owner.getInventory().setItem(free, chip.copyWithCount(remainder));
        owner.setItemInHand(hand, recalled);
        var source = loaded == null ? address.position() : loaded.position();
        var packet = new DigiviceRecallPayload(next, hand, owner.getId(), address.entity(), address.dimension(), source, owner.position(),
                address.dimension().equals(owner.level().dimension().identifier()), loaded == null ? -90 : loaded.pitch(1), loaded == null ? 0 : loaded.getYRot(),
                flyRange(owner));
        // The client takes over the existing pose before the entity-removal packet reaches it.
        broadcast(owner, packet);
        if (loaded != null) loaded.discard();
        Digivices.removeSignal(server, address);
        return deliver(owner, chip, recalled, packet);
    }

    /**
     * The device is neither with its owner nor lying in the world. Kept somewhere (a chest, a hopper, an item frame, a
     * box, the ender chest: {@link DigiviceStorage}), it is taken from there and flies out of it, the lid opening as it
     * rises. Nowhere to be found (burnt with a shulker box, cleared by a command, stored far from where it was last
     * seen), it is called back as it was last seen, from nowhere: straight down out of the sky. Either way the old
     * credential is revoked, so a copy left behind is dead when it is found.
     */
    private static InteractionResult summon(ServerPlayer owner, InteractionHand hand, ItemStack chip, int remainder, int free) {
        var server = owner.level().getServer();
        var data = DigiviceSavedData.get(server);
        var found = DigiviceStorage.find(owner, data.device(owner.getUUID()).token());
        ItemStack recalled = found != null ? found.stack() : data.snapshot(owner.getUUID());
        if (!recalled.is(DCItems.DIGIVICE)) recalled = new ItemStack(DCItems.DIGIVICE);
        if (found != null) found.take().run();
        UUID next = Digivices.revoke(server, owner.getUUID());
        Digivices.bind(recalled, owner.getUUID(), next);
        data.rememberStack(owner.getUUID(), recalled);
        if (remainder > 0) owner.getInventory().setItem(free, chip.copyWithCount(remainder));
        owner.setItemInHand(hand, recalled);
        DigiviceRecallPayload packet;
        if (found != null && found.position() != null) {
            packet = new DigiviceRecallPayload(next, hand, owner.getId(), DigiviceRecallPayload.NO_TOKEN, found.level().dimension().identifier(),
                    found.position(), owner.position(), found.level() == owner.level(), -90, 0, flyRange(owner));
            if (found.block() != null)
                DigiviceStorage.open(found.level(), found.block(), (int)Math.ceil(packet.journey().flightAt() * 20) + 12);
        } else {
            packet = new DigiviceRecallPayload(next, hand, owner.getId(), DigiviceRecallPayload.NO_TOKEN, owner.level().dimension().identifier(),
                    owner.position().add(0, 1000, 0), owner.position(), false, -90, 0, flyRange(owner));
        }
        broadcast(owner, packet);
        return deliver(owner, chip, recalled, packet);
    }
    private static InteractionResult deliver(ServerPlayer owner, ItemStack chip, ItemStack recalled, DigiviceRecallPayload packet) {
        owner.getCooldowns().addCooldown(chip, packet.journey().ticks());
        owner.getCooldowns().addCooldown(recalled, packet.journey().ticks());
        owner.containerMenu.broadcastChanges();
        // ItemStack.use applies its after-use components to this explicit replacement. Without it,
        // vanilla's game-mode layer would restore the original chip over the received device.
        return InteractionResult.SUCCESS_SERVER.heldItemTransformedTo(recalled);
    }
    /**
     * The owner gets the full packet; everyone near enough to see them gets it without the credential, to watch the
     * device fly into that player's hand.
     */
    private static void broadcast(ServerPlayer owner, DigiviceRecallPayload packet) {
        Services.PLATFORM.sendToPlayer(owner, packet);
        var watched = packet.withoutToken();
        for (var viewer : owner.level().players()) {
            if (viewer != owner && viewer.distanceTo(owner) <= flyRange(viewer) + 16) Services.PLATFORM.sendToPlayer(viewer, watched);
        }
    }
    /** How far the player sees, in blocks: their view distance within the server's, as the chunk map sends chunks. */
    public static float flyRange(ServerPlayer player) {
        int server = player.level().getServer().getPlayerList().getViewDistance();
        return 16 * Math.clamp(player.requestedViewDistance(), 2, Math.max(2, server));
    }
    private static InteractionResult fail(ServerPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable("item.digicube.recall_chip." + key));
        return InteractionResult.FAIL;
    }
}
