package com.digicube;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the agent instructions navigable. {@code AGENTS.md} is loaded into every agent task, so it stays within a
 * budget and holds a map; each topic guide under {@code agents/} is listed in that map and stays within its own
 * budget; every relative link and anchor between them resolves; and nothing silently changes which file an agent
 * loads. The rules are in {@code AGENTS.md}, section 7. Every failure says what to fix.
 */
public final class AgentDocsRegressionTest {
    private AgentDocsRegressionTest() {}

    /** Past these, AGENTS.md is turning back into the monolith that agents could not afford to read whole. */
    private static final int MAX_MAP_LINES = 200, MAX_MAP_BYTES = 16 * 1024;
    /** A guide past this covers two topics. */
    private static final int MAX_GUIDE_BYTES = 16 * 1024;
    /** Claude Code reads AGENTS.md only while none of these exist, unless they import it. */
    private static final List<String> CLAUDE_FILES = List.of("CLAUDE.md", "CLAUDE.local.md", ".claude/CLAUDE.md");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("#{1,6}\\s+(.+?)\\s*");
    /** An {@code @path} outside code is an import Claude Code inlines into every task. */
    private static final Pattern IMPORT = Pattern.compile("(?:^|\\s)@[\\w./~-]+");
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");

    /** @param args the repository root */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path map = root.resolve("AGENTS.md");
        String mapText = read(map);
        List<String> errors = new ArrayList<>();

        long mapLines = mapText.lines().count();
        int mapBytes = mapText.getBytes(StandardCharsets.UTF_8).length;
        if (mapLines > MAX_MAP_LINES || mapBytes > MAX_MAP_BYTES) {
            errors.add("AGENTS.md has " + mapLines + " lines and " + mapBytes + " bytes (budget " + MAX_MAP_LINES
                    + " lines, " + MAX_MAP_BYTES + " bytes): move what only some tasks need into the guide for its"
                    + " area under agents/");
        }
        checkImports(mapText, errors);

        List<Path> guides;
        try (Stream<Path> files = Files.walk(root.resolve("agents"))) {
            guides = files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }
        if (guides.isEmpty()) errors.add("agents/ holds no guides: restore them or remove the map from AGENTS.md");
        for (Path guide : guides) {
            String name = root.relativize(guide).toString().replace('\\', '/');
            String file = guide.getFileName().toString();
            if (file.equals("AGENTS.md") || file.equals("CLAUDE.md")) {
                errors.add(name + ": agents load a file of that name on their own; rename the guide");
            }
            String text = read(guide);
            if (!mapText.contains("](" + name + ")")) {
                errors.add(name + " is missing from the map: add a link to it in AGENTS.md section 3");
            }
            if (!text.startsWith("# ")) errors.add(name + ": open the guide with a # title");
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_GUIDE_BYTES) {
                errors.add(name + " has " + bytes + " bytes (budget " + MAX_GUIDE_BYTES + "): split it into two guides"
                        + " and map both");
            }
            checkLinks(root, guide, text, errors);
        }
        checkLinks(root, map, mapText, errors);

        for (String claude : CLAUDE_FILES) {
            Path file = root.resolve(claude);
            if (!Files.exists(file)) continue;
            String first = read(file).lines().filter(line -> !line.isBlank()).findFirst().orElse("").strip();
            if (!first.equals("@AGENTS.md") && !first.equals("@../AGENTS.md")) {
                errors.add(claude + " makes Claude Code skip AGENTS.md: make its first line an import of AGENTS.md"
                        + " or delete it");
            }
        }

        if (!errors.isEmpty()) throw new AssertionError("agent docs:\n  " + String.join("\n  ", errors));
        System.out.println("agent docs: AGENTS.md " + mapLines + " lines, " + mapBytes + " bytes; "
                + guides.size() + " guides; every link resolves");
    }

    /** Relative links must reach a file inside the repository, and an anchor a heading in it. */
    private static void checkLinks(Path root, Path file, String text, List<String> errors) throws IOException {
        String name = root.relativize(file).toString().replace('\\', '/');
        Matcher link = LINK.matcher(text);
        while (link.find()) {
            String target = link.group(1);
            if (target.contains("://") || target.startsWith("mailto:")) continue;
            int hash = target.indexOf('#');
            String path = hash < 0 ? target : target.substring(0, hash);
            String anchor = hash < 0 ? null : target.substring(hash + 1);
            Path resolved = path.isEmpty() ? file : file.getParent().resolve(path).normalize();
            // The design documents and the harness are private sibling folders a clone may not have.
            if (!resolved.startsWith(root)) continue;
            if (!Files.exists(resolved)) {
                errors.add(name + " links to missing " + target + ": fix the path or drop the link");
            } else if (anchor != null && !anchor.isEmpty() && !Files.isDirectory(resolved)
                    && !anchors(read(resolved)).contains(anchor)) {
                errors.add(name + " links to missing anchor " + target + ": point it at a heading that exists");
            }
        }
    }

    /** AGENTS.md is read natively, so an import there would load another file into every task. */
    private static void checkImports(String text, List<String> errors) {
        boolean fence = false;
        int number = 0;
        for (String line : text.split("\n")) {
            number++;
            if (line.strip().startsWith("```")) fence = !fence;
            if (fence) continue;
            Matcher found = IMPORT.matcher(CODE_SPAN.matcher(line).replaceAll(""));
            if (found.find()) {
                errors.add("AGENTS.md line " + number + " has an @ import (" + found.group().strip() + "): link the"
                        + " guide instead, or put the text in backticks");
            }
        }
    }

    /** Heading anchors as GitHub makes them: lower case, punctuation dropped, spaces as hyphens. */
    private static Set<String> anchors(String text) {
        Set<String> anchors = new HashSet<>();
        boolean fence = false;
        for (String line : text.split("\n")) {
            if (line.strip().startsWith("```")) fence = !fence;
            if (fence) continue;
            Matcher heading = HEADING.matcher(line);
            if (!heading.matches()) continue;
            StringBuilder slug = new StringBuilder();
            heading.group(1).toLowerCase(Locale.ROOT).codePoints().forEach(c -> {
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_') slug.appendCodePoint(c);
                else if (c == ' ') slug.append('-');
            });
            anchors.add(slug.toString());
        }
        return anchors;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
