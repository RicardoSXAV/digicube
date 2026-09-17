package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The developer panel's Battle Testing: two wild Digimon staged in front of the player, set on
 * each other, fighting to a knockout with their real stats. Nothing is healed or boosted, so the
 * winner, the time and the health left are calibration numbers. One fight per player; starting
 * another replaces it. The survivor is removed a few seconds after the result.
 *
 * <p>The fight's readout travels to the player in {@link DevStatePayload} under {@link #BATTLE}
 * every {@link #SYNC_TICKS} ticks; an empty state means no fight.
 */
public final class BattleTest {
    public static final String BATTLE = "battle";
    public static final String SIDE_A = "a", SIDE_B = "b";
    public static final String SPECIES = "species", LEVEL = "level", HEALTH = "health", MAX_HEALTH = "max_health";
    public static final String TICKS = "ticks", WINNER = "winner";

    /** Marks a staged fighter, so a leftover from a crashed or reloaded session can still be cleared. */
    private static final String FIGHTER_TAG = "digicube_battle_test";
    private static final double AHEAD = 9, APART = 4;
    private static final int SETTLE_TICKS = 20, RESULT_TICKS = 100, SYNC_TICKS = 5, MAX_GROUND_STEP = 6;

    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

    private BattleTest() {}

    private static final class Fight {
        final DigimonEntity a, b;
        int ticks, resultTicks;
        String winner = "";

        Fight(DigimonEntity a, DigimonEntity b) { this.a = a; this.b = b; }
    }

    static String start(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        DigimonSpecies speciesA = DigimonSpeciesRegistry.resolve(args.getStringOr(DevActions.SPECIES_A_ARG, "")).orElse(null);
        DigimonSpecies speciesB = DigimonSpeciesRegistry.resolve(args.getStringOr(DevActions.SPECIES_B_ARG, "")).orElse(null);
        if (speciesA == null || speciesB == null) return "Pick both fighters";
        if (!speciesA.canFight() || !speciesB.canFight()) return (speciesA.canFight() ? speciesB : speciesA).name() + " has no attacks";
        remove(player);
        ServerLevel level = player.level();
        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0, look.z);
        forward = forward.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 centre = player.position().add(forward.scale(AHEAD));
        DigimonEntity a = spawn(level, speciesA, args.getIntOr(DevActions.LEVEL_A_ARG, Progression.MIN_LEVEL), centre.subtract(right.scale(APART)), player.getY());
        DigimonEntity b = spawn(level, speciesB, args.getIntOr(DevActions.LEVEL_B_ARG, Progression.MIN_LEVEL), centre.add(right.scale(APART)), player.getY());
        if (a == null || b == null) {
            if (a != null) a.discard();
            if (b != null) b.discard();
            return "Could not create the fighters";
        }
        face(a, b);
        face(b, a);
        FIGHTS.put(player.getUUID(), new Fight(a, b));
        return speciesA.name() + " Lv " + a.getLevel() + " vs " + speciesB.name() + " Lv " + b.getLevel();
    }

    static String clear(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        int removed = remove(player);
        // Also sweep fighters that outlived their fight: a reloaded world, a crashed session.
        List<Entity> leftovers = new ArrayList<>();
        for (Entity entity : player.level().getAllEntities()) if (entity.entityTags().contains(FIGHTER_TAG)) leftovers.add(entity);
        leftovers.forEach(Entity::discard);
        removed += leftovers.size();
        return removed == 0 ? "No test fighters to remove" : "Removed " + removed + " test fighters";
    }

    /** Server tick hook, called by the loader module. */
    public static void tick(MinecraftServer server) {
        for (Iterator<Map.Entry<UUID, Fight>> it = FIGHTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Fight> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Fight fight = entry.getValue();
            if (player == null || !step(fight)) {
                discard(fight);
                it.remove();
                if (player != null) send(player, new CompoundTag());
            } else if (fight.ticks % SYNC_TICKS == 0 || fight.resultTicks == 1) {
                send(player, state(fight));
            }
        }
    }

    /** Advances one fight; false once it is over and its result has been shown long enough. */
    private static boolean step(Fight fight) {
        if (!fight.winner.isEmpty()) return ++fight.resultTicks <= RESULT_TICKS;
        boolean aDown = !fight.a.isAlive() || fight.a.isRemoved(), bDown = !fight.b.isAlive() || fight.b.isRemoved();
        if (aDown || bDown) {
            // A double knockout goes to nobody.
            fight.winner = aDown && bDown ? "-" : aDown ? SIDE_B : SIDE_A;
            fight.resultTicks = 1;
            Constants.LOG.info("[battle] {} after {} ticks: {} {}/{} vs {} {}/{}", fight.winner.equals("-") ? "double knockout" : "winner " + describe(fight.winner.equals(SIDE_A) ? fight.a : fight.b),
                    fight.ticks, describe(fight.a), health(fight.a), Math.round(fight.a.getMaxHealth()), describe(fight.b), health(fight.b), Math.round(fight.b.getMaxHealth()));
            return true;
        }
        if (fight.ticks++ < SETTLE_TICKS) {
            // Nobody strolls off before the bell.
            fight.a.getNavigation().stop();
            fight.b.getNavigation().stop();
            return true;
        }
        // Keep them on each other: a stray mob or the tamer's partner must not pull one away.
        if (fight.a.getTarget() != fight.b) fight.a.setTarget(fight.b);
        if (fight.b.getTarget() != fight.a) fight.b.setTarget(fight.a);
        return true;
    }

    /** The readout for this player: the running fight, or an empty tag. */
    public static CompoundTag state(ServerPlayer player) {
        Fight fight = FIGHTS.get(player.getUUID());
        return fight == null ? new CompoundTag() : state(fight);
    }

    private static CompoundTag state(Fight fight) {
        CompoundTag battle = new CompoundTag();
        battle.put(SIDE_A, side(fight.a));
        battle.put(SIDE_B, side(fight.b));
        battle.putInt(TICKS, Math.max(0, fight.ticks - SETTLE_TICKS));
        battle.putString(WINNER, fight.winner);
        CompoundTag state = new CompoundTag();
        state.put(BATTLE, battle);
        return state;
    }

    private static CompoundTag side(DigimonEntity fighter) {
        CompoundTag tag = new CompoundTag();
        tag.putString(SPECIES, fighter.getSpecies().map(species -> species.id().toString()).orElse(""));
        tag.putInt(LEVEL, fighter.getLevel());
        tag.putFloat(HEALTH, fighter.isAlive() ? fighter.getHealth() : 0);
        tag.putFloat(MAX_HEALTH, fighter.getMaxHealth());
        return tag;
    }

    private static DigimonEntity spawn(ServerLevel level, DigimonSpecies species, int digimonLevel, Vec3 spot, double playerY) {
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(spot));
        // Under a roof or in a cave the heightmap is the surface far above: stay on the player's floor then.
        double y = Math.abs(ground.getY() - playerY) <= MAX_GROUND_STEP ? ground.getY() : playerY;
        DigimonEntity fighter = DigimonEntity.spawnWild(level, species, Progression.clampLevel(digimonLevel), new Vec3(spot.x, y, spot.z));
        if (fighter != null) fighter.addTag(FIGHTER_TAG);
        return fighter;
    }

    private static void face(DigimonEntity fighter, DigimonEntity other) {
        float yaw = (float) (Math.toDegrees(Math.atan2(other.getZ() - fighter.getZ(), other.getX() - fighter.getX())) - 90);
        fighter.setYRot(yaw);
        fighter.yBodyRot = fighter.yHeadRot = yaw;
    }

    private static int remove(ServerPlayer player) {
        Fight fight = FIGHTS.remove(player.getUUID());
        return fight == null ? 0 : discard(fight);
    }

    private static int discard(Fight fight) {
        int removed = 0;
        for (DigimonEntity fighter : new DigimonEntity[]{fight.a, fight.b}) {
            if (!fighter.isRemoved()) { fighter.discard(); removed++; }
        }
        return removed;
    }

    private static void send(ServerPlayer player, CompoundTag state) {
        Services.PLATFORM.sendToPlayer(player, new DevStatePayload(state, ""));
    }

    private static String describe(DigimonEntity fighter) {
        return fighter.getSpecies().map(DigimonSpecies::name).orElse("?") + " Lv " + fighter.getLevel();
    }

    private static String health(DigimonEntity fighter) {
        return String.format(Locale.ROOT, "%.1f", fighter.isAlive() ? fighter.getHealth() : 0F);
    }
}
