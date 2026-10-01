package com.digicube.registry;

import com.digicube.Constants;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * DigiCube's own particles, all flat pixel planes (drawn by the loader's client): the Hunting Cannon's burst shards,
 * the flash at the heart of a burst or at the muzzle, and the sparks a bolt strews behind it ({@code PixelPlaneParticle});
 * ink ({@code InkParticle}): the blobs Deadly Shade throws off its victim, and the drops an inked body sheds, both
 * lying on the ground as stains once they land; and what rises off a Burned body ({@code BurnParticle}): embers, the
 * smoke of its charring and the steam when water puts it out.
 */
public final class DCParticles {
    public static final SimpleParticleType CANNON_SHARD = register("cannon_shard"), CANNON_FLASH = register("cannon_flash"),
            CANNON_SPARK = register("cannon_spark"), INK_SPLASH = register("ink_splash"), INK_DRIP = register("ink_drip"),
            BURN_EMBER = register("burn_ember"), BURN_SMOKE = register("burn_smoke"), BURN_STEAM = register("burn_steam");
    private DCParticles() {}
    // The type's constructor is protected: an anonymous subclass is the plain way to make one without a loader helper.
    private static SimpleParticleType register(String name) {
        return Registry.register(BuiltInRegistries.PARTICLE_TYPE, Constants.id(name), new SimpleParticleType(true) {});
    }
    public static void init() {}
}
