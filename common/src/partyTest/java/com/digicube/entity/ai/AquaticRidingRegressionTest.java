package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonBody;
import com.digicube.digimon.DigimonLocomotion;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

/** Runs the actual ridden input, attachment and fluid movement code without creating a world. */
public final class AquaticRidingRegressionTest {
    private AquaticRidingRegressionTest() {}
    public static void run() throws Exception {
        var mob=allocate(MountFixture.class);var player=allocate(PlayerFixture.class);
        var species=DigimonSpeciesRegistry.getOrThrow(Constants.id("ikkakumon"));
        mob.body=species.body();mob.locomotion=species.locomotion();mob.setDeltaMovement(Vec3.ZERO);
        // the float line is a share of the body's height, which the skipped constructor would have set
        var size=Entity.class.getDeclaredField("dimensions");size.setAccessible(true);size.set(mob,species.body().dimensions());
        var mount=mob.body.mount().orElseThrow();
        // Ikkakumon is a sea mount: seated on the mane, it turns to the view in water, surges, floats head out, holds
        // the surface, steers on the strafe keys and barrel-rolls on a double tap of the jump key.
        check(!mount.standing() && mount.waterTurnRate()>0 && mount.waterSprint()>1,"Ikkakumon is a seated sea mount that surges");
        check(mount.sea().floatLine()<DigimonBody.Sea.DEFAULT.floatLine() && mount.sea().surfaceDive()>0 && mount.sea().roll()>0,
                "it floats high enough to keep its rider dry, holds the surface and rolls");
        for (int yaw : new int[]{0,90,180,270}) {
            mob.setYRot(yaw);
            Vec3 actual=mob.attachment(player);
            Vec3 expected=mount.seat().yRot((float)Math.toRadians(-yaw));
            check(actual.distanceTo(expected)<1e-6,"the seat stays at the attachment through every facing");
        }
        mob.setYRot(0);player.zza=1;player.xxa=0;
        check(mount.ownPace() && mount.turnToTravel(),"on land it moves under its rider at its own pace and walks along its own length");
        var gait=species.locomotion().groundGait();
        // on ordinary ground a body the move control feeds once goes speed / (1 - 0.6 * 0.91) a tick
        check(Math.abs(gait.fullSpeed(mob.body.modelScale())-species.baseSpeed()*species.locomotion().walkSpeed()/(1-.6*.91))<2e-3 && gait.directional(),
                "the planted walk covers the ground at exactly the pace it walks, forwards, backwards and sideways");
        // afloat, a touch under its float line (above it the surface settles the body back down)
        mob.water=true;mob.fluid=species.body().dimensions().height()*mount.sea().floatLine()+.01;
        for(int tick=0;tick<160;tick++)mob.riddenWater(player);
        check(Math.abs(mob.lastMove.length()-species.locomotion().swimSpeed())<.001,
                "ridden water travel reaches the configured cruise speed exactly once");
        player.setSprinting(true);
        // the surge builds over sprint_build ticks as the gallop does on land, never all at once
        double cruise=mob.lastMove.length(),top=species.locomotion().swimSpeed()*mount.waterSprint(),before=cruise;
        int build=Math.round(mount.sprintBuild());boolean rising=true;
        for(int tick=1;tick<=build;tick++){mob.riddenWater(player);double now=mob.lastMove.length();rising&=now>before;before=now;
            if(tick==build/4)check(now<cruise+(top-cruise)*.5,"a quarter into the build the surge is not yet half way");}
        check(rising,"through the build the surge gains every tick");
        for(int tick=0;tick<160;tick++)mob.riddenWater(player);
        check(Math.abs(mob.lastMove.length()-top)<.002,"the sprint key surges it");
        player.setSprinting(false);
        // At the float line the surface holds it: a rider's glance up or down does not lift or dip it; a steep look
        // down dives, and a surge looking up breaches.
        float dive=mount.sea().surfaceDive();
        player.setXRot(-60);check(mob.input(player).y==0,"at the surface, looking up does not climb out of the water");
        player.setXRot(dive*.7F);var level=mob.input(player);
        check(level.y==0 && Math.abs(level.z-1)<1e-6,"at the surface, glancing down it swims on level at its full push");
        player.setXRot(dive+15);check(mob.input(player).y<0,"looking down past surface_dive at the surface dives");
        player.setSprinting(true);player.setXRot(-dive-15);check(mob.input(player).y>0,"a surge looking up past it breaches");
        player.setSprinting(false);
        // Under the surface it follows the view.
        double afloat=mob.fluid;mob.fluid=afloat+species.body().dimensions().height()*.5;
        player.setXRot(-60);check(mob.input(player).y>0,"under water, look up and forward climbs");
        player.setXRot(60);check(mob.input(player).y<0,"under water, look down and forward dives");
        mob.fluid=afloat;
        player.setXRot(0);player.zza=0;player.xxa=1;var steer=mob.input(player);
        check(Math.abs(steer.x)<1e-9 && steer.z>.5,"a strafe key steers it round, swimming on through the turn: no sideways slide");
        player.xxa=0;player.zza=0;check(mob.input(player).lengthSqr()==0,"looking alone does not accelerate at the float line");
        for(int tick=0;tick<80;tick++)mob.riddenWater(player);
        check(mob.lastMove.length()<.001,"released controls let water momentum settle");

        // Seadramon is the sea mount: in water it turns to the view, surges and casts both its attacks; on land it walks at
        // its own pace and sprints.
        var serpent=DigimonSpeciesRegistry.getOrThrow(Constants.id("seadramon")).body().mount().orElseThrow();
        check(serpent.waterTurnRate()>serpent.turnRate() && serpent.turnRate()>0,"the serpent turns faster in water than on land, and snaps in neither");
        check(serpent.waterSprint()>1 && serpent.sprint()>1 && serpent.ownPace(),"it surges in the water and sprints on land, and walks under a rider exactly as it does alone");
        check(serpent.sea().floatLine()<.5F && serpent.sea().surfaceDive()>0 && serpent.turnToTravel(),
                "it floats with its back at the surface and its neck out, holds the surface past a glance down, and swims along its own length");
        var casts=serpent.riderAttacks();
        check(casts.size()==2 && casts.get(0).attack().getPath().equals("ice_blast") && casts.get(0).aim()==com.digicube.digimon.RiderAttack.Aim.STREAM
                && casts.get(0).input()==com.digicube.digimon.RiderAttack.Input.HOLD,"the quick button breathes Ice Blast for as long as it is held");
        check(casts.get(1).attack().getPath().equals("constriction") && casts.get(1).aim()==com.digicube.digimon.RiderAttack.Aim.GRAB
                && casts.get(1).input()==com.digicube.digimon.RiderAttack.Input.TAP && casts.get(1).cone()>0 && casts.get(1).reach()>3.2,
                "one press of the special button sends it at the outlined prey, from beyond the wrap's own range");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static <T>T allocate(Class<T> type)throws Exception {
        var unsafe=Class.forName("sun.misc.Unsafe");var field=unsafe.getDeclaredField("theUnsafe");field.setAccessible(true);
        return com.digicube.entity.EntityFixtureDefaults.initialize(type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(field.get(null),type)));
    }
    private static final class PlayerFixture extends ServerPlayer {
        private PlayerFixture(){super(null,null,null,null);}
        @Override public Vec3 getVehicleAttachmentPoint(Entity vehicle){return Avatar.DEFAULT_VEHICLE_ATTACHMENT;}
        private boolean sprinting;
        @Override public boolean isSprinting(){return sprinting;}
        @Override public void setSprinting(boolean sprinting){this.sprinting=sprinting;}
    }
    private static final class MountFixture extends DigimonEntity {
        DigimonBody body;DigimonLocomotion locomotion;boolean water;double fluid;Vec3 lastMove;
        private MountFixture(){super(null,null);}
        @Override public DigimonBody getBody(){return body;}
        @Override public DigimonLocomotion getLocomotion(){return locomotion;}
        @Override public boolean isInWater(){return water;}
        @Override public double getFluidHeight(TagKey<Fluid> tag){return water?fluid:0;}
        @Override public void move(MoverType type,Vec3 delta){lastMove=delta;}
        Vec3 attachment(Entity player){return getPassengerAttachmentPoint(player,EntityDimensions.fixed(2.8F,2.9F),1);}
        Vec3 input(ServerPlayer player){return getRiddenInput(player,Vec3.ZERO);}
        float riddenSpeed(ServerPlayer player){return getRiddenSpeed(player);}
        void riddenWater(ServerPlayer player){buildGallop(player);setSpeed(riddenSpeed(player));travelInWater(input(player),0,false,0);}
    }
}
