package com.pumpkin.intellij.workflow;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.regex.Matcher;

/**
 * Shows a lightweight "press Enter to generate" hint at the caret while typing a
 * {@code wf:<workflow id>} shortcut line, so it's visible that something will happen - the actual
 * generation runs from {@link WorkflowShortcutEnterHandler} on the Enter keystroke itself, not
 * from this hint. Purely informational: showing/not showing the hint has no effect on whether
 * Enter is intercepted.
 */
public class WorkflowShortcutTypedHandler extends TypedHandlerDelegate {

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (!(file instanceof GherkinFile)) return Result.CONTINUE;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int lineNumber = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(lineNumber);
        String linePrefix = document.getText(new TextRange(lineStart, caretOffset)).strip();

        Matcher m = WorkflowShortcutEnterHandler.TRIGGER.matcher(linePrefix);
        if (m.matches()) {
            HintManager.getInstance().showInformationHint(editor,
                    "Press Enter to generate the workflow assertion for workflow " + m.group(1));
        }
        return Result.CONTINUE;
    }
}
