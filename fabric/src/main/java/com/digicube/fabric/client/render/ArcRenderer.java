package com.digicube.fabric.client.render;

import com.digicube.digimon.AuthoredAttacks;
import com.digicube.entity.ArcDischarge;
import com.digicube.entity.AttackGeometry;
import com.digicube.entity.DigimonEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;

/**
 * Draws an electric discharge's bolts ({@link ArcDischarge}) as solid glowing blocks ({@link GlowRods}, the way Howling
 * Blaster's flame is drawn): each bolt is a zigzag of square rods from where it leaves (the fin's tip, a struck body, the
 * caster's body) to the body it struck (or the ground it earthed in), a pale core with a thinner amber strand winding
 * about it and short amber forks off the main bolt, knuckled with a cube at every bend so no joint gapes. The zigzag is
 * dealt again about every two thirds of a tick, so the bolt flickers, and the rods thin away over its last two ticks.
 * Where a bolt lands a star of short rods bursts; where the main bolt leaves the fin flares. A bolt through water is
 * drawn icy white. Faces carry a shade by direction in their colour ({@link FrostBreathRenderer#shaded}).
 */
public final class ArcRenderer {
    /** Rod widths by bolt kind (blocks): the main bolt, a jump, through water, from the body, earthed. */
    private static final float[] WIDTH = {.085F, .065F, .05F, .06F, .08F};
    private static final int CORE = 0xFFFFFAD6, AMBER = 0xFFEDB224, WATER_CORE = 0xFFD2F6FF, WATER_EDGE = 0xFF7FD7F5;
    /** Blocks between the bends of a bolt, and how far a bend strays aside at most. */
    private static final float BEND = .42F, JAG = .26F;
    /** Zigzags a tick. */
    private static final float FLICKER = 1.5F;

    /** One frame's bolts, relative to the caster's drawn position. */
    public static final class State {
        public int count, seed, life;
        public float age;
        /** The bolt's colours (ARGB): its pale core, the strand and the forks; Elecmon's ivory and amber by default. */
        public int core = CORE, edge = AMBER, fork = AMBER;

        /** A bolt from {@code a} to {@code b} (relative to where the drawing is anchored), striking a body there or not. */
        public void bolt(Vec3 a, Vec3 b, boolean body) { add(ArcDischarge.Kind.MAIN, a, b, body); }
        public void reset() { clear(); }
        float[] from = new float[24], to = new float[24];
        int[] kind = new int[8];
        boolean[] struck = new boolean[8];
        final GlowRods rods = new GlowRods();

        void clear() { count = 0; }

        void add(ArcDischarge.Kind k, Vec3 a, Vec3 b, boolean body) {
            if (count == kind.length) {
                kind = Arrays.copyOf(kind, count * 2); struck = Arrays.copyOf(struck, count * 2);
                from = Arrays.copyOf(from, count * 6); to = Arrays.copyOf(to, count * 6);
            }
            from[count * 3] = (float) a.x; from[count * 3 + 1] = (float) a.y; from[count * 3 + 2] = (float) a.z;
            to[count * 3] = (float) b.x; to[count * 3 + 1] = (float) b.y; to[count * 3 + 2] = (float) b.z;
            kind[count] = k.ordinal(); struck[count] = body; count++;
        }
    }

