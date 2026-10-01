package com.digicube.entity;

import com.digicube.digimon.ConstrictionCoil;
import com.digicube.digimon.DigimonAttack;
import com.digicube.registry.DCEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One wrap ({@link ConstrictionCoil}): the strike, then the hold. The strike flies the body at the spot beside its prey
 * where its head will loom over it, homing on that spot as the prey moves, and takes the prey on contact: the prey is
 * then held at the coil's middle (a mob against a block the coil would cut is drawn out into the open first), squeezed
 * on the coil's clock and let go, while the body stands where it struck, facing it. Any tick may end it: the prey got
 * away from the strike, the caster is gone, the prey slipped out or was cleansed of the hold.
 */
final class ConstrictionSession {
    /** What one tick came to: go on, the move is over, or it broke off ({@link #interruption}). */
    enum Status { GOING, DONE, BROKEN }

    private final DigimonEntity owner;
    private final LivingEntity target;
    private final ConstrictionCoil.Shape shape;
    private int captureTick = ConstrictionCoil.NOT_TAKEN;
    /** The coil's axis at the prey's feet, where the prey stood when it was taken, and where the caster's feet stand. */
    private Vec3 center, taken, stand;
    private int winding = 1;
    private boolean released;
    private float heldFrom;
    private double closest = Double.MAX_VALUE;
    private int stalled;
    private String interruption;

    private ConstrictionSession(DigimonEntity owner, LivingEntity target, ConstrictionCoil.Shape shape) {
        this.owner = owner; this.target = target; this.shape = shape;
    }

    // --- who may be wrapped, and from where --------------------------------------------------------------------------

    /** The first reason this prey cannot be wrapped at all, wherever the caster is, or null when it can. */
    static String whyIneligible(DigimonEntity owner, LivingEntity target, DigimonAttack attack) {
        if (!target.isAlive()) return "prey dead";
        if (!owner.canAttack(target) || owner.isAllyOf(target)) return "not a valid prey";
        if (target.isInvulnerable() || target instanceof Player player && (player.isCreative() || player.isSpectator())) return "prey invulnerable";
        if (target.isPassenger() || target.isVehicle()) return "prey riding";
        if (target.hasEffect(DCEffects.CONSTRICTED)) return "prey already held";
        if (target.hasEffect(DCEffects.CONSTRICTION_RESISTANCE)) return "prey hold-resistant";
        // Nearly dead prey does not earn a ten-second cooldown, unless it is frozen and nothing else can touch it.
        // A rider decides that alone.
        if (owner.rider() == null && !target.hasEffect(DCEffects.FROZEN)
                && target.getHealth() <= owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * attack.power()) return "prey nearly dead";
        if (!target.canBeAffected(new MobEffectInstance(DCEffects.CONSTRICTED, 3))) return "prey immune to holds";
        if (ConstrictionCoil.fit(target.getBoundingBox(), owner.getBody()) == null) return "prey too big to go round";
        if (!target.onGround() && !target.isInWater() && floorUnder(owner, target) == null) return "prey out of reach in the air";
        return null;
    }

    /**
     * Why a strike from {@code feet} at this prey would fail, or null when it can go: the prey out of the strike's
     * {@code reach} or off its level, no clear line to it, or no room round it for the coil.
     */
    static String whyNotFrom(DigimonEntity owner, LivingEntity target, DigimonAttack attack, Vec3 feet, double reach) {
        String why = whyIneligible(owner, target, attack);
        if (why != null) return why;
        Vec3 flat = target.position().subtract(feet).multiply(1, 0, 1);
        if (flat.length() - target.getBbWidth() / 2 > reach) return "out of reach";
        boolean afloat = owner.isInWater() && target.isInWater();
        Vec3 floor = afloat || target.isInWater() ? target.position() : floorUnder(owner, target);
        if (floor == null) return "prey out of reach in the air";
        if (Math.abs(floor.y - feet.y) > (afloat ? reach : ConstrictionCoil.STRIKE_STEP)) return String.format("level off by %.2f", floor.y - feet.y);
        // from its eyes or its middle: swimming at the surface its eyes may look over the rim of a pool at prey in it
        Vec3 chest = AttackGeometry.chest(target.getBoundingBox());
        if (!clear(owner, feet.add(0, owner.getEyeHeight(), 0), chest) && !clear(owner, feet.add(0, owner.getBbHeight() * .5, 0), chest))
            return "no line to prey";
        var shape = ConstrictionCoil.fit(target.getBoundingBox(), owner.getBody());
        return room(owner, target, shape, floor, feet) == null ? "no room to coil round the prey (" + cramped(owner, target, shape, floor, feet) + ")" : null;
    }

