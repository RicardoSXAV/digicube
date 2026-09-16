package com.digicube.fabric.client.evolution;

import com.digicube.digimon.EvolutionEvent;
import com.digicube.digimon.EvolutionTimeline;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Small phase cues stay aligned to server time without restarting a song for late observers. */
public final class EvolutionAudioSequence {
    public record Cue(String name, int tick, int lengthTicks) {}
    private final List<Cue> cues;
    private long lastTick = -1;

    public EvolutionAudioSequence(int duration) {
        var time = EvolutionTimeline.of(duration);
        var result = new ArrayList<Cue>();
        if (time.longForm()) {
            if (time.leadIn() > 0) result.add(new Cue("sky", 0, 70));
            result.add(new Cue("gather", (int) time.leadIn(), 24));
            result.add(new Cue("shed", (int) time.shedStart(), 40));
            result.add(new Cue("grid", (int) time.shedEnd(), 28));
            result.add(new Cue("reshape", (int) time.morphStart(), 57));
            result.add(new Cue("reconstruct", (int) time.morphEnd(), 49));
            result.add(new Cue("arrival", (int) time.skinEnd(), 50));
            result.add(new Cue("landing", duration - 16, 34));
        }
        cues = List.copyOf(result);
    }

    public List<Cue> cues() { return cues; }

    /** Allow normal packet/tick skew, but never burst all past cues or replay after clock correction. */
    public List<Cue> advance(long elapsed) {
        if (elapsed <= lastTick) return List.of();
        long before = lastTick;
        lastTick = elapsed;
        return cues.stream().filter(cue -> cue.tick() > before && cue.tick() <= elapsed
                && elapsed - cue.tick() <= 3).toList();
    }

    public static boolean continues(EvolutionEvent started, EvolutionEvent current,
                                    Identifier species, long elapsed, boolean alive) {
        if (!alive || elapsed > started.duration() + 26) return false;
        if (current != null) return started.equals(current);
        // Only a successful real commit may keep its musical release. Recall/cancel cuts it short.
        return !started.preview() && elapsed >= started.duration() - 2 && species.equals(started.target());
    }
}
