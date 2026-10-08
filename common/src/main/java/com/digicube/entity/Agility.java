package com.digicube.entity;

import com.digicube.digimon.DigimonBody;
import com.digicube.entity.ai.FlightPhase;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;

/**
 * A body's own agility ({@code body.leap}, {@code body.crouch}): the leap it takes standing or at a run, the crouch it ducks
 * blows in, the combat roll a crouch at a run throws it into and the tuck a crouch in the air is. Each is an action of the
 * body itself ({@link DigimonEntity#leap}, {@link DigimonEntity#crouch}, {@link DigimonEntity#roll}), whatever drives it:
 * the AI today, any controller that takes the body over later.
 *
 * <p>The low box is the vanilla pose, synced: {@code CROUCHING} (crouched on the ground, or tucked in the air) and, through a
 * roll's tucked window only, {@code SPIN_ATTACK} (the roll's lowest box). The box follows the pose on both sides
 * ({@code DigimonEntity.getDefaultDimensions}), lowered from the top over the same feet, and the hit parts with it
 * ({@link #partSquash}). The body stands again only where its standing box fits.
 *
 * <p>A roll keeps the run's momentum (a little push forward as it starts, or the roll's own {@code speed} if faster, eased
 * off by {@code keep} a tick), cannot start again until it ends, and comes up into the run (into the crouch while it is
 * held, or while there is no room to stand). A roll may also be thrown along a heading from any pace ({@link #roll(Vec3)}, a
 * dodge roll): the body turns to it at once and keeps facing it until the roll ends.
 * Landing from a tuck after a running leap rolls on. The body's own leap takes no input in the air. A synced code tells
 * clients when a roll starts ({@code DATA_ROLL}); they ease a crouch weight over a few ticks (the model blends every gait
 * clip into its {@code _crouch} twin by it) and play the {@code roll} clip on the roll's own clock.
 */
public final class Agility {
    /** Ticks after a leap before the next may go. */
    static final int LEAP_COOLDOWN = 10;
    /** A body's own leap: this share of its pace thrown forward, and this share more height, at its full run. */
    static final float LEAP_PUSH = .3F, LEAP_LIFT = .12F;
    /** The pace a crouch at a run rolls from when neither the roll nor the gait says. */
    static final double ROLL_FROM = .2;
    /** The body's full run when its gait names none, blocks a tick. */
    private static final double RUN_PACE = .3;
    /** Vanilla's air: gravity and drag a tick, and the share of ground speed the air keeps (the leap's carry replaces it). */
    private static final double GRAVITY = .08, DRAG = .98, AIR_KEEP = .91;
    /** Share of the crouch weight the client moves a tick (about three and a half ticks down or up), and of the roll's. */
    private static final float CROUCH_EASE = 1 / 3.5F, ROLL_IN = .5F, ROLL_OUT = .3F;

    private final DigimonEntity body;
    /**
     * Where the body moves: the crouch is held; when the roll began (-1 with none) and its momentum; the leap's carry, and
     * whether the body has moved since it leapt (its first flight tick owes nothing to the carry).
     */
    private boolean held, carrying, launched;
    private int rollStart = -1, nextLeap;
    /** Where the body moves: the ground speed the last leap left with (a tuck that lands from a running leap rolls on). */
    private double leapPace;
    private Vec3 rollVelocity;
    /** Where the body moves: the heading a dodge roll faces until it ends (NaN for a roll along the run). */
    private float rollYaw = Float.NaN;
    /** The server's last look at the body: where it stood and whether it stood on the ground; its last tick's move. */
    private Vec3 lastPosition;
    private boolean wasOnGround = true;
    private Vec3 lastMove = Vec3.ZERO;
    /** Client: the eased crouch and roll weights, the roll clip's clock (-1 with none) and the roll code last seen. */
    private float crouch, previousCrouch, roll, previousRoll, rollClock = -1, previousRollClock = -1;
    private int seenRoll;

    Agility(DigimonEntity body) { this.body = body; }

    private DigimonBody.Crouch spec() { return body.getBody().crouch(); }
    private DigimonBody.Roll rollSpec() { var spec = spec(); return spec == null ? null : spec.roll(); }

