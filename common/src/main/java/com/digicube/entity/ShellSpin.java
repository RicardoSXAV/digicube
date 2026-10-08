package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.SpinAttacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Server: a spin in the shell ({@link SpinAttacks}; Shellmon's Drill Shell), for a rider or the AI. The server moves the
 * body from the press until it has come back out ({@code DigimonEntity.serverOwnsBody}).
 *
 * <p>Withdraw: the body stops and pulls into its shell over {@code withdraw} ticks (the cast; it turns slowly onto the
 * aim). Charge: held, the shell spins up in place, grinding the ground, to full strength over {@code charge} ticks
 * (the rider's attack tile fills); let go (or after {@code max_hold}), it sets off along the aim at a speed between its
 * two ends. Spin: it is steered after the rider's view (or the AI's prey, led by its motion) by a turn rate that falls
 * as it goes faster, and its travel follows only a share of that turn ({@code grip}), so a fast spin skids wide; walls
 * throw it back ({@code bounce}); every body it runs into is struck (once a {@code hit_cooldown}), harder the stronger
 * the spin-up and the more of its speed it still has, thrown along its travel and up, and the spin rebounds. Friction
 * slows it; under {@code stop_below}, after {@code max_ticks} or in deep water it winds down, and the body comes out.
 *
 * <p>The way the body faces never jumps while it is in: spinning it swings round after its travel at
 * {@link #FACING_TURN} degrees a tick (a wall's bounce turns the travel at once, the facing follows), and winding down
 * and coming out it holds still, so the shell's own turn ({@link #settle}) is all that shows.
 */
public final class ShellSpin {
    public enum Phase { NONE, WITHDRAW, CHARGE, SPIN, WIND_DOWN, EMERGE }

    /** The synced code: the phase in the high bits, the ticks into it below. */
    private static final int TICK_BITS = (1 << 20) - 1;
    public static int code(Phase phase, int ticks) { return phase.ordinal() << 20 | Math.min(ticks, TICK_BITS); }
    public static Phase phase(int code) { return Phase.values()[Math.clamp(code >>> 20, 0, Phase.values().length - 1)]; }
    public static int ticks(int code) { return code & TICK_BITS; }

    private final DigimonEntity body;
    private final SpinAttacks.Spec spec;
    private final DigimonAttack move;
    private final Player rider;
    private LivingEntity prey;
    private final int aiCharge;
    private Phase phase = Phase.WITHDRAW;
    private int ticks;
    private float charge, yaw, launched, facing;
    /** Degrees a tick the body's facing swings round after its travel while it spins. */
    static final float FACING_TURN = 24;
    private Vec3 velocity = Vec3.ZERO;
    private boolean released;
    private final Map<Integer, Integer> struck = new HashMap<>();
    private int spun;

    ShellSpin(DigimonEntity body, SpinAttacks.Spec spec, DigimonAttack move, Player rider, LivingEntity prey) {
        this.body = body;
        this.spec = spec;
        this.move = move;
        this.rider = rider;
        this.prey = prey;
        this.yaw = this.facing = body.getYRot();
        double distance = prey == null ? 0 : prey.position().distanceTo(body.position());
        this.aiCharge = (int) Mth.lerp(Math.clamp(distance / Math.max(1, move.range()), 0, 1), spec.aiCharge()[0], spec.aiCharge()[1]);
    }

    public DigimonAttack move() { return move; }
    public SpinAttacks.Spec spec() { return spec; }
    public Player rider() { return rider; }
    public Phase phase() { return phase; }
    /** The rider let go: it sets off as soon as it has withdrawn, however far it has spun up. */
    public void release() { released = true; }
    /** How far it has spun up, 0 to 1. */
    public float charge() { return charge; }
    /** Blocks a tick it travels. */
    public float speed() { return (float) velocity.horizontalDistance(); }
    public int code() { return code(phase, ticks); }

    /**
     * One tick.
     * @return false once the body has come out (or the spin is called off: the rider gone, the body dead)
     */
    boolean tick(ServerLevel level) {
        if (rider != null && body.rider() != rider || !body.isAlive()) return false;
        if (prey != null && (!prey.isAlive() || prey.level() != level)) prey = null;
        ticks++;
        float wanted = rider != null ? rider.getYRot() : prey != null ? aimAtPrey() : yaw;
        switch (phase) {
            case WITHDRAW -> {
                velocity = velocity.scale(.6);
                yaw = Mth.approachDegrees(yaw, wanted, 4);
                if (ticks == 1) level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.SHULKER_CLOSE, SoundSource.NEUTRAL, 1.2F, .6F);
                if (ticks >= spec.withdraw()) {
                    thump(level);
                    next(Phase.CHARGE);
                }
            }
            case CHARGE -> {
                velocity = Vec3.ZERO;
                charge = Math.min(1, charge + 1F / spec.charge());
                // spinning in place it turns onto the aim at once
                yaw = Mth.approachDegrees(yaw, wanted, 18);
                grind(level, charge);
                boolean go = rider != null ? released || ticks >= spec.maxHold() : ticks >= aiCharge;
                // a top spins off the ground: thrown up in its shell, it sets off once it has come down
                if (go && body.onGround()) launch(level);
            }
            case SPIN -> {
                spun++;
                float top = spec.top(), speed = speed(), fast = Math.clamp(speed / top, 0, 1);
                // steering: the heading turns after the aim slower the faster it goes; the travel follows a share of it
                float turn = SpinAttacks.Spec.share(spec.turn(), fast);
                yaw = Mth.approachDegrees(yaw, wanted, turn);
                float travelYaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
                float grip = SpinAttacks.Spec.share(spec.grip(), fast);
                float newTravel = travelYaw + Mth.wrapDegrees(yaw - travelYaw) * grip;
                velocity = Vec3.directionFromRotation(0, newTravel).scale(speed * spec.friction());
                travel(level);
                strike(level);
                trail(level, fast);
                if (speed() < spec.stopBelow() || spun >= spec.maxTicks() || deepWater()) next(Phase.WIND_DOWN);
            }
            case WIND_DOWN -> {
                velocity = velocity.scale(.8);
                travel(level);
                if (ticks >= spec.windDown()) {
                    velocity = Vec3.ZERO;
                    level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.SHULKER_OPEN, SoundSource.NEUTRAL, 1.1F, .75F);
                    separate(level);
                    next(Phase.EMERGE);
                }
            }
            case EMERGE -> {
                velocity = Vec3.ZERO;
                if (ticks >= spec.emerge()) return false;
            }
            default -> { return false; }
        }
        if (phase != Phase.SPIN && phase != Phase.WIND_DOWN) {
            // the heavy shell stays put: a blow that would toss it lifts it only by the guard's share
            double fall = body.getDeltaMovement().y;
            if (fall > 0 && phase != Phase.EMERGE) fall *= spec.guard();
            body.setDeltaMovement(0, fall, 0);
            if (velocity.lengthSqr() > 1.0E-6) body.move(MoverType.SELF, velocity);
        }
        if (phase == Phase.WITHDRAW || phase == Phase.CHARGE) facing = yaw;
        else if (phase == Phase.SPIN && velocity.lengthSqr() > 1.0E-6)
            facing = Mth.approachDegrees(facing, (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z)), FACING_TURN);
        body.spinFacing(facing);
        body.syncSpin(code(), speed(), charge);
        return true;
    }

    private void next(Phase to) {
        phase = to;
        ticks = 0;
    }

    /** It sets off: a burst of grit behind it and the whirr of the shell. */
    private void launch(ServerLevel level) {
        launched = charge;
        velocity = Vec3.directionFromRotation(0, yaw).scale(spec.launch(charge));
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.NEUTRAL, 1.1F, .8F + .3F * charge);
        var ground = groundUnder(level, body.position());
        Vec3 back = velocity.normalize().scale(-1);
        if (ground != null) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), body.getX() + back.x, body.getY() + .1,
                body.getZ() + back.z, 20 + (int) (20 * charge), .6, .1, .6, .25);
        level.sendParticles(ParticleTypes.CLOUD, body.getX() + back.x, body.getY() + .2, body.getZ() + back.z, 6, .5, .1, .5, .04);
        if (DigimonEntity.COMBAT_TRACE) Constants.LOG.info("[spin] {} sets off at {} spun up {}", body.getSpeciesId(),
                String.format("%.2f", spec.launch(charge)), String.format("%.2f", charge));
        body.spinLaunched(move);
        next(Phase.SPIN);
    }

    /**
     * Moves the body along its travel; a wall throws it back off the wall's face (the blocked axis turned round) with a
     * clang, keeping {@code bounce} of its speed.
     */
    private void travel(ServerLevel level) {
        double fall = body.getDeltaMovement().y;
        body.setDeltaMovement(0, fall, 0);
        if (velocity.lengthSqr() < 1.0E-6) return;
        Vec3 before = body.position();
        body.move(MoverType.SELF, velocity);
        Vec3 moved = body.position().subtract(before);
        if (body.horizontalCollision) {
            boolean xBlocked = Math.abs(moved.x) < Math.abs(velocity.x) * .5, zBlocked = Math.abs(moved.z) < Math.abs(velocity.z) * .5;
            if (!xBlocked && !zBlocked) xBlocked = zBlocked = true;
            velocity = new Vec3(xBlocked ? -velocity.x : velocity.x, 0, zBlocked ? -velocity.z : velocity.z).scale(spec.bounce());
            yaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.NEUTRAL, 1.2F, .55F);
            level.sendParticles(ParticleTypes.CRIT, body.getX(), body.getY() + .6, body.getZ(), 12, .6, .3, .6, .3);
            body.spinBounced();
        }
    }

    /** Every body the spinning shell runs into (not its rider, an ally or a passer-by the AI is not after) is struck. */
    private void strike(ServerLevel level) {
        float speed = speed();
        if (speed < 1.0E-3) return;
        AABB reach = body.getBoundingBox().inflate(.15, 0, .15).expandTowards(velocity);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, reach, e -> e.isAlive() && e != body && e != rider
                && !e.isSpectator() && body.canAttack(e) && !body.isAllyOf(e) && (rider != null || e == prey || e instanceof net.minecraft.world.entity.monster.Enemy))) {
            int last = struck.getOrDefault(victim.getId(), Integer.MIN_VALUE / 2);
            if (spun - last < spec.hitCooldown()) continue;
            struck.put(victim.getId(), spun);
            float fast = Math.clamp(speed / spec.top(), 0, 1);
            float share = SpinAttacks.Spec.share(spec.power(), launched) * (.35F + .65F * fast);
            Vec3 along = velocity.normalize();
            float knock = SpinAttacks.Spec.share(spec.knockback(), launched) * (.4F + .6F * fast);
            float toss = SpinAttacks.Spec.share(spec.toss(), launched) * (.4F + .6F * fast);
            boolean landed = body.spinStrike(victim, move, share, along.scale(knock).add(0, toss, 0));
            if (DigimonEntity.COMBAT_TRACE) Constants.LOG.info("[spin] {} strikes {} at {} for {}{}", body.getSpeciesId(), victim.getType().toShortString(),
                    String.format("%.2f", speed), String.format("%.2f", share), landed ? "" : " (no damage)");
            // the shell glances off the body it struck and spins on, slower
            Vec3 off = victim.position().subtract(body.position()).multiply(1, 0, 1);
            Vec3 normal = off.lengthSqr() > 1.0E-6 ? off.normalize() : along;
            Vec3 into = normal.scale(velocity.dot(normal));
            velocity = velocity.subtract(into.scale(1.6)).scale(spec.rebound());
            // the AI's spin has done what it set off to do: it rolls on where the blow sends it instead of turning back
            // into its prey; and the shell never ends up inside the body it struck
            if (rider == null && victim == prey) prey = null;
            separate(level);
            level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + victim.getBbHeight() * .5, victim.getZ(), 16, .4, .4, .4, .4);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.NEUTRAL, .9F, .7F + .3F * fast);
        }
    }

    /**
     * The shell steps out of any body it has ended up inside (a spin's travel goes through bodies, which only push each
     * other apart slowly): straight away from that body's middle, as far as their boxes overlap, blocks permitting.
     */
    private void separate(ServerLevel level) {
        AABB box = body.getBoundingBox();
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != body && e != rider && e.isAlive() && !e.isSpectator()
                && e.getVehicle() != body)) {
            Vec3 away = body.position().subtract(other.position()).multiply(1, 0, 1);
            double apart = away.length();
            Vec3 normal = apart > 1.0E-3 ? away.scale(1 / apart) : Vec3.directionFromRotation(0, yaw + 180);
            double need = (body.getBbWidth() + other.getBbWidth()) * .5 + .05 - apart;
            if (need > 0) body.move(MoverType.SELF, normal.scale(Math.min(need, .65 * body.getBbWidth())));
        }
    }

    /** The AI's heading: where its prey will be when the spin reaches it, led by its motion. */
    private float aimAtPrey() {
        Vec3 to = prey.position().subtract(body.position());
        double pace = Math.max(.3, phase == Phase.SPIN ? speed() : spec.launch(charge));
        double eta = Math.min(30, to.horizontalDistance() / pace);
        Vec3 lead = prey.getDeltaMovement().multiply(1, 0, 1).scale(eta);
        // at most half the way to it (and 4 blocks): prey charging in at close range is aimed at, not past the spinner
        double most = Math.min(4, to.horizontalDistance() * .5);
        if (lead.length() > most) lead = lead.normalize().scale(most);
        return AttackGeometry.yaw(body.position(), prey.position().add(lead));
    }

    /**
     * Fewest ticks a winding down shell takes to settle, and the most past the wind-down's own length (the body still in
     * as the emergence starts); a shell turning slowly may take up to twice the wind-down rather than speed up or rock.
     */
    public static final int SETTLE_LEAST = 6, SETTLE_OVER = 3;

    /**
     * How a winding down shell settles, planned once as the wind-down starts from its turn {@code angle} and its rate
     * (degrees, degrees a tick): it stops on a whole number of turns (its aperture ahead again, where the body comes out)
     * over some ticks near {@code nominal} (the wind-down's length; at most {@link #SETTLE_OVER} more, the body still in
     * as the emergence starts), its rate falling from the one it had to nothing with no jump, no reversal and at most a
     * few per cent of speed-up ({@link #settleShare}). Of the whole turns and lengths that allow it, the one nearest a
     * steady slowing over the nominal length; a shell turning too slowly for any takes longer (up to twice the nominal
     * length), and one that had all but stopped eases onto the nearest whole turn, back or on.
     * @return the angle it stops at and the ticks it takes
     */
    public static float[] settle(float angle, float rate, float nominal) {
        float left = (float) (Math.ceil(angle / 360) * 360 - angle);
        float bestTo = Float.NaN, bestTicks = nominal;
        for (int[] lengths : new int[][]{{SETTLE_LEAST, (int) nominal + SETTLE_OVER}, {(int) nominal + SETTLE_OVER + 1, (int) (2 * nominal)}}) {
            double best = Double.MAX_VALUE;
            for (int length = lengths[0]; length <= lengths[1]; length++) {
                float travel = Math.max(0, rate) * length;
                for (int k = 0; k < 8; k++) {
                    float way = left + 360 * k;
                    if (way > travel * .75F) break;
                    if (way < travel / 3) continue;
                    double cost = Math.abs(way - travel * .5) / travel + .04 * Math.abs(length - nominal);
                    if (cost < best) { best = cost; bestTo = angle + way; bestTicks = length; }
                }
            }
            if (!Float.isNaN(bestTo)) break;
        }
        if (Float.isNaN(bestTo)) {
            float back = left > 180 ? left - 360 : left;
            bestTo = angle + back;
            bestTicks = Math.max(SETTLE_LEAST, nominal * .75F);
        }
        return new float[]{bestTo, bestTicks};
    }

    /**
     * Share of a settle's turn made after share {@code u} of its time, setting out at {@code rateShare} (its rate times
     * its length over its whole turn: 2 for a steady slowing): a cubic that starts at that rate and stops dead on the
     * turn.
     */
    public static float settleShare(float u, float rateShare) {
        u = Math.clamp(u, 0, 1);
        return rateShare * (u * u * u - 2 * u * u + u) + (3 - 2 * u) * u * u;
    }

    private boolean deepWater() {
        return body.isInWater() && body.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > body.getBbHeight() * .5;
    }

    /** The shell drops into place as the body is in: a thud and the ground's dust. */
    private void thump(ServerLevel level) {
        var ground = groundUnder(level, body.position());
        if (ground != null) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), body.getX(), body.getY() + .05, body.getZ(),
                14, body.getBbWidth() * .4, .05, body.getBbWidth() * .4, .08);
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.TURTLE_EGG_BREAK, SoundSource.NEUTRAL, .8F, .5F);
    }

    /** Spinning up in place: grit thrown off the rim, more of it and further as it gathers. */
    private void grind(ServerLevel level, float share) {
        var ground = groundUnder(level, body.position());
        if (ground == null) return;
        double r = body.getBbWidth() * .5;
        double a = (body.tickCount * 1.7) % (Math.PI * 2);
        for (int i = 0; i < 1 + (int) (share * 3); i++) {
            double b = a + i * 2.1;
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), body.getX() + Math.cos(b) * r, body.getY() + .08,
                    body.getZ() + Math.sin(b) * r, 1, -Math.sin(b) * .3, .08, Math.cos(b) * .3, .15 + share * .2);
        }
        if (share >= 1 && body.tickCount % 3 == 0)
            level.sendParticles(ParticleTypes.CRIT, body.getX(), body.getY() + .15, body.getZ(), 3, r, .05, r, .2);
    }

    /** The spin tears up the ground it runs over. */
    private void trail(ServerLevel level, float fast) {
        var ground = groundUnder(level, body.position());
        if (ground == null) return;
        Vec3 back = velocity.lengthSqr() > 1.0E-6 ? velocity.normalize().scale(-body.getBbWidth() * .45) : Vec3.ZERO;
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), body.getX() + back.x, body.getY() + .05, body.getZ() + back.z,
                2 + (int) (fast * 5), .5, .05, .5, .2);
        if (spun % 3 == 0) level.sendParticles(ParticleTypes.CLOUD, body.getX() + back.x * 1.4, body.getY() + .2, body.getZ() + back.z * 1.4, 1, .3, .05, .3, .01);
    }

    private static net.minecraft.world.level.block.state.BlockState groundUnder(ServerLevel level, Vec3 at) {
        var state = level.getBlockState(BlockPos.containing(at.x, at.y - .2, at.z));
        return state.isAir() ? null : state;
    }
}
