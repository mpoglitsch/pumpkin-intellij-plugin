package com.pumpkin.intellij.util;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinScenario;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless helpers for inspecting Gherkin PSI elements.
 * All interaction with Gherkin PSI classes is isolated here so that the rest
 * of the plugin does not spread Gherkin API dependencies.
 */
public final class GherkinPsiUtil {

    private static final String PROCESS_PREFIX = "Process: ";
    // "(I )these values are (not )present in <table>" / "(I )this value is (not )present in
    // <table>" - the DB step whose header-row table cells can contain
    // join(...)/rejoin(...)/saveToContext(...) specs - see the com.pumpkin.intellij.dbstep
    // package. Singular/plural pairing mirrors StoreContextValuesEnterHandler's own "these
    // values|this value" alias handling; the negated ("not present") form is the same step shape,
    // just asserting absence instead of presence.
    private static final Pattern DB_PRESENT_STEP = Pattern.compile(
            "(?:I\\s+)?(?:these values are|this value is)(?:\\s+not)? present in\\s+(.+)", Pattern.CASE_INSENSITIVE);
    // The step definitions expose two step patterns distinguished by this trailing text:
    // "^Process: (.*) with data$" (takes a DataTable) and "^Process: (.*) without data$" (no table).
    private static final String WITH_DATA_SUFFIX = " with data";
    private static final String WITHOUT_DATA_SUFFIX = " without data";
    // GherkinTag.getName() returns getText(), which INCLUDES the '@' prefix.
    // "@Pumpkin" is accepted as a case-variant alias of "@pumpkin" (matched case-insensitively).
    private static final String PUMPKIN_TAG = "@pumpkin";
    // "@requiredParameters(...)" is accepted as an alias of "@processRequired(...)".
    private static final String PROCESS_REQUIRED_PREFIX = "@processRequired(";
    private static final String REQUIRED_PARAMETERS_PREFIX = "@requiredParameters(";
    private static final String SETS_PARAMETERS_PREFIX = "@setsParameters(";
    // Deliberately named differently from "@setsParameters" above - that tag declares which
    // RPTAContext values a Process *sets* as a side effect (unrelated concept); this one selects
    // *which variant* of the Process to run.
    private static final String PROCESS_CONTEXT_TAG_PREFIX = "@ProcessContext(";
    // Trailing "| ContextName" suffix on a Process invocation step, after "with/without data".
    private static final Pattern CONTEXT_SUFFIX_PATTERN = Pattern.compile("^(.*)\\s+\\|\\s+(\\S+)\\s*$");

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
     * Returns the invocation text: everything after {@code "Process: "} in the step's own first
     * line of text and before the {@code | ContextName} suffix (if present - see
     * {@link #getInvocationContextName}) and the trailing {@code " with data"}/
     * {@code " without data"} marker, or {@code null} if the step is not a Process step. The
     * marker is always the literal tail of the step text - the context suffix, when present, sits
     * *before* it (e.g. {@code "X | MobileApp with data"}).
     */
    public static @Nullable String getProcessInvocationText(@NotNull GherkinStep step) {
        String invocation = rawInvocationLine(step);
        if (invocation == null) return null;
        return stripContextSuffix(stripDataSuffix(invocation));
    }

    /**
     * Returns the {@code ContextName} from a {@code | ContextName} suffix on a Process invocation
     * step (e.g. {@code Process: X | MobileApp with data}), or {@code null} if the step has no
     * such suffix or isn't a Process step at all. A missing suffix means "use the default
     * (untagged) {@code @ProcessContext} variant" - the same convention
     * {@code ProcessExecutor.execute}'s {@code contextName} parameter uses at runtime.
     */
    public static @Nullable String getInvocationContextName(@NotNull GherkinStep step) {
        String invocation = rawInvocationLine(step);
        if (invocation == null) return null;
        Matcher m = CONTEXT_SUFFIX_PATTERN.matcher(stripDataSuffix(invocation));
        return m.matches() ? m.group(2) : null;
    }

    /**
     * Returns the text after {@code "Process: "} on the step's own first line of raw text
     * ({@link GherkinStep#getText()}), or {@code null} if this isn't a Process step. Deliberately
     * based on raw text rather than {@link GherkinStep#getName()}: Gherkin's own PSI
     * implementation (GherkinStepImpl.getElementText()) builds {@code getName()} by concatenating
     * only specific child-token types (TEXT, STEP_PARAMETER, WHITE_SPACE, STEP_PARAMETER_TEXT,
     * STEP_PARAMETER_BRACE - confirmed by disassembling the bundled Gherkin plugin's own
     * GherkinStepImpl.class), which silently drops a literal {@code |} appearing inline in step
     * text - it's lexed with its own reserved table-cell-delimiter token type, not one of those -
     * even though it isn't part of an actual data table here. That previously made {@code |
     * ContextName} invisible to every caller of this method: {@code getInvocationContextName}
     * always returned {@code null}, and the "clean" invocation text still had the context name
     * glued onto the end (pipe silently removed, name kept), which then got swallowed into the
     * Process's own last {@code {variable}} capture group by {@code PumpkinProcessMatcher} and
     * colored as a matched variable value instead of a context name. Raw text has no such
     * filtering - lookups via {@code indexOf} rather than requiring the prefix at position 0
     * mirror {@code PumpkinProcessAnnotator}'s own already-working raw-text parsing, which finds
     * {@code "Process:"} the same way regardless of which keyword precedes it.
     */
    private static @Nullable String rawInvocationLine(@NotNull GherkinStep step) {
        String text = step.getText();
        int newlineIdx = text.indexOf('\n');
        String firstLine = newlineIdx >= 0 ? text.substring(0, newlineIdx) : text;
        if (firstLine.endsWith("\r")) {
            firstLine = firstLine.substring(0, firstLine.length() - 1);
        }

        int idx = firstLine.indexOf(PROCESS_PREFIX);
        return idx < 0 ? null : firstLine.substring(idx + PROCESS_PREFIX.length());
    }

    /**
     * Strips a trailing {@code " with data"}/{@code " without data"} marker from the invocation
     * text, if present. Steps without a marker are returned unchanged. This marker is always the
     * literal tail of the step text, regardless of whether a {@code | ContextName} suffix precedes
     * it, so this needs no awareness of that suffix at all.
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

    /**
     * Strips a trailing {@code | ContextName} suffix, if present - applied *after*
     * {@link #stripDataSuffix} since the suffix sits before that marker (e.g.
     * {@code "X | MobileApp"} -> {@code "X"}, once {@code stripDataSuffix} has already removed the
     * trailing {@code " with data"}/{@code " without data"}).
     */
    private static @NotNull String stripContextSuffix(@NotNull String invocation) {
        Matcher m = CONTEXT_SUFFIX_PATTERN.matcher(invocation);
        return m.matches() ? m.group(1) : invocation;
    }

    /** Returns {@code true} for a "these values are present in &lt;table&gt;" DB step. */
    public static boolean isDbPresentStep(@NotNull GherkinStep step) {
        String name = step.getName();
        return name != null && DB_PRESENT_STEP.matcher(name.trim()).matches();
    }

    /** The table name after "present in", or {@code null} if this isn't a {@link #isDbPresentStep} step. */
    public static @Nullable String getDbTableName(@NotNull GherkinStep step) {
        String name = step.getName();
        if (name == null) return null;
        Matcher m = DB_PRESENT_STEP.matcher(name.trim());
        return m.matches() ? m.group(1).trim() : null;
    }

    // -------------------------------------------------------------------------
    // Scenario helpers
    // -------------------------------------------------------------------------

    /** Returns {@code true} when the scenario has the {@code @pumpkin} tag (case-insensitive, so {@code @Pumpkin} also matches). */
    public static boolean hasPumpkinTag(@NotNull GherkinScenario scenario) {
        for (GherkinTag tag : scenario.getTags()) {
            if (PUMPKIN_TAG.equalsIgnoreCase(tag.getName())) return true;
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
     * Parses the {@code @processRequired(...)} tag value (or its {@code @requiredParameters(...)}
     * alias) and returns the list of required parameter names, or an empty list if neither tag is
     * present.
     */
    public static @NotNull List<String> parseRequiredParameters(@NotNull GherkinScenario scenario) {
        return parseTagArguments(scenario, PROCESS_REQUIRED_PREFIX, REQUIRED_PARAMETERS_PREFIX);
    }

    /**
     * Parses the {@code @setsParameters(...)} tag value and returns the list of context parameter
     * names the Process sets when executed, or an empty list if the tag isn't present.
     */
    public static @NotNull List<String> parseSetsParameters(@NotNull GherkinScenario scenario) {
        return parseTagArguments(scenario, SETS_PARAMETERS_PREFIX);
    }

    /**
     * Parses the {@code @ProcessContext(...)} tag value, or {@code null} if the scenario has no
     * such tag - meaning it's the default variant (see {@link #getInvocationContextName} for the
     * call-site counterpart). Deliberately separate from {@link #parseSetsParameters}
     * despite the similar name - see {@link #PROCESS_CONTEXT_TAG_PREFIX}'s own doc comment.
     */
    public static @Nullable String getContextName(@NotNull GherkinScenario scenario) {
        for (GherkinTag tag : scenario.getTags()) {
            String name = tag.getName();
            if (name == null) continue;
            if (name.startsWith(PROCESS_CONTEXT_TAG_PREFIX) && name.endsWith(")")) {
                String content = name.substring(PROCESS_CONTEXT_TAG_PREFIX.length(), name.length() - 1).trim();
                return content.isEmpty() ? null : content;
            }
        }
        return null;
    }

    /** Shared parsing for comma-separated {@code @tagName(a, b, c)} tag arguments, trying each alias prefix in turn. */
    private static @NotNull List<String> parseTagArguments(@NotNull GherkinScenario scenario,
                                                            @NotNull String... prefixes) {
        for (GherkinTag tag : scenario.getTags()) {
            String name = tag.getName();
            if (name == null) continue;
            for (String prefix : prefixes) {
                if (name.startsWith(prefix) && name.endsWith(")")) {
                    String content = name.substring(prefix.length(), name.length() - 1);
                    List<String> result = new ArrayList<>();
                    for (String part : content.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                            result.add(trimmed);
                        }
                    }
                    return result;
                }
            }
        }
        return Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Ancestry helpers
    // -------------------------------------------------------------------------

    /** Returns the enclosing {@link GherkinStep} for any PSI element, or {@code null}. */
    public static @Nullable GherkinStep findEnclosingStep(@NotNull PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, GherkinStep.class, false);
    }

    /** Returns the enclosing {@link GherkinScenario} for any PSI element, or {@code null}. */
    public static @Nullable GherkinScenario findEnclosingScenario(@NotNull PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, GherkinScenario.class, false);
    }
}
