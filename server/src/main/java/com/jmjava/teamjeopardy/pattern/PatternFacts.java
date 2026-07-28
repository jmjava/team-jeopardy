package com.jmjava.teamjeopardy.pattern;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PatternFacts {

    private PatternFacts() {
    }

    static PatternFact of(
            String strategy,
            String language,
            String patternName,
            String text,
            String subjectType,
            String subjectId,
            String predicate,
            String objectType,
            String objectId,
            double confidence,
            String evidenceFile,
            List<String> tags,
            Map<String, Object> attributes
    ) {
        String hash = sha1(strategy + "|" + language + "|" + predicate + "|" + subjectId + "|" + objectId + "|" + text)
                .substring(0, 12);
        Map<String, Object> attrs = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        attrs.putIfAbsent("pattern", patternName);
        attrs.putIfAbsent("language", language);
        List<String> tagList = tags == null ? List.of(language) : tags;
        return new PatternFact(
                "pattern:" + language + ":" + strategy + ":" + hash,
                strategy,
                language,
                text,
                subjectType,
                subjectId,
                predicate,
                objectType,
                objectId,
                confidence,
                evidenceFile,
                List.copyOf(tagList),
                Map.copyOf(attrs)
        );
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
