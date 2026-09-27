package com.digicube.digimon;

import com.digicube.Constants;
import com.google.gson.JsonObject;
import com.digicube.entity.AttackBox;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Finite native performances: the visual cells and damage volumes share one clock. A definition's {@code effectClip}
 * is the clip of its effect model it plays (forms share one model), {@code key} the movement key a rider holds to pick
 * that form ({@code forward}, {@code left}, {@code right}; none for the first form's default).
 */
public final class AuthoredAttacks {
    public record Definition(DigimonAttack attack, String effect, boolean emissive, boolean grounded, int hitInterval, int maxHits,
                             List<String> parts, List<List<String>> visualParts, List<AttackBox[]> frames, int samplesPerTick, List<double[]> hitWindows,
                             List<AttackBox[]> waterFrames, AttackMotion waterMotion, List<AttackBox[]> mirroredFrames, int anchorLockTick, Vec3 anchorApproach, List<String> contactParts,
                             com.digicube.entity.StrikeParticles particles, boolean rootTravel, Leap leap, int charges, AttackVolley volley,
                             List<String> formNames, FormChoice formChoice, String effectClip, String key) {
        public boolean hasWaterVariant() { return waterMotion != null; }
        /**
         * A summoned strike: effect and volumes are authored around a landing point instead of the caster's feet.
         * The point follows the target until {@link #anchorLockTick} and then stays, which is the victim's chance to move.
         */
        public boolean anchored() { return anchorLockTick >= 0; }
        /** The server moves the caster along the motion's travel curve, as it does for a horn charge. */
        public boolean travels() { return rootTravel || leap != null; }
        /** The volumes do not strike: parts of the body fly as missiles instead (Gold Rush), see {@link AttackVolley}. */
        public boolean fires() { return volley != null; }
        public AttackMotion motion(boolean water) { return water && waterMotion != null ? waterMotion : attack.motion(); }
        public int beat(double tick) {
            for(int i=0;i<hitWindows.size();i++)if(tick>=hitWindows.get(i)[0] && tick<=hitWindows.get(i)[1])return i;
            return -1;
        }
        public AttackBox[] sample(double tick) {
            return sample(tick, false);
        }
        public AttackBox[] sample(double tick, boolean water) {
            return sample(tick, water, false);
        }
        public AttackBox[] sample(double tick, boolean water, boolean mirrored) {
            var frames = mirrored && !mirroredFrames.isEmpty() ? mirroredFrames
                    : water && !waterFrames.isEmpty() ? waterFrames : this.frames;
            double t=Math.clamp(tick*samplesPerTick,0,frames.size()-1);
            int i=(int)t; double w=t-i;
            AttackBox[] a=frames.get(i), b=frames.get(Math.min(i+1,frames.size()-1)), result=new AttackBox[a.length];
            for(int j=0;j<a.length;j++) {
                if(a[j]==null || b[j]==null) { result[j]=w<.5?a[j]:b[j];continue; }
                result[j]=new AttackBox(a[j].center().lerp(b[j].center(),w),a[j].x().lerp(b[j].x(),w),
                        a[j].y().lerp(b[j].y(),w),a[j].z().lerp(b[j].z(),w));
            }
            return result;
        }
    }
    /**
     * A jumping strike: the caster leaves the ground at {@code launch}, flies a planned arc and lands at {@code land},
     * its feet {@code lead} blocks short of where the target will be. Ticks between are the client's lunge window.
     * @param apex how high the arc rises above the higher of its two ends, in blocks
     * @param edge the lead is measured from the target's side instead of its centre, so a short reach (a claw) lands
     *             as close to a big body as to a small one
     */
    public record Leap(int launch, int land, double lead, double apex, boolean edge) {
        public boolean airborne(int tick) { return tick >= launch && tick < land; }
        /** Blocks between the landing feet and the target's centre. */
        public double lead(net.minecraft.world.entity.LivingEntity target) { return edge && target != null ? lead + target.getBbWidth() / 2 : lead; }
    }
    /**
     * How the AI picks one of a move's forms. {@code COMBO}: the forms are a combo in list order, and the next one
     * follows while the last ended no more than {@link #COMBO_TICKS} ago; otherwise the lightest form that reaches wins
     * (one that does not travel before one that does). {@code REACH}: the forms are one strike at different distances;
     * the one worth most (power times the chance it lands) wins.
     */
    public enum FormChoice { COMBO, REACH }
    /** Ticks after one form of a combo ends in which the next form continues it. */
    public static final int COMBO_TICKS = 24;
    /**
     * A move with forms ({@code forms} in {@code authored_attacks.json}): the species sheet names the first, which is
     * form 0; every cast plays one of them and spends the first's stacked uses. Each form is a complete authored attack
     * (clip, motion, volumes, effect clip); only the first carries the cooldown and the {@code charges}.
     * @param all the forms, the first one first
     */
    public record Forms(List<DigimonAttack> all, FormChoice choice) {
        public int index(DigimonAttack form) { return all.indexOf(form); }
    }
    private static final Map<Identifier,Definition> DEFINITIONS=load();
    private static final Map<Identifier,Forms> FORMS=new HashMap<>();
    private static final Map<Identifier,DigimonAttack> FIRST_FORMS=new HashMap<>();
    static { linkForms(); }
    private AuthoredAttacks() {}
    public static Collection<Definition> all() { return DEFINITIONS.values(); }
    public static Definition get(DigimonAttack attack) { return DEFINITIONS.get(attack.id()); }
    public static Definition get(Identifier id) { return DEFINITIONS.get(id); }
    /** The forms of a move named on a species sheet, or null when it has only itself. */
    public static Forms forms(DigimonAttack move) { return move == null ? null : FORMS.get(move.id()); }
    /** The move on the sheet a form belongs to (the form itself for anything else): its uses and cooldown are the move's. */
    public static DigimonAttack move(DigimonAttack attack) { return attack == null ? null : FIRST_FORMS.getOrDefault(attack.id(), attack); }
    private static void linkForms() {
        for (var d : DEFINITIONS.values()) {
            if (d.formNames().isEmpty()) continue;
            var list = new ArrayList<DigimonAttack>();
            list.add(d.attack());
            for (String name : d.formNames()) {
                var form = DEFINITIONS.get(Constants.id(name));
                if (form == null || !form.formNames().isEmpty() || form.charges() != 1 || form.attack().alternateSides() || form.attack() == d.attack()
                        || FIRST_FORMS.containsKey(form.attack().id()) || list.size() >= com.digicube.entity.DigimonAnimationEvents.MAX_FORMS)
                    throw new IllegalArgumentException("A form is a plain authored attack of one move " + d.attack().id() + ": " + name);
                list.add(form.attack());
                FIRST_FORMS.put(form.attack().id(), d.attack());
            }
            if (d.attack().alternateSides()) throw new IllegalArgumentException("A move with forms does not alternate " + d.attack().id());
            FORMS.put(d.attack().id(), new Forms(List.copyOf(list), d.formChoice()));
        }
    }

    public static boolean handles(DigimonAttack attack) {
        return attack.kind()==DigimonAttack.Kind.BOX_SWEEP || attack.kind()==DigimonAttack.Kind.BOX_BURST;
    }
    private static JsonObject read(String path) {
        try(var in=AuthoredAttacks.class.getResourceAsStream(path)) {
            if(in==null)throw new IllegalStateException("Missing "+path);
            return GsonHelper.parse(new InputStreamReader(in,StandardCharsets.UTF_8));
        } catch(java.io.IOException e) { throw new IllegalStateException(path,e); }
    }
    private static Vec3 vector(com.google.gson.JsonArray a,int i) {
        return new Vec3(a.get(i).getAsDouble(),a.get(i+1).getAsDouble(),a.get(i+2).getAsDouble());
    }
    private static Map<Identifier,Definition> load() {
        var config=read("/data/digicube/authored_attacks.json");
        var volumes=read("/data/digicube/attack_volumes/authored.json");
        int defaultRate=volumes.get("samples_per_tick").getAsInt();
        Map<Identifier,Definition> result=new LinkedHashMap<>();
        for(var entry:config.entrySet()) {
            String name=entry.getKey();var c=entry.getValue().getAsJsonObject();var id=Constants.id(name);
            var attack=new DigimonAttack(id,DigimonAttack.Kind.valueOf(c.get("kind").getAsString()),c.get("power").getAsFloat(),
                    c.get("cooldown").getAsInt(),c.get("duration").getAsInt(),c.get("hit_tick").getAsInt(),
                    c.get("range").getAsDouble(),GsonHelper.getAsBoolean(c,"alternate",false),AttackMotion.load(id),null,c.get("knockback").getAsDouble());
            var v=volumes.getAsJsonObject("attacks").getAsJsonObject(name);
            int rate=GsonHelper.getAsInt(v,"samples_per_tick",defaultRate);
            List<String> parts=new ArrayList<>();v.getAsJsonArray("parts").forEach(p->parts.add(p.getAsString()));
            List<List<String>> visuals=new ArrayList<>();
            if (v.has("visual_parts")) for (var group:v.getAsJsonArray("visual_parts")) {
                var names=new ArrayList<String>();group.getAsJsonArray().forEach(p->names.add(p.getAsString()));
                if(names.isEmpty())throw new IllegalArgumentException("Empty visual group "+id);
                visuals.add(List.copyOf(names));
            }
            if(visuals.isEmpty()) for(String part:parts)visuals.add(List.of(part));
            if(visuals.size()!=parts.size())throw new IllegalArgumentException("Visual volume width "+id);
            List<AttackBox[]> frames=readFrames(v.getAsJsonArray("frames"),parts.size(),id);
            List<AttackBox[]> mirroredFrames=v.has("mirrored_frames")?readFrames(v.getAsJsonArray("mirrored_frames"),parts.size(),id):List.of();
            if (attack.alternateSides() && mirroredFrames.size()!=frames.size())
                throw new IllegalArgumentException("Missing mirrored contact performance "+id);
            List<AttackBox[]> waterFrames=v.has("water_frames")?readFrames(v.getAsJsonArray("water_frames"),parts.size(),id):List.of();
            AttackMotion waterMotion=c.has("water_motion")?AttackMotion.load(Constants.id(c.get("water_motion").getAsString())):null;
            if((waterMotion==null)!=waterFrames.isEmpty() || waterMotion!=null && (waterFrames.size()!=frames.size()
                    || waterMotion.activeFrom()!=attack.motion().activeFrom() || waterMotion.activeUntil()!=attack.motion().activeUntil()))
                throw new IllegalArgumentException("Water performance clock "+id);
            int interval=c.get("hit_interval").getAsInt(),max=c.get("max_hits").getAsInt();
            var windows=new ArrayList<double[]>();
            if(c.has("hit_windows"))for(var window:c.getAsJsonArray("hit_windows")) {
                var w=window.getAsJsonArray();double from=w.get(0).getAsDouble(),until=w.get(1).getAsDouble();
                if(from<0 || until<from || until>attack.durationTicks())throw new IllegalArgumentException("Invalid hit window "+id);
                windows.add(new double[]{from,until});
            }
            if(rate<1 || frames.size()!=attack.durationTicks()*rate+1 || interval<1 || max<1)throw new IllegalArgumentException("Volume clock "+id);
            int anchorLock=GsonHelper.getAsInt(c,"anchor_lock_tick",-1);
            // Where a summoned strike comes from, relative to its landing point: the first place its leading volume hangs.
            Vec3 approach=Vec3.ZERO;
            for(var row:frames)if(row[0]!=null){approach=row[0].center();break;}
            // Effect cells a miss never shows: the hit burst of a fist, as opposed to its smear.
            var contactParts=new ArrayList<String>();
            if(c.has("contact_parts"))c.getAsJsonArray("contact_parts").forEach(p->contactParts.add(p.getAsString()));
            if(anchorLock>=0 && (attack.kind()!=DigimonAttack.Kind.BOX_BURST || windows.isEmpty() || anchorLock>windows.getFirst()[0] || waterMotion!=null))
                throw new IllegalArgumentException("A landing point locks before the first hit window of a burst "+id);
            boolean rootTravel=GsonHelper.getAsBoolean(c,"root_travel",false);
            // Uses stacked up, each refilling on its own cooldown (Gold Rush); 1 is an ordinary cooldown.
            int charges=GsonHelper.getAsInt(c,"charges",1);
            if(charges<1 || charges>9)throw new IllegalArgumentException("Charges between 1 and 9 "+id);
            Leap leap=null;
            if(c.has("leap")) {
                var l=c.getAsJsonObject("leap");
                leap=new Leap(l.get("launch").getAsInt(),l.get("land").getAsInt(),l.get("lead").getAsDouble(),GsonHelper.getAsDouble(l,"apex",2.0),
                        GsonHelper.getAsBoolean(l,"edge",false));
                if(leap.launch()<0 || leap.land()<=leap.launch() || leap.land()>=attack.durationTicks() || leap.lead()<0 || leap.apex()<=0
                        || windows.isEmpty() || windows.getFirst()[0]<leap.land()-1 || anchorLock>=0 || rootTravel || waterMotion!=null)
                    throw new IllegalArgumentException("A leap lands before its first hit window and carries nothing else "+id);
            }
            AttackVolley volley=c.has("volley")?AttackVolley.load(name,c.getAsJsonObject("volley"),attack.durationTicks()):null;
            if(volley!=null && (attack.kind()!=DigimonAttack.Kind.BOX_BURST || anchorLock>=0 || leap!=null || rootTravel || waterMotion!=null))
                throw new IllegalArgumentException("A volley is a plain burst "+id);
            result.put(id,new Definition(attack,GsonHelper.getAsString(c,"effect",null),GsonHelper.getAsBoolean(c,"emissive",false),
                    GsonHelper.getAsBoolean(c,"grounded",false),interval,max,
                    List.copyOf(parts),List.copyOf(visuals),List.copyOf(frames),rate,List.copyOf(windows),List.copyOf(waterFrames),waterMotion,List.copyOf(mirroredFrames),anchorLock,approach,List.copyOf(contactParts),
                    com.digicube.entity.StrikeParticles.byId(GsonHelper.getAsString(c,"particles",null)),rootTravel,leap,charges,volley,
                    forms(c),FormChoice.valueOf(GsonHelper.getAsString(c,"form_choice","combo").toUpperCase(Locale.ROOT)),
                    GsonHelper.getAsString(c,"effect_clip","effect"),GsonHelper.getAsString(c,"key",null)));
        }
        return Collections.unmodifiableMap(result);
    }
    private static List<String> forms(JsonObject c) {
        if (!c.has("forms")) return List.of();
        var names = new ArrayList<String>();
        c.getAsJsonArray("forms").forEach(n -> names.add(n.getAsString()));
        return List.copyOf(names);
    }
    private static List<AttackBox[]> readFrames(com.google.gson.JsonArray rows,int width,Identifier id) {
        List<AttackBox[]> frames=new ArrayList<>();
        for(var row:rows) {
            var cells=row.getAsJsonArray();var boxes=new AttackBox[cells.size()];
            if(cells.size()!=width)throw new IllegalArgumentException("Volume width "+id);
            for(int i=0;i<boxes.length;i++) if(!cells.get(i).isJsonNull()) {
                var a=cells.get(i).getAsJsonArray();if(a.size()!=12)throw new IllegalArgumentException("Cuboid "+id);
                for(var number:a)if(!Double.isFinite(number.getAsDouble()))throw new IllegalArgumentException("Nonfinite cuboid "+id);
                boxes[i]=new AttackBox(vector(a,0),vector(a,3),vector(a,6),vector(a,9));
            }
            frames.add(boxes);
        }
        return frames;
    }
}
