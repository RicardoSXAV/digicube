package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.NativeGroundModel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * A swimmer's water as the eye sees it and the ear hears it, for a model with {@code swim_wake} in the ground model
 * catalog (Ikkakumon). Every client reads it from the body alone (where it is, how it moves, whether it is in water):
 * <ul>
 * <li>swimming along the surface it throws a bow wave off its chest and leaves a wake, and every paddle splashes at
 * its side, in time with the surface clips;</li>
 * <li>under water bubbles stream back off its flippers, many more on a dash, a dash sets off in a burst, and every
 * stroke swishes;</li>
 * <li>leaving the water (a breach) and falling back in splash, harder the faster it goes;</li>
 * <li>surfacing after a dive it blows a spray from the muzzle, with its breath out if the catalog names a {@code blow};</li>
 * <li>a barrel roll leaves a corkscrew of bubbles.</li>
 * </ul>
 * Client only; nothing here is synced.
 */
public final class SwimWake {
    private static final double RANGE = 64;
    /** Ticks under water before surfacing counts as coming up for air. */
    private static final int HELD_BREATH = 40;
    private record Seen(boolean inWater, boolean eyeInWater, int under, float stroke, float dash, boolean rolling, long blown) {}
    private static final Map<Integer, Seen> seen = new HashMap<>();

