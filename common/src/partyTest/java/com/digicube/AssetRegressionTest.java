package com.digicube;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the bundled animation and motion tables lean. Exports arrive as dense sub-tick samples
 * with full double precision; the harness tool ({@code ../harness/tools/native_animation.py})
 * reduces them within tolerances no eye can see. A dense or unrounded table is a pipeline
 * mistake and fails the build here, before it multiplies the jar size.
 */
public final class AssetRegressionTest {
    private AssetRegressionTest() {}

    /** Same tolerances as the harness tool: radians, model pixels, scale factor. */
    private static final double ROTATION = 2e-4, POSITION = 1e-3, SCALE = 2e-4;
    /** Share of keys a linear loader would never need; reduced exports sit far below this. */
    private static final double MAX_REDUNDANT = .25;
    private static final Pattern LONG_DECIMALS = Pattern.compile("\\d\\.\\d{7,}");
    private static final Pattern LONG_MOTION_DECIMALS = Pattern.compile("\\d\\.\\d{9,}");

    /**
     * Escape hatch for the z-fighting gate: a species model listed here may keep that many part
     * pairs whose faces share a plane in the rest pose (see {@link MeshSurfaceCheck}), and the number
     * may only go down. Empty since 2026-09-18, when all nine species were cleaned with
     * {@code ../harness/v2/tools/fix_coplanar.py}; keep it empty. A model that is not listed must have none.
     */
    private static final Map<String, Integer> KNOWN_COPLANAR_PAIRS = Map.of();

    /** @param args unused */
    public static void main(String[] args) throws Exception {
        Path assets = resource("/assets/digicube/models/entity/ground_models.json").getParent();
        Path data = resource("/data/digicube/species.json").getParent();
        int animations = 0;
        try (Stream<Path> files = Files.list(assets)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".animation.json")).toList()) {
                checkAnimation(file);
                animations++;
            }
        }
        check(animations >= 20, "every bundled species and effect ships a native animation file");
        int surfaces = 0;
        try (Stream<Path> files = Files.list(assets)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".mesh.json")).toList()) {
                if (checkSurfaces(file)) surfaces++;
            }
        }
        check(surfaces >= 9, "every species model with an idle clip is checked for coplanar faces");
        checkDecimals(data.resolve("constriction_motion/constriction.json"), LONG_DECIMALS, "four");
        try (Stream<Path> files = Files.list(data.resolve("attack_motion"))) {
            for (Path file : files.toList()) checkDecimals(file, LONG_MOTION_DECIMALS, "six");
        }
        Constants.LOG.info("Asset regression checks passed: {} native animation files reduced and rounded, motion tables rounded, {} species meshes checked for coplanar faces.", animations, surfaces);
    }

    /** Species models only (they have an idle clip): effects stack hidden and scaled pieces by design. */
    private static boolean checkSurfaces(Path meshFile) throws IOException {
        String name = meshFile.getFileName().toString().replace(".mesh.json", "");
        Path animationFile = meshFile.resolveSibling(name + ".animation.json");
        if (!Files.exists(animationFile)) return false;
        JsonObject mesh, animation;
        try (var reader = Files.newBufferedReader(meshFile)) { mesh = JsonParser.parseReader(reader).getAsJsonObject(); }
        try (var reader = Files.newBufferedReader(animationFile)) { animation = JsonParser.parseReader(reader).getAsJsonObject(); }
        if (!animation.getAsJsonObject("clips").has("idle")) return false;
        var pairs = MeshSurfaceCheck.restPairs(mesh, animation);
        int allowed = KNOWN_COPLANAR_PAIRS.getOrDefault(name, 0);
        check(pairs.size() <= allowed, name + ".mesh.json will flicker in game (z-fighting): " + pairs.size() + " part pairs have same-facing faces on one plane"
                + " that overlap in the rest pose (allowed " + allowed + "): " + pairs.stream().limit(12).toList()
                + ". Run ../harness/v2/tools/coplanar_poses.py on it and move one face of each pair by 0.25 px.");
        check(pairs.size() == allowed, name + ".mesh.json improved to " + pairs.size() + " coplanar pairs; lower its entry in KNOWN_COPLANAR_PAIRS (" + allowed + ").");
        return true;
    }

    private static void checkAnimation(Path file) throws IOException {
        JsonObject root;
        try (var reader = Files.newBufferedReader(file)) { root = JsonParser.parseReader(reader).getAsJsonObject(); }
        long keys = 0, redundant = 0;
        for (var clip : root.getAsJsonObject("clips").entrySet()) {
            for (var element : clip.getValue().getAsJsonObject().getAsJsonArray("tracks")) {
                JsonObject track = element.getAsJsonObject();
                if (track.has("interpolation") && track.get("interpolation").getAsString().equals("catmullrom")) continue;
                JsonArray rows = track.getAsJsonArray("keys");
                double[] tolerance = tolerance(track, rows.get(0).getAsJsonArray().size());
                for (int i = 1; i + 1 < rows.size(); i++) {
                    JsonArray a = rows.get(i - 1).getAsJsonArray(), m = rows.get(i).getAsJsonArray(), b = rows.get(i + 1).getAsJsonArray();
                    double u = (m.get(0).getAsDouble() - a.get(0).getAsDouble()) / (b.get(0).getAsDouble() - a.get(0).getAsDouble());
                    boolean onLine = true;
                    for (int j = 1; j < m.size() && onLine; j++) {
                        double predicted = a.get(j).getAsDouble() + u * (b.get(j).getAsDouble() - a.get(j).getAsDouble());
                        onLine = Math.abs(predicted - m.get(j).getAsDouble()) <= tolerance[j - 1];
                    }
                    keys++;
                    if (onLine) redundant++;
                }
            }
        }
        double share = keys == 0 ? 0 : (double) redundant / keys;
        check(share <= MAX_REDUNDANT, file.getFileName() + " is a dense export: " + Math.round(share * 100)
                + "% of its keys are linear filler. Run ../harness/tools/native_animation.py simplify on it.");
    }

    private static double[] tolerance(JsonObject track, int width) {
        if (track.has("channel")) {
            double t = switch (track.get("channel").getAsString()) {
                case "position" -> POSITION;
                case "rotation" -> ROTATION;
                default -> SCALE;
            };
            return new double[]{t, t, t};
        }
        double[] tolerance = new double[width - 1];
        for (int i = 0; i < tolerance.length; i++) tolerance[i] = i < 3 ? POSITION : i < 6 ? ROTATION : SCALE;
        return tolerance;
    }

    private static void checkDecimals(Path file, Pattern pattern, String decimals) throws IOException {
        var matcher = pattern.matcher(Files.readString(file));
        check(!matcher.find(), file.getFileName() + " carries unrounded numbers such as " + (matcher.hitEnd() ? "" : matcher.group())
                + ". Run ../harness/tools/native_animation.py round-motion with " + decimals + " decimals.");
    }

    private static Path resource(String name) throws URISyntaxException {
        var url = AssetRegressionTest.class.getResource(name);
        check(url != null && url.getProtocol().equals("file"), name + " must be a bundled resource on the test classpath");
        return Path.of(url.toURI());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
