package com.digicube.command;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEntityTypes;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * DigiCube's commands. Loader modules hand us their dispatcher; everything else is
 * plain Brigadier and stays here.
 *
 * <pre>
 * /digicube spawn &lt;species&gt;          spawn a wild Digimon at the caller's feet
 * /digicube give &lt;species&gt; [player]  spawn a Digimon next to a player as their partner
 * </pre>
 */
public final class DigiCubeCommands {

    private DigiCubeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("digicube")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn")
                        .then(speciesArgument()
                                .executes(context -> spawn(context.getSource(),
                                        IdentifierArgument.getId(context, "species")))))
                .then(Commands.literal("give")
                        .then(speciesArgument()
                                .executes(context -> give(context.getSource(),
                                        IdentifierArgument.getId(context, "species"),
                                        context.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> give(context.getSource(),
                                                IdentifierArgument.getId(context, "species"),
                                                EntityArgument.getPlayer(context, "player")))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Identifier> speciesArgument() {
        return Commands.argument("species", IdentifierArgument.id())
                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                        DigimonSpeciesRegistry.all().stream().map(DigimonSpecies::id), builder));
    }

    private static int spawn(CommandSourceStack source, Identifier speciesId) {
        DigimonSpecies species = resolveSpecies(speciesId).orElse(null);
        if (species == null) {
            source.sendFailure(Component.translatable("commands.digicube.spawn.unknown", speciesId.toString()));
            return 0;
        }

        DigimonEntity digimon = create(source.getLevel(), species, source.getPosition());
        if (digimon == null) {
            source.sendFailure(Component.translatable("commands.digicube.spawn.failed"));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable("commands.digicube.spawn.success",
                Component.translatable(species.translationKey())), true);
        return 1;
    }

    private static int give(CommandSourceStack source, Identifier speciesId, ServerPlayer player) {
        DigimonSpecies species = resolveSpecies(speciesId).orElse(null);
        if (species == null) {
            source.sendFailure(Component.translatable("commands.digicube.spawn.unknown", speciesId.toString()));
            return 0;
        }

        // A step in front of the player, so the partner appears where they are looking.
        Vec3 forward = player.getViewVector(1.0F);
        Vec3 position = player.position().add(forward.x * 1.5, 0.0, forward.z * 1.5);
        DigimonEntity digimon = create(player.level(), species, position);
        if (digimon == null) {
            source.sendFailure(Component.translatable("commands.digicube.spawn.failed"));
            return 0;
        }
        digimon.setOwner(player);

        source.sendSuccess(() -> Component.translatable("commands.digicube.give.success",
                Component.translatable(species.translationKey()), player.getDisplayName()), true);
        return 1;
    }

    private static DigimonEntity create(ServerLevel level, DigimonSpecies species, Vec3 position) {
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        if (digimon == null) {
            return null;
        }
        digimon.setPos(position.x, position.y, position.z);
        digimon.setSpecies(species.id());
        level.addFreshEntity(digimon);
        return digimon;
    }

    /**
     * A bare name like {@code agumon} parses as {@code minecraft:agumon}; treat that as
     * shorthand for {@code digicube:agumon} so players need not type the namespace.
     */
    private static Optional<DigimonSpecies> resolveSpecies(Identifier speciesId) {
        Optional<DigimonSpecies> species = DigimonSpeciesRegistry.get(speciesId);
        if (species.isEmpty() && Identifier.DEFAULT_NAMESPACE.equals(speciesId.getNamespace())) {
            species = DigimonSpeciesRegistry.get(Constants.id(speciesId.getPath()));
        }
        return species;
    }
}
