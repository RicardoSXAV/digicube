package com.digicube.digimon;

import com.digicube.Constants;
import net.minecraft.world.phys.Vec3;

/** Integration contracts for Ikkakumon, the seated sea mount; no game window required. */
public final class IkkakumonRegressionTest {
    private IkkakumonRegressionTest() {}
    public static void run() {
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("ikkakumon"));
        var mount = species.body().mount().orElseThrow();
        check(species.stage() == DigimonStage.ADULT && species.attribute() == DigimonAttribute.VACCINE,
                "Ikkakumon is a vaccine adult");
        check(species.attacks().stream().map(a->a.id().getPath()).toList().equals(java.util.List.of("harpoon_vulcan","heat_top")), "signature priority and close fallback are loaded");
        check(species.attacks().getFirst().durationTicks()>=23 && species.attacks().get(1).motion().activeUntil()==9,
                "signature recovers the whole horn before another contact move can start");
        check(!mount.standing() && species.locomotion().canSwim(), "the rider sits on the mane, and it swims");
        check(species.locomotion().swimSpeed() > .46, "Ikkakumon is the fast swimmer");
        // On ordinary ground (friction 0.6) a body the move control feeds once goes speed / (1 - 0.6 * 0.91) a tick.
        double ground = 1 / (1 - .6 * .91);
        var gait = species.locomotion().groundGait();
        float scale = species.body().modelScale();
        check(Math.abs(species.baseSpeed() * species.locomotion().walkSpeed() * ground - gait.fullSpeed(scale)) < 2e-3
                && species.locomotion().runSpeed() == species.locomotion().walkSpeed() && mount.ownPace(),
                "he walks at the walk's own pace, ridden and alone, so the feet stay planted");
        check(Math.abs(gait.fullSpeed(scale) * mount.sprint() - gait.runSpeed(scale)) < 2e-3,
                "the rider's sprint goes at his galumph's own pace");
        double fight = species.baseSpeed() * species.tactics().fightSpeed() * ground;
        check(Math.abs(fight - .30) < 3e-3 && fight / gait.runSpeed(scale) <= gait.maxPlaybackRate(),
                "his fights keep the pace they always had, the galumph played faster within its cap");
        check(gait.fullSpeed(scale) <= .1 + 1e-3 && gait.runSpeed(scale) < .2, "a walrus is slow on land: 2 blocks a second walking, under 4 galumphing");
        // The seat is on the mane behind the head; the first-person eye sits a little to the right of it so the horn
        // stands beside the crosshair (the model draws the rider centred), and rises and moves on with the swimming pose.
        check(Math.abs(mount.seat().x + .3) < 1e-6 && mount.seat().y > 2.5 && Math.abs(mount.seat().z) < .5,
                "the seat is on the mane, the eye a little to the right of the horn");
        check(mount.position(1).y > mount.position(0).y && mount.position(1).z > mount.position(0).z,
                "swimming stretched out carries the seat up and forward");
        check(mount.position(.5F).distanceTo(mount.position(0).lerp(mount.position(1), .5)) < 1e-9,
                "the water attachment moves continuously");
        var sea = mount.sea();
        check(sea.floatLine() > .5 && sea.floatLine() < .7 && sea.surfaceDive() > 20 && sea.roll() > 0 && mount.waterSprint() > 1,
                "it floats head and mane out, holds the surface through a rider's glance down, and barrel-rolls");
        check(gait.directional() && gait.footfalls() && gait.cycleTicks() == 18 && gait.runCycleTicks() == 16,
                "a planted four-beat walk with its own back and side steps, a galumph, and heavy footfalls");
        check(gait.backStride() < gait.stride() && gait.sideStride() < gait.stride(), "reined back and stepping aside he takes shorter steps");
        for (String id : new String[]{"greymon", "garurumon"}) {
            var other = DigimonSpeciesRegistry.getOrThrow(Constants.id(id)).body().mount().orElseThrow();
            check(!other.standing() && other.waterSeatOffset().equals(Vec3.ZERO), "existing seated mounts are unchanged");
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
