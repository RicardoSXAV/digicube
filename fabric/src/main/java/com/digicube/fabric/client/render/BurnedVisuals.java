package com.digicube.fabric.client.render;

import com.digicube.Constants;
import com.digicube.entity.CombatMarkState;
import com.digicube.registry.DCParticles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * What the Burn mark looks like (fire a Digimon's attack lit: {@link CombatMarkState#burnRemaining}, read from the synced
 * marks, so every client sees the same thing), in place of vanilla's sheet of fire over the whole body, which hides it:
 * <ul>
 *     <li>the body glows warm: its model is drawn toward a fiery amber that flickers ({@code MixinLivingEntityRenderer}
 *     multiplies the tint by {@link #tint}), easing off as the fire burns down;</li>
 *     <li>it burns in separate tongues of fire standing on its top and round its upper sides, out of its silhouette
 *     ({@link BurningFlames}, drawn with the body), fewer and smaller as the fire burns down;</li>
 *     <li>embers rise off it, and as the fire burns down more of it is smoke ({@link BurnParticle});</li>
 *     <li>catching fire throws a burst of embers off it; when the fire goes out a puff of smoke rises, or steam where
 *     water put it out;</li>
 *     <li>a Burned player sees a low band of flames along the bottom of the view instead of vanilla's wall of fire.</li>
 * </ul>
 * A body that dies Burned burns on in its own flames through its fall (the mark is cleared on death, so the Burn it last
 * had alive is kept while it is still alight). Vanilla's own fire is left on bodies burning from anything else (lava, a
 * fire block), and its damage, sound and putting out by water are untouched.
 */
public final class BurnedVisuals {
    private BurnedVisuals() {}

    /**
     * A Burned body as drawn this frame: how strongly it glows (0 to 1, flicker included), the remaining share of its
     * Burn, and a seed of its own for its flames.
     */
    public record Burning(float heat, float burn, int seed) {}

    /** The body drawn's Burning; absent when it is not Burned. */
    public static final RenderStateDataKey<Burning> BURNING = RenderStateDataKey.create();
    static final Identifier BAND = Constants.id("textures/gui/burn_band.png");
    /** The band's frames (64 x 24 texels each, stacked) and the ticks each shows. */
    static final int BAND_W = 64, BAND_H = 24, BAND_FRAMES = 4, BAND_TICKS = 2;
    /** The glow's colour at full strength, as a tint multiplier (a fiery amber). */
    private static final int GLOW = 0xFFFFA866;
    /** Blocks from the camera within which Burned bodies strew their fire, and past which they strew half of it. */
    private static final double RANGE = 40, NEAR = 20;
    /**
     * Last tick's Burn of each body drawn, by entity id: catching fire and going out are changes in it, and a body that
     * dies alight keeps it.
     */
    private static final Map<Integer, Float> LAST = new HashMap<>();
    private static ClientLevel lastLevel;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(BurnedVisuals::tick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Constants.id("burned_view"), BurnedVisuals::screen);
    }

    /**
     * The remaining share of {@code living}'s Burn, 0 to 1; 0 when it is not Burned. A dying body still alight keeps the
     * Burn it last had alive (the server clears the marks on death).
     */
    public static float burn(LivingEntity living) {
        if (!living.isAlive()) return living.isOnFire() ? LAST.getOrDefault(living.getId(), 0F) : 0;
        return CombatMarkState.burnRemaining(((CombatMarkState) living).digicube$marks2());
    }

    /** The glow for a body Burned by {@code burn}, flickering by {@code time} (ticks): never out while it burns. */
    public static float heat(float burn, float time, int seed) {
        float flicker = .55F * Mth.sin(time * .83F + seed) + .3F * Mth.sin(time * 2.1F + seed * 1.7F) + .15F * Mth.sin(time * 4.3F);
        return Mth.clamp((.6F + .4F * burn) * (.85F + .15F * flicker), 0, 1);
    }

    /** A model tint warmed toward the fire's glow by {@code heat}. */
    public static int tint(int tint, float heat) {
        return ARGB.multiply(tint, ARGB.srgbLerp(Mth.clamp(heat, 0, 1) * .5F, 0xFFFFFFFF, GLOW));
    }

    private static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level != lastLevel) { LAST.clear(); lastLevel = level; }
        if (level == null || minecraft.isPaused()) return;
        var camera = minecraft.gameRenderer.mainCamera().position();
        Map<Integer, Float> seen = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            float burn = burn(living), last = LAST.getOrDefault(entity.getId(), 0F);
            if (burn > 0) seen.put(entity.getId(), burn);
            double distance = entity.distanceToSqr(camera);
            if (distance > RANGE * RANGE) continue;
            // The camera's own body in first person burns out of sight (the band at the bottom of the view shows it).
            if (living == minecraft.getCameraEntity() && minecraft.options.getCameraType().isFirstPerson()) continue;
            RandomSource random = living.getRandom();
            AABB box = living.getBoundingBox();
            Vec3 motion = new Vec3(living.getX() - living.xo, living.getY() - living.yo, living.getZ() - living.zo);
            double size = Math.sqrt(box.getXsize() * box.getYsize() + box.getZsize() * box.getYsize());
            if (burn > 0 && last <= 0) ignite(level, box, motion, size, random);
            else if (burn <= 0 && last > 0 && living.isAlive()) putOut(level, living, box, size, random);
            if (burn <= 0) continue;
            float lod = distance > NEAR * NEAR ? .5F : 1;
            float heat = .45F + .55F * burn;
            // embers rising off it, and more smoke as the fire burns down (its flames are drawn with it: BurningFlames)
            strew(level, DCParticles.BURN_EMBER, heat * lod * (float) (.25 + .18 * size), box, motion, .45, .35, random);
            strew(level, DCParticles.BURN_SMOKE, lod * (1.3F - burn) * (float) (.05 + .04 * size), box, motion.scale(.3), .4, .75, random);
        }
        LAST.clear();
        LAST.putAll(seen);
    }

    /**
     * About {@code rate} particles of {@code type} this tick from points on {@code box}: its top (a {@code top} share of
     * them) or its sides above {@code lowest} of its height, just outside it, so the body never hides them.
     */
    private static void strew(ClientLevel level, SimpleParticleType type, float rate, AABB box, Vec3 motion, double top, double lowest,
                              RandomSource random) {
        for (float n = rate; n > 0; n--) {
            if (n < 1 && random.nextFloat() > n) break;
            Vec3 at = random.nextDouble() < top ? onTop(box, random) : onSide(box, lowest, random);
            level.addParticle(type, at.x, at.y, at.z, motion.x, motion.y * .5, motion.z);
        }
    }

    private static Vec3 onTop(AABB box, RandomSource random) {
        // inset from the edges: a box is wider than most bodies' tops
        return new Vec3(Mth.lerp(.15 + .7 * random.nextDouble(), box.minX, box.maxX), box.maxY + .02,
                Mth.lerp(.15 + .7 * random.nextDouble(), box.minZ, box.maxZ));
    }

    private static Vec3 onSide(AABB box, double lowest, RandomSource random) {
        double u = random.nextDouble(), h = box.minY + box.getYsize() * (lowest + (1 - lowest) * random.nextDouble());
        return switch (random.nextInt(4)) {
            case 0 -> new Vec3(box.minX - .05, h, Mth.lerp(u, box.minZ, box.maxZ));
            case 1 -> new Vec3(box.maxX + .05, h, Mth.lerp(u, box.minZ, box.maxZ));
            case 2 -> new Vec3(Mth.lerp(u, box.minX, box.maxX), h, box.minZ - .05);
            default -> new Vec3(Mth.lerp(u, box.minX, box.maxX), h, box.maxZ + .05);
        };
    }

    /** Catching fire: embers thrown out of the body every way, a little upward. */
    private static void ignite(ClientLevel level, AABB box, Vec3 motion, double size, RandomSource random) {
        Vec3 centre = box.getCenter();
        int embers = 8 + (int) (size * 5);
        for (int i = 0; i < embers; i++) {
            double yaw = random.nextDouble() * Mth.TWO_PI, up = .3 + random.nextDouble() * .7, speed = .06 + random.nextDouble() * .1;
            double r = Math.sqrt(1 - up * up * .5);
            Vec3 at = centre.add(Math.cos(yaw) * box.getXsize() * .55, (random.nextDouble() - .3) * box.getYsize() * .5, Math.sin(yaw) * box.getZsize() * .55);
            level.addParticle(DCParticles.BURN_EMBER, at.x, at.y, at.z, motion.x + Math.cos(yaw) * r * speed, up * speed * .6, motion.z + Math.sin(yaw) * r * speed);
        }
    }

    /** The fire gone out: a puff of smoke off the body and its last embers, or steam where water put it out. */
    private static void putOut(ClientLevel level, LivingEntity living, AABB box, double size, RandomSource random) {
        boolean wet = living.isInWaterOrRain() || living.isInWater();
        for (int i = 0; i < 4 + (int) (size * 3); i++) {
            Vec3 at = random.nextBoolean() ? onTop(box, random) : onSide(box, .35, random);
            level.addParticle(wet ? DCParticles.BURN_STEAM : DCParticles.BURN_SMOKE, at.x, at.y, at.z, 0, .01, 0);
        }
        if (!wet) for (int i = 0; i < 3; i++) {
            Vec3 at = onSide(box, .4, random);
            level.addParticle(DCParticles.BURN_EMBER, at.x, at.y, at.z, 0, 0, 0);
        }
    }

    /**
     * A Burned player's view: a low band of flames rising from the bottom edge, flickering through its frames and
     * drifting along, fainter as the fire burns down.
     */
    private static void screen(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.options.getCameraType().isFirstPerson()) return;
        float burn = burn(minecraft.player);
        if (burn <= 0) return;
        int w = g.guiWidth(), h = g.guiHeight();
        int texel = Math.max(1, Math.round(h / 180F));
        int bandW = BAND_W * texel, bandH = BAND_H * texel;
        int tick = minecraft.player.tickCount;
        int frame = tick / BAND_TICKS % BAND_FRAMES;
        int drift = tick % bandW;
        int alpha = Math.round(205 * (.4F + .6F * burn));
        int colour = ARGB.color(alpha, 255, 255, 255);
        for (int x = -drift; x < w; x += bandW)
            g.blit(RenderPipelines.GUI_TEXTURED, BAND, x, h - bandH, 0, frame * BAND_H, bandW, bandH, BAND_W, BAND_H, BAND_W, BAND_H * BAND_FRAMES, colour);
    }
}
