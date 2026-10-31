package com.tungsten.fcl.vr;

import com.google.gson.JsonElement;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QuestModVersionRules {
    private static final Pattern NUMERIC = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
    private static final Pattern TERM = Pattern.compile("(>=|<=|>|<|=|~|\\^)?([0-9]+(?:\\.[0-9]+)*(?:\\.[xX*])?|[xX*])");
    private static final Pattern RANGE = Pattern.compile("([\\[(])([^,\\]()]*)?(?:,([^\\]()]*)?)?([\\])])");

    private QuestModVersionRules() {}

    static boolean matches(String version, JsonElement rules) {
        if (rules == null || rules.isJsonNull()) return false;
        if (rules.isJsonArray()) {
            for (JsonElement rule : rules.getAsJsonArray()) if (matches(version, rule)) return true;
            return false;
        }
        return rules.isJsonPrimitive() && rules.getAsJsonPrimitive().isString()
                && matches(version, rules.getAsString());
    }

    static boolean matches(String version, String rule) {
        try {
            return matchesChecked(version, rule);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean matchesChecked(String version, String rule) {
        if (version == null || rule == null || version.length() > 128 || rule.length() > 2048
                || !NUMERIC.matcher(version).matches()) return false;
        rule = rule.trim();
        if (rule.isEmpty()) return false;
        if (rule.startsWith("[") || rule.startsWith("(")) return maven(version, rule);
        for (String alternative : rule.split("\\s*\\|\\|\\s*", -1)) {
            String normalized = alternative.trim().replaceAll("(>=|<=|>|<|=|~|\\^)\\s+", "$1");
            boolean all = !normalized.isEmpty();
            for (String term : normalized.split("\\s+")) all &= term(version, term);
            if (all) return true;
        }
        return false;
    }

    private static boolean term(String version, String expression) {
        Matcher matcher = TERM.matcher(expression);
        if (!matcher.matches()) return false;
        String operator = matcher.group(1);
        String expected = matcher.group(2);
        if (expected.matches(".*[xX*]$")) {
            if (operator != null && !operator.equals("=")) return false;
            if (expected.length() == 1) return true;
            String prefix = expected.substring(0, expected.length() - 2);
            return version.equals(prefix) || version.startsWith(prefix + ".");
        }
        int comparison = compare(version, expected);
        if (operator == null || operator.equals("=")) return comparison == 0;
        return switch (operator) {
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            case "~", "^" -> comparison >= 0 && compare(version, upperBound(expected, operator)) < 0;
            default -> false;
        };
    }

    private static String upperBound(String version, String operator) {
        String[] values = version.split("\\.");
        int increment = operator.equals("~") ? Math.min(1, values.length - 1) : 0;
        if (operator.equals("^")) {
            while (increment < values.length - 1 && values[increment].equals("0")) increment++;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i <= increment; i++) {
            if (i > 0) result.append('.');
            result.append(i == increment ? Long.parseLong(values[i]) + 1 : Long.parseLong(values[i]));
        }
        return result.toString();
    }

    private static boolean maven(String version, String expression) {
        Matcher matcher = RANGE.matcher(expression);
        int end = 0;
        boolean matched = false;
        while (matcher.find()) {
            if (!expression.substring(end, matcher.start()).trim().replace(",", "").isEmpty()) return false;
            String low = matcher.group(2) == null ? "" : matcher.group(2).trim();
            String high = matcher.group(3);
            boolean valid;
            if (high == null) {
                valid = matcher.group(1).equals("[") && matcher.group(4).equals("]")
                        && NUMERIC.matcher(low).matches() && compare(version, low) == 0;
            } else {
                high = high.trim();
                if ((!low.isEmpty() && !NUMERIC.matcher(low).matches())
                        || (!high.isEmpty() && !NUMERIC.matcher(high).matches())) return false;
                valid = (low.isEmpty() || compare(version, low) > 0
                        || (compare(version, low) == 0 && matcher.group(1).equals("[")))
                        && (high.isEmpty() || compare(version, high) < 0
                        || (compare(version, high) == 0 && matcher.group(4).equals("]")));
            }
            matched |= valid;
            end = matcher.end();
        }
        return end == expression.length() && matched;
    }

    private static int compare(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? Long.parseLong(a[i]) : 0;
            long y = i < b.length ? Long.parseLong(b[i]) : 0;
            int difference = Long.compare(x, y);
            if (difference != 0) return difference;
        }
        return 0;
    }
}
