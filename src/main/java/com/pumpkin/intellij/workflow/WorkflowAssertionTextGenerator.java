package com.pumpkin.intellij.workflow;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a workflow-item lookup result into the two-line-per-workflow Gherkin assertion block:
 * <pre>
 *     Then an open workflow &lt;workflowId&gt; - &lt;workflowDesc&gt; exists for customer
 *     * workflowitem &lt;workflowItemId&gt; - &lt;workflowItemDesc&gt; is closed with returnCode &lt;returnCode&gt;
 * </pre>
 * The first line is emitted once (workflow id/description are constant across the whole result
 * set); one {@code * workflowitem ...} line follows per row, in query order. Pure and free of any
 * DB/UI imports so it's independently testable.
 */
public final class WorkflowAssertionTextGenerator {

    private WorkflowAssertionTextGenerator() {}

    public static @NotNull String generate(@NotNull List<WorkflowItemRow> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot generate workflow assertion text from an empty result set.");
        }

        List<String> lines = new ArrayList<>();

        WorkflowItemRow first = rows.get(0);
        lines.add("    Then an open workflow " + first.workflowId() + " - " + first.workflowDesc()
                + " exists for customer");

        for (WorkflowItemRow row : rows) {
            lines.add("    * workflowitem " + row.workflowItemId() + " - " + row.workflowItemDesc()
                    + " is closed with returnCode " + row.returnCode());
        }

        return String.join("\n", lines);
    }
}
