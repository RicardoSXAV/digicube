package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonLocomotion;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Exercise real controllers and Minecraft input scaling without launching a game or world. */
public final class LocomotionRegressionTest {
    private static final List<String> FAILURES = new ArrayList<>();

    private LocomotionRegressionTest() {}

    public static void main(String[] args) throws Exception {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            DigimonSpeciesBootstrap.registerBuiltIn();
            checkGroundAndWater();
            checkFollowWithoutAttacks();
            if (!FAILURES.isEmpty()) throw new AssertionError(String.join("; ", FAILURES));
            Constants.LOG.info("Locomotion regression checks passed: slow ground movement, stopping, fast swimming, pitch and following without attacks.");
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static void checkGroundAndWater() throws Exception {
        var mob = fixture(0);
        var control = new DigimonMoveControl(mob);
        control.setWantedPosition(0, 0, 20, mob.getLocomotion().walkSpeed());
        control.tick();
        // On ordinary ground Minecraft applies getSpeed() to its damped movement
        // input. Exercise that real vector calculation, starting from rest.
        mob.moveRelative(mob.getSpeed(), new Vec3(mob.xxa * .98, 0, mob.zza * .98));
        double firstStep = mob.getDeltaMovement().horizontalDistance();
        check(firstStep > .02 && firstStep < .04, "ground acceleration must be a slow walk, not speed squared: " + firstStep);
        check(firstStep > .003, "ground movement must exceed Minecraft's per-tick velocity cutoff");
        control.tick();
        mob.setDeltaMovement(Vec3.ZERO);
        mob.moveRelative(mob.getSpeed(), new Vec3(mob.xxa, 0, mob.zza));
        check(mob.getDeltaMovement().lengthSqr() == 0, "waiting controller must stop adding movement");

        mob.water = true;
        for (int tick = 0; tick < 120; tick++) {
            control.setWantedPosition(0, 0, 20, 1);
            control.tick();
            mob.waterTravel();
        }
        check(mob.lastMove.length() > .40 && mob.lastMove.length() < .47,
                "swimming retains fast independent cruise speed: " + mob.lastMove.length());
        mob.setXRot(-22);
        var look = new DigimonLookControl(mob);
        look.setLookAt(0, 8, 20);
        look.tick();
        check(mob.getXRot() == -22, "look controller must preserve diving pitch");
        mob.water = false;
        new DigimonLookControl(mob).tick();
        check(mob.getXRot() == 0, "ground look resets swimming pitch");
        mob.setYya(.5F);
        control.setWantedPosition(0, 0, 20, mob.getLocomotion().walkSpeed());
        control.tick();
        check(mob.yya == 0 && mob.zza == 1, "leaving water clears dive input and restores ground propulsion");
    }

    private static void checkFollowWithoutAttacks() throws Exception {
        var mob = fixture(0);
        mob.owner = fixture(6);
        mob.target = fixture(9);
        var follow = new FollowOwnerGoal(mob);
        boolean follows = follow.canUse();
        check(follows, "a species without attacks must follow even if a target goal selected an enemy");
        if (follows) {
            follow.start();
            follow.tick();
            check(mob.nav.requests == 1 && mob.nav.lastTarget == mob.owner,
                    "follow goal must issue the ground path toward its owner");
            check(mob.nav.lastSpeed == mob.getLocomotion().walkSpeed(), "following preserves slow land speed");
            check(follow.canContinueToUse(), "following continues while the owner is beyond stop distance");
            follow.stop();
            check(mob.nav.done, "stopping the follow goal releases its path");
        }
        mob.attacks = true;
        check(!new FollowOwnerGoal(mob).canUse(), "armed species still yield following to combat");
        mob.target = null;
        check(new FollowOwnerGoal(mob).canUse(), "armed species follow again without a target");
        mob.owner = fixture(1);
        check(!new FollowOwnerGoal(mob).canUse(), "nearby owners do not trigger following");
    }

    private static void check(boolean condition, String message) {
        if (!condition) FAILURES.add(message);
    }

    // These fixtures supply only flat air, identity and navigation commands. We
    // deliberately bypass world/entity constructors: no server, chunks, saves or
    // registry mutations are needed to run the actual control/input methods.
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        var singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    private static FixtureDigimon fixture(double z) throws Exception {
        var mob = allocate(FixtureDigimon.class);
        var species = DigimonSpeciesRegistry.getOrThrow(Constants.id("gomamon"));
        mob.locomotion = species.locomotion();
        mob.baseSpeed = species.baseSpeed();
        mob.air = allocate(AirLevel.class);
        mob.nav = allocate(FixtureNavigation.class);
        mob.look = new DigimonLookControl(mob);
        var position = Entity.class.getDeclaredField("position");
        position.setAccessible(true);
        position.set(mob, new Vec3(0, 0, z));
        mob.setDeltaMovement(Vec3.ZERO);
        return mob;
    }

    private static final class AirLevel extends ServerLevel {
        private AirLevel() { super(null, null, null, null, null, null, false, 0, List.of(), false); }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
    }

    private static final class FixtureNavigation extends AmphibiousPathNavigation {
        boolean done;
        int requests;
        Entity lastTarget;
        double lastSpeed;
        private FixtureNavigation() { super(null, null); }
        @Override public boolean isDone() { return done; }
        @Override public void stop() { done = true; }
        @Override public boolean moveTo(Entity target, double speed) {
            requests++;
            lastTarget = target;
            lastSpeed = speed;
            done = false;
            return true;
        }
    }

    private static final class FixtureDigimon extends DigimonEntity {
        DigimonLocomotion locomotion;
        float baseSpeed;
        boolean water;
        boolean attacks;
        boolean running;
        Level air;
        FixtureNavigation nav;
        LookControl look;
        LivingEntity owner;
        LivingEntity target;
        Vec3 lastMove;
        private FixtureDigimon() { super(null, null); }
        @Override public DigimonLocomotion getLocomotion() { return locomotion; }
        @Override public boolean isInWater() { return water; }
        @Override public boolean isSwimmingMovement() { return water; }
        @Override public double getAttributeValue(Holder<Attribute> attribute) { return baseSpeed; }
        @Override public float maxUpStep() { return .6F; }
        @Override public BlockPos blockPosition() { return BlockPos.ZERO; }
        @Override public Level level() { return air; }
        @Override public PathNavigation getNavigation() { return nav; }
        @Override public LookControl getLookControl() { return look; }
        @Override public LivingEntity getOwner() { return owner; }
        @Override public LivingEntity getTarget() { return target; }
        @Override public boolean hasAttacks() { return attacks; }
        @Override public boolean isAttacking() { return false; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isPassenger() { return false; }
        @Override public boolean isVehicle() { return false; }
        @Override public boolean isRunningToOwner() { return running; }
        @Override public void setRunningToOwner(boolean value) { running = value; }
        @Override public double getEyeY() { return getY() + .72; }
        @Override public void move(MoverType type, Vec3 delta) { lastMove = delta; }
        void waterTravel() { travelInWater(new Vec3(xxa * .98, yya * .98, zza * .98), 0, false, getY()); }
    }
}