    /** The pose is agility's to set: standing, crouched or tucked in a roll (never a dying or sleeping body's). */
    private static boolean managed(Pose pose) { return pose == Pose.STANDING || pose == Pose.CROUCHING || pose == Pose.SPIN_ATTACK; }

    /** Both sides: the body is crouched (on the ground or tucked in the air) or in its roll's tucked window. */
    public boolean low() { Pose pose = body.getPose(); return pose == Pose.CROUCHING || pose == Pose.SPIN_ATTACK; }
    /** Where the body moves: a roll is under way. */
    public boolean rolling() { return rollStart >= 0; }
    /** Where the body moves: ticks into the roll under way (-1 with none). */
    public int rollTicks() { return rollStart < 0 ? -1 : body.tickCount - rollStart; }
    /** The crouch is held (where the body moves). */
    public boolean held() { return held; }

    /** Blocks a tick from which a crouch on the ground becomes a roll. */
    public double rollFrom() {
        var roll = rollSpec();
        if (roll != null && roll.from() > 0) return roll.from();
        var gait = body.getLocomotion().groundGait();
        return gait != null && gait.runFrom() > 0 ? gait.runFrom() : ROLL_FROM;
    }

    /** The body's full run, blocks a tick: its gait's run, the pace its leap is measured against. */
    public double runPace() {
        var gait = body.getLocomotion().groundGait();
        return gait != null ? gait.runSpeed(body.getBody().modelScale()) : RUN_PACE;
    }

    /** Last tick's move along the ground, blocks (where the body moves). */
    public double pace() { return lastMove.horizontalDistance(); }
    /** Last tick's move (where the body moves). */
    public Vec3 lastMove() { return lastMove; }

    /** The way a roll started now would go: along the run, at its pace plus the push or the roll's own speed, whichever is faster. */
    public Vec3 rollVelocity() {
        var roll = rollSpec();
        Vec3 run = lastMove.multiply(1, 0, 1);
        double pace = run.length();
        if (roll == null) return run;
        Vec3 ahead = pace > 1.0E-3 ? run.scale(1 / pace) : Vec3.directionFromRotation(0, body.getYRot());
        return ahead.scale(Math.max(pace + roll.push(), roll.speed()));
    }

    /** Whether a crouch now would throw the body into a roll: it has one, stands on the ground and runs. */
    public boolean wouldRoll() {
        return rollSpec() != null && !rolling() && grounded() && !barred() && pace() >= rollFrom();
    }

    /** Whether the standing box fits where the body is (it stands up only then). */
    public boolean canStand() {
        return body.level().noBlockCollision(body, body.getDimensions(Pose.STANDING).makeBoundingBox(body.position()).deflate(1.0E-7));
    }

    private boolean grounded() {
        return body.onGround() && !body.isInWater() && !body.isInLava();
    }

    /** The crouch has no meaning here: afloat, on the wing, carried or without a crouch at all. */
    private boolean barred() {
        return spec() == null || body.isInWater() || body.isInLava() || body.isPassenger() || body.getFlightPhase() != FlightPhase.GROUNDED;
    }

    /** The pose outside a roll's tucked window: crouched while held (or with no room to stand), else standing. */
    private Pose upright() { return held || !canStand() ? Pose.CROUCHING : Pose.STANDING; }

    /**
     * Holds the crouch ({@code down}) or lets it go. Held, a body at a run on the ground throws itself into a roll, any other
     * on the ground crouches and one in the air tucks; let go, it stands as soon as its standing box fits (a roll under way
     * runs out first). Takes effect at once: the box is lowered this tick.
     * @return whether the body is low, or rolling, now
     */
    public boolean crouch(boolean down) {
        held = down && spec() != null;
        if (held && wouldRoll()) startRoll();
        settle();
        return low() || rolling();
    }

    /**
     * A roll now, from a pace of at least {@link #rollFrom} on the ground; none while one is under way.
     * @return whether it rolls
     */
    public boolean roll() {
        if (!wouldRoll() || !managed(body.getPose())) return false;
        startRoll();
        settle();
        return true;
    }

