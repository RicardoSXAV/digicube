package com.digicube.fabric.client.render;

import com.digicube.registry.DCParticles;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

/**
 * What rises off a Burned body ({@link DCParticles}, strewn by {@link BurnedVisuals}; its flames are {@link BurningFlames}),
 * in the warm oranges of the flames that lit it.
 * <ul>
 *     <li>ember: a glowing speck that rises off the body, swaying as hot air carries it, twinkling, white-yellow cooling
 *     to orange and deep red, shrinking away. Given a velocity, it is thrown that way first (the burst as a body
 *     catches fire).</li>
 *     <li>smoke: a dark puff of the body's charring that rises slowly, swells, greys and thins out; lit by the world.</li>
 *     <li>steam: a pale puff, quicker and shorter, where water put the fire out.</li>
 * </ul>
 */
public final class BurnParticle extends SingleQuadParticle {
    public enum Kind { EMBER, SMOKE, STEAM }

    /** An ember's white-yellow heat, and the oranges and reds it cools through. */
    private static final int[] HOT = {0xFFF6C8, 0xFFE27A, 0xFFD04A}, WARM = {0xFF9A2A, 0xFF7E22}, COOL = {0xD9481A, 0xA8301A};
    /** The charring smoke's dark warm grey as it leaves, and the grey it thins to. */
    private static final int SMOKE_FROM = 0x3A302B, SMOKE_TO = 0x6E6862, STEAM = 0xEEF0F3;

    private final Kind kind;
    private final SpriteSet sprites;
    private final float size, phase;
    private final int hot, warm, cool;
    private float oQuadSize;

    private BurnParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites, Kind kind) {
        super(level, x, y, z, sprites.first());
        this.kind = kind;
        this.sprites = sprites;
        float roll = random.nextFloat();
        phase = random.nextFloat() * Mth.TWO_PI;
        hot = HOT[random.nextInt(HOT.length)];
        warm = WARM[random.nextInt(WARM.length)];
        cool = COOL[random.nextInt(COOL.length)];
        hasPhysics = false;
        this.xd = xd; this.yd = yd; this.zd = zd;
        switch (kind) {
            case EMBER -> {
                size = .014F + roll * roll * .016F;
                lifetime = 16 + random.nextInt(18);
                friction = .93F; gravity = 0;
                this.yd += .02 + random.nextFloat() * .03;
            }
            case SMOKE -> {
                size = .07F + roll * .04F;
                lifetime = 26 + random.nextInt(16);
                friction = .96F; gravity = 0;
                this.yd += .012 + random.nextFloat() * .01;
                this.roll = this.oRoll = random.nextInt(4) * Mth.HALF_PI;
            }
            default -> {
                size = .08F + roll * .05F;
                lifetime = 14 + random.nextInt(10);
                friction = .94F; gravity = 0;
                this.yd += .03 + random.nextFloat() * .02;
                this.roll = this.oRoll = random.nextInt(4) * Mth.HALF_PI;
            }
        }
        quadSize = oQuadSize = kind == Kind.EMBER ? size : size * .6F;
        setSpriteFromAge(sprites);
        look(0);
    }

    /** Colour, alpha and size for the share {@code f} of its life gone. */
    private void look(float f) {
        switch (kind) {
            case EMBER -> {
                int c = f < .35F ? lerp(hot, warm, f / .35F) : lerp(warm, cool, Math.min(1, (f - .35F) / .5F));
                float twinkle = .82F + .18F * Mth.sin(age * 1.7F + phase);
                setColor((c >> 16 & 255) / 255F * twinkle, (c >> 8 & 255) / 255F * twinkle, (c & 255) / 255F * twinkle);
                setAlpha(f < .8F ? 1 : (1 - f) / .2F);
                quadSize = size * (f < .7F ? 1 : 1 - (f - .7F) / .3F * .7F);
            }
            case SMOKE -> {
                int c = lerp(SMOKE_FROM, SMOKE_TO, f);
                setColor((c >> 16 & 255) / 255F, (c >> 8 & 255) / 255F, (c & 255) / 255F);
                setAlpha(.5F * Mth.clamp(f * 6, 0, 1) * (1 - f));
                quadSize = size * (.6F + .9F * Mth.sqrt(f));
            }
            default -> {
                setColor((STEAM >> 16 & 255) / 255F, (STEAM >> 8 & 255) / 255F, (STEAM & 255) / 255F);
                setAlpha(.6F * Mth.clamp(f * 8, 0, 1) * (1 - f));
                quadSize = size * (.6F + 1.1F * Mth.sqrt(f));
            }
        }
    }

    private static int lerp(int a, int b, float t) {
        t = Mth.clamp(t, 0, 1);
        return Math.round(Mth.lerp(t, a >> 16 & 255, b >> 16 & 255)) << 16 | Math.round(Mth.lerp(t, a >> 8 & 255, b >> 8 & 255)) << 8
                | Math.round(Mth.lerp(t, a & 255, b & 255));
    }

    @Override
    public void tick() {
        oQuadSize = quadSize;
        super.tick();
        if (removed) return;
        switch (kind) {
            // hot air: an ember sways and keeps rising, smoke drifts
            case EMBER -> {
                xd += Mth.cos(age * .35F + phase) * .003;
                zd += Mth.sin(age * .31F + phase) * .003;
                yd += .0015;
            }
            case SMOKE, STEAM -> {
                xd += Mth.cos(age * .12F + phase) * .0015;
                zd += Mth.sin(age * .1F + phase) * .0015;
            }
            default -> { }
        }
        if (kind != Kind.EMBER) setSpriteFromAge(sprites);
        look(Math.min(1, age / (float) lifetime));
    }

    @Override public float getQuadSize(float partial) { return Mth.lerp(partial, oQuadSize, quadSize); }

    @Override
    protected int getLightCoords(float partial) {
        return kind == Kind.EMBER ? LightCoordsUtil.FULL_BRIGHT : super.getLightCoords(partial);
    }

    @Override protected Layer getLayer() { return Layer.TRANSLUCENT; }

    /** Client entry point: the providers for the three kinds. */
    public static void register() {
        provide(DCParticles.BURN_EMBER, Kind.EMBER);
        provide(DCParticles.BURN_SMOKE, Kind.SMOKE);
        provide(DCParticles.BURN_STEAM, Kind.STEAM);
    }

    private static void provide(SimpleParticleType type, Kind kind) {
        ParticleProviderRegistry.getInstance().register(type, sprites -> (options, level, x, y, z, xd, yd, zd, random) ->
                new BurnParticle(level, x, y, z, xd, yd, zd, sprites, kind));
    }
}
