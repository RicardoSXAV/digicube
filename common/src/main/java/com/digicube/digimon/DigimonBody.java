package com.digicube.digimon;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable physical dimensions and optional riding data, shared by each species.
 * @param modelScale renderer scale applied to the authored model
 * @param dimensions collision dimensions and eye height in blocks
 * @param mount optional seat and movement settings
 */
public record DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount) {

    /** Original rookie dimensions, retained for existing species and unknown ids. */
    public static final DigimonBody DEFAULT = new DigimonBody(0.75F,
            EntityDimensions.scalable(0.7F, 1.3F).withEyeHeight(1.15F), Optional.empty());

    public DigimonBody {
        if (!Float.isFinite(modelScale) || modelScale <= 0.0F) {
            throw new IllegalArgumentException("Model scale must be positive and finite");
        }
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(mount, "mount");
    }

    /**
     * A single tamer's riding configuration.
     * @param seat position in blocks relative to the feet at yaw zero (+Z forward)
     * @param speed ridden movement speed
     * @param stepHeight maximum automatic step height in blocks
     * @param standing whether seat denotes the feet instead of the vanilla riding attachment
     * @param waterSeatOffset change in attachment position in the swimming posture
     */
    public record Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset,
                        AerialMount flight) {
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
            if (!Double.isFinite(seat.x) || !Double.isFinite(seat.y) || !Double.isFinite(seat.z)
                    || !Double.isFinite(waterSeatOffset.x) || !Double.isFinite(waterSeatOffset.y) || !Double.isFinite(waterSeatOffset.z)
                    || !Float.isFinite(speed) || speed <= 0.0F
                    || !Float.isFinite(stepHeight) || stepHeight < 0.0F) {
                throw new IllegalArgumentException("Invalid mount dimensions or speed");
            }
        }
    }
}
