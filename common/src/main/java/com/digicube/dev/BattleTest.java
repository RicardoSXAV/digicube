package com.digicube.dev;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
 * The developer panel's Battle Testing: two sides of wild Digimon staged in front of the player, set on each other,
 * fighting until one side is down, with their real stats. A side is up to {@link BattleRoster#MAX_KINDS} kinds, each
 * any number of one species at one level ({@link BattleRoster}). Nothing is healed or boosted, so the winner, the time
 * and the health left are calibration numbers. A side's fighters spare each other, and every fighter that can carry a
 * rider takes one on a right click ({@link DigimonEntity#battleSide}). One fight per player; starting another replaces
 * it. The survivors are removed a few seconds after the result.
 *
 * <p>The fight's readout travels to the player in {@link DevStatePayload} under {@link #BATTLE} every
 * {@link #SYNC_TICKS} ticks; an empty state means no fight.
 */
public final class BattleTest {
    public static final String BATTLE = "battle";
    public static final String SIDE_A = "a", SIDE_B = "b";
    /** Readout of a side: its kinds (species, level, count, alive) and the health of the whole side. */
    public static final String KINDS = "kinds", ALIVE = "alive", HEALTH = "health", MAX_HEALTH = "max_health";
    public static final String TICKS = "ticks", WINNER = "winner";

    /** Marks a staged fighter, so a leftover from a crashed or reloaded session can still be cleared. */
    private static final String FIGHTER_TAG = "digicube_battle_test";
    /** Blocks ahead of the player to the middle of the field, and from there to each front rank beyond half a body. */
    private static final double AHEAD = 9, APART = 3.5, GAP = 0.75;
    private static final int SETTLE_TICKS = 20, RESULT_TICKS = 100, SYNC_TICKS = 5, MAX_GROUND_STEP = 6;

    private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

    private BattleTest() {}

    private static final class Fight {
        /** Who staged it; a headless scenario's stand-in is never in the player list, so it is kept here too. */
        final ServerPlayer owner;
        final List<BattleRoster.Entry> kindsA, kindsB;
        final List<DigimonEntity> a = new ArrayList<>(), b = new ArrayList<>();
        int ticks, resultTicks;
        String winner = "";

        Fight(ServerPlayer owner, List<BattleRoster.Entry> kindsA, List<BattleRoster.Entry> kindsB) {
            this.owner = owner;
            this.kindsA = kindsA;
            this.kindsB = kindsB;
        }

        List<DigimonEntity> all() {
            List<DigimonEntity> all = new ArrayList<>(a);
            all.addAll(b);
            return all;
        }
    }

    public static String start(MinecraftServer server, ServerPlayer player, CompoundTag args) {
        List<BattleRoster.Entry> kindsA = BattleRoster.read(args.getListOrEmpty(DevActions.SIDE_A_ARG));
        List<BattleRoster.Entry> kindsB = BattleRoster.read(args.getListOrEmpty(DevActions.SIDE_B_ARG));
        if (kindsA.isEmpty() || kindsB.isEmpty()) return "Pick a fighter for both sides";
        for (BattleRoster.Entry entry : concat(kindsA, kindsB)) {
            DigimonSpecies species = DigimonSpeciesRegistry.resolve(entry.species()).orElse(null);
            if (species == null) return "Unknown Digimon " + entry.species();
            if (!species.canFight()) return species.name() + " has no attacks";
        }
        remove(player);
        ServerLevel level = player.level();
        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0, look.z);
        forward = forward.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 centre = player.position().add(forward.scale(AHEAD));

        Fight fight = new Fight(player, kindsA, kindsB);
        boolean staged = stage(level, fight.a, kindsA, 1, centre, right.scale(-1), forward, player.getY())
                && stage(level, fight.b, kindsB, 2, centre, right, forward, player.getY());
        if (!staged) {
            discard(fight);
            return "Could not create the fighters";
        }
        for (DigimonEntity fighter : fight.a) face(fighter, centre.add(right.scale(APART)));
        for (DigimonEntity fighter : fight.b) face(fighter, centre.subtract(right.scale(APART)));
        FIGHTS.put(player.getUUID(), fight);
        return describe(kindsA) + " vs " + describe(kindsB);
    }

    /** Spawns one side in ranks facing the middle, {@code outward} pointing away from it; false if a fighter could not be made. */
    private static boolean stage(ServerLevel level, List<DigimonEntity> fighters, List<BattleRoster.Entry> kinds, int side,
                                 Vec3 centre, Vec3 outward, Vec3 along, double playerY) {
        List<DigimonSpecies> bodies = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        double widest = 0;
        for (BattleRoster.Entry entry : kinds) {
            DigimonSpecies species = DigimonSpeciesRegistry.resolve(entry.species()).orElseThrow();
            widest = Math.max(widest, species.body().dimensions().width());
            for (int i = 0; i < entry.count(); i++) {
                bodies.add(species);
                levels.add(entry.level());
            }
        }
        double spacing = widest + GAP, front = APART + widest / 2;
        for (int i = 0; i < bodies.size(); i++) {
            double[] place = BattleRoster.place(i, bodies.size(), front, spacing);
            DigimonEntity fighter = spawn(level, bodies.get(i), levels.get(i), centre.add(outward.scale(place[0])).add(along.scale(place[1])), playerY);
            if (fighter == null) return false;
            fighter.joinBattleSide(side);
            fighters.add(fighter);
        }
        return true;
    }

    public static String clear(MinecraftServer server, ServerPlayer player, CompoundTag args) {
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
            Fight fight = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null && !fight.owner.hasDisconnected() && !fight.owner.isRemoved()) player = fight.owner;
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
        List<DigimonEntity> standingA = standing(fight.a), standingB = standing(fight.b);
        if (standingA.isEmpty() || standingB.isEmpty()) {
            // A double knockout goes to nobody.
            fight.winner = standingA.isEmpty() && standingB.isEmpty() ? "-" : standingA.isEmpty() ? SIDE_B : SIDE_A;
            fight.resultTicks = 1;
            Constants.LOG.info("[battle] {} after {} ticks: A {} ({}/{} standing, {}) vs B {} ({}/{} standing, {})",
                    fight.winner.equals("-") ? "double knockout" : "winner " + fight.winner.toUpperCase(Locale.ROOT), fight.ticks,
                    describe(fight.kindsA), standingA.size(), fight.a.size(), health(fight.a),
                    describe(fight.kindsB), standingB.size(), fight.b.size(), health(fight.b));
            return true;
        }
        if (fight.ticks++ < SETTLE_TICKS) {
            // Nobody strolls off before the bell.
            fight.all().forEach(fighter -> fighter.getNavigation().stop());
            return true;
        }
        // Keep everyone on the other side: a stray mob, a team-mate or the tamer's partner must not pull one away.
        aim(standingA, standingB);
        aim(standingB, standingA);
        return true;
    }

    /** Gives every riderless fighter without a standing enemy as its target the nearest one. */
    private static void aim(List<DigimonEntity> fighters, List<DigimonEntity> enemies) {
        for (DigimonEntity fighter : fighters) {
            if (fighter.rider() != null) continue; // the rider picks the fights
            LivingEntity target = fighter.getTarget();
            if (target instanceof DigimonEntity enemy && enemies.contains(enemy)) continue;
            DigimonEntity nearest = null;
            for (DigimonEntity enemy : enemies) {
                if (nearest == null || fighter.distanceToSqr(enemy) < fighter.distanceToSqr(nearest)) nearest = enemy;
            }
            fighter.setTarget(nearest);
        }
    }

    private static List<DigimonEntity> standing(List<DigimonEntity> side) {
        return side.stream().filter(fighter -> fighter.isAlive() && !fighter.isRemoved()).toList();
    }

    /** The fighters of side {@link #SIDE_A} or {@link #SIDE_B} in this player's fight, fallen ones included; empty without a fight. */
    public static List<DigimonEntity> fighters(ServerPlayer player, String side) {
        Fight fight = FIGHTS.get(player.getUUID());
        return fight == null ? List.of() : List.copyOf(side.equals(SIDE_A) ? fight.a : fight.b);
    }

    /** This player's fight's result: empty while it runs or without one, {@link #SIDE_A}, {@link #SIDE_B}, or "-" for a double knockout. */
    public static String winner(ServerPlayer player) {
        Fight fight = FIGHTS.get(player.getUUID());
        return fight == null ? "" : fight.winner;
    }

    /** The readout for this player: the running fight, or an empty tag. */
    public static CompoundTag state(ServerPlayer player) {
        Fight fight = FIGHTS.get(player.getUUID());
        return fight == null ? new CompoundTag() : state(fight);
    }

    private static CompoundTag state(Fight fight) {
        CompoundTag battle = new CompoundTag();
        battle.put(SIDE_A, side(fight.kindsA, fight.a));
        battle.put(SIDE_B, side(fight.kindsB, fight.b));
        battle.putInt(TICKS, Math.max(0, fight.ticks - SETTLE_TICKS));
        battle.putString(WINNER, fight.winner);
        CompoundTag state = new CompoundTag();
        state.put(BATTLE, battle);
        return state;
    }

    private static CompoundTag side(List<BattleRoster.Entry> kinds, List<DigimonEntity> fighters) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        int first = 0;
        float health = 0, max = 0;
        for (BattleRoster.Entry entry : kinds) {
            int alive = 0;
            for (DigimonEntity fighter : fighters.subList(first, Math.min(fighters.size(), first + entry.count()))) {
                boolean up = fighter.isAlive() && !fighter.isRemoved();
                if (up) alive++;
                health += up ? fighter.getHealth() : 0;
                max += fighter.getMaxHealth();
            }
            first += entry.count();
            CompoundTag kind = new CompoundTag();
            kind.putString(BattleRoster.SPECIES, DigimonSpeciesRegistry.resolve(entry.species()).map(species -> species.id().toString()).orElse(entry.species()));
            kind.putInt(BattleRoster.LEVEL, entry.level());
            kind.putInt(BattleRoster.COUNT, entry.count());
            kind.putInt(ALIVE, alive);
            list.add(kind);
        }
        tag.put(KINDS, list);
        tag.putFloat(HEALTH, health);
        tag.putFloat(MAX_HEALTH, max);
        return tag;
    }

    private static DigimonEntity spawn(ServerLevel level, DigimonSpecies species, int digimonLevel, Vec3 spot, double playerY) {
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(spot));
        // Under a roof or in a cave the heightmap is the surface far above: stay on the player's floor then.
        double y = Math.abs(ground.getY() - playerY) <= MAX_GROUND_STEP ? ground.getY() : playerY;
        DigimonEntity fighter = DigimonEntity.spawnWild(level, species, digimonLevel, new Vec3(spot.x, y, spot.z));
        if (fighter != null) fighter.addTag(FIGHTER_TAG);
        return fighter;
    }

    private static void face(DigimonEntity fighter, Vec3 toward) {
        float yaw = (float) (Math.toDegrees(Math.atan2(toward.z - fighter.getZ(), toward.x - fighter.getX())) - 90);
        fighter.setYRot(yaw);
        fighter.yBodyRot = fighter.yHeadRot = yaw;
    }

    private static int remove(ServerPlayer player) {
        Fight fight = FIGHTS.remove(player.getUUID());
        return fight == null ? 0 : discard(fight);
    }

    private static int discard(Fight fight) {
        int removed = 0;
        for (DigimonEntity fighter : fight.all()) {
            if (!fighter.isRemoved()) { fighter.discard(); removed++; }
        }
        return removed;
    }

    private static void send(ServerPlayer player, CompoundTag state) {
        Services.PLATFORM.sendToPlayer(player, new DevStatePayload(state, ""));
    }

    private static List<BattleRoster.Entry> concat(List<BattleRoster.Entry> a, List<BattleRoster.Entry> b) {
        List<BattleRoster.Entry> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    /** "10 Gotsumon Lv 20 + Golemon Lv 30". */
    private static String describe(List<BattleRoster.Entry> kinds) {
        List<String> parts = new ArrayList<>();
        for (BattleRoster.Entry entry : kinds) {
            String name = DigimonSpeciesRegistry.resolve(entry.species()).map(DigimonSpecies::name).orElse(entry.species());
            parts.add((entry.count() > 1 ? entry.count() + " " : "") + name + " Lv " + entry.level());
        }
        return String.join(" + ", parts);
    }

    private static String health(List<DigimonEntity> side) {
        float health = 0;
        for (DigimonEntity fighter : side) health += fighter.isAlive() ? fighter.getHealth() : 0;
        return String.format(Locale.ROOT, "%.1f hp", health);
    }
}
