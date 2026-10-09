package com.digicube.digimon;

import com.digicube.Constants;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Which moves an unridden flyer casts on the wing ({@code wing} on an entry of {@code kinetic_attacks.json} or
 * {@code authored_attacks.json}): {@code "only"} is cast only off the ground (Tentomon's Petit Thunder draws its static
 * from the beating wings), {@code true} on the ground and on the wing alike (Twice Arm). Without the key a move stays on
 * the ground. A flying mount under its rider keeps its own rule ({@code DigimonEntity.wingCast}).
 */
public final class WingCasts {
    public enum Mode { GROUND, BOTH, ONLY }

    private static final Map<Identifier, Mode> MODES = load();

    private WingCasts() {}

    public static Mode mode(DigimonAttack attack) {
        return attack == null ? Mode.GROUND : MODES.getOrDefault(attack.id(), Mode.GROUND);
    }

    /** The move is cast only on the wing. */
    public static boolean only(DigimonAttack attack) { return mode(attack) == Mode.ONLY; }

    /** The move may be cast on the wing (only there, or there and on the ground). */
    public static boolean allowed(DigimonAttack attack) { return mode(attack) != Mode.GROUND; }

    private static Map<Identifier, Mode> load() {
        var out = new HashMap<Identifier, Mode>();
        for (String file : new String[]{"kinetic_attacks.json", "authored_attacks.json"}) {
            for (var entry : read("/data/digicube/" + file).entrySet()) {
                var c = entry.getValue().getAsJsonObject();
                if (!c.has("wing")) continue;
                var v = c.get("wing").getAsJsonPrimitive();
                Mode mode;
                if (v.isBoolean()) mode = v.getAsBoolean() ? Mode.BOTH : Mode.GROUND;
                else if (v.getAsString().equals("only")) mode = Mode.ONLY;
                else throw new IllegalArgumentException("A move's wing is true, false or \"only\": " + entry.getKey());
                out.put(Constants.id(entry.getKey()), mode);
            }
        }
        return Map.copyOf(out);
    }

    private static JsonObject read(String path) {
        try (var input = WingCasts.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path);
            return GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
