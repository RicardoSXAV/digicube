package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.RushAttacks;
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

/**
 * Server: a held charge that ends in a blow ({@link RushAttacks}; Monochromon's Guardy Tusk), for a rider or the AI. The
 * server moves the body from the press until the blow is over ({@code DigimonEntity.serverOwnsBody}).
 *
 * <p>Brace: a body standing (slower than {@code standing_below}) stops within {@code brake} ticks, turns slowly onto the
 * rider's view or its prey and paws the ground; one already running lowers its head and keeps its pace, steered as it
 * rushes. After {@code build} ticks (the rider's attack tile fills over them) it rushes: it gathers to {@code pace} over
 * {@code ramp} ticks, steered after the rider's view and keys (or homing on the AI's prey, led by its motion) by at most
 * {@code turn} degrees a tick. It strikes (the authored attack of the same id, begun by the entity) when the rider lets
 * go, when an enemy is close ahead of its horn, when it runs into a wall, or after {@code max_ticks} of rushing; the blow
 * hits harder, throws further and tosses higher the further the rush got ({@link #rushed}).
 */
public final class BullRush {
    /** Flags of the synced code beside the ticks since the press: a standing brace, and the blow it ended in. */
    public static final int STANDING = 1 << 20, BLOW = 1 << 21;
    private static final int TICK_BITS = (1 << 20) - 1;
    /** Blocks between the front of the body's box and its prey's at which the blow starts (its own drive closes them). */
    private static final double STRIKE_GAP = 2.1;
    /** Degrees either side of the heading in which an enemy that close is struck. */
    private static final double STRIKE_CONE = 28;
    /** Ticks into a standing brace at which it snorts. */
    private static final int SNORT_TICK = 13;

    private final DigimonEntity body;
    private final RushAttacks.Spec spec;
    private final DigimonAttack move;
    private final Player rider;
    private LivingEntity prey;
    private final boolean standing;
    private final int build;
    private final double startPace;
    private int ticks, rushTicks;
    private float yaw;
    private double pace;
    private boolean released;

    BullRush(DigimonEntity body, RushAttacks.Spec spec, DigimonAttack move, Player rider, LivingEntity prey) {
        this.body = body;
        this.spec = spec;
        this.move = move;
        this.rider = rider;
        this.prey = prey;
        startPace = pace = body.getDeltaMovement().horizontalDistance();
        standing = pace < spec.standingBelow();
        build = rider != null ? spec.build() : spec.aiBuild();
        yaw = body.getYRot();
    }

    public DigimonAttack move() { return move; }
    public RushAttacks.Spec spec() { return spec; }
    public Player rider() { return rider; }
    /** The rider let go: it strikes on the next tick, however far it got. */
    public void release() { released = true; }
    public boolean charging() { return ticks >= build; }
    /** How far the rush got, 0 (released from the brace) to 1 (at its full pace). */
    public float rushed() { return charging() ? Math.min(1, rushTicks / (float) spec.ramp()) : 0; }

    /** The synced code: ticks since the press plus one, and {@link #STANDING} for a brace that stands. */
    public int code() { return Math.min(ticks + 1, TICK_BITS) | (standing ? STANDING : 0); }
    public static int ticks(int code) { return (code & TICK_BITS) - 1; }
    public static boolean standing(int code) { return (code & STANDING) != 0; }
    public static boolean blow(int code) { return (code & BLOW) != 0; }

