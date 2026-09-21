package com.digicube.fabric.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The middle mouse button is the command wheel's: vanilla's pick block gets another default, which is final in vanilla. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Mutable
    @Accessor("defaultKey")
    void digicube$setDefaultKey(InputConstants.Key key);
}
