package com.digicube.digimon;

import com.digicube.Constants;
import com.digicube.entity.AttackBox;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Data for aimed solid projectiles and collision-safe retreat strikes. */
public final class KineticAttacks {
    public record Frame(Vec3 offset, float yaw, Vec3 pivot, Vec3 muzzle, Vec3 direction, List<AttackBox> hooves) {}

    public record Motion(int samplesPerTick, List<Frame> frames) {
        public Motion {
            frames = List.copyOf(frames);
            if (samplesPerTick < 1 || frames.size() < 2 || (frames.size() - 1) % samplesPerTick != 0) {
                throw new IllegalArgumentException("Invalid kinetic motion clock");
            }
        }

        public float duration() { return (float) (frames.size() - 1) / samplesPerTick; }

        public Frame sample(double tick) {
            double time = Math.clamp(tick * samplesPerTick, 0, frames.size() - 1);
            int index = (int) time;
            double mix = time - index;
            Frame a = frames.get(index), b = frames.get(Math.min(index + 1, frames.size() - 1));
            var boxes = new ArrayList<AttackBox>(a.hooves.size());
            for (int i = 0; i < a.hooves.size(); i++) {
                AttackBox x = a.hooves.get(i), y = b.hooves.get(i);
                boxes.add(new AttackBox(x.center().lerp(y.center(), mix), x.x().lerp(y.x(), mix),
                        x.y().lerp(y.y(), mix), x.z().lerp(y.z(), mix)));
            }
            return new Frame(a.offset.lerp(b.offset, mix), (float) (a.yaw + (b.yaw - a.yaw) * mix),
                    a.pivot.lerp(b.pivot, mix), a.muzzle.lerp(b.muzzle, mix),
                    a.direction.lerp(b.direction, mix).normalize(), List.copyOf(boxes));
        }
    }

    public record Definition(DigimonAttack attack, Motion motion, Motion kickMotion, String kickAnimation,
                             int decisionTick, String projectile, double projectileSpeed, int projectileLife,
                             float modelScale, double maxLead, float maxPitch, List<AttackBox> projectileBoxes,
                             List<String> aimPath, ProjectileMotion projectileMotion, int impairmentTicks, boolean emissive,
                             boolean blendAim, boolean aimAtTop, int riderDrawTick, RiderKick riderKick,
                             com.digicube.entity.ShotStyle shotStyle, int exposeTicks, float projectileScale, int burn,
                             int charges, Proximity proximity, Blast blast, Falloff falloff, Launch launch) {
        /** A shot that lands sets its victim alight for {@code burn} ticks, a Burn ({@code burn}, 0 for none). */
        public boolean burns() { return burn > 0; }
        /** The same shot weakening with its flight by {@code falloff} and throwing its victim by {@code launch} (either may be null). */
        public Definition withImpact(Falloff falloff, Launch launch) {
            return new Definition(attack, motion, kickMotion, kickAnimation, decisionTick, projectile, projectileSpeed, projectileLife,
                    modelScale, maxLead, maxPitch, projectileBoxes, aimPath, projectileMotion, impairmentTicks, emissive, blendAim, aimAtTop,
                    riderDrawTick, riderKick, shotStyle, exposeTicks, projectileScale, burn, charges, proximity, blast, falloff, launch);
        }
        public Motion motion(boolean kick) { return kick && kickMotion != null ? kickMotion : motion; }
        public String animation(boolean kick) { return kick && kickAnimation != null ? kickAnimation : attack.id().getPath(); }
        public int duration(boolean kick) { return Math.round(motion(kick).duration()); }
        public boolean matches(String animation) {
            return attack.id().getPath().equals(animation) || animation != null && (animation.equals(kickAnimation) || riderKick != null && animation.equals(riderKick.animation()));
        }
        /** Ticks the clip called {@code animation} lasts: the move, its kick, or a rider's buck. */
        public int duration(String animation) {
            if (riderKick != null && riderKick.animation().equals(animation)) return Math.round(riderKick.length());
            return duration(animation != null && animation.equals(kickAnimation));
        }
        /** The motion's own time at {@code tick} of the clip called {@code animation} (a buck runs the kick on its clock). */
        public double motionTime(String animation, double tick) {
            return riderKick != null && riderKick.animation().equals(animation) ? riderKick.kickTime(tick) : tick;
        }
    }

