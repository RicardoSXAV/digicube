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
                    .clientTrackingRange(10)
                    // Scripted lunges and dodges cover blocks in a few ticks; the vanilla 3-tick cadence
                    // leaves remote clients interpolating stale targets, which reads as stutter under jitter.
                    .updateInterval(1));

    public static final ResourceKey<EntityType<?>> PEPPER_BREATH_KEY = key("pepper_breath");
    /** Agumon's fireball: a one-block ball flying dead straight, so clients get its velocity every other tick. */
    public static final EntityType<PepperBreathEntity> PEPPER_BREATH = register(PEPPER_BREATH_KEY,
            EntityType.Builder.<PepperBreathEntity>of(PepperBreathEntity::new, MobCategory.MISC)
                    .sized(0.9F, 0.9F)
                    .eyeHeight(0.45F)
                    .clientTrackingRange(8)
                    .updateInterval(2));

    /**
     * Attack entities draw to 160 blocks whatever their hitbox: vanilla scales the render
     * distance with hitbox size, which hid a .1-block ink shot or spike wave past 6 blocks.
     */
    public static final double ATTACK_RENDER_DISTANCE_SQR = 160 * 160;

    private DCEntityTypes() {}

    public static final EntityType<com.digicube.digivice.DroppedDigivice> DROPPED_DIGIVICE = register(key("dropped_digivice"),
            EntityType.Builder.<com.digicube.digivice.DroppedDigivice>of(com.digicube.digivice.DroppedDigivice::new, MobCategory.MISC)
                    .sized(.55F, .12F).fireImmune().clientTrackingRange(10).updateInterval(1));

    public static final EntityType<com.digicube.entity.KineticProjectileEntity> KINETIC_PROJECTILE = register(key("kinetic_projectile"),
            EntityType.Builder.<com.digicube.entity.KineticProjectileEntity>of(com.digicube.entity.KineticProjectileEntity::new, MobCategory.MISC)
                    .sized(.1F, .1F).clientTrackingRange(12).updateInterval(1));

    /** A part of a caster's body fired as a homing missile (Digmon's drills in Gold Rush); drawn with the part itself. */
    public static final EntityType<com.digicube.entity.VolleyMissileEntity> VOLLEY_MISSILE = register(key("volley_missile"),
            EntityType.Builder.<com.digicube.entity.VolleyMissileEntity>of(com.digicube.entity.VolleyMissileEntity::new, MobCategory.MISC)
                    .sized(.25F, .25F).clientTrackingRange(10).updateInterval(1));

    /** A returning throw (Mojyamon's bone): flies its synced path, then falls and lies where it lands. */
    public static final EntityType<com.digicube.entity.BoomerangEntity> BOOMERANG = register(key("boomerang"),
            EntityType.Builder.<com.digicube.entity.BoomerangEntity>of(com.digicube.entity.BoomerangEntity::new, MobCategory.MISC)
                    .sized(.6F, .25F).clientTrackingRange(12).updateInterval(1));

    /** A charged throw (Mojyamon's icicle): its size and weight ride along as entity data. */
    public static final EntityType<com.digicube.entity.IcicleEntity> ICICLE = register(key("icicle"),
            EntityType.Builder.<com.digicube.entity.IcicleEntity>of(com.digicube.entity.IcicleEntity::new, MobCategory.MISC)
                    .sized(.25F, .25F).clientTrackingRange(12).updateInterval(1));

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

    public static final EntityType<com.digicube.entity.TectonicWaveEntity> TECTONIC_WAVE = register(key("tectonic_wave"),
            EntityType.Builder.<com.digicube.entity.TectonicWaveEntity>of(com.digicube.entity.TectonicWaveEntity::new, MobCategory.MISC)
                    .sized(.1F,.1F).clientTrackingRange(12).updateInterval(1));

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
