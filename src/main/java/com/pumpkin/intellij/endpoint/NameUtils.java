package com.pumpkin.intellij.endpoint;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Case-conversion helpers for turning a free-text endpoint name (or an existing
 * {@code ApiNotation} constant name) into the identifiers the code generator needs:
 * an enum constant name, a camelCase field/filename, and a kebab-case folder name.
 */
final class NameUtils {

    private NameUtils() {}

    /** {@code "Create Order"} → {@code "CREATE_ORDER"} (enum constant name). */
    static @NotNull String toUpperSnakeCase(@NotNull String input) {
        return splitWords(input).stream()
                .map(w -> w.toUpperCase(Locale.ROOT))
                .collect(Collectors.joining("_"));
    }

    /** {@code "Create Order"} → {@code "createOrder"} (template field/filename base). */
    static @NotNull String toCamelCase(@NotNull String input) {
        List<String> words = splitWords(input);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).toLowerCase(Locale.ROOT);
            if (i == 0) {
                sb.append(w);
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    /** {@code "BACKEND_API"} → {@code "backend-api"} (template folder name). */
    static @NotNull String toKebabCase(@NotNull String input) {
        return splitWords(input).stream()
                .map(w -> w.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining("-"));
    }

    /**
     * Splits on runs of non-alphanumeric characters and on lower-to-upper camelCase
     * boundaries, e.g. {@code "Create Order"} / {@code "createOrder"} / {@code "BACKEND_API"}
     * all split into {@code ["Create"/"create"/"BACKEND", "Order"/"Order"/"API"]}.
     */
    private static @NotNull List<String> splitWords(@NotNull String input) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (current.length() > 0
                        && Character.isUpperCase(c)
                        && Character.isLowerCase(current.charAt(current.length() - 1))) {
                    words.add(current.toString());
                    current.setLength(0);
                }
                current.append(c);
            } else if (current.length() > 0) {
                words.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) words.add(current.toString());
        return words;
    }
}
