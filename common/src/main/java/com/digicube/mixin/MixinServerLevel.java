package com.digicube.mixin;

import com.digicube.entity.DigimonEntity;
import com.digicube.entity.DigimonPart;
import com.digicube.entity.PartedLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player's attack packet names the part it clicked; resolve it the way dragon parts are resolved. */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {
    @Inject(method = "getEntityOrPart", at = @At("RETURN"), cancellable = true)
    private void digicube$resolvePart(int id, CallbackInfoReturnable<Entity> cir) {
        if (cir.getReturnValue() != null || id >= 0) return;
        for (DigimonEntity digimon : ((PartedLevel) this).digicube$parted()) {
            for (DigimonPart part : digimon.parts()) {
                if (part.getId() == id) { cir.setReturnValue(part); return; }
            }
        }
    }
}
