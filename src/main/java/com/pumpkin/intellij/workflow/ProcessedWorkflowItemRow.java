package com.pumpkin.intellij.workflow;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One row of the "what actually happened in this processed workflow instance" lookup (see
 * {@code WorkflowDataSourceBridge#findProcessedWorkflowItems}), backing the {@code dwf:<datasource>:
 * <includePayloadSteps>:<workflow instance id>} shortcut. {@code workflowId}/{@code workflowName}/
 * {@code workflowStatus} are the *template* ({@code w_workflows}) identity plus the surrounding
 * *instance*'s ({@code d_workflows}) own status - all constant across every row for a given
 * lookup, same convention as {@link WorkflowItemRow}'s own {@code workflowId}/{@code workflowDesc}
 * - even though the query itself is keyed by a {@code d_workflows} instance id. {@code status} is
 * always the WORKFLOW ITEM's own status ({@code ks.id}), never the workflow's.
 * {@code subworkflowId}, if non-null, is itself a {@code d_workflows} instance id suitable for a
 * recursive call to the same lookup.
 */
public record ProcessedWorkflowItemRow(
        long workflowItemId, @NotNull String workflowItemName,
        long workflowId, @NotNull String workflowName,
        long status,
        @Nullable String returnCode,
        @Nullable Long subworkflowId,
        @Nullable String contentOutgoing,
        @Nullable String objectType,
        @Nullable String object,
        long workflowStatus) {
}
