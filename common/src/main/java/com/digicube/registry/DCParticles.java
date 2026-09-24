package com.digicube.registry;

import com.digicube.Constants;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * DigiCube's own particles, all flat pixel planes that tumble in the air (drawn by the loader's client,
 * {@code PixelPlaneParticle}): the Hunting Cannon's burst shards, the flash at the heart of a burst or at the muzzle,
 * and the sparks a bolt strews behind it.
 */
public final class DCParticles {
    public static final SimpleParticleType CANNON_SHARD = register("cannon_shard"), CANNON_FLASH = register("cannon_flash"),
            CANNON_SPARK = register("cannon_spark");
    private DCParticles() {}
    // The type's constructor is protected: an anonymous subclass is the plain way to make one without a loader helper.
    private static SimpleParticleType register(String name) {
        return Registry.register(BuiltInRegistries.PARTICLE_TYPE, Constants.id(name), new SimpleParticleType(true) {});
    }
    public static void init() {}
}