    /**
     * A rider's buck: the kick replayed on a faster clock, {@code clock} being [buck tick, kick tick] pairs (piecewise
     * linear, from 0 to the kick's end), with its own clip: the kick retimed to the same clock.
     */
    public record RiderKick(String animation, float[][] clock) {
        public float length() { return clock[clock.length - 1][0]; }
        public double kickTime(double tick) {
            for (int i = 0; i < clock.length - 1; i++) if (tick <= clock[i + 1][0] || i == clock.length - 2) {
                double f = Math.clamp((tick - clock[i][0]) / (clock[i + 1][0] - clock[i][0]), 0, 1);
                return clock[i][1] + (clock[i + 1][1] - clock[i][1]) * f;
            }
            return clock[clock.length - 1][1];
        }
    }

    /**
     * A shot that shocks what it passes ({@code proximity}; Kabuterimon's Mega Blaster): a body within {@code radius} blocks
     * of the ball (measured to the nearest point of its box) is struck once a shot as the ball passes it, at its nearest,
     * for {@code near} of the shot's damage at no distance falling to {@code far} at the radius; a direct hit is the whole
     * shot, and where the ball bursts, every body within the radius not yet struck takes its share too. Each strike is a
     * bolt from the ball (synced to the clients, which draw it for {@code bolt_ticks}).
     */
    public record Proximity(double radius, float near, float far, int boltTicks) {
        public Proximity {
            if (!(radius > 0 && radius <= 8 && near > 0 && near <= 1 && far >= 0 && far <= near && boltTicks > 0 && boltTicks <= 40))
                throw new IllegalArgumentException("Invalid shot proximity");
        }
        /** The share of the shot's damage a body takes at {@code distance} blocks from the ball. */
        public float share(double distance) {
            double u = Math.clamp(distance / radius, 0, 1);
            return (float) (near + (far - near) * u);
        }
    }

    /**
     * A shot that bursts where it strikes ({@code blast}; Greymon's Mega Flame): on a block or a body, every other body
     * within {@code radius} blocks of the burst (measured to the nearest point of its box, in sight of it) takes the shot's
     * damage times {@code 1 - falloff} at the radius up to the whole of it at the burst, and its {@code burn}. A shot that
     * flies its full range without striking anything fizzles out without one.
     */
    public record Blast(double radius, float falloff) {
        public Blast {
            if (!(radius > 0 && radius <= 6 && falloff >= 0 && falloff < 1)) throw new IllegalArgumentException("Invalid shot blast");
        }
        /** The share of the shot's damage a body takes at {@code distance} blocks from the burst. */
        public float share(double distance) { return (float) (1 - falloff * Math.clamp(distance / radius, 0, 1)); }
    }

    /** Animated projectile cuboids use the same phase clock as the native effect. */
    public record ProjectileMotion(int samplesPerTick, float holdTick, int impactTicks, List<List<AttackBox>> frames) {
        public float flightTick(float age) { return Math.clamp(age, 0, holdTick); }
        public List<AttackBox> sample(double age) {
            double time=Math.clamp(age,0,holdTick)*samplesPerTick;
            int i=Math.min((int)time,frames.size()-1); double w=time-i;
            var a=frames.get(i);var b=frames.get(Math.min(i+1,frames.size()-1));var result=new ArrayList<AttackBox>();
            for(int j=0;j<a.size();j++) { var x=a.get(j);var y=b.get(j);
                result.add(new AttackBox(x.center().lerp(y.center(),w),x.x().lerp(y.x(),w),x.y().lerp(y.y(),w),x.z().lerp(y.z(),w)));
            }
            return result;
        }
    }

