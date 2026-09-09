package com.digicube.starter;

import com.digicube.Constants;
import com.digicube.digimon.DigimonSpeciesBootstrap;
import com.digicube.digimon.DigimonSpeciesRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Util;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Covers the bundled starter set, its validation, the saved data, the eligibility and choice rules and the payloads. */
public final class StarterRegressionTest {
    private StarterRegressionTest() {}

    private static final Predicate<Identifier> KNOWN_SPECIES = id -> DigimonSpeciesRegistry.get(id).isPresent();
    private static final Identifier AGUMON = Constants.id("agumon");
    private static final Identifier GABUMON = Constants.id("gabumon");
    private static final Identifier GOMAMON = Constants.id("gomamon");
    private static final Identifier KOROMON = Constants.id("koromon");

    /** @param args unused */
    public static void main(String[] args) {
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            DigimonSpeciesBootstrap.registerBuiltIn();
            checkBundledSet();
            checkValidation();
            checkSavedData();
            checkEligibility();
            checkOutcomes();
            checkTransaction();
            checkPayloads();
            Constants.LOG.info("Starter regression checks passed: bundled set, validation, saved data, eligibility, choice, transaction and payloads.");
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static void checkBundledSet() {
        StarterSet set = StarterSet.load(KNOWN_SPECIES);
        check(set.species().equals(List.of(AGUMON, GABUMON, GOMAMON)), "the bundled starters are Agumon, Gabumon and Gomamon in that order");
        check(set.level() == 1, "starters begin at level 1");
        check(set.contains(GABUMON) && !set.contains(KOROMON), "membership follows the list");
        check(set.asSet().size() == 3, "the set view has one entry per species");
        StarterSet.registerBuiltIn();
        check(StarterSet.get().equals(set), "the registered set is the bundled one");
    }

    private static void checkValidation() {
        JsonObject json = GsonHelper.parse("{\"level\": 1, \"starters\": [\"agumon\", \"digicube:gabumon\", \"gomamon\"]}");
        StarterSet parsed = StarterSet.parse(json, KNOWN_SPECIES);
        check(parsed.species().equals(List.of(AGUMON, GABUMON, GOMAMON)), "bare names take the mod namespace");
        json.remove("level");
        check(StarterSet.parse(json, KNOWN_SPECIES).level() == 1, "a missing level defaults to 1");
        for (int level : new int[] {0, 51}) {
            json.addProperty("level", level);
            rejects(() -> StarterSet.parse(json, KNOWN_SPECIES), "level " + level + " rejected");
        }
        json.addProperty("level", 5);
        check(StarterSet.parse(json, KNOWN_SPECIES).level() == 5, "level 5 accepted");
        json.add("starters", array("agumon", "unknownmon"));
        rejects(() -> StarterSet.parse(json, KNOWN_SPECIES), "unknown species rejected");
        json.add("starters", array("agumon", "agumon"));
        rejects(() -> StarterSet.parse(json, KNOWN_SPECIES), "duplicate species rejected");
        json.add("starters", array("agumon"));
        rejects(() -> StarterSet.parse(json, KNOWN_SPECIES), "a single starter rejected");
        json.add("starters", array());
        rejects(() -> StarterSet.parse(json, KNOWN_SPECIES), "an empty list rejected");
        json.add("starters", array("Not A Valid Id!", "agumon"));
        rejects(() -> StarterSet.parse(json, KNOWN_SPECIES), "malformed id rejected");
        String[] many = new String[9];
        for (int index = 0; index < many.length; index++) many[index] = "mon" + index;
        json.add("starters", array(many));
        rejects(() -> StarterSet.parse(json, id -> true), "nine starters rejected");
        json.add("starters", array(java.util.Arrays.copyOf(many, 8)));
        check(StarterSet.parse(json, id -> true).species().size() == 8, "eight starters accepted");
        json.remove("starters");
        rejects(() -> StarterSet.parse(json, KNOWN_SPECIES), "a missing list rejected");
    }

    private static void checkSavedData() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        StarterSavedData data = new StarterSavedData();
        check(!data.isDirty() && data.records().isEmpty() && !data.hasRecord(first), "a fresh document is empty and clean");
        data.record(first, GABUMON);
        data.record(second, GOMAMON);
        check(data.isDirty() && data.hasRecord(first) && data.choice(first).orElseThrow().equals(GABUMON), "a record is remembered");
        data.record(first, AGUMON);
        check(data.choice(first).orElseThrow().equals(GABUMON), "a record is never overwritten");
        check(data.records().size() == 2 && data.records().getFirst().player().equals(first), "records keep their order");
        data.markOffered(first);
        check(data.isOffered(first) && !data.isOffered(second), "the offer set is per player");
        check(!data.hasDigivice(first) && data.digivices().isEmpty(), "nobody holds a Digivice at first");
        data.markDigivice(first);
        data.markDigivice(first);
        check(data.hasDigivice(first) && !data.hasDigivice(second) && data.digivices().equals(List.of(first)), "a Digivice is marked once per player");

        var encoded = StarterSavedData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        StarterSavedData decoded = StarterSavedData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        check(decoded.records().equals(data.records()), "records round-trip through the codec");
        check(decoded.digivices().equals(List.of(first)), "handed Digivices round-trip through the codec");
        check(!decoded.isOffered(first), "the offer set is not saved");
        StarterSavedData empty = StarterSavedData.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        check(empty.records().isEmpty(), "an empty document loads as no records");
        check(empty.digivices().isEmpty(), "a document from before the Digivice hand-out loads as nobody holding one");

        check(data.reset(first) && !data.hasRecord(first) && !data.reset(first), "reset removes a record once");
        data.clearOffered(first);
        check(!data.isOffered(first), "offers can be withdrawn");
    }

