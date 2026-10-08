package com.digicube.digimon;

import com.digicube.Constants;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves whose casts are other attacks ({@code data/digicube/compound_attacks.json}, {@link DigimonAttack.Kind#COMPOUND}):
 * forms across families. A compound's forms are attacks of the authored, pounce and kinetic catalogs that no species
 * sheet names; each cast is the first form, in list order, whose conditions hold (on the ground or in the air, within a
 * reach) and that can strike from where the body stands. A form keeps its own variants: an authored attack's combo forms,
 * a pounce's air and run starts, a shot cast on the move.
 *
 * <p>Two gates may stand in front of the forms. A {@link Stance} is a weapon drawn for a while: the draw, the hold
 * (the forms strike only while it holds), the sheathe, and the move's cooldown after it. A {@link Gauge} fills as other
 * attacks land ({@code fill} by attack id) and unlocks the move when full; every cast spends it whole, and there is no
 * cooldown. The entity runs both ({@code DigimonEntity}), and the AI or any other controller casts through it.
 */
public final class CompoundAttacks {
    /**
     * One form of a compound.
     * @param attack   the attack cast (an authored attack's first form, a pounce or a shot)
     * @param air      true: only in the air; false: only on the ground; null: either
     * @param minReach blocks between the two bodies (box to box, level) from which the form is cast
     * @param maxReach and up to which
     * @param move     the body keeps moving under it, the upper body playing the form (a shot on the run or from a leap)
     */
    public record Form(DigimonAttack attack, Boolean air, double minReach, double maxReach, boolean move) {
        /** Whether the form may be cast in the air ({@code airborne}) or on the ground, with {@code gap} blocks between the bodies. */
        public boolean suits(boolean airborne, double gap) {
            return (air == null || air == airborne) && gap >= minReach && gap <= maxReach;
        }
    }

    /**
     * A weapon drawn for a while (ticks): its {@code draw} clip (the weapon changes hands at {@code drawSwap}), the
     * {@code hold}, its {@code sheathe} clip (back at {@code sheatheSwap}) and the move's {@code cooldown} after it. A strike
     * begun before the hold ends plays out, and the sheathe follows it. {@code drawSound} and {@code sheatheSound} play
     * at the swaps (none when null).
     */
    public record Stance(int draw, int drawSwap, int hold, int sheathe, int sheatheSwap, int cooldown, Identifier drawSound, Identifier sheatheSound) {
        /** Ticks from the draw to the end of the sheathe, with no strike holding it open. */
        public int length() { return draw + hold + sheathe; }
    }

    /** A gauge of {@code capacity} that other attacks fill as they land ({@code fill}: attack id to amount). */
    public record Gauge(float capacity, Map<Identifier, Float> fill) {
        /**
         * What a landed hit of {@code attack} pays in: its own entry, else its move's (an authored form pays as the move it
         * belongs to); 0 for none.
         */
        public float fill(DigimonAttack attack) {
            if (attack == null) return 0;
            Float own = fill.get(attack.id());
            if (own != null) return own;
            Float move = fill.get(AuthoredAttacks.move(attack).id());
            return move == null ? 0 : move;
        }
    }

    /**
     * @param attack the move as a species sheet names it
     * @param forms  its forms, tried in this order
     * @param stance its weapon stance, or null
     * @param gauge  its gauge, or null
     * @param chain  ticks before one of its strikes ends from which the next may cut in, starting from the pose the strike
     *               is in (no blend back to the gait between them)
     */
    public record Definition(DigimonAttack attack, List<Form> forms, Stance stance, Gauge gauge, int chain) {
        /** Whether {@code cast} is one of this move's forms (or an authored combo form of one). */
        public boolean owns(DigimonAttack cast) {
            if (cast == null) return false;
            DigimonAttack move = AuthoredAttacks.move(cast);
            for (Form form : forms) if (form.attack().id().equals(move.id())) return true;
            return false;
        }

        /** The form entries whose attack is {@code cast} (or the authored move it belongs to). */
        public List<Form> entries(DigimonAttack cast) {
            DigimonAttack move = AuthoredAttacks.move(cast);
            return forms.stream().filter(f -> f.attack().id().equals(move.id())).toList();
        }
    }

    /** Forms one compound may have; with a species' two moves their attacks must fit the start events' 16 indices. */
    public static final int MAX_FORMS = 6;

    private static final Map<Identifier, Definition> DEFINITIONS = load();
    /** By the species' own move list (one instance per species): records holding motion tables hash slowly. */
    private static final Map<List<DigimonAttack>, List<DigimonAttack>> CASTABLES = Collections.synchronizedMap(new IdentityHashMap<>());

    private CompoundAttacks() {}

    public static Collection<Definition> all() { return DEFINITIONS.values(); }
    public static Definition get(DigimonAttack attack) { return attack == null ? null : DEFINITIONS.get(attack.id()); }
    public static Definition get(Identifier id) { return DEFINITIONS.get(id); }
    public static boolean handles(DigimonAttack attack) { return attack != null && attack.kind() == DigimonAttack.Kind.COMPOUND && get(attack) != null; }
    /** Every compound move, for the species sheets to name. */
    public static List<DigimonAttack> attacks() { return DEFINITIONS.values().stream().map(Definition::attack).toList(); }

    /** The compound among {@code moves} that casts {@code cast} as one of its forms, or null. */
    public static Definition owner(List<DigimonAttack> moves, DigimonAttack cast) {
        if (cast == null) return null;
        for (DigimonAttack move : moves) {
            Definition d = get(move);
            if (d != null && d.owns(cast)) return d;
        }
        return null;
    }

    /**
     * Every attack a body with these sheet moves plays: the moves in sheet order, then the forms of its compounds, each
     * once. Start events and synced attack names index this list, so a form plays as any move does.
     */
    public static List<DigimonAttack> castables(List<DigimonAttack> moves) {
        if (moves.stream().noneMatch(CompoundAttacks::handles)) return moves;
        return CASTABLES.computeIfAbsent(moves, CompoundAttacks::collect);
    }

    /** As {@link #castables}, counted without keeping the list (a sheet being read). */
    public static int castableCount(List<DigimonAttack> moves) { return collect(moves).size(); }

    private static List<DigimonAttack> collect(List<DigimonAttack> moves) {
        var list = new ArrayList<>(moves);
        for (DigimonAttack move : moves) {
            Definition d = get(move);
            if (d == null) continue;
            for (Form form : d.forms()) if (!list.contains(form.attack())) list.add(form.attack());
        }
        return List.copyOf(list);
    }

    /** Whether any form of the compound {@code id} reaches beyond contact (a shot). */
    static boolean ranged(Identifier id) {
        Definition d = DEFINITIONS.get(id);
        return d != null && d.forms().stream().anyMatch(f -> f.attack().isRanged());
    }

    /** Whether {@code cast} is a form some compound casts on the move ({@code move}): the legs keep their gait or leap under it. */
    public static boolean castsOnTheMove(DigimonAttack cast) {
        if (cast == null) return false;
        for (Definition d : DEFINITIONS.values()) for (Form form : d.forms()) if (form.move() && form.attack().id().equals(cast.id())) return true;
        return false;
    }

    /** The attack a form names: an authored attack's first form, a pounce or a shot. */
    private static DigimonAttack form(String name, Identifier owner) {
        Identifier id = name.contains(":") ? Identifier.parse(name) : Constants.id(name);
        var authored = AuthoredAttacks.get(id);
        if (authored != null) {
            if (AuthoredAttacks.move(authored.attack()) != authored.attack())
                throw new IllegalArgumentException(owner + ": a form names an authored move, not one of its later forms: " + name);
            return authored.attack();
        }
        for (DigimonAttack pounce : PounceAttacks.attacks()) if (pounce.id().equals(id)) return pounce;
        var shot = KineticAttacks.get(id);
        if (shot != null && shot.attack().kind() == DigimonAttack.Kind.KINETIC_SHOT) return shot.attack();
        throw new IllegalArgumentException(owner + ": a form is an authored attack, a pounce or a shot: " + name);
    }

    /** Whether {@code id} names an attack of any catalog (for a gauge's fill). */
    private static boolean known(Identifier id) {
        return AuthoredAttacks.get(id) != null || KineticAttacks.get(id) != null
                || PounceAttacks.attacks().stream().anyMatch(a -> a.id().equals(id)) || BreathAttacks.attacks().stream().anyMatch(a -> a.id().equals(id))
                || ThrownAttacks.attacks().stream().anyMatch(a -> a.id().equals(id)) || WhipAttacks.attacks().stream().anyMatch(a -> a.id().equals(id))
                || SpinAttacks.attacks().stream().anyMatch(a -> a.id().equals(id));
    }

    private static Identifier sound(JsonObject sounds, String key) {
        return sounds == null || !sounds.has(key) ? null : Identifier.parse(GsonHelper.getAsString(sounds, key));
    }

    private static Map<Identifier, Definition> load() {
        try (var input = CompoundAttacks.class.getResourceAsStream("/data/digicube/compound_attacks.json")) {
            if (input == null) return Map.of();
            return parse(GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read compound attacks", e);
        }
    }

    /**
     * Reads a compound catalog (the bundled one, or a check's own): each entry's {@code forms} (attack, {@code air},
     * {@code min_reach}, {@code max_reach}, {@code move}), optional {@code stance} ({@code draw}, {@code draw_swap},
     * {@code hold}, {@code sheathe}, {@code sheathe_swap}, {@code cooldown}, {@code sounds}), optional {@code gauge}
     * ({@code capacity}, {@code fill}), {@code chain}, and the optional {@code power} and {@code range} the move reports.
     * @throws IllegalArgumentException on anything it cannot cast
     */
    public static Map<Identifier, Definition> parse(JsonObject data) {
        var result = new LinkedHashMap<Identifier, Definition>();
        for (var entry : data.entrySet()) {
            var id = Constants.id(entry.getKey());
            var c = entry.getValue().getAsJsonObject();
            var forms = new ArrayList<Form>();
            for (var element : GsonHelper.getAsJsonArray(c, "forms")) {
                var f = element.getAsJsonObject();
                Boolean air = f.has("air") ? GsonHelper.getAsBoolean(f, "air") : null;
                double min = GsonHelper.getAsDouble(f, "min_reach", 0), max = GsonHelper.getAsDouble(f, "max_reach", Double.MAX_VALUE);
                if (!(min >= 0 && max >= min)) throw new IllegalArgumentException(id + ": a form's reach runs from min_reach up to max_reach");
                forms.add(new Form(form(GsonHelper.getAsString(f, "attack"), id), air, min, max, GsonHelper.getAsBoolean(f, "move", false)));
            }
            if (forms.isEmpty() || forms.size() > MAX_FORMS) throw new IllegalArgumentException(id + ": a compound has 1 to " + MAX_FORMS + " forms");
            Stance stance = null;
            if (c.has("stance")) {
                var s = GsonHelper.getAsJsonObject(c, "stance");
                var sounds = s.has("sounds") ? GsonHelper.getAsJsonObject(s, "sounds") : null;
                stance = new Stance(GsonHelper.getAsInt(s, "draw"), GsonHelper.getAsInt(s, "draw_swap"), GsonHelper.getAsInt(s, "hold"),
                        GsonHelper.getAsInt(s, "sheathe"), GsonHelper.getAsInt(s, "sheathe_swap"), GsonHelper.getAsInt(s, "cooldown"),
                        sound(sounds, "draw"), sound(sounds, "sheathe"));
                if (stance.draw() < 1 || stance.drawSwap() < 0 || stance.drawSwap() > stance.draw() || stance.hold() < 1 || stance.sheathe() < 1
                        || stance.sheatheSwap() < 0 || stance.sheatheSwap() > stance.sheathe() || stance.cooldown() < 0 || stance.length() > 4000)
                    throw new IllegalArgumentException(id + ": a stance draws, holds and sheathes for at least a tick each, its swaps inside its clips");
            }
            Gauge gauge = null;
            if (c.has("gauge")) {
                var g = GsonHelper.getAsJsonObject(c, "gauge");
                var fill = new HashMap<Identifier, Float>();
                for (var f : GsonHelper.getAsJsonObject(g, "fill").entrySet()) {
                    Identifier attack = f.getKey().contains(":") ? Identifier.parse(f.getKey()) : Constants.id(f.getKey());
                    float amount = f.getValue().getAsFloat();
                    if (!known(attack) || !(amount > 0)) throw new IllegalArgumentException(id + ": a gauge fills by a known attack's landed hits: " + f.getKey());
                    fill.put(attack, amount);
                }
                gauge = new Gauge(GsonHelper.getAsFloat(g, "capacity"), Map.copyOf(fill));
                if (!(gauge.capacity() > 0) || fill.isEmpty()) throw new IllegalArgumentException(id + ": a gauge has a capacity and something that fills it");
            }
            int chain = GsonHelper.getAsInt(c, "chain", 0);
            if (chain < 0 || chain > 10) throw new IllegalArgumentException(id + ": a chain cuts in 0 to 10 ticks before a strike ends");
            float power = GsonHelper.getAsFloat(c, "power", (float) forms.stream().mapToDouble(f -> f.attack().power()).max().orElse(1));
            double range = GsonHelper.getAsDouble(c, "range", forms.stream().mapToDouble(f -> f.attack().range()).max().orElse(0));
            // The record's clock: a stance's whole length, its cooldown after it; one tick for a move that has none.
            int duration = stance == null ? 1 : stance.length();
            int cooldown = stance == null ? 1 : stance.length() + stance.cooldown();
            var attack = new DigimonAttack(id, DigimonAttack.Kind.COMPOUND, power, cooldown, duration, 0, range, false);
            result.put(id, new Definition(attack, List.copyOf(forms), stance, gauge, chain));
        }
        return Collections.unmodifiableMap(result);
    }
}
