package com.digicube.fabric.client.evolution;

import com.digicube.Constants;
import com.digicube.digimon.EvolutionEvent;
import com.digicube.digimon.EvolutionTimeline;
import com.digicube.entity.DigimonEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Connection-owned positional score. No global music replacement or species-specific clips. */
public final class EvolutionAudio {
    private static final int MAX_PERFORMANCES = 3;
    private static final double RANGE_SQUARED = 32 * 32;
    private final Map<UUID, Performance> active = new HashMap<>();
    private ClientLevel level;

    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear(client));
    }

    private void clear(Minecraft client) {
        for (var performance : active.values())
            for (var sound : performance.sounds) client.getSoundManager().stop(sound);
        active.clear();
        level = null;
    }

    private void tick(Minecraft client) {
        if (client.level != level) { clear(client); level = client.level; }
        if (level == null || client.player == null || client.isPaused()) return;
        long now = level.getGameTime();
        var candidates = new ArrayList<DigimonEntity>();
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof DigimonEntity digimon) || !digimon.isAlive()
                    || digimon.isGuiPreview() || digimon.distanceToSqr(client.player) > RANGE_SQUARED) continue;
            var event = digimon.evolutionEvent();
            if (event != null && EvolutionTimeline.of(event.duration()).longForm()
                    && now < event.start() + event.duration()) candidates.add(digimon);
        }
        candidates.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(client.player)));
        if (candidates.size() > MAX_PERFORMANCES) candidates.subList(MAX_PERFORMANCES, candidates.size()).clear();
        var selected = candidates.stream().map(DigimonEntity::getUUID).toList();
        active.entrySet().removeIf(entry -> {
            var p = entry.getValue();
            long elapsed = now - p.event.start();
            boolean keep = p.entity.level() == level && !p.entity.isRemoved()
                    && p.entity.distanceToSqr(client.player) <= RANGE_SQUARED
                    && (elapsed >= p.event.duration() || selected.contains(entry.getKey()))
                    && EvolutionAudioSequence.continues(p.event, p.entity.evolutionEvent(),
                    p.entity.getSpeciesId(), elapsed, p.entity.isAlive());
            if (!keep) p.sounds.forEach(PhaseSound::cancel);
            return !keep;
        });
        for (var entity : candidates) {
            active.computeIfAbsent(entity.getUUID(), id -> new Performance(entity, entity.evolutionEvent()));
        }
        // Identical simultaneous scores can be phase-coherent, so reserve headroom by count.
        float gain = .85F / Math.max(1, active.size());
        for (var p : active.values()) {
            p.sounds.removeIf(sound -> sound.isStopped() || now > sound.endTick);
            for (var sound : p.sounds) sound.mixGain = gain;
            for (var cue : p.sequence.advance(now - p.event.start())) {
                var sound = new PhaseSound(p.entity, cue, now, gain);
                p.sounds.add(sound);
                client.getSoundManager().play(sound);
            }
        }
    }

    private static final class Performance {
        final DigimonEntity entity;
        final EvolutionEvent event;
        final EvolutionAudioSequence sequence;
        final List<PhaseSound> sounds = new ArrayList<>();
        Performance(DigimonEntity entity, EvolutionEvent event) {
            this.entity = entity;
            this.event = event;
            sequence = new EvolutionAudioSequence(event.duration());
        }
    }

    private static final class PhaseSound extends AbstractTickableSoundInstance {
        private final DigimonEntity entity;
        private final long endTick;
        private int fade = -1;
        private float mixGain;

        PhaseSound(DigimonEntity entity, EvolutionAudioSequence.Cue cue, long start, float gain) {
            super(SoundEvent.createVariableRangeEvent(Constants.id("evolution_" + cue.name())),
                    SoundSource.NEUTRAL, RandomSource.create());
            this.entity = entity;
            endTick = start + cue.lengthTicks() + 2;
            mixGain = volume = gain;
            pitch = 1;
            updatePosition();
        }

        void cancel() { if (fade < 0) fade = 3; }

        private void updatePosition() {
            x = entity.getX(); y = entity.getY() + entity.getBbHeight() * .55; z = entity.getZ();
        }

        @Override public void tick() {
            if (entity.isRemoved() || !entity.isAlive()) cancel();
            if (entity.level().getGameTime() >= endTick || fade == 0) { stop(); return; }
            updatePosition();
            volume = mixGain * (fade < 0 ? 1 : fade-- / 3F);
        }
    }
}
