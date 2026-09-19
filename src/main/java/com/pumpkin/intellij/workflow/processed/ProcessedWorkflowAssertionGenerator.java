package com.pumpkin.intellij.workflow.processed;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.pumpkin.intellij.endpoint.NameUtils;
import com.pumpkin.intellij.repository.PumpkinProcessService;
import com.pumpkin.intellij.util.FeatureFilePaths;
import com.pumpkin.intellij.workflow.ProcessedWorkflowItemRow;
import com.pumpkin.intellij.workflow.PumpkinDataSourceRef;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Backs the {@code dwf:<datasource>:<includePayloadSteps>:<workflow instance id>} shortcut (see
 * {@link ProcessedWorkflowTriggerHandler}/{@link ProcessedWorkflowEnterHandler}): reads an
 * actually-processed workflow instance (a {@code d_workflows.id}), picks the right step per
 * workflowitem from its real status, recursively expands any subworkflow that was actually
 * entered, and - if requested - turns each item's real outgoing JSON payload into a reusable
 * {@code %field%} template plus its own assertion step. Mirrors {@code WorkflowShortcutGenerator}'s
 * two-phase shape (placeholder + {@link RangeMarker} → background-thread DB fetch →
 * {@code invokeLater} → one {@link WriteCommandAction}), extended with recursion and
 * template-file resolution.
 */
final class ProcessedWorkflowAssertionGenerator {

    private ProcessedWorkflowAssertionGenerator() {}

    // -------------------------------------------------------------------------
    // In-memory fetch result (built entirely in phase 1, before any VFS/document write)
    // -------------------------------------------------------------------------

    private record FetchedWorkflow(long workflowId, @NotNull String workflowName, long workflowStatus,
                                   @NotNull List<FetchedItem> items) {}

    private record FetchedItem(@NotNull ProcessedWorkflowItemRow row, @Nullable PendingPayload payload,
                               @Nullable FetchedWorkflow subworkflow) {}

    /** {@code fields} keep concrete array indices (e.g. {@code arr[0].field}) - only comparisons for template reuse ignore them. */
    private record PendingPayload(@NotNull String objectSnake, @NotNull String objectTypeCamel,
                                  @NotNull String rewrittenJson, @NotNull List<String> fields) {}

    // -------------------------------------------------------------------------
    // Trigger / phase 1 (background thread)
    // -------------------------------------------------------------------------

    /** Replaces {@code [lineStart, lineEnd)} with a "Generating..." placeholder, then kicks off the recursive lookup. */
    static void trigger(@NotNull Project project, @NotNull Document document, int lineStart, int lineEnd,
                        @NotNull WorkflowDataSourceBridge bridge, @NotNull PumpkinDataSourceRef dataSource,
                        boolean includePayloadSteps, long workflowInstanceId, @NotNull VirtualFile featureFile) {

        String placeholder = "    # Generating processed workflow assertion for workflow " + workflowInstanceId + "...";
        document.replaceString(lineStart, lineEnd, placeholder);

        RangeMarker marker = document.createRangeMarker(lineStart, lineStart + placeholder.length());
        marker.setGreedyToRight(true);

        generateAsync(project, document, marker, bridge, dataSource, includePayloadSteps, workflowInstanceId, featureFile);
    }

