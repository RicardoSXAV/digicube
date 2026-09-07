package com.digicube.command;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.digimon.Progression;
import com.digicube.entity.DigimonEntity;
import com.digicube.party.PartyManager;
import com.digicube.party.PartyMember;
import com.digicube.registry.DCEntityTypes;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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

/**
 * DigiCube's operator commands. Loader modules hand us their dispatcher; everything
 * else is plain Brigadier and stays here.
 *
 * <pre>
 * /digicube spawn &lt;species&gt; [level]            wild Digimon at the caller's feet; it stays put
 * /digicube give &lt;species&gt; [player [level]]   partner for a player, default the caller
 * /digicube level &lt;targets&gt; &lt;level&gt;         set the level, reset XP, restore full health
 * /digicube xp &lt;targets&gt; &lt;amount&gt;           grant XP through the normal path, level-ups included
 * </pre>
 */
public final class DigiCubeCommands {

    private DigiCubeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("digicube")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn")
                        .then(speciesArgument()
                                .executes(context -> spawn(context, Progression.MIN_LEVEL))
                                .then(levelArgument()
                                        .executes(context -> spawn(context, IntegerArgumentType.getInteger(context, "level"))))))
                .then(Commands.literal("give")
                        .then(speciesArgument()
                                .executes(context -> give(context, context.getSource().getPlayerOrException(), Progression.MIN_LEVEL))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> give(context, EntityArgument.getPlayer(context, "player"), Progression.MIN_LEVEL))
                                        .then(levelArgument()
                                                .executes(context -> give(context, EntityArgument.getPlayer(context, "player"),
                                                        IntegerArgumentType.getInteger(context, "level")))))))
                .then(Commands.literal("level")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(levelArgument()
                                        .executes(context -> level(context.getSource(), EntityArgument.getEntities(context, "targets"),
                                                IntegerArgumentType.getInteger(context, "level"))))))
                .then(Commands.literal("xp")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(context -> xp(context.getSource(), EntityArgument.getEntities(context, "targets"),
                                                IntegerArgumentType.getInteger(context, "amount")))))));
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
}
