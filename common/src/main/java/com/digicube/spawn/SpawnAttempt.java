package com.digicube.spawn;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The outcome of one spawn attempt, kept per dimension so {@code /digicube wild status}
 * can name the step that failed instead of leaving a quiet world unexplained.
 *
 * @param gameTime when the attempt ran
 * @param result   what happened
 * @param detail   what was spawned, or why nothing was; may be empty
 */
public record SpawnAttempt(long gameTime, Result result, String detail) {

    /** Each value has a translation under {@code commands.digicube.wild.result}. */
    public enum Result {
        SPAWNED,
        NO_TABLE,
        NO_PLAYER,
        CAP,
        UNLOADED,
        PLACEMENT,
        NO_ENTRY,
        FAILED;

        public String translationKey() {
            return "commands.digicube.wild.result." + name().toLowerCase(Locale.ROOT);
        }
    }

    public static SpawnAttempt of(long gameTime, Result result) {
        return new SpawnAttempt(gameTime, result, "");
    }

    public boolean succeeded() {
        return result == Result.SPAWNED;
    }

    public Component describe() {
        return Component.translatable(result.translationKey(), detail);
    }
}
