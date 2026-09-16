package com.pumpkin.intellij.workflow.db;

import com.intellij.database.dataSource.DatabaseConnection;
import com.intellij.database.dataSource.DatabaseConnectionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.LocalDataSourceManager;
import com.intellij.database.dataSource.connection.ConnectionRequestor;
import com.intellij.database.remote.jdbc.RemoteConnection;
import com.intellij.database.remote.jdbc.RemotePreparedStatement;
import com.intellij.database.remote.jdbc.RemoteResultSet;
import com.intellij.database.util.GuardedRef;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.pumpkin.intellij.workflow.PumpkinDataSourceRef;
import com.pumpkin.intellij.workflow.ScenarioMatch;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge;
import com.pumpkin.intellij.workflow.WorkflowItemRow;
import org.jetbrains.annotations.NotNull;

import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The only class in this plugin that imports {@code com.intellij.database.*}. Registered as the
 * {@link WorkflowDataSourceBridge} implementation exclusively via {@code
 * META-INF/pumpkin-database.xml}, which the platform loads only when the Database Tools plugin is
 * actually present - see that interface's doc for why this split exists.
 *
 * <p>{@code LocalDataSource} implements {@code DatabaseConnectionConfig}, which itself extends
 * {@code DatabaseConnectionPoint} - so a {@code LocalDataSource} can be passed anywhere a
 * connection point is expected without any separate conversion step.
 */
public final class WorkflowDataSourceBridgeImpl implements WorkflowDataSourceBridge {

    // Ordered so the workflow's default scenario (if any) is always first - callers rely on this
    // to auto-generate from row 0 immediately while still offering every other match.
    private static final String FIND_SCENARIOS_SQL =
            "SELECT id, scenario\n"
            + "FROM (\n"
            + "    SELECT DISTINCT ON (s.id)\n"
            + "        s.id,\n"
            + "        s.scenario,\n"
            + "        s2w.is_default\n"
            + "    FROM w_workflows w\n"
            + "    JOIN w_items_2_workflows i2w\n"
            + "        ON i2w.workflow = w.id\n"
            + "    JOIN w_scenarios s\n"
            + "        ON s.id = i2w.scenario\n"
            + "    JOIN w_scenarios_2_workflows s2w\n"
            + "        ON s2w.workflow = w.id\n"
            + "       AND s2w.scenario = s.id\n"
            + "    WHERE w.id = ?\n"
            + "    ORDER BY\n"
            + "        s.id,\n"
            + "        s2w.is_default DESC\n"
            + ") x\n"
            + "ORDER BY\n"
            + "    is_default DESC,\n"
            + "    id ASC";

    private static final String FIND_WORKFLOW_ITEMS_SQL =
            "select w.id as workflow_id, w.workflow as workflow_desc, i.id as workflowitem_id,\n"
            + "    i.workflowitem as workflowitem_desc, wrc.description as returncode\n"
            + "from w_workflows w\n"
            + "    join w_items_2_workflows i2w on i2w.workflow = w.id\n"
            + "    join w_workflowitems i ON i2w.workflowitem = i.id\n"
            + "    left join\n"
            + "        (\n"
            + "            SELECT DISTINCT ON (wrc.workflowitems_2_workflow) *\n"
            + "            FROM w_wfitems_wf_return_codes wrc\n"
            + "            ORDER BY wrc.workflowitems_2_workflow, wrc.return_code\n"
            + "        ) wrc ON wrc.workflowitems_2_workflow = i2w.id\n"
            + "where w.id = ? and i2w.scenario = ?\n"
            + "order by i2w.sort_order";

    @Override
    public @NotNull List<PumpkinDataSourceRef> listDataSources(@NotNull Project project) {
        List<PumpkinDataSourceRef> result = new ArrayList<>();
        for (LocalDataSource ds : LocalDataSourceManager.getInstance(project).getDataSources()) {
            result.add(new PumpkinDataSourceRef(ds.getUniqueId(), ds.getName()));
        }
        return result;
    }

