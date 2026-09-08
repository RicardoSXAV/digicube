package com.digicube.dev;

import com.digicube.digimon.DigimonBody;
import com.digicube.digimon.DigimonLocomotion;
import com.digicube.digimon.DigimonSpecies;
import com.digicube.platform.Services;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes tuned speeds back into a species sheet in the repository, editing the JSON text
 * in place so the file keeps its formatting and the diff shows only the changed numbers.
 * The repository is found by walking up from the game directory (a dev run lives in
 * {@code fabric/runs/client}); nothing is written outside a checkout that has the sheet.
 */
public final class SpeciesSheetWriter {
    private static final int MAX_PARENTS = 5;
    private static final String SPECIES_DIRECTORY = "common/src/main/resources/data";

    private SpeciesSheetWriter() {}

    /** The checkout that contains the species sheets, if the game runs inside one. */
    public static Optional<Path> repositoryRoot() {
        Path directory = Services.PLATFORM.gameDirectory().toAbsolutePath().normalize();
        for (int level = 0; level <= MAX_PARENTS && directory != null; level++, directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))
                    && Files.isDirectory(directory.resolve(SPECIES_DIRECTORY))) {
                return Optional.of(directory);
            }
        }
        return Optional.empty();
    }

    public static Path sheet(Path root, DigimonSpecies species) {
        return root.resolve(SPECIES_DIRECTORY).resolve(species.id().getNamespace())
                .resolve("species").resolve(species.id().getPath() + ".json");
    }

    /**
     * Puts the species' current speeds into its sheet.
     * @return the file written
     * @throws IOException when there is no checkout, no sheet, or the write fails
     */
    public static Path write(DigimonSpecies species) throws IOException {
        Path root = repositoryRoot().orElseThrow(() -> new IOException("No repository checkout above " + Services.PLATFORM.gameDirectory()));
        Path file = sheet(root, species);
        if (!Files.isRegularFile(file)) throw new IOException("No sheet at " + file);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        text = rewrite(text, species);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** The text edit on its own, so it can be checked without a file system. */
    static String rewrite(String text, DigimonSpecies species) {
        DigimonLocomotion locomotion = species.locomotion();
        text = replaceNumber(text, "base_speed", species.baseSpeed())
                .orElseThrow(() -> new IllegalStateException("Sheet has no base_speed"));
        if (text.contains("\"locomotion\"")) {
            text = replaceNumber(text, "walk_speed", locomotion.walkSpeed()).orElse(text);
            text = replaceNumber(text, "run_speed", locomotion.runSpeed()).orElse(text);
            Optional<String> swim = replaceNumber(text, "swim_speed", locomotion.swimSpeed());
            if (swim.isPresent()) text = swim.get();
            else if (locomotion.canSwim()) {
                // Add the key after run_speed, inside the existing block.
                Matcher run = numberPattern("run_speed").matcher(text);
                if (run.find()) {
                    text = text.substring(0, run.end()) + ",\n    \"swim_speed\": " + format(locomotion.swimSpeed())
                            + text.substring(run.end());
                }
            }
        } else {
            String block = "  \"locomotion\": {\n"
                    + "    \"follow_start_distance\": " + format(locomotion.followStartDistance()) + ",\n"
                    + "    \"follow_stop_distance\": " + format(locomotion.followStopDistance()) + ",\n"
                    + "    \"walk_speed\": " + format(locomotion.walkSpeed()) + ",\n"
                    + "    \"run_speed\": " + format(locomotion.runSpeed())
                    + (locomotion.canSwim() ? ",\n    \"swim_speed\": " + format(locomotion.swimSpeed()) : "")
                    + "\n  }";
            int close = text.lastIndexOf('}');
            int previous = text.lastIndexOf('}', close - 1);
            int bracket = text.lastIndexOf(']', close - 1);
            int lastValueEnd = Math.max(previous, bracket);
            // The root object's last member ends with } or ] for every bundled sheet
            // (evolutions, attacks, body or locomotion); a number would need the same treatment.
            String tail = text.substring(lastValueEnd + 1, close);
            text = text.substring(0, lastValueEnd + 1) + ",\n" + block + "\n" + tail.stripLeading() + text.substring(close);
            if (!text.endsWith("\n")) text += "\n";
        }
        // Both blocks call their number "speed", so each is edited inside its own braces.
        if (locomotion.canFly()) text = replaceNumberIn(text, "flight", "speed", locomotion.flight().speed());
        Optional<DigimonBody.Mount> mount = species.body().mount();
        if (mount.isPresent()) text = replaceNumberIn(text, "mount", "speed", mount.get().speed());
        return text;
    }

    /** Replaces {@code key} only inside the object {@code block} names; untouched when either is missing. */
    private static String replaceNumberIn(String text, String block, String key, double value) {
        Matcher open = Pattern.compile("\"" + Pattern.quote(block) + "\"\\s*:\\s*\\{").matcher(text);
        if (!open.find()) return text;
        int depth = 1;
        int close = open.end();
        while (close < text.length() && depth > 0) {
            char c = text.charAt(close);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            close++;
        }
        String inside = text.substring(open.end(), close);
        return text.substring(0, open.end()) + replaceNumber(inside, key, value).orElse(inside) + text.substring(close);
    }

    private static Pattern numberPattern(String key) {
        return Pattern.compile("(\"" + Pattern.quote(key) + "\"\\s*:\\s*)(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)");
    }

    private static Optional<String> replaceNumber(String text, String key, double value) {
        Matcher matcher = numberPattern(key).matcher(text);
        if (!matcher.find()) return Optional.empty();
        return Optional.of(text.substring(0, matcher.start(2)) + format(value) + text.substring(matcher.end(2)));
    }

    /** Up to three decimals, no trailing zeros, always with a decimal point. */
    public static String format(double value) {
        String plain = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return plain.contains(".") ? plain : plain + ".0";
    }
}
