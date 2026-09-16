package com.digicube.fabric.client.evolution;

import com.digicube.Constants;
import com.digicube.digimon.EvolutionEvent;
import java.util.ArrayList;
import java.util.List;

/** Sound scheduling is checked without launching or controlling a graphical Minecraft client. */
public final class EvolutionAudioRegressionTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var score = new EvolutionAudioSequence(220);
        check(score.cues().stream().map(EvolutionAudioSequence.Cue::tick).toList()
                .equals(List.of(0, 60, 72, 100, 116, 160, 196, 204)), "approved visual beat alignment");
        var played = new ArrayList<EvolutionAudioSequence.Cue>();
        for (int tick = 0; tick < 260; tick++) {
            played.addAll(score.advance(tick));
            check(score.advance(tick).isEmpty(), "no duplicate tick playback");
        }
        check(played.equals(score.cues()), "each phase exactly once, including landing");
        var delayed = new EvolutionAudioSequence(220);
        check(delayed.advance(2).getFirst().name().equals("sky"), "ordinary tracking skew tolerated");
        var late = new EvolutionAudioSequence(220);
        check(late.advance(135).isEmpty(), "late observer does not replay old phases");
        check(late.advance(160).getFirst().name().equals("reconstruct"), "late observer rejoins next phase");
        check(late.advance(120).isEmpty() && late.advance(160).isEmpty(), "clock rollback cannot duplicate audio");
        check(late.advance(207).size() == 1, "large jump skips stale arrival but tolerates landing skew");
        check(new EvolutionAudioSequence(16).cues().isEmpty()
                && new EvolutionAudioSequence(32).cues().isEmpty(), "short and return remain server cues");
        check(new EvolutionAudioSequence(160).cues().getFirst().name().equals("gather"), "legacy without intro");
        check(new EvolutionAudioSequence(230).cues().get(1).tick() == 70, "legacy absorption timing");
        var source = Constants.id("gomamon"); var target = Constants.id("ikkakumon");
        var event = new EvolutionEvent(source, target, 100, 220, 4, false);
        check(EvolutionAudioSequence.continues(event, event, source, 70, true), "ongoing event follows source");
        check(!EvolutionAudioSequence.continues(event, null, source, 70, true), "recall cancels score");
        check(!EvolutionAudioSequence.continues(event, event, source, 70, false), "death cancels score");
        check(EvolutionAudioSequence.continues(event, null, target, 221, true), "successful reveal preserves release");
        check(!EvolutionAudioSequence.continues(event, null, target, 247, true), "release bounded");
        check(!EvolutionAudioSequence.continues(event,
                new EvolutionEvent(source, target, 100, 220, 5, false), source, 70, true), "new sequence replaces old");
        var preview = new EvolutionEvent(source, target, 100, 220, 4, true);
        check(EvolutionAudioSequence.continues(preview, preview, source, 221, true), "preview can release before restore ends");
        check(!EvolutionAudioSequence.continues(preview, null, source, 236, true), "preview restoration stops score");
        Constants.LOG.info("[evolution-audio] PASS {} assertions; 8 long cues; late tracking, cancellation, commit and replay", assertions);
    }
}