    private static void checkEligibility() {
        check(StarterFlow.needsDigivice(false, false), "a new player is handed a Digivice");
        check(!StarterFlow.needsDigivice(true, false), "spectators wait for their Digivice");
        check(!StarterFlow.needsDigivice(false, true), "one Digivice per player per world");
        check(StarterFlow.eligibility(false, false, 0) == StarterFlow.Eligibility.ELIGIBLE, "a new player is eligible");
        check(StarterFlow.eligibility(true, false, 0) == StarterFlow.Eligibility.SPECTATOR, "spectators are skipped");
        check(StarterFlow.eligibility(false, true, 0) == StarterFlow.Eligibility.ALREADY_CHOSEN, "one starter per player");
        check(StarterFlow.eligibility(false, false, 2) == StarterFlow.Eligibility.HAS_PARTNERS, "legacy tamers are never prompted");
        check(StarterFlow.eligibility(true, true, 2) == StarterFlow.Eligibility.SPECTATOR, "spectator is checked first");
        check(StarterFlow.eligibility(false, true, 2) == StarterFlow.Eligibility.ALREADY_CHOSEN, "the record outranks the roster");
        check(!StarterFlow.Eligibility.SPECTATOR.eligible() && StarterFlow.Eligibility.ELIGIBLE.eligible(), "only ELIGIBLE opens the prompt");
        check(StarterFlow.Eligibility.HAS_PARTNERS.translationKey().equals("commands.digicube.starter.has_partners"), "eligibility keys");
    }

    private static void checkOutcomes() {
        check(StarterFlow.decide(true, true, true, false) == StarterFlow.Outcome.LINKED, "a valid choice links");
        check(StarterFlow.decide(false, true, true, false) == StarterFlow.Outcome.NOT_OFFERED, "no offer, no choice");
        check(StarterFlow.decide(true, false, true, false) == StarterFlow.Outcome.UNAVAILABLE, "dead or spectating players wait");
        check(StarterFlow.decide(true, true, false, false) == StarterFlow.Outcome.UNKNOWN, "only starters are accepted");
        check(StarterFlow.decide(true, true, true, true) == StarterFlow.Outcome.ALREADY_CHOSEN, "a second choice is refused");
        check(StarterFlow.decide(false, false, false, true) == StarterFlow.Outcome.NOT_OFFERED, "the offer is checked first");
        check(StarterFlow.Outcome.SPAWN_FAILED.translationKey().equals("commands.digicube.starter.failed")
                && StarterFlow.Outcome.NOT_OFFERED.translationKey().equals("commands.digicube.starter.not_offered"), "outcome keys");
        check(StarterFlow.Outcome.LINKED.ok() && !StarterFlow.Outcome.UNKNOWN.ok(), "only LINKED is a success");
    }

