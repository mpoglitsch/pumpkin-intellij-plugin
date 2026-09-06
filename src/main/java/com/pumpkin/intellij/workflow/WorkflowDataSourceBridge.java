package com.pumpkin.intellij.workflow;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

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
}