    private static void generateAsync(@NotNull Project project, @NotNull Document document, @NotNull RangeMarker marker,
                                      @NotNull WorkflowDataSourceBridge bridge, @NotNull PumpkinDataSourceRef dataSource,
                                      boolean includePayloadSteps, long workflowInstanceId, @NotNull VirtualFile featureFile) {

        ModalityState modality = ModalityState.defaultModalityState();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            FetchedWorkflow root;
            try {
                root = fetchRecursive(project, bridge, dataSource, workflowInstanceId, includePayloadSteps,
                        new LinkedHashSet<>());
            } catch (SQLException | IllegalStateException e) {
                ApplicationManager.getApplication().invokeLater(() -> replaceMarker(project, document, marker,
                        "    # Could not generate processed workflow assertion for workflow " + workflowInstanceId
                                + ": " + e.getMessage()), modality);
                return;
            }
            FetchedWorkflow fetched = root;
            ApplicationManager.getApplication().invokeLater(
                    () -> finishOnEdt(project, document, marker, fetched, featureFile), modality);
        });
    }

    /**
     * Recursively fetches one workflow instance's own items (and, for any status-1-with-subworkflow
     * item, that subworkflow's items too), guarded by {@code visitedInstanceIds} against a
     * cyclical {@code subworkflow} chain - mirrors {@code ProcessExecutor.rejectRecursion}'s own
     * call-stack-guard spirit (helper_files mirror), just keyed by workflow instance id instead of
     * scenario id. Also validates every row's status up front (throws on an unmapped one) and
     * eagerly computes each row's outgoing-payload rewrite (a pure, in-memory JSON transform) -
     * everything here is safe to do off the EDT since no VFS/document write happens yet.
     */
    private static @NotNull FetchedWorkflow fetchRecursive(@NotNull Project project, @NotNull WorkflowDataSourceBridge bridge,
                                                           @NotNull PumpkinDataSourceRef dataSource, long workflowInstanceId,
                                                           boolean includePayloadSteps, @NotNull Set<Long> visitedInstanceIds)
            throws SQLException {

        if (!visitedInstanceIds.add(workflowInstanceId)) {
            throw new IllegalStateException("Workflow instance " + workflowInstanceId + " is reachable from itself "
                    + "through a chain of subworkflows - refusing to recurse forever.");
        }

        List<ProcessedWorkflowItemRow> rows = bridge.findProcessedWorkflowItems(project, dataSource, workflowInstanceId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Processed workflow " + workflowInstanceId + " has no workflow items (or does not exist).");
        }

        List<FetchedItem> items = new ArrayList<>();
        for (ProcessedWorkflowItemRow row : rows) {
            validateStatus(row); // throws for an unmapped status - fail fast, before any recursion/file work

            PendingPayload payload = null;
            if (includePayloadSteps && row.contentOutgoing() != null && !row.contentOutgoing().isBlank()) {
                payload = buildPendingPayload(project, row);
            }

            FetchedWorkflow subworkflow = null;
            if (row.status() == 1 && row.subworkflowId() != null) {
                subworkflow = fetchRecursive(project, bridge, dataSource, row.subworkflowId(), includePayloadSteps, visitedInstanceIds);
            }

            items.add(new FetchedItem(row, payload, subworkflow));
        }

        return new FetchedWorkflow(rows.get(0).workflowId(), rows.get(0).workflowName(), rows.get(0).workflowStatus(), items);
    }

    private static void validateStatus(@NotNull ProcessedWorkflowItemRow row) {
        itemStepText(row); // discards the text - only run here for its unmapped-status check
    }

    /**
     * Returns {@code null} if {@code content_outgoing} isn't valid JSON - per the user's own "if
     * the content_outgoing column has a valid json in there" wording, that's simply "no payload
     * step for this row", not an error. A row that DOES parse as JSON but is missing {@code
     * object}/{@code object_type} (so the template path can't be built) is a genuine error, though.
     */
    private static @Nullable PendingPayload buildPendingPayload(@NotNull Project project, @NotNull ProcessedWorkflowItemRow row) {
        OutgoingPayloadJson.Rewritten[] holder = new OutgoingPayloadJson.Rewritten[1];
        ApplicationManager.getApplication().runReadAction(() -> {
            holder[0] = OutgoingPayloadJson.rewrite(project, row.contentOutgoing());
        });
        OutgoingPayloadJson.Rewritten rewritten = holder[0];
        if (rewritten == null) {
            return null;
        }
        if (row.object() == null || row.objectType() == null) {
            throw new IllegalStateException("workflowitem " + row.workflowItemId() + " - " + row.workflowItemName()
                    + " has an outgoing payload but no object/object type to build its template path from.");
        }

        String objectSnake = NameUtils.toUpperSnakeCase(row.object()).toLowerCase(Locale.ROOT);
        String objectTypeCamel = NameUtils.toCamelCase(row.objectType());
        return new PendingPayload(objectSnake, objectTypeCamel, rewritten.json(), rewritten.fields());
    }

    // -------------------------------------------------------------------------
    // Phase 2 (EDT, one WriteCommandAction: template-file resolution + the final document edit)
    // -------------------------------------------------------------------------

    private static void finishOnEdt(@NotNull Project project, @NotNull Document document, @NotNull RangeMarker marker,
                                    @NotNull FetchedWorkflow root, @NotNull VirtualFile featureFile) {
        if (!marker.isValid()) return;
        WriteCommandAction.runWriteCommandAction(project, "Generate Processed Workflow Assertion", null, () -> {
            String result;
            try {
                result = buildText(project, root, featureFile);
            } catch (IllegalStateException e) {
                result = "    # Could not generate processed workflow assertion: " + e.getMessage();
            }
            document.replaceString(marker.getStartOffset(), marker.getEndOffset(), result);
            PumpkinProcessService.getInstance(project).invalidate();
        });
    }

    /**
     * No-ops if the marker was invalidated (e.g. the user deleted that text before this finished).
     *
     * <p>Also invalidates {@link PumpkinProcessService}'s cache - see {@code
     * WorkflowShortcutGenerator.replaceMarker}'s own doc comment (same generator shape, same
     * seam) for why a big single-shot multi-line {@code document.replaceString} like this one
     * needs this: it can force the Gherkin parser to replace the enclosing scenario's own PSI
     * node, which would otherwise leave {@code PumpkinProcessService}'s cache pointing at a
     * now-dead pre-edit element and cause {@code DuplicateProcessContextInspection} to misreport
     * this scenario as colliding with its own pre-edit ghost.
     */
    private static void replaceMarker(@NotNull Project project, @NotNull Document document,
                                      @NotNull RangeMarker marker, @NotNull String replacement) {
        if (!marker.isValid()) return;
        WriteCommandAction.runWriteCommandAction(project, "Generate Processed Workflow Assertion", null, () -> {
            document.replaceString(marker.getStartOffset(), marker.getEndOffset(), replacement);
            PumpkinProcessService.getInstance(project).invalidate();
        });
    }

    private static @NotNull String buildText(@NotNull Project project, @NotNull FetchedWorkflow root, @NotNull VirtualFile featureFile) {
        VirtualFile xxDir = FeatureFilePaths.resolveDirTwoLevelsBelowFeatures(featureFile);
        if (xxDir == null) {
            throw new IllegalStateException("This feature file isn't nested under a recognizable "
                    + "features/.../.../... structure - can't determine the XX template folder.");
        }
        Module module = ModuleUtilCore.findModuleForFile(featureFile, project);
        if (module == null) {
            throw new IllegalStateException("Could not determine the module for this feature file.");
        }

        List<String> lines = new ArrayList<>();
        lines.add("    Then an open workflow " + root.workflowId() + " - " + root.workflowName() + " exists for customer");
        appendItems(project, lines, root, module, xxDir.getName());
        return String.join("\n", lines);
    }

    private static void appendItems(@NotNull Project project, @NotNull List<String> lines, @NotNull FetchedWorkflow workflow,
                                    @NotNull Module module, @NotNull String xxFolder) {
        for (FetchedItem item : workflow.items()) {
            lines.add("    * " + itemStepText(item.row()));

            if (item.payload() != null) {
                appendPayloadStep(project, lines, item.payload(), module, xxFolder);
            }

            if (item.subworkflow() != null) {
                FetchedWorkflow sub = item.subworkflow();
                lines.add(""); // separates the parent's own steps from the subworkflow being checked
                lines.add("    Then subworkflow " + sub.workflowId() + " - " + sub.workflowName()
                        + " was started by workflowitem " + item.row().workflowItemId() + " - " + item.row().workflowItemName());
                appendItems(project, lines, sub, module, xxFolder);
                lines.add(""); // separates the subworkflow's own steps from returning to the parent
                lines.add("    Then I navigate to workflow " + workflow.workflowId() + " - " + workflow.workflowName());
            }
        }

        // Applies at every recursion level (both the top-level workflow and any subworkflow),
        // right after that level's own items - for a subworkflow this lands before the "navigate
        // to workflow <parent>" step appended by the caller above, since it's still describing the
        // subworkflow's own state.
        if (workflow.workflowStatus() == 1) {
            lines.add("    * workflow " + workflow.workflowId() + " - " + workflow.workflowName() + " is closed");
        }
    }

    private static void appendPayloadStep(@NotNull Project project, @NotNull List<String> lines, @NotNull PendingPayload payload,
                                          @NotNull Module module, @NotNull String xxFolder) {
        OutgoingPayloadTemplateResolver.Resolved resolved = OutgoingPayloadTemplateResolver.resolveOrCreate(
                project, module, xxFolder, payload.objectSnake(), payload.objectTypeCamel(), payload.rewrittenJson(), payload.fields());

        lines.add("    * outgoing payload is compliant with template /" + resolved.relativePath());
        lines.add("    | " + String.join(" | ", resolved.fields()) + " |");
        lines.add("    |" + "  |".repeat(resolved.fields().size()));
    }

    /**
     * The one place the 9 known statuses are translated to step text - reused both to validate a
     * row's status early (see {@link #validateStatus}, result discarded) and to build the actual
     * step line here in phase 2.
     */
    private static @NotNull String itemStepText(@NotNull ProcessedWorkflowItemRow row) {
        String subject = "workflowitem " + row.workflowItemId() + " - " + row.workflowItemName();
        return switch ((int) row.status()) {
            case 7 -> subject + " is obsolete";
            case 1 -> row.subworkflowId() == null
                    ? subject + " is closed with returnCode " + row.returnCode()
                    : subject + " is waiting for subworkflow or has returnCode " + row.returnCode();
            case 10 -> subject + " is waiting for subworkflow";
            case 6 -> subject + " is in ready for automatic processing status";
            case 2 -> subject + " is in error status";
            case 8 -> subject + " is in ready for manual processing status";
            case 11 -> subject + " is in waiting for response status";
            case 0 -> subject + " is in open status";
            default -> throw new IllegalStateException("Unexpected status " + row.status()
                    + " for workflowitem " + row.workflowItemId() + " - " + row.workflowItemName()
                    + " - not one of the statuses the dwf: generator knows how to translate into a step.");
        };
    }
}
