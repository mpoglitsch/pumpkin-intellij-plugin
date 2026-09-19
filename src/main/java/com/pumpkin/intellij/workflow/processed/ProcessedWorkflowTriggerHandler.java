package com.pumpkin.intellij.workflow.processed;

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
import com.pumpkin.intellij.workflow.PumpkinDataSourceRef;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "dwf:" shortcut: typing this (optionally after a step keyword) chains two filterable popups -
 * first every configured datasource (same {@link WorkflowDataSourceBridge} seam {@code db:}/
 * {@code wf:} already use), then whether to also generate outgoing-payload steps - inserting each
 * choice plus a trailing {@code :} in turn. The user then types the numeric workflow-instance id
 * by hand and presses Enter, handled by {@link ProcessedWorkflowEnterHandler}. Mirrors {@code
 * com.pumpkin.intellij.dbstep.DbStepTriggerHandler}'s own shape.
 */
public class ProcessedWorkflowTriggerHandler extends TypedHandlerDelegate {

    private static final Pattern DWF_TRIGGER = Pattern.compile(
            "(?:(Given|When|Then|And|But|\\*)\\s+)?dwf:", Pattern.CASE_INSENSITIVE);

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c != ':' || !(file instanceof GherkinFile)) return Result.CONTINUE;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int lineNumber = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(lineNumber);
        String linePrefix = document.getText(new TextRange(lineStart, caretOffset)).strip();

        Matcher m = DWF_TRIGGER.matcher(linePrefix);
        if (m.matches()) {
            openDataSourcePopup(project, editor, lineStart, caretOffset, m.group(1));
        }
        return Result.CONTINUE;
    }

    private void openDataSourcePopup(@NotNull Project project, @NotNull Editor editor, int lineStart,
                                     int caretOffset, @Nullable String keyword) {
        if (DumbService.isDumb(project)) return;

        List<WorkflowDataSourceBridge> bridges = WorkflowDataSourceBridge.EP_NAME.getExtensionList();
        if (bridges.isEmpty()) return;

        List<PumpkinDataSourceRef> dataSources = bridges.get(0).listDataSources(project);
        if (dataSources.isEmpty()) return;

        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(dataSources)
                .setTitle("Choose Datasource")
                .setRenderer(SimpleListCellRenderer.create("", PumpkinDataSourceRef::displayName))
                .setNamerForFiltering(PumpkinDataSourceRef::displayName)
                .setItemChosenCallback(dataSource -> onDataSourceChosen(project, editor, lineStart, caretOffset, keyword, dataSource))
                .createPopup()
                .showInBestPositionFor(editor);
    }

    private void onDataSourceChosen(@NotNull Project project, @NotNull Editor editor, int lineStart, int caretOffset,
                                    @Nullable String keyword, @NotNull PumpkinDataSourceRef dataSource) {
        Document document = editor.getDocument();
        String resolvedKeyword = keyword != null ? keyword + " " : "";
        String newText = resolvedKeyword + "dwf:" + dataSource.displayName() + ":";

        WriteCommandAction.runWriteCommandAction(project, "Insert dwf: Datasource", null, () ->
                document.replaceString(lineStart, caretOffset, newText));

        int newCaret = lineStart + newText.length();
        editor.getCaretModel().moveToOffset(newCaret);

        openIncludePayloadPopup(project, editor, newCaret);
    }

    private void openIncludePayloadPopup(@NotNull Project project, @NotNull Editor editor, int caretOffset) {
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(List.of("true", "false"))
                .setTitle("Create Outgoing-Payload Steps?")
                .setRenderer(SimpleListCellRenderer.create("", s -> s))
                .setItemChosenCallback(choice -> onIncludePayloadChosen(project, editor, caretOffset, choice))
                .createPopup()
                .showInBestPositionFor(editor);
    }

    private void onIncludePayloadChosen(@NotNull Project project, @NotNull Editor editor, int caretOffset,
                                        @NotNull String choice) {
        Document document = editor.getDocument();
        String newText = choice + ":";

        WriteCommandAction.runWriteCommandAction(project, "Insert dwf: Payload Choice", null, () ->
                document.insertString(caretOffset, newText));

        editor.getCaretModel().moveToOffset(caretOffset + newText.length());
    }
}
