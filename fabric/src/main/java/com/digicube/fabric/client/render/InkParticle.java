package com.digicube.fabric.client.render;

import com.digicube.registry.DCParticles;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Squid ink ({@link DCParticles#INK_SPLASH}, {@link DCParticles#INK_DRIP}): flat squares of near-black violet, lit by
 * the world like the body they came off. Once one lands it lies on the ground as a stain that spreads a little and
 * fades.
 * <ul>
 *     <li>splash: a blob thrown out of Deadly Shade's burst on its victim, in every direction and a little upward.</li>
 *     <li>drip: a drop an inked body sheds; it keeps the small velocity it was given and falls.</li>
 * </ul>
 */
public final class InkParticle extends SingleQuadParticle {
    public enum Kind { SPLASH, DRIP }

    /** Ink from its darkest to the sheen a drop catches. */
    private static final int[] INK = {0x120A1B, 0x1C1029, 0x291838, 0x36214A, 0x4A3163};

    private final Quaternionf lying;
    private final float size;
    private float spread, oQuadSize;
    private boolean stain;
    private int stainAt, stainFor;

    private InkParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, TextureAtlasSprite sprite, Kind kind) {
        super(level, x, y, z, sprite);
        float roll = random.nextFloat();
        if (kind == Kind.SPLASH) {
            Vector3f out;
            do out = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
            while (out.lengthSquared() > 1 || out.lengthSquared() < 1.0E-3F);
            out.normalize().add(0, .45F, 0).normalize();
            float speed = .08F + random.nextFloat() * .22F;
            this.xd = out.x * speed + xd; this.yd = out.y * speed + yd; this.zd = out.z * speed + zd;
            friction = .9F; gravity = .75F;
            lifetime = 30 + random.nextInt(20);
            size = .035F + roll * roll * .09F;
        } else {
            this.xd = xd; this.yd = yd; this.zd = zd;
            friction = .96F; gravity = .55F + random.nextFloat() * .2F;
            lifetime = 24 + random.nextInt(16);
            size = .025F + roll * .035F;
        }
        hasPhysics = true;
        quadSize = oQuadSize = size;
        int colour = INK[random.nextInt(roll > .85F ? INK.length : INK.length - 1)];
        setColor((colour >> 16 & 255) / 255F, (colour >> 8 & 255) / 255F, (colour & 255) / 255F);
        setAlpha(.94F);
        lying = new Quaternionf().rotationY(random.nextFloat() * Mth.TWO_PI).rotateX(-Mth.HALF_PI);
    }

    @Override
    public void tick() {
        super.tick();
        oQuadSize = quadSize;
        if (!stain && onGround) {
            // Landed: it stays where it fell, flat on the ground, and spreads into a stain before it fades.
            stain = true;
            xd = yd = zd = 0;
            gravity = 0;
            hasPhysics = false;
            stainAt = age;
            stainFor = 40 + random.nextInt(40);
            lifetime = age + stainFor;
            setPos(x, y + .015, z);
            spread = 1.5F + random.nextFloat() * .9F;
        }
        if (stain) {
            float f = (age - stainAt) / (float) stainFor;
            quadSize = size * Mth.lerp(Math.min(1, f * 6), 1, spread);
            setAlpha(.9F * (f < .6F ? 1 : 1 - (f - .6F) / .4F));
        } else if (age > lifetime - 6) setAlpha(.94F * (lifetime - age) / 6F);
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partial) {
        if (!stain) { super.extract(state, camera, partial); return; }
        extractRotatedQuad(state, camera, lying, partial);
        // Particles are drawn with back faces culled: the stain's other side is its own quad.
        extractRotatedQuad(state, camera, new Quaternionf(lying).rotateY(Mth.PI), partial);
    }

    @Override public float getQuadSize(float partial) { return Mth.lerp(partial, oQuadSize, quadSize); }
    @Override protected Layer getLayer() { return Layer.TRANSLUCENT; }

    /** Client entry point: the providers for both kinds. */
    public static void register() {
        provide(DCParticles.INK_SPLASH, Kind.SPLASH);
        provide(DCParticles.INK_DRIP, Kind.DRIP);
    }

    private static void provide(SimpleParticleType type, Kind kind) {
        ParticleProviderRegistry.getInstance().register(type, sprites -> (options, level, x, y, z, xd, yd, zd, random) ->
                new InkParticle(level, x, y, z, xd, yd, zd, sprites.first(), kind));
    }
}