    private static final Map<Identifier, Definition> DEFINITIONS = load();
    private KineticAttacks() {}
    public static java.util.Collection<Definition> all() { return DEFINITIONS.values(); }
    public static Definition get(DigimonAttack attack) { return attack == null ? null : DEFINITIONS.get(attack.id()); }
    public static Definition get(Identifier id) { return DEFINITIONS.get(id); }
    public static boolean handles(DigimonAttack attack) {
        return attack != null && (attack.kind() == DigimonAttack.Kind.KINETIC_SHOT || attack.kind() == DigimonAttack.Kind.RETREAT_KICK);
    }

    /** Development scenarios only: serves {@code definition} in place of its catalog entry from now on (the catalog's to restore it). */
    public static void replace(Definition definition) {
        if (!com.digicube.platform.Services.PLATFORM.isDevelopmentEnvironment()) throw new IllegalStateException("A development scenario's change");
        DEFINITIONS.put(definition.attack().id(), definition);
    }

    private static JsonObject read(String path) {
        try (var input = KineticAttacks.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path);
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Cannot read " + path, e); }
    }

    private static Vec3 vector(JsonArray a, int start) {
        double x = a.get(start).getAsDouble(), y = a.get(start + 1).getAsDouble(), z = a.get(start + 2).getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Nonfinite kinetic coordinate");
        return new Vec3(x, y, z);
    }

    private static List<AttackBox> boxes(JsonArray rows) { return boxes(rows, 1); }

    /** Cuboids scaled by {@code scale} about the shot's own origin (the muzzle), as the drawn shot is. */
    private static List<AttackBox> boxes(JsonArray rows, double scale) {
        var boxes = new ArrayList<AttackBox>();
        for (var row : rows) {
            var r = row.getAsJsonArray();
            if (r.size() != 12) throw new IllegalArgumentException("Invalid kinetic cuboid");
            boxes.add(new AttackBox(vector(r, 0).scale(scale), vector(r, 3).scale(scale), vector(r, 6).scale(scale), vector(r, 9).scale(scale)));
        }
        return List.copyOf(boxes);
    }

    private static Motion motion(JsonObject data) {
        var frames = new ArrayList<Frame>();
        int width = -1;
        for (var value : data.getAsJsonArray("frames")) {
            var r = value.getAsJsonObject();
            float yaw = r.get("yaw").getAsFloat();
            var hooves = boxes(r.getAsJsonArray("hooves"));
            if (!Float.isFinite(yaw) || width >= 0 && width != hooves.size()) throw new IllegalArgumentException("Invalid kinetic frame");
            width = hooves.size();
            frames.add(new Frame(vector(r.getAsJsonArray("offset"), 0), yaw, vector(r.getAsJsonArray("pivot"), 0),
                    vector(r.getAsJsonArray("muzzle"), 0), vector(r.getAsJsonArray("direction"), 0), hooves));
        }
        return new Motion(data.get("samples_per_tick").getAsInt(), frames);
    }

    private static RiderKick riderKick(JsonObject c, Motion kick, Identifier id) {
        if (!c.has("rider_kick")) return null;
        var data = c.getAsJsonObject("rider_kick");
        var rows = data.getAsJsonArray("clock");
        float[][] clock = new float[rows.size()][];
        for (int i = 0; i < clock.length; i++) {
            var r = rows.get(i).getAsJsonArray();
            clock[i] = new float[]{r.get(0).getAsFloat(), r.get(1).getAsFloat()};
            if (i > 0 && (clock[i][0] <= clock[i - 1][0] || clock[i][1] <= clock[i - 1][1]))
                throw new IllegalArgumentException("A rider kick clock must rise " + id);
        }
        if (kick == null || clock.length < 2 || clock[0][0] != 0 || clock[0][1] != 0 || Math.abs(clock[clock.length - 1][1] - kick.duration()) > .01)
            throw new IllegalArgumentException("A rider kick clock runs the whole kick " + id);
        return new RiderKick(data.get("animation").getAsString(), clock);
    }

