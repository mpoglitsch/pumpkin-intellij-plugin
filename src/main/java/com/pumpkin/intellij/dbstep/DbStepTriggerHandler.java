package com.pumpkin.intellij.dbstep;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.ui.SimpleListCellRenderer;
import com.pumpkin.intellij.endpoint.SwitchMethodEditor;
import com.pumpkin.intellij.settings.PumpkinSettingsState;
import com.pumpkin.intellij.workflow.PumpkinDataSourceRef;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "db:" shortcut: typing this (optionally after a step keyword, e.g. {@code Then db:}) opens a
 * filterable popup of every table in the configured datasource's schema (same {@link
 * WorkflowDataSourceBridge} seam and {@link PumpkinSettingsState#workflowAssertionDataSourceId}
 * setting {@code DbStepCompletionContributor}'s own completion already uses) - selecting one
 * generates the whole {@code <keyword> these values are present in <table>} step, plus its
 * header+blank-row table skeleton (via {@link DbStepTableUtil#createTableSkeleton}, the same
 * helper {@code DbStepEnterHandler} uses), caret in the first header cell ready to type a
 * column/function spec. Mirrors {@code ApiStepTriggerHandler}'s own shape for the {@code api:}
 * shortcut.
 */
public class DbStepTriggerHandler extends TypedHandlerDelegate {

    private static final Pattern DB_TRIGGER = Pattern.compile(
            "(?:(Given|When|Then|And|But|\\*)\\s+)?db:", Pattern.CASE_INSENSITIVE);

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c != ':' || !(file instanceof GherkinFile)) return Result.CONTINUE;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int lineNumber = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(lineNumber);
        String linePrefix = document.getText(new TextRange(lineStart, caretOffset)).strip();

        Matcher m = DB_TRIGGER.matcher(linePrefix);
        if (m.matches()) {
            openTablePopup(project, editor, lineStart, caretOffset, m.group(1));
        }
        return Result.CONTINUE;
    }

    private void openTablePopup(@NotNull Project project, @NotNull Editor editor, int lineStart,
                                int caretOffset, @Nullable String keyword) {
        if (DumbService.isDumb(project)) return;

        List<WorkflowDataSourceBridge> bridges = WorkflowDataSourceBridge.EP_NAME.getExtensionList();
        if (bridges.isEmpty()) return;
        WorkflowDataSourceBridge bridge = bridges.get(0);

        String dataSourceId = PumpkinSettingsState.getInstance(project).workflowAssertionDataSourceId;
        PumpkinDataSourceRef dataSource = dataSourceId == null ? null : bridge.listDataSources(project).stream()
                .filter(ds -> ds.id().equals(dataSourceId))
                .findFirst()
                .orElse(null);
        if (dataSource == null) return;

        List<String> tables = bridge.listTables(project, dataSource);
        if (tables.isEmpty()) return;

        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(tables)
                .setTitle("Choose Table")
                .setRenderer(SimpleListCellRenderer.create("", t -> t))
                .setNamerForFiltering(t -> t)
                .setItemChosenCallback(table -> insertStep(project, editor, lineStart, caretOffset, keyword, table))
                .createPopup()
                .showInBestPositionFor(editor);
    }

    /** Same shape as StoreContextValuesEnterHandler's own step-plus-table generation. */
    private void insertStep(@NotNull Project project, @NotNull Editor editor, int lineStart, int caretOffset,
                            @Nullable String keyword, @NotNull String table) {
        Document document = editor.getDocument();
        // No explicit keyword typed (bare "db:" at the start of the line) defaults to "*",
        // matching every DB-step example already established for this feature.
        String resolvedKeyword = keyword != null ? keyword : "*";
        String stepText = resolvedKeyword + " these values are present in " + table;

        WriteCommandAction.runWriteCommandAction(project, "Insert DB Step", null, () -> {
            document.replaceString(lineStart, caretOffset, stepText);

            int stepEnd = lineStart + stepText.length();
            String indent = SwitchMethodEditor.indentOf(document.getText(), lineStart);
            DbStepTableUtil.createTableSkeleton(document, editor, stepEnd, indent);
        });
    }
}
