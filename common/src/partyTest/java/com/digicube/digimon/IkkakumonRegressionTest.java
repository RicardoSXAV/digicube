package com.digicube.digimon;

import com.digicube.Constants;
import com.digicube.dev.SpeciesTuning;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.phys.Vec3;

/** Integration contracts for the first standing aquatic mount; no game window required. */
public final class IkkakumonRegressionTest {
    private IkkakumonRegressionTest() {}
    public static void run() {
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("ikkakumon"));
        var mount = species.body().mount().orElseThrow();
        check(species.stage() == DigimonStage.ADULT && species.attribute() == DigimonAttribute.VACCINE,
                "Ikkakumon is a vaccine adult");
        check(species.attacks().isEmpty(), "only authored locomotion is enabled");
        check(mount.standing() && species.locomotion().canSwim(), "standing aquatic mount is loaded from data");
        check(species.baseSpeed() > DigimonSpeciesRegistry.getOrThrow(Constants.id("gomamon")).baseSpeed()
                && species.locomotion().swimSpeed() > .46, "Ikkakumon has the faster land and water speeds");
        Vec3 feet = mount.position(0);
        Vec3 playerPosition = feet.add(Avatar.DEFAULT_VEHICLE_ATTACHMENT).subtract(Avatar.DEFAULT_VEHICLE_ATTACHMENT);
        check(playerPosition.distanceTo(feet) < 1e-9 && feet.y + 1.62 > 2.75,
                "standing attachment cancels the vanilla hip offset and lifts the eyes above the crown");
        check(mount.position(.5F).distanceTo(feet.lerp(mount.position(1), .5)) < 1e-9,
                "water attachment transitions continuously");
        var gait = species.locomotion().groundGait();
        check(gait.maxPlaybackRate() == 1 && gait.cycleTicks() == 20,
                "the walk remains at most one authored push/glide cycle per second");
        check(Math.abs(species.baseSpeed() - .10) < 1e-7
                && species.locomotion().walkSpeed() == 1.3 && species.locomotion().runSpeed() == 1.3
                && Math.abs(mount.speed() - .13) < 1e-7,
                "slower animation preserves the approved ground movement speeds");
        var uncapped = new DigimonGait(gait.cycleTicks(), gait.stride());
        for (float amount : new float[]{.125F, .25F, .5F, 1F}) {
            check(gait.advance(0, amount, 1) == 0, "stopping does not advance the walk");
            double slowTravel = gait.fullSpeed(1) * amount * .4;
            check(Math.abs(gait.advance(slowTravel, amount, 1) - .4) < 1e-7,
                    "slow movement retains continuous travel-responsive cadence");
            for (double travel : new double[]{.037, .13, .286, .75}) {
                check(gait.advance(travel, amount, 1) <= 1,
                        "acceleration and fast travel cannot accelerate the body cycle past the cap");
                double recovered = uncapped.advance(travel, amount, 1) / gait.cycleTicks() * gait.stride() * amount;
                check(Math.abs(travel-recovered) < 1e-6, "unspecified playback limits preserve existing gait behavior");
            }
        }
        var edit = new CompoundTag();edit.putDouble(SpeciesTuning.MOUNT_SPEED, .15);
        edit.putDouble(SpeciesTuning.SWIM_SPEED, .65);
        var tuned = SpeciesTuning.with(species, edit);
        check(tuned.body().mount().orElseThrow().standing()
                && tuned.body().mount().orElseThrow().waterSeatOffset().equals(mount.waterSeatOffset())
                && tuned.locomotion().groundGait().equals(gait), "developer tuning preserves rider and gait metadata");
        for (String id : new String[]{"greymon", "garurumon"}) {
            var other = DigimonSpeciesRegistry.getOrThrow(Constants.id(id)).body().mount().orElseThrow();
            check(!other.standing() && other.waterSeatOffset().equals(Vec3.ZERO), "existing seated mounts are unchanged");
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
