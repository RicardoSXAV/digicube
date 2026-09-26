package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.NativeGroundModel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * A heavy walker's feet on the gait's own clock, for a model with {@code stomps} in the ground model catalog
 * (DarkTyrannomon): where a foot lands in the phase it thuds (a ravager's step, low, with the ground's own step under
 * it), raises a puff of the ground it stands on, and nods its rider's view down a touch, more at a run. A ridden one
 * that breaks into its run roars. Client only; the gait's {@code footfalls} silences vanilla's step per block.
 */
public final class Stomps {
    private static final double RANGE = 48;
    /** Ticks a stomp's nod lasts in the rider's view, and its depth in degrees walking and at a full run. */
    private static final float NOD_TICKS = 5, NOD_WALK = .35F, NOD_RUN = .8F;
    /** A ridden body roars when its run passes this share from below LOW, at most once per ROAR_EVERY ticks. */
    private static final float ROAR_RUN = .75F, ROAR_LOW = .3F;
    private static final int ROAR_EVERY = 240;
    private record Seen(float phase, float run, boolean low, long roared) {}
    private static final Map<Integer, Seen> seen = new HashMap<>();
    /** The last stomp per body: when and how hard (0 to 1), for the rider's view. */
    private record Stomp(long tick, float depth) {}
    private static final Map<Integer, Stomp> last = new HashMap<>();

    private Stomps() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(Stomps::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) { seen.clear(); return; }
        long now = minecraft.level.getGameTime();
        Map<Integer, Seen> next = new HashMap<>();
        for (DigimonEntity digimon : minecraft.level.getEntitiesOfClass(DigimonEntity.class, minecraft.player.getBoundingBox().inflate(RANGE))) {
            var definition = NativeGroundModel.definitions().get(digimon.getSpeciesId());
            var gait = digimon.getLocomotion().groundGait();
            if (definition == null || definition.stomps() == null || gait == null) continue;
            float phase = digimon.getGroundAnimationPhase(1), amount = digimon.getGroundAnimationAmount(1), run = digimon.getGroundRunAmount(1);
            Seen before = seen.get(digimon.getId());
            long roared = before == null ? now - ROAR_EVERY : before.roared();
            boolean low = before == null ? run < ROAR_LOW : before.low() || run < ROAR_LOW;
            if (before != null) {
                if (digimon.onGround() && amount >= .15F && phase != before.phase() && Math.abs(phase - before.phase()) < gait.cycleTicks())
                    stomp(minecraft, digimon, definition.stomps(), gait.cycleTicks(), before.phase(), phase, amount, run, now);
                if (digimon.isVehicle() && low && run >= ROAR_RUN && before.run() < ROAR_RUN && now - roared >= ROAR_EVERY) {
                    minecraft.level.playLocalSound(digimon.getX(), digimon.getEyeY(), digimon.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.NEUTRAL,
                            1.1F, .72F + digimon.getRandom().nextFloat() * .06F, false);
                    roared = now;
                    low = false;
                }
            }
            next.put(digimon.getId(), new Seen(phase, run, low, roared));
        }
        seen.clear();
        seen.putAll(next);
        last.keySet().retainAll(next.keySet());
    }

    private static void stomp(Minecraft minecraft, DigimonEntity digimon, NativeGroundModel.Stomps stomps, float cycle,
                              float from, float to, float amount, float run, long now) {
        float lo = Math.min(from, to), hi = Math.max(from, to);
        for (int i = 0; i < stomps.down().length; i++) {
            float at = stomps.down()[i] * cycle;
            if (Math.floor((hi - at) / cycle) <= Math.floor((lo - at) / cycle)) continue;
            float weight = Mth.clamp(amount, .4F, 1) * (.75F + .25F * run);
            // Where the foot stands, from the body's heading: x across, then forward.
            float[] foot = stomps.feet()[i];
            Vec3 at3 = new Vec3(foot[0], 0, foot[1]).yRot(-digimon.getYRot() * Mth.DEG_TO_RAD).add(digimon.position());
            BlockPos below = BlockPos.containing(at3.x, digimon.getY() - .2, at3.z);
            var ground = minecraft.level.getBlockState(below);
            minecraft.level.playLocalSound(at3.x, digimon.getY(), at3.z, SoundEvents.RAVAGER_STEP, SoundSource.NEUTRAL,
                    .75F * weight, .5F + .08F * run + digimon.getRandom().nextFloat() * .05F, false);
            if (!ground.isAir()) {
                var sound = ground.getSoundType();
                minecraft.level.playLocalSound(at3.x, digimon.getY(), at3.z, sound.getStepSound(), SoundSource.NEUTRAL,
                        sound.getVolume() * .5F * weight, sound.getPitch() * .55F, false);
                var dust = new BlockParticleOption(ParticleTypes.BLOCK, ground);
                int count = 4 + Math.round(6 * run);
                for (int k = 0; k < count; k++) {
                    double a = digimon.getRandom().nextDouble() * Math.PI * 2, r = .3 + digimon.getRandom().nextDouble() * .5;
                    minecraft.level.addParticle(dust, at3.x + Math.cos(a) * r, digimon.getY() + .05, at3.z + Math.sin(a) * r,
                            Math.cos(a) * .12, .08 + .1 * run, Math.sin(a) * .12);
                }
            }
            last.put(digimon.getId(), new Stomp(now, weight * Mth.lerp(run, NOD_WALK, NOD_RUN)));
        }
    }

    /** Degrees the rider's view dips for the mount's last stomp (0 when none is under way). */
    public static float nod(DigimonEntity mount, float partialTick) {
        Stomp stomp = last.get(mount.getId());
        if (stomp == null || Minecraft.getInstance().level == null) return 0;
        float t = Minecraft.getInstance().level.getGameTime() - stomp.tick() + partialTick;
        return t < 0 || t >= NOD_TICKS ? 0 : stomp.depth() * Mth.sin(Mth.PI * t / NOD_TICKS) * (1 - t / NOD_TICKS * .5F);
    }
}
