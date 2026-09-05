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
     */
    public record Mount(Vec3 seat, float speed, float stepHeight) {
        public Mount {
            Objects.requireNonNull(seat, "seat");
            if (!Double.isFinite(seat.x) || !Double.isFinite(seat.y) || !Double.isFinite(seat.z)
                    || !Float.isFinite(speed) || speed <= 0.0F
                    || !Float.isFinite(stepHeight) || stepHeight < 0.0F) {
                throw new IllegalArgumentException("Invalid mount dimensions or speed");
            }
        }
    }
}
