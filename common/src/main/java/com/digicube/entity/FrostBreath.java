package com.digicube.entity;

import com.digicube.digimon.BreathAttacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A breath of frost as a train of puffs ({@link BreathAttacks}), the same on the server (what it strikes) and on every
 * client (what is drawn). Each tick the mouth sheds {@code perTick} puffs spread over the tick along the path the mouth
 * and the aim took, each leaving with the aim's speed plus the body's own motion; they fly on slowed by the air, widen
 * as the breath's profile says (narrow at the mouth, broadest partway out), sink a little (cold air is heavy), and a puff
 * that meets a block or the water keeps its speed along the surface, a little of the rest thrown back and some splashing
 * out over the surface, and spreads wider, so the frost washes along floors and walls instead of passing through them. A
 * puff already under water flies on through it (breathed by a swimmer, it meets only blocks and the surface from below).
 * A swept aim bends the train like water from a hose; the oldest puffs fade out at the end of their life. A liquid jet
 * ({@link BreathAttacks.Spec#liquid}) plunges into water instead of striking its surface, and under it loses its speed
 * fast ({@code under_drag}) and falls no more.
 */
public final class FrostBreath {
    /** One puff: position and velocity (blocks, a tick), age (ticks), whether it has struck a surface, and a seed for its look. */
    public static final class Puff {
        public double x, y, z, vx, vy, vz, px, py, pz;
        public float age;
        public boolean struck;
        public final int seed;
        /** Where it struck, and the surface's normal (for splashes), set on the tick it struck. */
        public Vec3 hit, normal;
        /**
         * The way its flame points (unit): the aim it was shed along with the body's motion, without the scatter, so a
         * steady breath's flame stays straight; once it has struck, the way it slides.
         */
        public float lookX, lookY, lookZ;
        /** The face of the last surface it struck (unit; zero until it strikes): its flame lies flat on it. */
        public float surfaceX, surfaceY, surfaceZ;
        /** Its age when it first struck a surface (-1 until it does): a splash is drawn from then. */
        public float struckAt = -1;
        /** It flies through water (a liquid jet's puff is drawn as a swirl of bubbles there). */
        public boolean underwater;
        /** Where it was as this tick began (where it left the mouth, on the tick it was shed): what it swept through. */
        public double sx, sy, sz;
        private boolean shed;

        Puff(int seed) { this.seed = seed; }
        public Vec3 position() { return new Vec3(x, y, z); }
        public Vec3 previous() { return new Vec3(px, py, pz); }
        public Vec3 velocity() { return new Vec3(vx, vy, vz); }
    }

    private final BreathAttacks.Spec spec;
    private final List<Puff> puffs = new ArrayList<>();
    private Vec3 lastMouth, lastDirection;
    private int count;

    public FrostBreath(BreathAttacks.Spec spec) { this.spec = spec; }

    public BreathAttacks.Spec spec() { return spec; }
    public List<Puff> puffs() { return puffs; }
    public boolean isEmpty() { return puffs.isEmpty(); }

    /** The mouth stopped breathing: the next shed starts afresh instead of joining the old path. */
    public void breakTrain() { lastMouth = lastDirection = null; }

    public void clear() { puffs.clear(); breakTrain(); }

    /**
     * Sheds this tick's puffs from {@code mouth} along {@code direction} (unit), each with {@code inherit} (the body's
     * motion) added; they are spread over the tick between last tick's mouth and aim and this one's, already moved on
     * by the share of the tick they have lived, so a fast sweep leaves a whole curve, never a row of dots.
     */
    public void emit(Vec3 mouth, Vec3 direction, Vec3 inherit, RandomSource random) {
        Vec3 fromMouth = lastMouth == null ? mouth : lastMouth, fromDirection = lastDirection == null ? direction : lastDirection;
        int n = spec.perTick();
        for (int i = 0; i < n; i++) {
            double u = (i + 1) / (double) n;
            Vec3 at = fromMouth.lerp(mouth, u);
            Vec3 dir = fromDirection.lerp(direction, u);
            if (dir.lengthSqr() < 1.0E-8) dir = direction;
            dir = dir.normalize();
            Vec3 clean = dir.scale(spec.speed()).add(inherit);
            Vec3 v = clean.add((random.nextDouble() - .5) * spec.spread() * spec.speed(), (random.nextDouble() - .5) * spec.spread() * spec.speed(),
                    (random.nextDouble() - .5) * spec.spread() * spec.speed());
            Puff puff = new Puff(count++);
            look(puff, clean.lengthSqr() > 1.0E-8 ? clean.normalize() : dir);
            double lived = 1 - u;
            puff.x = at.x + v.x * lived; puff.y = at.y + v.y * lived; puff.z = at.z + v.z * lived;
            puff.px = at.x; puff.py = at.y; puff.pz = at.z;
            puff.sx = at.x; puff.sy = at.y; puff.sz = at.z; puff.shed = true;
            puff.vx = v.x; puff.vy = v.y; puff.vz = v.z;
            puff.age = (float) lived;
            puffs.add(puff);
        }
        lastMouth = mouth;
        lastDirection = direction;
    }

    /** One tick of flight for every puff; blocks and the water's surface stop them. {@code level} may be null offline. */
    public void step(Level level) {
        for (int i = puffs.size() - 1; i >= 0; i--) {
            Puff p = puffs.get(i);
            p.hit = p.normal = null;
            p.age += 1;
            if (p.age >= spec.life()) { puffs.remove(i); continue; }
            p.px = p.x; p.py = p.y; p.pz = p.z;
            if (!p.shed) { p.sx = p.x; p.sy = p.y; p.sz = p.z; }
            p.shed = false;
            Vec3 from = p.position(), to = from.add(p.vx, p.vy, p.vz);
            boolean wet = level != null && !level.getFluidState(BlockPos.containing(from)).isEmpty();
            p.underwater = wet;
            if (level != null && to.distanceToSqr(from) > 1.0E-8) {
                BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                        wet || spec.liquid() ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY, CollisionContext.empty()));
                if (hit.getType() != HitResult.Type.MISS) {
                    Direction face = hit.getDirection();
                    Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
                    Vec3 v = p.velocity();
                    double into = v.dot(n);
                    // Along the surface it slides on; into it, a little is thrown back and some splashes out over the
                    // surface, each puff its own way, so a breath that meets a wall head-on spreads over it.
                    Vec3 along = v.subtract(n.scale(into));
                    Vec3 back = n.scale(-into * spec.bounce());
                    Vec3 across = n.cross(Math.abs(n.y) > .5 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), over = n.cross(across);
                    double way = p.seed * 2.39996;
                    Vec3 splash = across.scale(Math.cos(way)).add(over.scale(Math.sin(way))).scale(Math.abs(into) * SPLASH);
                    Vec3 out = along.scale(.8).add(back).add(splash);
                    p.x = hit.getLocation().x + n.x * .05; p.y = hit.getLocation().y + n.y * .05; p.z = hit.getLocation().z + n.z * .05;
                    p.vx = out.x; p.vy = out.y; p.vz = out.z;
                    if (!p.struck) p.struckAt = p.age;
                    p.struck = true;
                    p.hit = hit.getLocation();
                    p.normal = n;
                    Vec3 slide = out.subtract(n.scale(out.dot(n)));
                    if (slide.lengthSqr() > 1.0E-6) look(p, slide.normalize());
                    p.surfaceX = (float) n.x; p.surfaceY = (float) n.y; p.surfaceZ = (float) n.z;
                    continue;
                }
            }
            p.x = to.x; p.y = to.y; p.z = to.z;
            if (wet && spec.liquid()) {
                // water through water: the jet's own pressure spends itself in a few blocks, and nothing falls
                p.vx *= spec.underDrag(); p.vy *= spec.underDrag(); p.vz *= spec.underDrag();
                continue;
            }
            p.vx *= spec.drag(); p.vy = p.vy * spec.drag() + spec.rise(); p.vz *= spec.drag();
        }
    }

    /** A puff's radius now: the breath's profile at its age, wider once it has struck a surface. */
    public float radius(Puff p) {
        return radius(p, 0);
    }

    /** A puff's radius {@code partial} of a tick on from now (for drawing between ticks). */
    public float radius(Puff p, float partial) {
        return spec.radiusAt(p.age + partial) * (p.struck ? 1.35F : 1);
    }

    /** Share of the speed a puff drives into a surface that splashes out over it. */
    private static final double SPLASH = .25;

    private static void look(Puff p, Vec3 way) {
        p.lookX = (float) way.x; p.lookY = (float) way.y; p.lookZ = (float) way.z;
    }

    /** Whether any puff's sphere meets {@code box}. */
    public boolean touches(AABB box) {
        for (Puff p : puffs) if (touches(p, box)) return true;
        return false;
    }

    /**
     * Whether one puff touches {@code box}: where it is now, or for a liquid jet anywhere along what it swept this tick
     * (a fast jet's newest puffs are already well past the mouth when it strikes, and would pass through a body pressed
     * against it).
     */
    public boolean touches(Puff p, AABB box) {
        double r = radius(p);
        if (!spec.liquid()) return near(box, p.x, p.y, p.z, r);
        double lx = p.x - p.sx, ly = p.y - p.sy, lz = p.z - p.sz;
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(lx * lx + ly * ly + lz * lz) / Math.max(.15, r)));
        for (int i = 0; i <= steps; i++) {
            double u = i / (double) steps;
            if (near(box, p.sx + lx * u, p.sy + ly * u, p.sz + lz * u, r)) return true;
        }
        return false;
    }

    private static boolean near(AABB box, double x, double y, double z, double r) {
        double dx = Math.max(0, Math.max(box.minX - x, x - box.maxX));
        double dy = Math.max(0, Math.max(box.minY - y, y - box.maxY));
        double dz = Math.max(0, Math.max(box.minZ - z, z - box.maxZ));
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    /** The box around every puff, or null when there are none. */
    public AABB bounds() {
        if (puffs.isEmpty()) return null;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Puff p : puffs) {
            double r = radius(p);
            minX = Math.min(minX, p.x - r); minY = Math.min(minY, p.y - r); minZ = Math.min(minZ, p.z - r);
            maxX = Math.max(maxX, p.x + r); maxY = Math.max(maxY, p.y + r); maxZ = Math.max(maxZ, p.z + r);
            if (spec.liquid()) {
                // and what it swept this tick (touches(Puff, AABB)), the stretch from the mouth included
                minX = Math.min(minX, p.sx - r); minY = Math.min(minY, p.sy - r); minZ = Math.min(minZ, p.sz - r);
                maxX = Math.max(maxX, p.sx + r); maxY = Math.max(maxY, p.sy + r); maxZ = Math.max(maxZ, p.sz + r);
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** How far over a surface the frost of a puff that strikes it spreads, in shares of the puff's radius. */
    private static final double SPREAD = 1.6;

    /**
     * The blocks the puffs are in or just struck, for what frost does to the world (still water to ice, fire out): the
     * block each puff flies through (a fire it crosses), and where one strikes, the blocks behind and in front of the
     * surface over as far as its frost spreads.
     */
    public java.util.Collection<BlockPos> touchedBlocks() {
        var out = new java.util.LinkedHashSet<BlockPos>();
        for (Puff p : puffs) {
            out.add(BlockPos.containing(p.x, p.y, p.z));
            if (p.hit == null) continue;
            Vec3 n = p.normal;
            Vec3 across = n.cross(Math.abs(n.y) > .5 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), along = n.cross(across);
            double r = radius(p) * SPREAD;
            for (Vec3 d : new Vec3[]{Vec3.ZERO, across.scale(r), across.scale(-r), along.scale(r), along.scale(-r)}) {
                Vec3 at = p.hit.add(d);
                // the block it struck is behind the surface; the one in front of it holds a fire or a water surface
                out.add(BlockPos.containing(at.subtract(n.scale(.1))));
                out.add(BlockPos.containing(at.add(n.scale(.1))));
            }
        }
        return out;
    }

    /** Degrees a tick toward {@code target} from {@code current}, both yaw-like angles. */
    public static float approach(float current, float target, float rate) { return Mth.approachDegrees(current, target, rate); }
}
