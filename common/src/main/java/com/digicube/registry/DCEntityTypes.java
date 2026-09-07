package com.digicube.registry;

import com.digicube.Constants;
import com.digicube.entity.DigimonEntity;
import com.digicube.entity.BubbleBlowEntity;
import com.digicube.entity.MegaFlameEntity;
import com.digicube.entity.MarchingFishesEntity;
import com.digicube.entity.PepperBreathEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * Every entity type DigiCube adds.
 *
 * <p>There is deliberately a single {@code digicube:digimon} type rather than one per
 * species: species are content loaded at runtime, while entity types must be
 * registered before the registry freezes. The species rides along as entity data.
 *
 * <p>Attributes are registered separately through the platform helper, because
 * vanilla offers no loader-neutral hook for it; see {@link com.digicube.DigiCube#init()}.
 */
public final class DCEntityTypes {

    public static final ResourceKey<EntityType<?>> DIGIMON_KEY = key("digimon");
    public static final EntityType<DigimonEntity> DIGIMON = register(DIGIMON_KEY,
            EntityType.Builder.of(DigimonEntity::new, MobCategory.CREATURE)
                    // Agumon's model is 28 px tall, rendered at 0.75 scale: ~1.3 blocks.
                    .sized(0.7F, 1.3F)
                    .eyeHeight(1.15F)
                    .clientTrackingRange(10));

    public static final ResourceKey<EntityType<?>> PEPPER_BREATH_KEY = key("pepper_breath");
    /** Agumon's fireball: a one-block ball that bends toward its target, so clients get velocity every other tick. */
    public static final EntityType<PepperBreathEntity> PEPPER_BREATH = register(PEPPER_BREATH_KEY,
            EntityType.Builder.<PepperBreathEntity>of(PepperBreathEntity::new, MobCategory.MISC)
                    .sized(0.9F, 0.9F)
                    .eyeHeight(0.45F)
                    .clientTrackingRange(8)
                    .updateInterval(2));

    private DCEntityTypes() {}

    /** Koromon's small bubble volley; its visual trail follows behind the hitbox. */
    public static final EntityType<BubbleBlowEntity> BUBBLE_BLOW = register(key("bubble_blow"),
            EntityType.Builder.<BubbleBlowEntity>of(BubbleBlowEntity::new, MobCategory.MISC)
                    .sized(0.45F, 0.3F)
                    .eyeHeight(0.15F)
                    .clientTrackingRange(8)
                    .updateInterval(1));

    /** Greymon's broad flame core; trailing sheets are visual follow-through. */
    public static final EntityType<MegaFlameEntity> MEGA_FLAME = register(key("mega_flame"),
            EntityType.Builder.<MegaFlameEntity>of(MegaFlameEntity::new, MobCategory.MISC)
                    .sized(1.2F, 1.2F).eyeHeight(0.6F).clientTrackingRange(10).updateInterval(1));

    /** The water crest's complete volume, including fish, is used for swept collision. */
    public static final EntityType<MarchingFishesEntity> MARCHING_FISHES = register(key("marching_fishes"),
            EntityType.Builder.<MarchingFishesEntity>of(MarchingFishesEntity::new, MobCategory.MISC)
                    .sized(2.2F, 1.25F).eyeHeight(0.625F).clientTrackingRange(10).updateInterval(1));

    /** Forces the static initialiser, which performs the registration. */
    public static void init() {
        Constants.LOG.debug("DigiCube entity types registered.");
    }

    private static ResourceKey<EntityType<?>> key(String path) {
        return ResourceKey.create(Registries.ENTITY_TYPE, Constants.id(path));
    }

    private static <T extends Entity> EntityType<T> register(ResourceKey<EntityType<?>> key, EntityType.Builder<T> builder) {
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
    }
}
