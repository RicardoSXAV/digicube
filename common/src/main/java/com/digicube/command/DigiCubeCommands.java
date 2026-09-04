package com.digicube.command;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.registry.DCEntityTypes;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * The {@code /digicube} command tree. Loader modules hand us their dispatcher;
 * everything else is plain Brigadier and stays here.
 *
 * <pre>
 * /digicube spawn &lt;species&gt;   spawn a Digimon of that species at the caller's feet
 * </pre>
 */
public final class DigiCubeCommands {

    private DigiCubeCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("digicube")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("species", IdentifierArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
                                        DigimonSpeciesRegistry.all().stream().map(DigimonSpecies::id), builder))
                                .executes(context -> spawn(context.getSource(),
                                        IdentifierArgument.getId(context, "species"))))));
    }

    private static int spawn(CommandSourceStack source, Identifier speciesId) {
        DigimonSpecies species = resolveSpecies(speciesId).orElse(null);
        if (species == null) {
            source.sendFailure(Component.translatable("commands.digicube.spawn.unknown", speciesId.toString()));
            return 0;
        }

        ServerLevel level = source.getLevel();
        DigimonEntity digimon = DCEntityTypes.DIGIMON.create(level, EntitySpawnReason.COMMAND);
        if (digimon == null) {
            source.sendFailure(Component.translatable("commands.digicube.spawn.failed"));
            return 0;
        }

        Vec3 position = source.getPosition();
        digimon.setPos(position.x, position.y, position.z);
        digimon.setSpecies(species.id());
        level.addFreshEntity(digimon);

        source.sendSuccess(() -> Component.translatable("commands.digicube.spawn.success",
                Component.translatable(species.translationKey())), true);
        return 1;
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