    private SwimWake() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SwimWake::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) { seen.clear(); return; }
        long now = minecraft.level.getGameTime();
        Map<Integer, Seen> next = new HashMap<>();
        for (DigimonEntity digimon : minecraft.level.getEntitiesOfClass(DigimonEntity.class, minecraft.player.getBoundingBox().inflate(RANGE))) {
            var definition = NativeGroundModel.definitions().get(digimon.getSpeciesId());
            if (definition == null || definition.swimWake() == null || !digimon.canSwim()) continue;
            var spec = definition.swimWake();
            Seen before = seen.get(digimon.getId());
            boolean inWater = digimon.isInWater(), eye = digimon.isEyeInFluid(FluidTags.WATER);
            float stroke = digimon.getSwimAnimationPhase(1), dash = digimon.getSwimDash(1);
            boolean rolling = digimon.getSwimRoll(1) != 0;
            int under = eye ? (before == null ? 0 : before.under()) + 1 : 0;
            long blown = before == null ? now - 200 : before.blown();
            if (before != null) {
                Vec3 moved = digimon.position().subtract(digimon.xo, digimon.yo, digimon.zo);
                if (!before.inWater() && inWater) enter(minecraft, digimon, moved);
                if (before.inWater() && !inWater && moved.y > .12) leave(minecraft, digimon, moved);
                if (before.eyeInWater() && !eye && inWater && before.under() >= HELD_BREATH && now - blown > 60) { blow(minecraft, digimon, spec); blown = now; }
                if (inWater) swim(minecraft, digimon, spec, moved, before.stroke(), stroke, dash, before.dash() < .5F && dash >= .5F, eye);
                if (rolling && !before.rolling() && inWater) roll(minecraft, digimon);
            }
            next.put(digimon.getId(), new Seen(inWater, eye, under, stroke, dash, rolling, blown));
        }
        seen.clear();
        seen.putAll(next);
    }

    private static Vec3 forward(DigimonEntity digimon) { return Vec3.directionFromRotation(0, digimon.getYRot()); }

    /** The water's surface over the body's feet, or NaN when it is out of the water. */
    private static double surface(DigimonEntity digimon) {
        double h = digimon.getFluidHeight(FluidTags.WATER);
        return h <= 0 ? Double.NaN : digimon.getY() + h;
    }

    private static void swim(Minecraft minecraft, DigimonEntity digimon, NativeGroundModel.SwimWakeSpec spec, Vec3 moved, float strokeBefore,
                             float stroke, float dash, boolean dashStarts, boolean submerged) {
        RandomSource random = digimon.getRandom();
        var level = minecraft.level;
        double pace = moved.horizontalDistance();
        Vec3 ahead = forward(digimon);
        Vec3 across = new Vec3(ahead.z, 0, -ahead.x);
        double half = digimon.getBbWidth() * .5;
        double top = surface(digimon);
        boolean atSurface = !submerged && !Double.isNaN(top) && top < digimon.getY() + digimon.getBbHeight();
        if (atSurface && pace > .08) {
            // The bow wave curls off the chest either side, and the wake spreads behind in a V.
            int bow = Math.min(6, 1 + (int) (pace * 7));
            for (int i = 0; i < bow; i++) {
                double side = random.nextBoolean() ? 1 : -1, spread = .3 + random.nextDouble() * half;
                Vec3 at = digimon.position().add(ahead.scale(half + .3)).add(across.scale(side * spread * .6));
                level.addParticle(ParticleTypes.SPLASH, at.x, top + .05, at.z, across.x * side * .15 + ahead.x * pace * .5, .12 + pace * .25,
                        across.z * side * .15 + ahead.z * pace * .5);
            }
            int wake = Math.min(5, (int) (pace * 6));
            for (int i = 0; i < wake; i++) {
                double side = random.nextBoolean() ? 1 : -1, back = half + random.nextDouble() * 1.6;
                Vec3 at = digimon.position().subtract(ahead.scale(back)).add(across.scale(side * (.3 + back * .45)));
                level.addParticle(random.nextInt(3) == 0 ? ParticleTypes.BUBBLE_POP : ParticleTypes.FISHING, at.x, top + .02, at.z, 0, 0, 0);
            }
        }
        var trail = digimon.serpentTrail();
        if (trail != null && trail.head() != null && pace > .06) along(minecraft, digimon, trail, top, atSurface, pace, dash);
        if (!atSurface && pace > .05) {
            // Under water the stroke sheds bubbles off the flippers, a stream of them on a dash.
            int count = (int) (pace * 4) + (int) (dash * 5);
            Vec3 tail = digimon.position().add(0, digimon.getBbHeight() * .35, 0).subtract(ahead.scale(half + .4));
            for (int i = 0; i < count; i++)
                level.addParticle(ParticleTypes.BUBBLE, tail.x + (random.nextDouble() - .5) * half, tail.y + (random.nextDouble() - .5) * .8,
                        tail.z + (random.nextDouble() - .5) * half, -ahead.x * .2, .02, -ahead.z * .2);
        }
        if (dashStarts) {
            // The torpedo sets off: a rush of water and a burst of bubbles behind.
            Vec3 tail = digimon.position().add(0, digimon.getBbHeight() * .35, 0).subtract(ahead.scale(half));
            for (int i = 0; i < 24; i++)
                level.addParticle(ParticleTypes.BUBBLE, tail.x + (random.nextDouble() - .5) * 1.4, tail.y + (random.nextDouble() - .5) * 1.2,
                        tail.z + (random.nextDouble() - .5) * 1.4, -ahead.x * .45 + (random.nextDouble() - .5) * .2, (random.nextDouble() - .5) * .2,
                        -ahead.z * .45 + (random.nextDouble() - .5) * .2);
            play(minecraft, digimon, SoundEvents.DOLPHIN_SWIM, 1.1F, .62F);
            play(minecraft, digimon, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_INSIDE, .5F, 1.3F);
        }
        if (pace < .05) return;
        if (atSurface) {
            // Along the surface every paddle bites the water at its side: a splash thrown up and forward off the flipper
            // and a slap, left then right, on the surface clip's own beat.
            float beat = (dash >= .5F ? spec.surgePaddle() : spec.paddle()) * .5F;
            if (Math.floor(stroke / beat) > Math.floor(strokeBefore / beat)) {
                double side = Math.floorMod((long) Math.floor(stroke / beat), 2) == 0 ? 1 : -1;
                Vec3 at = digimon.position().add(ahead.scale(half * .7)).add(across.scale(side * half * .9));
                int count = 5 + (int) (dash * 5);
                for (int i = 0; i < count; i++)
                    level.addParticle(ParticleTypes.SPLASH, at.x + (random.nextDouble() - .5) * .5, top + .05, at.z + (random.nextDouble() - .5) * .5,
                            across.x * side * .1 + ahead.x * .1, .15 + random.nextDouble() * (.15 + .1 * dash), across.z * side * .1 + ahead.z * .1);
                level.addParticle(ParticleTypes.BUBBLE_POP, at.x, top + .02, at.z, 0, .02, 0);
                play(minecraft, digimon, SoundEvents.BOAT_PADDLE_WATER, .4F + .25F * dash, .75F + random.nextFloat() * .1F);
            }
        } else if (Math.floor(stroke / spec.stroke()) > Math.floor(strokeBefore / spec.stroke()))
            // Under water a swish on each stroke (the swim clip's cycle).
            play(minecraft, digimon, SoundEvents.PLAYER_SWIM, .3F, .7F + random.nextFloat() * .1F);
    }

    /** Blocks behind the head the points of a serpent's body are shed from. */
    private static final double[] ALONG = {1.6, 3.2, 4.8, 6.4, 8};

    /**
     * A serpent's body along its trail: at the surface its back breaks the water in a wake off each stretch of it, spray
     * thrown aside on a dash; under water bubbles stream off its length.
     */
    private static void along(Minecraft minecraft, DigimonEntity digimon, com.digicube.entity.SerpentTrail trail, double top, boolean atSurface,
                              double pace, float dash) {
        RandomSource random = digimon.getRandom();
        double[] at = new double[3 * ALONG.length], tangent = new double[3 * ALONG.length];
        trail.sample(ALONG, at, tangent);
        var serpent = digimon.serpent();
        double body = serpent == null ? digimon.getBbHeight() * .5 : serpent.swimHeight();
        for (int i = 0; i < ALONG.length; i++) {
            if (random.nextFloat() > .35F + pace) continue;
            double x = at[3 * i], y = at[3 * i + 1], z = at[3 * i + 2], across = (random.nextDouble() - .5) * .9;
            double ax = tangent[3 * i + 2] * across, az = -tangent[3 * i] * across;
            if (atSurface) {
                minecraft.level.addParticle(random.nextInt(3) == 0 ? ParticleTypes.BUBBLE_POP : ParticleTypes.FISHING, x + ax, top + .02, z + az, 0, 0, 0);
                if (dash > .3F && random.nextInt(3) == 0) minecraft.level.addParticle(ParticleTypes.SPLASH, x + ax, top + .05, z + az,
                        ax * .2, .1 + random.nextDouble() * .15 * dash, az * .2);
            } else minecraft.level.addParticle(ParticleTypes.BUBBLE, x + ax, y + body + (random.nextDouble() - .5) * .5, z + az, 0, .03, 0);
        }
    }

    /** Falling back into the water: a crown of spray and a slap, harder the faster it comes down. */
    private static void enter(Minecraft minecraft, DigimonEntity digimon, Vec3 moved) {
        double fall = Math.max(0, -moved.y), hard = Mth.clamp((fall + moved.horizontalDistance() * .3) / .5, 0, 1.4);
        if (hard < .15) return;
        double top = surface(digimon);
        double y = Double.isNaN(top) ? digimon.getY() : top;
        ring(minecraft, digimon, y, (int) (18 + 30 * hard), .25 + .3 * hard);
        play(minecraft, digimon, hard > .7 ? SoundEvents.PLAYER_SPLASH_HIGH_SPEED : SoundEvents.GENERIC_SPLASH, (float) (.6 + .5 * hard), .72F);
    }

    /** Bursting out of the water. */
    private static void leave(Minecraft minecraft, DigimonEntity digimon, Vec3 moved) {
        ring(minecraft, digimon, digimon.getY() + .1, 26, .35);
        play(minecraft, digimon, SoundEvents.DOLPHIN_JUMP, 1F, .7F);
    }

    private static void ring(Minecraft minecraft, DigimonEntity digimon, double y, int count, double lift) {
        RandomSource random = digimon.getRandom();
        double r = digimon.getBbWidth() * .55;
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = r * (.6 + random.nextDouble() * .6);
            minecraft.level.addParticle(ParticleTypes.SPLASH, digimon.getX() + Math.cos(a) * d, y, digimon.getZ() + Math.sin(a) * d,
                    Math.cos(a) * .18, lift * (.6 + random.nextDouble() * .8), Math.sin(a) * .18);
            if (i % 3 == 0) minecraft.level.addParticle(ParticleTypes.BUBBLE_POP, digimon.getX() + Math.cos(a) * d * .7, y,
                    digimon.getZ() + Math.sin(a) * d * .7, 0, .05, 0);
        }
    }

    /** Up for air after a dive: the breath goes out in a blow and a spray off the muzzle. */
    private static void blow(Minecraft minecraft, DigimonEntity digimon, NativeGroundModel.SwimWakeSpec spec) {
        RandomSource random = digimon.getRandom();
        Vec3 muzzle = digimon.position().add(forward(digimon).scale(digimon.getBbWidth() * .6)).add(0, digimon.getEyeHeight(), 0);
        for (int i = 0; i < 16; i++)
            minecraft.level.addParticle(i % 2 == 0 ? ParticleTypes.SPLASH : ParticleTypes.CLOUD, muzzle.x + (random.nextDouble() - .5) * .4, muzzle.y,
                    muzzle.z + (random.nextDouble() - .5) * .4, (random.nextDouble() - .5) * .1, .25 + random.nextDouble() * .2, (random.nextDouble() - .5) * .1);
        if (spec.blow() != null) play(minecraft, digimon, spec.blow(), 1.2F, 1F);
        play(minecraft, digimon, SoundEvents.GENERIC_SPLASH, .3F, 1.3F);
    }

    /** A barrel roll: a corkscrew of bubbles round the body and a rush of water. */
    private static void roll(Minecraft minecraft, DigimonEntity digimon) {
        RandomSource random = digimon.getRandom();
        Vec3 ahead = forward(digimon);
        Vec3 across = new Vec3(ahead.z, 0, -ahead.x);
        Vec3 centre = digimon.position().add(0, digimon.getBbHeight() * .4, 0);
        double r = digimon.getBbWidth() * .6;
        for (int i = 0; i < 28; i++) {
            double a = i * .45, along = (i / 28.0 - .5) * 2.4;
            Vec3 at = centre.add(ahead.scale(along)).add(across.scale(Math.cos(a) * r)).add(0, Math.sin(a) * r, 0);
            minecraft.level.addParticle(ParticleTypes.BUBBLE, at.x, at.y, at.z, (random.nextDouble() - .5) * .1, .03, (random.nextDouble() - .5) * .1);
        }
        play(minecraft, digimon, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_INSIDE, .7F, 1.1F);
    }

    private static void play(Minecraft minecraft, DigimonEntity digimon, SoundEvent sound, float volume, float pitch) {
        minecraft.level.playLocalSound(digimon.getX(), digimon.getY() + digimon.getBbHeight() * .5, digimon.getZ(), sound, SoundSource.NEUTRAL,
                volume, pitch + digimon.getRandom().nextFloat() * .06F, false);
    }

    /** Degrees the rider's view dips as the mount comes back down into the water, and the field of view's punch. */
    public static float[] splashKick(DigimonEntity mount, float partialTick) {
        float t = mount.ticksSinceSplash() + partialTick;
        if (t < 0 || t >= 8 || !mount.canSwim()) return null;
        float hard = Mth.clamp(mount.splashSpeed() / .45F, 0, 1.3F);
        if (hard < .15F) return null;
        float shape = Mth.sin(Mth.PI * t / 8) * (1 - t / 12);
        return new float[]{2.6F * hard * shape, .05F * hard * shape};
    }
}