    /**
     * Fills {@code s} from the discharge the entity let go last (none once it has faded): every bolt's ends, the struck
     * bodies followed where they are drawn this frame, relative to the caster at {@code ox, oy, oz}.
     */
    public static void extract(DigimonEntity entity, State s, double ox, double oy, double oz, float partial) {
        s.clear();
        var arc = arcOf(entity);
        if (arc == null) return;
        var strike = entity.clientArc(arc.arc().life());
        if (strike == null) return;
        s.age = entity.clientArcAge(partial);
        s.life = arc.arc().life();
        if (s.age > s.life) return;
        s.seed = strike.seed();
        Vec3 origin = new Vec3(ox, oy, oz);
        var level = entity.level();
        for (var link : strike.links()) {
            Vec3 a;
            if (link.from() == entity.getId()) {
                a = switch (link.kind()) {
                    case MAIN, EARTH -> emitter(entity, arc, partial);
                    case BURST -> entity.getPosition(partial).add(0, entity.getBbHeight() * .45, 0);
                    default -> entity.getPosition(partial).add(0, .2, 0);
                };
            } else {
                Entity e = level.getEntity(link.from());
                if (e == null) continue;
                a = chest(e, partial);
            }
            Entity target = link.to() < 0 ? null : level.getEntity(link.to());
            Vec3 b = target != null ? chest(target, partial) : link.point();
            s.add(link.kind(), a.subtract(origin), b.subtract(origin), target != null);
        }
    }

    /** The discharging move of the entity's species, or null. */
    private static AuthoredAttacks.Definition arcOf(DigimonEntity entity) {
        for (var attack : entity.getSpecies().map(com.digicube.digimon.DigimonSpecies::attacks).orElse(java.util.List.of())) {
            var d = AuthoredAttacks.get(attack);
            if (d != null && d.discharges()) return d;
        }
        return null;
    }

    /** The fin's tip this frame: the move's mouth marker where its clip is (its hit tick once the clip has ended). */
    private static Vec3 emitter(DigimonEntity entity, AuthoredAttacks.Definition d, float partial) {
        double tick = d.attack().hitTick();
        if (entity.getAnimatingAttack() == d.attack() && entity.attackAnimationState.isStarted())
            tick = Math.min(d.attack().durationTicks(), entity.attackAnimationState.getTimeInMillis(entity.tickCount + partial) / 50F);
        return AttackGeometry.world(entity.getPosition(partial), d.motion(entity.isInWater()).sample(tick).mouth(), entity.getAttackYaw(partial));
    }

    private static Vec3 chest(Entity e, float partial) {
        Vec3 p = e.getPosition(partial);
        return p.add(0, Math.min(e.getBbHeight() * .55, 1.4), 0);
    }

    public void submit(State s, PoseStack pose, SubmitNodeCollector collector) {
        if (s.count == 0 || s.age > s.life) return;
        var rods = s.rods;
        rods.clear();
        float fade = Mth.clamp((s.life - s.age) / 2F, 0, 1);
        int frame = (int) (s.age * FLICKER);
        for (int i = 0; i < s.count; i++) {
            int k = s.kind[i];
            float w = WIDTH[k] * (.35F + .65F * fade);
            long rng = GlowRods.mix(s.seed, i, frame);
            boolean water = k == ArcDischarge.Kind.WATER.ordinal();
            float ax = s.from[i * 3], ay = s.from[i * 3 + 1], az = s.from[i * 3 + 2];
            float bx = s.to[i * 3], by = s.to[i * 3 + 1], bz = s.to[i * 3 + 2];
            rng = rods.bolt(ax, ay, az, bx, by, bz, w, water ? WATER_CORE : s.core, JAG, BEND, rng, k == 0 ? 2 : k == 1 ? 1 : 0, water ? WATER_EDGE : s.fork);
            // the thinner strand winding about the core
            rng = rods.bolt(ax, ay, az, bx, by, bz, w * .55F, water ? WATER_EDGE : s.edge, JAG * 1.25F, BEND, rng, 0, 0);
            // a star of rods where it struck, and the fin's flare where the main bolt left, in its first ticks
            if (s.age < 3.2F) {
                if (s.struck[i] || k == ArcDischarge.Kind.EARTH.ordinal()) rng = rods.star(bx, by, bz, .34F * fade + .1F, w * .8F, water ? WATER_CORE : s.core, s.edge, rng, 7);
                if (k == 0 || k == ArcDischarge.Kind.EARTH.ordinal()) rng = rods.star(ax, ay, az, .22F, w * .7F, s.core, s.edge, rng, 5);
            }
        }
        rods.submit(pose, collector);
    }
}
