package com.digicube.digimon;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable physical dimensions and optional riding data, shared by each species.
 * @param modelScale renderer scale applied to the authored model
 * @param dimensions collision dimensions and eye height in blocks
 * @param mount optional seat and movement settings
 * @param hitParts extra hittable volumes for bodies that extend well beyond the collision box
 */
public record DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount, List<HitPart> hitParts) {

    /** Original rookie dimensions, retained for existing species and unknown ids. */
    public static final DigimonBody DEFAULT = new DigimonBody(0.75F,
            EntityDimensions.scalable(0.7F, 1.3F).withEyeHeight(1.15F), Optional.empty());

    public DigimonBody {
        if (!Float.isFinite(modelScale) || modelScale <= 0.0F) {
            throw new IllegalArgumentException("Model scale must be positive and finite");
        }
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(mount, "mount");
        hitParts = List.copyOf(Objects.requireNonNull(hitParts, "hitParts"));
    }

    /** Bodies whose collision box already covers them. */
    public DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount) {
        this(modelScale, dimensions, mount, List.of());
    }

    /**
     * One extra hittable box, carried along by the body. A long neck or tail is a chain of these.
     * @param offset bottom-centre of the box in blocks relative to the feet at yaw zero (+Z forward)
     * @param width horizontal size in blocks
     * @param height vertical size in blocks
     */
    public record HitPart(Vec3 offset, float width, float height) {
        public HitPart {
            Objects.requireNonNull(offset, "offset");
            if (!Double.isFinite(offset.x) || !Double.isFinite(offset.y) || !Double.isFinite(offset.z)
                    || !Float.isFinite(width) || width <= 0 || !Float.isFinite(height) || height <= 0) {
                throw new IllegalArgumentException("Invalid hit part");
            }
        }
    }

    /**
     * A single tamer's riding configuration.
     * @param seat position in blocks relative to the feet at yaw zero (+Z forward)
     * @param speed ridden movement speed
     * @param stepHeight maximum automatic step height in blocks
     * @param standing whether seat denotes the feet instead of the vanilla riding attachment
     * @param waterSeatOffset change in attachment position in the swimming posture
     * @param combat          whether the rider casts this Digimon's attacks (mounted combat)
     * @param turnRate        degrees a tick the mount turns toward the rider's view; 0 follows it instantly.
     *                        Above zero the mount also gathers pace instead of starting at full speed
     * @param sprint          pace multiplier while the rider sprints; 1 for a mount that cannot
     */
    public record Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset,
                        AerialMount flight, java.util.List<RiderAttack> riderAttacks, float turnRate, float sprint,
                        float waterTurnRate, float waterSprint) {
        public Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset, AerialMount flight) {
            this(seat, speed, stepHeight, standing, waterSeatOffset, flight, java.util.List.of(), 0, 1, 0, 1);
        }
        /** {@code speed} 0 (the sheet leaves it out) is the rule: a mount moves under its rider at the pace it has by itself. */
        public boolean ownPace() { return speed == 0; }
        /** Mounted combat is opt-in: a mount fights for its rider when its sheet lists rider attacks. */
        public boolean combat() { return !riderAttacks.isEmpty(); }
        public Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset) {
            this(seat, speed, stepHeight, standing, waterSeatOffset, null);
        }
        public Mount(Vec3 seat, float speed, float stepHeight) {
            this(seat, speed, stepHeight, false, Vec3.ZERO);
        }

        /** Standing seats describe the feet; seated mounts retain the vanilla hip attachment. */
        public Vec3 position(float waterAmount) { return seat.add(waterSeatOffset.scale(waterAmount)); }

        public Mount {
            Objects.requireNonNull(seat, "seat");
            Objects.requireNonNull(waterSeatOffset, "waterSeatOffset");
            riderAttacks = java.util.List.copyOf(riderAttacks);
            if (!Double.isFinite(seat.x) || !Double.isFinite(seat.y) || !Double.isFinite(seat.z)
                    || !Double.isFinite(waterSeatOffset.x) || !Double.isFinite(waterSeatOffset.y) || !Double.isFinite(waterSeatOffset.z)
                    || !Float.isFinite(speed) || speed < 0.0F
                    || !Float.isFinite(stepHeight) || stepHeight < 0.0F
                    || !Float.isFinite(turnRate) || turnRate < 0 || !Float.isFinite(sprint) || sprint < 1
                    || !Float.isFinite(waterTurnRate) || waterTurnRate < 0 || !Float.isFinite(waterSprint) || waterSprint < 1) {
                throw new IllegalArgumentException("Invalid mount dimensions or speed");
            }
        }
    }
}
