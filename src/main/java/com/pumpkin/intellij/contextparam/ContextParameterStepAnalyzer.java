package com.pumpkin.intellij.contextparam;

import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.api.ApiEndpointResolver;
import com.pumpkin.intellij.api.ApiStepPattern;
import com.pumpkin.intellij.api.PumpkinPostprocessorResolver;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder;
import org.jetbrains.plugins.cucumber.psi.GherkinTable;
import org.jetbrains.plugins.cucumber.psi.GherkinTableCell;
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts, one event per step, what each step in a {@link GherkinStepsHolder} contributes to the
 * running context-parameter set - see {@link ContextEvent} for the two event shapes and {@link
 * PumpkinContextParameterService} for how this feeds the {@code %contextParameter%} autocomplete.
 * Stateless and side-effect-free so it's safe to call directly from a {@code CachedValuesManager}
 * provider.
 */
final class ContextParameterStepAnalyzer {

    // Mirrors StoreContextValuesEnterHandler.TRIGGER, minus the keyword prefix - matched against
    // GherkinStep.getName() (already keyword-stripped) rather than raw text.
    private static final Pattern STORE_STEP = Pattern.compile(
            "(?:I\\s+)?store (?:these values|this value) in context", Pattern.CASE_INSENSITIVE);

    // Matches a saveToContext(...) header cell wherever it appears; the second group is the
    // context-parameter name it declares. Can occur zero or more times across a table's header
    // cells, in any column.
    private static final Pattern SAVE_TO_CONTEXT = Pattern.compile(
            "saveToContext\\(\\s*[^,()]+\\s*,\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCENARIO_NAME_VARIABLE = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    private ContextParameterStepAnalyzer() {}

    /**
     * One event per step that establishes something, in document order. A {@code
     * @pumpkin}-tagged holder's own {@code {placeholder}} names (if any) are NOT included here -
     * they're available from the very start of the scenario, not "after step N" - see {@link
     * #scenarioNameVariables}, added separately by {@link PumpkinContextParameterService}.
     */
    @NotNull
    static List<ContextEvent> extractEvents(@NotNull GherkinStepsHolder holder) {
        List<ContextEvent> events = new ArrayList<>();
        for (GherkinStep step : holder.getSteps()) {
            ContextEvent event = eventFor(step);
            if (event != null) events.add(event);
        }
        return events;
    }

    /** The {@code {placeholder}} names in a scenario's own title, e.g. {@code {client}}. */
    @NotNull
    static List<String> scenarioNameVariables(@NotNull String scenarioName) {
        List<String> names = new ArrayList<>();
        Matcher m = SCENARIO_NAME_VARIABLE.matcher(scenarioName);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static @Nullable ContextEvent eventFor(@NotNull GherkinStep step) {
        if (GherkinPsiUtil.isProcessStep(step)) {
            return processInvocationEvent(step);
        }

        String name = step.getName();
        if (name == null) return null;
        String trimmed = name.trim();

        if (STORE_STEP.matcher(trimmed).matches()) {
            return directEvent(step, storeStepParamNames(step.getTable()));
        }
        if (GherkinPsiUtil.isDbPresentStep(step)) {
            return directEvent(step, dbStepParamNames(step.getTable()));
        }

        ApiStepPattern.ParsedApiStep parsed = ApiStepPattern.parse(step);
        if (parsed != null) {
            PsiEnumConstant endpoint = ApiEndpointResolver.resolve(
                    step.getProject(), parsed.getApiNotation(), parsed.getApiRequestDefinition());
            if (endpoint != null) {
                List<String> written = PumpkinPostprocessorResolver.resolveWrittenParams(
                        step.getProject(), parsed.getApiNotation(), endpoint.getName());
                return directEvent(step, written);
            }
        }

        return null;
    }

    private static @Nullable ContextEvent directEvent(@NotNull GherkinStep step, @NotNull List<String> paramNames) {
        return paramNames.isEmpty() ? null : new ContextEvent.DirectContextEvent(step, paramNames);
    }

    @NotNull
    private static ContextEvent processInvocationEvent(@NotNull GherkinStep step) {
        String invocationText = GherkinPsiUtil.getProcessInvocationText(step);
        String requestedContext = GherkinPsiUtil.getInvocationContextName(step);
        List<String> callSiteFields = invocationText == null ? List.of() : callSiteFieldNames(step.getTable());
        return new ContextEvent.ProcessInvocationEvent(
                step, callSiteFields, invocationText == null ? "" : invocationText, requestedContext);
    }

    /**
     * The {@code fieldName} column's value from every DATA row of a real {@code fieldName | value}
     * header table (the {@code store ... in context} step's own convention) - contrast {@link
     * #callSiteFieldNames}, which reads a differently-shaped, headerless table.
     */
    @NotNull
    private static List<String> storeStepParamNames(@Nullable GherkinTable table) {
        if (table == null) return List.of();
        GherkinTableRow header = table.getHeaderRow();
        if (header == null) return List.of();

        int fieldNameColumn = columnIndexOf(header, "fieldName");
        if (fieldNameColumn < 0) return List.of();

        List<String> names = new ArrayList<>();
        for (GherkinTableRow row : table.getDataRows()) {
            String value = cellText(row, fieldNameColumn);
            if (value != null && !value.isBlank()) names.add(value);
        }
        return names;
    }

    /**
     * Scans every header cell's text for {@code saveToContext(source, name)} and collects {@code
     * name} from each match - the spec can appear in any column, zero or more times.
     */
    @NotNull
    private static List<String> dbStepParamNames(@Nullable GherkinTable table) {
        if (table == null) return List.of();
        GherkinTableRow header = table.getHeaderRow();
        if (header == null) return List.of();

        List<GherkinTableCell> cells = header.getPsiCells();
        if (cells == null) return List.of();

        List<String> names = new ArrayList<>();
        for (GherkinTableCell cell : cells) {
            Matcher m = SAVE_TO_CONTEXT.matcher(cell.getText());
            while (m.find()) names.add(m.group(1));
        }
        return names;
    }

    /**
     * Column 0 of EVERY row (Gherkin PSI's own "header row" included) - a {@code Process: ... with
     * data} table is a headerless {@code fieldName | value} pair list, not a real header/data
     * split, matching {@code PumpkinProcessInsertHandler.collectExistingTableKeys}'s own reading of
     * this exact table shape.
     */
    @NotNull
    private static List<String> callSiteFieldNames(@Nullable GherkinTable table) {
        if (table == null) return List.of();
        List<String> names = new ArrayList<>();
        for (GherkinTableRow row : PsiTreeUtil.findChildrenOfType(table, GherkinTableRow.class)) {
            List<GherkinTableCell> cells = row.getPsiCells();
            if (cells == null || cells.isEmpty()) continue;
            String key = cells.get(0).getText().trim();
            if (!key.isBlank()) names.add(key);
        }
        return names;
    }

    private static int columnIndexOf(@NotNull GherkinTableRow header, @NotNull String columnName) {
        List<GherkinTableCell> cells = header.getPsiCells();
        if (cells == null) return -1;
        for (int i = 0; i < cells.size(); i++) {
            if (columnName.equalsIgnoreCase(cells.get(i).getText().trim())) return i;
        }
        return -1;
    }

    private static @Nullable String cellText(@NotNull GherkinTableRow row, int columnIndex) {
        List<GherkinTableCell> cells = row.getPsiCells();
        if (cells == null || columnIndex >= cells.size()) return null;
        return cells.get(columnIndex).getText().trim();
    }
}
