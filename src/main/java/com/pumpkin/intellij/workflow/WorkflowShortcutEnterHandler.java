package com.pumpkin.intellij.workflow;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pressing Enter right after typing {@code wf:<workflow id>} on its own line (optionally after a
 * step keyword, e.g. {@code * wf:123}) - similar in spirit to Java's {@code psvm} live template -
 * generates that workflow's assertion steps directly into the document instead of inserting a
 * plain newline. See {@link WorkflowShortcutTypedHandler} for the visual hint shown while typing,
 * and {@link WorkflowShortcutGenerator} for what happens after Enter is pressed.
 *
 * <p>Deliberately not implemented via a completion popup: IntelliJ's completion auto-popup only
 * triggers after characters it considers identifier-like, and a bare {@code :} doesn't qualify -
 * so a completion-based version of this never reliably opened. Intercepting the physical Enter
 * keystroke here has no such dependency on that heuristic.
 */
public class WorkflowShortcutEnterHandler implements EnterHandlerDelegate {

    static final Pattern TRIGGER =
            Pattern.compile("(?:(?:Given|When|Then|And|But|\\*)\\s+)?wf:(\\d+)", Pattern.CASE_INSENSITIVE);

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

        long workflowId = Long.parseLong(m.group(1));
        WorkflowShortcutGenerator.trigger(file.getProject(), document, lineStart, caretOffset, workflowId);

        return Result.Stop;
    }

    /**
     * Explicitly implemented rather than left to {@code EnterHandlerDelegate}'s own default: on
     * at least one real IntelliJ build (reported on Windows, the same class of issue as {@code
     * PumpkinFloatingToolbarProvider.getAutoHideable()}), that interface doesn't supply a default
     * implementation of this method at all, and the platform fails to even construct this
     * extension with {@code AbstractMethodError: ... does not define or inherit an implementation
     * of ... postProcessEnter(...)}. This handler has no post-processing to do - the whole
     * generation happens in {@link #preprocessEnter} - so this is a plain no-op.
     */
    @Override
    public Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext dataContext) {
        return Result.Continue;
    }
}
