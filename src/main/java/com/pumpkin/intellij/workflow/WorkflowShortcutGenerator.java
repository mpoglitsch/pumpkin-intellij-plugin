package com.pumpkin.intellij.workflow;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.project.Project;
import com.pumpkin.intellij.settings.PumpkinSettingsState;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;

/**
 * Backs the {@code wf:<workflow id>} shortcut (see {@link WorkflowShortcutEnterHandler}):
 * replaces the triggering line with a placeholder comment, then asynchronously replaces that with
 * either the generated steps or a clear inline error - using only the workflow's default scenario
 * (see {@link WorkflowDataSourceBridge#findScenarios}'s ordering doc); for a different scenario,
 * "Pumpkin ▸ Generator ▸ Workflow Assertion..." offers a picker instead.
 */
final class WorkflowShortcutGenerator {

    private WorkflowShortcutGenerator() {}

    /** Replaces {@code [lineStart, lineEnd)} with a "Generating..." placeholder, then kicks off the lookup. */
    static void trigger(@NotNull Project project, @NotNull Document document,
                        int lineStart, int lineEnd, long workflowId) {

        String placeholder = "    # Generating workflow assertion for workflow " + workflowId + "...";
        document.replaceString(lineStart, lineEnd, placeholder);

        RangeMarker marker = document.createRangeMarker(lineStart, lineStart + placeholder.length());
        marker.setGreedyToRight(true);

        generateAsync(project, document, marker, workflowId);
    }

    private static void generateAsync(@NotNull Project project, @NotNull Document document,
                                      @NotNull RangeMarker marker, long workflowId) {

        List<WorkflowDataSourceBridge> bridges = WorkflowDataSourceBridge.EP_NAME.getExtensionList();
        if (bridges.isEmpty()) {
            replaceMarker(project, document, marker,
                    "    # Generating workflow assertions requires the Database Tools plugin, "
                            + "bundled with IntelliJ IDEA Ultimate (not Community Edition).");
            return;
        }
        WorkflowDataSourceBridge bridge = bridges.get(0);

        String dataSourceId = PumpkinSettingsState.getInstance(project).workflowAssertionDataSourceId;
        PumpkinDataSourceRef dataSource = dataSourceId == null ? null : bridge.listDataSources(project).stream()
                .filter(ds -> ds.id().equals(dataSourceId))
                .findFirst()
                .orElse(null);
        if (dataSource == null) {
            replaceMarker(project, document, marker,
                    "    # No datasource configured - open Pumpkin ▸ Generator ▸ Workflow Assertion... once first.");
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

    /** No-ops if the marker was invalidated (e.g. the user deleted that text before this finished). */
    private static void replaceMarker(@NotNull Project project, @NotNull Document document,
                                      @NotNull RangeMarker marker, @NotNull String replacement) {
        if (!marker.isValid()) return;
        WriteCommandAction.runWriteCommandAction(project, "Generate Workflow Assertion", null, () ->
                document.replaceString(marker.getStartOffset(), marker.getEndOffset(), replacement));
    }
}
