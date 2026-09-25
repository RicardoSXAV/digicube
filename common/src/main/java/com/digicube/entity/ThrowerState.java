package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.ThrownAttacks;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * What a thrower is doing with its weapons, server side: whether it holds its returning weapon (carried on its back),
 * the throw, catch and pickup performances, and the charged throw's form, hold and release. The AI (or later a rider)
 * decides; this runs the timelines, spawns the projectiles and keeps the synced clip, charge and carried flag.
 *
 * <p>Every performance plays on the upper body over the gait, so the thrower walks through all of them except the
 * pickup, which bends down to the ground and holds still until the bone is in the fist.
 *
 * <p>The bone is tapped (thrown at once) or held: the wind-up stops cocked and winds tighter as the charge grows, and
 * the throw reaches further, flies faster and hits harder for it. Either weapon leaves the hand harder from a leap and
 * from a body carrying it forward ({@link ThrownAttacks#impulse}), which this measures from the body's own travel.
 */
public final class ThrowerState {
    public enum Stage { NONE, BONE_THROW, BONE_HOLD, BONE_RELEASE, CATCH, PICKUP, ICE_FORM, ICE_HOLD, ICE_RELEASE }
    private final DigimonEntity mob;
    private Stage stage = Stage.NONE;
    private int tick;
    private boolean carried = true;
    private UUID bone;
    private int regrowAt = -1, readyAt;
    private BoomerangEntity pending;
    private LivingEntity target;
    private float throwYaw; private double throwRange, throwLift; private int throwSide;
    private float charge, wantCharge;
    private int hold;
    private Vec3 aimPoint;
    private boolean releaseNow;
    /** A rider holds the charged throw (or the bone's wind-up): it keeps growing, then waits at full charge, until they let go. */
    private boolean riderHeld;
    /** The body's travel over the last ticks (a ridden body is moved by its rider's client, in steps), and its time in the air. */
    private final Vec3[] travel = {Vec3.ZERO, Vec3.ZERO, Vec3.ZERO};
    private Vec3 lastAt;
    private int airTicks;
    private double groundY = Double.NaN;
    private float lastImpulse = 1;

    ThrowerState(DigimonEntity mob) { this.mob = mob; }

    public ThrownAttacks.Returning returning() {
        for (var a : mob.speciesAttacks()) { var r = ThrownAttacks.returning(a); if (r != null) return r; }
        return null;
    }
    public ThrownAttacks.Charged charged() {
        for (var a : mob.speciesAttacks()) { var c = ThrownAttacks.charged(a); if (c != null) return c; }
        return null;
    }
    /** A species that fights with thrown weapons. */
    public boolean active() { return returning() != null || charged() != null; }

    public Stage stage() { return stage; }
    public int stageTick() { return tick; }
    public boolean carried() { return carried; }
    public float charge() { return charge; }
    public float wantedCharge() { return wantCharge; }
    public int holdTicks() { return hold; }
    public boolean busy() { return stage != Stage.NONE; }
    /** Holding the charged throw or forming it: the hands are full and the pace is a walk. */
    public boolean charging() { return stage == Stage.ICE_FORM || stage == Stage.ICE_HOLD; }
    /** The bone's wind-up held, winding tighter. */
    public boolean holdingBone() { return stage == Stage.BONE_HOLD; }
    /** The ticks the legs belong to the performance: the pickup bends to the ground until the bone is in the fist. */
    public boolean locksLegs() { return stage == Stage.PICKUP && tick <= returning().pickupClip().event() + PICKUP_STANDS; }
    /** Ticks after the fist closes on a lost bone that the body still stands (then it walks on while the arm puts it away). */
    public static final int PICKUP_STANDS = 3;
    public BoomerangEntity bone() {
        if (bone == null || !(mob.level() instanceof ServerLevel level)) return null;
        return level.getEntity(bone) instanceof BoomerangEntity b && b.isAlive() ? b : null;
    }
    /** Ticks until a lost weapon grows back, or -1. */
    public int regrowIn() { return regrowAt < 0 ? -1 : Math.max(0, regrowAt - mob.tickCount); }

    public boolean boneReady() {
        var r = returning();
        return r != null && carried && stage == Stage.NONE && mob.tickCount >= readyAt && !mob.isAttacking() && mob.isAttackReadyForThrower(r.attack());
    }
    public boolean icicleReady() {
        var c = charged();
        return c != null && stage == Stage.NONE && !mob.isAttacking() && mob.isAttackReadyForThrower(c.attack());
    }

    /** The world point the catching fist closes on, for the thrower's current place and facing. */
    public Vec3 catchHand() { return local(returning().catchPoint()); }
    public Vec3 pickupHand() { return local(returning().pickupPoint()); }
    public Vec3 releaseHand() { return local(returning().releasePoint()); }
    private Vec3 local(Vec3 point) { return AttackGeometry.world(mob.position(), point, mob.yBodyRot); }
    /** Where a fist at {@code local} would be if the thrower stood at {@code feet} facing {@code yaw}. */
    public static Vec3 local(Vec3 feet, Vec3 point, float yaw) { return AttackGeometry.world(feet, point, yaw); }

    // --- the returning throw -----------------------------------------------------------------------------------

    /** Throw along {@code yaw}, turning at {@code range}, curving to {@code side} (+1 its left). */
    public boolean startThrow(LivingEntity target, float yaw, double range, int side) { return startThrow(target, yaw, range, side, 0); }
    /** The same, with the far turn {@code lift} blocks above the cruise height (a target up a slope, on a ledge, in the air). */
    public boolean startThrow(LivingEntity target, float yaw, double range, int side, double lift) {
        var r = returning();
        if (!boneReady()) return false;
        this.target = target; throwYaw = yaw; throwRange = range; throwSide = side; throwLift = lift;
        charge = 0; hold = 0; wantCharge = 0; releaseNow = false; riderHeld = false;
        begin(Stage.BONE_THROW, r.throwClip().name());
        mob.thrownAttackStarted(r.attack(), target);
        mob.countSkill("bone_throw");
        return true;
    }

    /** Re-aims the throw while it winds up: where the aim is when the bone leaves the fist is where it goes. */
    public void retarget(LivingEntity target, float yaw, double range, int side, double lift) {
        if (!windingUp()) return;
        this.target = target; throwYaw = yaw; throwRange = range; throwSide = side; throwLift = lift;
    }
    /** Winding up the throw, held or not, before the bone leaves the fist. */
    public boolean windingUp() {
        return stage == Stage.BONE_THROW && tick < returning().throwClip().event() || stage == Stage.BONE_HOLD
                || stage == Stage.BONE_RELEASE && tick < returning().releaseClip().event();
    }
    /** Ticks until the bone leaves the fist, for a wind-up under way (a hold ends when the wanted charge is reached); -1 otherwise. */
    public int boneReleaseIn() {
        var r = returning();
        if (r == null || !windingUp()) return -1;
        return switch (stage) {
            case BONE_THROW -> (riderHeld || wantCharge > 0) && !releaseNow ? r.holdAt() - tick + r.holdFor(wantCharge) + r.releaseClip().event()
                    : r.throwClip().event() - tick;
            case BONE_HOLD -> Math.max(0, r.holdFor(wantCharge) - hold) + r.releaseClip().event();
            default -> r.releaseClip().event() - tick;
        };
    }
    /** Ticks until the bone would leave the fist if the wind-up were let go now; -1 when none is under way. */
    public int boneReleaseIfLetGo() {
        var r = returning();
        if (r == null || !windingUp()) return -1;
        return switch (stage) {
            case BONE_THROW -> r.throwClip().event() - tick;
            case BONE_HOLD -> r.releaseClip().event();
            default -> r.releaseClip().event() - tick;
        };
    }
    /** How far out the bone could turn if it left the fist now at this charge, along {@code yaw}: the reach, with the body's impulse. */
    public double boneReach(float charge, float yaw) {
        var r = returning();
        return r.reach(charge) * impulse(Vec3.directionFromRotation(0, yaw), r.speed()[0] * r.mix(r.chargeSpeed(), charge), r.airBoost());
    }

    // --- the body's own motion, which a throw carries ------------------------------------------------------------

    /** The body's travel a tick, averaged over the last three. */
    public Vec3 motion() { return travel[0].add(travel[1]).add(travel[2]).scale(1 / 3.0); }
    /** Off the ground for more than a tick: a leap (a step up or down never is). */
    public boolean airborne() { return airTicks >= 2; }
    /** How much harder a throw along {@code direction} at {@code speed} leaves the hand now. */
    public float impulse(Vec3 direction, double speed, float airBoost) {
        return ThrownAttacks.impulse(airborne(), motion(), direction, speed, airBoost);
    }
    /** How much harder the last weapon thrown left the hand ({@link ThrownAttacks#impulse}), for checks and traces. */
    public float lastImpulse() { return lastImpulse; }
    /** The floor the thrower last stood on: a bone thrown from a leap cruises over the ground, not the leap. */
    public double groundY() { return Double.isNaN(groundY) ? mob.getY() : groundY; }

    private void track() {
        Vec3 at = mob.position();
        travel[2] = travel[1]; travel[1] = travel[0];
        travel[0] = lastAt == null || at.distanceToSqr(lastAt) > 4 ? Vec3.ZERO : at.subtract(lastAt);
        lastAt = at;
        boolean ground = mob.onGround() || mob.isInWater() || mob.isPassenger();
        airTicks = ground ? 0 : airTicks + 1;
        if (ground) groundY = mob.getY();
    }

    public boolean canCatch() {
        return !carried && (stage == Stage.NONE || stage == Stage.ICE_RELEASE && tick > charged().release().event());
    }
    public boolean beginCatch(BoomerangEntity entity) {
        if (!canCatch()) return false;
        if (stage == Stage.ICE_RELEASE) mob.thrownAttackEnded();
        pending = entity;
        begin(Stage.CATCH, returning().catchClip().name());
        return true;
    }
    void caught(BoomerangEntity entity) {
        carried = true; bone = null; regrowAt = -1; pending = null;
        var r = returning();
        readyAt = mob.tickCount + r.catchClip().length() - r.catchClip().event() + r.recovery();
        mob.countSkill("bone_caught");
        mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BONE_BLOCK_PLACE, SoundSource.NEUTRAL, 1F, 1.1F);
        Constants.LOG.info("[thrown] {} caught its bone", mob.getSpeciesId().getPath());
        mob.syncCarried(true);
    }
    void lost(BoomerangEntity entity) {
        regrowAt = mob.tickCount + returning().dropTicks();
        mob.countSkill("bone_lost");
    }
    /** Called by a lost bone lying on the ground: true once the thrower has grown another (it then vanishes). */
    boolean boneRegrown(BoomerangEntity entity) {
        if (pending == entity) return false;
        if (regrowAt < 0 || mob.tickCount < regrowAt) return false;
        regrow();
        return true;
    }
    private void regrow() {
        carried = true; bone = null; regrowAt = -1;
        readyAt = Math.max(readyAt, mob.tickCount + 10);
        mob.countSkill("bone_regrown");
        if (mob.level() instanceof ServerLevel level) {
            Vec3 back = AttackGeometry.world(mob.position(), new Vec3(0, mob.getBbHeight() * .7, -.5), mob.yBodyRot);
            level.sendParticles(ParticleTypes.SNOWFLAKE, back.x, back.y, back.z, 12, .25, .35, .25, .02);
            level.playSound(null, back.x, back.y, back.z, SoundEvents.BONE_BLOCK_PLACE, SoundSource.NEUTRAL, .8F, .8F);
        }
        Constants.LOG.info("[thrown] {} grew a new bone", mob.getSpeciesId().getPath());
        mob.syncCarried(true);
    }

    /** Bend down and take a lost bone lying within reach. */
    public boolean beginPickup(BoomerangEntity entity) {
        var r = returning();
        if (r == null || carried || stage != Stage.NONE || mob.isAttacking() || entity.phase() != BoomerangEntity.Phase.GROUNDED
                || entity.position().distanceTo(mob.position()) > r.pickupRadius()) return false;
        pending = entity;
        begin(Stage.PICKUP, r.pickupClip().name());
        return true;
    }

    // --- the charged throw --------------------------------------------------------------------------------------

    /** Form the projectile and hold it until the charge reaches {@code want} (0 = throw as soon as it has formed). */
    public boolean startIcicle(LivingEntity target, float want) {
        var c = charged();
        if (!icicleReady()) return false;
        this.target = target; wantCharge = Math.clamp(want, 0, 1); charge = 0; hold = 0; releaseNow = false; aimPoint = null; riderHeld = false;
        begin(Stage.ICE_FORM, c.form().name());
        mob.thrownAttackStarted(c.attack(), target);
        mob.countSkill("icicle_start");
        return true;
    }
    /**
     * Hit while forming or holding the charged throw: past a third of a charge the weight is too much to keep through
     * a blow and the spear shatters in its hands; the hands are free again after a short wait. A snap throw forming
     * is too quick to lose.
     */
    void struck(ServerLevel level, float damage) {
        var c = charged();
        if (c == null || !charging() || damage <= 0 || charge < BREAKS_ABOVE) return;
        Vec3 hand = local(c.releasePoint(charge));
        level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.PACKED_ICE.defaultBlockState()),
                hand.x, hand.y, hand.z, 12 + (int) (charge * 20), .3 + charge * .3, .3, .3 + charge * .3, .15);
        level.playSound(null, hand.x, hand.y, hand.z, SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 1F, .8F);
        mob.countSkill("icicle_broken");
        Constants.LOG.info("[thrown] {} lost its icicle to a blow at charge {}", mob.getSpeciesId().getPath(), String.format("%.2f", charge));
        cancel();
        mob.thrownAttackEnded();
        mob.putThrownCooldown(c.attack(), BROKEN_COOLDOWN);
    }
    /** Charge past which a blow breaks the held spear, and the wait for the next one after it breaks. */
    public static final float BREAKS_ABOVE = .3F;
    private static final int BROKEN_COOLDOWN = 20;

    /** The AI's latest wish for the charge; reaching it releases. */
    public void wantCharge(float want) { wantCharge = Math.clamp(want, 0, 1); }
    /** Throw at the next chance: a hold goes at once, and a wind-up not yet cocked goes without one (a tap). */
    public void releaseNow() { releaseNow = true; riderHeld = false; }
    /** The rider holds the charged throw (or the bone's cocked wind-up) as long as their button is down; {@link #releaseNow} lets it go. */
    public void riderHold() { riderHeld = true; wantCharge = 1; }
    /** Where the throw should land; the release solves the arc to it. */
    public void aim(Vec3 point) { aimPoint = point; }
    public Vec3 aimPoint() { return aimPoint; }
    public LivingEntity target() { return target; }

    // --- timeline ------------------------------------------------------------------------------------------------

    private void begin(Stage stage, String clip) {
        this.stage = stage; tick = 0;
        mob.showThrowerClip(clip, 0);
    }
    private void end() {
        if (stage == Stage.BONE_THROW || stage == Stage.BONE_HOLD || stage == Stage.BONE_RELEASE || stage == Stage.ICE_FORM
                || stage == Stage.ICE_HOLD || stage == Stage.ICE_RELEASE) mob.thrownAttackEnded();
        if (charge != 0) { charge = 0; hold = 0; mob.syncThrowCharge(0); }
        stage = Stage.NONE; tick = 0; pending = null;
        mob.showThrowerClip("", 0);
    }
    /** An interrupted performance: a weapon not yet thrown stays in hand (the unthrown ice melts away). */
    public void cancel() {
        if (stage == Stage.NONE) return;
        if (charge != 0) { charge = 0; hold = 0; mob.syncThrowCharge(0); }
        if (stage == Stage.ICE_FORM || stage == Stage.ICE_HOLD) mob.countSkill("icicle_cancelled");
        stage = Stage.NONE; tick = 0; pending = null;
        mob.showThrowerClip("", 0);
    }

    public void tick(ServerLevel level) {
        track();
        var r = returning();
        if (r != null && !carried && bone != null && bone() == null && regrowAt < 0 && stage != Stage.CATCH && stage != Stage.PICKUP) {
            // The bone is gone without a word (unloaded, or its owner lost track): the usual wait grows another.
            regrowAt = mob.tickCount + r.dropTicks();
        }
        if (r != null && !carried && regrowAt >= 0 && mob.tickCount >= regrowAt && bone() == null) regrow();
        if (stage == Stage.NONE) return;
        // Held by a rider who is no longer in the saddle: the AI has it back, and decides the release.
        if (riderHeld && !(mob.getControllingPassenger() instanceof net.minecraft.world.entity.player.Player)) riderHeld = false;
        tick++;
        switch (stage) {
            case BONE_THROW -> {
                if (tick == r.holdAt() && !releaseNow && (riderHeld || wantCharge > 0)) {
                    // Cocked and held: the throw waits here, winding tighter, until it is let go.
                    stage = Stage.BONE_HOLD; tick = 0; hold = 0;
                    mob.showThrowerClip(r.holdClip(), 0);
                    return;
                }
                mob.showThrowerClip(r.throwClip().name(), tick);
                if (tick == r.throwClip().event()) release(level, r);
                if (tick >= r.throwClip().length()) end();
            }
            case BONE_HOLD -> {
                hold++;
                float was = charge;
                charge = Math.min(1, hold / (float) r.chargeTicks());
                mob.syncThrowCharge(charge);
                if (charge >= 1 && was < 1) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.NEUTRAL, 1F, .7F);
                else if (hold % 7 == 0 && charge < 1) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), SoundSource.NEUTRAL, .5F + charge * .4F, .6F + charge * .5F);
                if (mob.tickCount % 3 == 0) {
                    Vec3 hand = local(r.releasePoint(charge));
                    level.sendParticles(ParticleTypes.SNOWFLAKE, hand.x, hand.y, hand.z, 1 + (int) (charge * 2), .25, .25, .25, .01);
                }
                mob.showThrowerClip(r.holdClip(), tick);
                if (releaseNow || !riderHeld && (charge >= wantCharge || hold >= r.maxHold())) {
                    stage = Stage.BONE_RELEASE; tick = 0; releaseNow = false;
                    mob.showThrowerClip(r.releaseClip().name(), 0);
                }
            }
            case BONE_RELEASE -> {
                mob.showThrowerClip(r.releaseClip().name(), tick);
                if (tick == r.releaseClip().event()) release(level, r);
                if (tick >= r.releaseClip().length()) end();
            }
            case CATCH -> {
                mob.showThrowerClip(r.catchClip().name(), tick);
                if (tick >= r.catchClip().length()) end();
            }
            case PICKUP -> {
                mob.getNavigation().stop();
                mob.showThrowerClip(r.pickupClip().name(), tick);
                if (tick == r.pickupClip().event()) {
                    if (pending != null && pending.isAlive()) { pending.discard(); caughtFromGround(); }
                    else { end(); return; }
                }
                if (tick >= r.pickupClip().length()) end();
            }
            case ICE_FORM -> {
                var c = charged();
                mob.showThrowerClip(c.form().name(), tick);
                formParticles(level, c);
                if (tick >= c.form().length()) {
                    if (wantCharge <= 0 || releaseNow) { stage = Stage.ICE_RELEASE; tick = 0; mob.showThrowerClip(c.release().name(), 0); }
                    else { stage = Stage.ICE_HOLD; tick = 0; mob.showThrowerClip(c.hold(), 0); }
                }
            }
            case ICE_HOLD -> {
                var c = charged();
                hold++;
                float was = charge;
                charge = Math.min(1, hold / (float) c.chargeTicks());
                mob.syncThrowCharge(charge);
                if (charge >= 1 && was < 1) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.2F, .6F);
                if (hold % 6 == 0) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.POWDER_SNOW_STEP, SoundSource.NEUTRAL, .5F + charge * .5F, 1.4F - charge * .6F);
                formParticles(level, c);
                mob.showThrowerClip(c.hold(), tick);
                if (releaseNow || !riderHeld && (charge >= wantCharge || hold >= c.maxHold())) { stage = Stage.ICE_RELEASE; tick = 0; mob.showThrowerClip(c.release().name(), 0); }
            }
            case ICE_RELEASE -> {
                var c = charged();
                mob.showThrowerClip(c.release().name(), tick);
                if (tick == c.release().event()) throwIcicle(level, c);
                if (tick >= c.release().length()) end();
            }
            default -> {}
        }
    }

    private void caughtFromGround() {
        var r = returning();
        carried = true; bone = null; regrowAt = -1; pending = null;
        readyAt = mob.tickCount + r.pickupClip().length() - r.pickupClip().event() + r.recovery();
        mob.countSkill("bone_picked_up");
        mob.level().playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BONE_BLOCK_PLACE, SoundSource.NEUTRAL, 1F, .9F);
        Constants.LOG.info("[thrown] {} picked its bone up", mob.getSpeciesId().getPath());
        mob.syncCarried(true);
    }

    /**
     * The bone leaves the fist: the flight is fixed here. The charge and the body's impulse set its pace, how far out
     * its turn may be and how hard it hits; it cruises over the floor the thrower last stood on, its turn lifted toward
     * the aim.
     */
    private void release(ServerLevel level, ThrownAttacks.Returning r) {
        Vec3 hand = local(r.releasePoint(charge));
        float impulse = impulse(Vec3.directionFromRotation(0, throwYaw), r.speed()[0] * r.mix(r.chargeSpeed(), charge), r.airBoost());
        double reach = r.reach(charge) * impulse, range = Math.clamp(throwRange, r.minRange(), reach);
        double pace = r.mix(r.chargeSpeed(), charge) * impulse, lift = Math.clamp(throwLift, r.lift()[0], r.lift()[1]);
        float power = r.mix(r.chargePower(), charge) * ThrownAttacks.impulsePower(impulse);
        lastImpulse = impulse;
        var path = BoomerangPath.of(hand, throwYaw, range, throwSide, r, groundY(), pace, lift);
        var entity = new BoomerangEntity(level, mob, r, path, power);
        level.addFreshEntity(entity);
        bone = entity.getUUID(); carried = false; regrowAt = -1;
        mob.syncCarried(false);
        level.playSound(null, hand.x, hand.y, hand.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 1.2F + charge * .4F, .7F - charge * .15F);
        if (airborne()) mob.countSkill("bone_throw_air");
        if (charge > 0) mob.countSkill(charge >= .75F ? "bone_throw_far" : "bone_throw_charged");
        Constants.LOG.info("[thrown] {} threw its bone range={} side={} flight={}t catch=({}) charge={} impulse={}{} lift={} power={}",
                mob.getSpeciesId().getPath(), String.format("%.1f", path.range()), throwSide, path.ticks(),
                String.format("%.1f %.1f %.1f", path.catchPoint().x, path.catchPoint().y, path.catchPoint().z),
                String.format("%.2f", charge), String.format("%.2f", impulse), airborne() ? " (air)" : "",
                String.format("%.1f", lift), String.format("%.2f", power));
    }

    private void formParticles(ServerLevel level, ThrownAttacks.Charged c) {
        if (mob.tickCount % 2 != 0) return;
        Vec3 hand = local(c.releasePoint(charge));
        level.sendParticles(ParticleTypes.SNOWFLAKE, hand.x, hand.y, hand.z, 1 + (int) (charge * 3), .3 + charge * .3, .3, .3 + charge * .3, .01);
    }

    private void throwIcicle(ServerLevel level, ThrownAttacks.Charged c) {
        Vec3 origin = local(c.releasePoint(charge));
        float speed = c.mix(c.speed(), charge), gravity = c.mix(c.gravity(), charge);
        Vec3 point = aimPoint != null ? aimPoint : target != null && target.isAlive() ? AttackGeometry.chest(target.getBoundingBox())
                : origin.add(Vec3.directionFromRotation(0, mob.getYRot()).scale(8));
        // Thrown from a leap or on the move it leaves harder: faster (so flatter to the same point), and it hits harder.
        float impulse = impulse(point.subtract(origin), speed, c.airBoost());
        speed *= impulse;
        lastImpulse = impulse;
        Vec3 velocity = Ballistics.launch(origin, point, speed, gravity);
        if (velocity == null) velocity = Ballistics.farthest(origin, point, speed);
        var entity = new IcicleEntity(level, mob, c, charge, origin, velocity, impulse);
        if (airborne()) mob.countSkill("icicle_throw_air");
        level.addFreshEntity(entity);
        mob.putThrownCooldown(c.attack());
        level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.NEUTRAL, .8F + charge * .5F, 1.3F - charge * .6F);
        mob.countSkill(charge >= .75F ? "icicle_throw_heavy" : charge >= .3F ? "icicle_throw_mid" : "icicle_throw_light");
        Constants.LOG.info("[thrown] {} threw an icicle charge={} hold={} speed={} impulse={}{} pitch={}", mob.getSpeciesId().getPath(),
                String.format("%.2f", charge), hold, String.format("%.2f", speed), String.format("%.2f", impulse), airborne() ? " (air)" : "",
                String.format("%.1f", Math.toDegrees(Math.atan2(velocity.y, velocity.horizontalDistance()))));
    }
}
