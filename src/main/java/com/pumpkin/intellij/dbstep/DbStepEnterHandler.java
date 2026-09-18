package com.pumpkin.intellij.dbstep;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.pumpkin.intellij.util.GherkinPsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;
import org.jetbrains.plugins.cucumber.psi.GherkinStep;

/**
 * Pressing Enter right after typing a {@code these values are present in &lt;table&gt;} step with
 * no table below it yet inserts a single-column header+blank-row skeleton, caret in the header
 * cell - matching {@link com.pumpkin.intellij.context.StoreContextValuesEnterHandler}'s own
 * established shape for a different step. Selecting a table-name completion for this same step
 * (see {@code DbStepCompletionContributor}) triggers the identical skeleton via {@link
 * DbStepTableUtil#createTableSkeleton}, so the outcome is the same whichever way the user gets
 * there.
 */
public class DbStepEnterHandler implements EnterHandlerDelegate {

    @Override
    public Result preprocessEnter(@NotNull PsiFile file, @NotNull Editor editor,
            @NotNull Ref<Integer> caretOffsetRef, @NotNull Ref<Integer> caretAdvance,
            @NotNull DataContext dataContext, @Nullable EditorActionHandler originalHandler) {

        if (!(file instanceof GherkinFile)) return Result.Continue;

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();

        PsiElement element = file.findElementAt(Math.max(0, caretOffset - 1));
        GherkinStep step = PsiTreeUtil.getParentOfType(element, GherkinStep.class, false);
        if (step == null || !GherkinPsiUtil.isDbPresentStep(step)) return Result.Continue;
        if (step.getTable() != null) return Result.Continue; // already has a table - leave it alone

        String indent = DbStepTableUtil.indentFor(document, step);
        DbStepTableUtil.createTableSkeleton(document, editor, caretOffset, indent);

        return Result.Stop;
    }

    /**
     * Explicitly implemented rather than left to {@code EnterHandlerDelegate}'s own default - see
     * {@code WorkflowShortcutEnterHandler.postProcessEnter}'s doc comment for the Windows
     * {@code AbstractMethodError} this avoids. This handler has no post-processing to do - the
     * whole generation happens in {@link #preprocessEnter} - so this is a plain no-op.
     */
    @Override
    public Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext dataContext) {
        return Result.Continue;
    }
}
