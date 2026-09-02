package com.pumpkin.intellij.util;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinTag;

/**
 * Stateless helpers for inspecting Gherkin PSI elements.
 * All interaction with Gherkin PSI classes is isolated here so that the rest
 * of the plugin does not spread Gherkin API dependencies.
 */
public final class GherkinPsiUtil {

    private static final String PROCESS_PREFIX = "Process: ";
    // The step definitions expose two step patterns distinguished by this trailing text:
    // "^Process: (.*) with data$" (takes a DataTable) and "^Process: (.*) without data$" (no table).
    private static final String WITH_DATA_SUFFIX = " with data";
    private static final String WITHOUT_DATA_SUFFIX = " without data";
    // GherkinTag.getName() returns getText(), which INCLUDES the '@' prefix.
    private static final String PUMPKIN_TAG = "@pumpkin";
    private static final String PROCESS_REQUIRED_PREFIX = "@processRequired(";

    private GherkinPsiUtil() {}

    // -------------------------------------------------------------------------
    // Step helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the step's text (after the keyword) starts with {@code "Process: "}.
     * Only the prefix is tested – not whether a matching process definition exists.
     */
    public static boolean isProcessStep(@NotNull GherkinStep step) {
        String name = step.getName();
        return name != null && name.startsWith(PROCESS_PREFIX);
    }

    /**
     * Returns the invocation text: everything after {@code "Process: "} in the step name
     * and before the trailing {@code " with data"}/{@code " without data"} marker (if present),
     * or {@code null} if the step is not a Process step.
     */
    public static @Nullable String getProcessInvocationText(@NotNull GherkinStep step) {
        String name = step.getName();
        if (name == null || !name.startsWith(PROCESS_PREFIX)) return null;
        String invocation = name.substring(PROCESS_PREFIX.length());
        return stripDataSuffix(invocation);
    }

    /**
     * Strips a trailing {@code " with data"}/{@code " without data"} marker from the invocation
     * text, if present. Steps without a marker are returned unchanged.
     */
    private static @NotNull String stripDataSuffix(@NotNull String invocation) {
        if (invocation.endsWith(WITH_DATA_SUFFIX)) {
            return invocation.substring(0, invocation.length() - WITH_DATA_SUFFIX.length());
        }
        if (invocation.endsWith(WITHOUT_DATA_SUFFIX)) {
            return invocation.substring(0, invocation.length() - WITHOUT_DATA_SUFFIX.length());
        }
        return invocation;
    }

    // -------------------------------------------------------------------------
    // Scenario helpers
    // -------------------------------------------------------------------------

    /** Returns {@code true} when the scenario has the {@code @pumpkin} tag. */
    public static boolean hasPumpkinTag(@NotNull GherkinScenario scenario) {
        for (GherkinTag tag : scenario.getTags()) {
            if (PUMPKIN_TAG.equals(tag.getName())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when the scenario is a non-background regular Scenario AND
     * carries the {@code @pumpkin} tag.
     *
     * <p>Note: {@code GherkinScenarioOutline} is a separate interface from {@link GherkinScenario};
     * callers that pass in a {@link GherkinScenario} instance have already excluded outlines
     * by type. Background sections are excluded here explicitly via {@code isBackground()}.
     * Scenario Outlines are not supported as Processes to avoid conflicts with the Examples table.
     */
    public static boolean isPumpkinScenario(@NotNull GherkinScenario scenario) {
        return !scenario.isBackground() && hasPumpkinTag(scenario);
    }

    /**
     * Parses the {@code @processRequired(...)} tag value and returns the list of required
     * parameter names, or an empty list if the tag is absent or has no parameters.
     */
    public static @NotNull java.util.List<String> parseRequiredParameters(@NotNull GherkinScenario scenario) {
        for (GherkinTag tag : scenario.getTags()) {
            String name = tag.getName();
            if (name != null && name.startsWith(PROCESS_REQUIRED_PREFIX) && name.endsWith(")")) {
                String content = name.substring(PROCESS_REQUIRED_PREFIX.length(), name.length() - 1);
                java.util.List<String> result = new java.util.ArrayList<>();
                for (String part : content.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                        result.add(trimmed);
                    }
                }
                return result;
            }
        }
        return java.util.Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Ancestry helpers
    // -------------------------------------------------------------------------

    /** Returns the enclosing {@link GherkinStep} for any PSI element, or {@code null}. */
    public static @Nullable GherkinStep findEnclosingStep(@NotNull PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, GherkinStep.class, false);
    }
}
