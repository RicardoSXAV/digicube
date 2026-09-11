package com.digicube.entity;
import com.digicube.Constants;
import com.digicube.digimon.*;
import net.minecraft.world.phys.*;
import java.util.List;

/** Authored contact, no-through-wall behavior, timing, scale and attack priority. */
public final class GolemonRegressionTest {
    private GolemonRegressionTest() {}
    public static void main(String[] args) {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
            DigimonSpeciesBootstrap.registerBuiltIn();
            var effects=net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT;
            field(effects,net.minecraft.core.MappedRegistry.class,"frozen",false);
            try {com.digicube.registry.DCEffects.init();} finally {field(effects,net.minecraft.core.MappedRegistry.class,"frozen",true);}
            run();
        } catch(Exception e) {throw new AssertionError(e);
        } finally {net.minecraft.util.Util.shutdownExecutors();}
    }
    public static void run() {
        var golem=DigimonSpeciesRegistry.getOrThrow(Constants.id("golemon"));
        var punch=DigimonSpeciesBootstrap.ROCK_PUNCH;var wave=DigimonSpeciesBootstrap.TECTONIC_FIST;
        check(golem.stage()==DigimonStage.ADULT && golem.attribute()==DigimonAttribute.VIRUS,"stone virus champion");
        check(golem.attacks().equals(List.of(wave,punch)),"special gets priority and fast attack fills its cooldown");
        check(punch.cooldownTicks()==24 && punch.durationTicks()==22 && wave.cooldownTicks()==240,"fast punch and 12-second tectonic cooldown");
        check(wave.power()>punch.power()*2 && wave.knockback()>1,"champion attack strength");
        for(float yaw:new float[]{0,90,180,270}) {
            Vec3 center=new Vec3(0,0,1.8).yRot((float)-Math.toRadians(yaw));
            AABB target=new AABB(center.x-.45,0,center.z-.45,center.x+.45,1.8,center.z+.45);
            check(AttackGeometry.canContact(punch,Vec3.ZERO,2.1,2.75,target,(a,b)->true,b->true,p->true),"native fist reaches opponent at yaw "+yaw);
            check(!AttackGeometry.canContact(punch,Vec3.ZERO,2.1,2.75,target,(a,b)->false,b->true,p->true),"fist cannot hit through cover");
            check(!AttackGeometry.canContact(punch,Vec3.ZERO,2.1,2.75,target.move(0,4,0),(a,b)->true,b->true,p->true),"high targets are outside fist");
        }
        double lastHeight=0,lastFront=0;
        for(int i=0;i<6;i++) {
            var b=TectonicWave.local(i,48);
            check(b.maxY>lastHeight && b.maxZ>lastFront,"six progressively larger spikes advance in one line");
            check(TectonicWave.local(i,28).maxY<0,"spikes are still underground at impact");
            lastHeight=b.maxY;lastFront=b.maxZ;
            var a=TectonicWave.world(i,48,Vec3.ZERO,0,0);var raised=TectonicWave.world(i,48,Vec3.ZERO,0,.5F);
            check(Math.abs(raised.minY-a.minY-.5)<1e-6,"terrain height affects physical volume");
        }
        check(TectonicWave.local(5,84).maxY<0,"spikes fully retract");
        check(golem.locomotion().groundGait().advance(0,0,.5F)==0,"walk freezes at zero travel");
        try { terrain(); damage(); positioning(); } catch(Exception e) {throw new AssertionError(e);}
    }
    private static <T> T allocate(Class<T> type)throws Exception {
        var c=Class.forName("sun.misc.Unsafe");var f=c.getDeclaredField("theUnsafe");f.setAccessible(true);
        return type.cast(c.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));
    }
    private static void field(Object object,Class<?> type,String name,Object value)throws Exception {
        var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private static void place(net.minecraft.world.entity.Entity entity,Vec3 p)throws Exception {
        field(entity,net.minecraft.world.entity.Entity.class,"position",p);
        entity.setBoundingBox(new AABB(p.x-.3,p.y,p.z-.3,p.x+.3,p.y+1.8,p.z+.3));
    }
    private static TectonicWaveEntity cast(Owner owner,Floor world,float yaw)throws Exception {
        var wave=allocate(TectonicWaveEntity.class);
        place(wave,Vec3.ZERO);wave.setYRot(yaw);
        field(wave,TectonicWaveEntity.class,"owner",owner);
        field(wave,TectonicWaveEntity.class,"attack",DigimonSpeciesBootstrap.TECTONIC_FIST);
        field(wave,TectonicWaveEntity.class,"hit",new java.util.HashSet<java.util.UUID>());
        var builder=new net.minecraft.network.syncher.SynchedEntityData.Builder(wave);
        // Defaults normally installed by Entity's constructor before subclass data.
        for(var entry:java.util.Map.<String,Object>of("DATA_SHARED_FLAGS_ID",(byte)0,"DATA_AIR_SUPPLY_ID",300,
                "DATA_CUSTOM_NAME_VISIBLE",false,"DATA_CUSTOM_NAME",java.util.Optional.empty(),"DATA_SILENT",false,
                "DATA_NO_GRAVITY",false,"DATA_POSE",net.minecraft.world.entity.Pose.STANDING,"DATA_TICKS_FROZEN",0).entrySet()) {
            var data=net.minecraft.world.entity.Entity.class.getDeclaredField(entry.getKey());data.setAccessible(true);
            @SuppressWarnings("unchecked")
            var key=(net.minecraft.network.syncher.EntityDataAccessor<Object>)data.get(null);
            builder.define(key,entry.getValue());
        }
        wave.defineSynchedData(builder);
        field(wave,net.minecraft.world.entity.Entity.class,"entityData",builder.build());
        var f=TectonicWaveEntity.class.getDeclaredField("HEIGHTS");f.setAccessible(true);
        @SuppressWarnings("unchecked")
        var accessors=(List<net.minecraft.network.syncher.EntityDataAccessor<Float>>)f.get(null);
        float[] heights=TectonicWave.ground(world,owner,Vec3.ZERO,yaw);
        for(int i=0;i<6;i++)wave.getEntityData().set(accessors.get(i),heights[i]);
        return wave;
    }
    private static void damage()throws Exception {
        var world=allocate(Floor.class);var owner=allocate(Owner.class);var target=allocate(Owner.class);
        place(owner,Vec3.ZERO);place(target,new Vec3(TectonicWave.base(0).x,0,4.5));world.victim=target;
        field(target,net.minecraft.world.entity.Entity.class,"uuid",java.util.UUID.randomUUID());
        var cast=cast(owner,world,0);
        cast.damageAt(world,28);check(owner.hits==0,"underground spikes do not damage distant prey");
        // Deliberately enter after the former emergence-only damage window.
        for(int tick=48;tick<77;tick++)cast.damageAt(world,tick);
        check(owner.hits==1,"late contact with overlapping exposed spikes damages once per cast");
        owner.hits=0;owner.reject=true;cast=cast(owner,world,0);
        cast.damageAt(world,48);check(owner.hits==0,"failed damage is not recorded as a hit");
        owner.reject=false;cast.damageAt(world,49);cast.damageAt(world,50);
        check(owner.hits==1,"temporary invulnerability can expire while touching exposed stone");
        owner.hits=0;owner.ally=true;cast=cast(owner,world,0);cast.damageAt(world,48);
        check(owner.hits==0,"allied targets are excluded");owner.ally=false;
        world.wall=true;cast=cast(owner,world,0);cast.damageAt(world,48);
        check(owner.hits==0,"spikes cannot continue through cover");world.wall=false;
        world.border=true;cast=cast(owner,world,0);cast.damageAt(world,48);
        check(owner.hits==0,"world border still blocks the wave");world.border=false;
        cast=cast(owner,world,0);world.wall=true;cast.damageAt(world,48);
        check(owner.hits==0,"cover placed after release also stops exposed spikes");world.wall=false;
        cast=cast(owner,world,0);cast.damageAt(world,76);
        check(owner.hits==0,"retracted spikes do not retain an invisible hitbox");
        place(target,new Vec3(-TectonicWave.base(0).x,0,4.5));cast=cast(owner,world,0);cast.damageAt(world,48);
        check(owner.hits==0,"the former mirrored collision line cannot hit on the opposite side");
        var motion=DigimonSpeciesBootstrap.TECTONIC_FIST.motion();
        for(float angle:new float[]{0,90,180,270}) {
            Vec3 close=new Vec3(0,0,1.35).yRot((float)-Math.toRadians(angle));place(target,close);
            check(TectonicWave.canReach(world,owner,Vec3.ZERO,target.getBoundingBox(),motion),"close enemy can be slammed at "+angle);
            cast=cast(owner,world,TectonicWave.yaw(Vec3.ZERO,target.getBoundingBox().getCenter(),motion));
            owner.hits=0;cast.damageAt(world,28);check(owner.hits==1,"physical fist covers the gap before the first spike");
            place(target,AttackGeometry.world(Vec3.ZERO,TectonicWave.base(1),cast.getYRot()));
            cast.damageAt(world,48);check(owner.hits==1,"slam and spike share one damage allowance");
        }
        // A strafing target used to leave the narrow line before the delayed eruption arrived.
        for(float angle:new float[]{0,90,180,270}) {
            Vec3 start=new Vec3(0,0,5.7).yRot((float)-Math.toRadians(angle));
            Vec3 velocity=new Vec3(.12,0,0).yRot((float)-Math.toRadians(angle));
            cast=cast(owner,world,TectonicWave.yaw(Vec3.ZERO,start,motion));owner.hits=0;
            for(int tick=28;tick<65;tick++) {
                place(target,start.add(velocity.scale(tick-28)));cast.damageAt(world,tick);
            }
            check(owner.hits==0,"unled line reproduces the strafing miss at "+angle);
            Vec3 aim=TectonicWave.aimPoint(Vec3.ZERO,start.add(0,.9,0),velocity);
            cast=cast(owner,world,TectonicWave.yaw(Vec3.ZERO,aim,motion));owner.hits=0;
            for(int tick=28;tick<65;tick++) {
                place(target,start.add(velocity.scale(tick-28)));cast.damageAt(world,tick);
            }
            check(owner.hits==1,"led spike line intercepts moving prey at "+angle);
        }
        Vec3 center=new Vec3(0,.9,5);
        check(TectonicWave.aimPoint(Vec3.ZERO,center,Vec3.ZERO).equals(center),"stationary prey needs no lead");
        check(TectonicWave.aimPoint(Vec3.ZERO,center,new Vec3(100,100,100)).distanceTo(center)<=2.50001,"extreme movement has bounded horizontal lead");
        // A bounding envelope includes air around a tapered, tilted stone; require native cuboid contact.
        int airCorners=0;
        for(float yaw:new float[]{0,45,90,135,180,225,270,315}) {
            AABB envelope=TectonicWave.world(5,48,Vec3.ZERO,yaw,0);
            for(double x:new double[]{envelope.minX+.01,envelope.maxX-.02})for(double z:new double[]{envelope.minZ+.01,envelope.maxZ-.02}) {
                AABB tiny=new AABB(x,envelope.maxY-.05,z,x+.01,envelope.maxY-.04,z+.01);
                if(!TectonicWave.intersects(5,48,Vec3.ZERO,yaw,0,tiny))airCorners++;
            }
        }
        check(airCorners==32,"all empty upper corners reject broad-phase false hits at eight facings");
    }
    private static void terrain()throws Exception {
        var level=allocate(Floor.class);var owner=allocate(Owner.class);
        var position=net.minecraft.world.entity.Entity.class.getDeclaredField("position");position.setAccessible(true);position.set(owner,Vec3.ZERO);
        owner.setBoundingBox(new AABB(-1,0,-1,1,2.75,1));
        float[] heights=TectonicWave.ground(level,owner,Vec3.ZERO,0);
        for(float h:heights)check(h==0,"all six spikes supported on flat stone");
        level.wall=true;heights=TectonicWave.ground(level,owner,Vec3.ZERO,0);
        check(heights[0]==0 && heights[5]==TectonicWave.INVALID,"wall stops continuation");
        level.wall=false;level.gap=true;heights=TectonicWave.ground(level,owner,Vec3.ZERO,0);
        check(heights[0]==0 && heights[5]==TectonicWave.INVALID,"unsupported chasm stops continuation");
        level.gap=false;level.step=true;level.stepHeight=.5;heights=TectonicWave.ground(level,owner,Vec3.ZERO,0);
        check(heights[0]==0 && heights[5]==.5F,"half block rise follows terrain");
        var motion=DigimonSpeciesBootstrap.TECTONIC_FIST.motion();
        for(double height:new double[]{-1,1}) {
            level.stepHeight=height;
            heights=TectonicWave.ground(level,owner,Vec3.ZERO,0);
            check(heights[0]==0 && heights[5]==height,"one-block step follows terrain at "+height);
            AABB cow=new AABB(-.45,height,4.55,.45,height+1.4,5.45);
            check(TectonicWave.canReach(level,owner,Vec3.ZERO,cow,motion),"cow one block above/below is a usable spike target");
            var target=allocate(Owner.class);place(target,new Vec3(0,height,5));target.setBoundingBox(cow);level.victim=target;
            field(target,net.minecraft.world.entity.Entity.class,"uuid",java.util.UUID.randomUUID());
            var wave=cast(owner,level,TectonicWave.yaw(Vec3.ZERO,cow.getCenter(),motion));owner.hits=0;
            for(int tick=28;tick<77;tick++)wave.damageAt(level,tick);
            check(owner.hits==1,"actual spike damage reaches cow at one-block elevation "+height);
            String review=System.getProperty("golemon.review.dir");
            if(review!=null && height==-1) {
                float[] actual=new float[6];for(int i=0;i<6;i++)actual[i]=wave.height(i);
                java.nio.file.Files.writeString(java.nio.file.Path.of(review,"ledge_scene.json"),
                        "{\"yaw\":"+wave.getYRot()+",\"heights\":"+java.util.Arrays.toString(actual)+",\"target\":[0,-1,5],\"hits\":"+owner.hits+"}");
            }
        }
        for(double height:new double[]{-2,2}) {
            level.stepHeight=height;heights=TectonicWave.ground(level,owner,Vec3.ZERO,0);
            check(heights[5]==TectonicWave.INVALID,"two-block cliff/wall still blocks propagation");
        }
        level.step=false;level.water=true;
        for(float h:TectonicWave.ground(level,owner,Vec3.ZERO,0))check(h==TectonicWave.INVALID,"water cannot support the spike line");
    }
    private static void positioning()throws Exception {
        var world=allocate(Floor.class);world.step=true;world.stepHeight=-1;world.entityObstruction=false;
        var owner=allocate(Owner.class);owner.world=world;owner.nav=allocate(Navigation.class);
        owner.moves=List.of(DigimonSpeciesBootstrap.ROCK_PUNCH);
        var target=allocate(Owner.class);place(owner,Vec3.ZERO);place(target,new Vec3(0,-1,5));
        field(owner,net.minecraft.world.entity.Entity.class,"dimensions",net.minecraft.world.entity.EntityDimensions.fixed(2.1F,2.75F));
        field(target,net.minecraft.world.entity.Entity.class,"dimensions",net.minecraft.world.entity.EntityDimensions.fixed(.9F,1.4F));
        owner.setBoundingBox(new AABB(-1.05,0,-1.05,1.05,2.75,1.05));
        target.setBoundingBox(new AABB(-.45,-1,4.55,.45,.4,5.45));
        check(!owner.canAttackFrom(owner.moves.getFirst(),target,Vec3.ZERO),"punch cannot invent downward/ranged reach");
        var path=com.digicube.entity.ai.DigimonCombatPosition.find(owner,target);
        check(path!=null,"cooling-down spikes allow a reachable fist stance below the ledge");
        Vec3 end=path.getEntityPosAtNode(owner,path.getNodeCount()-1);
        check(end.y==-1 && owner.canAttackFrom(owner.moves.getFirst(),target,end),"actual wide-body path endpoint places fist within reach");
        owner.nav.unreachable=true;
        check(com.digicube.entity.ai.DigimonCombatPosition.find(owner,target)==null,"unreachable navigation endpoints are rejected");
    }
    private static final class Navigation extends net.minecraft.world.entity.ai.navigation.GroundPathNavigation {
        boolean unreachable;
        private Navigation(){super(null,null);}
        @Override public net.minecraft.world.level.pathfinder.Path createPath(net.minecraft.core.BlockPos target,int accuracy) {
            var node=new net.minecraft.world.level.pathfinder.Node(target.getX(),target.getY(),target.getZ());
            return new net.minecraft.world.level.pathfinder.Path(List.of(node),new net.minecraft.core.BlockPos(node.x,node.y,node.z),!unreachable);
        }
    }
    private static final class Owner extends DigimonEntity {
        int hits;boolean reject,ally;
        Floor world;Navigation nav;List<DigimonAttack> moves;
        private Owner(){super(null,null);}
        @Override public net.minecraft.world.level.Level level(){return world;}
        @Override public java.util.Optional<DigimonSpecies> getSpecies(){return DigimonSpeciesRegistry.get(Constants.id("golemon"));}
        @Override public net.minecraft.world.entity.ai.navigation.PathNavigation getNavigation(){return nav;}
        @Override public List<DigimonAttack> positioningAttacks(net.minecraft.world.entity.LivingEntity target){return moves;}
        @Override public boolean isAttackReady(DigimonAttack attack){return true;}
        @Override public boolean hasEffect(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect){return false;}
        @Override public boolean isAlive(){return true;}
        @Override public boolean canAttack(net.minecraft.world.entity.LivingEntity e){return e!=this;}
        @Override public boolean isAllyOf(net.minecraft.world.entity.Entity e){return ally;}
        @Override public boolean hitWithAttack(net.minecraft.server.level.ServerLevel level,DigimonAttack attack,net.minecraft.world.entity.LivingEntity e) {
            if(reject)return false;hits++;return true;
        }
        @Override public boolean isDescending(){return false;}
        @Override public net.minecraft.world.item.ItemStack getMainHandItem(){return net.minecraft.world.item.ItemStack.EMPTY;}
    }
    private static final class Floor extends net.minecraft.server.level.ServerLevel {
        boolean wall,gap,step,water,border;boolean entityObstruction=true;double stepHeight;Owner victim;
        private Floor(){super(null,null,null,null,null,null,false,0,List.of(),false);}
        @Override public <T extends net.minecraft.world.entity.Entity> List<T> getEntitiesOfClass(Class<T> type,AABB box,java.util.function.Predicate<? super T> predicate) {
            return victim!=null && type.isInstance(victim) && box.intersects(victim.getBoundingBox()) && predicate.test(type.cast(victim))
                    ?List.of(type.cast(victim)):List.of();
        }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos p) {
            return (water?net.minecraft.world.level.material.Fluids.WATER:net.minecraft.world.level.material.Fluids.EMPTY).defaultFluidState();
        }
        @Override public boolean noEntityCollision(net.minecraft.world.entity.Entity e,AABB b){return !entityObstruction;}
        @Override public boolean noBorderCollision(net.minecraft.world.entity.Entity e,AABB b){return !border;}
        @Override public Iterable<net.minecraft.world.phys.shapes.VoxelShape> getBlockCollisions(net.minecraft.world.entity.Entity e,AABB b) {
            var obstruction=new AABB(-8,0,3,8,8,3.4);
            var shapes=new java.util.ArrayList<net.minecraft.world.phys.shapes.VoxelShape>();
            if(wall && obstruction.intersects(b))shapes.add(net.minecraft.world.phys.shapes.Shapes.create(obstruction));
            for(var ground:List.of(new AABB(-100,-100,-100,100,0,3),new AABB(-100,-100,3,100,step?stepHeight:0,100)))
                if(ground.intersects(b))shapes.add(net.minecraft.world.phys.shapes.Shapes.create(ground));
            return shapes;
        }
        @Override public BlockHitResult clip(net.minecraft.world.level.ClipContext c) {
            Vec3 a=c.getFrom(),b=c.getTo();double y=step && b.z>3?stepHeight:0;
            Vec3 hit=null;
            if(wall)hit=new AABB(-8,0,3,8,8,3.4).clip(a,b).orElse(null);
            if(hit==null && !(gap && b.z>3) && a.y>y && b.y<=y)hit=a.lerp(b,(a.y-y)/(a.y-b.y));
            return hit==null?BlockHitResult.miss(b,net.minecraft.core.Direction.UP,net.minecraft.core.BlockPos.ZERO):
                    new BlockHitResult(hit,net.minecraft.core.Direction.UP,net.minecraft.core.BlockPos.containing(hit),false);
        }
    }
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
