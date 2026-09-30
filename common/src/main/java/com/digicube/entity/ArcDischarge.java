package com.digicube.entity;

import com.digicube.digimon.AuthoredAttacks;
import com.digicube.digimon.DigimonAttack;
import com.google.gson.JsonObject;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An electric discharge ({@code arc} on an authored burst: Betamon's Electric Shock). At the move's hit tick a bolt leaps
 * from the caster's emitter (the motion's mouth marker, the tip of the fin) to its target, if that target is within
 * {@code reach} of the emitter, no more than {@code cone} degrees off the caster's facing and in sight of it; failing
 * that, to the foe nearest the aim within the same bounds; failing that it earths itself on the ground ahead. From the
 * struck body it jumps on to {@code chain} further foes, each the nearest within {@code chain_reach} blocks of the last and
 * in sight of it, at {@code chain_power} of the damage per jump. A caster standing in water runs the discharge through
 * the water to every foe in it within {@code water_reach}, and so does a struck body in water, at {@code water_power}.
 * Foes within {@code burst} blocks of the caster's middle are struck by the discharge from its body. Every body is struck
 * once a cast; allies, the caster's tamer and bodies that are not foes (a passing cow, a player who never fought it)
 * never are. A foe is the caster's target, a monster, a Digimon fighting the caster, its tamer or its side, or anything
 * fighting the caster.
 *
 * <p>Clients draw the bolts ({@code ArcRenderer}) from the strike synced on the caster as text ({@link #encode}): each
 * link runs from a body (or the caster's emitter) to a body (or a point), with its kind.
 */
public final class ArcDischarge {
    public enum Kind { MAIN, CHAIN, WATER, BURST, EARTH }

    /** The {@code arc} block of an authored attack. */
    public record Spec(double reach, double cone, double burst, int chain, double chainReach, double chainPower,
                       double waterReach, double waterPower, int life) {
        public Spec {
            if (!(reach > 0) || !(cone > 0 && cone <= 180) || burst < 0 || chain < 0 || chain > 8 || chainReach < 0
                    || !(chainPower > 0 && chainPower <= 1) || waterReach < 0 || !(waterPower > 0 && waterPower <= 1) || life < 1)
                throw new IllegalArgumentException("Invalid arc");
        }

        public static Spec load(JsonObject json) {
            return new Spec(GsonHelper.getAsDouble(json, "reach"), GsonHelper.getAsDouble(json, "cone", 70),
                    GsonHelper.getAsDouble(json, "burst", 0), GsonHelper.getAsInt(json, "chain", 0),
                    GsonHelper.getAsDouble(json, "chain_reach", 0), GsonHelper.getAsDouble(json, "chain_power", .6),
                    GsonHelper.getAsDouble(json, "water_reach", 0), GsonHelper.getAsDouble(json, "water_power", .7),
                    GsonHelper.getAsInt(json, "life", 6));
        }
    }

    /** One bolt: from an entity id (or the caster's emitter when {@code from} is the caster) to an entity id, or to {@code point} when {@code to} is -1. */
    public record Link(Kind kind, int from, int to, Vec3 point) {}

    /** A strike as the clients see it: when it went off (the caster's tick count), its random seed and its bolts. */
    public record Strike(int tick, int seed, List<Link> links) {}

    private ArcDischarge() {}

    /** Where the bolt leaves from at {@code tick}: the motion's mouth marker (the tip of the fin), in the world. */
    public static Vec3 emitter(DigimonEntity caster, DigimonAttack attack, AuthoredAttacks.Definition d, Vec3 feet, float yaw, double tick) {
        return AttackGeometry.world(feet, d.motion(caster.isInWater()).sample(tick).mouth(), yaw);
    }

    /** The point of a body a bolt strikes: its middle, a little above for a tall one. */
    public static Vec3 chest(Entity e) {
        AABB box = e.getBoundingBox();
        return new Vec3(box.getCenter().x, box.minY + Math.min(box.getYsize() * .55, 1.4), box.getCenter().z);
    }

    private static double reachTo(Vec3 from, LivingEntity target) {
        AABB box = target.getBoundingBox();
        return from.distanceTo(new Vec3(Mth.clamp(from.x, box.minX, box.maxX), Mth.clamp(from.y, box.minY, box.maxY), Mth.clamp(from.z, box.minZ, box.maxZ)));
    }

    private static boolean sight(DigimonEntity caster, Vec3 from, Vec3 to) {
        return caster.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster)).getType() == HitResult.Type.MISS;
    }

    private static float offAim(Vec3 from, Vec3 to, float yaw) {
        return Math.abs(Mth.wrapDegrees(AttackGeometry.yaw(from, to) - yaw));
    }

    /** A body the discharge may strike: alive, not the caster, not an ally, and a foe ({@code aimed}, the move's own target, always is). */
    public static boolean foe(DigimonEntity caster, LivingEntity e, LivingEntity aimed) {
        if (e == caster || !e.isAlive() || caster.isAllyOf(e) || !caster.canStrike(e) || e.isSpectator()) return false;
        if (e == aimed || e == caster.getTarget()) return true;
        if (e instanceof Enemy) return true;
        LivingEntity owner = caster.getOwner();
        if (e instanceof net.minecraft.world.entity.Mob mob && (mob.getTarget() == caster || owner != null && mob.getTarget() == owner)) return true;
        if (e instanceof DigimonEntity other && caster.battleSide() != 0 && other.battleSide() != 0 && other.battleSide() != caster.battleSide()) return true;
        if (e.getLastHurtByMob() == caster || caster.getLastHurtByMob() == e) return true;
        return e instanceof Player player && owner == null && caster.getTarget() == player;
    }

    /**
     * The AI's question before it charges: from {@code feet}, would the bolt reach {@code target} (within reach, a little
     * short of it so a step away during the charge does not break it, in sight), or the water carry it there?
     */
    public static boolean canReach(DigimonEntity caster, DigimonAttack attack, AuthoredAttacks.Definition d, Vec3 feet, LivingEntity target) {
        Spec spec = d.arc();
        float yaw = AttackGeometry.yaw(feet, target.position());
        Vec3 from = emitter(caster, attack, d, feet, yaw, attack.hitTick());
        if (spec.waterReach() > 0 && caster.isInWater() && target.isInWater() && feet.distanceTo(target.position()) <= spec.waterReach()) return true;
        if (spec.burst() > 0 && feet.add(0, caster.getBbHeight() * .5, 0).distanceTo(chest(target)) <= spec.burst() + target.getBbWidth() * .5) return true;
        return reachTo(from, target) <= spec.reach() - AIM_MARGIN && sight(caster, from, chest(target));
    }

    /** Blocks short of the reach the AI starts a charge from: a target stepping away through the charge is still struck. */
    public static final double AIM_MARGIN = 1.0;

    /** The strike: bolts, damage and the clients' copy. {@code aimed} is the attack's target, or null. */
    public static Strike strike(ServerLevel level, DigimonEntity caster, DigimonAttack attack, AuthoredAttacks.Definition d, LivingEntity aimed, int tick) {
        Spec spec = d.arc();
        float yaw = caster.getYRot();
        Vec3 feet = caster.position(), from = emitter(caster, attack, d, feet, yaw, tick);
        List<Link> links = new ArrayList<>();
        Set<LivingEntity> struck = new HashSet<>();
        List<LivingEntity> foes = level.getEntitiesOfClass(LivingEntity.class, caster.getBoundingBox().inflate(Math.max(spec.reach(), spec.waterReach()) + spec.chainReach() * spec.chain() + 2),
                e -> foe(caster, e, aimed));
        LivingEntity primary = null;
        if (aimed != null && foe(caster, aimed, aimed) && reachable(caster, spec, from, yaw, aimed)) primary = aimed;
        if (primary == null) {
            double best = Double.MAX_VALUE;
            for (var e : foes) if (reachable(caster, spec, from, yaw, e)) {
                double score = offAim(from, chest(e), yaw) * .08 + from.distanceTo(chest(e));
                if (score < best) { best = score; primary = e; }
            }
        }
        if (primary != null) {
            if (hit(level, caster, attack, primary, from, 1)) { struck.add(primary); links.add(new Link(Kind.MAIN, caster.getId(), primary.getId(), chest(primary))); }
            else primary = null;
        }
        if (primary == null) {
            Vec3 earth = earth(caster, spec, from, yaw);
            links.add(new Link(Kind.EARTH, caster.getId(), -1, earth));
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, true, earth.x, earth.y + .05, earth.z, 16, .2, .05, .2, .35);
            level.sendParticles(ParticleTypes.SMOKE, true, true, earth.x, earth.y + .05, earth.z, 4, .12, .02, .12, .02);
        }
        // the discharge from the body itself: foes pressed against the caster
        if (spec.burst() > 0) {
            Vec3 middle = feet.add(0, caster.getBbHeight() * .5, 0);
            for (var e : foes) if (!struck.contains(e) && middle.distanceTo(chest(e)) <= spec.burst() + e.getBbWidth() * .5
                    && sight(caster, middle, chest(e)) && hit(level, caster, attack, e, middle, 1)) {
                struck.add(e); links.add(new Link(Kind.BURST, caster.getId(), e.getId(), chest(e)));
            }
        }
        // on from the struck body to the next foe, and the next
        LivingEntity last = primary;
        float power = 1;
        for (int jump = 0; last != null && jump < spec.chain(); jump++) {
            LivingEntity next = null;
            double best = spec.chainReach();
            for (var e : foes) {
                if (struck.contains(e)) continue;
                double dist = chest(last).distanceTo(chest(e));
                if (dist <= best && sight(caster, chest(last), chest(e))) { best = dist; next = e; }
            }
            if (next == null) break;
            power *= (float) spec.chainPower();
            if (!hit(level, caster, attack, next, chest(last), power)) break;
            struck.add(next); links.add(new Link(Kind.CHAIN, last.getId(), next.getId(), chest(next)));
            last = next;
        }
        // through the water: from a caster standing in it, and from a struck body in it
        if (spec.waterReach() > 0) {
            List<Entity> sources = new ArrayList<>();
            if (caster.isInWater()) sources.add(caster);
            if (primary != null && primary.isInWater() && !caster.isInWater()) sources.add(primary);
            for (Entity source : sources) {
                for (var e : foes) if (!struck.contains(e) && e.isInWater() && source.position().distanceTo(e.position()) <= spec.waterReach()
                        && hit(level, caster, attack, e, source.position(), (float) spec.waterPower())) {
                    struck.add(e); links.add(new Link(Kind.WATER, source.getId(), e.getId(), chest(e)));
                }
            }
        }
        d.cue(level, "release", from);
        if (primary == null) d.cue(level, "struck", links.getFirst().point());
        for (var e : struck) {
            Vec3 c = chest(e);
            d.cue(level, "struck", c);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, true, c.x, c.y, c.z, 14, e.getBbWidth() * .35, e.getBbHeight() * .3, e.getBbWidth() * .35, .45);
            level.sendParticles(ParticleTypes.CRIT, true, true, c.x, c.y, c.z, 6, .2, .2, .2, .3);
            if (e.isInWater()) level.sendParticles(ParticleTypes.BUBBLE, true, true, c.x, c.y, c.z, 10, .3, .3, .3, .1);
        }
        return new Strike(caster.tickCount, level.getRandom().nextInt(1 << 20), List.copyOf(links));
    }

    private static boolean reachable(DigimonEntity caster, Spec spec, Vec3 from, float yaw, LivingEntity e) {
        Vec3 c = chest(e);
        return reachTo(from, e) <= spec.reach() && offAim(from, c, yaw) <= spec.cone() && sight(caster, from, c);
    }

    private static boolean hit(ServerLevel level, DigimonEntity caster, DigimonAttack attack, LivingEntity victim, Vec3 from, float scale) {
        return caster.hitWithAttack(level, attack, victim, from, scale);
    }

    /** Where a bolt with nothing to strike comes down: ahead along the facing, on the first block it meets, or the floor under its reach. */
    private static Vec3 earth(DigimonEntity caster, Spec spec, Vec3 from, float yaw) {
        Vec3 ahead = new Vec3(0, 0, 1).yRot(-yaw * Mth.DEG_TO_RAD);
        Vec3 end = caster.position().add(ahead.scale(Math.min(spec.reach(), 4.5)));
        var hit = caster.level().clip(new ClipContext(from, end.add(0, -.2, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        if (hit.getType() != HitResult.Type.MISS) return hit.getLocation();
        var floor = caster.level().clip(new ClipContext(end.add(0, 1, 0), end.add(0, -4, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, caster));
        return floor.getType() != HitResult.Type.MISS ? floor.getLocation() : end;
    }

    /**
     * Client, every tick of a body with a discharging move: through the charge its fin crackles, sparks leaping off the
     * tip more and more often as the charge grows; while its bolts live, the bodies they struck spit sparks.
     */
    public static void clientTick(DigimonEntity e) {
        var level = e.level();
        var random = e.getRandom();
        DigimonAttack animating = e.getAnimatingAttack();
        var d = animating == null ? null : AuthoredAttacks.get(animating);
        if (d != null && d.discharges() && e.attackAnimationState.isStarted()) {
            float t = e.attackAnimationState.getTimeInMillis(e.tickCount) / 50F;
            if (t < animating.hitTick()) {
                float share = t / animating.hitTick();
                Vec3 tip = emitter(e, animating, d, e.position(), e.getAttackYaw(1), t);
                int sparks = share < .25F ? 0 : random.nextFloat() < share ? share > .7F ? 3 : 2 : 1;
                for (int i = 0; i < sparks; i++) {
                    double h = random.nextDouble() * .5;
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, tip.x + (random.nextDouble() - .5) * .25, tip.y - h, tip.z + (random.nextDouble() - .5) * .25,
                            (random.nextDouble() - .5) * .2, random.nextDouble() * .15, (random.nextDouble() - .5) * .2);
                }
            }
        }
        // Any other move in progress (the headbutt) has no bolt: the strike to draw belongs to the species' discharge.
        if (d != null && !d.discharges()) d = null;
        if (d == null) for (var attack : e.getSpecies().map(com.digicube.digimon.DigimonSpecies::attacks).orElse(List.of())) {
            var a = AuthoredAttacks.get(attack);
            if (a != null && a.discharges()) { d = a; break; }
        }
        if (d == null) return;
        var strike = e.clientArc(d.arc().life());
        if (strike == null || e.clientArcAge(0) > d.arc().life()) return;
        for (var link : strike.links()) {
            Entity struck = link.to() < 0 ? null : level.getEntity(link.to());
            Vec3 c = struck != null ? chest(struck) : link.point();
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, c.x + (random.nextDouble() - .5) * .5, c.y + (random.nextDouble() - .5) * .5,
                    c.z + (random.nextDouble() - .5) * .5, (random.nextDouble() - .5) * .3, random.nextDouble() * .2, (random.nextDouble() - .5) * .3);
        }
    }

    /** The strike as text for the caster's synced data: {@code tick;seed;kind,from,to,x,y,z|...}. */
    public static String encode(Strike strike) {
        StringBuilder out = new StringBuilder().append(strike.tick()).append(';').append(strike.seed()).append(';');
        for (int i = 0; i < strike.links().size(); i++) {
            Link l = strike.links().get(i);
            if (i > 0) out.append('|');
            out.append(l.kind().ordinal()).append(',').append(l.from()).append(',').append(l.to()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f", l.point().x, l.point().y, l.point().z));
        }
        return out.toString();
    }

    /** The strike back from its text; null for an empty or malformed one. */
    public static Strike decode(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            String[] parts = text.split(";", -1);
            int tick = Integer.parseInt(parts[0]), seed = Integer.parseInt(parts[1]);
            List<Link> links = new ArrayList<>();
            if (parts.length > 2 && !parts[2].isEmpty()) for (String row : parts[2].split("\\|")) {
                String[] f = row.split(",");
                links.add(new Link(Kind.values()[Integer.parseInt(f[0])], Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                        new Vec3(Double.parseDouble(f[3]), Double.parseDouble(f[4]), Double.parseDouble(f[5]))));
            }
            return new Strike(tick, seed, List.copyOf(links));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
