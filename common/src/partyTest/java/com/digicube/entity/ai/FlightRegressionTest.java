package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.*;
import com.digicube.entity.DigimonEntity;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/** Exercises real decisions and steering using virtual collision/path delivery, without running Minecraft. */
public final class FlightRegressionTest {
    private static DigimonLocomotion locomotion;
    private static Air world;
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args)throws Exception {
        try {
            SharedConstants.tryDetectVersion();Bootstrap.bootStrap();DigimonSpeciesBootstrap.registerBuiltIn();
            locomotion=DigimonSpeciesRegistry.getOrThrow(Constants.id("tentomon")).locomotion();
            reserve();decisions();steering();timeline();
            Constants.LOG.info("Flight regression passed: fuel, restart hysteresis, persistence, trigger gates, steering, transitions and descent.");
        }finally {Util.shutdownExecutors();}
    }
    private static void reserve() {
        var data=locomotion.flight();var tank=new FlightReserve(data);
        check(tank.ready() && tank.fraction()==1,"initial full tank");
        for(int i=0;i<data.capacityTicks()-data.landingReserveTicks();i++)tank.consume();
        check(tank.mustLand() && !tank.exhausted(),"reserve leaves powered landing time");
        for(int i=0;i<1000;i++)tank.consume();
        check(tank.charge()==0 && !tank.ready(),"bounded charge, no empty-tank takeoff");
        tank.landed();
        for(int i=0;i<59;i++)tank.rest();
        check(!tank.ready(),"mandatory rest prevents hopping");
        var copy=new FlightReserve(data);copy.restore(tank.charge(),tank.restRemaining());
        check(copy.charge()==tank.charge() && copy.restRemaining()==tank.restRemaining(),"recall/load keeps exact fuel and rest debt");
        for(int i=59;i<data.rechargeTicks();i++)copy.rest();
        check(Math.abs(copy.fraction()-1)<.00001 && copy.ready(),"full recharge matches configured time");
        copy.restore(data.capacityTicks()*.399,0);check(!copy.ready(),"restart threshold below 40%");
        copy.rest();check(copy.ready(),"restart after reaching threshold");
        copy.restore(Double.NaN,-7);check(copy.charge()==0 && copy.restRemaining()==0,"malformed save cannot mint fuel");
        copy.restore(-10,99999);check(copy.charge()==0 && copy.restRemaining()==data.restTicks(),"save clamps bounds");
    }
    private static void decisions()throws Exception {
        var mob=fixture(0);mob.owner=fixture(4);
        check(!new DigimonFlightGoal(mob).canUse(),"ordinary following stays bipedal");
        mob.owner=fixture(12);check(new DigimonFlightGoal(mob).canUse(),"distant owner triggers catch-up");
        mob.owner=fixture(6);mob.owner.sprint=true;check(new DigimonFlightGoal(mob).canUse(),"sprint catch-up starts earlier");
        mob.air.clear=false;check(!new DigimonFlightGoal(mob).canUse(),"full-volume ceiling clearance required");
        mob.air.clear=true;mob.air.loaded=false;check(!new DigimonFlightGoal(mob).canUse(),"no launch into unloaded space");
        mob.air.loaded=true;mob.water=true;check(!new DigimonFlightGoal(mob).canUse(),"water does not trigger air controls");
        mob.water=false;mob.leash=true;check(!new DigimonFlightGoal(mob).canUse(),"leash retains ground control");
        mob.leash=false;mob.reserve.restore(1,0);check(!new DigimonFlightGoal(mob).canUse(),"low stamina blocks voluntary flight");
        mob.reserve=new FlightReserve(locomotion.flight());mob.owner=null;mob.threat=fixture(3);
        check(new DigimonFlightGoal(mob).canUse(),"recent nearby attack allows escape without an owner");
        mob.ally=true;check(!new DigimonFlightGoal(mob).canUse(),"friendly damage does not trigger escape");
        mob.ally=false;mob.threat=null;mob.recover=true;mob.reserve.restore(0,0);mob.ground=false;
        check(new DigimonFlightGoal(mob).canUse(),"airborne reload can descend with an empty tank");
    }
    private static void steering()throws Exception {
        var mob=fixture(0);mob.phase=FlightPhase.FLYING;mob.ground=false;
        var control=new DigimonFlightMoveControl(mob);
        for(int i=0;i<100;i++) {control.setWantedPosition(0,0,20,1);control.tick();}
        check(mob.getDeltaMovement().length()>.36 && mob.getDeltaMovement().length()<=locomotion.flight().speed(),"flight speed applied once");
        double speed=mob.getDeltaMovement().length();control.tick();
        check(mob.getDeltaMovement().length()<speed,"waiting brakes rather than drifting forever");
        mob.reserve.restore(0,0);control.setWantedPosition(0,20,20,1);control.tick();
        check(mob.getDeltaMovement().y<=-.12,"exhaustion forces controlled descent even with an ascending waypoint");
        mob.travel(Vec3.ZERO);check(mob.lastMove.equals(mob.getDeltaMovement()),"flight uses real entity collision movement");
        check(mob.xxa==0 && mob.yya==0 && mob.zza==0,"flight clears ground inputs");
    }
    private static void timeline()throws Exception {
        var mob=fixture(0);mob.owner=fixture(12);var goal=new DigimonFlightGoal(mob);
        field(goal,"departure",Vec3.ZERO);field(goal,"previousPosition",Vec3.ZERO);
        mob.phase=FlightPhase.TAKEOFF;
        mob.clock=12;goal.tick();check(!mob.noGravity,"anticipation keeps feet grounded");
        mob.clock=13;goal.tick();check(mob.noGravity,"lift begins with wing deployment");
        mob.clock=32;goal.tick();check(mob.phase==FlightPhase.FLYING,"takeoff reaches hover at authored endpoint");
        mob.ground=false;mob.reserve.restore(48,0);mob.clock=33;goal.tick();
        check(mob.phase==FlightPhase.APPROACH,"low reserve requests a landing before depletion");
        mob.ground=true;mob.reserve.restore(0,0);goal.tick();
        check(mob.phase==FlightPhase.LANDING && !mob.noGravity,"touchdown precedes shell closure");
        mob.clock+=32;goal.tick();check(!goal.canContinueToUse(),"landing finishes at the idle endpoint");
    }
    private static void field(Object o,String name,Object value)throws Exception {
        Class<?> type=o.getClass();java.lang.reflect.Field f=null;
        while(type!=null) {try {f=type.getDeclaredField(name);break;}catch(NoSuchFieldException e){type=type.getSuperclass();}}
        if(f==null)throw new NoSuchFieldException(name);f.setAccessible(true);f.set(o,value);
    }
    private static <T>T allocate(Class<T> type)throws Exception {
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");var f=unsafe.getDeclaredField("theUnsafe");f.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(f.get(null),type));
    }
    private static Fixture fixture(double z)throws Exception {
        Fixture f=allocate(Fixture.class);f.reserve=new FlightReserve(locomotion.flight());f.phase=FlightPhase.GROUNDED;
        if(world==null) {world=allocate(Air.class);world.border=new WorldBorder();}
        f.air=world;f.air.clear=f.air.loaded=true;
        f.nav=allocate(Navigation.class);f.control=new DigimonFlightMoveControl(f);f.ground=true;
        field(f,"position",new Vec3(0,0,z));field(f,"dimensions",EntityDimensions.scalable(1.1F,1.5F));
        f.setBoundingBox(new AABB(-.55,0,z-.55,.55,1.5,z+.55));f.setDeltaMovement(Vec3.ZERO);
        return f;
    }
    private static final class Air extends ServerLevel {
        boolean clear,loaded;WorldBorder border;
        private Air(){super(null,null,null,null,null,null,false,0,List.of(),false);}
        @Override public boolean noCollision(Entity e,AABB box){return clear;}
        @Override public boolean hasChunk(int x,int z){return loaded;}
        @Override public WorldBorder getWorldBorder(){return border;}
        @Override public BlockState getBlockState(BlockPos pos){return Blocks.AIR.defaultBlockState();}
    }
    private static final class Navigation extends FlyingPathNavigation {
        private Navigation(){super(null,null);}
        @Override public boolean isDone(){return true;}
        @Override public void stop(){}
        @Override public boolean moveTo(double x,double y,double z,double speed){return true;}
    }
    private static final class Fixture extends DigimonEntity {
        FlightReserve reserve;FlightPhase phase;int clock,start,loop;Air air;Navigation nav;MoveControl<?> control;
        Fixture owner,threat;boolean ground,water,leash,ally,recover,sprint,noGravity;Vec3 lastMove;
        private Fixture(){super(null,null);}
        @Override public DigimonLocomotion getLocomotion(){return locomotion;}
        @Override public FlightReserve flightReserve(){return reserve;}
        @Override public FlightPhase getFlightPhase(){return phase;}
        @Override public void setFlightPhase(FlightPhase p){phase=p;start=clock;if(p==FlightPhase.FLYING)loop=clock;}
        @Override public float getFlightPhaseTime(float partial){return clock-start+partial;}
        @Override public float getFlightLoopTime(float partial){return clock-loop+partial;}
        @Override public boolean needsFlightLanding(){return recover;}
        @Override public boolean onGround(){return ground;}
        @Override public boolean isInWater(){return water;}
        @Override public boolean isInLava(){return false;}
        @Override public boolean isLeashed(){return leash;}
        @Override public boolean isNoAi(){return false;}
        @Override public boolean isAlive(){return true;}
        @Override public boolean isAttacking(){return false;}
        @Override public boolean isPassenger(){return false;}
        @Override public boolean isVehicle(){return false;}
        @Override public boolean isOnFire(){return false;}
        @Override public boolean isSpectator(){return false;}
        @Override public boolean isSprinting(){return sprint;}
        @Override public LivingEntity getOwner(){return owner;}
        @Override public LivingEntity getLastHurtByMob(){return threat;}
        @Override public int getLastHurtByMobTimestamp(){return tickCount;}
        @Override public LivingEntity getTarget(){return null;}
        @Override public boolean isAllyOf(Entity other){return ally;}
        @Override public float getHealth(){return 20;}
        @Override public double getAttributeValue(Holder<Attribute> a){return 20;}
        @Override public Level level(){return air;}
        @Override public PathNavigation getNavigation(){return nav;}
        @Override public MoveControl<?> getMoveControl(){return control;}
        @Override public void setNoGravity(boolean v){noGravity=v;}
        @Override public void move(MoverType t,Vec3 v){lastMove=v;}
    }
}
