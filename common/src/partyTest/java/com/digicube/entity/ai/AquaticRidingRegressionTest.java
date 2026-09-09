package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonBody;
import com.digicube.digimon.DigimonLocomotion;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/** Runs the actual ridden input, attachment and fluid movement code without creating a world. */
public final class AquaticRidingRegressionTest {
    private AquaticRidingRegressionTest() {}
    public static void run() throws Exception {
        var mob=allocate(MountFixture.class);var player=allocate(PlayerFixture.class);
        var species=DigimonSpeciesRegistry.getOrThrow(Constants.id("ikkakumon"));
        mob.body=species.body();mob.locomotion=species.locomotion();mob.setDeltaMovement(Vec3.ZERO);
        var mount=mob.body.mount().orElseThrow();
        for (int yaw : new int[]{0,90,180,270}) {
            mob.setYRot(yaw);
            Vec3 actual=mob.attachment(player).subtract(Avatar.DEFAULT_VEHICLE_ATTACHMENT);
            Vec3 expected=mount.seat().yRot((float)Math.toRadians(-yaw));
            check(actual.distanceTo(expected)<1e-6,"standing feet stay at the attachment through every facing");
        }
        mob.setYRot(0);player.zza=1;player.xxa=0;
        check(Math.abs(mob.riddenSpeed(player)-mount.speed())<1e-7,"land riding uses the mount pace");
        mob.water=true;
        for(int tick=0;tick<160;tick++)mob.riddenWater(player);
        check(Math.abs(mob.lastMove.length()-species.locomotion().swimSpeed())<.001,
                "ridden water travel reaches the configured cruise speed exactly once");
        player.setXRot(-60);check(mob.input(player).y>0,"look up and forward climbs");
        player.setXRot(60);check(mob.input(player).y<0,"look down and forward dives");
        player.zza=0;check(mob.input(player).lengthSqr()==0,"looking alone does not accelerate");
        for(int tick=0;tick<80;tick++)mob.riddenWater(player);
        check(mob.lastMove.length()<.001,"released controls let water momentum settle");
        mob.water=false;player.zza=1;
        check(mob.input(player).y==0 && Math.abs(mob.riddenSpeed(player)-mount.speed())<1e-7,
                "leaving water restores horizontal land input and speed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static <T>T allocate(Class<T> type)throws Exception {
        var unsafe=Class.forName("sun.misc.Unsafe");var field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
    }
    private static final class PlayerFixture extends ServerPlayer {
        private PlayerFixture(){super(null,null,null,null);}
        @Override public Vec3 getVehicleAttachmentPoint(Entity vehicle){return Avatar.DEFAULT_VEHICLE_ATTACHMENT;}
    }
    private static final class MountFixture extends DigimonEntity {
        DigimonBody body;DigimonLocomotion locomotion;boolean water;Vec3 lastMove;
        private MountFixture(){super(null,null);}
        @Override public DigimonBody getBody(){return body;}
        @Override public DigimonLocomotion getLocomotion(){return locomotion;}
        @Override public boolean isInWater(){return water;}
        @Override public void move(MoverType type,Vec3 delta){lastMove=delta;}
        Vec3 attachment(Entity player){return getPassengerAttachmentPoint(player,EntityDimensions.fixed(2.8F,2.75F),1);}
        Vec3 input(ServerPlayer player){return getRiddenInput(player,Vec3.ZERO);}
        float riddenSpeed(ServerPlayer player){return getRiddenSpeed(player);}
        void riddenWater(ServerPlayer player){setSpeed(riddenSpeed(player));travelInWater(input(player),0,false,0);}
    }
}
