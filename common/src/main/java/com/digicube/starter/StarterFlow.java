package com.digicube.starter;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.digicube.entity.DigimonEntity;
import com.digicube.party.PartyManager;
import com.digicube.party.PartySavedData;
import com.digicube.platform.Services;
import com.digicube.registry.DCEntityTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The first-partner prompt, server side. The pure decisions ({@link #eligibility},
 * {@link #decide}) and the transaction ({@link #link}) are separated from the server
 * objects so the headless suite can cover every branch. Loader adapters call
 * {@link #offer} on join, {@link #handle} for a choice packet and {@link #disconnect}
 * when the player leaves.
 */
public final class StarterFlow {

    /** Command the deferral hint runs; also what operators tell players to type. */
    public static final String COMMAND = "/digicube starter";

    private StarterFlow() {}

    /** Why a player is, or is not, shown the prompt. */
    public enum Eligibility {
        ELIGIBLE, SPECTATOR, ALREADY_CHOSEN, HAS_PARTNERS;

        public boolean eligible() { return this == ELIGIBLE; }

        public String translationKey() { return "commands.digicube.starter." + name().toLowerCase(Locale.ROOT); }
    }

    /** What became of a choice. */
    public enum Outcome {
        LINKED, NOT_OFFERED, UNAVAILABLE, UNKNOWN, ALREADY_CHOSEN, SPAWN_FAILED;

        public boolean ok() { return this == LINKED; }

        public String translationKey() {
            return "commands.digicube.starter." + (this == SPAWN_FAILED ? "failed" : name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * The eligibility rule. The roster check keeps existing worlds quiet: anyone who
     * already owns a partner is never prompted.
     */
    public static Eligibility eligibility(boolean spectator, boolean hasRecord, int ownedCount) {
        if (spectator) return Eligibility.SPECTATOR;
        if (hasRecord) return Eligibility.ALREADY_CHOSEN;
        if (ownedCount > 0) return Eligibility.HAS_PARTNERS;
        return Eligibility.ELIGIBLE;
    }

    /** The validation order of a choice, before anything is written. */
    public static Outcome decide(boolean offered, boolean available, boolean known, boolean hasRecord) {
        if (!offered) return Outcome.NOT_OFFERED;
        if (!available) return Outcome.UNAVAILABLE;
        if (!known) return Outcome.UNKNOWN;
        if (hasRecord) return Outcome.ALREADY_CHOSEN;
        return Outcome.LINKED;
    }

    /**
     * The transaction: validate, write the record, then grant the partner. The record
     * comes first and is rolled back when the grant fails, so a crash in between leaves a
     * record and no partner (repaired by {@code /digicube starter reset}) and never the
     * reverse.
     * @param available the player is alive and not a spectator
     * @param grant     creates and gives the partner; false when the entity could not be made
     */
    public static Outcome link(StarterSavedData data, StarterSet set, UUID player, Identifier species,
                               boolean available, Predicate<DigimonSpecies> grant) {
        Outcome outcome = decide(data.isOffered(player), available, set.contains(species), data.hasRecord(player));
        if (!outcome.ok()) return outcome;
        DigimonSpecies sheet = DigimonSpeciesRegistry.get(species).orElse(null);
        if (sheet == null) return Outcome.UNKNOWN;
        data.record(player, species);
        if (!grant.test(sheet)) {
            data.reset(player);
            return Outcome.SPAWN_FAILED;
        }
        data.clearOffered(player);
        return Outcome.LINKED;
    }

    /**
     * Shows the prompt if the player is eligible.
     * @param force ignore owned partners (operator use, for players who predate the prompt)
     * @return the eligibility, so commands can explain a refusal
     */
    public static Eligibility offer(MinecraftServer server, ServerPlayer player, boolean force) {
        StarterSavedData data = StarterSavedData.get(server);
        UUID id = player.getUUID();
        int owned = force ? 0 : PartySavedData.get(server).roster().owned(id).size();
        Eligibility eligibility = eligibility(player.isSpectator(), data.hasRecord(id), owned);
        Constants.LOG.debug("Starter offer for {}: {}", player.getGameProfile().name(), eligibility);
        if (eligibility.eligible()) {
            StarterSet set = StarterSet.get();
            data.markOffered(id);
            Services.PLATFORM.sendToPlayer(player, new StarterOfferPayload(set.species(), set.level()));
        }
        return eligibility;
    }

    /** Routes a choice packet; runs on the server thread. */
    public static void handle(MinecraftServer server, ServerPlayer player, StarterChoicePayload payload) {
        if (payload.action() == StarterChoicePayload.CHOOSE) choose(server, player, payload.species());
        else if (payload.action() == StarterChoicePayload.DEFER) defer(server, player);
    }

    /** Links the player with a starter through the normal party path and answers the client. */
    public static Outcome choose(MinecraftServer server, ServerPlayer player, Identifier species) {
        StarterSet set = StarterSet.get();
        Outcome outcome = link(StarterSavedData.get(server), set, player.getUUID(), species,
                player.isAlive() && !player.isSpectator(), sheet -> {
                    DigimonEntity digimon = DCEntityTypes.DIGIMON.create(player.level(), EntitySpawnReason.EVENT);
                    if (digimon == null) return false;
                    digimon.initializeAs(sheet, set.level());
                    PartyManager.give(player, digimon);
                    player.sendSystemMessage(Component.translatable("digimon.digicube.starter.linked",
                            Component.translatable(sheet.translationKey())));
                    return true;
                });
        Constants.LOG.debug("Starter choice {} by {}: {}", species, player.getGameProfile().name(), outcome);
        Services.PLATFORM.sendToPlayer(player, outcome.ok() ? StarterResultPayload.linked()
                : new StarterResultPayload(false, outcome.translationKey()));
        return outcome;
    }

    /** The player closed the prompt; tell them how to come back. The offer stays open. */
    public static void defer(MinecraftServer server, ServerPlayer player) {
        if (!StarterSavedData.get(server).isOffered(player.getUUID())) return;
        Component command = Component.literal(COMMAND).withStyle(Style.EMPTY
                .withUnderlined(true).withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.RunCommand(COMMAND)));
        player.sendSystemMessage(Component.translatable("digimon.digicube.starter.later", command));
    }

    public static void disconnect(MinecraftServer server, ServerPlayer player) {
        StarterSavedData.get(server).clearOffered(player.getUUID());
    }
}
