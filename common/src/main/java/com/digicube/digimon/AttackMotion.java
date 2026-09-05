package com.digicube.digimon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Blender-exported contact markers and root travel, in blocks relative to the feet.
 * @param frames immutable sub-tick samples
 * @param samplesPerTick sampling frequency of the source data
 * @param minimumRange minimum target distance for a clear strike
 * @param activeFrom first damaging tick
 * @param activeUntil last damaging tick
 * @param contactRadius radius around the horn segment
 */
public record AttackMotion(List<Frame> frames, int samplesPerTick, double minimumRange, int activeFrom, int activeUntil,
                           double contactRadius) {
    /** Validate the exported time range before registering the attack. */
    public AttackMotion {
        frames = List.copyOf(frames);
        if (samplesPerTick < 1 || frames.size() < 2 || (frames.size()-1) % samplesPerTick != 0
                || minimumRange < 0 || activeFrom < 0 || activeUntil < activeFrom
                || activeUntil * samplesPerTick >= frames.size() || contactRadius <= 0) {
            throw new IllegalArgumentException("Invalid exported attack motion");
        }
    }

    /**
     * One sample of the authored clip; forward is +Z and up is +Y.
     * @param travel cumulative forward root travel in blocks
     * @param head head pivot
     * @param mouth flame origin at neutral aim
     * @param hornBase start of the horn contact segment
     * @param hornTip end of the horn contact segment
     * @param headPitch authored downward head pitch in degrees
     * @param aimWeight blend weight for the extra target aim
     */
    public record Frame(double travel, Vec3 head, Vec3 mouth, Vec3 hornBase, Vec3 hornTip,
                        float headPitch, float aimWeight) {
        /**
         * Apply the same extra head pitch used by the client model, around its actual pivot.
         * @param pitch extra downward aim in degrees
         * @return transformed mouth offset
         */
        public Vec3 aimedMouth(float pitch) {
            Vec3 v = mouth.subtract(head);
            double a = Math.toRadians(pitch * aimWeight);
            return head.add(v.x, v.y * Math.cos(a) - v.z * Math.sin(a),
                    v.y * Math.sin(a) + v.z * Math.cos(a));
        }
    }

    /**
     * Interpolate the exported markers for the same fractional tick as the model.
     * @param tick elapsed attack time in ticks
     * @return interpolated pose markers
     */
    public Frame sample(double tick) {
        double t = Math.clamp(tick * samplesPerTick, 0.0, frames.size() - 1.0);
        int index = (int) t;
        Frame a = frames.get(index), b = frames.get(Math.min(index + 1, frames.size() - 1));
        double f = t - index;
        return new Frame(a.travel + (b.travel - a.travel) * f, a.head.lerp(b.head, f),
                a.mouth.lerp(b.mouth, f), a.hornBase.lerp(b.hornBase, f), a.hornTip.lerp(b.hornTip, f),
                (float) (a.headPitch + (b.headPitch - a.headPitch) * f),
                (float) (a.aimWeight + (b.aimWeight - a.aimWeight) * f));
    }

    /**
     * Load original motion data exported by the model harness; never client classes.
     * @param id attack identifier
     * @return validated motion profile
     */
    public static AttackMotion load(Identifier id) {
        String path = "/data/" + id.getNamespace() + "/attack_motion/" + id.getPath() + ".json";
        try (var input = AttackMotion.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing attack motion " + path);
            JsonObject json = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
            var frames = new ArrayList<Frame>();
            for (var element : json.getAsJsonArray("frames")) {
                JsonObject f = element.getAsJsonObject();
                frames.add(new Frame(f.get("travel").getAsDouble(), vector(f.getAsJsonArray("head")),
                        vector(f.getAsJsonArray("mouth")), vector(f.getAsJsonArray("horn_base")),
                        vector(f.getAsJsonArray("horn_tip")), f.get("head_pitch").getAsFloat(),
                        f.get("aim_weight").getAsFloat()));
            }
            return new AttackMotion(frames, json.get("samples_per_tick").getAsInt(), json.get("minimum_range").getAsDouble(),
                    json.get("active_from").getAsInt(), json.get("active_until").getAsInt(),
                    json.get("contact_radius").getAsDouble());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read attack motion " + path, e);
        }
    }

    private static Vec3 vector(JsonArray values) {
        if (values.size() != 3) throw new IllegalArgumentException("A motion marker needs three coordinates");
        double x = values.get(0).getAsDouble(), y = values.get(1).getAsDouble(), z = values.get(2).getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Non-finite motion marker");
        }
        return new Vec3(x, y, z);
    }
}
