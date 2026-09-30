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
 * @param headTurn degrees the head looks off the body's line before the body turns after it ({@code head_turn}; vanilla's
 *                 75 without one). A head fused to its trunk that can hardly turn on its own (Mojyamon) sets it low, so
 *                 the whole body comes round to what it looks at.
 * @param serpent  a long body that lies along the path its head took ({@code serpent}), or null
 */
public record DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount, List<HitPart> hitParts, float headTurn,
                          Serpent serpent) {
    /** Vanilla's {@code Mob.getMaxHeadYRot}. */
    public static final float HEAD_TURN = 75;

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
        if (!Float.isFinite(headTurn) || headTurn < 5 || headTurn > 180) throw new IllegalArgumentException("Invalid head turn");
    }

    public DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount, List<HitPart> hitParts, float headTurn) {
        this(modelScale, dimensions, mount, hitParts, headTurn, null);
    }

    public DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount, List<HitPart> hitParts) {
        this(modelScale, dimensions, mount, hitParts, HEAD_TURN);
    }

    /**
     * A serpent's body ({@code serpent} on the sheet): it lies along the path its head took (SerpentTrail), so its hit
     * parts are laid along that path, and it turns only as it goes, as a snake does (its body cannot slew round).
     * @param swimHeight     blocks above the feet the middle of its body lies swimming: its hit parts are laid there in
     *                       the water, and a swim pose keeps the body inside its collision box
     * @param turnRadius     the tightest circle, in blocks, it turns through on land at its pace
     * @param swimTurnRadius the same swimming
     * @param standingTurn   degrees a tick it turns at most with no pace at all (its head and neck bring it round)
     * @param swimHeadDrop   blocks lower its head is swimming than in the land pose its attacks were measured in: a stream
     *                       breathed swimming leaves from the mouth where it is drawn
     * @param neckTurn       degrees its head turns at most off the way its body runs up to it: further round, it has to go on
     *                       and let its body curl after it (180 for no limit)
     * @param climbShare     the share of its body it holds up a face, the rest on the ground under it: it climbs a wall (and
     *                       lowers itself down one) that share of its length high ({@link DigimonBody#climbHeight}); 0 for
     *                       only its step
     */
    public record Serpent(float swimHeight, float turnRadius, float swimTurnRadius, float standingTurn, float swimHeadDrop,
                          float neckTurn, float climbShare) {
        public Serpent {
            if (!(swimHeight >= 0 && turnRadius > 0 && swimTurnRadius > 0 && standingTurn >= 0 && Float.isFinite(swimHeadDrop)
                    && neckTurn > 0 && neckTurn <= 180 && climbShare >= 0 && climbShare < 1))
                throw new IllegalArgumentException("Invalid serpent");
        }

        /** Degrees a tick it may turn going {@code speed} blocks a tick: its circle at that pace, and its standing turn. */
        public float turnRate(double speed, boolean swimming) {
            return (float) Math.max(standingTurn, Math.toDegrees(speed / (swimming ? swimTurnRadius : turnRadius)));
        }
    }

    /** How far behind its feet a serpent's body reaches: its furthest hit part, and a block past it. */
    public double length() {
        double back = 0;
        for (HitPart part : hitParts) back = Math.max(back, -part.offset().z + part.width() / 2);
        return back + 1;
    }

    /**
     * Blocks of wall a serpent climbs (and lowers itself down): the share of its body it holds up a face
     * ({@code climb_share}) of the body behind its feet, its furthest hit part; 0 for a body that is no serpent or does
     * not climb.
     */
    public double climbHeight() {
        return serpent == null ? 0 : serpent.climbShare() * (length() - 1);
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
     * @param sprint          pace multiplier while the rider sprints (reached gradually, a heavy mount breaks into its
     *                        gallop); 1 for a mount that cannot
     * @param jump            upward speed of a leap on the rider's jump key, in blocks a tick; 0 for a mount that
     *                        does not leap (0.62 clears two blocks)
     * @param turnToTravel    a four-legged body that cannot step sideways: the strafe keys turn it into the way it goes
     *                        and the back key reins it back, so it always walks along its own length
     * @param cameraDistance  the third-person camera's distance behind the rider in blocks; 0 frames the whole body
     * @param sprintBuild     ticks the sprint key takes to reach its full multiplier from a standstill of it (16 by
     *                        default, most of a second; a long build rewards holding the gallop)
     * @param sea             a sea mount's handling beyond turning and surging: its float line, porpoising, barrel roll
     * @param leapCarry       share of its speed along the ground a leaping body keeps each tick in the air; 0 leaves it to
     *                        vanilla (0.91 a tick, which soon eats a running leap's momentum)
     */
    public record Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset,
                        AerialMount flight, java.util.List<RiderAttack> riderAttacks, float turnRate, float sprint,
                        float waterTurnRate, float waterSprint, float jump, boolean turnToTravel, float cameraDistance, float sprintBuild,
                        Sea sea, float leapCarry) {
        public static final float SPRINT_BUILD = 16;
        public Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset, AerialMount flight,
                     java.util.List<RiderAttack> riderAttacks, float turnRate, float sprint, float waterTurnRate, float waterSprint, float jump,
                     boolean turnToTravel, float cameraDistance, float sprintBuild, Sea sea) {
            this(seat, speed, stepHeight, standing, waterSeatOffset, flight, riderAttacks, turnRate, sprint, waterTurnRate, waterSprint, jump,
                    turnToTravel, cameraDistance, sprintBuild, sea, 0);
        }
        public Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset, AerialMount flight,
                     java.util.List<RiderAttack> riderAttacks, float turnRate, float sprint, float waterTurnRate, float waterSprint, float jump,
                     boolean turnToTravel, float cameraDistance, float sprintBuild) {
            this(seat, speed, stepHeight, standing, waterSeatOffset, flight, riderAttacks, turnRate, sprint, waterTurnRate, waterSprint, jump,
                    turnToTravel, cameraDistance, sprintBuild, Sea.DEFAULT);
        }
        public Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset, AerialMount flight,
                     java.util.List<RiderAttack> riderAttacks, float turnRate, float sprint, float waterTurnRate, float waterSprint, float jump) {
            this(seat, speed, stepHeight, standing, waterSeatOffset, flight, riderAttacks, turnRate, sprint, waterTurnRate, waterSprint, jump, false, 0, SPRINT_BUILD);
        }
        public Mount(Vec3 seat, float speed, float stepHeight, boolean standing, Vec3 waterSeatOffset, AerialMount flight,
                     java.util.List<RiderAttack> riderAttacks, float turnRate, float sprint, float waterTurnRate, float waterSprint) {
            this(seat, speed, stepHeight, standing, waterSeatOffset, flight, riderAttacks, turnRate, sprint, waterTurnRate, waterSprint, 0);
        }
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
            Objects.requireNonNull(sea, "sea");
            riderAttacks = java.util.List.copyOf(riderAttacks);
            if (!Double.isFinite(seat.x) || !Double.isFinite(seat.y) || !Double.isFinite(seat.z)
                    || !Double.isFinite(waterSeatOffset.x) || !Double.isFinite(waterSeatOffset.y) || !Double.isFinite(waterSeatOffset.z)
                    || !Float.isFinite(speed) || speed < 0.0F
                    || !Float.isFinite(stepHeight) || stepHeight < 0.0F
                    || !Float.isFinite(turnRate) || turnRate < 0 || !Float.isFinite(sprint) || sprint < 1
                    || !Float.isFinite(waterTurnRate) || waterTurnRate < 0 || !Float.isFinite(waterSprint) || waterSprint < 1
                    || !Float.isFinite(jump) || jump < 0 || !Float.isFinite(cameraDistance) || cameraDistance < 0
                    || !Float.isFinite(sprintBuild) || sprintBuild < 1 || !(leapCarry >= 0 && leapCarry < 1)) {
                throw new IllegalArgumentException("Invalid mount dimensions or speed");
            }
        }
    }

    /**
     * How a sea mount handles past turning to the view and surging ({@code body.mount} keys {@code float_line},
     * {@code surface_dive}, {@code water_roll}).
     * @param floatLine   share of the body's height under water when it floats at the surface
     * @param surfaceDive degrees a rider at the surface may look down (or up, surging) before the body dives (or
     *                    breaches); within them it holds its float line and swims level, head out. 0 for a body that
     *                    follows the view at the surface too
     * @param roll        speed, blocks a tick, of the barrel roll a double tap of the jump key throws in the water; 0
     *                    for a body that does not roll
     */
    public record Sea(float floatLine, float surfaceDive, float roll) {
        public static final Sea DEFAULT = new Sea(.9F, 0, 0);

        public Sea {
            if (!(floatLine > .1F && floatLine <= 1) || !(surfaceDive >= 0 && surfaceDive < 70) || !(roll >= 0 && roll <= 2))
                throw new IllegalArgumentException("Invalid sea mount handling");
        }
    }
}
