package com.digicube.fabric.client.render;

import com.digicube.entity.DigimonEntity;
import com.digicube.fabric.client.model.NativeGroundModel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SoundType;

import java.util.HashMap;
import java.util.Map;

/**
 * Hooves strike the ground on the gait's own clock, for a model with {@code hoof_beats} in the ground model catalog
 * (where each hoof lands and lifts in the clips, per column of the lattice, walk to gallop):
 * <ul>
 * <li>walking, one clop where each hoof lands: four even beats; walked backwards the clip runs in reverse, so a hoof
 *     sounds where the forward clip lifts it;</li>
 * <li>galloping, Minecraft's own gallop once a stride. Each of its samples holds the four hoof hits of a gallop in
 *     about {@link #GALLOP_SPAN} ticks, so it starts on the stride's first hoof down (the first after the flight) and
 *     its pitch stretches it over the stride's own span, first hoof to last: the hits fall on the legs at any pace.</li>
 * </ul>
 * A leap is heard too: the push-off as it leaves the ground and the landing, as loud as the fall was long. Client only;
 * the body makes no vanilla steps of its own ({@code footfalls} on the gait).
 */
public final class HoofBeats {
    private static final double RANGE = 32;
    /** From this share of the run on, a stride is a gallop (one gallop sample) rather than four single clops. */
    private static final float GALLOP_RUN = .5F;
    /** Ticks from the first to the last hoof hit inside Minecraft's gallop samples (measured: 221-233 ms). */
    private static final float GALLOP_SPAN = 4.6F;
    /**
     * The gallop sample is only played this far from its own speed: slowed further it sounds slowed (a stride run
     * down to a stop), so a slower stride is four single clops instead; a faster one keeps the sample at the top.
     */
    private static final float GALLOP_SLOWEST = .82F, GALLOP_FASTEST = 1.25F;
    private record Seen(float phase, long struck, int takeoff, int landing) {}
    private static final Map<Integer, Seen> seen = new HashMap<>();

    private HoofBeats() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(HoofBeats::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) { seen.clear(); return; }
        long now = minecraft.level.getGameTime();
        Map<Integer, Seen> next = new HashMap<>();
        for (DigimonEntity digimon : minecraft.level.getEntitiesOfClass(DigimonEntity.class, minecraft.player.getBoundingBox().inflate(RANGE))) {
            var definition = NativeGroundModel.definitions().get(digimon.getSpeciesId());
            var gait = digimon.getLocomotion().groundGait();
            if (definition == null || definition.hoofBeats() == null || gait == null) continue;
            float phase = digimon.getGroundAnimationPhase(1), amount = digimon.getGroundAnimationAmount(1);
            Seen before = seen.get(digimon.getId());
            // Never struck is long ago, not Long.MIN_VALUE: now minus that overflows negative, and every clop read "too soon"
            // until a gallop sample (which does not ask) happened to play; seen is emptied on every pause, too.
            long struck = before == null ? now - 100 : before.struck();
            if (before != null) {
                leap(minecraft, digimon, before);
                if (digimon.onGround() && amount >= .15F && phase != before.phase() && Math.abs(phase - before.phase()) < gait.cycleTicks()
                        && stride(minecraft, digimon, definition.hoofBeats(), gait.cycleTicks(), before.phase(), phase, amount, now - struck))
                    struck = now;
            }
            next.put(digimon.getId(), new Seen(phase, struck, digimon.ticksSinceLeapTakeoff(), digimon.ticksSinceLeapLanding()));
        }
        seen.clear();
        seen.putAll(next);
    }

    /** Sounds what the hooves did between two phases; true when something was struck. */
    private static boolean stride(Minecraft minecraft, DigimonEntity digimon, NativeGroundModel.HoofTimes times, float cycle,
                                  float from, float to, float amount, long sinceStruck) {
        float run = digimon.getGroundRunAmount(1);
        int column = times.column(run);
        boolean back = to < from;
        float lo = Math.min(from, to), hi = Math.max(from, to);
        if (!back && run >= GALLOP_RUN) {
            float[] row = times.down()[column];
            int lead = lead(row);
            // Stretch the sample's hits over this stride's first-to-last hoof, at the pace the phase is running now.
            float pitch = GALLOP_SPAN / (span(row, lead) * cycle / (hi - lo));
            if (pitch >= GALLOP_SLOWEST) {
                if (!crossed(lo, hi, row[lead] * cycle, cycle)) return false;
                float volume = Mth.lerp(run, .45F, .7F) * Mth.clamp(amount, .5F, 1);
                play(minecraft, digimon, SoundEvents.HORSE_GALLOP, volume, Math.min(pitch, GALLOP_FASTEST));
                return true;
            }
        }
        // Forwards a hoof sounds where it lands; in reverse, where the forward clip lifts it.
        float[] row = back ? times.up()[column] : times.down()[column];
        int hind = 0, fore = 0;
        for (int i = 0; i < row.length; i++) if (crossed(lo, hi, row[i] * cycle, cycle)) { if (times.hind(i)) hind++; else fore++; }
        if (hind + fore == 0 || sinceStruck < 2) return false;
        var ground = minecraft.level.getBlockState(digimon.getOnPos());
        var sound = ground.getSoundType() == SoundType.WOOD ? SoundEvents.HORSE_STEP_WOOD : SoundEvents.HORSE_STEP;
        // A big armoured body: lower than a horse, the hind hooves lowest.
        play(minecraft, digimon, sound, .38F * Mth.clamp(amount, .4F, 1) * (hind + fore > 1 ? 1.15F : 1),
                (hind >= fore ? .66F : .74F) + digimon.getRandom().nextFloat() * .05F);
        return true;
    }

    private static void leap(Minecraft minecraft, DigimonEntity digimon, Seen before) {
        if (digimon.ticksSinceLeapTakeoff() == 0 && before.takeoff() != 0)
            play(minecraft, digimon, SoundEvents.HORSE_JUMP, .55F, .78F + digimon.getRandom().nextFloat() * .06F);
        if (digimon.ticksSinceLeapLanding() == 0 && before.landing() != 0 && digimon.leapImpact() > .15F)
            play(minecraft, digimon, SoundEvents.HORSE_LAND, .35F + .5F * digimon.leapImpact(), .7F + digimon.getRandom().nextFloat() * .06F);
    }

    /** Whether the phase passed {@code at} (mod the cycle) going from lo to hi. */
    private static boolean crossed(float lo, float hi, float at, float cycle) {
        return Math.floor((hi - at) / cycle) > Math.floor((lo - at) / cycle);
    }

    /** The first hoof down after the stride's longest gap (the flight): where a gallop's four beats begin. */
    private static int lead(float[] row) {
        int best = 0;
        float widest = -1;
        for (int i = 0; i < row.length; i++) {
            float gap = 1;
            for (int j = 0; j < row.length; j++) if (j != i) gap = Math.min(gap, Mth.positiveModulo(row[i] - row[j], 1F));
            if (gap > widest) { widest = gap; best = i; }
        }
        return best;
    }

    /** Share of the stride from the lead hoof's landing to the last hoof's. */
    private static float span(float[] row, int lead) {
        float span = 0;
        for (float at : row) span = Math.max(span, Mth.positiveModulo(at - row[lead], 1F));
        return span;
    }

    private static void play(Minecraft minecraft, DigimonEntity digimon, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        minecraft.level.playLocalSound(digimon.getX(), digimon.getY(), digimon.getZ(), sound, SoundSource.NEUTRAL, volume, pitch, false);
    }
}