    /**
     * A dodge roll along {@code heading} from any pace on the ground, standing or crouched: the body turns to the heading at
     * once (the roll's clip rolls forward) and goes at the roll's own {@code speed}, or at its pace along the heading plus the
     * push if that is faster; a roll with neither goes nowhere and is refused. None while one is under way.
     * @return whether it rolls
     */
    public boolean roll(Vec3 heading) {
        var roll = rollSpec();
        Vec3 level = new Vec3(heading.x, 0, heading.z);
        if (roll == null || rolling() || !grounded() || barred() || !managed(body.getPose()) || level.lengthSqr() < 1.0E-8) return false;
        level = level.normalize();
        double speed = Math.max(Math.max(0, lastMove.dot(level)) + roll.push(), roll.speed());
        if (speed < 1.0E-3) return false;
        rollYaw = (float) (Mth.atan2(level.z, level.x) * Mth.RAD_TO_DEG) - 90;
        face();
        startRoll(level.scale(speed));
        settle();
        return true;
    }

    /** Holds a dodge roll's heading: body and head turned to it. */
    private void face() {
        if (Float.isNaN(rollYaw)) return;
        body.setYRot(rollYaw);
        body.yBodyRot = rollYaw;
        body.setYHeadRot(rollYaw);
    }

    private void startRoll() { startRoll(rollVelocity()); }

    private void startRoll(Vec3 velocity) {
        rollVelocity = velocity;
        rollStart = body.tickCount;
        carrying = false;
        body.syncRoll((body.rollCode() & ~1) + 3);
    }

    /** Ends the roll under way: it comes up into the run, or the crouch while held (or with no room to stand). */
    private void endRoll() {
        rollStart = -1;
        rollVelocity = null;
        rollYaw = Float.NaN;
        body.syncRoll(body.rollCode() & ~1);
    }

    /** Brings the pose in line with the crouch held, the roll under way and where the body is. */
    private void settle() {
        Pose pose = body.getPose();
        if (!body.isAlive() || !managed(pose)) return;
        if (barred()) {
            held = held && spec() != null;
            if (rolling()) endRoll();
            if (pose != Pose.STANDING && (spec() == null || canStand())) body.setPose(Pose.STANDING);
            return;
        }
        if (rolling()) {
            var roll = rollSpec();
            int ticks = rollTicks();
            // Off the ground (over an edge, or sprung out of it) or run out: up into the run, or the crouch.
            if (roll == null || ticks >= roll.ticks() || ticks > 1 && !body.onGround()) {
                endRoll();
                body.setPose(upright());
                return;
            }
            Pose want = roll.tucked(ticks) ? Pose.SPIN_ATTACK : upright();
            if (pose != want) body.setPose(want);
            return;
        }
        if (pose == Pose.SPIN_ATTACK) body.setPose(upright());
        else if (pose == Pose.CROUCHING) {
            // Landing from a tuck after a running leap rolls on.
            if (held && grounded() && !wasOnGround && rollSpec() != null && Math.max(pace(), leapPace) >= rollFrom()) { startRoll(); settle(); }
            else if (!held && canStand()) body.setPose(Pose.STANDING);
        } else if (held) body.setPose(Pose.CROUCHING);
    }

    /** Server, the end of every tick: reads the tick's move, then settles the pose (rolls run on and out, crouches stand up). */
    void tick() {
        Vec3 now = body.position();
        lastMove = lastPosition == null || now.distanceToSqr(lastPosition) > 9 ? Vec3.ZERO : now.subtract(lastPosition);
        lastPosition = now;
        if (spec() == null && !low() && !rolling()) { held = false; wasOnGround = body.onGround(); return; }
        settle();
        wasOnGround = body.onGround();
        if (wasOnGround) leapPace = 0;
    }

    // --- the leap ------------------------------------------------------------------------------------------------------

