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
                             List<String> aimPath) {
        public Motion motion(boolean kick) { return kick && kickMotion != null ? kickMotion : motion; }
        public String animation(boolean kick) { return kick && kickAnimation != null ? kickAnimation : attack.id().getPath(); }
        public int duration(boolean kick) { return Math.round(motion(kick).duration()); }
        public boolean matches(String animation) { return attack.id().getPath().equals(animation) || animation != null && animation.equals(kickAnimation); }
    }

    private static final Map<Identifier, Definition> DEFINITIONS = load();
    private KineticAttacks() {}
    public static java.util.Collection<Definition> all() { return DEFINITIONS.values(); }
    public static Definition get(DigimonAttack attack) { return attack == null ? null : DEFINITIONS.get(attack.id()); }
    public static Definition get(Identifier id) { return DEFINITIONS.get(id); }
    public static boolean handles(DigimonAttack attack) {
        return attack != null && (attack.kind() == DigimonAttack.Kind.KINETIC_SHOT || attack.kind() == DigimonAttack.Kind.RETREAT_KICK);
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

    private static List<AttackBox> boxes(JsonArray rows) {
        var boxes = new ArrayList<AttackBox>();
        for (var row : rows) {
            var r = row.getAsJsonArray();
            if (r.size() != 12) throw new IllegalArgumentException("Invalid kinetic cuboid");
            boxes.add(new AttackBox(vector(r, 0), vector(r, 3), vector(r, 6), vector(r, 9)));
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
                    || alternate != null && (decision < 0 || decision >= attack.hitTick() || alternate.duration() > attack.cooldownTicks())) {
                throw new IllegalArgumentException("Invalid kinetic definition " + id);
            }
            result.put(id, new Definition(attack, base, alternate, kick, decision, projectile, speed, life, scale, lead, pitch,
                    projectile == null ? List.of() : boxes(geometry.getAsJsonObject("projectile_boxes").getAsJsonArray(name)), List.copyOf(aim)));
        }
        return Collections.unmodifiableMap(result);
    }
}
