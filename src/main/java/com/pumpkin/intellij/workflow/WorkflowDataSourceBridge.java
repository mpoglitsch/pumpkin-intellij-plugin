package com.pumpkin.intellij.workflow;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.List;

/**
 * Seam between the always-loaded Pumpkin plugin and the Database Tools plugin, which is bundled
 * with IntelliJ Ultimate (and DataGrip) but not Community Edition. No implementation of this
 * interface is registered unless {@code com.intellij.database} is installed and enabled - see
 * {@code META-INF/pumpkin-database.xml}, which the platform only loads in that case.
 *
 * <p>Callers must treat {@link #EP_NAME}'s extension list as a plain "is the feature available"
 * check (empty vs non-empty) - see {@code GenerateWorkflowAssertionAction} - never assume more
 * than the single {@code WorkflowDataSourceBridgeImpl} registration exists.
 */
public interface WorkflowDataSourceBridge {

    ExtensionPointName<WorkflowDataSourceBridge> EP_NAME =
            ExtensionPointName.create("com.pumpkin.intellij.pumpkin-plugin.workflowDataSourceBridge");

    /** Every datasource configured in the IDE's Database tool window for this project. */
    @NotNull List<PumpkinDataSourceRef> listDataSources(@NotNull Project project);

    /**
     * Finds every scenario a Workflow ID belongs to, ordered so the workflow's default scenario
     * (per {@code w_scenarios_2_workflows.is_default}, if any) is always first - callers rely on
     * this ordering to auto-generate from the first result immediately while still offering every
     * other match for the user to pick instead. Must be called off the EDT - this blocks on a
     * real database round trip.
     */
    @NotNull List<ScenarioMatch> findScenarios(@NotNull Project project,
            @NotNull PumpkinDataSourceRef dataSource, long workflowId) throws SQLException;

    /**
     * Runs the workflow/workflow-item/return-code lookup (one row per workflow item, in {@code
     * i2w.sort_order}) for one specific workflow+scenario pair. Must be called off the EDT.
     */
    @NotNull List<WorkflowItemRow> findWorkflowItems(@NotNull Project project,
            @NotNull PumpkinDataSourceRef dataSource, long workflowId, long scenarioId) throws SQLException;

    /** Every table name in {@code dataSource}'s introspected schema, for the DB-step autocomplete. */
    @NotNull List<String> listTables(@NotNull Project project, @NotNull PumpkinDataSourceRef dataSource);

    /**
     * Every column of {@code tableName} (case-insensitive) in {@code dataSource}'s introspected
     * schema, or empty if the table can't be found. Schema-only - no live connection is opened for
     * this (see {@code WorkflowDataSourceBridgeImpl}'s own doc).
     */
    @NotNull List<ColumnInfo> listColumns(@NotNull Project project, @NotNull PumpkinDataSourceRef dataSource,
                                          @NotNull String tableName);

    /**
     * Every {@code (table, column)} pair anywhere in the schema whose column has a foreign key
     * pointing at {@code targetTable.targetColumn} - the reverse direction of {@link ColumnInfo}'s
     * own (outgoing) foreign-key info, for the {@code rejoin(...)} DB-step completion (find every
     * table that references *this* table, rather than what *this* table references).
     */
    @NotNull List<ReverseForeignKey> findColumnsReferencing(@NotNull Project project,
            @NotNull PumpkinDataSourceRef dataSource, @NotNull String targetTable, @NotNull String targetColumn);

    /**
     * One column of a table, plus - if it's a foreign key - the single table/column it references.
     * Composite foreign keys resolve to their first source/target column pair only, which is all
     * the {@code join(...)}-completion chaining needs.
     */
    record ColumnInfo(@NotNull String name, @Nullable String foreignKeyTable, @Nullable String foreignKeyColumn) {
    }

    /** One incoming foreign key: {@code table.column} references some other table's column. */
    record ReverseForeignKey(@NotNull String table, @NotNull String column) {
    }
}
