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
    public record Definition(DigimonAttack attack, String effect, boolean emissive, int hitInterval, int maxHits,
                             List<String> parts, List<AttackBox[]> frames, int samplesPerTick) {
        public AttackBox[] sample(double tick) {
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
        int rate=volumes.get("samples_per_tick").getAsInt();
        Map<Identifier,Definition> result=new LinkedHashMap<>();
        for(var entry:config.entrySet()) {
            String name=entry.getKey();var c=entry.getValue().getAsJsonObject();var id=Constants.id(name);
            var attack=new DigimonAttack(id,DigimonAttack.Kind.valueOf(c.get("kind").getAsString()),c.get("power").getAsFloat(),
                    c.get("cooldown").getAsInt(),c.get("duration").getAsInt(),c.get("hit_tick").getAsInt(),
                    c.get("range").getAsDouble(),false,AttackMotion.load(id),null,c.get("knockback").getAsDouble());
            var v=volumes.getAsJsonObject("attacks").getAsJsonObject(name);
            List<String> parts=new ArrayList<>();v.getAsJsonArray("parts").forEach(p->parts.add(p.getAsString()));
            List<AttackBox[]> frames=new ArrayList<>();
            for(var row:v.getAsJsonArray("frames")) {
                var cells=row.getAsJsonArray();var boxes=new AttackBox[cells.size()];
                if(cells.size()!=parts.size())throw new IllegalArgumentException("Volume width "+id);
                for(int i=0;i<boxes.length;i++) if(!cells.get(i).isJsonNull()) {
                    var a=cells.get(i).getAsJsonArray();if(a.size()!=12)throw new IllegalArgumentException("Cuboid "+id);
                    for(var number:a)if(!Double.isFinite(number.getAsDouble()))throw new IllegalArgumentException("Nonfinite cuboid "+id);
                    boxes[i]=new AttackBox(vector(a,0),vector(a,3),vector(a,6),vector(a,9));
                }
                frames.add(boxes);
            }
            int interval=c.get("hit_interval").getAsInt(),max=c.get("max_hits").getAsInt();
            if(rate<1 || frames.size()!=attack.durationTicks()*rate+1 || interval<1 || max<1)throw new IllegalArgumentException("Volume clock "+id);
            result.put(id,new Definition(attack,GsonHelper.getAsString(c,"effect",null),GsonHelper.getAsBoolean(c,"emissive",false),interval,max,List.copyOf(parts),List.copyOf(frames),rate));
        }
        return Collections.unmodifiableMap(result);
    }
}
