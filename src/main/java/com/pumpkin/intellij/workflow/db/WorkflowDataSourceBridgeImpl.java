package com.pumpkin.intellij.workflow.db;

import com.intellij.openapi.project.Project;
import com.pumpkin.intellij.workflow.PumpkinDataSourceRef;
import com.pumpkin.intellij.workflow.ScenarioMatch;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge;
import com.pumpkin.intellij.workflow.WorkflowItemRow;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;

/**
 * TEMPORARILY STUBBED for IU-262.10315.125 compatibility: {@code com.intellij.database.dataSource.*}
 * (LocalDataSource, LocalDataSourceManager, DatabaseConnectionManager, DatabaseConnection, etc.) -
 * everything this class used to call - does not exist anywhere in that EAP build's bundled
 * Database Tools plugin (confirmed by searching every jar under {@code plugins/DatabaseTools} for
 * those exact class names - none exist, in any package). This isn't a rename we can chase down;
 * it looks like a substantial, still-in-flux reorganization of undocumented internal API in a
 * pre-release build, so guessing at a replacement now risks writing against something that changes
 * again before 2026.2 ships.
 *
 * <p>Every caller already treats an empty {@link #listDataSources} as the documented "feature
 * unavailable" signal (see {@link WorkflowDataSourceBridge}'s own doc), so that's what this stub
 * returns - the Database-backed workflow-assertion lookup is simply unavailable on this build,
 * same as if the Database Tools plugin weren't installed at all, rather than the whole plugin
 * failing to compile. The real, working implementation is preserved in git history and should be
 * restored (or rewritten against whatever the stable 2026.2 API turns out to be) once that API
 * stops moving.
 */
public final class WorkflowDataSourceBridgeImpl implements WorkflowDataSourceBridge {

    @Override
    public @NotNull List<PumpkinDataSourceRef> listDataSources(@NotNull Project project) {
        return List.of();
    }

    @Override
    public @NotNull List<ScenarioMatch> findScenarios(@NotNull Project project,
            @NotNull PumpkinDataSourceRef dataSource, long workflowId) throws SQLException {
        throw new SQLException("Database-backed workflow assertions are temporarily unavailable on this IDE build.");
    }

    @Override
    public @NotNull List<WorkflowItemRow> findWorkflowItems(@NotNull Project project,
            @NotNull PumpkinDataSourceRef dataSource, long workflowId, long scenarioId) throws SQLException {
        throw new SQLException("Database-backed workflow assertions are temporarily unavailable on this IDE build.");
    }
}
