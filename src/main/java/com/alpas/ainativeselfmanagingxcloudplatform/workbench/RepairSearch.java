package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import static com.alpas.ainativeselfmanagingxcloudplatform.workbench.CodeModel.*;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded single-token mutation search; explicitly distinct from generative inference. */
public final class RepairSearch {
    public static final List<String> DEFAULT_ORDER = List.of("BOUNDARY", "EQUALITY", "ARITHMETIC");
    private static final Pattern TOKENS =
            Pattern.compile(
                    "(?s)//[^\\n"
                        + "]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|>=|<=|==|!=|>|<|[+*/-]");

    public record Candidate(List<Change> changes, String kind) {}

    public static List<Candidate> candidates(
            Map<String, String> files, List<String> order, int limit) {
        var result = new ArrayList<Candidate>();
        var replacements =
                Map.of(
                        ">=", ">", ">", ">=", "<=", "<", "<", "<=", "==", "!=", "!=", "==", "+",
                        "-", "-", "+", "*", "/", "/", "*");
        for (String kind : order)
            for (var file : new TreeMap<>(files).entrySet()) {
                if (!file.getKey().startsWith("src/main/java/")) continue;
                var matcher = TOKENS.matcher(file.getValue());
                while (matcher.find()) {
                    String token = matcher.group();
                    if (!replacements.containsKey(token)) continue;
                    String actualKind =
                            List.of(">=", ">", "<=", "<").contains(token)
                                    ? "BOUNDARY"
                                    : List.of("==", "!=").contains(token)
                                            ? "EQUALITY"
                                            : "ARITHMETIC";
                    if (!kind.equals(actualKind)) continue;
                    String content =
                            file.getValue().substring(0, matcher.start())
                                    + replacements.get(token)
                                    + file.getValue().substring(matcher.end());
                    result.add(
                            new Candidate(
                                    List.of(
                                            new Change(
                                                    file.getKey(),
                                                    IntentCompiler.digest(file.getValue()),
                                                    content)),
                                    kind));
                    if (result.size() >= limit) return List.copyOf(result);
                }
            }
        return List.copyOf(result);
    }

    public static Map<String, String> validateAndApply(
            Map<String, String> base, List<Change> changes) {
        if (changes == null || changes.isEmpty() || changes.size() > 5) throw denied();
        var result = new TreeMap<>(base);
        var seen = new HashSet<String>();
        int bytes = 0;
        for (var change : changes) {
            if (change == null
                    || change.path() == null
                    || !change.path().startsWith("src/main/java/")
                    || !base.containsKey(change.path())
                    || !seen.add(change.path())
                    || !IntentCompiler.digest(base.get(change.path())).equals(change.beforeDigest())
                    || change.content() == null
                    || change.content().length() > 32_768
                    || change.content().indexOf('\0') >= 0) throw denied();
            bytes += change.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > 65_536) throw denied();
            result.put(change.path(), change.content());
        }
        if (base.equals(result)) throw denied();
        return result;
    }

    private static PlatformException denied() {
        return new PlatformException(
                422,
                "PATCH_SCOPE_DENIED",
                "Patches may only replace up to five existing source files with matching before"
                    + " hashes. Tests, build files, paths and dependencies are immutable.");
    }
}
