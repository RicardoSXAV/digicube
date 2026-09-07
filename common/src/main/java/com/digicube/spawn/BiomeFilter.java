package com.digicube.spawn;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * One biome reference from a spawn entry: a biome id such as {@code minecraft:plains}
 * or a biome tag such as {@code #minecraft:is_forest}.
 *
 * <p>Matching takes predicates rather than a biome holder, so the spawner passes the
 * holder's own {@code is} methods while a headless test passes plain lambdas.
 *
 * @param id  the biome id, or the tag id when {@code tag} is set
 * @param tag the tag key, or null for a plain biome id
 */
public record BiomeFilter(Identifier id, TagKey<Biome> tag) {

    public BiomeFilter {
        Objects.requireNonNull(id, "biome id");
    }

    /** Parses {@code minecraft:plains} or {@code #minecraft:is_forest}; a bare name is a vanilla id. */
    public static BiomeFilter parse(String reference) {
        boolean isTag = reference.startsWith("#");
        Identifier id = Identifier.tryParse(isTag ? reference.substring(1) : reference);
        // Vanilla accepts an empty path ("minecraft:"), which can only be a typo here.
        if (id == null || id.getPath().isEmpty()) throw new IllegalArgumentException("Invalid biome reference: " + reference);
        return new BiomeFilter(id, isTag ? TagKey.create(Registries.BIOME, id) : null);
    }

    public boolean isTag() {
        return tag != null;
    }

    public boolean matches(Predicate<Identifier> biomeIds, Predicate<TagKey<Biome>> biomeTags) {
        return tag != null ? biomeTags.test(tag) : biomeIds.test(id);
    }

    /** The reference as written in JSON. */
    public String reference() {
        return (tag != null ? "#" : "") + id;
    }
}
