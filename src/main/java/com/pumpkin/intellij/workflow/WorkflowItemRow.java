package com.pumpkin.intellij.workflow;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One row of the workflow-item lookup query. {@code workflowId}/{@code workflowDesc} are constant
 * across every row for a given lookup (the query joins back to the same {@code w_workflows} row);
 * {@code returnCode} is nullable because the query {@code left join}s it.
 */
public record WorkflowItemRow(long workflowId, @NotNull String workflowDesc, long workflowItemId,
                              @NotNull String workflowItemDesc, @Nullable String returnCode) {
}
