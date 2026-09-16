package com.pumpkin.intellij.contextparam;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.cucumber.psi.GherkinFile;

/**
 * Forces the standard completion popup open immediately after typing {@code %} inside a Gherkin
 * file, so {@link ContextParameterCompletionContributor} can offer {@code %contextParameter%}
 * suggestions - {@code %} isn't an identifier character, so the platform's own default auto-popup
 * (which only triggers on identifier-part characters) never fires for it on its own.
 */
public class ContextParameterTypedHandler extends TypedHandlerDelegate {

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (c == '%' && file instanceof GherkinFile) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
        }
        return Result.CONTINUE;
    }
}
