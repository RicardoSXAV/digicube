package com.digicube.entity;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.registry.DCEffects;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.entity.ai.navigation.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Executes real cast preparation/hold/release with flat-world collision fixtures. */
public final class ConstrictionRegressionTest {
    private ConstrictionRegressionTest() {}
    public static void run() {
        try { exercise(); } catch(Exception e) {throw new AssertionError(e);}
    }
    private static void exercise() throws Exception {
        // Reproduce the loader's effect-registration phase in plain Minecraft fixtures.
        var registry=net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT;
        field(registry,net.minecraft.core.MappedRegistry.class,"frozen",false);
        try { DCEffects.init(); } finally { field(registry,net.minecraft.core.MappedRegistry.class,"frozen",true); }
        var species=DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon"));
        check(species.stage()==DigimonStage.ADULT&&species.attribute()==DigimonAttribute.DATA,"adult data species");
        check(species.attacks().equals(List.of(DigimonSpeciesBootstrap.CONSTRICTION,DigimonSpeciesBootstrap.ICE_BLAST)),"only the two authored attacks");
        check(species.locomotion().canSwim()&&species.locomotion().swimSpeed()>.6,"fast aquatic navigation");
        var ice=DigimonSpeciesBootstrap.ICE_BLAST;var wrap=DigimonSpeciesBootstrap.CONSTRICTION;
        check(ice.fuel().equals(DigimonSpeciesBootstrap.HOWLING_BLASTER.fuel()),"same tank, refill and pulse timing");
        var tank=new FuelReserve(ice.fuel());tank.begin();for(int i=0;i<80;i++)check(tank.consume(),"full tank emits 80 ticks");
        tank.end();for(int i=0;i<159;i++)tank.tickRecharge();check(!tank.isReady(),"exhaustion cannot stutter-fire");
        tank.tickRecharge();check(tank.isReady(),"empty tank refills in eight seconds");
        var motion=DigimonSpeciesBootstrap.CONSTRICTION_MOTION;
        check(motion.fit(new AABB(-.3,0,-.3,.3,1.8,.3),.6F)!=null,"player fits");
        check(motion.fit(new AABB(-.7,0,-.7,.7,1,.7),.6F)!=null,"wide spider-sized body fits");
        check(motion.fit(new AABB(-1.4,0,-1.4,1.4,2.8,1.4),.6F)==null,"oversized adult selects breath");
        check(motion.fit(new AABB(-.2,0,-.2,.2,.5,.2),.6F)==null,"tiny prey does not waste a wrap");
        for(float radius:new float[]{27,33,39,47})for(float pitch:new float[]{22,26,34}) {
            var fit=new ConstrictionMotion.Fit(radius,pitch);check(Math.abs(motion.blends(fit).stream().mapToDouble(ConstrictionMotion.Blend::weight).sum()-1)<1e-6,"interpolation normalized");
            check(motion.root(fit,0,2,.6F).length()<1e-5&&motion.root(fit,120,2,.6F).length()<1e-5,"starts and finishes at actual feet");
            for(int t=0;t<120;t++)check(motion.root(fit,t+1,2,.6F).distanceTo(motion.root(fit,t,2,.6F))<1,"root stays below collision escape threshold");
        }
        var owner=fixture(0,.9,2.65);var target=fixture(2,.9,1.4);target.world=owner.world;
        place(target,new Vec3(0,0,6));
        check(owner.chooseAttack(target)==null,"approach a fitting cow for ready Constriction instead of spending Ice Blast first");
        check(owner.positioningAttacks(target).equals(List.of(wrap)),"navigation commits to the same wrap approach");
        owner.target=target;
        check(owner.minimumAttackSpacing()==0,"ready wrap does not retreat to Ice Blast spacing");
        var exhausted = new FuelReserve(ice.fuel());exhausted.begin();
        for(int i=0;i<80;i++)exhausted.consume();exhausted.end();
        field(owner,DigimonEntity.class,"attackFuel",new HashMap<>(Map.of(ice.id(),exhausted)));
        check(!owner.isAttackReady(ice)&&owner.isAttackReady(wrap),"Ice Blast exhaustion leaves Constriction available");
        check(owner.positioningAttacks(target).equals(List.of(wrap)),"continue closing for wrap after Ice Blast empties");
        owner.nav.reachable=false;owner.tickCount+=24;
        check(owner.chooseAttack(target)==null,"empty tank cannot fire when wrap path is blocked");
        for(int i=0;i<160;i++)exhausted.tickRecharge();
        check(owner.chooseAttack(target)==ice,"unreachable wrap falls back to recharged Ice Blast");
        owner.nav.reachable=true;
        owner.tickCount=81;
        check(owner.chooseAttack(target)==null,"retry resumes a reachable wrap after backoff");
        owner.tickCount+=ConstrictionMotion.APPROACH_TICKS;
        check(owner.chooseAttack(target)==ice,"bounded wrap approach falls back to Ice Blast instead of chasing forever");
        owner.tickCount+=ConstrictionMotion.APPROACH_RETRY_TICKS;
        place(target,new Vec3(0,0,2));
        check(owner.chooseAttack(target)==wrap,"ready close target uses occasional wrap");
        target.effects.put(DCEffects.CONSTRICTION_RESISTANCE,new MobEffectInstance(DCEffects.CONSTRICTION_RESISTANCE,200));
        check(owner.chooseAttack(target)==ice,"resistant target falls back to fueled Ice Blast");target.effects.clear();
        var cast=ConstrictionSession.prepare(owner,target,wrap);check(cast!=null,"flat clear terrain permits cast");
        for(int t=0;t<120;t++) {
            check(cast.tick(t),"cast progresses at tick "+t);
            check(target.hasEffect(DCEffects.CONSTRICTED)==(t>=40&&t<80),"exact two-second hold");
        }
        check(owner.pulses==4,"four evenly spaced damage pulses");
        check(Math.abs(owner.damage-14*.22*4)<.001,"12.32 raw champion damage over two seconds");
        check(target.hasEffect(DCEffects.CONSTRICTION_RESISTANCE)&&target.hasEffect(DCEffects.FROST_RESISTANCE),"capture grants shared anti-chain resistance");
        check(ConstrictionSession.prepare(owner,target,wrap)==null,"another ready caster cannot immediately recapture");
        check(wrap.cooldownTicks()==240&&wrap.cooldownTicks()>wrap.durationTicks(),"twelve-second cooldown outlasts the complete performance");
        check(ConstrictionMotion.CAPTURE_TICK+ConstrictionMotion.RESISTANCE_TICKS<=wrap.cooldownTicks(),"resistance does not add a hidden wait beyond the caster cooldown");
        owner=fixture(0,.9,2.65);target=fixture(2,.9,1.4);target.world=owner.world;
        cast=ConstrictionSession.prepare(owner,target,wrap);
        for(int t=0;t<120;t++) {
            if(t<40)place(target,new Vec3((t+1)*.04,0,2));
            check(cast.tick(t),"ordinary cow walking is tracked through approach at tick "+t);
        }
        check(owner.pulses==4&&!target.hasEffect(DCEffects.CONSTRICTED),"moving cow is captured, damaged and released");
        check(owner.position().distanceTo(new Vec3(1.6,0,0))<.001,"root and target use the same translated anchor");
        owner=fixture(0,.9,2.65);target=fixture(2,.9,1.4);target.world=owner.world;
        cast=ConstrictionSession.prepare(owner,target,wrap);place(target,new Vec3(.5,0,2));
        check(!cast.tick(0),"fast displacement still evades the wind-up");
        for(String reason:List.of("escape","death","cleansed","wall","interruption")) {
            owner=fixture(0,.9,2.65);target=fixture(2,.9,1.4);target.world=owner.world;
            cast=ConstrictionSession.prepare(owner,target,wrap);check(cast!=null,"fresh test cast");
            for(int t=0;t<=40;t++)check(cast.tick(t),"capture before interruption");
            switch(reason) {
                case "escape" -> place(target,new Vec3(0,0,3));
                case "death" -> owner.alive=false;
                case "cleansed" -> target.effects.remove(DCEffects.CONSTRICTED);
                case "wall" -> owner.world.wall=owner.getBoundingBox().inflate(.2);
                case "interruption" -> {cast.release();check(!target.hasEffect(DCEffects.CONSTRICTED),"explicit interrupt releases");continue;}
            }
            check(!cast.tick(41),reason+" stops the cast");cast.release();check(!target.hasEffect(DCEffects.CONSTRICTED),reason+" releases immediately");
        }
        owner=fixture(0,.9,2.65);target=fixture(2,.9,1.4);target.world=owner.world;
        owner.world.wall=new AABB(-4,0,1,4,5,1.2);
        check(ConstrictionSession.prepare(owner,target,wrap)==null,"authored body cannot sweep through a wall");
        owner=fixture(0,.9,2.65);
        field(owner,DigimonEntity.class,"activeAttack",wrap);
        var move=new com.digicube.entity.ai.DigimonMoveControl(owner);
        move.setWantedPosition(8,0,0,1);
        owner.setYRot(0);
        move.tick(); // Vanilla executes this AFTER customServerAiStep's root/yaw update.
        check(owner.getYRot()==0&&owner.zza==0,"queued walking must not rotate or propel an active wrap");
        integratedControllerChecks(wrap,ice);
        recoveryAndElevationChecks(wrap,ice);
        System.out.println("Seadramon: fuel, target fit, AI fallback, full cast, periodic damage, resistance and interruption checks passed.");
    }
    private static void recoveryAndElevationChecks(DigimonAttack wrap,DigimonAttack ice)throws Exception {
        var owner=fixture(0,.9,2.65);var cow=fixture(6,.9,1.4);cow.world=owner.world;owner.target=cow;
        field(owner,DigimonEntity.class,"cooldownUntil",new HashMap<>(Map.of(wrap.id(),40)));
        var empty=new FuelReserve(ice.fuel());empty.begin();for(int t=0;t<80;t++)empty.consume();empty.end();
        field(owner,DigimonEntity.class,"attackFuel",new HashMap<>(Map.of(ice.id(),empty)));
        var goal=new com.digicube.entity.ai.DigimonAttackGoal(owner,1);goal.tick();
        check(owner.nav.getPath()!=null,"prepare the next approach while cooldown and fuel recover");
        check(!owner.isAttacking(),"preparation must not bypass the cooldown");
        var arrival=owner.nav.getPath().getEntityPosAtNode(owner,owner.nav.getPath().getNodeCount()-1);
        place(owner,arrival);owner.setYRot(AttackGeometry.yaw(arrival,cow.position()));
        for(int t=1;t<40;t++) {owner.tickCount=t;goal.tick();check(!owner.isAttacking(),"no early capture during recovery at tick "+t);}
        owner.tickCount=40;goal.tick();
        check(owner.isAttacking(),"prepared wrap starts on the exact cooldown-ready tick");

        owner=fixture(0,.9,2.65);cow=fixture(10,.9,1.4);cow.world=owner.world;owner.target=cow;
        owner.world.plateau=new AABB(-100,0,3,100,3,100);place(cow,new Vec3(0,3,10));
        field(owner,DigimonEntity.class,"cooldownUntil",new HashMap<>(Map.of(wrap.id(),500)));
        var path=com.digicube.entity.ai.DigimonCombatPosition.find(owner,cow);
        check(path!=null,"find a firing stance against a target three blocks above");
        var end=path.getEntityPosAtNode(owner,path.getNodeCount()-1);
        check(owner.canAttackFrom(ice,cow,end),"elevated stance must have a real unobstructed mouth path");
        owner.nav.reachable=false;
        check(com.digicube.entity.ai.DigimonCombatPosition.find(owner,cow)==null,"unreachable upper platform cannot be used as a firing stance");

        for(double height:new double[]{1,3,-1,-3}) {
            owner=fixture(0,.9,2.65);cow=fixture(10,.9,1.4);cow.world=owner.world;owner.target=cow;
            if(height>0) {owner.world.plateau=new AABB(-1,0,9,1,height,11);place(cow,new Vec3(0,height,10));}
            else {owner.world.plateau=new AABB(-100,0,-100,100,-height,3);place(owner,new Vec3(0,-height,0));}
            check(owner.canAttackFrom(ice,cow,owner.position()),"authored breath can aim across Y difference "+height);
            check(owner.chooseAttack(cow)==ice&&owner.nav.requests==0,"clear shot across Y difference fires without waiting for a wrap path: "+height);
        }

        owner=fixture(0,.9,2.65);cow=fixture(10,.9,1.4);cow.world=owner.world;owner.target=cow;
        owner.world.plateau=new AABB(-100,0,3,100,3,100);place(cow,new Vec3(0,3,10));
        owner.world.wall=new AABB(-1,0,1.5,1,5,2.1);
        field(owner,DigimonEntity.class,"cooldownUntil",new HashMap<>(Map.of(wrap.id(),500)));
        empty=new FuelReserve(ice.fuel());empty.begin();for(int t=0;t<80;t++)empty.consume();empty.end();
        field(owner,DigimonEntity.class,"attackFuel",new HashMap<>(Map.of(ice.id(),empty)));
        check(!owner.canAttackFrom(ice,cow,owner.position()),"cover blocks the current breath stance");
        goal=new com.digicube.entity.ai.DigimonAttackGoal(owner,1);goal.tick();path=owner.nav.getPath();
        check(path!=null&&!owner.isAttacking(),"reposition to an elevated firing stance during fuel recharge");
        place(owner,path.getEntityPosAtNode(owner,path.getNodeCount()-1));owner.tickCount++;
        goal.tick();check(owner.nav.getPath()==null,"hold the useful recovered stance instead of orbiting it");
        for(int t=0;t<160;t++)empty.tickRecharge();
        check(owner.chooseAttack(cow)==ice,"refilled tank can fire immediately from its prepared stance");

        owner=fixture(0,.9,2.65);cow=fixture(6,.9,1.4);cow.world=owner.world;owner.target=cow;
        check(owner.tickConstrictionApproach(cow,1),"start stair-approach fixture");
        path=owner.nav.getPath();end=path.getEntityPosAtNode(owner,path.getNodeCount()-1);
        place(owner,end.add(0,.1,0));owner.airborne=true;owner.tickCount++;
        check(owner.tickConstrictionApproach(cow,1)&&owner.nav.getPath()==path&&!owner.combatControlsLocked(),"do not cancel or lock movement while landing at the wrap stance");
        place(owner,end);owner.airborne=false;owner.tickCount++;owner.setYRot(AttackGeometry.yaw(end,cow.position()));
        goal=new com.digicube.entity.ai.DigimonAttackGoal(owner,1);goal.tick();
        check(owner.isAttacking(),"start the wrap promptly once stair navigation lands");
    }
    private static void integratedControllerChecks(DigimonAttack wrap,DigimonAttack ice)throws Exception {
        var owner=fixture(0,.9,2.65);var cow=fixture(2,.9,1.4);cow.world=owner.world;owner.target=cow;
        owner.setYRot(170);
        var goal=new com.digicube.entity.ai.DigimonAttackGoal(owner,1);
        int turning=0;
        while(!owner.isAttacking()&&turning<25) {
            float before=owner.getYRot();
            owner.getMoveControl().setWantedPosition(8,0,0,1);
            owner.getJumpControl().jump();
            goal.tick();
            owner.combatTick();
            check(!owner.jumping(),"queued navigation jump is discarded during alignment");
            check(Math.abs(net.minecraft.util.Mth.wrapDegrees(owner.getYRot()-before))<=20.01,"alignment turns smoothly at no more than twenty degrees per tick");
            owner.tickCount++;turning++;
        }
        check(owner.isAttacking()&&turning<=9,"real goal starts even a backward-facing wrap within half a second");
        float yaw=owner.getYRot();
        int remaining=0;
        while(owner.isAttacking()&&remaining<120) {
            if(remaining<39)place(cow,new Vec3((remaining+1)*.04,0,2));
            owner.getMoveControl().setWantedPosition(-8,0,0,1);
            owner.getLookControl().setLookAt(-8,3,0);
            owner.getJumpControl().jump();
            goal.tick();
            owner.combatTick();
            if(owner.isAttacking())check(owner.getYRot()==yaw&&owner.yHeadRot==yaw&&owner.yBodyRot==yaw
                    &&owner.zza==0&&owner.xxa==0&&!owner.jumping(),"real post-AI controls preserve cast heading/root and discard jumps");
            owner.tickCount++;remaining++;
        }
        check(!owner.isAttacking()&&remaining==119&&owner.pulses==4,"actual entity timeline completes one six-second cast and four pulses");
        check(!cow.hasEffect(DCEffects.CONSTRICTED)&&!owner.isAttackReady(wrap),"actual release and cooldown survive the controller chain");
        check(owner.position().distanceTo(new Vec3(1.56,0,0))<.001,"walking cow stays aligned through the actual timeline and controls");

        owner=fixture(0,.9,2.65);cow=fixture(6,.9,1.4);cow.world=owner.world;owner.target=cow;
        check(owner.tickConstrictionApproach(cow,1),"distant cow gets a rehearsed approach");
        var path=owner.nav.getPath();check(path!=null,"approach submits a real endpoint");
        int requests=owner.nav.requests;
        for(int t=1;t<20;t++) {owner.tickCount=t;check(owner.tickConstrictionApproach(cow,1),"retain approach while making progress");}
        check(owner.nav.requests==requests&&owner.nav.submissions==1,"stable prey does not trigger an orbit of alternating paths");
        Vec3 end=path.getEntityPosAtNode(owner,path.getNodeCount()-1);place(owner,end);owner.tickCount=20;
        goal=new com.digicube.entity.ai.DigimonAttackGoal(owner,1);
        for(int t=0;t<22&&!owner.isAttacking();t++){goal.tick();owner.combatTick();owner.tickCount++;}
        check(owner.isAttacking(),"quantized arrival used by navigation really starts the cast");

        owner=fixture(0,.9,2.65);cow=fixture(2,.9,1.4);cow.world=owner.world;owner.target=cow;
        owner.world.wall=new AABB(-1,0,-3,1,3,-2);
        check(!owner.canAttackFrom(wrap,cow,owner.position()),"a wall behind the head blocks the authored tail sweep");
        check(owner.tickConstrictionApproach(cow,1),"blocked current stance finds a usable alternate wrap angle");
        path=owner.nav.getPath();check(path!=null,"alternate stance has a reachable path");
        end=path.getEntityPosAtNode(owner,path.getNodeCount()-1);
        check(owner.canAttackFrom(wrap,cow,end),"alternate endpoint passes the complete casting preflight");

        owner=fixture(0,.9,2.65);cow=fixture(6,.9,1.4);cow.world=owner.world;owner.target=cow;
        owner.nav.reachable=false;
        check(owner.chooseAttack(cow)==ice,"no reachable wrap stance immediately selects breath");
        requests=owner.nav.requests;
        for(int t=1;t<40;t++){owner.tickCount=t;check(owner.chooseAttack(cow)==ice,"failed stance has a bounded retry backoff");}
        check(owner.nav.requests==requests,"blocked wrap does not search every tick");
        var tank=new FuelReserve(ice.fuel());tank.begin();for(int t=0;t<80;t++)tank.consume();tank.end();
        field(owner,DigimonEntity.class,"attackFuel",new HashMap<>(Map.of(ice.id(),tank)));
        goal=new com.digicube.entity.ai.DigimonAttackGoal(owner,1);goal.tick();
        check(owner.nav.getPath()==null&&!owner.isAttacking(),"blocked wrap plus exhausted tank waits instead of circling");
        owner.nav.reachable=true;owner.tickCount=40;
        check(owner.tickConstrictionApproach(cow,1),"wrap retries a reachable stance while Ice Blast is still empty");

        owner=fixture(0,.9,2.65);cow=fixture(2,.9,1.4);cow.world=owner.world;owner.target=cow;
        owner.world.wall=new AABB(-20,3,-20,20,5,20);
        check(!owner.canAttackFrom(wrap,cow,owner.position()),"clear eye line does not authorize a body-obstructed wrap");
        check(owner.chooseAttack(cow)==ice,"terrain-invalid full body uses breath instead of repeatedly failing cast start");
        owner=fixture(0,.9,2.65);cow=fixture(2,.9,1.4);cow.world=owner.world;
        owner.setYRot(90);check(owner.tickConstrictionApproach(cow,1),"begin alignment before terrain changes");
        owner.world.wall=new AABB(-20,3,-20,20,5,20);owner.setYRot(0);owner.tickCount++;
        check(owner.chooseAttack(cow)==ice,"obstruction appearing during alignment falls back immediately at commit");
    }
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    private static <T>T allocate(Class<T> type)throws Exception {
        var u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);
        return type.cast(u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));
    }
    private static void field(Object o,Class<?> type,String name,Object value)throws Exception {var f=type.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void place(Fixture f,Vec3 p) {
        try {field(f,Entity.class,"position",p);field(f,Entity.class,"blockPosition",BlockPos.containing(p));f.setBoundingBox(new AABB(p.x-f.width/2,p.y,p.z-f.width/2,p.x+f.width/2,p.y+f.height,p.z+f.width/2));}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static Fixture fixture(double z,double width,double height)throws Exception {
        var f=allocate(Fixture.class);f.setId(z==0?1:2);f.width=width;f.height=height;f.alive=true;f.effects=new HashMap<>();f.world=allocate(FlatWorld.class);f.nav=allocate(Navigation.class);f.nav.reachable=true;
        field(f,DigimonEntity.class,"attackFuel",new HashMap<>());field(f,DigimonEntity.class,"cooldownUntil",new HashMap<>());
        field(f,Entity.class,"dimensions",EntityDimensions.fixed((float)width,(float)height));place(f,new Vec3(0,0,z));f.setDeltaMovement(Vec3.ZERO);
        var builder=new net.minecraft.network.syncher.SynchedEntityData.Builder(f);
        defineEntityData(builder,"DATA_SHARED_FLAGS_ID",(byte)0);
        defineEntityData(builder,"DATA_AIR_SUPPLY_ID",300);
        defineEntityData(builder,"DATA_CUSTOM_NAME_VISIBLE",false);
        defineEntityData(builder,"DATA_CUSTOM_NAME",Optional.empty());
        defineEntityData(builder,"DATA_SILENT",false);
        defineEntityData(builder,"DATA_NO_GRAVITY",false);
        defineEntityData(builder,"DATA_POSE",Pose.STANDING);
        defineEntityData(builder,"DATA_TICKS_FROZEN",0);
        f.defineSynchedData(builder);
        field(f,Entity.class,"entityData",builder.build());
        field(f,Entity.class,"random",net.minecraft.util.RandomSource.create(0));
        field(f,DigimonEntity.class,"iceExposure",new IceExposure());
        field(f,Mob.class,"lookControl",new com.digicube.entity.ai.DigimonLookControl(f));
        field(f,Mob.class,"moveControl",new com.digicube.entity.ai.DigimonMoveControl(f));
        field(f,Mob.class,"jumpControl",new com.digicube.entity.ai.DigimonJumpControl(f));
        field(f,Mob.class,"bodyRotationControl",new net.minecraft.world.entity.ai.control.BodyRotationControl(f));
        return f;
    }
    @SuppressWarnings("unchecked")
    private static <T> void defineEntityData(net.minecraft.network.syncher.SynchedEntityData.Builder builder,String name,T value)throws Exception {
        var accessor=Entity.class.getDeclaredField(name);accessor.setAccessible(true);
        builder.define((net.minecraft.network.syncher.EntityDataAccessor<T>)accessor.get(null),value);
    }
    private static final class Navigation extends AmphibiousPathNavigation {
        boolean reachable;int requests,submissions;
        private Navigation(){super(null,null);}@Override public void stop(){path=null;}
        @Override public boolean moveTo(net.minecraft.world.level.pathfinder.Path p,double speed){path=p;submissions++;return reachable;}
        @Override public net.minecraft.world.level.pathfinder.Path createPath(BlockPos p,int accuracy) {
            requests++;
            return new net.minecraft.world.level.pathfinder.Path(List.of(new net.minecraft.world.level.pathfinder.Node(p.getX(),p.getY(),p.getZ())),p,reachable);
        }
        @Override public net.minecraft.world.level.pathfinder.Path createPath(Entity target,int accuracy) {
            BlockPos p=BlockPos.containing(target.position());
            return createPath(p,accuracy);
        }
    }
    private static final class FlatWorld extends ServerLevel {
        AABB wall,plateau;
        private FlatWorld(){super(null,null,null,null,null,null,false,0,List.of(),false);}
        @Override public BlockState getBlockState(BlockPos p){return p.getY()<0?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState();}
        @Override public boolean noCollision(Entity entity,AABB box){return !getBlockCollisions(entity,box).iterator().hasNext();}
        @Override public Iterable<VoxelShape> getBlockCollisions(Entity entity,AABB box){
            var floor=new AABB(-100,-1,-100,100,0,100);var hits=new ArrayList<VoxelShape>();
            if(floor.intersects(box))hits.add(Shapes.create(floor));
            if(plateau!=null&&plateau.intersects(box))hits.add(Shapes.create(plateau));
            if(wall!=null&&wall.intersects(box))hits.add(Shapes.create(wall));return hits;
        }
        @Override public BlockHitResult clip(ClipContext c) {
            Vec3 hit=wall==null?null:wall.clip(c.getFrom(),c.getTo()).orElse(null);
            Vec3 ledge=plateau==null?null:plateau.clip(c.getFrom(),c.getTo()).orElse(null);
            if(ledge!=null&&(hit==null||ledge.distanceToSqr(c.getFrom())<hit.distanceToSqr(c.getFrom())))hit=ledge;
            if(hit==null)hit=new AABB(-100,-1,-100,100,0,100).clip(c.getFrom(),c.getTo()).orElse(null);
            return hit==null?BlockHitResult.miss(c.getTo(),Direction.UP,BlockPos.containing(c.getTo())):new BlockHitResult(hit,Direction.UP,BlockPos.containing(hit),false);
        }
    }
    private static final class Fixture extends DigimonEntity {
        FlatWorld world;Navigation nav;double width,height,damage;int pulses;boolean alive,blocked,airborne;
        Map<Holder<MobEffect>,MobEffectInstance> effects;
        LivingEntity target;
        private Fixture(){super(null,null);}
        @Override public Level level(){return world;}
        @Override public Optional<DigimonSpecies> getSpecies(){return Optional.of(DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon")));}
        @Override public DigimonBody getBody(){return getSpecies().orElseThrow().body();}
        @Override public com.digicube.entity.ai.FlightPhase getFlightPhase(){return com.digicube.entity.ai.FlightPhase.GROUNDED;}
        @Override public LivingEntity getTarget(){return target;}
        @Override public boolean isAlive(){return alive;}
        @Override public boolean onGround(){return !airborne;}
        @Override public boolean isDescending(){return false;}
        @Override public net.minecraft.world.item.ItemStack getMainHandItem(){return net.minecraft.world.item.ItemStack.EMPTY;}
        @Override public boolean isInWater(){return false;}
        @Override public boolean isPassenger(){return false;}
        @Override public boolean isVehicle(){return false;}
        @Override public Entity getFirstPassenger(){return null;}
        @Override public boolean canAttack(LivingEntity e){return e!=this;}
        @Override public boolean isAllyOf(Entity e){return false;}
        @Override public float getHealth(){return alive?40:0;}
        @Override public double getAttributeValue(Holder<Attribute> a){return 14;}
        @Override public PathNavigation getNavigation(){return nav;}
        @Override public boolean hasEffect(Holder<MobEffect> e){return effects.containsKey(e);}
        @Override public boolean canBeAffected(MobEffectInstance e){return true;}
        @Override public boolean addEffect(MobEffectInstance e,Entity source){effects.put(e.getEffect(),e);return true;}
        @Override public boolean removeEffect(Holder<MobEffect> e){return effects.remove(e)!=null;}
        @Override public void move(MoverType type,Vec3 delta){if(!blocked)place(this,position().add(delta));}
        @Override DigimonAttack activeAttackDefinition(){return super.activeAttackDefinition()==null?DigimonSpeciesBootstrap.CONSTRICTION:super.activeAttackDefinition();}
        void combatTick(){super.customServerAiStep(world);getMoveControl().tick();getLookControl().tick();getJumpControl().tick();super.tickHeadTurn(0);}
        boolean jumping(){return jumping;}
        @Override boolean damageWithActiveAttack(LivingEntity target){pulses++;damage+=14*DigimonSpeciesBootstrap.CONSTRICTION.power();return true;}
    }
}
