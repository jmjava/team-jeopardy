package com.jmjava.teamjeopardy.quiz;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moderator guidance for what kinds of questions to emphasize on this board.
 * Free-text plus optional structured focus tags.
 */
public record QuestionHints(
        String text,
        List<String> focuses
) {
    public static QuestionHints empty() {
        return new QuestionHints("", List.of());
    }

    public static QuestionHints of(String text) {
        return of(text, List.of());
    }

    public static QuestionHints of(String text, List<String> focuses) {
        String cleaned = text == null ? "" : text.trim();
        List<String> tags = new ArrayList<>();
        if (focuses != null) {
            for (String focus : focuses) {
                if (focus != null && !focus.isBlank()) {
                    tags.add(focus.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        // Derive focus tags from free text when chips were not sent.
        String lower = cleaned.toLowerCase(Locale.ROOT);
        for (String candidate : List.of(
                "patterns", "pull-requests", "qa", "apis", "architecture", "components", "security"
        )) {
            if (matchesFocus(lower, candidate) && !tags.contains(candidate)) {
                tags.add(candidate);
            }
        }
        return new QuestionHints(cleaned, List.copyOf(tags));
    }

    public boolean isBlank() {
        return (text == null || text.isBlank()) && (focuses == null || focuses.isEmpty());
    }

    public String combined() {
        StringBuilder sb = new StringBuilder();
        if (text != null && !text.isBlank()) {
            sb.append(text.trim());
        }
        if (focuses != null && !focuses.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("Focus: ").append(String.join(", ", focuses));
        }
        return sb.toString();
    }

    /**
     * Score how well a category matches moderator hints (higher = prefer).
     */
    public int scoreCategory(Category category) {
        if (isBlank() || category == null) {
            return 0;
        }
        String title = category.title() == null ? "" : category.title().toLowerCase(Locale.ROOT);
        String blob = title + " " + category.clues().stream()
                .limit(3)
                .map(c -> (c.prompt() == null ? "" : c.prompt()) + " " + (c.response() == null ? "" : c.response()))
                .reduce("", (a, b) -> a + " " + b)
                .toLowerCase(Locale.ROOT);

        int score = 0;
        Set<String> focusSet = Set.copyOf(focuses == null ? List.of() : focuses);
        if (focusSet.contains("patterns") && (title.contains("pattern") || blob.contains("pattern"))) {
            score += 48;
        }
        if (focusSet.contains("pull-requests") && (title.contains("pr:") || title.contains("pull") || blob.contains("pr #"))) {
            score += 52;
        }
        if (focusSet.contains("qa") && (title.startsWith("qa:") || blob.contains("regression") || blob.contains("test matrix"))) {
            score += 46;
        }
        if (focusSet.contains("apis") && (title.contains("api") || title.contains("contract") || blob.contains("signature"))) {
            score += 42;
        }
        if (focusSet.contains("architecture") && (title.contains("module") || title.contains("ownership") || title.contains("hierarchy"))) {
            score += 40;
        }
        if (focusSet.contains("components") && (title.contains("component") || blob.contains("vue"))) {
            score += 40;
        }
        if (focusSet.contains("security") && (blob.contains("auth") || blob.contains("token") || blob.contains("inject"))) {
            score += 36;
        }

        String hay = combined().toLowerCase(Locale.ROOT);
        for (String token : hay.split("[^a-z0-9+#./_-]+")) {
            if (token.length() < 3) {
                continue;
            }
            if (title.contains(token)) {
                score += 8;
            } else if (blob.contains(token)) {
                score += 3;
            }
        }
        return score;
    }

    private static boolean matchesFocus(String lower, String focus) {
        return switch (focus) {
            case "patterns" -> lower.contains("pattern") || lower.contains("gof") || lower.contains("design");
            case "pull-requests" -> lower.contains("pull request") || lower.contains(" pr")
                    || lower.startsWith("pr ") || lower.contains("github pr") || lower.contains("recent pr");
            case "qa" -> lower.contains("qa") || lower.contains("test") || lower.contains("regression")
                    || lower.contains("quality");
            case "apis" -> lower.contains("api") || lower.contains("contract") || lower.contains("endpoint");
            case "architecture" -> lower.contains("architect") || lower.contains("module")
                    || lower.contains("structure") || lower.contains("ownership");
            case "components" -> lower.contains("component") || lower.contains("frontend") || lower.contains("vue");
            case "security" -> lower.contains("security") || lower.contains("auth") || lower.contains("vulnerab");
            default -> false;
        };
    }
}
