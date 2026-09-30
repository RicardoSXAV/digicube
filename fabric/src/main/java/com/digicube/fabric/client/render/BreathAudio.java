package com.digicube.fabric.client.render;

import com.digicube.digimon.BreathAttacks;
import com.digicube.entity.DigimonEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Client: the sounds of every breath of puffs this client flies, as its sheet names them ({@link BreathAttacks.Sounds}):
 * the start as a mouth begins shedding, the loop from the mouth while it sheds (fading in under the start), and the end
 * as it stops, the loop fading out under it over three ticks. A breath already under way when it comes within hearing
 * starts at its loop; one that goes out of hearing fades without its end. Every client plays its own, from the breaths it
 * flies.
 */
public final class BreathAudio {
    /** Blocks within which a breath's loop is kept playing. */
    private static final double RANGE_SQUARED = 40 * 40;
    /** Ticks: the loop fades in under the start from the second to the ninth (a raised cosine), and out over three. */
    private static final float FADE_IN_FROM = 2, FADE_IN_TO = 9, FADE_OUT = 3;
    private final Map<Integer, Loop> loops = new HashMap<>();
    private ClientLevel level;

    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear(client));
    }

    private void clear(Minecraft client) {
        for (var loop : loops.values()) client.getSoundManager().stop(loop);
        loops.clear();
        level = null;
    }

    private void tick(Minecraft client) {
        if (client.level != level) { clear(client); level = client.level; }
        if (level == null || client.player == null || client.isPaused()) return;
        // breaths that stopped play their end under the fading loop; ones that went out of hearing just fade
        loops.values().removeIf(loop -> {
            DigimonEntity entity = loop.entity;
            boolean here = entity.isAlive() && !entity.isRemoved() && entity.level() == level;
            boolean near = here && entity.distanceToSqr(client.player) <= RANGE_SQUARED;
            if (near && entity.isClientBreathing()) return false;
            if (near) play(loop.mouth(), loop.sounds.end());
            loop.release();
            return true;
        });
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof DigimonEntity digimon) || !digimon.isClientBreathing() || loops.containsKey(digimon.getId())
                    || digimon.clientBreathMouth() == null || digimon.distanceToSqr(client.player) > RANGE_SQUARED) continue;
            var spec = BreathAttacks.get(digimon.getAnimatingAttack());
            if (spec == null || spec.sounds() == null) continue;
            boolean fresh = digimon.clientBreathTicks() <= 2;
            if (fresh) play(digimon.clientBreathMouth(), spec.sounds().start());
            var loop = new Loop(digimon, spec.sounds(), fresh);
            client.getSoundManager().play(loop);
            loops.put(digimon.getId(), loop);
        }
    }

    private void play(Vec3 at, Identifier sound) {
        level.playLocalSound(at.x, at.y, at.z, SoundEvent.createVariableRangeEvent(sound), SoundSource.NEUTRAL, 1, 1, false);
    }

    /** A breath's loop, following its mouth. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private final DigimonEntity entity;
        private final BreathAttacks.Sounds sounds;
        private int age, fade = -1;

        Loop(DigimonEntity entity, BreathAttacks.Sounds sounds, boolean fresh) {
            super(SoundEvent.createVariableRangeEvent(sounds.loop()), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.entity = entity;
            this.sounds = sounds;
            looping = true;
            delay = 0;
            pitch = 1;
            age = fresh ? 0 : (int) FADE_IN_TO;
            volume = gain();
            follow();
        }

        Vec3 mouth() { return entity.clientBreathMouth() != null ? entity.clientBreathMouth() : entity.position(); }

        void release() { if (fade < 0) fade = (int) FADE_OUT; }

        private void follow() {
            Vec3 at = mouth();
            x = at.x; y = at.y; z = at.z;
        }

        private float gain() {
            float in = age <= FADE_IN_FROM ? 0 : age >= FADE_IN_TO ? 1 : .5F - .5F * Mth.cos(Mth.PI * (age - FADE_IN_FROM) / (FADE_IN_TO - FADE_IN_FROM));
            float out = fade < 0 ? 1 : Mth.cos(.5F * Mth.PI * (1 - fade / FADE_OUT));
            return in * out;
        }

        @Override public boolean canStartSilent() { return true; }

        @Override public void tick() {
            if (fade == 0) { stop(); return; }
            age++;
            if (fade > 0) fade--;
            if (!entity.isRemoved()) follow();
            volume = gain();
        }
    }
}
