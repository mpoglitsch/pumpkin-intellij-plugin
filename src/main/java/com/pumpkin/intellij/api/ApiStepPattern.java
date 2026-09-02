package com.pumpkin.intellij.api;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gherkin steps that match the generic API send pattern and extracts
 * the {@code {apiRequestDefinition}} and {@code {apiNotation}} parts.
 *
 * <p>Supported forms:
 * <pre>
 *   (I )send a/an {apiRequestDefinition} to {apiNotation} including these/this parameter/parameters
 *   (I )send a/an {apiRequestDefinition} to {apiNotation} without parameters
 * </pre>
 */
public final class ApiStepPattern {

    // Matches both "including these/this parameter/parameters" and "without parameters".
    // Dot does not match \n (no DOTALL), so the match stops at the step line boundary
    // even when getText() includes a trailing data table.
    private static final Pattern STEP_PATTERN = Pattern.compile(
            "(?:I\\s+)?send\\s+an?\\s+(.+?)\\s+to\\s+([A-Z_][A-Z0-9_]*)\\s+" +
            "(?:including\\s+(?:these|this)\\s+parameters?|without\\s+parameters)",
            Pattern.CASE_INSENSITIVE
    );

    private ApiStepPattern() {}

    /**
     * Returns a {@link ParsedApiStep} if the step matches the API send pattern,
     * or {@code null} otherwise.
     * The {@link ParsedApiStep#getDefinitionRangeInStep()} range is relative to
     * {@link GherkinStep#getText()} (the full element text including keyword).
     */
    @Nullable
    public static ParsedApiStep parse(@NotNull GherkinStep step) {
        String text = step.getText();
        Matcher m = STEP_PATTERN.matcher(text);
        if (!m.find()) return null;

        return new ParsedApiStep(
                m.group(1),
                m.group(2),
                TextRange.create(m.start(1), m.end(1))
        );
    }

    public static final class ParsedApiStep {
        private final @NotNull String apiRequestDefinition;
        private final @NotNull String apiNotation;
        /** Range of the apiRequestDefinition within {@code step.getText()}. */
        private final @NotNull TextRange definitionRangeInStep;

        ParsedApiStep(@NotNull String apiRequestDefinition,
                      @NotNull String apiNotation,
                      @NotNull TextRange definitionRangeInStep) {
            this.apiRequestDefinition = apiRequestDefinition;
            this.apiNotation = apiNotation;
            this.definitionRangeInStep = definitionRangeInStep;
        }

        public @NotNull String getApiRequestDefinition() { return apiRequestDefinition; }
        public @NotNull String getApiNotation()          { return apiNotation; }
        public @NotNull TextRange getDefinitionRangeInStep() { return definitionRangeInStep; }
    }
}