    private static int charges(JsonObject c, Identifier id) {
        int charges = GsonHelper.getAsInt(c, "charges", 1);
        if (charges < 1 || charges > 5) throw new IllegalArgumentException("A shot holds 1 to 5 uses " + id);
        return charges;
    }

    private static Proximity proximity(JsonObject c) {
        if (!c.has("proximity")) return null;
        var p = GsonHelper.getAsJsonObject(c, "proximity");
        return new Proximity(GsonHelper.getAsDouble(p, "radius"), GsonHelper.getAsFloat(p, "near"), GsonHelper.getAsFloat(p, "far"),
                GsonHelper.getAsInt(p, "bolt_ticks", 6));
    }

    private static Blast blast(JsonObject c) {
        if (!c.has("blast")) return null;
        var b = GsonHelper.getAsJsonObject(c, "blast");
        return new Blast(GsonHelper.getAsDouble(b, "radius"), GsonHelper.getAsFloat(b, "falloff", .5F));
    }

    /**
     * A shot that weakens with its flight ({@code falloff}; the far form of Beast King Fist): its damage and its push (a
     * {@link Launch} too) are whole up to {@code near} blocks from the muzzle and fall in a straight line to {@code power}
     * and {@code knockback} of themselves at {@code far}, held past it. Measured from where the shot left to where it strikes.
     */
    public record Falloff(double near, double far, float power, float knockback) {
        public Falloff {
            if (!(near >= 0 && far > near && far <= 64 && power >= 0 && power <= 1 && knockback >= 0 && knockback <= 1))
                throw new IllegalArgumentException("Invalid shot falloff");
        }
        /** The share of the shot's damage that lands {@code distance} blocks from the muzzle. */
        public float power(double distance) { return 1 + (power - 1) * along(distance); }
        /** The share of the shot's push (knockback or launch) that lands {@code distance} blocks from the muzzle. */
        public float knockback(double distance) { return 1 + (knockback - 1) * along(distance); }
        private float along(double distance) { return (float) Math.clamp((distance - near) / (far - near), 0, 1); }
    }

    static Falloff falloff(JsonObject c) {
        if (!c.has("falloff")) return null;
        var f = GsonHelper.getAsJsonObject(c, "falloff");
        return new Falloff(GsonHelper.getAsDouble(f, "near"), GsonHelper.getAsDouble(f, "far"), GsonHelper.getAsFloat(f, "power"),
                GsonHelper.getAsFloat(f, "knockback", GsonHelper.getAsFloat(f, "power")));
    }

    /** Ticks a landed shot sets its victim alight for (0 to 400; 0: none). */
    private static int burn(JsonObject c, Identifier id) {
        int ticks = GsonHelper.getAsInt(c, "burn", 0);
        if (ticks < 0 || ticks > 400) throw new IllegalArgumentException("A shot burns 0 to 400 ticks " + id);
        return ticks;
    }