    /**
     * The body may leap now: it has a leap, stands on the ground, is not tucked in a roll, and has room for the box it will
     * fly in (the tuck's while the crouch is held, else its standing box).
     */
    public boolean canLeap() {
        var roll = rollSpec();
        return body.leapPower() > 0 && grounded() && !body.isPassenger() && body.tickCount >= nextLeap
                && body.getFlightPhase() == FlightPhase.GROUNDED && !(rolling() && roll != null && rollTicks() < roll.lowUntil())
                && (held || !low() || canStand());
    }

    /**
     * A leap from where the body stands: straight up from a standstill, thrown forward and a little higher by its pace at a
     * run (its run's momentum kept through the air at the leap's carry). Out of a roll it springs on; held, the crouch tucks.
     * @return whether it leapt
     */
    public boolean leap() {
        if (!canLeap()) return false;
        Vec3 run = rolling() && rollVelocity != null ? rollVelocity : lastMove.multiply(1, 0, 1);
        double runPace = runPace(), pace = run.length();
        float share = (float) Mth.clamp(pace / runPace, 0, 1);
        Vec3 ahead = pace > 1.0E-3 ? run.scale(1 / pace) : Vec3.directionFromRotation(0, body.getYRot());
        Vec3 push = ahead.scale(LEAP_PUSH * share * runPace);
        launch(new Vec3(run.x + push.x, body.leapPower() * (1 + LEAP_LIFT * share), run.z + push.z));
        return true;
    }

    /**
     * A leap that lands about {@code reach} blocks away along {@code heading} (level), at the leap's own height: out of the
     * line of a ground wave, onto a spot. The flight is worked out from vanilla's gravity and the leap's carry.
     * @return whether it leapt
     */
    public boolean leap(Vec3 heading, double reach) {
        if (!canLeap()) return false;
        Vec3 level = new Vec3(heading.x, 0, heading.z);
        if (level.lengthSqr() < 1.0E-8) return leap();
        double jump = body.leapPower(), keep = body.leapCarry() > 0 ? body.leapCarry() : AIR_KEEP, covered = 0, factor = 1, y = 0, vy = jump;
        // The ground speed's share left each tick of the flight, summed over the ticks until the feet are back down.
        for (int tick = 0; tick < 80 && (tick == 0 || y > 0); tick++) {
            covered += factor;
            factor *= keep;
            y += vy;
            vy = (vy - GRAVITY) * DRAG;
        }
        Vec3 out = level.normalize().scale(Math.max(0, reach) / Math.max(1, covered));
        launch(new Vec3(out.x, jump, out.z));
        return true;
    }

    private void launch(Vec3 velocity) {
        if (rolling()) endRoll();
        // In the air the box is the tuck while the crouch is held, else the standing body's (there is room: canLeap).
        body.setPose(held ? Pose.CROUCHING : Pose.STANDING);
        body.setDeltaMovement(velocity);
        body.needsSync = true;
        carrying = launched = true;
        leapPace = velocity.horizontalDistance();
        nextLeap = body.tickCount + LEAP_COOLDOWN;
        lastMove = Vec3.ZERO;
        wasOnGround = true;
    }

    // --- where the body moves: the roll's momentum, the crouched walk and the leap's carry --------------------------------

    /** Before the body moves: a roll sets its own momentum; a leap keeps its carry of the run through the air. */
    void beforeTravel() {
        if (rolling()) {
            if (rollVelocity == null) rollVelocity = body.getDeltaMovement().multiply(1, 0, 1);
            face();
            Vec3 v = body.getDeltaMovement();
            body.setDeltaMovement(rollVelocity.x, v.y, rollVelocity.z);
            carrying = false;
            return;
        }
        // The air took vanilla's share of the run on the last move: the carry gives back what it keeps instead.
        if (!carrying || launched || body.isInWater() || body.isAttacking()) return;
        float carry = body.leapCarry();
        if (carry <= 0) return;
        Vec3 v = body.getDeltaMovement();
        double k = carry / AIR_KEEP;
        body.setDeltaMovement(v.x * k, v.y, v.z * k);
    }