    @Override
    public @NotNull List<ScenarioMatch> findScenarios(@NotNull Project project,
            @NotNull PumpkinDataSourceRef dataSource, long workflowId) throws SQLException {

        List<ScenarioMatch> result = new ArrayList<>();
        try (GuardedRef<DatabaseConnection> ref = openConnection(project, dataSource)) {
            RemoteConnection remote = ref.get().getRemoteConnection();
            RemotePreparedStatement stmt = remote.prepareStatement(FIND_SCENARIOS_SQL);
            try {
                stmt.setLong(1, workflowId);
                RemoteResultSet rs = stmt.executeQuery();
                try {
                    while (rs.next()) {
                        result.add(new ScenarioMatch(rs.getLong("id"), rs.getString("scenario")));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                stmt.close();
            }
        } catch (RemoteException e) {
            throw new SQLException("Lost connection to " + dataSource.displayName() + " while finding scenarios.", e);
        }
        return result;
    }

    @Override
    public @NotNull List<WorkflowItemRow> findWorkflowItems(@NotNull Project project,
            @NotNull PumpkinDataSourceRef dataSource, long workflowId, long scenarioId) throws SQLException {

        List<WorkflowItemRow> result = new ArrayList<>();
        try (GuardedRef<DatabaseConnection> ref = openConnection(project, dataSource)) {
            RemoteConnection remote = ref.get().getRemoteConnection();
            RemotePreparedStatement stmt = remote.prepareStatement(FIND_WORKFLOW_ITEMS_SQL);
            try {
                stmt.setLong(1, workflowId);
                stmt.setLong(2, scenarioId);
                RemoteResultSet rs = stmt.executeQuery();
                try {
                    while (rs.next()) {
                        result.add(new WorkflowItemRow(
                                rs.getLong("workflow_id"),
                                rs.getString("workflow_desc"),
                                rs.getLong("workflowitem_id"),
                                rs.getString("workflowitem_desc"),
                                rs.getString("returncode")));
                    }
                } finally {
                    rs.close();
                }
            } finally {
                stmt.close();
            }
        } catch (RemoteException e) {
            throw new SQLException(
                    "Lost connection to " + dataSource.displayName() + " while finding workflow items.", e);
        }
        return result;
    }

    /** Resolves {@code ref} back to the live {@code LocalDataSource} and opens a blocking connection to it. */
    private static GuardedRef<DatabaseConnection> openConnection(@NotNull Project project,
            @NotNull PumpkinDataSourceRef ref) throws SQLException {

        LocalDataSource dataSource = LocalDataSourceManager.getInstance(project).getDataSources().stream()
                .filter(ds -> ds.getUniqueId().equals(ref.id()))
                .findFirst()
                .orElseThrow(() -> new SQLException(
                        "Datasource \"" + ref.displayName() + "\" is no longer configured in this project."));

        // createBlocking() internally requires an active ProgressIndicator/Job on the calling
        // thread (it runs on a coroutine via runBlockingCancellable, which throws
        // "There is no ProgressIndicator or Job in this thread" otherwise) - both callers of this
        // bridge (the "wf:<id>" shortcut and the Workflow Assertion dialog) run on a plain pooled
        // thread with neither, so this establishes one. A bare EmptyProgressIndicator (no visible
        // UI, never cancelled) is enough - it doesn't change any caller-visible behavior, it only
        // satisfies that internal precondition.
        @SuppressWarnings("unchecked")
        GuardedRef<DatabaseConnection>[] result = new GuardedRef[1];
        SQLException[] error = new SQLException[1];
        ProgressManager.getInstance().runProcess(() -> {
            try {
                result[0] = DatabaseConnectionManager.getInstance()
                        .build(project, dataSource)
                        .setRequestor(new ConnectionRequestor.Anonymous())
                        .createBlocking();
            } catch (SQLException e) {
                error[0] = e;
            }
        }, new EmptyProgressIndicator());

        if (error[0] != null) throw error[0];
        return result[0];
    }
}
