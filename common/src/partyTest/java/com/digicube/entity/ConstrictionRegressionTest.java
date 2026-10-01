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

/** The wrap's strike, coil, hold and release, and the AI's choice of it, run on the real session with flat-world fixtures. */
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
        var wrap=DigimonSpeciesBootstrap.CONSTRICTION;
        var ice=species.attacks().stream().filter(a->a.id().getPath().equals("ice_blast")).findFirst().orElseThrow();
        check(species.attacks().equals(List.of(wrap,ice)),"only the two attacks, the wrap first");
        var hitParts=species.body().hitParts();
        check(hitParts.size()==9&&hitParts.get(0).offset().z()<0&&hitParts.get(8).offset().z()<-8.5,"the serpent body carries nine hit parts trailing behind the head");
        for(int i=1;i<hitParts.size();i++)check(hitParts.get(i).offset().z()<hitParts.get(i-1).offset().z()&&hitParts.get(i).offset().y()<=hitParts.get(i-1).offset().y(),"parts descend from the neck to the tail in order");
        var tail=HitParts.place(hitParts.get(8),new Vec3(10,5,10),90);
        check(Math.abs(tail.getCenter().x-(10+8.8))<1e-6&&Math.abs(tail.getCenter().z-10)<1e-6&&Math.abs(tail.minY-5)<1e-9,"a part follows the body yaw like an authored attack point");
        check(DigimonPart.idFor(1234,3)<0&&DigimonPart.idFor(1234,3)!=DigimonPart.idFor(1234,4)&&DigimonPart.idFor(1234,3)!=DigimonPart.idFor(1235,3),"part ids are negative and unique per parent and index");
        check(species.locomotion().canSwim()&&species.locomotion().swimSpeed()>.6,"fast aquatic navigation");
        shapeChecks(species.body());
        strikeAndHoldChecks(wrap);
        aiChecks(wrap,ice);
        System.out.println("Seadramon: coil fit, strike, capture, squeezes, release, interruption, drawing out and AI checks passed.");
    }

    /** The coil fits what the body can go round once with its neck and tail free, from a silverfish to a spider. */
    private static void shapeChecks(DigimonBody body) {
        var coil=body.serpent().coil();
        check(coil!=null&&coil.girth()>.7&&coil.loops()>=2,"Seadramon's sheet carries its coil");
        double widest=ConstrictionCoil.widest(body);
        check(widest>1.4&&widest<1.8,"the widest prey its body goes round: past a spider, short of a Garurumon ("+widest+")");
        for(double[] prey:new double[][]{{.4,.3},{.4,.5},{.4,.7},{.6,1.8},{.9,1.4},{1.,2.6},{1.4,.9},{.6,2.9},{1.2,.85}}) {
            var shape=ConstrictionCoil.fit(prey[0],prey[1],body);
            check(shape!=null,"a body "+prey[0]+" wide and "+prey[1]+" tall is wrapped");
            double circle=2*Math.PI*(shape.hug()+coil.girth()/2);
            check(shape.loops()>=ConstrictionCoil.LEAST_LOOPS&&shape.loops()<=coil.loops()+1e-9,"between once round and the sheet's most loops");
            check(shape.loops()*circle+coil.neck()+coil.tail()<=body.length()-1+1e-6,"the loops, neck and tail fit in the body");
            check(shape.bottom()>=coil.girth()/2-1e-9,"the bottom loop rests on the floor, never under it");
            check(Math.abs(shape.top()-shape.bottom()-coil.girth()*ConstrictionCoil.STACK*shape.loops())<1e-9,"each loop a girth under the one it goes round");
            check(shape.hug()>=prey[0]/2,"the loops press on the prey's sides");
        }
        check(ConstrictionCoil.fit(.4,.7,body).loops()<ConstrictionCoil.fit(.6,1.8,body).loops(),"a taller prey gets more loops");
        for(double[] prey:new double[][]{{1.8,2.1},{2.1,2.75},{2.25,3.7},{2.8,2.9},{4,4}})
            check(ConstrictionCoil.fit(prey[0],prey[1],body)==null,"a body "+prey[0]+" wide is too big to go round");
        check(ConstrictionCoil.fit(.6,body.dimensions().height()+1.5,body)==null,"a prey far taller than itself is refused");
        // the timeline
        check(ConstrictionCoil.clipTime(3,ConstrictionCoil.NOT_TAKEN)==3&&ConstrictionCoil.clipTime(10,ConstrictionCoil.NOT_TAKEN)==ConstrictionCoil.STRIKE_POSE,"the clip holds its lunge while the strike flies");
        check(ConstrictionCoil.clipTime(12,5)==ConstrictionCoil.BITE+7,"then runs on the capture's clock");
        check(ConstrictionCoil.squeeze(ConstrictionCoil.FIRST_SQUEEZE)==1&&ConstrictionCoil.squeeze(ConstrictionCoil.FIRST_SQUEEZE-ConstrictionCoil.SQUEEZE_RISE-1)==0,"a squeeze peaks on its own tick");
        check(ConstrictionCoil.onCoil(0,0)==0&&ConstrictionCoil.onCoil(ConstrictionCoil.COIL_TICKS,1)==1&&ConstrictionCoil.onCoil(ConstrictionCoil.AFTER_CAPTURE,0)==0,"the coil closes by its ticks and opens again by the end");
        check(ConstrictionCoil.onCoil(4,0)>ConstrictionCoil.onCoil(4,1),"the front of the body closes first");
        check(ConstrictionCoil.RELEASE>ConstrictionCoil.FIRST_SQUEEZE+(ConstrictionCoil.SQUEEZES-1)*ConstrictionCoil.INTERVAL,"every squeeze lands before the release");
        check(DigimonSpeciesBootstrap.CONSTRICTION.durationTicks()==ConstrictionCoil.STRIKE_TICKS+ConstrictionCoil.AFTER_CAPTURE,"the move lasts the longest strike and the hold after it");
        var ring=ConstrictionCoil.ring(Vec3.ZERO,ConstrictionCoil.fit(.9,1.4,body),body);
        for(var box:ring)check(box.minY>0&&Math.hypot(box.getCenter().x,box.getCenter().z)>.45,"the loops' line lies clear of the floor and outside the prey");
    }

    private static void strikeAndHoldChecks(DigimonAttack wrap)throws Exception {
        var owner=fixture(0,.9,2.65);var cow=fixture(4,.9,1.4);cow.world=owner.world;
        check(ConstrictionSession.whyIneligible(owner,cow,wrap)==null,"a cow may be wrapped");
        check(ConstrictionSession.whyNotFrom(owner,cow,wrap,owner.position(),5)==null,"from four blocks a strike goes");
        check("out of reach".equals(ConstrictionSession.whyNotFrom(owner,cow,wrap,owner.position(),2.5)),"out of reach names its gate");
        cow.effects.put(DCEffects.CONSTRICTION_RESISTANCE,new MobEffectInstance(DCEffects.CONSTRICTION_RESISTANCE,200));
        check("prey hold-resistant".equals(ConstrictionSession.whyIneligible(owner,cow,wrap)),"hold-resistant prey is refused and named");
        cow.effects.clear();
        var golemon=fixture(4,2.1,2.75);golemon.world=owner.world;
        check("prey too big to go round".equals(ConstrictionSession.whyIneligible(owner,golemon,wrap))&&ConstrictionSession.strike(owner,golemon,wrap,5)==null,"a Golemon is too big to go round");
        var hopping=fixture(3,.4,.5);hopping.world=owner.world;place(hopping,new Vec3(0,.9,3));hopping.airborne=true;
        check(ConstrictionSession.whyNotFrom(owner,hopping,wrap,owner.position(),5)==null,"a rabbit in mid-hop is snatched");
        place(hopping,new Vec3(0,2.5,3));
        check("prey out of reach in the air".equals(ConstrictionSession.whyIneligible(owner,hopping,wrap)),"a flier well off the ground is not");
        owner.world.wall=new AABB(-2,0,1.8,2,4,2);
        check("no line to prey".equals(ConstrictionSession.whyNotFrom(owner,cow,wrap,owner.position(),5)),"a wall between names its gate");
        owner.world.wall=null;

        // strike, capture, four squeezes, release
        owner=fixture(0,.9,2.65);cow=fixture(4,.9,1.4);cow.world=owner.world;
        var session=ConstrictionSession.strike(owner,cow,wrap,5);check(session!=null,"a strike from four blocks");
        int tick=0;
        while(!session.captured()&&tick<=ConstrictionCoil.STRIKE_TICKS) check(session.tick(tick++)==ConstrictionSession.Status.GOING,"the strike flies at tick "+tick);
        check(session.captured()&&session.captureTick()<=8,"a cow four blocks off is taken within eight ticks ("+session.captureTick()+")");
        var shape=ConstrictionCoil.fit(cow.getBoundingBox(),owner.getBody());
        double stand=owner.position().subtract(cow.position()).horizontalDistance();
        check(Math.abs(stand-ConstrictionCoil.headDistance(shape,owner.getBody()))<ConstrictionCoil.CONTACT+.05,"the head stands beside its prey ("+stand+")");
        check(owner.wrapCaptureTick()==session.captureTick()&&owner.wrapCenter().distanceTo(cow.position())<1e-6&&owner.wrapSize().x()==.9F,"the coil is sent to every client");
        int capture=session.captureTick();
        for(int t=capture;t<capture+ConstrictionCoil.AFTER_CAPTURE;t++) {
            var status=session.tick(t);
            check(status==ConstrictionSession.Status.GOING,"the hold goes on at "+(t-capture));
            check(cow.hasEffect(DCEffects.CONSTRICTED)==(t-capture<ConstrictionCoil.RELEASE),"held exactly until the release, at "+(t-capture));
        }
        check(session.tick(capture+ConstrictionCoil.AFTER_CAPTURE)==ConstrictionSession.Status.DONE,"the move ends once the body is unwound");
        check(owner.pulses==ConstrictionCoil.SQUEEZES,"four squeezes ("+owner.pulses+")");
        check(Math.abs(wrap.power()-.08F)<1e-6&&ConstrictionCoil.CRUSH_SHARE==.06F,"each squeeze is power .08 and 6 % of the prey's full health");
        check(cow.hasEffect(DCEffects.CONSTRICTION_RESISTANCE)&&cow.hasEffect(DCEffects.FROST_RESISTANCE),"the capture grants the shared anti-chain resistance");
        check(ConstrictionSession.strike(owner,cow,wrap,5)==null,"no one wraps it again at once");
        check(wrap.cooldownTicks()==200&&ConstrictionCoil.RESISTANCE_TICKS<=wrap.cooldownTicks(),"resistance never outlasts the caster's own cooldown");

        // a frozen prey stays iced through the hold and a tail after it
        owner=fixture(0,.9,2.65);cow=fixture(3,.9,1.4);cow.world=owner.world;freeze(cow);
        session=ConstrictionSession.strike(owner,cow,wrap,5);
        for(tick=0;!session.captured()&&tick<=ConstrictionCoil.STRIKE_TICKS;tick++)session.tick(tick);
        check(cow.effects.get(DCEffects.FROZEN).getDuration()==ConstrictionCoil.RELEASE+ConstrictionCoil.FROZEN_TAIL_TICKS,"wrapping frozen prey re-ices it through the hold and a tail");

        // prey walking off during the strike is still taken; one that dashes away is missed
        owner=fixture(0,.9,2.65);cow=fixture(4,.9,1.4);cow.world=owner.world;
        session=ConstrictionSession.strike(owner,cow,wrap,5);
        for(tick=0;!session.captured()&&tick<=ConstrictionCoil.STRIKE_TICKS;tick++) {
            place(cow,cow.position().add(.2,0,.1));
            check(session.tick(tick)==ConstrictionSession.Status.GOING,"the strike turns after a walking prey at "+tick);
        }
        check(session.captured(),"a walking cow is taken");
        owner=fixture(0,.9,2.65);cow=fixture(4,.9,1.4);cow.world=owner.world;
        session=ConstrictionSession.strike(owner,cow,wrap,5);
        ConstrictionSession.Status status=ConstrictionSession.Status.GOING;
        for(tick=0;status==ConstrictionSession.Status.GOING&&tick<=ConstrictionCoil.STRIKE_TICKS+1;tick++) {
            place(cow,cow.position().add(0,0,1.2));
            status=session.tick(tick);
        }
        check(status==ConstrictionSession.Status.BROKEN&&!session.captured()&&!cow.hasEffect(DCEffects.CONSTRICTED),"a dash out of the strike's reach makes it miss ("+session.interruption()+")");

        // a dead or cleansed prey is let go at once and the body unwinds from there; a caster gone breaks the move off
        for(String reason:List.of("death","cleansed","caster")) {
            owner=fixture(0,.9,2.65);cow=fixture(3,.9,1.4);cow.world=owner.world;
            session=ConstrictionSession.strike(owner,cow,wrap,5);
            for(tick=0;!session.captured()&&tick<=ConstrictionCoil.STRIKE_TICKS;tick++)session.tick(tick);
            check(session.captured(),reason+": taken");
            for(int t=0;t<5;t++)session.tick(tick++);
            switch(reason) {
                case "death" -> cow.alive=false;
                case "cleansed" -> cow.effects.remove(DCEffects.CONSTRICTED);
                case "caster" -> owner.alive=false;
            }
            if(reason.equals("caster")) {
                check(session.tick(tick)==ConstrictionSession.Status.BROKEN,"a caster gone breaks the move off");
                session.release();
                check(!cow.hasEffect(DCEffects.CONSTRICTED),"a caster gone lets the prey go");
                continue;
            }
            check(session.tick(tick)==ConstrictionSession.Status.GOING&&!cow.hasEffect(DCEffects.CONSTRICTED),reason+" lets the prey go at once");
            check(owner.wrapCaptureTick()==tick-ConstrictionCoil.RELEASE,reason+": every client unwinds the body from here");
            int left=0;
            while(session.tick(++tick)==ConstrictionSession.Status.GOING&&left<100)left++;
            check(left==ConstrictionCoil.UNWIND_TICKS-1,reason+": the move ends once unwound ("+left+")");
        }

        // a cow with its back to a wall is drawn out into the open for the coil
        owner=fixture(0,.9,2.65);cow=fixture(3,.9,1.4);cow.world=owner.world;
        owner.world.wall=new AABB(-3,0,3.46,3,3,4.5);
        check(ConstrictionSession.whyNotFrom(owner,cow,wrap,owner.position(),5)==null,"a wall behind the prey still lets the strike go");
        session=ConstrictionSession.strike(owner,cow,wrap,5);
        for(tick=0;!session.captured()&&tick<=ConstrictionCoil.STRIKE_TICKS;tick++)session.tick(tick);
        for(int t=0;t<8;t++)session.tick(tick++);
        var center=owner.wrapCenter();
        check(center.z<3-.1&&center.distanceTo(new Vec3(0,0,3))<=ConstrictionCoil.DRAW_OUT+1e-6&&cow.position().distanceTo(center)<1e-6,"the prey is drawn out from the wall to the coil's middle");
        for(var box:ConstrictionCoil.ring(center,shape,owner.getBody()))check(!box.intersects(owner.world.wall),"and the loops clear the wall");
        // one in a corridor a block wide, where no coil goes round it however far it is drawn out, is not struck at
        owner=fixture(0,.9,2.65);var boxed=fixture(3,.9,1.4);boxed.world=owner.world;
        owner.world.wall=new AABB(.5,0,1,1.5,3,6);owner.world.plateau=new AABB(-1.5,0,1,-.5,3,6);
        check(String.valueOf(ConstrictionSession.whyNotFrom(owner,boxed,wrap,owner.position(),5)).startsWith("no room to coil round the prey")
                &&ConstrictionSession.strike(owner,boxed,wrap,5)==null,"prey in a corridor with no room for the loops is not struck at");
    }

    private static void aiChecks(DigimonAttack wrap,DigimonAttack ice)throws Exception {
        // Cold prey within strike reach is wrapped at once; further off, the AI closes in for it
        var owner=fixture(0,.9,2.65);var cow=fixture(4,.9,1.4);cow.world=owner.world;owner.target=cow;
        emptyTank(owner,ice);cold(cow);
        check(owner.chooseAttack(cow)==wrap,"Cold prey in strike reach is wrapped at once");
        place(cow,new Vec3(0,0,7));
        check(owner.chooseAttack(cow)==null&&owner.positioningAttacks(cow).equals(List.of(wrap)),"Cold prey beyond strike reach is closed in on");
        check(owner.minimumAttackSpacing()==0,"a wanted wrap never backs off to shooting spacing");
        check(owner.canAttackFrom(wrap,cow,new Vec3(0,0,3)),"a stance within reach is one to strike from");
        // a chase that never gets there gives way to the other moves for a while
        owner.tickCount+=100;owner.chooseAttack(cow);
        check(owner.positioningAttacks(cow).stream().noneMatch(a->a==wrap),"a chase that came to nothing gives way to the other moves");
        // the real timeline: strike, hold, release, cooldown
        owner=fixture(0,.9,2.65);cow=fixture(4,.9,1.4);cow.world=owner.world;owner.target=cow;
        emptyTank(owner,ice);cold(cow);
        owner.startAttack(wrap,cow);check(owner.isAttacking(),"the AI's strike starts");
        int ticks=0;
        while(owner.isAttacking()&&ticks<wrap.durationTicks()+5) {owner.combatTick();owner.tickCount++;ticks++;}
        check(!owner.isAttacking()&&owner.pulses==ConstrictionCoil.SQUEEZES&&!cow.hasEffect(DCEffects.CONSTRICTED),"the entity's timeline runs strike, hold and release ("+ticks+" ticks)");
        check(ticks<=ConstrictionCoil.STRIKE_TICKS+ConstrictionCoil.AFTER_CAPTURE+1&&!owner.isAttackReady(wrap),"it ends with the body unwound, and cools down");
        // a strike that misses costs a short retry, not the cooldown
        owner=fixture(0,.9,2.65);cow=fixture(4,.9,1.4);cow.world=owner.world;owner.target=cow;
        emptyTank(owner,ice);
        owner.startAttack(wrap,cow);place(cow,new Vec3(0,0,12));
        for(int t=0;t<ConstrictionCoil.STRIKE_TICKS+2&&owner.isAttacking();t++){owner.combatTick();owner.tickCount++;}
        check(!owner.isAttacking()&&owner.constrictionReadyIn(wrap)<=ConstrictionCoil.RETRY_TICKS,"a missed strike costs a short retry");
        // queued walking never turns or pushes a wrap under way
        owner=fixture(0,.9,2.65);
        field(owner,DigimonEntity.class,"activeAttack",wrap);
        var move=new com.digicube.entity.ai.DigimonMoveControl(owner);
        move.setWantedPosition(8,0,0,1);
        owner.setYRot(0);
        move.tick();
        check(owner.getYRot()==0&&owner.zza==0,"queued walking must not rotate or propel an active wrap");
    }

    private static void emptyTank(Fixture owner,DigimonAttack ice)throws Exception {
        var tank=new FuelReserve(ice.fuel());tank.begin();while(tank.consume()){}tank.end();
        field(owner,DigimonEntity.class,"attackFuel",new HashMap<>(Map.of(ice.id(),tank)));
    }
    private static void cold(Fixture f){f.effects.put(DCEffects.COLD,new MobEffectInstance(DCEffects.COLD,120));}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    private static void freeze(Fixture f){f.effects.put(DCEffects.FROZEN,new MobEffectInstance(DCEffects.FROZEN,30));}
    private static void thaw(Fixture f){f.effects.remove(DCEffects.FROZEN);}
    private static <T>T allocate(Class<T> type)throws Exception {
        var u=Class.forName("sun.misc.Unsafe");var f=u.getDeclaredField("theUnsafe");f.setAccessible(true);
        return com.digicube.entity.EntityFixtureDefaults.initialize(type.cast(u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type)));
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
        @Override public void broadcastEntityEvent(Entity entity,byte event){} // no chunk tracking offline
        @Override public void playSeededSound(Entity entity,double x,double y,double z,Holder<net.minecraft.sounds.SoundEvent> sound,net.minecraft.sounds.SoundSource source,float volume,float pitch,long seed){} // no players offline
        @Override public void playSeededSound(Entity entity,Entity from,Holder<net.minecraft.sounds.SoundEvent> sound,net.minecraft.sounds.SoundSource source,float volume,float pitch,long seed){}
        @Override public void playSound(Entity entity,double x,double y,double z,net.minecraft.sounds.SoundEvent sound,net.minecraft.sounds.SoundSource source,float volume,float pitch){}
        @Override public void playSound(Entity entity,double x,double y,double z,Holder<net.minecraft.sounds.SoundEvent> sound,net.minecraft.sounds.SoundSource source,float volume,float pitch){}
        @Override public <T extends net.minecraft.core.particles.ParticleOptions> int sendParticles(T particle,double x,double y,double z,int count,double dx,double dy,double dz,double speed){return 0;}
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();}
        @Override public <T extends Entity> List<T> getEntitiesOfClass(Class<T> type,AABB box,java.util.function.Predicate<? super T> predicate){return List.of();} // no shots in flight offline
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
        boolean harmless; // a cow: nothing a range holder needs to keep a band from
        @Override public boolean hasAttacks(){return !harmless&&super.hasAttacks();}
        @Override public boolean hasRangedAttack(){return !harmless&&super.hasRangedAttack();}
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
        @Override public MobEffectInstance getEffect(Holder<MobEffect> e){return effects.get(e);}
        @Override public boolean canBeAffected(MobEffectInstance e){return true;}
        @Override public boolean addEffect(MobEffectInstance e,Entity source){effects.put(e.getEffect(),e);return true;}
        @Override public boolean removeEffect(Holder<MobEffect> e){return effects.remove(e)!=null;}
        @Override public void move(MoverType type,Vec3 delta){if(!blocked)place(this,position().add(delta));}
        @Override public void setPos(double x,double y,double z){place(this,new Vec3(x,y,z));}
        @Override DigimonAttack activeAttackDefinition(){return super.activeAttackDefinition()==null?DigimonSpeciesBootstrap.CONSTRICTION:super.activeAttackDefinition();}
        void combatTick(){super.customServerAiStep(world);getMoveControl().tick();getLookControl().tick();getJumpControl().tick();super.tickHeadTurn(0);}
        boolean jumping(){return jumping;}
        @Override boolean damageWithActiveAttack(LivingEntity target){pulses++;damage+=14*DigimonSpeciesBootstrap.CONSTRICTION.power();return true;}
    }
}
