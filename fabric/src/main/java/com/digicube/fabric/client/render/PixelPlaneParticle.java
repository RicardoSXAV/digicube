package com.digicube.fabric.client.render;

import com.digicube.registry.DCParticles;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A flat square of colour that glows and tumbles through the air: the Hunting Cannon's particles
 * ({@link DCParticles}). Shards and sparks are real planes turning about their own axis, drawn from both sides, so
 * they flash as they catch the eye edge-on and flat; a flash faces the camera, as light does.
 * <ul>
 *     <li>shard: thrown out of a burst in every direction (a little upward), slowed by the air, falling, pale yellow
 *     burning down to orange, shrinking away at the end. A few are large.</li>
 *     <li>flash: a white-hot square of the size the server sent (the velocity's x, in blocks) that swells a little
 *     and fades in a quarter of a second.</li>
 *     <li>spark: small, short-lived, keeping the velocity it was given (a bolt's trail) or thrown out if it had none.</li>
 * </ul>
 */
public final class PixelPlaneParticle extends SingleQuadParticle {
    public enum Kind { SHARD, FLASH, SPARK }

    /** The bolt's own yellows, pale to deep: where a shard starts, and where it burns down to. */
    private static final int[] HOT = {0xFFFBE0, 0xFFF886, 0xFFE430}, COOL = {0xFFBE2B, 0xFF9838, 0xD95A1C};

    private final Kind kind;
    private final Quaternionf base;
    private final Vector3f axis;
    private final float size;
    private final int from, to;
    private float angle, oAngle, spin, oQuadSize;

    private PixelPlaneParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
                               TextureAtlasSprite sprite, Kind kind) {
        super(level, x, y, z, sprite);
        this.kind = kind;
        base = new Quaternionf().rotationXYZ(random.nextFloat() * Mth.TWO_PI, random.nextFloat() * Mth.TWO_PI, random.nextFloat() * Mth.TWO_PI);
        axis = new Vector3f(random.nextFloat() - .5F, random.nextFloat() - .5F, random.nextFloat() - .5F);
        if (axis.lengthSquared() < 1.0E-4F) axis.set(0, 1, 0);
        axis.normalize();
        float roll = random.nextFloat();
        switch (kind) {
            case SHARD -> {
                Vector3f out = outward(.35F);
                float speed = .14F + random.nextFloat() * .36F;
                this.xd = out.x * speed; this.yd = out.y * speed; this.zd = out.z * speed;
                friction = .83F; gravity = .6F; hasPhysics = true;
                lifetime = 12 + random.nextInt(14);
                size = .025F + roll * roll * roll * .15F;
                spin = (.2F + random.nextFloat() * .5F) * (random.nextBoolean() ? 1 : -1);
            }
            case FLASH -> {
                this.xd = this.yd = this.zd = 0;
                friction = 1; gravity = 0; hasPhysics = false;
                lifetime = 5;
                size = Mth.clamp((float) xd, .2F, 4F) / 2;
                spin = .12F * (random.nextBoolean() ? 1 : -1);
                this.roll = this.oRoll = random.nextFloat() * Mth.HALF_PI;
            }
            default -> {
                if (xd * xd + yd * yd + zd * zd < 1.0E-6) {
                    Vector3f out = outward(.2F);
                    float speed = .05F + random.nextFloat() * .16F;
                    this.xd = out.x * speed; this.yd = out.y * speed; this.zd = out.z * speed;
                } else { this.xd = xd; this.yd = yd; this.zd = zd; }
                friction = .86F; gravity = .3F; hasPhysics = false;
                lifetime = 6 + random.nextInt(7);
                size = .02F + roll * .03F;
                spin = (.3F + random.nextFloat() * .5F) * (random.nextBoolean() ? 1 : -1);
            }
        }
        from = HOT[random.nextInt(HOT.length)];
        to = COOL[random.nextInt(COOL.length)];
        quadSize = oQuadSize = size;
        tint(0);
    }

    /** A random direction, lifted by {@code up} before it is normalised. */
    private Vector3f outward(float up) {
        Vector3f v;
        do v = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
        while (v.lengthSquared() > 1 || v.lengthSquared() < 1.0E-3F);
        return v.normalize().add(0, up, 0).normalize();
    }

    private void tint(float f) {
        float heat = kind == Kind.FLASH ? f : Mth.clamp((f - .15F) / .75F, 0, 1);
        setColor(Mth.lerp(heat, (from >> 16 & 255) / 255F, (to >> 16 & 255) / 255F),
                Mth.lerp(heat, (from >> 8 & 255) / 255F, (to >> 8 & 255) / 255F),
                Mth.lerp(heat, (from & 255) / 255F, (to & 255) / 255F));
    }

    @Override
    public void tick() {
        super.tick();
        oAngle = angle;
        angle += spin;
        spin *= .96F;
        oRoll = roll;
        if (kind == Kind.FLASH) roll += spin;
        oQuadSize = quadSize;
        float f = Math.min(1, age / (float) lifetime);
        tint(f);
        switch (kind) {
            case FLASH -> { quadSize = size * (.7F + .5F * Mth.sqrt(f)); setAlpha(Mth.square(1 - f)); }
            case SHARD -> quadSize = size * (f < .65F ? 1 : 1 - (f - .65F) / .35F);
            default -> quadSize = size * (1 - f * f);
        }
    }

    @Override
    public void extract(QuadParticleRenderState state, Camera camera, float partial) {
        if (kind == Kind.FLASH) { super.extract(state, camera, partial); return; }
        Quaternionf turn = new Quaternionf().rotationAxis(Mth.lerp(partial, oAngle, angle), axis).mul(base);
        extractRotatedQuad(state, camera, turn, partial);
        // Particles are drawn with back faces culled: the plane's other side is its own quad.
        extractRotatedQuad(state, camera, new Quaternionf(turn).rotateY(Mth.PI), partial);
    }

    @Override public float getQuadSize(float partial) { return Mth.lerp(partial, oQuadSize, quadSize); }
    @Override protected int getLightCoords(float partial) { return LightCoordsUtil.FULL_BRIGHT; }
    @Override protected Layer getLayer() { return Layer.TRANSLUCENT; }

    /** Client entry point: the providers for the three kinds. */
    public static void register() {
        provide(DCParticles.CANNON_SHARD, Kind.SHARD);
        provide(DCParticles.CANNON_FLASH, Kind.FLASH);
        provide(DCParticles.CANNON_SPARK, Kind.SPARK);
    }

    private static void provide(SimpleParticleType type, Kind kind) {
        ParticleProviderRegistry.getInstance().register(type, sprites -> (options, level, x, y, z, xd, yd, zd, random) ->
                new PixelPlaneParticle(level, x, y, z, xd, yd, zd, sprites.first(), kind));
    }
}
