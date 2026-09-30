package com.digicube.digimon;

import com.digicube.Constants;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.GsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What a species says: its ambient call, hurt and death sounds, its battle cry and the pitch they are played at, from
 * {@code data/digicube/voices.json}. A species without an entry keeps vanilla's (no ambient call, the generic hurt and
 * death). Digmon, Armadillomon armoured with the Digi-Egg of Knowledge, speaks with an armadillo's voice; Ikkakumon
 * has a walrus's voice of its own.
 *
 * @param ambient         call now and then, or null for none
 * @param hurt            when struck, or null for vanilla's
 * @param death           when defeated, or null for vanilla's
 * @param pitch           centre pitch of all of them (vanilla varies it by +-.1 around this)
 * @param ambientInterval ticks between ambient calls, on average
 * @param cry             called as one of its moves starts, in place of the growl a move opens with, or null
 */
public record DigimonVoices(SoundEvent ambient, SoundEvent hurt, SoundEvent death, float pitch, int ambientInterval, SoundEvent cry) {
    private static Map<Identifier, DigimonVoices> voices;

    /** The voice of {@code species}, or null to keep vanilla's. */
    public static DigimonVoices of(Identifier species) {
        if (voices == null) voices = load();
        return species == null ? null : voices.get(species);
    }

    private static Map<Identifier, DigimonVoices> load() {
        JsonObject data;
        try (var input = DigimonVoices.class.getResourceAsStream("/data/digicube/voices.json")) {
            if (input == null) return Map.of();
            data = GsonHelper.parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) { throw new IllegalStateException("Cannot read voices.json", e); }
        var result = new HashMap<Identifier, DigimonVoices>();
        for (var entry : data.entrySet()) {
            var v = entry.getValue().getAsJsonObject();
            var voice = new DigimonVoices(sound(v, "ambient"), sound(v, "hurt"), sound(v, "death"),
                    GsonHelper.getAsFloat(v, "pitch", 1), GsonHelper.getAsInt(v, "ambient_interval", 80), sound(v, "cry"));
            if (!(voice.pitch() >= .5F && voice.pitch() <= 2F) || voice.ambientInterval() < 20)
                throw new IllegalArgumentException("Invalid voice " + entry.getKey());
            result.put(Constants.id(entry.getKey()), voice);
        }
        return Map.copyOf(result);
    }

    private static SoundEvent sound(JsonObject v, String key) {
        if (!v.has(key)) return null;
        var id = Identifier.parse(GsonHelper.getAsString(v, key));
        return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseThrow(() -> new IllegalArgumentException("Unknown sound " + id));
    }
}
