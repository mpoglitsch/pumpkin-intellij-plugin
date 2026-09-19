package com.pumpkin.intellij.workflow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.settings.PumpkinSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.List;

/**
 * Backs the {@code wf:<workflow id>} / {@code wf:<datasource>:<workflow id>} shortcut (see
 * {@link WorkflowShortcutEnterHandler}): replaces the triggering line with a placeholder comment,
 * then asynchronously replaces that with either the generated steps or a clear inline error -
 * using only the workflow's default scenario (see {@link WorkflowDataSourceBridge#findScenarios}'s
 * ordering doc); for a different scenario, "Pumpkin ▸ Generator ▸ Workflow Assertion..." offers a
 * picker instead.
 */
final class WorkflowShortcutGenerator {

    private WorkflowShortcutGenerator() {}

    /** Replaces {@code [lineStart, lineEnd)} with a "Generating..." placeholder, then kicks off the lookup. */
    static void trigger(@NotNull Project project, @NotNull Document document, int lineStart, int lineEnd,
                        long workflowId, @Nullable String dataSourceName) {

        String placeholder = "    # Generating workflow assertion for workflow " + workflowId + "...";
        document.replaceString(lineStart, lineEnd, placeholder);

        RangeMarker marker = document.createRangeMarker(lineStart, lineStart + placeholder.length());
        marker.setGreedyToRight(true);

        generateAsync(project, document, marker, workflowId, dataSourceName);
    }

    private static void generateAsync(@NotNull Project project, @NotNull Document document,
                                      @NotNull RangeMarker marker, long workflowId, @Nullable String dataSourceName) {

        List<WorkflowDataSourceBridge> bridges = WorkflowDataSourceBridge.EP_NAME.getExtensionList();
        if (bridges.isEmpty()) {
            replaceMarker(project, document, marker,
                    "    # Generating workflow assertions requires the Database Tools plugin, "
                            + "bundled with IntelliJ IDEA Ultimate (not Community Edition).");
            return;
        }
        WorkflowDataSourceBridge bridge = bridges.get(0);

        PumpkinDataSourceRef dataSource = dataSourceName != null
                ? resolveByDisplayName(bridge, project, dataSourceName)
                : resolveFromStoredSetting(bridge, project);
        if (dataSource == null) {
            String message = dataSourceName != null
                    ? "    # Unknown datasource: " + dataSourceName
                    : "    # No datasource configured - open Pumpkin ▸ Generator ▸ Workflow Assertion... once first, "
                            + "or type wf:<datasource>:<id> instead.";
            replaceMarker(project, document, marker, message);
            return;
        }

        ModalityState modality = ModalityState.defaultModalityState();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<ScenarioMatch> scenarios = bridge.findScenarios(project, dataSource, workflowId);
                if (scenarios.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater(() -> replaceMarker(project, document, marker,
                            "    # Workflow " + workflowId + " does not exist."), modality);
                    return;
                }
                // First = the workflow's default scenario - see findScenarios' ordering doc.
                List<WorkflowItemRow> rows = bridge.findWorkflowItems(
                        project, dataSource, workflowId, scenarios.get(0).scenarioId());
                String generated = rows.isEmpty()
                        ? "    # No workflow items found for workflow " + workflowId + "."
                        : WorkflowAssertionTextGenerator.generate(rows);
                ApplicationManager.getApplication().invokeLater(
                        () -> replaceMarker(project, document, marker, generated), modality);
            } catch (SQLException e) {
                ApplicationManager.getApplication().invokeLater(() -> replaceMarker(project, document, marker,
                        "    # Could not generate workflow assertion for workflow " + workflowId + ": "
                                + e.getMessage()), modality);
            }
        });
    }

    /** Resolves a datasource typed/picked inline (e.g. via {@link WorkflowShortcutTypedHandler}'s chained popup) by its display name. */
    private static @Nullable PumpkinDataSourceRef resolveByDisplayName(@NotNull WorkflowDataSourceBridge bridge,
                                                                       @NotNull Project project, @NotNull String dataSourceName) {
        return bridge.listDataSources(project).stream()
                .filter(ds -> ds.displayName().equalsIgnoreCase(dataSourceName))
                .findFirst()
                .orElse(null);
    }

    /** Falls back to {@code PumpkinSettingsState.workflowAssertionDataSourceId} for a bare {@code wf:<id>} (no datasource typed). */
    private static @Nullable PumpkinDataSourceRef resolveFromStoredSetting(@NotNull WorkflowDataSourceBridge bridge,
                                                                           @NotNull Project project) {
        String dataSourceId = PumpkinSettingsState.getInstance(project).workflowAssertionDataSourceId;
        if (dataSourceId == null) return null;
        return bridge.listDataSources(project).stream()
                .filter(ds -> ds.id().equals(dataSourceId))
                .findFirst()
                .orElse(null);
    }

    /**
     * No-ops if the marker was invalidated (e.g. the user deleted that text before this finished).
     *
     * <p>Also invalidates {@link PumpkinProcessService}'s cache: this edit can land inside a
     * Process feature file (a {@code wf:}/{@code dwf:} shortcut can be typed inside a scenario
     * that's itself a Pumpkin Process), and a big single-shot multi-line {@code
     * document.replaceString} like this one is exactly the kind of edit that forces the Gherkin
     * parser to reparse - and replace - the enclosing {@code GherkinScenario} PSI node. {@code
     * PumpkinProcessService}'s cache is otherwise only invalidated by a VFS content-change event
     * (i.e. a disk save), so without this it would keep pointing at the now-dead pre-edit
     * scenario PSI element; {@code DuplicateProcessContextInspection}'s own self-exclusion check
     * (comparing the current scenario PSI against the cached one via {@code equals}) then fails,
     * misreporting this scenario as colliding with a "different" definition that's really just its
     * own pre-edit ghost. Invalidating unconditionally here (rather than only when the file is
     * confirmed to be a process file) is deliberately simple - the reload it forces on the next
     * lookup is cheap relative to this already-async, DB-driven generation.
     */
    private static void replaceMarker(@NotNull Project project, @NotNull Document document,
                                      @NotNull RangeMarker marker, @NotNull String replacement) {
        if (!marker.isValid()) return;
        WriteCommandAction.runWriteCommandAction(project, "Generate Workflow Assertion", null, () -> {
            document.replaceString(marker.getStartOffset(), marker.getEndOffset(), replacement);
            PumpkinProcessService.getInstance(project).invalidate();
        });
    }
}
