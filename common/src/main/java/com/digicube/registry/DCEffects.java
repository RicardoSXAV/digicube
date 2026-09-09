package com.digicube.registry;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Synced, saveable frost statuses on any living target, including non-Digimon mobs. */
public final class DCEffects {
    public static final Holder<MobEffect> ICE_MARK = register("ice_mark", new FrostEffect(false, 0x65CFFF));
    public static final Holder<MobEffect> FROZEN = register("frozen", new FrostEffect(true, 0xB8EEFF)
            .addAttributeModifier(Attributes.MOVEMENT_SPEED, Constants.id("frozen_movement"), -1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
            .addAttributeModifier(Attributes.JUMP_STRENGTH, Constants.id("frozen_jump"), -1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    public static final Holder<MobEffect> FROST_RESISTANCE = register("frost_resistance",
            new MobEffect(MobEffectCategory.NEUTRAL, 0x6685AE) {});

    private DCEffects() {}

    private static Holder<MobEffect> register(String name, MobEffect effect) {
        return Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, Constants.id(name), effect);
    }

    public static void init() {}

    private static final class FrostEffect extends MobEffect {
        private final boolean frozen;

        private FrostEffect(boolean frozen, int color) {
            super(MobEffectCategory.HARMFUL, color, ParticleTypes.SNOWFLAKE);
            this.frozen = frozen;
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int ticks, int amplifier) { return true; }

        @Override
        public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
            if (frozen) {
                entity.setDeltaMovement(0, Math.min(0, entity.getDeltaMovement().y), 0);
                entity.setJumping(false);
                if (entity instanceof Mob mob) mob.getNavigation().stop();
                if (entity instanceof DigimonEntity digimon) digimon.interruptAttack();
            }
            if (entity.tickCount % (frozen ? 2 : 8) == 0) {
                var box = entity.getBoundingBox();
                var center = box.getCenter();
                level.sendParticles(ParticleTypes.SNOWFLAKE, center.x, center.y, center.z,
                        frozen ? 7 : 2, entity.getBbWidth() * .5, entity.getBbHeight() * .45,
                        entity.getBbWidth() * .5, frozen ? .025 : .005);
            }
            return true;
        }
    }
}