    /**
     * One tick. Strikes by calling back into the body ({@code DigimonEntity.rushStrike}), which ends this rush.
     * @return false when it is called off: the rider gone, the body in water, frozen or held
     */
    boolean tick(ServerLevel level) {
        if (rider != null && body.rider() != rider || !body.isAlive() || body.isInWater() || body.isInLava()) return false;
        if (prey != null && (!prey.isAlive() || prey.level() != level)) prey = null;
        if (rider == null && prey == null) return false;
        ticks++;
        boolean grounded = body.standingOnGround();
        float wanted = rider != null ? body.riderSteer(rider) : aimAtPrey();
        Vec3 before = body.position();
        if (!charging()) {
            if (standing) {
                // stops within the brake, turns slowly onto the aim, paws the ground and snorts
                pace = Math.max(0, pace - Math.max(startPace, .05) / spec.brake());
                yaw = Mth.approachDegrees(yaw, wanted, spec.braceTurn());
                if (ticks >= spec.scrape()[0] && ticks <= spec.scrape()[1] && ticks % 2 == 0) scrape(level);
                if (ticks == SNORT_TICK) snort(level);
            } else {
                // already running: the head goes down on the run and the pace holds
                pace *= .985;
                yaw = Mth.approachDegrees(yaw, wanted, spec.turn());
            }
            if (ticks == 1) level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.HOGLIN_ANGRY, SoundSource.NEUTRAL, 1.1F, .55F);
            if (released) { strike(level, 0); return true; }
            if (ticks == build) launch(level);
        } else {
            rushTicks++;
            pace = Mth.approach((float) pace, (float) spec.pace(), (float) (spec.pace() / spec.ramp()));
            yaw = Mth.approachDegrees(yaw, wanted, spec.turn());
            trail(level);
            if (released || rushTicks >= spec.maxTicks()) { strike(level, rushed()); return true; }
            LivingEntity met = ahead(level);
            if (met != null) { prey = met; strike(level, rushed()); return true; }
        }
        body.rushFacing(yaw);
        Vec3 dir = Vec3.directionFromRotation(0, yaw);
        double fall = body.getDeltaMovement().y;
        body.setDeltaMovement(0, fall, 0);
        if (pace > 1.0E-4) body.move(MoverType.SELF, dir.scale(pace));
        // A wall in the way stops the rush: it strikes the wall, its momentum spent.
        if (charging() && grounded && pace > .2 && body.horizontalCollision && body.position().subtract(before).horizontalDistance() < pace * .4) {
            level.broadcastEntityEvent(body, DigimonAnimationEvents.SLAM);
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.NEUTRAL, 1F, .6F);
            strike(level, rushed());
            return true;
        }
        body.syncRush(code());
        return true;
    }

    private void strike(ServerLevel level, float rushed) {
        Constants.LOG.info("[rush] {} strikes after {} ticks, rushed {}{}", body.getSpeciesId(), ticks, String.format("%.2f", rushed),
                prey == null ? "" : " at " + prey.getType().toShortString());
        body.rushStrike(this, prey, rushed);
    }

    /** The AI's heading: where its prey will be when the rush meets it, led by the prey's motion. */
    private float aimAtPrey() {
        Vec3 to = prey.position().subtract(body.position());
        double eta = Math.min(20, to.horizontalDistance() / Math.max(.2, Math.max(pace, spec.pace() * .6)));
        Vec3 lead = prey.getDeltaMovement().multiply(1, 0, 1).scale(eta);
        if (lead.length() > 3) lead = lead.normalize().scale(3);
        return AttackGeometry.yaw(body.position(), prey.position().add(lead));
    }

    /**
     * An enemy close ahead of the horn: the AI's prey, or for a rider the nearest foe in its path (never a player or an
     * ally), within {@link #STRIKE_GAP} of the body's box front and {@link #STRIKE_CONE} degrees of its heading.
     */
    private LivingEntity ahead(ServerLevel level) {
        Vec3 dir = Vec3.directionFromRotation(0, yaw);
        LivingEntity best = null;
        double bestGap = Double.MAX_VALUE;
        AABB around = body.getBoundingBox().inflate(STRIKE_GAP + pace + 1, 1.5, STRIKE_GAP + pace + 1);
        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, around, e -> e.isAlive() && e != body && e != rider
                && !e.isSpectator() && (rider == null ? e == prey : !(e instanceof Player) && body.canAttack(e) && !body.isAllyOf(e)))) {
            Vec3 to = candidate.position().subtract(body.position()).multiply(1, 0, 1);
            if (to.lengthSqr() < 1.0E-6) continue;
            double angle = Math.toDegrees(Math.acos(Mth.clamp(to.normalize().dot(dir), -1, 1)));
            double gap = to.length() - candidate.getBbWidth() * .5 - body.getBbWidth() * .5;
            if (angle > STRIKE_CONE || gap > STRIKE_GAP + pace || !body.hasLineOfSight(candidate)) continue;
            if (gap < bestGap) { bestGap = gap; best = candidate; }
        }
        return best;
    }

    /** The body bursts off: a roar, and the ground thrown back from its feet. */
    private void launch(ServerLevel level) {
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.NEUTRAL, 1.3F, .62F);
        var ground = groundUnder(level, body.position());
        Vec3 back = Vec3.directionFromRotation(0, yaw).scale(-1);
        if (ground != null) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), body.getX() + back.x * .8,
                body.getY() + .1, body.getZ() + back.z * .8, 24, .7, .1, .7, .25);
        level.sendParticles(ParticleTypes.CLOUD, body.getX() + back.x, body.getY() + .2, body.getZ() + back.z, 6, .5, .1, .5, .03);
    }

    /** The pawing forefoot (its right) drags back along the ground: clods kicked back and the ground's scrape. */
    private void scrape(ServerLevel level) {
        Vec3 foot = body.position().add(new Vec3(-.62, 0, .55 - .06 * (ticks - spec.scrape()[0])).yRot(-yaw * Mth.DEG_TO_RAD));
        var ground = groundUnder(level, foot);
        if (ground == null) return;
        Vec3 back = Vec3.directionFromRotation(0, yaw).scale(-1);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), foot.x, body.getY() + .08, foot.z, 7,
                .12, .04, .12, .12);
        level.sendParticles(ParticleTypes.POOF, foot.x + back.x * .3, body.getY() + .15, foot.z + back.z * .3, 1, .05, .02, .05, .01);
        var sound = ground.getSoundType();
        level.playSound(null, foot.x, body.getY(), foot.z, sound.getHitSound(), SoundSource.NEUTRAL, sound.getVolume() * .9F, sound.getPitch() * .6F);
    }

    /** A snort: two puffs out of the nostrils. */
    private void snort(ServerLevel level) {
        Vec3 nose = body.position().add(new Vec3(0, 1.0, 1.75).yRot(-yaw * Mth.DEG_TO_RAD));
        Vec3 out = Vec3.directionFromRotation(0, yaw);
        for (int s = -1; s <= 1; s += 2) {
            Vec3 at = nose.add(new Vec3(.12 * s, 0, 0).yRot(-yaw * Mth.DEG_TO_RAD));
            level.sendParticles(ParticleTypes.POOF, at.x, at.y, at.z, 0, out.x + .25 * s, -.15, out.z, .12);
        }
        level.playSound(null, nose.x, nose.y, nose.z, SoundEvents.HOGLIN_ANGRY, SoundSource.NEUTRAL, .9F, .45F);
    }

    /** The rush tears up the ground behind its feet. */
    private void trail(ServerLevel level) {
        var ground = groundUnder(level, body.position());
        if (ground == null) return;
        Vec3 back = Vec3.directionFromRotation(0, yaw).scale(-1);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), body.getX() + back.x * .6, body.getY() + .05,
                body.getZ() + back.z * .6, 3 + (int) (pace * 6), .6, .05, .6, .15);
        if (rushTicks % 3 == 0) level.sendParticles(ParticleTypes.CLOUD, body.getX() + back.x * 1.4, body.getY() + .2,
                body.getZ() + back.z * 1.4, 1, .3, .05, .3, .01);
    }

    private static net.minecraft.world.level.block.state.BlockState groundUnder(ServerLevel level, Vec3 at) {
        var state = level.getBlockState(BlockPos.containing(at.x, at.y - .2, at.z));
        return state.isAir() ? null : state;
    }
}
