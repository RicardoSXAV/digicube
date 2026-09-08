package com.digicube.command;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.entity.DigimonEntity;
import com.digicube.party.PartyManager;
import com.digicube.party.PartyMember;
import com.digicube.registry.DCEntityTypes;
import com.digicube.spawn.SpawnAttempt;
import com.digicube.spawn.WildSpawnSettings;
import com.digicube.spawn.WildSpawner;
import com.digicube.starter.StarterFlow;
import com.digicube.starter.StarterSavedData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * DigiCube's commands. Loader modules hand us their dispatcher; everything else is
 * plain Brigadier and stays here. Everything but {@code starter} needs operator rights.
 *
 * <pre>
 * /digicube starter                          reopen the first-partner choice while still eligible (everyone)
 * /digicube starter open [player]            operator: offer it even to a player who already has partners
 * /digicube starter reset &lt;player&gt;          operator: forget the choice so it can be made again
 * /digicube starter list                     operator: who chose what
 * /digicube spawn &lt;species&gt; [level]            wild Digimon at the caller's feet; it stays put
 * /digicube give &lt;species&gt; [player [level]]   partner for a player, default the caller
 * /digicube level &lt;targets&gt; &lt;level&gt;         set the level, reset XP, restore full health
 * /digicube xp &lt;targets&gt; &lt;amount&gt;           grant XP through the normal path, level-ups included
 * /digicube wild status|on|off|interval|cap|distance|try|clear|debug
 * </pre>
 */
public final class DigiCubeCommands {

