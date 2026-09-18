package com.pumpkin.intellij.dbstep;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.CompletionUtilCore;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import com.pumpkin.intellij.settings.PumpkinSettingsState;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import com.pumpkin.intellij.workflow.PumpkinDataSourceRef;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge.ColumnInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;
import org.jetbrains.plugins.cucumber.psi.GherkinTable;
import org.jetbrains.plugins.cucumber.psi.GherkinTableCell;
import org.jetbrains.plugins.cucumber.psi.GherkinTableRow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schema-driven autocomplete for {@code these values are (not) present in &lt;table&gt;} DB steps
 * (see {@link GherkinPsiUtil#isDbPresentStep} - the negated "not present" form is the same step
 * shape, just asserting absence), sourced from the datasource configured via {@code Pumpkin ▸
 * Generator ▸ Workflow Assertion} ({@link PumpkinSettingsState#workflowAssertionDataSourceId}, the
 * same setting that feature already uses) through the existing {@link WorkflowDataSourceBridge}
 * seam to the optional Database Tools plugin - see {@code WorkflowDataSourceBridgeImpl.listTables}/
 * {@code listColumns}/{@code findColumnsReferencing} for how the schema itself is read (a plain
 * cache read, no live query).
 *
 * <p>Handles these cursor positions, all gated on {@link GherkinPsiUtil#isDbPresentStep}:
 * <ol>
 *   <li>On the step's own text, after "present in " - table names.</li>
 *   <li>In a plain (not yet inside a function call) header-row cell - every column of the step's
 *       own table, plus {@code join(...)}/{@code rejoin(...)}/{@code saveToContext(...)} templates.</li>
 *   <li>Right after {@code join(}/{@code rejoin(}/{@code saveToContext(} in that cell - columns
 *       (or, for {@code rejoin}, tables - see below) for argument 1.</li>
 *   <li>Right after a comma inside {@code join(col, } - either the foreign-key target table's
 *       columns directly, or (non-FK column) every table name as a fallback, then that table's
 *       columns after a typed/inserted {@code .}. {@code saveToContext(...)}'s 2nd argument is a
 *       free-form new context-variable name and gets no completion at all.</li>
 *   <li>{@code rejoin(table1.column1, column2)} - the reverse of {@code join}: {@code table1} is
 *       restricted to tables that have some column referencing this step's own table's {@code id}
 *       column (via {@link WorkflowDataSourceBridge#findColumnsReferencing}); {@code column1} is
 *       further restricted to specifically that referencing column (there may be more than one);
 *       {@code column2} is any column of {@code table1}, unrestricted, same as a plain column.</li>
 * </ol>
 *
 * <p>Selecting a column that finishes a cell's whole spec (a plain column, or a {@code
 * join(...)}'s final target column) jumps the caret to the next header column - widening the
 * table by one column (header row and every data row, kept in sync so the table stays
 * rectangular) if there isn't one yet. Every chained follow-up popup (after an insert handler
 * places the caret at an otherwise-empty position with nothing just typed to trigger the
 * platform's own auto-popup) is scheduled via {@link InsertionContext#setLaterRunnable} rather
 * than calling {@link AutoPopupController#scheduleAutoPopup} directly inline - confirmed via
 * bytecode inspection that calling it synchronously from inside an insert handler races the
 * completion-phase state machine and can silently no-op, whereas {@code setLaterRunnable} is the
 * platform's own documented-in-bytecode hook for exactly this "after this insertion settles" need
 * (used internally by {@code CodeCompletionHandlerBase} itself).
 */
public class DbStepCompletionContributor extends CompletionContributor {

    // "present in " (or "not present in ") followed by whatever's typed so far (possibly nothing) -
    // singular/plural/negation pairing mirrors GherkinPsiUtil.DB_PRESENT_STEP.
    private static final Pattern PRESENT_IN_PREFIX = Pattern.compile(
            "(?:I\\s+)?(?:these values are|this value is)(?:\\s+not)? present in\\s*(.*)", Pattern.CASE_INSENSITIVE);

    // Matches while still typing inside an open join(/rejoin(/saveToContext( call - no closing ")" yet.
    private static final Pattern FUNCTION_OPEN = Pattern.compile(
            "(join|rejoin|saveToContext)\\(([^)]*)$", Pattern.CASE_INSENSITIVE);

    // The column rejoin(...) always reverse-joins against on the step's own table - see the class doc.
    private static final String REJOIN_TARGET_COLUMN = "id";

    public DbStepCompletionContributor() {
        extend(CompletionType.BASIC,
               PlatformPatterns.psiElement().inside(GherkinStep.class),
               new DbStepCompletionProvider());
    }

    // -------------------------------------------------------------------------

    private static final class DbStepCompletionProvider extends CompletionProvider<CompletionParameters> {

        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {

            PsiElement position = parameters.getPosition();
            GherkinStep step = PsiTreeUtil.getParentOfType(position, GherkinStep.class);
            if (step == null || !GherkinPsiUtil.isDbPresentStep(step)) return;

            GherkinTableCell cell = PsiTreeUtil.getParentOfType(position, GherkinTableCell.class);
            if (cell == null) {
                completeTableName(step, result);
            } else {
                completeHeaderCell(step, cell, result);
            }
        }

        private void completeTableName(@NotNull GherkinStep step, @NotNull CompletionResultSet result) {
            String stepName = step.getName();
            if (stepName == null) return;
            Matcher m = PRESENT_IN_PREFIX.matcher(truncateAtCaret(stepName).trim());
            if (!m.matches()) return;

            List<String> tables = resolveTables(step.getProject());
            if (tables.isEmpty()) return;

            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(m.group(1).trim()));
            for (String table : tables) {
                prefixed.addElement(LookupElementBuilder.create(table)
                        .withInsertHandler(DbStepCompletionProvider::onTableNameInserted));
            }
        }

        private void completeHeaderCell(@NotNull GherkinStep step, @NotNull GherkinTableCell cell,
                                        @NotNull CompletionResultSet result) {
            GherkinTable table = step.getTable();
            GherkinTableRow row = PsiTreeUtil.getParentOfType(cell, GherkinTableRow.class);
            if (table == null || row == null || row != table.getHeaderRow()) return;

            String tableName = GherkinPsiUtil.getDbTableName(step);
            if (tableName == null) return;

            String beforeCursor = truncateAtCaret(cell.getText());
            Matcher fn = FUNCTION_OPEN.matcher(beforeCursor);
            if (!fn.matches()) {
                offerPlainCellCompletions(step, tableName, beforeCursor.trim(), result);
                return;
            }

            String func = fn.group(1);
            String argsSoFar = fn.group(2);

            if ("rejoin".equalsIgnoreCase(func)) {
                completeRejoinArgs(step, tableName, argsSoFar, result);
                return;
            }

            int comma = argsSoFar.indexOf(',');
            if (comma < 0) {
                offerColumnsForArg1(step, tableName, func, argsSoFar.trim(), result);
                return;
            }
            if (!"join".equalsIgnoreCase(func)) return; // saveToContext's 2nd arg is free-form text

            String arg2SoFar = argsSoFar.substring(comma + 1);
            int dot = arg2SoFar.indexOf('.');
            if (dot < 0) {
                offerTablesForJoinArg2(step, arg2SoFar.trim(), result);
            } else {
                String targetTable = arg2SoFar.substring(0, dot).trim();
                offerColumnsForArg2(step, targetTable, arg2SoFar.substring(dot + 1).trim(), result);
            }
        }

        /**
         * {@code rejoin(table1.column1, column2)} - unlike {@code join}, argument 1 is itself
         * dotted ({@code table1.column1}), so this has its own comma/dot parsing rather than
         * sharing {@code offerColumnsForArg1}/{@code offerTablesForJoinArg2}.
         */
        private void completeRejoinArgs(@NotNull GherkinStep step, @NotNull String tableName,
                                        @NotNull String argsSoFar, @NotNull CompletionResultSet result) {
            int comma = argsSoFar.indexOf(',');
            if (comma < 0) {
                int dot = argsSoFar.indexOf('.');
                if (dot < 0) {
                    offerReferencingTables(step, tableName, argsSoFar.trim(), result);
                } else {
                    String table1 = argsSoFar.substring(0, dot).trim();
                    offerReferencingColumns(step, tableName, table1, argsSoFar.substring(dot + 1).trim(), result);
                }
                return;
            }

            // Argument 2: any column of table1 (the table named before the dot in argument 1),
            // unrestricted - same "finishes the cell" shape as a plain column or join's arg 2.
            String arg1 = argsSoFar.substring(0, comma);
            int dot = arg1.indexOf('.');
            if (dot < 0) return; // malformed - argument 1 should already be "table1.column1" by now
            String table1 = arg1.substring(0, dot).trim();
            offerColumnsForArg2(step, table1, argsSoFar.substring(comma + 1).trim(), result);
        }

        private void offerPlainCellCompletions(@NotNull GherkinStep step, @NotNull String tableName,
                                               @NotNull String typed, @NotNull CompletionResultSet result) {
            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(typed));
            for (ColumnInfo column : resolveColumns(step.getProject(), tableName)) {
                prefixed.addElement(LookupElementBuilder.create(column.name())
                        .withIcon(AllIcons.Nodes.Parameter)
                        .withInsertHandler(DbStepCompletionProvider::onCellSpecFinished));
            }
            prefixed.addElement(LookupElementBuilder.create("join")
                    .withPresentableText("join(...)")
                    .withTailText("  column, table.column", true)
                    .withInsertHandler(DbStepCompletionProvider::onFunctionTemplateSelected));
            prefixed.addElement(LookupElementBuilder.create("saveToContext")
                    .withPresentableText("saveToContext(...)")
                    .withTailText("  column, contextParamName", true)
                    .withInsertHandler(DbStepCompletionProvider::onFunctionTemplateSelected));
            prefixed.addElement(LookupElementBuilder.create("rejoin")
                    .withPresentableText("rejoin(...)")
                    .withTailText("  table.column, column", true)
                    .withInsertHandler(DbStepCompletionProvider::onFunctionTemplateSelected));
        }

        /** {@code rejoin(}'s argument 1 table part - only tables with a column referencing this table's id. */
        private void offerReferencingTables(@NotNull GherkinStep step, @NotNull String tableName,
                                            @NotNull String typed, @NotNull CompletionResultSet result) {
            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(typed));
            List<String> tables = new ArrayList<>();
            for (WorkflowDataSourceBridge.ReverseForeignKey ref
                    : resolveReferencingColumns(step.getProject(), tableName, REJOIN_TARGET_COLUMN)) {
                if (!tables.contains(ref.table())) tables.add(ref.table());
            }
            for (String table1 : tables) {
                prefixed.addElement(LookupElementBuilder.create(table1)
                        .withInsertHandler((ctx, item) -> insertAndScheduleFollowUp(ctx, ".")));
            }
        }

        /** {@code rejoin(}'s argument 1 column part - only table1's column(s) that reference this table's id. */
        private void offerReferencingColumns(@NotNull GherkinStep step, @NotNull String tableName,
                                             @NotNull String table1, @NotNull String typed,
                                             @NotNull CompletionResultSet result) {
            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(typed));
            for (WorkflowDataSourceBridge.ReverseForeignKey ref
                    : resolveReferencingColumns(step.getProject(), tableName, REJOIN_TARGET_COLUMN)) {
                if (!ref.table().equalsIgnoreCase(table1)) continue;
                prefixed.addElement(LookupElementBuilder.create(ref.column())
                        .withIcon(AllIcons.Nodes.Parameter)
                        .withInsertHandler((ctx, item) -> insertAndScheduleFollowUp(ctx, ", ")));
            }
        }

        private void offerColumnsForArg1(@NotNull GherkinStep step, @NotNull String tableName, @NotNull String func,
                                         @NotNull String typed, @NotNull CompletionResultSet result) {
            boolean isJoin = "join".equalsIgnoreCase(func);
            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(typed));
            for (ColumnInfo column : resolveColumns(step.getProject(), tableName)) {
                prefixed.addElement(LookupElementBuilder.create(column.name())
                        .withIcon(AllIcons.Nodes.Parameter)
                        .withInsertHandler(isJoin
                                ? (ctx, item) -> onJoinArg1Selected(ctx, column)
                                : DbStepCompletionProvider::onSaveToContextArg1Selected));
            }
        }

        private void offerTablesForJoinArg2(@NotNull GherkinStep step, @NotNull String typed,
                                            @NotNull CompletionResultSet result) {
            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(typed));
            for (String table : resolveTables(step.getProject())) {
                prefixed.addElement(LookupElementBuilder.create(table)
                        .withInsertHandler((ctx, item) -> insertAndScheduleFollowUp(ctx, ".")));
            }
        }

        private void offerColumnsForArg2(@NotNull GherkinStep step, @NotNull String targetTable,
                                         @NotNull String typed, @NotNull CompletionResultSet result) {
            CompletionResultSet prefixed = result.withPrefixMatcher(new PlainPrefixMatcher(typed));
            for (ColumnInfo column : resolveColumns(step.getProject(), targetTable)) {
                prefixed.addElement(LookupElementBuilder.create(column.name())
                        .withIcon(AllIcons.Nodes.Parameter)
                        .withInsertHandler(DbStepCompletionProvider::onCellSpecFinished));
            }
        }

        // -------------------------------------------------------------------------
        // Insert handlers
        // -------------------------------------------------------------------------

        private static void onTableNameInserted(@NotNull InsertionContext context, @NotNull LookupElement item) {
            context.commitDocument();
            PsiElement pos = context.getFile().findElementAt(context.getStartOffset());
            GherkinStep step = PsiTreeUtil.getParentOfType(pos, GherkinStep.class, false);
            if (step == null || step.getTable() != null) return; // already has a table - leave it alone

            Document document = context.getDocument();
            String indent = DbStepTableUtil.indentFor(document, step);
            DbStepTableUtil.createTableSkeleton(document, context.getEditor(), context.getTailOffset(), indent);
        }

        private static void onFunctionTemplateSelected(@NotNull InsertionContext context, @NotNull LookupElement item) {
            Document document = context.getDocument();
            int tail = context.getTailOffset();
            document.insertString(tail, "()");
            context.getEditor().getCaretModel().moveToOffset(tail + 1); // between the parens
            scheduleFollowUpPopup(context);
        }

        private static void onJoinArg1Selected(@NotNull InsertionContext context, @NotNull ColumnInfo column) {
            String text = column.foreignKeyTable() != null ? ", " + column.foreignKeyTable() + "." : ", ";
            insertAndScheduleFollowUp(context, text);
        }

        private static void onSaveToContextArg1Selected(@NotNull InsertionContext context, @NotNull LookupElement item) {
            // No follow-up popup (the 2nd argument is a free-form new context-variable name the
            // user types manually) and no next-column jump either - this cell isn't finished yet.
            Document document = context.getDocument();
            int tail = context.getTailOffset();
            document.insertString(tail, ", ");
            context.getEditor().getCaretModel().moveToOffset(tail + 2);
        }

        /** Selecting a column that completes a cell's whole spec - jump to the next header column. */
        private static void onCellSpecFinished(@NotNull InsertionContext context, @NotNull LookupElement item) {
            context.commitDocument();
            PsiElement pos = context.getFile().findElementAt(context.getStartOffset());
            GherkinTableCell cell = PsiTreeUtil.getParentOfType(pos, GherkinTableCell.class, false);
            GherkinStep step = PsiTreeUtil.getParentOfType(pos, GherkinStep.class, false);
            GherkinTable table = step == null ? null : step.getTable();
            GherkinTableRow headerRow = cell == null ? null : PsiTreeUtil.getParentOfType(cell, GherkinTableRow.class);
            if (cell == null || table == null || headerRow == null || headerRow != table.getHeaderRow()) return;

            List<GherkinTableCell> headerCells = headerRow.getPsiCells();
            int index = headerCells == null ? -1 : headerCells.indexOf(cell);
            if (index < 0) return;

            Editor editor = context.getEditor();
            if (index + 1 < headerCells.size()) {
                editor.getCaretModel().moveToOffset(headerCells.get(index + 1).getTextRange().getStartOffset());
                return;
            }

            // Last column - widen every row by one empty column so the table stays rectangular.
            // Data rows first, bottom-to-top: each row's own end-offset is only ever affected by an
            // edit strictly after it in the document, so widening later rows first keeps every
            // still-to-be-used offset valid without needing RangeMarkers.
            Document document = context.getDocument();
            List<GherkinTableRow> dataRows = table.getDataRows();
            for (int i = dataRows.size() - 1; i >= 0; i--) {
                document.insertString(dataRows.get(i).getTextRange().getEndOffset(), "  |");
            }
            int headerEnd = headerRow.getTextRange().getEndOffset();
            document.insertString(headerEnd, "  |");
            editor.getCaretModel().moveToOffset(headerEnd + 1);
        }

        private static void insertAndScheduleFollowUp(@NotNull InsertionContext context, @NotNull String text) {
            Document document = context.getDocument();
            int tail = context.getTailOffset();
            document.insertString(tail, text);
            context.getEditor().getCaretModel().moveToOffset(tail + text.length());
            scheduleFollowUpPopup(context);
        }

        private static void scheduleFollowUpPopup(@NotNull InsertionContext context) {
            context.setLaterRunnable(() ->
                    AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor()));
        }

        // -------------------------------------------------------------------------
        // Schema resolution
        // -------------------------------------------------------------------------

        private static @NotNull List<String> resolveTables(@NotNull Project project) {
            WorkflowDataSourceBridge bridge = firstBridge();
            if (bridge == null) return List.of();
            PumpkinDataSourceRef dataSource = resolveDataSource(project, bridge);
            return dataSource == null ? List.of() : bridge.listTables(project, dataSource);
        }

        private static @NotNull List<ColumnInfo> resolveColumns(@NotNull Project project, @NotNull String tableName) {
            WorkflowDataSourceBridge bridge = firstBridge();
            if (bridge == null) return List.of();
            PumpkinDataSourceRef dataSource = resolveDataSource(project, bridge);
            return dataSource == null ? List.of() : bridge.listColumns(project, dataSource, tableName);
        }

        private static @NotNull List<WorkflowDataSourceBridge.ReverseForeignKey> resolveReferencingColumns(
                @NotNull Project project, @NotNull String targetTable, @NotNull String targetColumn) {
            WorkflowDataSourceBridge bridge = firstBridge();
            if (bridge == null) return List.of();
            PumpkinDataSourceRef dataSource = resolveDataSource(project, bridge);
            return dataSource == null ? List.of()
                    : bridge.findColumnsReferencing(project, dataSource, targetTable, targetColumn);
        }

        private static @Nullable WorkflowDataSourceBridge firstBridge() {
            List<WorkflowDataSourceBridge> bridges = WorkflowDataSourceBridge.EP_NAME.getExtensionList();
            return bridges.isEmpty() ? null : bridges.get(0);
        }

        private static @Nullable PumpkinDataSourceRef resolveDataSource(@NotNull Project project,
                                                                        @NotNull WorkflowDataSourceBridge bridge) {
            String id = PumpkinSettingsState.getInstance(project).workflowAssertionDataSourceId;
            if (id == null) return null;
            return bridge.listDataSources(project).stream()
                    .filter(ds -> ds.id().equals(id))
                    .findFirst()
                    .orElse(null);
        }

        /** Text up to the dummy identifier IntelliJ injects at the caret during completion. */
        private static @NotNull String truncateAtCaret(@NotNull String text) {
            int idx = text.indexOf(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED);
            return idx >= 0 ? text.substring(0, idx) : text;
        }
    }
}