    private static Map<Identifier, Definition> load() {
        var config = read("/data/digicube/kinetic_attacks.json");
        var geometry = read("/data/digicube/kinetic_motion.json");
        var result = new LinkedHashMap<Identifier, Definition>();
        for (var entry : config.entrySet()) {
            String name = entry.getKey();
            var c = entry.getValue().getAsJsonObject();
            var id = Constants.id(name);
            var attack = new DigimonAttack(id, DigimonAttack.Kind.valueOf(c.get("kind").getAsString()), c.get("power").getAsFloat(),
                    c.get("cooldown").getAsInt(), c.get("duration").getAsInt(), c.get("hit_tick").getAsInt(),
                    c.get("range").getAsDouble(), false, AttackMotion.load(id), null, GsonHelper.getAsDouble(c, "knockback", 0));
            String kick = GsonHelper.getAsString(c, "kick_animation", null), projectile = GsonHelper.getAsString(c, "projectile", null);
            Motion base = motion(geometry.getAsJsonObject("motions").getAsJsonObject(name));
            Motion alternate = kick == null ? null : motion(geometry.getAsJsonObject("motions").getAsJsonObject(kick));
            var aim = new ArrayList<String>();
            if (c.has("aim_path")) c.getAsJsonArray("aim_path").forEach(n -> aim.add(n.getAsString()));
            double speed = GsonHelper.getAsDouble(c, "projectile_speed", 0);
            int life = GsonHelper.getAsInt(c, "projectile_life", 0), decision = GsonHelper.getAsInt(c, "decision_tick", 0);
            float scale = c.get("model_scale").getAsFloat(), pitch = GsonHelper.getAsFloat(c, "max_pitch", 60);
            double lead = GsonHelper.getAsDouble(c, "max_lead", 3);
            if (base.duration() != attack.durationTicks() || !Float.isFinite(scale) || scale <= 0 || !Double.isFinite(lead) || lead < 0
                    || !Float.isFinite(pitch) || pitch <= 0 || pitch >= 90
                    || projectile != null && (!Double.isFinite(speed) || speed <= 0 || life < 1)
                    || alternate != null && (decision < 0 || decision >= attack.hitTick() || alternate.duration() > attack.cooldownTicks())
                    || GsonHelper.getAsInt(c,"rider_draw_tick",0) < 0 || GsonHelper.getAsInt(c,"rider_draw_tick",0) >= attack.hitTick()) {
                throw new IllegalArgumentException("Invalid kinetic definition " + id);
            }
            // A shot drawn smaller than it was exported: the model and every cuboid it hits with shrink together.
            float shrink = GsonHelper.getAsFloat(c, "projectile_scale", 1);
            if (!(shrink > 0 && shrink <= 4)) throw new IllegalArgumentException("Invalid projectile scale " + id);
            ProjectileMotion flight=null;
            if(geometry.has("projectile_motion") && geometry.getAsJsonObject("projectile_motion").has(name)) {
                var data=geometry.getAsJsonObject("projectile_motion").getAsJsonObject(name);
                var frames=new ArrayList<List<AttackBox>>();data.getAsJsonArray("frames").forEach(row->frames.add(boxes(row.getAsJsonArray(), shrink)));
                flight=new ProjectileMotion(data.get("samples_per_tick").getAsInt(),data.get("hold_tick").getAsFloat(),
                        GsonHelper.getAsInt(c,"impact_ticks",7),List.copyOf(frames));
                if(flight.samplesPerTick()<1 || flight.holdTick()<0 || flight.holdTick()*flight.samplesPerTick()>=frames.size()
                        || frames.stream().anyMatch(row->row.size()!=frames.getFirst().size()))throw new IllegalArgumentException("Invalid projectile animation "+id);
            }
            result.put(id, new Definition(attack, base, alternate, kick, decision, projectile, speed, life, scale, lead, pitch,
                    projectile == null ? List.of() : boxes(geometry.getAsJsonObject("projectile_boxes").getAsJsonArray(name), shrink), List.copyOf(aim),
                    flight,GsonHelper.getAsInt(c,"impairment_ticks",0),GsonHelper.getAsBoolean(c,"emissive",true),
                    GsonHelper.getAsBoolean(c,"blend_aim",false),GsonHelper.getAsBoolean(c,"aim_at_top",flight!=null),
                    // A rider's shot is drawn: the clip holds on this tick (the weapon raised) for as long as the button is held.
                    GsonHelper.getAsInt(c,"rider_draw_tick",Math.max(0,attack.hitTick()-4)),
                    riderKick(c, alternate, id),
                    com.digicube.entity.ShotStyle.byId(GsonHelper.getAsString(c, "shot_style", null)),
                    // A shot that lands leaves its victim Exposed (ExposedMark) for this long.
                    GsonHelper.getAsInt(c, "expose_ticks", 0), shrink, burn(c, id),
                    // Uses held at once (AttackCharges), and the shocks a ball gives what it passes.
                    charges(c, id), proximity(c),
                    // A shot that bursts where it strikes, and how much of it the bodies about the burst take.
                    blast(c),
                    // A shot that weakens with its flight, and one that throws its victim.
                    falloff(c), Launch.parse(c)));
        }
        // Mutable only for development scenarios (replace).
        return Collections.synchronizedMap(result);
    }
}
