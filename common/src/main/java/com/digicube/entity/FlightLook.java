package com.digicube.entity;

import com.digicube.entity.ai.FlightPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Client, every client: how a flyer carries itself on the wing, read from how it moves (every client sees that, so
 * nothing is synced but the barrel roll). Each share eases toward what the motion asks for, so the pose never snaps:
 * <ul>
 *   <li>{@code cruise}: how far it flies on ahead (0 hovering, 1 at its cruise and on), leaning into its way</li>
 *   <li>{@code dash}: level flight well past cruise (the sprint key's wingbeats, or a dive's speed carried on)</li>
 *   <li>{@code dive}: going down steeply at speed, the wings swept back into an arrow</li>
 *   <li>{@code brake}: a flare, losing speed fast or backing off</li>
 *   <li>{@code bank}: degrees into a turn and a slide (+ the left side down, as the model's roll); {@code pitch}: degrees
 *   the path tips the body (+ nose down)</li>
 *   <li>{@code power}: how hard the wings work (a climb, a takeoff, a boost); the wing clock runs faster with it</li>
 * </ul>
 * An agile flyer also stirs the world under it: the downwash raises the ground's dust (or the water's spray) when it flies low,
 * the air streams past it at speed, a ring bursts round it as it breaks past {@link #BOOM} blocks a tick, its takeoff
 * and its landing throw a ring of dust.
 */
public final class FlightLook {
    private float cruise, dash, dive, brake, bank, pitch, power = .4F, wing;
    private float pCruise, pDash, pDive, pBrake, pBank, pPitch, pPower = .4F, pWing;
    private double lastSpeed;
    private float speed, pSpeed;
    private FlightPhase lastPhase = FlightPhase.GROUNDED;
    private double fallSpeed;
    private boolean boomed;
    /** Client ticks since the speed last broke past BOOM (for the rider's camera), and since the last hard landing. */
    private int boomAge = 1000, landAge = 1000;
    private float landWeight;

    /** Blocks a tick past which the air bursts in a ring round the body. */
    public static final double BOOM = 1.42;

    public float cruise(float p) { return Mth.lerp(p, pCruise, cruise); }
    public float dash(float p) { return Mth.lerp(p, pDash, dash); }
    public float dive(float p) { return Mth.lerp(p, pDive, dive); }
    public float brake(float p) { return Mth.lerp(p, pBrake, brake); }
    public float bank(float p) { return Mth.lerp(p, pBank, bank); }
    public float pitch(float p) { return Mth.lerp(p, pPitch, pitch); }
    public float power(float p) { return Mth.lerp(p, pPower, power); }
    public float wing(float p) { return Mth.lerp(p, pWing, wing); }
    public float speed(float p) { return Mth.lerp(p, pSpeed, speed); }
    public int boomAge() { return boomAge; }
    public int landAge() { return landAge; }
    public float landWeight() { return landWeight; }

    public void tick(DigimonEntity e) {
        pCruise = cruise; pDash = dash; pDive = dive; pBrake = brake; pBank = bank; pPitch = pitch; pPower = power; pWing = wing; pSpeed = speed;
        boomAge++; landAge++;
        var phase = e.getFlightPhase();
        var mount = e.aerialMount();
        double cruiseSpeed = mount != null ? mount.cruiseSpeed() : e.getLocomotion().flight() != null ? e.getLocomotion().flight().speed() : .5;
        Vec3 moved = new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
        double v = moved.length(), h = moved.horizontalDistance();
        double yaw = Math.toRadians(e.getYRot());
        Vec3 ahead = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
        double forward = moved.dot(ahead), aside = moved.dot(left);
        boolean aloft = phase.airborne();
        float wantCruise = 0, wantDash = 0, wantDive = 0, wantBrake = 0, wantBank = 0, wantPitch = 0, wantPower = .45F;
        float rate = 1;
        if (aloft) {
            wantCruise = (float) Mth.clamp(forward / (cruiseSpeed * .75), 0, 1);
            wantDive = (float) (Mth.clamp((-moved.y - .22) / .4, 0, 1) * Mth.clamp((v - cruiseSpeed * .7) / (cruiseSpeed * .6), 0, 1));
            wantDash = (float) (Mth.clamp((h - cruiseSpeed * 1.08) / (cruiseSpeed * .45), 0, 1) * (1 - wantDive));
            double slowing = lastSpeed - v;
            wantBrake = (float) Math.max(Mth.clamp((slowing - .025) / .05, 0, 1) * (1 - wantDive), Mth.clamp(-forward / .08, 0, 1));
            float turn = Mth.wrapDegrees(e.getYRot() - e.yRotO);
            wantBank = (float) Mth.clamp(-turn * (1.6 + 7 * h) + aside * 75, -55, 55);
            double path = Math.toDegrees(Math.atan2(-moved.y, Math.max(1.0E-3, h)));
            // a diving body points down its path; a cruising one tips a little with its climb or its descent
            wantPitch = (float) (v < .05 ? 0 : Mth.clamp(path, -50, 80) * (.25 + .75 * wantDive) * Mth.clamp(v / .25, 0, 1));
            wantPower = (float) Mth.clamp(.35 + Math.max(0, moved.y) * 4 + wantDash * .6 + (phase == FlightPhase.TAKEOFF ? .7 : 0) + wantBrake * .3, 0, 1);
            rate = (float) (1 + .45 * wantPower + .2 * wantDash - .45 * wantDive);
        }
        float ease = aloft ? .2F : .3F;
        cruise = Mth.lerp(ease, cruise, wantCruise);
        dash = Mth.lerp(ease, dash, wantDash);
        dive = Mth.lerp(wantDive > dive ? .16F : .22F, dive, wantDive);
        brake = Mth.lerp(.25F, brake, wantBrake);
        bank = Mth.lerp(.16F, bank, wantBank);
        pitch = Mth.lerp(.18F, pitch, wantPitch);
        power = Mth.lerp(.2F, power, wantPower);
        speed = (float) v;
        wing += aloft || phase == FlightPhase.LANDING ? rate : 0;
        boolean agile = mount != null && mount.agility() != null;
        if (agile) world(e, moved, v, h, cruiseSpeed, phase);
        if (agile && phase == FlightPhase.LANDING && lastPhase != FlightPhase.LANDING) {
            landAge = 0;
            landWeight = (float) Mth.clamp(fallSpeed / .5, .25, 1);
            ring(e, 14 + (int) (landWeight * 18), .18 + .25 * landWeight);
            e.level().playLocalSound(e.getX(), e.getY(), e.getZ(), SoundEvents.RAVAGER_STEP, SoundSource.NEUTRAL, .5F + .5F * landWeight, .75F, false);
        }
        if (agile && phase == FlightPhase.TAKEOFF && lastPhase == FlightPhase.GROUNDED) {
            ring(e, 16, .3);
            e.level().playLocalSound(e.getX(), e.getY(), e.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, .7F, 1.55F, false);
        }
        fallSpeed = Math.max(0, -moved.y) * .5 + fallSpeed * .5;
        lastPhase = phase;
        lastSpeed = v;
    }

    /** What the flight stirs in the world round the body (particles and the burst's sound), on this client. */
    private void world(DigimonEntity e, Vec3 moved, double v, double h, double cruiseSpeed, FlightPhase phase) {
        var level = e.level();
        var random = e.getRandom();
        if (!phase.airborne()) { boomed = false; return; }
        // the air streaming past at speed
        if (v > cruiseSpeed * 1.25) {
            Vec3 back = moved.scale(-.6);
            int n = (int) Mth.clamp((v - cruiseSpeed) * 6, 1, 6);
            for (int i = 0; i < n; i++) {
                double a = random.nextDouble() * Math.PI * 2, r = e.getBbWidth() * (.6 + random.nextDouble() * .8);
                Vec3 side = new Vec3(Math.cos(a) * r, e.getBbHeight() * (.2 + random.nextDouble() * .8), Math.sin(a) * r);
                level.addParticle(ParticleTypes.CLOUD, e.getX() + side.x + moved.x, e.getY() + side.y, e.getZ() + side.z + moved.z, back.x * .3, back.y * .3, back.z * .3);
            }
        }
        // the burst as the body breaks past BOOM
        if (v > BOOM && !boomed) {
            boomed = true;
            boomAge = 0;
            Vec3 dir = moved.normalize(), u = dir.cross(new Vec3(0, 1, 0));
            if (u.lengthSqr() < 1.0E-4) u = new Vec3(1, 0, 0);
            u = u.normalize();
            Vec3 w = dir.cross(u).normalize();
            Vec3 middle = e.position().add(0, e.getBbHeight() * .5, 0);
            for (int i = 0; i < 28; i++) {
                double a = i * Math.PI * 2 / 28;
                Vec3 out = u.scale(Math.cos(a)).add(w.scale(Math.sin(a)));
                level.addParticle(ParticleTypes.CLOUD, middle.x + out.x * 1.2, middle.y + out.y * 1.2, middle.z + out.z * 1.2,
                        out.x * .35 - dir.x * .2, out.y * .35 - dir.y * .2, out.z * .35 - dir.z * .2);
            }
            level.playLocalSound(middle.x, middle.y, middle.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, SoundSource.NEUTRAL, 1.2F, .55F, false);
        } else if (v < BOOM * .85) boomed = false;
        // the downwash: dust (or spray over water) under a body flying low, more the harder its wings work
        if (e.tickCount % 2 != 0) return;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int below = -1;
        for (int d = 0; d <= 5; d++) {
            at.set(e.getX(), e.getY() - d - .01, e.getZ());
            if (!level.getFluidState(at).isEmpty() && level.getFluidState(at).is(FluidTags.WATER) || !level.getBlockState(at).getCollisionShape(level, at).isEmpty()) { below = d; break; }
        }
        if (below < 0) return;
        boolean water = level.getFluidState(at).is(FluidTags.WATER);
        double surface = at.getY() + (water ? level.getFluidState(at).getHeight(level, at) : 1);
        double height = e.getY() - surface;
        if (height > 4.5) return;
        float strength = (float) ((1 - height / 4.5) * (.5 + power));
        int n = (int) (strength * 6) + (h > .5 ? 3 : 0);
        var block = level.getBlockState(at);
        for (int i = 0; i < n; i++) {
            double a = random.nextDouble() * Math.PI * 2, r = .5 + random.nextDouble() * 1.4;
            double x = e.getX() + Math.cos(a) * r, z = e.getZ() + Math.sin(a) * r;
            double out = .08 + strength * .12;
            if (water) level.addParticle(h > .4 ? ParticleTypes.SPLASH : ParticleTypes.BUBBLE_POP, x, surface + .05, z, Math.cos(a) * out - moved.x * .4, .12 + strength * .1, Math.sin(a) * out - moved.z * .4);
            else if (!block.isAir()) level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, block), x, surface + .05, z, Math.cos(a) * out * 2, .05, Math.sin(a) * out * 2);
        }
        if (water && h > .5) level.addParticle(ParticleTypes.CLOUD, e.getX(), surface + .1, e.getZ(), -moved.x * .2, .03, -moved.z * .2);
    }

    /** A ring of dust thrown out from under the body (a takeoff, a landing). */
    private static void ring(DigimonEntity e, int n, double speed) {
        var level = e.level();
        BlockPos under = BlockPos.containing(e.getX(), e.getY() - .2, e.getZ());
        var block = level.getBlockState(under);
        if (block.isAir()) return;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, block), e.getX() + Math.cos(a) * 1.1, e.getY() + .1, e.getZ() + Math.sin(a) * 1.1,
                    Math.cos(a) * speed, .08, Math.sin(a) * speed);
            if (i % 2 == 0) level.addParticle(ParticleTypes.POOF, e.getX() + Math.cos(a) * .9, e.getY() + .15, e.getZ() + Math.sin(a) * .9,
                    Math.cos(a) * speed * .6, .02, Math.sin(a) * speed * .6);
        }
    }
}