    /**
     * After the body moved from {@code before}: on the leap's first flight tick the air's share is kept, not the ground's
     * friction vanilla took as the feet still counted as down; the carry ends on the ground; a roll keeps what it moved, eased
     * by its {@code keep}.
     */
    void afterTravel(Vec3 before) {
        if (carrying) {
            if (launched) {
                launched = false;
                Vec3 moved = body.position().subtract(before), v = body.getDeltaMovement();
                double keep = body.leapCarry() > 0 ? body.leapCarry() : AIR_KEEP;
                body.setDeltaMovement(moved.x * keep, v.y, moved.z * keep);
            } else if (body.onGround() || body.isInWater()) carrying = false;
        }
        var roll = rollSpec();
        if (!rolling() || roll == null) return;
        Vec3 moved = body.position().subtract(before);
        rollVelocity = new Vec3(moved.x, 0, moved.z).scale(roll.keep());
        Vec3 v = body.getDeltaMovement();
        body.setDeltaMovement(rollVelocity.x, v.y, rollVelocity.z);
    }

    /** No input drives a roll, nor the body's own leap through the air: it goes on its momentum alone. */
    boolean drifting() { return rolling() || carrying && (launched || !body.onGround()); }

    /** The share of its pace a crouched body walks at (1 standing, rolling or in the air). */
    float walkShare() {
        var spec = spec();
        return spec != null && !rolling() && body.getPose() == Pose.CROUCHING && body.onGround() ? spec.pace() : 1;
    }

    /** The top of everything hittable on the standing body (its box and its hit parts), blocks above its feet. */
    public double standingTop() {
        var body = this.body.getBody();
        double top = body.dimensions().height();
        for (var part : body.hitParts()) top = Math.max(top, part.offset().y + part.height());
        return top;
    }

    /**
     * The same crouched ({@code tucked} false) or tucked in a roll, the hit parts lowered with the box; the standing top
     * without such a box.
     */
    public double lowTop(boolean tucked) {
        var spec = spec();
        if (spec == null || tucked && spec.roll() == null) return standingTop();
        var body = this.body.getBody();
        float height = tucked ? spec.roll().height() : spec.height(), squash = height / body.dimensions().height();
        double top = height;
        for (var part : body.hitParts()) top = Math.max(top, (part.offset().y + part.height()) * squash);
        return top;
    }

    /** The height share the hit parts are lowered to, about the feet: the low box's against the standing one's. */
    float partSquash() {
        var spec = spec();
        if (spec == null || !low()) return 1;
        float height = body.getPose() == Pose.SPIN_ATTACK && spec.roll() != null ? spec.roll().height() : spec.height();
        return height / body.getBody().dimensions().height();
    }

    // --- client: the eased weights the model blends by --------------------------------------------------------------------

    void clientTick() {
        previousCrouch = crouch;
        previousRoll = roll;
        previousRollClock = rollClock;
        // Through a roll the crouch weight follows the crouch alone (its tucked box is the roll clip's own).
        crouch = Mth.approach(crouch, body.getPose() == Pose.CROUCHING ? 1 : 0, CROUCH_EASE);
        int code = body.rollCode();
        boolean under = (code & 1) != 0;
        if (under && code != seenRoll) previousRollClock = rollClock = 0;
        else if (rollClock >= 0) rollClock++;
        seenRoll = code;
        roll = Mth.approach(roll, under ? 1 : 0, under ? ROLL_IN : ROLL_OUT);
        if (!under && roll <= 0) previousRollClock = rollClock = -1;
    }

    /** Client: how far into its crouch the body is drawn, 0 standing to 1 crouched (eased in and out). */
    public float crouchWeight(float partial) {
        float w = Mth.clamp(Mth.lerp(partial, previousCrouch, crouch), 0, 1);
        return w * w * (3 - 2 * w);
    }

    /** Client: how much of the pose the roll has, 0 to 1 (eased in and out). */
    public float rollWeight(float partial) {
        float w = Mth.clamp(Mth.lerp(partial, previousRoll, roll), 0, 1);
        return w * w * (3 - 2 * w);
    }

    /** Client: ticks into the roll's clip (-1 with none). */
    public float rollTick(float partial) {
        return rollClock < 0 ? -1 : Mth.lerp(partial, Math.max(0, previousRollClock), rollClock);
    }
}
