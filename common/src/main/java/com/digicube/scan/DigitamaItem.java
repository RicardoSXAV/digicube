package com.digicube.scan;

import com.digicube.digimon.DigimonFamilies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.digivice.Digivices;
import com.digicube.party.PartyManager;
import com.digicube.party.PartyMember;
import com.digicube.party.PartySavedData;
import com.digicube.party.PartySnapshotPayload;
import com.digicube.platform.Services;
import com.digicube.registry.DCDataComponents;
import com.digicube.registry.DCItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

/**
 * A Digitama carried as an item: CONVERT on the SCAN page puts one in the tamer's inventory ({@link Scan#convert}), its
 * family in {@link DCDataComponents#DIGITAMA}. Using it takes it into the Digivice, which must be with the tamer: the
 * Digitama waits there and hatches ({@link PartyManager#giveDigitama}). Custody moves at once on the server; the egg
 * breaking into data in the hand and the Digivice opening on it are cosmetic ({@link DigitamaUsePayload}).
 */
public final class DigitamaItem extends Item {
    public DigitamaItem(Properties properties) { super(properties); }

    /** A Digitama of {@code family}, named by its first form. */
    public static ItemStack of(Identifier family) {
        ItemStack stack = new ItemStack(DCItems.DIGITAMA);
        stack.set(DCDataComponents.DIGITAMA, family);
        return stack;
    }

    /** The family {@code stack} holds, or null when it is no Digitama or holds no family of the catalog. */
    public static Identifier family(ItemStack stack) {
        Identifier family = stack.is(DCItems.DIGITAMA) ? stack.get(DCDataComponents.DIGITAMA) : null;
        return family != null && DigimonFamilies.all().contains(family) ? family : null;
    }

    @Override public Component getName(ItemStack stack) {
        Identifier family = family(stack);
        if (family == null) return super.getName(stack);
        return Component.translatable("item.digicube.digitama.of", Component.translatable(DigimonSpeciesRegistry.getOrThrow(family).translationKey()));
    }

    /** Deprecated in 26.2 in favour of tooltip components, yet ItemStack still calls it for every tooltip. */
    @SuppressWarnings("deprecation")
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        if (family(stack) == null) {
            tooltip.accept(Component.translatable("item.digicube.digitama.empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.accept(Component.translatable("item.digicube.digitama.use").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("item.digicube.digitama.hatch", Progression.DIGITAMA_HATCH_TICKS / 1200).withStyle(ChatFormatting.GRAY));
    }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer tamer)) return InteractionResult.SUCCESS;
        ItemStack stack = tamer.getItemInHand(hand);
        if (!tamer.isAlive() || tamer.isSpectator() || !stack.is(this) || tamer.getCooldowns().isOnCooldown(stack)) return InteractionResult.FAIL;
        Identifier family = family(stack);
        if (family == null) return fail(tamer, "empty");
        if (!Digivices.hasDevice(tamer)) return fail(tamer, "no_device");
        PartyMember egg = PartyManager.giveDigitama(tamer, family);
        if (egg == null) return fail(tamer, "empty");
        // The cooldown is the item's, so it is set while the hand still holds it.
        tamer.getCooldowns().addCooldown(stack, DigitamaUsePayload.COOLDOWN_TICKS);
        stack.consume(1, tamer);
        tamer.awardStat(Stats.ITEM_USED.get(this));
        // The Digivice opens on the page the new Digitama is on.
        PartySavedData parties = PartySavedData.get(tamer.level().getServer());
        List<PartyMember> owned = parties.roster().owned(tamer.getUUID());
        int at = owned.indexOf(egg);
        if (at >= 0) parties.session(tamer.getUUID()).page = at / PartySnapshotPayload.PAGE_SIZE;
        DigitamaUsePayload packet = new DigitamaUsePayload(egg.id(), family, hand, tamer.getId());
        Services.PLATFORM.sendToPlayer(tamer, packet);
        DigitamaUsePayload watched = packet.withoutEgg();
        for (ServerPlayer viewer : tamer.level().players()) {
            if (viewer != tamer && viewer.distanceToSqr(tamer) <= DigitamaUsePayload.WATCH_RANGE * DigitamaUsePayload.WATCH_RANGE)
                Services.PLATFORM.sendToPlayer(viewer, watched);
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    private static InteractionResult fail(ServerPlayer player, String key) {
        player.sendOverlayMessage(Component.translatable("item.digicube.digitama." + key));
        return InteractionResult.FAIL;
    }
}
