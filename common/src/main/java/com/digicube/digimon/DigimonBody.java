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
 * @param fireproof a body of fire ({@code fireproof}: Meramon): fire and lava never burn it, so it is never Burned
 * @param stepHeight blocks the body steps up without a mount ({@code step_height}; 0 leaves vanilla's 0.6). A mount's own
 *                   {@code step_height} wins
 * @param leap     the body's own leap ({@code leap}), or null; a mount that leaps ({@code mount.jump}) uses its own
 * @param crouch   how the body crouches and rolls ({@code crouch}), or null for one that never does
 */
public record DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount, List<HitPart> hitParts, float headTurn,
                          Serpent serpent, boolean fireproof, float stepHeight, Leap leap, Crouch crouch) {
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
        if (!(stepHeight >= 0 && stepHeight <= 4)) throw new IllegalArgumentException("Invalid step height");
        if (crouch != null && !(crouch.height() < dimensions.height() && crouch.eyeHeight() <= crouch.height()))
            throw new IllegalArgumentException("A crouch is lower than the standing body, its eye within it");
    }

    public DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount, List<HitPart> hitParts, float headTurn,
                       Serpent serpent, boolean fireproof) {
        this(modelScale, dimensions, mount, hitParts, headTurn, serpent, fireproof, 0, null, null);
    }

    /**
     * A leap of the body's own ({@code body.leap}), as a mount's {@code jump} and {@code leap_carry} are under a rider: the AI
     * (and any controller) leaps with it standing and at a run, and lands six blocks of fall unhurt.
     * @param jump  upward speed of a standing leap, blocks a tick (0.62 clears two blocks); a run throws it a little higher
     *              and forward by its pace
     * @param carry share of its speed along the ground the leaping body keeps each tick in the air; 0 leaves it to vanilla
     *              (0.91 a tick)
     */
    public record Leap(float jump, float carry) {
        public Leap {
            if (!(jump > 0 && jump <= 2 && carry >= 0 && carry < 1)) throw new IllegalArgumentException("Invalid leap");
        }
    }

    /**
     * A body that crouches ({@code body.crouch}): a lowered box (its hit parts lowered with it) that blows over it miss, a tuck
     * in the air, and a combat roll a crouch at a run throws it into ({@code roll}). The width stays the standing body's.
     * @param height    the crouched box's height, blocks
     * @param eyeHeight the crouched eye, blocks
     * @param pace      share of its pace a crouched body walks at
     * @param roll      the roll a crouch at a run becomes, or null for a body that only crouches
     */
    public record Crouch(float height, float eyeHeight, float pace, Roll roll) {
        public Crouch {
            if (!(height > 0 && eyeHeight >= 0 && pace > 0 && pace <= 1)) throw new IllegalArgumentException("Invalid crouch");
            if (roll != null && !(roll.height() <= height)) throw new IllegalArgumentException("A roll tucks no higher than the crouch");
        }
    }

    /**
     * A forward combat roll ({@code body.crouch.roll}): a crouch from a run throws the body into it. It keeps the run's
     * momentum (a little push forward at the start, or the roll's own {@code speed} if that is faster, eased off by
     * {@code keep} a tick), lies on its lowest box only through the tucked window ({@code low_from} to {@code low_until},
     * ticks into the roll; the standing box, or the crouch's while it is held, outside it), cannot start again until it
     * ends, and comes up into the run (or the crouch, held). The {@code roll} clip is that many ticks long.
     * @param ticks     how long the roll lasts, ticks
     * @param height    the tucked box's height, blocks
     * @param eyeHeight the tucked eye, blocks
     * @param lowFrom   the tick into the roll the tucked box starts
     * @param lowUntil  the tick into the roll it ends
     * @param push      blocks a tick added along the run as it starts
     * @param keep      share of its pace the roll keeps each tick
     * @param from      blocks a tick from which a crouch on the ground becomes a roll; 0 takes the gait's {@code run_from},
     *                  else 0.2
     * @param speed     blocks a tick the roll goes at least (the speed at which its clip's body rolls without slipping); 0
     *                  for the run's own
     */
    public record Roll(int ticks, float height, float eyeHeight, int lowFrom, int lowUntil, float push, float keep, float from, float speed) {
        public Roll {
            if (!(ticks >= 4 && ticks <= 60 && height > 0 && eyeHeight >= 0 && eyeHeight <= height && lowFrom >= 0 && lowUntil > lowFrom
                    && lowUntil <= ticks && push >= 0 && push <= 1 && keep > .5F && keep <= 1 && from >= 0 && from < 2 && speed >= 0 && speed <= 2))
                throw new IllegalArgumentException("Invalid roll");
        }
        /** Whether {@code tick} into the roll is in its tucked window. */
        public boolean tucked(int tick) { return tick >= lowFrom && tick < lowUntil; }
    }

    public DigimonBody(float modelScale, EntityDimensions dimensions, Optional<Mount> mount, List<HitPart> hitParts, float headTurn,
                       Serpent serpent) {
        this(modelScale, dimensions, mount, hitParts, headTurn, serpent, false);
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
     * @param coil           how it wraps its prey ({@code coil}), or null for a serpent that does not
     */
    public record Serpent(float swimHeight, float turnRadius, float swimTurnRadius, float standingTurn, float swimHeadDrop,
                          float neckTurn, float climbShare, Coil coil) {
        public Serpent {
            if (!(swimHeight >= 0 && turnRadius > 0 && swimTurnRadius > 0 && standingTurn >= 0 && Float.isFinite(swimHeadDrop)
                    && neckTurn > 0 && neckTurn <= 180 && climbShare >= 0 && climbShare < 1))
                throw new IllegalArgumentException("Invalid serpent");
        }

        public Serpent(float swimHeight, float turnRadius, float swimTurnRadius, float standingTurn, float swimHeadDrop,
                       float neckTurn, float climbShare) {
            this(swimHeight, turnRadius, swimTurnRadius, standingTurn, swimHeadDrop, neckTurn, climbShare, null);
        }

        /** Degrees a tick it may turn going {@code speed} blocks a tick: its circle at that pace, and its standing turn. */
        public float turnRate(double speed, boolean swimming) {
            return (float) Math.max(standingTurn, Math.toDegrees(speed / (swimming ? swimTurnRadius : turnRadius)));
        }
    }

    /**
     * How a serpent wraps its prey ({@code coil} in its {@code serpent}; {@link ConstrictionCoil}), in blocks.
     * @param girth the body's thickness where it coils: the loops lie that far out from the prey and stack that high
     * @param neck  the body behind the head left free of the loops, rearing from the top loop to the head over the prey
     * @param tail  the thin end of the tail left out of the loops, lying on the ground
     * @param loops the most loops it throws round a small prey
     */
    public record Coil(float girth, float neck, float tail, float loops) {
        public Coil {
            if (!(girth > 0 && girth < 4 && neck >= 0 && tail >= 0 && loops >= 1 && loops <= 4))
                throw new IllegalArgumentException("Invalid coil");
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
