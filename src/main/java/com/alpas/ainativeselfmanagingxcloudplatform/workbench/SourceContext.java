package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic retrieval, versioned separately from model prompts. Full files retain digest
 * checks.
 */
public final class SourceContext {
    public static final String VERSION = "ranked-context-v1";

    public record Selection(
            Map<String, String> files, List<String> terms, int bytes, List<String> omitted) {}

    private SourceContext() {}

    public static Selection select(Map<String, String> files, String instruction, String failure) {
        var terms =
                Pattern.compile("[^A-Za-z0-9_]+")
                        .splitAsStream(instruction + " " + failure)
                        .filter(t -> t.length() > 3)
                        .map(t -> t.toLowerCase(Locale.ROOT))
                        .distinct()
                        .limit(40)
                        .toList();
        var ranked =
                files.keySet().stream()
                        .sorted(
                                Comparator.<String>comparingInt(
                                                path -> score(path, files.get(path), terms))
                                        .reversed()
                                        .thenComparing(Comparator.naturalOrder()))
                        .toList();
        var result = new TreeMap<String, String>();
        int bytes = 0;
        for (var path : ranked) {
            int size = files.get(path).getBytes(StandardCharsets.UTF_8).length;
            if (result.size() < 10 && bytes + size <= 24000) {
                result.put(path, files.get(path));
                bytes += size;
            }
        }
        if (result.keySet().stream().noneMatch(p -> p.startsWith("src/main/")))
            throw new PlatformException(
                    422,
                    "CONTEXT_BUDGET",
                    "No complete editable source file fits the 24 KB retrieval budget.");
        return new Selection(
                Collections.unmodifiableMap(result),
                terms,
                bytes,
                files.keySet().stream().filter(p -> !result.containsKey(p)).sorted().toList());
    }

    private static int score(String path, String content, List<String> terms) {
        int score = path.startsWith("src/test/") ? 50 : 0;
        String name = path.toLowerCase(Locale.ROOT), body = content.toLowerCase(Locale.ROOT);
        for (var term : terms) {
            if (name.contains(term)) score += 20;
            if (body.contains(term)) score += 2;
        }
        return score;
    }
}
