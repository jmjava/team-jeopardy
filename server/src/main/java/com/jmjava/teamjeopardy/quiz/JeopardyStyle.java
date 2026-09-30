package com.jmjava.teamjeopardy.quiz;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared Jeopardy phrasing helpers: answers as questions, prompts as statements,
 * and redaction so the correct response is not sitting in the clue text.
 */
public final class JeopardyStyle {

    private static final Pattern ALREADY_QUESTION = Pattern.compile(
            "^(what|who|where|when|which)\\s+(is|are|was|were)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INTERROGATIVE_START = Pattern.compile(
            "^(how many|how much|who|what|where|when|which|why)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private JeopardyStyle() {
    }

    public static String whatIs(String answer) {
        return asQuestion(answer, "What");
    }

    public static String whoIs(String answer) {
        return asQuestion(answer, "Who");
    }

    public static String asQuestion(String answer, String pronoun) {
        String trimmed = trimAnswer(answer);
        if (trimmed.isEmpty()) {
            return pronoun + " is it?";
        }
        if (ALREADY_QUESTION.matcher(trimmed).find()) {
            return trimmed.endsWith("?") ? trimmed : trimmed + "?";
        }
        return pronoun + " is " + trimmed + "?";
    }

    public static String answerToken(String response) {
        if (response == null) {
            return "";
        }
        String trimmed = response.trim();
        var matcher = ALREADY_QUESTION.matcher(trimmed);
        if (matcher.find()) {
            trimmed = trimmed.substring(matcher.end()).trim();
        }
        if (trimmed.endsWith("?")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    public static boolean looksLikeQuestion(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String first = prompt.strip().split("\\R", 2)[0].trim();
        return first.endsWith("?") || INTERROGATIVE_START.matcher(first).find();
    }

    public static boolean promptLeaksAnswer(String prompt, String response) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String token = answerToken(response);
        if (token.length() < 4 || token.chars().allMatch(Character::isDigit)) {
            return false;
        }
        String haystack = prompt.toLowerCase(Locale.ROOT);
        String needle = token.toLowerCase(Locale.ROOT);
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !isIdentChar(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean endOk = end >= haystack.length() || !isIdentChar(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            idx = haystack.indexOf(needle, idx + 1);
        }
        return false;
    }

    public static String redact(String text, String secret) {
        if (text == null) {
            return "";
        }
        if (secret == null || secret.isBlank() || secret.length() < 2) {
            return text;
        }
        String quoted = Pattern.quote(secret);
        return text.replaceAll("(?i)(?<![A-Za-z0-9_])" + quoted + "(?![A-Za-z0-9_])", "___");
    }

    public static String parentDir(String path) {
        if (path == null || path.isBlank()) {
            return "the project";
        }
        Path parsed = Path.of(path.replace('\\', '/'));
        Path parent = parsed.getParent();
        if (parent == null) {
            return path;
        }
        String value = parent.toString().replace('\\', '/');
        return value.isBlank() ? path : value;
    }

    public static String pathHint(String path, String secret) {
        String hint = parentDir(path);
        if (hint.equals(path) || hint.isBlank()) {
            hint = basename(path);
        }
        return redact(hint, secret);
    }

    public static String basename(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        Path parsed = Path.of(path.replace('\\', '/'));
        Path name = parsed.getFileName();
        return name == null ? path : name.toString();
    }

    private static String trimAnswer(String answer) {
        if (answer == null) {
            return "";
        }
        String trimmed = answer.trim();
        if ((trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() > 1)
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1)) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
