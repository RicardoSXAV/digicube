package com.digicube.entity.ai;

import com.digicube.Constants;
import com.digicube.digimon.DigimonAttack;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/** Real selection/range checks with a closing bear; no running game or world required. */
final class CombatPressureRegressionTest {
    static void run() throws Exception {
        // Plain Minecraft bootstrap has already frozen registries. Reproduce the
        // loader's registration window only in this isolated regression JVM.
        var effects = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT;
        set(effects, net.minecraft.core.MappedRegistry.class, "frozen", false);
        try {
            DCEffects.init();
        } finally {
            set(effects, net.minecraft.core.MappedRegistry.class, "frozen", true);
        }
        var mob = fixture(0, 1.9F, 2.8F);
        var bearSize = EntityTypes.POLAR_BEAR.getDimensions();
        var bear = fixture(3, bearSize.width(), bearSize.height());
        mob.target = bear;
        var bite = DigimonSpeciesBootstrap.FREEZE_FANG;
        var breath = DigimonSpeciesBootstrap.HOWLING_BLASTER;
        check(mob.chooseAttack(bear) == bite, "opening bite against an unmarked bear");
        bear.marked = true;
        // The bear walks into melee range after the bite and throughout the inhale.
        for (double distance : new double[]{2.69, 2.3, 1.9, 1.65}) {
            place(bear, distance, bearSize.width(), bearSize.height());
            check(mob.canAttackFrom(breath, bear, mob.position()), "real range gate accepts close bear at " + distance);
            check(mob.chooseAttack(bear) == breath, "convert the mark while the bear closes to " + distance);
        }
        bear.marked = false;
        bear.frozen = true;
        check(mob.chooseAttack(bear) == bite, "bite the frozen bear at body contact");
        bear.frozen = false;
        bear.marked = true;
        mob.blockBreath = true;
        check(mob.chooseAttack(bear) == bite, "blocked flame still permits a physically valid defensive bite");
        check(mob.positioningAttacks(bear).contains(bite), "navigation also permits the defensive bite stance");
        // A path pinned by the bear must not remain cached forever just because isDone is false.
        mob.navigationOnly = true;
        var goal = new DigimonAttackGoal(mob, 1.25);
        goal.start();
        set(goal, DigimonAttackGoal.class, "positionedTarget", bear.position());
        set(goal, DigimonAttackGoal.class, "positionedMoves", List.of(bite));
        for (int tick = 0; tick <= 32; tick++) {
            mob.tickCount = tick;
            goal.tick();
        }
        check(mob.nav.requests > 0, "reconsider a pinned, unfinished path within one second plus the repath interval");
        Constants.LOG.info("Combat pressure checks passed: closing bear, close breath, frozen bite, defensive fallback and pinned path expiry.");
    }

    private static Fixture fixture(double z, float width, float height) throws Exception {
        var mob = allocate(Fixture.class);
        mob.setId(z == 0 ? 1 : 2);
        mob.world = allocate(FloorLevel.class);
        mob.nav = allocate(Navigation.class);
        mob.look = new LookControl(mob);
        mob.randomSource = RandomSource.create(42);
        place(mob, z, width, height);
        set(mob, DigimonEntity.class, "attackFuel", new HashMap<>());
        set(mob, DigimonEntity.class, "cooldownUntil", new HashMap<>());
        return mob;
    }

    private static void place(Fixture mob, double z, float width, float height) throws Exception {
        set(mob, Entity.class, "position", new Vec3(0, 0, z));
        set(mob, Entity.class, "dimensions", EntityDimensions.scalable(width, height));
        mob.setBoundingBox(new AABB(-width / 2, 0, z - width / 2, width / 2, height, z + width / 2));
    }

    private static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type));
    }

    private static final class Fixture extends DigimonEntity {
        FloorLevel world;
        Navigation nav;
        LookControl look;
        RandomSource randomSource;
        LivingEntity target;
        boolean marked, frozen, blockBreath, navigationOnly;
        private Fixture() { super(null, null); }
        @Override public Level level() { return world; }
        @Override public PathNavigation getNavigation() { return nav; }
        @Override public LookControl getLookControl() { return look; }
        @Override public RandomSource getRandom() { return randomSource; }
        @Override public LivingEntity getTarget() { return target; }
        @Override public Optional<DigimonSpecies> getSpecies() {
            return navigationOnly ? Optional.empty() : DigimonSpeciesRegistry.get(Constants.id("garurumon"));
        }
        @Override public FlightPhase getFlightPhase() { return FlightPhase.GROUNDED; }
        @Override public boolean isVehicle() { return false; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean onGround() { return true; }
        @Override public boolean isDescending() { return false; }
        @Override public net.minecraft.world.item.ItemStack getMainHandItem() { return net.minecraft.world.item.ItemStack.EMPTY; }
        @Override public boolean canAttack(LivingEntity target) { return true; }
        @Override public boolean canBeAffected(MobEffectInstance effect) { return true; }
        @Override public boolean hasEffect(Holder<MobEffect> effect) {
            return effect == DCEffects.ICE_MARK && marked || effect == DCEffects.FROZEN && frozen;
        }
        @Override public boolean canAttackFrom(DigimonAttack move, LivingEntity target, Vec3 feet) {
            return !(blockBreath && move.fuel() != null) && super.canAttackFrom(move, target, feet);
        }
        @Override public DigimonAttack chooseAttack(LivingEntity target) {
            return navigationOnly ? null : super.chooseAttack(target);
        }
        @Override public List<DigimonAttack> positioningAttacks(LivingEntity target) {
            return navigationOnly ? List.of(DigimonSpeciesBootstrap.FREEZE_FANG) : super.positioningAttacks(target);
        }
        @Override public void setAggressive(boolean aggressive) {}
    }

    private static final class Navigation extends GroundPathNavigation {
        int requests;
        private Navigation() { super(null, null); }
        @Override public Path createPath(Entity target, int accuracy) {
            return new Path(List.of(new Node(0, 0, 3)), new BlockPos(0, 0, 3), true);
        }
        @Override public boolean isDone() { return false; }
        @Override public boolean moveTo(Entity target, double speed) { requests++; return true; }
    }

    private static final class FloorLevel extends ServerLevel {
        private FloorLevel() { super(null, null, null, null, null, null, false, 0, List.of(), false); }
        @Override public boolean noCollision(Entity entity, AABB box) { return true; }
        @Override public BlockHitResult clip(ClipContext context) {
            Vec3 from = context.getFrom(), to = context.getTo();
            if (from.y > 0 && to.y <= 0) {
                Vec3 hit = from.lerp(to, from.y / (from.y - to.y));
                return new BlockHitResult(hit, Direction.UP, BlockPos.ZERO, false);
            }
            return BlockHitResult.miss(to, Direction.UP, BlockPos.ZERO);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
