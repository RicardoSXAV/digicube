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

/** Finite native performances: the visual cells and damage volumes share one clock. */
public final class AuthoredAttacks {
    public record Definition(DigimonAttack attack, String effect, boolean emissive, boolean grounded, int hitInterval, int maxHits,
                             List<String> parts, List<List<String>> visualParts, List<AttackBox[]> frames, int samplesPerTick, List<double[]> hitWindows,
                             List<AttackBox[]> waterFrames, AttackMotion waterMotion, List<AttackBox[]> mirroredFrames, int anchorLockTick, Vec3 anchorApproach, List<String> contactParts,
                             com.digicube.entity.StrikeParticles particles) {
        public boolean hasWaterVariant() { return waterMotion != null; }
        /**
         * A summoned strike: effect and volumes are authored around a landing point instead of the caster's feet.
         * The point follows the target until {@link #anchorLockTick} and then stays, which is the victim's chance to move.
         */
        public boolean anchored() { return anchorLockTick >= 0; }
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
    private static final Map<Identifier,Definition> DEFINITIONS=load();
    private AuthoredAttacks() {}
    public static Collection<Definition> all() { return DEFINITIONS.values(); }
    public static Definition get(DigimonAttack attack) { return DEFINITIONS.get(attack.id()); }
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
            result.put(id,new Definition(attack,GsonHelper.getAsString(c,"effect",null),GsonHelper.getAsBoolean(c,"emissive",false),
                    GsonHelper.getAsBoolean(c,"grounded",false),interval,max,
                    List.copyOf(parts),List.copyOf(visuals),List.copyOf(frames),rate,List.copyOf(windows),List.copyOf(waterFrames),waterMotion,List.copyOf(mirroredFrames),anchorLock,approach,List.copyOf(contactParts),
                    com.digicube.entity.StrikeParticles.byId(GsonHelper.getAsString(c,"particles",null))));
        }
        return Collections.unmodifiableMap(result);
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
