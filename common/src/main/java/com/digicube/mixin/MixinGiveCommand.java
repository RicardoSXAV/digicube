package com.digicube.mixin;

import com.digicube.digivice.Digivices;
import com.digicube.registry.DCItems;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.GiveCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collection;

/** Vanilla exposes no item-specific give event. Keep its permissions, parsing and selectors. */
@Mixin(GiveCommand.class)
public class MixinGiveCommand {
    @Inject(method = "giveItem", at = @At("HEAD"), cancellable = true)
    private static void digicube$replaceDevice(CommandSourceStack source, ItemInput input,
            Collection<ServerPlayer> targets, int count, CallbackInfoReturnable<Integer> ci) throws CommandSyntaxException {
        if (input.item().value() != DCItems.DIGIVICE) return;
        var requested = input.createItemStack(1); // Invalid components must not revoke an existing device.
        for (var player : targets) Digivices.replace(player, requested);
        if (targets.size() == 1) source.sendSuccess(() -> Component.translatable("commands.give.success.single",
                1, requested.getDisplayName(), targets.iterator().next().getDisplayName()), true);
        else source.sendSuccess(() -> Component.translatable("commands.give.success.multiple",
                1, requested.getDisplayName(), targets.size()), true);
        ci.setReturnValue(targets.size());
    }
}