    private static void checkTransaction() {
        StarterSet set = StarterSet.get();
        StarterSavedData data = new StarterSavedData();
        UUID player = UUID.randomUUID();
        check(StarterFlow.link(data, set, player, GABUMON, true, sheet -> true) == StarterFlow.Outcome.NOT_OFFERED
                && !data.hasRecord(player), "an unrequested choice writes nothing");
        data.markOffered(player);
        check(StarterFlow.link(data, set, player, KOROMON, true, sheet -> true) == StarterFlow.Outcome.UNKNOWN
                && !data.hasRecord(player), "a non-starter writes nothing");
        boolean[] recordedBeforeGrant = new boolean[1];
        StarterFlow.Outcome failed = StarterFlow.link(data, set, player, GABUMON, true, sheet -> {
            recordedBeforeGrant[0] = data.hasRecord(player);
            return false;
        });
        check(failed == StarterFlow.Outcome.SPAWN_FAILED && recordedBeforeGrant[0], "the record is written before the partner is created");
        check(!data.hasRecord(player) && data.isOffered(player), "a failed grant rolls the record back and keeps the offer open");
        StarterFlow.Outcome linked = StarterFlow.link(data, set, player, GABUMON, true, sheet -> sheet.id().equals(GABUMON));
        check(linked == StarterFlow.Outcome.LINKED && data.choice(player).orElseThrow().equals(GABUMON) && !data.isOffered(player),
                "a successful link records the species and closes the offer");
        data.markOffered(player);
        check(StarterFlow.link(data, set, player, AGUMON, true, sheet -> true) == StarterFlow.Outcome.ALREADY_CHOSEN
                && data.choice(player).orElseThrow().equals(GABUMON), "a second choice is refused and leaves the record");
    }

    private static void checkPayloads() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            StarterOfferPayload offer = new StarterOfferPayload(List.of(AGUMON, GABUMON, GOMAMON), 1);
            StarterOfferPayload.STREAM_CODEC.encode(buffer, offer);
            check(StarterOfferPayload.STREAM_CODEC.decode(buffer).equals(offer), "offer round-trip");
            buffer.clear();
            StarterChoicePayload choice = StarterChoicePayload.choose(GABUMON);
            StarterChoicePayload.STREAM_CODEC.encode(buffer, choice);
            check(StarterChoicePayload.STREAM_CODEC.decode(buffer).equals(choice), "choice round-trip");
            buffer.clear();
            StarterChoicePayload defer = StarterChoicePayload.defer();
            StarterChoicePayload.STREAM_CODEC.encode(buffer, defer);
            check(StarterChoicePayload.STREAM_CODEC.decode(buffer).action() == StarterChoicePayload.DEFER, "defer round-trip");
            buffer.clear();
            StarterResultPayload result = new StarterResultPayload(false, "commands.digicube.starter.unknown");
            StarterResultPayload.STREAM_CODEC.encode(buffer, result);
            check(StarterResultPayload.STREAM_CODEC.decode(buffer).equals(result), "result round-trip");
            check(StarterResultPayload.linked().ok() && StarterResultPayload.linked().messageKey().isEmpty(), "the success result carries no key");
            buffer.clear();
            List<Identifier> nine = java.util.stream.IntStream.range(0, 9).mapToObj(index -> Constants.id("mon" + index)).toList();
            rejects(() -> new StarterOfferPayload(nine, 1), "nine species refused at construction");
            buffer.writeVarInt(9);
            nine.forEach(buffer::writeIdentifier);
            buffer.writeVarInt(1);
            rejects(() -> StarterOfferPayload.STREAM_CODEC.decode(buffer), "nine species refused on the wire");
            buffer.clear();
            rejects(() -> new StarterResultPayload(false, "k".repeat(65)), "a 65-character key refused");
        } finally {
            buffer.release();
        }
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException | IllegalStateException | JsonParseException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