    private static boolean clear(DigimonEntity owner, Vec3 from, Vec3 to) {
        return owner.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner)).getType() == HitResult.Type.MISS;
    }

    /** The floor under airborne prey within a hop, or its own feet when it stands; null when it flies higher. */
    private static Vec3 floorUnder(DigimonEntity owner, LivingEntity target) {
        if (target.onGround()) return target.position();
        var hit = owner.level().clip(new ClipContext(target.position(), target.position().add(0, -ConstrictionCoil.SNATCH, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, owner));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }

    /**
     * Where the coil goes round this prey, whose feet are at {@code at}: there, when nothing solid cuts the loops' line
     * and the caster has a spot beside it; else, for a mob (a player is never moved), the nearest spot within
     * {@link ConstrictionCoil#DRAW_OUT} of it, toward the caster first, that has room for both. Null when there is none.
     */
    private static Vec3 room(DigimonEntity owner, LivingEntity target, ConstrictionCoil.Shape shape, Vec3 at, Vec3 from) {
        if (fits(owner, target, shape, at, from)) return at;
        if (target instanceof Player) return null;
        Vec3 toward = from.subtract(at).multiply(1, 0, 1);
        toward = toward.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : toward.normalize();
        for (double out = .4; out <= ConstrictionCoil.DRAW_OUT + 1.0E-6; out += .4)
            for (int turn : new int[]{0, 45, -45, 90, -90, 135, -135, 180}) {
                Vec3 spot = at.add(toward.yRot(turn * Mth.DEG_TO_RAD).scale(out));
                if (fits(owner, target, shape, spot, from)) return spot;
            }
        return null;
    }

    /** Whether the coil fits round prey standing at {@code at}: its box and the loops' line clear, the caster's spot too. */
    private static boolean fits(DigimonEntity owner, LivingEntity target, ConstrictionCoil.Shape shape, Vec3 at, Vec3 from) {
        return cramped(owner, target, shape, at, from) == null;
    }

    /** What keeps the coil from going round prey standing at {@code at}, or null when it fits. */
    private static String cramped(DigimonEntity owner, LivingEntity target, ConstrictionCoil.Shape shape, Vec3 at, Vec3 from) {
        var level = owner.level();
        AABB body = target.getBoundingBox().move(at.subtract(target.position()));
        if (level.getBlockCollisions(target, body.deflate(.01)).iterator().hasNext()) return "no room for the prey";
        boolean water = level.getFluidState(BlockPos.containing(at.x, at.y + .2, at.z)).is(FluidTags.WATER);
        if (!water && !level.getBlockCollisions(target, body.move(0, -.2, 0).setMaxY(body.minY)).iterator().hasNext()) return "no floor under the prey";
        for (AABB box : ConstrictionCoil.ring(at, shape, owner.getBody()))
            if (level.getBlockCollisions(owner, box).iterator().hasNext())
                return String.format(java.util.Locale.ROOT, "a block in the loops at %.1f %.1f %.1f", box.getCenter().x, box.getCenter().y, box.getCenter().z);
        return standAt(owner, shape, at, from) == null ? "no spot beside it for the head" : null;
    }

    /**
     * Where the caster's feet stand through the hold: beside the prey at {@code at}, on the side it came from (or round
     * from it, up to a right angle, when that spot is taken), at the prey's level; null when there is no such spot.
     */
    private static Vec3 standAt(DigimonEntity owner, ConstrictionCoil.Shape shape, Vec3 at, Vec3 from) {
        Vec3 out = from.subtract(at).multiply(1, 0, 1);
        out = out.lengthSqr() < 1.0E-6 ? Vec3.directionFromRotation(0, owner.getYRot() + 180) : out.normalize();
        double distance = ConstrictionCoil.headDistance(shape, owner.getBody());
        for (int turn : new int[]{0, 30, -30, 60, -60, 90, -90}) {
            Vec3 feet = at.add(out.yRot(turn * Mth.DEG_TO_RAD).scale(distance));
            AABB box = owner.getBoundingBox().move(feet.subtract(owner.position()));
            if (!owner.level().getBlockCollisions(owner, box.deflate(.01)).iterator().hasNext()) return feet;
        }
        return null;
    }

    /** A strike at {@code target}, or null when one could not go from where the caster is ({@link #whyNotFrom}). */
    static ConstrictionSession strike(DigimonEntity owner, LivingEntity target, DigimonAttack attack, double reach) {
        if (whyNotFrom(owner, target, attack, owner.position(), reach) != null) return null;
        var session = new ConstrictionSession(owner, target, ConstrictionCoil.fit(target.getBoundingBox(), owner.getBody()));
        if (owner.level() instanceof ServerLevel level)
            level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, .8F, .55F);
        return session;
    }

    // --- the move -----------------------------------------------------------------------------------------------------

    boolean captured() { return captureTick != ConstrictionCoil.NOT_TAKEN; }

    /** Ticks until the strike reaches its prey from where it is, {@code tick} ticks in: what an opponent has to dodge it. */
    int landsIn(int tick) {
        double pace = owner.isInWater() ? ConstrictionCoil.STRIKE_PACE_WATER : ConstrictionCoil.STRIKE_PACE;
        double gap = Math.max(0, owner.position().subtract(target.position()).horizontalDistance() - ConstrictionCoil.headDistance(shape, owner.getBody()));
        return Math.max(0, WIND_UP - tick) + Math.max(1, (int) Math.ceil(gap / pace));
    }
    int captureTick() { return captureTick; }
    LivingEntity target() { return target; }
    /** Why the last tick broke the wrap off; development traces name the gate. */
    String interruption() { return interruption; }

    private Status broken(String why) { interruption = why; return Status.BROKEN; }

    /** One tick of the move, {@code tick} ticks in. */
    Status tick(int tick) {
        if (!owner.isAlive() || owner.isRemoved()) return broken("caster gone");
        if (!captured()) return strikeTick(tick);
        int since = tick - captureTick;
        if (!released) {
            Status held = hold(since);
            if (held != Status.GOING) return held;
        }
        return since >= ConstrictionCoil.AFTER_CAPTURE ? Status.DONE : Status.GOING;
    }

    /** Ticks of the strike's wind-up (the head draws back) before the body flies. */
    private static final int WIND_UP = 2;
    /** Ticks the strike may go without closing on its spot before it gives up. */
    private static final int STALL_TICKS = 3;

    /**
     * The strike: the body flies at the spot beside the prey, turning after it, and takes it on reaching it. The prey
     * getting further than the strike can fly, off its level, out of sight or out of reach ends it as a miss.
     */
    private Status strikeTick(int tick) {
        if (tick > ConstrictionCoil.STRIKE_TICKS) return broken("the strike missed");
        if (target.level() != owner.level()) return broken("prey gone");
        String why = whyIneligible(owner, target, owner.activeAttackDefinition());
        if (why != null) return broken("strike refused: " + why);
        boolean water = owner.isInWater();
        Vec3 floor = target.isInWater() ? target.position() : floorUnder(owner, target);
        if (floor == null) return broken("prey flew off");
        Vec3 spot = standAt(owner, shape, floor, owner.position());
        if (spot == null) return broken("no spot beside the prey");
        float yaw = AttackGeometry.yaw(owner.position(), target.position());
        owner.setYRot(Mth.approachDegrees(owner.getYRot(), yaw, ConstrictionCoil.STRIKE_TURN));
        owner.yBodyRot = owner.yHeadRot = owner.getYRot();
        owner.syncAttackYaw(owner.getYRot());
        owner.getNavigation().stop();
        Vec3 to = spot.subtract(owner.position());
        double flat = to.horizontalDistance();
        if (tick >= WIND_UP) {
            double pace = water ? ConstrictionCoil.STRIKE_PACE_WATER : ConstrictionCoil.STRIKE_PACE;
            Vec3 step = flat < 1.0E-6 ? Vec3.ZERO : to.multiply(1, 0, 1).scale(Math.min(pace, flat) / flat);
            // Up or down a step to its prey on land, to its depth in the water.
            step = step.add(0, Mth.clamp(to.y, water ? -pace : -.5, water ? pace : .6), 0);
            owner.setDeltaMovement(0, water ? 0 : Math.min(0, owner.getDeltaMovement().y), 0);
            owner.move(MoverType.SELF, step);
            to = spot.subtract(owner.position());
            flat = to.horizontalDistance();
            if (flat < closest - .05) { closest = flat; stalled = 0; }
            else if (++stalled >= STALL_TICKS) return broken("the strike was blocked");
        }
        double reached = owner.position().subtract(floor).horizontalDistance();
        if (flat <= ConstrictionCoil.CONTACT || tick >= WIND_UP && reached <= ConstrictionCoil.headDistance(shape, owner.getBody()) + ConstrictionCoil.CONTACT)
            return capture(tick, floor);
        return Status.GOING;
    }

    /** Takes the prey: the coil's place (drawn out from a block if it must), which way it winds, the hold's marks. */
    private Status capture(int tick, Vec3 floor) {
        Vec3 at = room(owner, target, shape, floor, owner.position());
        if (at == null) return broken("no room to coil round the prey");
        stand = standAt(owner, shape, at, owner.position());
        if (stand == null) return broken("no spot beside the prey");
        if (!target.addEffect(new MobEffectInstance(DCEffects.CONSTRICTED, 3, 0, false, false, true), owner))
            return broken("the hold did not take");
        center = at;
        taken = target.position();
        captureTick = tick;
        heldFrom = target.getHealth();
        winding = winding(owner, center, stand);
        // Wrapping frozen prey keeps the ice through the hold and a short tail after release.
        if (target.hasEffect(DCEffects.FROZEN)) target.addEffect(new MobEffectInstance(DCEffects.FROZEN,
                ConstrictionCoil.RELEASE + ConstrictionCoil.FROZEN_TAIL_TICKS, 0, false, true), owner);
        target.addEffect(new MobEffectInstance(DCEffects.CONSTRICTION_RESISTANCE, ConstrictionCoil.RESISTANCE_TICKS, 0, false, false, true), owner);
        target.addEffect(new MobEffectInstance(DCEffects.FROST_RESISTANCE, ConstrictionCoil.RESISTANCE_TICKS, 0, false, false, true), owner);
        if (target instanceof DigimonEntity digimon) digimon.interruptAttack();
        owner.syncWrap(center, Math.max(target.getBbWidth(), 1.0E-3F), target.getBbHeight(), winding, captureTick);
        if (owner.level() instanceof ServerLevel level) {
            level.playSound(null, center.x, center.y + shape.height() * .5, center.z, SoundEvents.PHANTOM_BITE, SoundSource.NEUTRAL, 1F, .6F);
            level.playSound(null, center.x, center.y, center.z, SoundEvents.ARMADILLO_ROLL, SoundSource.NEUTRAL, .9F, .7F);
        }
        return hold(0);
    }

    /**
     * The way the coil winds (1 counterclockwise seen from above): so that the top loop starts on the side the body
     * already lies to, and the body swings the shorter way round into it.
     */
    private static int winding(DigimonEntity owner, Vec3 center, Vec3 stand) {
        var trail = owner.serverTrail();
        if (trail == null || trail.head() == null) return 1;
        double[] at = new double[3], tangent = new double[3];
        trail.sample(new double[]{2}, at, tangent);
        double ox = stand.x - center.x, oz = stand.z - center.z, bx = at[0] - trail.head().x, bz = at[2] - trail.head().z;
        // the body lying counterclockwise of the head (seen from above) wants the loop to start there: wind clockwise
        return ox * bz - oz * bx > 0 ? -1 : 1;
    }

    /** Ticks a held mob takes to be drawn out to the coil's middle. */
    private static final int DRAW_TICKS = 6;
    /** Blocks a tick the caster settles onto its spot beside the prey through the hold. */
    private static final double SETTLE = .3;

    /**
     * One tick of the hold, {@code since} ticks after the capture: the prey kept in the coil, squeezed, let go. A prey
     * that is gone (dead, out of reach of the coil, cleansed of the hold) is let go at once and the body unwinds from there.
     */
    private Status hold(int since) {
        if (!target.isAlive() || target.isRemoved() || target.level() != owner.level() || !owner.canStrike(target) || owner.isAllyOf(target)
                || target.isPassenger() || target.isVehicle()) return unwindEarly(since, "prey gone from the coil");
        if (since > 0 && !target.hasEffect(DCEffects.CONSTRICTED)) return unwindEarly(since, "hold cleansed");
        target.addEffect(new MobEffectInstance(DCEffects.CONSTRICTED, 3, 0, false, false, true), owner);
        // The prey stays in the coil: a mob is held there (drawn out to it first), a player slips out if it gets away.
        Vec3 keep = since < DRAW_TICKS ? taken.lerp(center, (since + 1) / (double) DRAW_TICKS) : center;
        if (target instanceof Player) {
            if (target.position().subtract(center).horizontalDistance() > ConstrictionCoil.SLIPPED) return unwindEarly(since, "prey slipped out");
        } else {
            target.setPos(keep.x, keep.y, keep.z);
        }
        target.setDeltaMovement(0, target.isInWater() ? 0 : Math.min(0, target.getDeltaMovement().y), 0);
        // The caster stands over its coil, facing the prey.
        owner.getNavigation().stop();
        Vec3 to = stand.subtract(owner.position());
        if (to.lengthSqr() > 1.0E-6) owner.move(MoverType.SELF, to.length() > SETTLE ? to.normalize().scale(SETTLE) : to);
        owner.setDeltaMovement(0, owner.isInWater() ? 0 : Math.min(0, owner.getDeltaMovement().y), 0);
        float yaw = AttackGeometry.yaw(owner.position(), center);
        owner.setYRot(yaw); owner.yBodyRot = owner.yHeadRot = yaw;
        owner.syncAttackYaw(yaw);
        int squeeze = since - ConstrictionCoil.FIRST_SQUEEZE;
        if (squeeze >= 0 && squeeze % ConstrictionCoil.INTERVAL == 0 && squeeze / ConstrictionCoil.INTERVAL < ConstrictionCoil.SQUEEZES) squeeze();
        if (since >= ConstrictionCoil.RELEASE) release();
        return Status.GOING;
    }

    /**
     * Lets the prey go before its time: the move's clock jumps to the release, so every client unwinds the body from here
     * (the coil does not go on squeezing empty air) and the move ends when it is unwound.
     */
    private Status unwindEarly(int since, String why) {
        interruption = why;
        release();
        captureTick += since - ConstrictionCoil.RELEASE;
        owner.syncWrap(center, Math.max(target.getBbWidth(), 1.0E-3F), target.getBbHeight(), winding, captureTick);
        return Status.GOING;
    }

    /** A squeeze: crush damage, the loops' creak, and a burst where they press. */
    private void squeeze() {
        owner.damageWithActiveAttack(target);
        if (!(owner.level() instanceof ServerLevel level)) return;
        double y = center.y + (shape.top() + shape.bottom()) / 2;
        level.playSound(null, center.x, y, center.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, .8F, .55F);
        level.sendParticles(ParticleTypes.CRIT, center.x, y, center.z, 10, shape.hug() * .8, (shape.top() - shape.bottom()) * .5 + .2, shape.hug() * .8, .15);
    }

    /** Lets the prey go (once): the hold ends and a squeezed Digimon is winded. */
    void release() {
        if (!captured() || released) return;
        released = true;
        target.removeEffect(DCEffects.CONSTRICTED);
        if (DigimonEntity.COMBAT_TRACE) com.digicube.Constants.LOG.info(String.format(java.util.Locale.ROOT,
                "[wrap-trace] %s held %s: %.1f of %.1f health squeezed out, %.1f left, caster at %.0f%%",
                owner.getSpeciesId(), target.getName().getString(), heldFrom - target.getHealth(), target.getMaxHealth(),
                target.getHealth(), 100 * owner.getHealth() / owner.getMaxHealth()));
        if (target instanceof DigimonEntity prey) prey.windFor(ConstrictionCoil.WINDED_TICKS);
    }
}
