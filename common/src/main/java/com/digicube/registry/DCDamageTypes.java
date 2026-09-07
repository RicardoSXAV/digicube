package com.digicube.registry;

import com.digicube.Constants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

/** Datapack-defined damage, attributed to the partner without displacing its victim. */
public final class DCDamageTypes {
    /** Normal mob damage with the no-knockback tag, supplied by bundled data. */
    public static final ResourceKey<DamageType> PARTNER_ATTACK =
            ResourceKey.create(Registries.DAMAGE_TYPE, Constants.id("partner_attack"));

    private DCDamageTypes() {}

    /** @param attacker responsible partner @return attributed damage without a hurt impulse */
    public static DamageSource partnerAttack(LivingEntity attacker) {
        return new DamageSource(attacker.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(PARTNER_ATTACK), attacker);
    }
}
