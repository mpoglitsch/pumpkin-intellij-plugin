package com.pumpkin.intellij.matching;

import com.pumpkin.intellij.model.PumpkinProcessDefinition;
import com.pumpkin.intellij.model.PumpkinProcessVariable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central matching logic for Pumpkin Processes.
 * All features (completion, navigation, annotator, inspection) must use this class
 * so that matching behaviour is consistent across the plugin.
 */
public final class PumpkinProcessMatcher {

    private static final Pattern VARIABLE_SYNTAX = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    private PumpkinProcessMatcher() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns {@code true} if {@code invocationText} matches the given process definition. */
    public static boolean matches(@NotNull PumpkinProcessDefinition def, @NotNull String invocationText) {
        return buildJavaMatcher(def, invocationText) != null;
    }

    /**
     * Returns a {@link MatchResult} with captured variable values and their positions within
     * {@code invocationText}, or {@code null} if the invocation does not match.
     */
    public static @Nullable MatchResult match(@NotNull PumpkinProcessDefinition def,
                                              @NotNull String invocationText) {
        Matcher m = buildJavaMatcher(def, invocationText);
        if (m == null) return null;

        List<VariableMatch> vars = new ArrayList<>();
        List<PumpkinProcessVariable> defVars = def.getVariables();
        for (int i = 0; i < defVars.size(); i++) {
            int g = i + 1;
            vars.add(new VariableMatch(defVars.get(i).getName(), m.group(g), m.start(g), m.end(g)));
        }
        return new MatchResult(vars);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    @Nullable
    private static Matcher buildJavaMatcher(@NotNull PumpkinProcessDefinition def,
                                            @NotNull String invocationText) {
        String regex = buildRegex(def.getScenarioName(), def.getVariables().size());
        try {
            Pattern p = Pattern.compile(regex);
            Matcher m = p.matcher(invocationText);
            return m.matches() ? m : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Converts a scenario name pattern like {@code "Create customer {name} for service {id}"}
     * into a Java regex with a capturing group per variable.
     * Intermediate variables use non-greedy capture; the last variable is greedy.
     */
    @NotNull
    static String buildRegex(@NotNull String scenarioName, int variableCount) {
        StringBuilder sb = new StringBuilder("^");
        Matcher vm = VARIABLE_SYNTAX.matcher(scenarioName);
        int lastEnd = 0;
        int varIdx = 0;

        while (vm.find()) {
            sb.append(Pattern.quote(scenarioName.substring(lastEnd, vm.start())));
            boolean isLast = (varIdx == variableCount - 1);
            sb.append(isLast ? "(.+)" : "(.+?)");
            lastEnd = vm.end();
            varIdx++;
        }
        sb.append(Pattern.quote(scenarioName.substring(lastEnd)));
        sb.append("$");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    public static final class MatchResult {
        private final @NotNull List<VariableMatch> variables;

        public MatchResult(@NotNull List<VariableMatch> variables) {
            this.variables = variables;
        }

        public @NotNull List<VariableMatch> getVariables() { return variables; }
    }

    public static final class VariableMatch {
        private final @NotNull String name;
        private final @NotNull String value;
        /** Start offset within the invocation text string (0-based, inclusive). */
        private final int startOffset;
        /** End offset within the invocation text string (0-based, exclusive). */
        private final int endOffset;

        public VariableMatch(@NotNull String name, @NotNull String value,
                             int startOffset, int endOffset) {
            this.name = name;
            this.value = value;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public @NotNull String getName() { return name; }
        public @NotNull String getValue() { return value; }
        public int getStartOffset() { return startOffset; }
        public int getEndOffset() { return endOffset; }
    }
}
