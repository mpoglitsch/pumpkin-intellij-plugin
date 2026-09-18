package com.pumpkin.intellij.workflow.db;

import com.intellij.database.dataSource.DatabaseConnection;
import com.intellij.database.dataSource.DatabaseConnectionManager;
import com.intellij.database.dataSource.LocalDataSource;
import com.intellij.database.dataSource.LocalDataSourceManager;
import com.intellij.database.dataSource.connection.ConnectionRequestor;
import com.intellij.database.model.DasColumn;
import com.intellij.database.model.DasDataSource;
import com.intellij.database.model.DasForeignKey;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.MultiRef;
import com.intellij.database.remote.jdbc.RemoteConnection;
import com.intellij.database.remote.jdbc.RemotePreparedStatement;
import com.intellij.database.remote.jdbc.RemoteResultSet;
import com.intellij.database.util.DasUtil;
import com.intellij.database.util.GuardedRef;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.pumpkin.intellij.workflow.PumpkinDataSourceRef;
import com.pumpkin.intellij.workflow.ScenarioMatch;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge;
import com.pumpkin.intellij.workflow.WorkflowItemRow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    @Override
    public @NotNull List<String> listTables(@NotNull Project project, @NotNull PumpkinDataSourceRef dataSource) {
        LocalDataSource ds = findDataSource(project, dataSource);
        if (ds == null) return List.of();

        List<String> names = new ArrayList<>();
        // Explicitly typed as DasDataSource (not DatabaseSystem, which LocalDataSource also
        // implements) - DasUtil.getTables(DatabaseSystem) is deprecated/marked for removal in this
        // platform version; getTables(DasDataSource) is the current, non-deprecated overload.
        for (DasTable table : DasUtil.getTables((DasDataSource) ds)) {
            names.add(table.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    @Override
    public @NotNull List<ColumnInfo> listColumns(@NotNull Project project, @NotNull PumpkinDataSourceRef dataSource,
                                                 @NotNull String tableName) {
        LocalDataSource ds = findDataSource(project, dataSource);
        if (ds == null) return List.of();

        DasTable table = findTable(ds, tableName);
        if (table == null) return List.of();

        List<ColumnInfo> result = new ArrayList<>();
        for (DasColumn column : DasUtil.getColumns(table)) {
            result.add(toColumnInfo(table, column));
        }
        return result;
    }

    @Override
    public @NotNull List<ReverseForeignKey> findColumnsReferencing(@NotNull Project project,
            @NotNull PumpkinDataSourceRef dataSource, @NotNull String targetTable, @NotNull String targetColumn) {
        LocalDataSource ds = findDataSource(project, dataSource);
        if (ds == null) return List.of();

        List<ReverseForeignKey> result = new ArrayList<>();
        for (DasTable table : DasUtil.getTables((DasDataSource) ds)) {
            for (DasForeignKey fk : DasUtil.getForeignKeys(table)) {
                DasTable refTable = fk.getRefTable();
                String refTableName = refTable != null ? refTable.getName() : fk.getRefTableName();
                if (!targetTable.equalsIgnoreCase(refTableName)) continue;

                MultiRef.It<?> sourceNames = fk.getColumnsRef().iterate();
                MultiRef.It<?> targetNames = fk.getRefColumns().iterate();
                while (sourceNames.hasNext() && targetNames.hasNext()) {
                    String sourceName = sourceNames.next();
                    String targetName = targetNames.next();
                    if (targetColumn.equalsIgnoreCase(targetName)) {
                        result.add(new ReverseForeignKey(table.getName(), sourceName));
                    }
                }
            }
        }
        return result;
    }

    private static @Nullable DasTable findTable(@NotNull LocalDataSource ds, @NotNull String tableName) {
        // Explicitly typed as DasDataSource (not DatabaseSystem, which LocalDataSource also
        // implements) - DasUtil.getTables(DatabaseSystem) is deprecated/marked for removal in this
        // platform version; getTables(DasDataSource) is the current, non-deprecated overload.
        for (DasTable table : DasUtil.getTables((DasDataSource) ds)) {
            if (tableName.equalsIgnoreCase(table.getName())) return table;
        }
        return null;
    }

    /**
     * Resolves whether {@code column} is a foreign key and, if so, which table/column it points
     * to - walking {@link DasForeignKey#getColumnsRef()} (this table's own referencing columns)
     * and {@link DasForeignKey#getRefColumns()} (the target columns) in lockstep by position, per
     * the verified {@code MultiRef} contract (composite keys correspond positionally - there's no
     * separate per-column pairing object). Only the first matching source/target pair is used,
     * which is all the {@code join(...)}-completion chaining needs even for a composite key.
     */
    private static @NotNull ColumnInfo toColumnInfo(@NotNull DasTable table, @NotNull DasColumn column) {
        if (!table.getColumnAttrs(column).contains(DasColumn.Attribute.FOREIGN_KEY)) {
            return new ColumnInfo(column.getName(), null, null);
        }

        for (DasForeignKey fk : DasUtil.getForeignKeys(table)) {
            MultiRef.It<?> sourceNames = fk.getColumnsRef().iterate();
            MultiRef.It<?> targetNames = fk.getRefColumns().iterate();
            while (sourceNames.hasNext() && targetNames.hasNext()) {
                String sourceName = sourceNames.next();
                String targetName = targetNames.next();
                if (sourceName.equalsIgnoreCase(column.getName())) {
                    DasTable refTable = fk.getRefTable();
                    String targetTable = refTable != null ? refTable.getName() : fk.getRefTableName();
                    return new ColumnInfo(column.getName(), targetTable, targetName);
                }
            }
        }
        return new ColumnInfo(column.getName(), null, null);
    }

    private static @Nullable LocalDataSource findDataSource(@NotNull Project project, @NotNull PumpkinDataSourceRef ref) {
        return LocalDataSourceManager.getInstance(project).getDataSources().stream()
                .filter(ds -> ds.getUniqueId().equals(ref.id()))
                .findFirst()
                .orElse(null);
    }

    /** Resolves {@code ref} back to the live {@code LocalDataSource} and opens a blocking connection to it. */
    private static GuardedRef<DatabaseConnection> openConnection(@NotNull Project project,
            @NotNull PumpkinDataSourceRef ref) throws SQLException {

        LocalDataSource dataSource = findDataSource(project, ref);
        if (dataSource == null) {
            throw new SQLException("Datasource \"" + ref.displayName() + "\" is no longer configured in this project.");
        }

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
