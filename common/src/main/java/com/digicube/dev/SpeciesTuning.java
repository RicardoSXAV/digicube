package com.digicube.dev;

import com.digicube.digimon.DigimonBody;
import com.digicube.digimon.DigimonFlight;
import com.digicube.digimon.DigimonLocomotion;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Optional;

/**
 * Runtime tuning of a species sheet, for the developer panel. The tunable numbers are the
 * speeds: the movement attribute, the walk and run follow modifiers, the swim cruise speed
 * and the ridden speed. A tune replaces the registered species with a modified copy that
 * went through the same validation as a loaded sheet, then refreshes every live Digimon
 * of that species. {@link SpeciesSheetWriter} puts an accepted value back into the JSON.
 */
public final class SpeciesTuning {
    public static final String BASE_SPEED = "base_speed";
    public static final String WALK_SPEED = "walk_speed";
    public static final String RUN_SPEED = "run_speed";
    public static final String SWIM_SPEED = "swim_speed";
    public static final String MOUNT_SPEED = "mount_speed";
    public static final String FLIGHT_SPEED = "flight_speed";

    /**
     * One tunable number.
     * @param key   the tag key, also the JSON key except for the mount and flight speeds
     * @param label short label for the panel
     * @param step  what one click of + or - changes
     */
    public record Field(String key, String label, double step) {}

    public static final List<Field> FIELDS = List.of(
            new Field(BASE_SPEED, "base", 0.01),
            new Field(WALK_SPEED, "walk", 0.05),
            new Field(RUN_SPEED, "run", 0.05),
            new Field(SWIM_SPEED, "swim", 0.02),
            new Field(FLIGHT_SPEED, "flight", 0.02),
            new Field(MOUNT_SPEED, "mount", 0.02));

    private SpeciesTuning() {}

    /** The current numbers of a species; flight and mount speeds only when the species has them. */
    public static CompoundTag values(DigimonSpecies species) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble(BASE_SPEED, species.baseSpeed());
        DigimonLocomotion locomotion = species.locomotion();
        tag.putDouble(WALK_SPEED, locomotion.walkSpeed());
        tag.putDouble(RUN_SPEED, locomotion.runSpeed());
        tag.putDouble(SWIM_SPEED, locomotion.swimSpeed());
        if (locomotion.canFly()) tag.putDouble(FLIGHT_SPEED, locomotion.flight().speed());
        species.body().mount().ifPresent(mount -> tag.putDouble(MOUNT_SPEED, mount.speed()));
        return tag;
    }

    /**
     * A copy of the species with the given numbers; keys that are absent keep their value.
     * @throws IllegalArgumentException when the sheet validators reject the numbers
     */
    public static DigimonSpecies with(DigimonSpecies species, CompoundTag values) {
        double base = values.getDoubleOr(BASE_SPEED, species.baseSpeed());
        if (!Double.isFinite(base) || base <= 0) throw new IllegalArgumentException("base speed must be positive");
        DigimonLocomotion current = species.locomotion();
        DigimonFlight flight = current.flight();
        if (flight != null && values.contains(FLIGHT_SPEED)) {
            flight = new DigimonFlight(values.getDoubleOr(FLIGHT_SPEED, flight.speed()), flight.capacityTicks(),
                    flight.rechargeTicks(), flight.restTicks(), flight.restartFraction(), flight.landingReserveTicks(),
                    flight.minimumFlightTicks(), flight.startDistance(), flight.stopDistance(), flight.cruiseHeight(),
                    flight.clearanceWidth(), flight.clearanceHeight());
        }
        DigimonLocomotion locomotion = new DigimonLocomotion(current.followStartDistance(), current.followStopDistance(),
                values.getDoubleOr(WALK_SPEED, current.walkSpeed()), values.getDoubleOr(RUN_SPEED, current.runSpeed()),
                values.getDoubleOr(SWIM_SPEED, current.swimSpeed()), flight);
        DigimonBody body = species.body();
        Optional<DigimonBody.Mount> mount = body.mount();
        if (mount.isPresent() && values.contains(MOUNT_SPEED)) {
            DigimonBody.Mount ridden = mount.get();
            body = new DigimonBody(body.modelScale(), body.dimensions(), Optional.of(new DigimonBody.Mount(
                    ridden.seat(), (float) values.getDoubleOr(MOUNT_SPEED, ridden.speed()), ridden.stepHeight())));
        }
        return new DigimonSpecies(species.id(), species.stage(), species.attribute(), species.baseHealth(),
                species.baseAttack(), species.baseDefence(), (float) base, species.evolutions(), species.attacks(),
                body, locomotion);
    }

    /** Registers the tuned copy and refreshes the live Digimon of that species. */
    public static DigimonSpecies apply(MinecraftServer server, DigimonSpecies species, CompoundTag values) {
        DigimonSpecies tuned = with(species, values);
        DigimonSpeciesRegistry.replace(tuned);
        refresh(server, tuned.id());
        return tuned;
    }

    /** Puts the bundled sheet back, undoing every tune of that species. */
    public static DigimonSpecies reset(MinecraftServer server, Identifier id) {
        DigimonSpecies bundled = DigimonSpeciesBootstrap.bundled(id);
        DigimonSpeciesRegistry.replace(bundled);
        refresh(server, id);
        return bundled;
    }

    private static void refresh(MinecraftServer server, Identifier id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof DigimonEntity digimon && digimon.getSpeciesId().equals(id)) digimon.refreshSpeciesData();
            }
        }
    }
}
