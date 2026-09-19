package com.pumpkin.intellij.workflow.processed;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.pumpkin.intellij.workflow.PumpkinDataSourceRef;
import com.pumpkin.intellij.workflow.WorkflowDataSourceBridge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pressing Enter right after fully typing {@code dwf:<datasource>:<true|false>:<workflow instance
 * id>} (optionally after a step keyword) - built up via {@link ProcessedWorkflowTriggerHandler}'s
 * two chained popups plus the free-typed numeric id - generates that processed workflow's
 * assertion steps. See {@link ProcessedWorkflowAssertionGenerator} for what happens after Enter is
 * pressed; mirrors {@code WorkflowShortcutEnterHandler}'s own shape and reasoning for intercepting
 * Enter directly rather than a completion popup.
 */
public class ProcessedWorkflowEnterHandler implements EnterHandlerDelegate {

    static final Pattern TRIGGER = Pattern.compile(
            "(?:(?:Given|When|Then|And|But|\\*)\\s+)?dwf:([^:]+):(true|false):(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public Result preprocessEnter(@NotNull PsiFile file, @NotNull Editor editor,
            @NotNull Ref<Integer> caretOffsetRef, @NotNull Ref<Integer> caretAdvance,
            @NotNull DataContext dataContext, @Nullable EditorActionHandler originalHandler) {

        if (!(file instanceof GherkinFile)) return Result.Continue;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int lineNumber = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(lineNumber);
        String linePrefix = document.getText(new TextRange(lineStart, caretOffset)).strip();

        Matcher m = TRIGGER.matcher(linePrefix);
        if (!m.matches()) return Result.Continue;

        Project project = file.getProject();
        String dataSourceName = m.group(1);
        boolean includePayloadSteps = Boolean.parseBoolean(m.group(2));
        long workflowInstanceId = Long.parseLong(m.group(3));

        List<WorkflowDataSourceBridge> bridges = WorkflowDataSourceBridge.EP_NAME.getExtensionList();
        if (bridges.isEmpty()) {
            HintManager.getInstance().showErrorHint(editor,
                    "Generating processed workflow assertions requires the Database Tools plugin.");
            return Result.Continue;
        }
        WorkflowDataSourceBridge bridge = bridges.get(0);

        PumpkinDataSourceRef dataSource = bridge.listDataSources(project).stream()
                .filter(ds -> ds.displayName().equalsIgnoreCase(dataSourceName))
                .findFirst()
                .orElse(null);
        if (dataSource == null) {
            HintManager.getInstance().showErrorHint(editor, "Unknown datasource: " + dataSourceName);
            return Result.Continue;
        }

        VirtualFile featureFile = file.getVirtualFile();
        if (featureFile == null) return Result.Continue;

        ProcessedWorkflowAssertionGenerator.trigger(project, document, lineStart, caretOffset,
                bridge, dataSource, includePayloadSteps, workflowInstanceId, featureFile);

        return Result.Stop;
    }

    /**
     * Explicitly implemented rather than left to {@code EnterHandlerDelegate}'s own default - see
     * {@code WorkflowShortcutEnterHandler}'s own doc comment on the exact same `AbstractMethodError`
     * this guards against on at least one real IntelliJ build.
     */
    @Override
    public Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext dataContext) {
        return Result.Continue;
    }
}