    private DigiCubeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The root is open so /digicube starter works for everyone; a child cannot loosen
        // a parent's requirement, so each operator branch carries its own.
        dispatcher.register(Commands.literal("digicube")
                .then(Commands.literal("spawn")
                        .requires(operator())
                        .then(speciesArgument()
                                .executes(context -> spawn(context, Progression.MIN_LEVEL))
                                .then(levelArgument()
                                        .executes(context -> spawn(context, IntegerArgumentType.getInteger(context, "level"))))))
                .then(Commands.literal("give")
                        .requires(operator())
                        .then(speciesArgument()
                                .executes(context -> give(context, context.getSource().getPlayerOrException(), Progression.MIN_LEVEL))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> give(context, EntityArgument.getPlayer(context, "player"), Progression.MIN_LEVEL))
                                        .then(levelArgument()
                                                .executes(context -> give(context, EntityArgument.getPlayer(context, "player"),
                                                        IntegerArgumentType.getInteger(context, "level")))))))
                .then(Commands.literal("level")
                        .requires(operator())
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(levelArgument()
                                        .executes(context -> level(context.getSource(), EntityArgument.getEntities(context, "targets"),
                                                IntegerArgumentType.getInteger(context, "level"))))))
                .then(Commands.literal("xp")
                        .requires(operator())
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(context -> xp(context.getSource(), EntityArgument.getEntities(context, "targets"),
                                                IntegerArgumentType.getInteger(context, "amount"))))))
                .then(wild())
                .then(starter()));
    }

    private static Predicate<CommandSourceStack> operator() {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> starter() {
        return Commands.literal("starter")
                .executes(context -> starterSelf(context.getSource()))
                .then(Commands.literal("open")
                        .requires(operator())
                        .executes(context -> starterOpen(context.getSource(), context.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> starterOpen(context.getSource(), EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("reset")
                        .requires(operator())
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> starterReset(context.getSource(), EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("list")
                        .requires(operator())
                        .executes(context -> starterList(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> wild() {
        return Commands.literal("wild")
                .requires(operator())
                .then(Commands.literal("status").executes(context -> wildStatus(context.getSource())))
                .then(Commands.literal("on").executes(context -> wildEnabled(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> wildEnabled(context.getSource(), false)))
                .then(Commands.literal("interval")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(WildSpawnSettings.MIN_INTERVAL_TICKS))
                                .executes(context -> wildInterval(context.getSource(), IntegerArgumentType.getInteger(context, "ticks")))))
                .then(Commands.literal("cap")
                        .then(Commands.argument("perPlayer", IntegerArgumentType.integer(0))
                                .executes(context -> wildCap(context.getSource(), IntegerArgumentType.getInteger(context, "perPlayer"), -1))
                                .then(Commands.argument("perLevel", IntegerArgumentType.integer(0))
                                        .executes(context -> wildCap(context.getSource(), IntegerArgumentType.getInteger(context, "perPlayer"),
                                                IntegerArgumentType.getInteger(context, "perLevel"))))))
                .then(Commands.literal("distance")
                        .then(Commands.argument("min", IntegerArgumentType.integer(1, WildSpawnSettings.MAX_DISTANCE))
                                .then(Commands.argument("max", IntegerArgumentType.integer(1, WildSpawnSettings.MAX_DISTANCE))
                                        .executes(context -> wildDistance(context.getSource(), IntegerArgumentType.getInteger(context, "min"),
                                                IntegerArgumentType.getInteger(context, "max"))))))
                .then(Commands.literal("try").executes(context -> wildTry(context.getSource())))
                .then(Commands.literal("clear").executes(context -> wildClear(context.getSource())))
                .then(Commands.literal("debug")
                        .then(Commands.literal("on").executes(context -> wildDebug(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> wildDebug(context.getSource(), false))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Identifier> speciesArgument() {
        return Commands.argument("species", IdentifierArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                        DigimonSpeciesRegistry.all().stream().map(DigimonSpecies::id), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> levelArgument() {
        return Commands.argument("level", IntegerArgumentType.integer(Progression.MIN_LEVEL, Progression.LEVEL_CAP));
    }

    // --- spawning and giving ---------------------------------------------------------

    private static int spawn(CommandContext<CommandSourceStack> context, int level) {
        CommandSourceStack source = context.getSource();
        DigimonSpecies species = resolveSpecies(source, IdentifierArgument.getId(context, "species"));
        if (species == null) return 0;
        DigimonEntity digimon = create(source, source.getLevel());
        if (digimon == null) return 0;
        Vec3 position = source.getPosition();
        digimon.initializeAs(species, level);
        digimon.setPos(position.x, position.y, position.z);
        // A test spawn stays where the operator put it instead of despawning like a natural one.
        digimon.setPersistenceRequired();
        source.getLevel().addFreshEntity(digimon);
        source.sendSuccess(() -> Component.translatable("commands.digicube.spawn.success",
                Component.translatable(species.translationKey()), digimon.getLevel()), true);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> context, ServerPlayer player, int level) {
        CommandSourceStack source = context.getSource();
        DigimonSpecies species = resolveSpecies(source, IdentifierArgument.getId(context, "species"));
        if (species == null) return 0;
        DigimonEntity digimon = create(source, player.level());
        if (digimon == null) return 0;
        digimon.initializeAs(species, level);
        PartyMember member = PartyManager.give(player, digimon);
        source.sendSuccess(() -> Component.translatable(member.active()
                        ? "commands.digicube.give.party" : "commands.digicube.give.reserve",
                Component.translatable(species.translationKey()), player.getDisplayName()), true);
        return 1;
    }

    private static DigimonEntity create(CommandSourceStack source, ServerLevel level) {
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        if (digimon == null) source.sendFailure(Component.translatable("commands.digicube.spawn.failed"));
        return digimon;
    }

    /**
     * A bare name like {@code agumon} parses as {@code minecraft:agumon}; treat that as
     * shorthand for {@code digicube:agumon} so players need not type the namespace.
     * @return the species, or null after reporting the failure
     */
    private static DigimonSpecies resolveSpecies(CommandSourceStack source, Identifier speciesId) {
        Optional<DigimonSpecies> species = DigimonSpeciesRegistry.get(speciesId);
        if (species.isEmpty() && Identifier.DEFAULT_NAMESPACE.equals(speciesId.getNamespace())) {
            species = DigimonSpeciesRegistry.get(Constants.id(speciesId.getPath()));
        }
        if (species.isEmpty()) source.sendFailure(Component.translatable("commands.digicube.spawn.unknown", speciesId.toString()));
        return species.orElse(null);
    }

    // --- progression -------------------------------------------------------------------

    private static int level(CommandSourceStack source, Collection<? extends Entity> targets, int level) {
        List<DigimonEntity> digimon = digimon(source, targets);
        digimon.forEach(entity -> entity.resetToLevel(level));
        if (!digimon.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.digicube.level.success", digimon.size(), level), true);
        }
        return digimon.size();
    }

    private static int xp(CommandSourceStack source, Collection<? extends Entity> targets, int amount) {
        List<DigimonEntity> digimon = digimon(source, targets);
        digimon.forEach(entity -> entity.addExperience(amount));
        if (!digimon.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.digicube.xp.success", amount, digimon.size()), true);
        }
        return digimon.size();
    }

    /** The Digimon among the selected entities; reports when there are none. */
    private static List<DigimonEntity> digimon(CommandSourceStack source, Collection<? extends Entity> targets) {
        List<DigimonEntity> digimon = targets.stream()
                .filter(DigimonEntity.class::isInstance).map(DigimonEntity.class::cast).toList();
        if (digimon.isEmpty()) source.sendFailure(Component.translatable("commands.digicube.targets.none"));
        return digimon;
    }

    // --- first partner -----------------------------------------------------------------

    /** Anyone: reopen the prompt while still eligible, otherwise say why not. */
    private static int starterSelf(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        StarterFlow.Eligibility eligibility = StarterFlow.offer(source.getServer(), player, false);
        if (eligibility.eligible()) return 1;
        source.sendFailure(Component.translatable(eligibility.translationKey()));
        return 0;
    }

    /** Operator: offer regardless of owned partners; refused only by an existing record. */
    private static int starterOpen(CommandSourceStack source, ServerPlayer player) {
        StarterFlow.Eligibility eligibility = StarterFlow.offer(source.getServer(), player, true);
        if (!eligibility.eligible()) {
            source.sendFailure(Component.translatable(eligibility.translationKey()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.digicube.starter.opened", player.getDisplayName()), true);
        return 1;
    }

    private static int starterReset(CommandSourceStack source, ServerPlayer player) {
        StarterSavedData.get(source.getServer()).reset(player.getUUID());
        source.sendSuccess(() -> Component.translatable("commands.digicube.starter.reset", player.getDisplayName()), true);
        return 1;
    }

    private static int starterList(CommandSourceStack source) {
        List<StarterSavedData.StarterRecord> records = StarterSavedData.get(source.getServer()).records();
        if (records.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.digicube.starter.list_empty"), false);
            return 0;
        }
        for (StarterSavedData.StarterRecord record : records) {
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(record.player());
            Component name = online != null ? online.getDisplayName() : Component.literal(record.player().toString());
            Component species = DigimonSpeciesRegistry.get(record.species()).map(DigimonSpecies::translationKey)
                    .map(Component::translatable).orElseGet(() -> Component.literal(record.species().toString()));
            source.sendSuccess(() -> Component.translatable("commands.digicube.starter.list", name, species), false);
        }
        return records.size();
    }

    // --- wild spawner ------------------------------------------------------------------

    private static WildSpawnSettings settings(CommandSourceStack source) {
        return WildSpawnSettings.get(source.getServer());
    }

    private static Component onOff(boolean value) {
        return Component.translatable(value ? "commands.digicube.wild.on" : "commands.digicube.wild.off");
    }

    private static int wildStatus(CommandSourceStack source) {
        WildSpawnSettings settings = settings(source);
        ServerLevel level = source.getLevel();
        int wild = WildSpawner.wild(level).size();
        int cap = settings.cap(level.players().size());
        Component last = settings.lastAttempt(level.dimension()).map(SpawnAttempt::describe)
                .orElseGet(() -> Component.translatable("commands.digicube.wild.no_attempt"));
        source.sendSuccess(() -> Component.translatable("commands.digicube.wild.status.settings",
                onOff(settings.enabled()), settings.intervalTicks(), settings.maxPerPlayer(), settings.maxPerLevel(),
                settings.minDistance(), settings.maxDistance(), onOff(settings.debug())), false);
        source.sendSuccess(() -> Component.translatable("commands.digicube.wild.status.level", wild, cap, last), false);
        return wild;
    }

    private static int wildEnabled(CommandSourceStack source, boolean enabled) {
        settings(source).setEnabled(enabled);
        source.sendSuccess(() -> Component.translatable("commands.digicube.wild.toggled", onOff(enabled)), true);
        return 1;
    }

    private static int wildInterval(CommandSourceStack source, int ticks) {
        int applied = settings(source).setIntervalTicks(ticks);
        source.sendSuccess(() -> Component.translatable("commands.digicube.wild.interval", applied), true);
        return applied;
    }

    private static int wildCap(CommandSourceStack source, int perPlayer, int perLevel) {
        WildSpawnSettings settings = settings(source);
        settings.setCaps(perPlayer, perLevel < 0 ? settings.maxPerLevel() : perLevel);
        source.sendSuccess(() -> Component.translatable("commands.digicube.wild.cap", settings.maxPerPlayer(), settings.maxPerLevel()), true);
        return settings.maxPerLevel();
    }

    private static int wildDistance(CommandSourceStack source, int min, int max) {
        WildSpawnSettings settings = settings(source);
        settings.setDistance(min, max);
        source.sendSuccess(() -> Component.translatable("commands.digicube.wild.distance", settings.minDistance(), settings.maxDistance()), true);
        return settings.maxDistance();
    }

    private static int wildTry(CommandSourceStack source) {
        SpawnAttempt attempt = WildSpawner.attempt(source.getLevel(), settings(source));
        Component report = Component.translatable("commands.digicube.wild.attempt", attempt.describe());
        if (!attempt.succeeded()) {
            source.sendFailure(report);
            return 0;
        }
        source.sendSuccess(() -> report, true);
        return 1;
    }

    private static int wildClear(CommandSourceStack source) {
        int removed = WildSpawner.clear(source.getLevel());
        source.sendSuccess(() -> Component.translatable("commands.digicube.wild.clear", removed), true);
        return removed;
    }

    private static int wildDebug(CommandSourceStack source, boolean debug) {
        settings(source).setDebug(debug);
        source.sendSuccess(() -> Component.translatable("commands.digicube.wild.debug", onOff(debug)), true);
        return 1;
    }
}
