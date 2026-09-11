package com.digicube.entity;

import com.digicube.digimon.AttackMotion;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Native spike volumes in feet-relative world units; shared by planning, hits and rendering. */
public final class TectonicWave {
    public static final int COUNT = 6, IMPACT = 28, END = 77;
    public static final float INVALID = -1000;
    private static AttackBox[][][] boxes;
    private static Vec3[] bases;
    private static final AABB[][] BOUNDS = load();
    private TectonicWave() {}
    private static AABB[][] load() {
        try (var in = TectonicWave.class.getResourceAsStream("/data/digicube/attack_motion/tectonic_spikes.json")) {
            if (in == null) throw new IllegalStateException("Missing native spike volumes");
            var data = GsonHelper.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            var frames = data.getAsJsonArray("frames");
            bases = new Vec3[COUNT];
            for(int j=0;j<COUNT;j++)bases[j]=vector(data.getAsJsonArray("bases").get(j).getAsJsonArray(),0);
            boxes = new AttackBox[frames.size()][COUNT][];
            AABB[][] result = new AABB[frames.size()][COUNT];
            for (int i=0;i<result.length;i++) for (int j=0;j<COUNT;j++) {
                var v=frames.get(i).getAsJsonArray().get(j).getAsJsonObject();
                var a=v.getAsJsonArray("min");var b=v.getAsJsonArray("max");
                result[i][j]=new AABB(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble(),
                        b.get(0).getAsDouble(),b.get(1).getAsDouble(),b.get(2).getAsDouble());
                var shapes=v.getAsJsonArray("boxes");boxes[i][j]=new AttackBox[shapes.size()];
                for(int k=0;k<shapes.size();k++) {
                    var shape=shapes.get(k).getAsJsonArray();
                    boxes[i][j][k]=new AttackBox(vector(shape,0),vector(shape,3),vector(shape,6),vector(shape,9));
                }
            }
            return result;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static Vec3 vector(com.google.gson.JsonArray values,int offset) {
        return new Vec3(values.get(offset).getAsDouble(),values.get(offset+1).getAsDouble(),values.get(offset+2).getAsDouble());
    }
    public static AttackBox[] boxes(int spike,double tick) {
        return boxes[Math.clamp((int)Math.round(tick*4),0,boxes.length-1)][spike].clone();
    }
    public static AABB local(int spike, double tick) {
        return BOUNDS[Math.clamp((int)Math.round(tick*4),0,BOUNDS.length-1)][spike];
    }
    public static Vec3 base(int spike) { return bases[spike]; }
    public static float yaw(Vec3 feet, Vec3 target) {
        double d=target.subtract(feet).horizontalDistance();
        return AttackGeometry.yaw(feet,target)+(float)Math.toDegrees(Math.asin(Math.clamp(base(0).x/Math.max(d,Math.abs(base(0).x)),-1,1)));
    }
    public static float yaw(Vec3 feet, Vec3 target, AttackMotion motion) {
        if (feet.subtract(target).horizontalDistance() < 2.1) {
            var frame = motion.sample(IMPACT);
            Vec3 fist = frame.hornBase().lerp(frame.hornTip(), .5);
            return AttackGeometry.yaw(feet, target) - (float)Math.toDegrees(Math.atan2(-fist.x, fist.z));
        }
        return yaw(feet, target);
    }
    /** The physical fist also lands: opponents inside the first spike are not immune to the slam. */
    static AABB slamBox(AttackMotion motion, Vec3 feet, float yaw) {
        var frame = motion.sample(IMPACT);
        AABB box = new AABB(AttackGeometry.world(feet, frame.hornBase(), yaw),
                AttackGeometry.world(feet, frame.hornTip(), yaw)).inflate(motion.contactRadius());
        return new AABB(box.minX, Math.max(feet.y, box.minY), box.minZ, box.maxX, box.maxY, box.maxZ);
    }
    /** Lead only until the nearby spike emerges; the released line never homes. */
    public static Vec3 aimPoint(Vec3 feet, LivingEntity target,int anticipation) {
        return aimPoint(feet, target.getBoundingBox().getCenter(), target.position().subtract(target.oldPosition()),anticipation);
    }
    static Vec3 aimPoint(Vec3 feet, Vec3 center, Vec3 velocity) {
        return aimPoint(feet,center,velocity,0);
    }
    static Vec3 aimPoint(Vec3 feet, Vec3 center, Vec3 velocity,int anticipation) {
        Vec3 horizontal = velocity.multiply(1, 0, 1);
        Vec3 predicted = center;
        for (int refine = 0; refine < 3; refine++) {
            double distance = predicted.subtract(feet).horizontalDistance();
            int nearest = 0;
            for (int i = 1; i < COUNT; i++) {
                if (Math.abs(base(i).horizontalDistance() - distance)
                        < Math.abs(base(nearest).horizontalDistance() - distance)) nearest = i;
            }
            double arrival = IMPACT;
            while (arrival < END && local(nearest, arrival).maxY < .2) arrival += .25;
            Vec3 lead = horizontal.scale(anticipation+(distance<2.1?0:arrival-IMPACT));
            if (lead.lengthSqr() > 2.5 * 2.5) lead = lead.normalize().scale(2.5);
            predicted = center.add(lead);
        }
        return predicted;
    }
    /** Targets may overlap a damaging spike; only terrain and the world border block it. */
    static boolean clearVolume(Level level, Entity source, AABB box) {
        return level.noBlockCollision(source, box.deflate(.02))
                && level.noBorderCollision(source, box.deflate(.02));
    }
    public static boolean intersects(int spike,double tick,Vec3 feet,float yaw,float height,AABB target) {
        double floor=feet.y+height;
        if(target.maxY<=floor)return false;
        AABB exposed=new AABB(target.minX,Math.max(target.minY,floor),target.minZ,target.maxX,target.maxY,target.maxZ);
        for(var box:boxes(spike,tick))if(box.world(feet,yaw,height).intersects(exposed))return true;
        return false;
    }
    public static AABB world(int spike,double tick,Vec3 feet,float yaw,float height) {
        AABB b=local(spike,tick);double minY=Math.max(0,b.minY),maxY=Math.max(minY,b.maxY);
        AABB result=null;
        for(double x:new double[]{b.minX,b.maxX})for(double z:new double[]{b.minZ,b.maxZ}) {
            Vec3 p=AttackGeometry.world(feet,new Vec3(x,minY+height,z),yaw);
            AABB corner=new AABB(p,p.add(0,maxY-minY,0));result=result==null?corner:result.minmax(corner);
        }
        return result;
    }
    /** Follow ordinary one-block stairs in either direction; stop at walls, chasms and water. */
    public static float[] ground(Level level,DigimonEntity owner,Vec3 feet,float yaw) {
        float[] heights=new float[COUNT];java.util.Arrays.fill(heights,INVALID);
        Vec3 previous=feet;double lastY=feet.y;
        for(int j=0;j<COUNT;j++) {
            Vec3 dest=AttackGeometry.world(feet,base(j),yaw);
            int steps=(int)Math.ceil(previous.subtract(dest).horizontalDistance()/.2);
            for(int k=1;k<=steps;k++) {
                Vec3 p=previous.lerp(dest,k/(double)steps);
                Vec3 high=new Vec3(p.x,lastY+1.1,p.z);
                var hit=level.clip(new ClipContext(high,high.add(0,-2.2,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,owner));
                if(hit.getType()==HitResult.Type.MISS || Math.abs(hit.getLocation().y-lastY)>1.01
                        || !level.getFluidState(hit.getBlockPos()).isEmpty()) return heights;
                double y=hit.getLocation().y;
                Vec3 from=new Vec3(k==1?previous.x:previous.lerp(dest,(k-1)/(double)steps).x,Math.max(y,lastY)+.1,
                        k==1?previous.z:previous.lerp(dest,(k-1)/(double)steps).z);
                if(level.clip(new ClipContext(from,new Vec3(p.x,from.y,p.z),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,owner)).getType()!=HitResult.Type.MISS) return heights;
                lastY=y;
            }
            heights[j]=(float)(lastY-feet.y);
            // A spike straddling a ledge can be occluded without cancelling the supported line beyond it.
            if(!clearVolume(level,owner,world(j,48,feet,yaw,heights[j]))) heights[j]=INVALID;
            previous=dest;
        }
        return heights;
    }
    public static boolean canReach(Level level,DigimonEntity owner,Vec3 feet,AABB target,AttackMotion motion) {
        float yaw=yaw(feet,target.getCenter(),motion);float[] h=ground(level,owner,feet,yaw);
        AABB slam=slamBox(motion,feet,yaw);
        if(slam.intersects(target) && clearVolume(level,owner,slam)
                && level.clip(new ClipContext(AttackGeometry.world(feet,motion.sample(IMPACT).head(),yaw),
                target.getCenter(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,owner)).getType()==HitResult.Type.MISS)return true;
        for(int j=0;j<COUNT;j++)if(h[j]!=INVALID && intersects(j,48,feet,yaw,h[j],target))return true;
        return false;
    }
}
