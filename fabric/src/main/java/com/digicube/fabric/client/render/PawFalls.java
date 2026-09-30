package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.NativeGroundModel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Paws strike the ground where the clips set them down, for a model with {@code paws} in the ground model catalog: each
 * paw's toe is followed through the very blend the model plays ({@link NativeGroundModel#toes}: the walk's columns, back
 * and aside, the run over them), so every paw is heard as it lands, at any pace and in any mix, a walk's four even beats,
 * a trot's diagonal pairs and a gallop's two pairs and its flight. A paw sounds the ground it lands on (grass, snow, ice,
 * stone), softly at a walk and harder at a gallop, the hind paws hardest; the species' own step, when the catalog names
 * one, sounds under it, or in its place on the grounds the catalog lists ({@link NativeGroundModel.Paws}). The quicker
 * the landings come, the softer each ({@link #balance}), so a sprint sounds about as full as a trot. A leap is heard
 * too: the hind paws' push as it leaves the ground, and the landing, forepaws then hind paws, as hard as the fall was
 * long. Skidding on ice ({@link DigimonEntity#getSkid}) the braced paws scrape: the
 * ground's own hit sound, quick and soft, and its chips thrown ahead of the forepaws. Client only; the gait's
 * {@code footfalls} silences vanilla's step per block.
 */
public final class PawFalls {
    private static final double RANGE = 32;
    /** Model units above the ground within which a toe is down, and the most phase ticks one sample of the swing spans. */
    private static final float CONTACT = .6F / 16, SAMPLE = .5F;
    /** The ground's own step, per paw: its share of the block's step volume and its pitch, a big body's (vanilla steps at 0.15). */
    private static final float STEP_VOLUME = .16F, STEP_PITCH = .8F;
    /** Ticks after the forepaws land from a leap that the hind paws come down. */
    private static final int HIND_AFTER = 2;
    /** A skid scrapes from this far into it, a scrape every SCRAPE_TICKS ticks, at this share of the block's volume and this pitch. */
    private static final float SCRAPE_FROM = .3F, SCRAPE_VOLUME = .22F, SCRAPE_PITCH = .7F;
    private static final int SCRAPE_TICKS = 3;
    /** Beats a second a gait may land at full weight; past it each step is softened by the square root of the excess. */
    private static final float FULL_BEATS = 6;
    /** Ticks over which the beat rate is averaged. */
    private static final float BEAT_TICKS = 20;
    private record Seen(float phase, boolean[] down, int takeoff, int landing, long hindAt, float hindWeight, float beats) {}
    private static final Map<Integer, Seen> seen = new HashMap<>();

    private PawFalls() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(PawFalls::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) { seen.clear(); return; }
        long now = minecraft.level.getGameTime();
        Map<Integer, Seen> next = new HashMap<>();
        for (DigimonEntity digimon : minecraft.level.getEntitiesOfClass(DigimonEntity.class, minecraft.player.getBoundingBox().inflate(RANGE))) {
            var model = NativeGroundModel.pawed(digimon.getSpeciesId());
            var gait = digimon.getLocomotion().groundGait();
            if (model == null || gait == null) continue;
            var paws = model.definition().paws();
            int count = paws.feet().size();
            float phase = digimon.getGroundAnimationPhase(1), amount = digimon.getGroundAnimationAmount(1), run = digimon.getGroundRunAmount(1);
            float[] shares = digimon.getGaitShares(1);
            float pivot = digimon.getPivotTurn(1), skid = digimon.getSkid(1);
            float[][] toes = new float[count][3];
            Seen before = seen.get(digimon.getId());
            boolean[] down = new boolean[count];
            long hindAt = before == null ? -1 : before.hindAt();
            float hindWeight = before == null ? 0 : before.hindWeight();
            // landings in one tick are one beat (a pair landing together); the rate of beats softens each step
            float beats = before == null ? 0 : before.beats(), soften = balance(beats);
            boolean beat = false;
            boolean walking = digimon.onGround() && !digimon.isInWater() && amount >= .05F;
            if (before != null && walking && Math.abs(phase - before.phase()) < gait.cycleTicks()) {
                // From last tick's phase to this one's in short steps: a paw lands where its toe comes down onto the ground.
                System.arraycopy(before.down(), 0, down, 0, count);
                int steps = Math.max(1, (int) Math.ceil(Math.abs(phase - before.phase()) / SAMPLE));
                for (int s = 1; s <= steps; s++) {
                    model.toes(Mth.lerp((float) s / steps, before.phase(), phase), amount, run, shares, pivot, skid, toes);
                    for (int f = 0; f < count; f++) {
                        boolean on = toes[f][1] >= model.toeRest()[f][1] - CONTACT;
                        if (on && !down[f]) { land(minecraft, digimon, paws, f, toes[f], weight(amount, run, paws.feet().get(f).hind()) * soften); beat = true; }
                        down[f] = on;
                    }
                }
            } else {
                model.toes(phase, amount, run, shares, pivot, skid, toes);
                for (int f = 0; f < count; f++) down[f] = !walking || toes[f][1] >= model.toeRest()[f][1] - CONTACT;
            }
            if (skid >= SCRAPE_FROM && digimon.onGround() && !digimon.isInWater()) {
                model.toes(phase, amount, run, shares, pivot, skid, toes);
                scrape(minecraft, digimon, paws, toes, skid, now);
            }
            if (before != null) {
                // The leap: the hind paws drive off the ground, and on landing the forepaws strike first, the hind paws after.
                if (digimon.ticksSinceLeapTakeoff() == 0 && before.takeoff() != 0)
                    for (int f = 0; f < count; f++) if (paws.feet().get(f).hind()) { land(minecraft, digimon, paws, f, model.toeRest()[f], 1.4F); beat = true; }
                if (digimon.ticksSinceLeapLanding() == 0 && before.landing() != 0 && digimon.leapImpact() > .1F) {
                    float weight = 1 + digimon.leapImpact();
                    for (int f = 0; f < count; f++) if (!paws.feet().get(f).hind()) { land(minecraft, digimon, paws, f, model.toeRest()[f], weight); beat = true; }
                    hindAt = now + HIND_AFTER;
                    hindWeight = weight * .9F;
                }
                if (hindAt >= 0 && now >= hindAt) {
                    for (int f = 0; f < count; f++) if (paws.feet().get(f).hind()) { land(minecraft, digimon, paws, f, model.toeRest()[f], hindWeight); beat = true; }
                    hindAt = -1;
                }
            }
            beats += ((beat ? 20 : 0) - beats) / BEAT_TICKS;
            next.put(digimon.getId(), new Seen(phase, down, digimon.ticksSinceLeapTakeoff(), digimon.ticksSinceLeapLanding(), hindAt, hindWeight, beats));
        }
        seen.clear();
        seen.putAll(next);
    }

    /**
     * The braced paws of a skid: chips of the ground thrown ahead of each forepaw every tick, and every few ticks one paw
     * after another scraping (the ground's hit sound, soft and low).
     */
    private static void scrape(Minecraft minecraft, DigimonEntity digimon, NativeGroundModel.Paws paws, float[][] toes, float skid, long now) {
        var level = minecraft.level;
        var random = digimon.getRandom();
        Vec3 slide = digimon.position().subtract(digimon.xo, digimon.yo, digimon.zo).multiply(1, 0, 1);
        for (int f = 0; f < toes.length; f++) {
            if (paws.feet().get(f).hind()) continue;
            Vec3 at = toeAt(digimon, toes[f]);
            var ground = groundUnder(level, digimon, at);
            if (ground.isAir()) continue;
            if (random.nextFloat() < skid)
                level.addParticle(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, ground),
                        at.x + (random.nextDouble() - .5) * .3, digimon.getY() + .05, at.z + (random.nextDouble() - .5) * .3,
                        slide.x * 1.5 + (random.nextDouble() - .5) * .1, .12 + random.nextDouble() * .1, slide.z * 1.5 + (random.nextDouble() - .5) * .1);
        }
        if (now % SCRAPE_TICKS != 0) return;
        int foot = (int) (now / SCRAPE_TICKS % toes.length);
        Vec3 at = toeAt(digimon, toes[foot]);
        var ground = groundUnder(level, digimon, at);
        if (ground.isAir()) return;
        var type = ground.getSoundType();
        level.playLocalSound(at.x, digimon.getY(), at.z, type.getHitSound(), SoundSource.NEUTRAL,
                type.getVolume() * SCRAPE_VOLUME * skid, type.getPitch() * SCRAPE_PITCH * (.94F + random.nextFloat() * .12F), false);
    }

    /** Where a toe is in the world (model units in, y down). */
    private static Vec3 toeAt(DigimonEntity digimon, float[] toe) {
        return new Vec3(toe[0], 1.501F - toe[1], -toe[2]).scale(digimon.getBody().modelScale()).yRot(-digimon.yBodyRot * Mth.DEG_TO_RAD).add(digimon.position());
    }

    /** The block a paw at {@code at} stands on, or the one under the body's middle. */
    private static net.minecraft.world.level.block.state.BlockState groundUnder(net.minecraft.world.level.Level level, DigimonEntity digimon, Vec3 at) {
        var ground = level.getBlockState(BlockPos.containing(at.x, digimon.getY() - .2, at.z));
        return ground.isAir() ? level.getBlockState(digimon.getOnPos()) : ground;
    }

    /** The share of its weight each step keeps at {@code beats} landings a second: whole up to FULL_BEATS, then less. */
    public static float balance(float beats) {
        return beats <= FULL_BEATS ? 1 : (float) Math.sqrt(FULL_BEATS / beats);
    }

    /** How hard a paw comes down: softly at a walk, pounding at a gallop, where the hind paws drive hardest. */
    private static float weight(float amount, float run, boolean hind) {
        return Mth.clamp(amount, .35F, 1) * (1 + .6F * run) * (hind ? 1 + .15F * run : 1);
    }

    /** One paw on the ground: the ground's own step where the toe is, and the species' own under it or in its place. */
    private static void land(Minecraft minecraft, DigimonEntity digimon, NativeGroundModel.Paws paws, int foot, float[] toe, float weight) {
        var level = minecraft.level;
        Vec3 at = toeAt(digimon, toe);
        var ground = groundUnder(level, digimon, at);
        float jitter = .96F + digimon.getRandom().nextFloat() * .08F;
        var step = ground.isAir() ? null : ground.getSoundType();
        if (step != null && !paws.replacesStep(step.getStepSound()))
            level.playLocalSound(at.x, digimon.getY(), at.z, step.getStepSound(), SoundSource.NEUTRAL,
                    step.getVolume() * STEP_VOLUME * weight, step.getPitch() * STEP_PITCH * jitter, false);
        if (paws.sound() != null && (step == null ? paws.replaces().isEmpty() : paws.padsOn(step.getStepSound())))
            level.playLocalSound(at.x, digimon.getY(), at.z, paws.sound(), SoundSource.NEUTRAL, paws.volume() * .5F * weight, paws.pitch() * jitter, false);
    }
}
